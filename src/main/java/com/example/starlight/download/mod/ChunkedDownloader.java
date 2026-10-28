package com.example.starlight.download.mod;

import com.example.starlight.download.DownloadSettings;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.DebugLog;
import com.example.starlight.util.NetTrafficStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 大文件分块并发下载器（2026-09-30 A/B 双池方案，B 池大文件专用）。
 *
 * <p>把单个大文件按 {@link #chunkCount(int)} 拆成 4~8 段，用 {@code Range: bytes=from-to} 发起
 * <b>多连接并行</b>下载各段到分片临时文件，全部成功后顺序合并、原子替换目标文件。
 * 若源不支持 Range（对 Range 请求回 200 全量而非 206），返回 {@link ChunkOutcome#NO_RANGE}，
 * 由调用方回退到单连接 {@code downloadFile}，保证兼容不退化。
 *
 * <p>停滞 / 超时语义与补全链路一致：连接超时与读超时取 {@link DownloadSettings}，
 * lastResort 最后候选放宽（同一套分级），任一段失败即整体返回可重试，由等待池换源重试。
 */
public final class ChunkedDownloader {

    private static final Logger log = LoggerFactory.getLogger(ChunkedDownloader.class);

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_REDIRECT_HOPS = 5;

    private ChunkedDownloader() {
    }

    /** 分块数按文件大小自适应：1~4MB → 4 段，4~8MB → 6 段，≥8MB → 8 段 */
    public static int chunkCount(int sizeBytes) {
        if (sizeBytes >= 8 * 1024 * 1024) {
            return 8;
        }
        if (sizeBytes >= 4 * 1024 * 1024) {
            return 6;
        }
        return 4;
    }

    /**
     * 分块下载一个文件到目标路径。
     *
     * @param urlStr       候选下载 URL（若源支持 Range 才能分块）
     * @param targetPath   最终文件路径（合并成功后原子替换）
     * @param expectedSize 文件期望大小（来自 assets 索引；未知大小无法拆段，回退 NO_RANGE）
     * @param cancel       取消标志
     * @param lastResort   是否最后可用候选（放宽读超时，与单连接下载同一套分级）
     */
    public static ChunkOutcome download(String urlStr, Path targetPath, int expectedSize,
                                        AtomicBoolean cancel, boolean lastResort) {
        if (isCancelled(cancel)) {
            return ChunkOutcome.CANCELLED;
        }
        if (expectedSize <= 0) {
            return ChunkOutcome.NO_RANGE;
        }
        Path dir = targetPath.getParent();
        String baseName = targetPath.getFileName().toString();
        int chunks = chunkCount(expectedSize);
        long startMs = System.currentTimeMillis();
        try {
            Files.createDirectories(dir);
            cleanupChunks(dir, baseName, chunks);

            // 并发下载各段 //
            long seg = (expectedSize + chunks - 1) / chunks;
            List<Callable<ChunkResult>> calls = new ArrayList<>(chunks);
            for (int i = 0; i < chunks; i++) {
                long from = i * seg;
                long to = Math.min((long) expectedSize - 1L, from + seg - 1L);
                final int index = i;
                calls.add(() -> downloadChunk(urlStr, dir.resolve(baseName + ".chunk." + index + ".tmp"),
                        from, to, index, cancel, lastResort));
            }
            ExecutorService pool = Executors.newFixedThreadPool(calls.size(), r -> {
                Thread t = new Thread(r, "sl-chunk-worker");
                t.setDaemon(true);
                return t;
            });
            List<Future<ChunkResult>> results;
            try {
                results = pool.invokeAll(calls);
            }
            finally {
                pool.shutdownNow();
            }

            for (Future<ChunkResult> future : results) {
                ChunkResult r = future.get();
                if (r.outcome == ChunkOutcome.CANCELLED) {
                    cleanupChunks(dir, baseName, chunks);
                    return ChunkOutcome.CANCELLED;
                }
                if (r.outcome == ChunkOutcome.NO_RANGE) {
                    // 源不支持 Range：清分片，整体回退单连接下载
                    cleanupChunks(dir, baseName, chunks);
                    return ChunkOutcome.NO_RANGE;
                }
                if (r.outcome != ChunkOutcome.OK) {
                    cleanupChunks(dir, baseName, chunks);
                    return ChunkOutcome.RETRY;
                }
            }

            // 全部段成功：顺序合并 → 原子替换 //
            Path merged = dir.resolve(baseName + ".merged.tmp");
            try (OutputStream out = Files.newOutputStream(merged,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int i = 0; i < chunks; i++) {
                    Path chunk = dir.resolve(baseName + ".chunk." + i + ".tmp");
                    if (!Files.isRegularFile(chunk)) {
                        cleanupChunks(dir, baseName, chunks);
                        return ChunkOutcome.RETRY;
                    }
                    Files.copy(chunk, out);
                }
            }
            Files.move(merged, targetPath, StandardCopyOption.REPLACE_EXISTING);
            cleanupChunks(dir, baseName, chunks);
            log.info("分块下载完成: {} ({} 段, {}ms)", targetPath.getFileName(), chunks,
                    System.currentTimeMillis() - startMs);
            return ChunkOutcome.OK;
        }
        catch (Exception e) {
            log.debug("分块下载失败: {} ({})", urlStr, e.toString());
            cleanupChunks(dir, baseName, chunks);
            return ChunkOutcome.RETRY;
        }
    }

    /**
     * 下载单个分片 {@code bytes=from-to} 到分片文件。
     *
     * <p>沿用补全链路单连接下载的语义：手动处理重定向、读超时停滞判定、0 字节段判定；
     * 返回 {@link ChunkOutcome#NO_RANGE} 表示源不支持 Range（200 全量响应）。
     */
    private static ChunkResult downloadChunk(String urlStr, Path chunkFile, long from, long to,
                                             int index, AtomicBoolean cancel, boolean lastResort) {
        String current = urlStr;
        int hop = 0;
        HttpURLConnection conn = null;
        try {
            while (true) {
                URL url = new URL(current);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(DownloadSettings.getConnectTimeoutMs());
                conn.setReadTimeout(readTimeoutMs(lastResort));
                conn.setRequestProperty("User-Agent", AppConfig.USER_AGENT);
                conn.setRequestProperty("Range", "bytes=" + from + "-" + to);

                DebugLog.http(true, "GET", current, 0, 0L, null, -1L);
                int code = conn.getResponseCode();

                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    disconnect(conn);
                    conn = null;
                    if (location == null || location.isBlank() || ++hop > MAX_REDIRECT_HOPS) {
                        DebugLog.http(false, null, current, code, 0L, "重定向异常", -1L);
                        return new ChunkResult(ChunkOutcome.FAIL, false);
                    }
                    current = new URL(url, sanitizeRedirect(location)).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    DebugLog.http(false, null, current, code, 0L, null, -1L);
                    return new ChunkResult(code == 404 || code == 410
                            ? ChunkOutcome.FAIL : ChunkOutcome.RETRY, false);
                }
                if (code == 200) {
                    // 源不支持 Range：整文件全量返回。若继续写分片，各段会各自收到整份文件互相覆盖
                    DebugLog.http(false, null, current, code, 0L, "[不支持Range]", -1L);
                    return new ChunkResult(ChunkOutcome.NO_RANGE, true);
                }
                // 206 Partial Content
                long totalRead = 0L;
                long windowStartMs = System.currentTimeMillis();
                long bytesInWindow = 0L;
                long windowMs = DownloadSettings.getStallWindowMs(lastResort);
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(chunkFile,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (isCancelled(cancel)) {
                            DebugLog.http(false, null, current, code, totalRead, "[已取消]", -1L);
                            return new ChunkResult(ChunkOutcome.CANCELLED, false);
                        }
                        out.write(buffer, 0, read);
                        NetTrafficStats.recordBytes(read); // 全局流量统计（下载速度悬浮窗）
                        totalRead += read;
                        bytesInWindow += read;
                        // 速率型停滞判定：数据在动就继续（慢但可用），滴流/不动才判卡死。
                        // 与单连接路径同一套语义（DownloadSettings.isStalled），避免首包慢被误杀。
                        long nowMs = System.currentTimeMillis();
                        long elapsed = nowMs - windowStartMs;
                        if (elapsed >= windowMs) {
                            if (DownloadSettings.isStalled(elapsed, bytesInWindow)) {
                                log.warn("分片停滞（{}s 窗口内仅 {} 字节），放弃该源: {} 段 {}",
                                        elapsed / 1000L, bytesInWindow, urlStr, index);
                                deleteQuietly(chunkFile);
                                return new ChunkResult(ChunkOutcome.RETRY, false);
                            }
                            windowStartMs = nowMs;
                            bytesInWindow = 0L;
                        }
                    }
                }
                DebugLog.http(false, null, current, code, totalRead, "[分片]", -1L);
                long expected = to - from + 1L;
                if (totalRead != expected) {
                    log.warn("分片长度不符: {} (期望 {} 字节, 实际 {} 字节)", chunkFile, expected, totalRead);
                    return new ChunkResult(ChunkOutcome.RETRY, false);
                }
                return new ChunkResult(ChunkOutcome.OK, false);
            }
        }
        catch (InterruptedIOException e) {
            long stalledSec = readTimeoutMs(lastResort) / 1000L;
            log.warn("分片停滞（{}s 无数据）: {} 段 {} (来源: 资源补全分块)", stalledSec, urlStr, index);
            deleteQuietly(chunkFile);
            return new ChunkResult(ChunkOutcome.RETRY, false);
        }
        catch (IOException e) {
            log.debug("分片下载失败: {} 段 {} ({})", urlStr, index, e.toString());
            deleteQuietly(chunkFile);
            return new ChunkResult(ChunkOutcome.RETRY, false);
        }
        finally {
            disconnect(conn);
        }
    }

    /**
     * socket 读超时：作为「连接彻底无响应」的兜底，<b>不承担</b>慢速判定。
     *
     * <p>2026-10-20 修正：原来首试用 5 秒（{@code getFirstTryStallMs()}），与实测 5.7~8.85 秒的
     * 首包延迟重叠，导致合法慢响应在建连阶段就被判停滞。现在统一用 {@link DownloadSettings#getStallTimeoutMs()}
     * （默认 15s，用户可调），慢速/滴流由正文循环里的速率窗口（{@link DownloadSettings#isStalled}）负责。
     */
    private static int readTimeoutMs(boolean lastResort) {
        int base = DownloadSettings.getStallTimeoutMs();
        return lastResort ? base * 2 : base;
    }

    private static void cleanupChunks(Path dir, String baseName, int chunks) {
        try {
            Files.deleteIfExists(dir.resolve(baseName + ".chunk.merged.tmp"));
            for (int i = 0; i < chunks; i++) {
                Files.deleteIfExists(dir.resolve(baseName + ".chunk." + i + ".tmp"));
            }
        }
        catch (IOException ignored) {
            // 清理失败不影响主流程；同名分片会在下次下载开始时被再次清理
        }
    }

    private static boolean isCancelled(AtomicBoolean cancel) {
        return cancel != null && cancel.get();
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        }
        catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    private static void disconnect(HttpURLConnection c) {
        if (c != null) {
            try {
                c.disconnect();
            }
            catch (Exception ignored) {
                // disconnect 失败无补救手段
            }
        }
    }

    /** 修复重定向 Location 里的非 ASCII 字符（与补全链路同一逻辑，避免跨类依赖） */
    private static String sanitizeRedirect(String location) {
        if (location == null || location.isBlank()) return location;
        boolean ascii = true;
        for (int i = 0; i < location.length(); i++) {
            if (location.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        if (ascii) return location;
        StringBuilder sb = new StringBuilder(location.length() + 16);
        for (byte b : location.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if (v < 0x80) {
                sb.append((char) v);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit((v >> 4) & 0xF, 16)))
                  .append(Character.toUpperCase(Character.forDigit(v & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** 单段下载结果 */
    private static final class ChunkResult {
        final ChunkOutcome outcome;
        final boolean noRange;

        ChunkResult(ChunkOutcome outcome, boolean noRange) {
            this.outcome = outcome;
            this.noRange = noRange;
        }
    }
}
