package com.example.starlight.download.mod;

import com.example.starlight.util.DebugLog;
import com.example.starlight.util.NetTrafficStats;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分块（对标 PCL NetThread）。
 *
 * <p>PCL 用链表组织分块，Java 用 {@code List<DownloadPiece>} 即可。
 * 每个分块：
 * <ul>
 *   <li>记录 {@code [start, end]} 字节区间（end  inclusive；-1 表示未知大小开到尾）、
 *       已完成字节数 {@link #done}；</li>
 *   <li>写<b>独立</b>的 {@code .part} 临时文件（{@code {gameDir}/.download-cache/{fileHash}/piece-{序号}.part}），
 *       天然无锁、无并发写冲突；</li>
 *   <li>挂载一个当前源 {@link #mountedSource}；</li>
 *   <li>首线程不带 Range 探测总大小；非首线程一律带 {@code Range: bytes=start-end}。</li>
 * </ul>
 *
 * <p>读缓冲沿用 64KB（与 HttpDownloadEngine.BUFFER_SIZE 一致，减少系统调用）。
 *
 * <p>HTTP 原语复用 {@link HttpDownloadEngine#HTTP_CLIENT} / {@link HttpDownloadEngine#HTTP_CLIENT_FALLBACK}
 * （HTTP/2→1.1 降级双客户端）与 {@link HttpDownloadEngine#safeUri(String)} /
 * {@link HttpDownloadEngine#sanitizeUrl(String)}（重定向 Location 非 ASCII 修复）。
 * 本包不 import newui/AppConfig，UA 自管常量。
 */
public final class DownloadPiece {

    /** 读缓冲：64KB（沿用现有 Java 引擎参数） */
    public static final int BUFFER_SIZE = 64 * 1024;

    /** 首线程 UA（不依赖 newui/AppConfig.USER_AGENT） */
    private static final String USER_AGENT = "StarlightLauncher/1.0";

    /** 重定向最大跳数 */
    private static final int MAX_REDIRECT_HOPS = 5;

    /** 分块状态 */
    public enum State {
        WAITING, CONNECTING, DOWNLOADING, DONE, FAILED, CANCELLED
    }

    /** 块序号（part 文件名用，不用随机数——便于一期可选跨重启续传） */
    private final int index;

    /** 区间起点（字节，inclusive） */
    private final long start;

    /** 区间终点（字节，inclusive；-1 表示未知大小开到尾；动态分裂时会被收紧） */
    private long end;

    /** 已完成字节数（Atomic：限速/统计线程与 IO 线程共享） */
    private final AtomicLong done = new AtomicLong(0);

    /** 独立 .part 临时文件 */
    private final Path partFile;

    /** 是否首线程（首线程不带 Range，负责探测 Content-Length） */
    private final boolean firstThread;

    /** 当前挂载源（volatile：换源时整体替换） */
    private volatile DownloadSource mountedSource;

    private volatile State state = State.WAITING;

    public DownloadPiece(int index, long start, long end, Path partFile, boolean firstThread) {
        this.index = index;
        this.start = start;
        this.end = end;
        this.partFile = partFile;
        this.firstThread = firstThread;
    }

    public int getIndex() {
        return index;
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    /** 动态分裂时收紧本块终点（把尾部让给新切出的分块） */
    public void setEnd(long end) {
        this.end = end;
    }

    public long getDone() {
        return done.get();
    }

    public void setDone(long done) {
        this.done.set(done);
    }

    public Path getPartFile() {
        return partFile;
    }

    public boolean isFirstThread() {
        return firstThread;
    }

    public DownloadSource getMountedSource() {
        return mountedSource;
    }

    public void setMountedSource(DownloadSource mountedSource) {
        this.mountedSource = mountedSource;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    /** 剩余未完成量 = (end-start+1) - done；end=-1 时按已观察量估 */
    public long remaining(long fileSize) {
        long effectiveEnd = end >= 0 ? end : (fileSize >= 0 ? fileSize - 1 : Long.MAX_VALUE);
        long total = effectiveEnd - start + 1;
        return Math.max(0, total - done.get());
    }

    /**
     * 执行本块下载（由 DownloadCoordinator 的全局线程池调用）。
     *
     * @param task      所属单文件任务（回调：拿到字节 / 探测到总大小 / 完成上报）
     * @param limiter   全局令牌桶限速
     */
    public void run(DownloadFileTask task, GlobalSpeedLimiter limiter) throws IOException {
        state = State.CONNECTING;
        DownloadSource src = mountedSource;
        if (src == null) {
            throw new IOException("分块无可用源: piece-" + index);
        }
        // HttpURLConnection（HTTP/1.1）而非 java.net.http.HttpClient（h2）——2026-10-11 实测结论：
        // 同一 client.jar 地址浏览器可下载，HttpClient「响应头即达、正文静默」挂死（多次复现，
        // 有 329B 截断与 0 字节两种形态）；项目里走 HUC 的链路（模组分块下载等）无此病。
        // HUC 自带 setReadTimeout（与停滞看门狗同源），静默连接在阈值内强制抛错，走换源/整任务重试。
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) URI.create(src.getUrl()).toURL().openConnection();
            conn.setConnectTimeout(com.example.starlight.download.DownloadSettings.getConnectTimeoutMs());
            conn.setReadTimeout(com.example.starlight.download.DownloadSettings.getStallTimeoutMs());
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (!firstThread && start > 0) {
                conn.setRequestProperty("Range",
                        end >= 0 ? ("bytes=" + start + "-" + end) : ("bytes=" + start + "-"));
            }
            int status = conn.getResponseCode();
            // 分块级网络埋点：调试日志里能看到协调器引擎的每次连接与收包（此前该链路完全隐形）
            DebugLog.http(true, "GET", src.getUrl() + rangeSuffix(), 0, 0, null, -1);

            if (status == 416) {
                // 416 只是本次 Range 不合法（如远端文件已变），不算地址段失效
                src.recordFailure(new IOException("HTTP 416"));
                throw new SourceDisabledException("Range 不合法: HTTP 416 " + src.getUrl());
            }
            if (status < 200 || status >= 300) {
                // 404/410：镜像「这个地址段不存在」的确定性信号，计入容器级健康表
                if (status == 404 || status == 410) {
                    SourceHealth.getInstance().record404(src.getUrl(), status, "分块下载");
                }
                task.onSourceHttpError(src, status);
                throw new SourceDisabledException("源被禁用: HTTP " + status + " " + src.getUrl());
            }
            if (!firstThread && status == 200) {
                task.onRangeNotSupported(src);
                throw new RangeNotSupportedException("服务器忽略 Range (HTTP 200): " + src.getUrl());
            }
            if (!firstThread && status != 206) {
                src.recordFailure(new IOException("HTTP " + status));
                task.onSourceHttpError(src, status);
                throw new SourceDisabledException("源异常: HTTP " + status);
            }
            if (firstThread) {
                // 首线程：不带 Range，从 Content-Length 探测总大小（大小不符在 onFileSizeKnown 内切源）
                task.onFileSizeKnown(conn.getContentLengthLong(), src);
            }

            // 流式写入独立 .part 文件（首线程覆盖写；续传/换源由上层重建 part）。
            // HUC setReadTimeout 已覆盖「正文静默」；慢速掐线继续负责「慢但在动」的涓流。
            long pieceStartNs = System.nanoTime();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(partFile,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[BUFFER_SIZE];
                long lastPackNs = System.nanoTime();
                long bytesInGap = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (task.isCancelled()) throw new IOException("已取消");
                    limiter.acquire(n);
                    NetTrafficStats.recordBytes(n); // 全局流量统计（下载速度悬浮窗）
                    out.write(buf, 0, n);
                    done.addAndGet(n);
                    task.onBytes(n);
                    src.recordSuccess(); // 拿到字节即清零
                    // 慢速掐线（两包间隔 >5s 且间隔内 <1KB 且非单线程源）
                    long nowNs = System.nanoTime();
                    long gapMs = (nowNs - lastPackNs) / 1_000_000L;
                    if (RetryPolicy.isTooSlow(gapMs, bytesInGap, src.isSingleThread())) {
                        throw new IOException("慢速掐线: gap=" + gapMs + "ms");
                    }
                    // 速度下限护栏：开跑 30s 平均仍低于 ~26KB/s 判为「半死源」掐断换源。
                    // 2026-10-11 实测 BMCLAPI 病态之一是 3-5KB/s 涓流——不触发静默超时也不触发
                    // 慢速掐线（包间隔 <5s），41MB 要拖 2 小时；正常弱网（>100KB/s）不受影响。
                    long elapsedMs = (nowNs - pieceStartNs) / 1_000_000L;
                    if (elapsedMs > 30_000L && done.get() < 768L * 1024L) {
                        throw new IOException("源速度过低（30s 仅 "
                                + done.get() / 1024 + "KB），掐断换源");
                    }
                    lastPackNs = nowNs;
                    bytesInGap = n;
                }
            }
            state = State.DONE;
            DebugLog.http(false, null, src.getUrl() + rangeSuffix(), status,
                    done.get(), "[分块" + index + "完成]", -1);
        } catch (IOException e) {
            // 失败归类（驱动换源）：连接阶段失败 / 正文停滞（读超时、慢速掐线）计入
            // SourceHealth 超时计数（3 次拉黑 60s），其余计入源 failCount（建连超时自适应）。
            // 404/410 与 Range 异常在上面的分支里已各自记录过不重复计；用户取消不算源的错
            if (!("已取消".equals(e.getMessage()) || task.isCancelled())
                    && !(e instanceof SourceDisabledException)
                    && !(e instanceof RangeNotSupportedException)) {
                SourceHealth.getInstance().recordTimeout(src.getUrl(), e.getMessage());
                src.recordFailure(e);
            }
            DebugLog.http(false, null, src.getUrl() + rangeSuffix(), 0,
                    done.get(), "[分块" + index + "失败] " + e.getMessage(), -1);
            state = State.FAILED;
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 本分块的请求区间描述（首线程无 Range；日志定位用） */
    private String rangeSuffix() {
        return firstThread ? "" : " [Range " + start + "-" + end + "]";
    }

    @Override
    public String toString() {
        return "Piece#" + index + "[" + start + "," + (end < 0 ? "∞" : end) + "] done=" + done.get() + " " + state;
    }
}
