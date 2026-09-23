package com.example.starlight.download;

import com.example.starlight.ModsApi.RemoteMod;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.ArchiveUtils;
import com.example.starlight.util.DebugLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * HTTP 下载引擎（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>职责：
 * <ul>
 *   <li>单文件下载（流式 + 断点续传 + HTTP/2 → HTTP/1.1 降级重试）；</li>
 *   <li>大文件（≥4MB 且服务器支持 Range）多线程分块并发下载后合并；</li>
 *   <li>CurseForge 资源「直链 → 镜像候选」回退；</li>
 *   <li>文件大小 / Range 支持探测；</li>
 *   <li>统一的取消语义（{@link #CANCELLED_MESSAGE}）。</li>
 * </ul>
 *
 * <p>全部为无状态静态方法，不依赖 JavaFX，可在任意后台线程调用。
 * 进度通过 {@link IntConsumer}（0-100）回调，取消通过 {@link AtomicBoolean} 传入。
 */
public final class HttpDownloadEngine {

    private HttpDownloadEngine() {
    }

    public static final java.net.http.HttpClient HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            // 重定向手动处理：MCIM 镜像的 302 Location 头带未编码的原始中文文件名
            // （如「枪战.mrpack」），HttpClient 自动跟随重建 URI 时直接抛
            // Illegal character in path；手动跟随前先做百分号再编码（见 resolveRedirect）
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();

    /**
     * HTTP/1.1 备用客户端：部分 CDN（如 Modrinth CDN）与 HTTP/2 兼容性差，
     * 大文件下载时容易出现 Connection reset，降级到 HTTP/1.1 可避免该问题
     */
    public static final java.net.http.HttpClient HTTP_CLIENT_FALLBACK = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();

    /** 大文件分块下载阈值：文件大于该值时启用多线程分块并发下载（突破 CDN 单连接限速） */
    private static final long MIN_CHUNK_SIZE = 4L * 1024 * 1024; // 4MB

    /**
     * 把任意 URL（含未编码的中文/空格等非法 URI 字符）修成合法 URI 字符串：
     * 仅对非 ASCII 与 URI 非法字符做百分号编码，已编码序列（%xx）保持原样。
     * <p>MCIM 镜像的 302 Location 头带原始中文文件名，
     * {@link URI#create(String)} 对非 ASCII 直接抛 Illegal character in path，
     * 这里在跟随重定向前修一遍。
     */
    public static String sanitizeUrl(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        // HTTP 头按 RFC 规范是 Latin-1：Java HttpClient 把 Location 里的非 ASCII 字节
        // 解成 0x80-0xFF 的 Latin-1 字符（「枪战」→ æ\x9Eª æ\x88\x98），而服务器期待的
        // 正是这些字节的百分号编码（%E6%9E...）。逐字符映射回单字节再编码即可还原；
        // 已是合法 %xx 序列的直接保留，避免双重编码。
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // 已编码序列（% + 2 个十六进制）原样保留
            if (c == '%' && i + 2 < raw.length()
                    && isHex(raw.charAt(i + 1)) && isHex(raw.charAt(i + 2))) {
                bytes.write('%');
                bytes.write(raw.charAt(++i));
                bytes.write(raw.charAt(++i));
                continue;
            }
            if (c <= 0x7F) {
                bytes.write(c);
            } else if (c <= 0xFF) {
                // Latin-1 误解码出来的高位字符：映射回原始单字节
                bytes.write((byte) c);
            } else {
                // 真正的 Unicode 字符（含中文）：按 UTF-8 拆字节
                byte[] u = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bytes.write(u, 0, u.length);
            }
        }
        byte[] rawBytes = bytes.toByteArray();
        StringBuilder sb = new StringBuilder(rawBytes.length + 16);
        for (int i = 0; i < rawBytes.length; i++) {
            int b = rawBytes[i] & 0xFF;
            char ch = (char) b;
            // '%' 已在第一段循环里验证过是合法 %xx 前缀，此处必须放行，否则双重编码
            boolean safe = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || "-._~!$&'()*+,;=:/?#[]@%".indexOf(ch) >= 0;
            if (safe) {
                sb.append(ch);
            } else {
                sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** URI.create 的安全封装：先 sanitize 再建，构建失败返回 null */
    public static java.net.URI safeUri(String raw) {
        try {
            return java.net.URI.create(sanitizeUrl(raw));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 手动跟随重定向：返回最终响应（3xx 时取 Location 重新请求，最多跟 5 跳）。
     * Location 里的非 ASCII 字符（MCIM 302 带原始中文）先经 {@link #sanitizeUrl} 修复。
     */
    private static java.net.http.HttpResponse<java.io.InputStream> sendFollowingRedirects(
            java.net.http.HttpClient client, java.net.http.HttpRequest firstRequest) throws IOException, InterruptedException {
        java.net.http.HttpResponse<java.io.InputStream> resp =
                client.send(firstRequest, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        int redirects = 0;
        while (resp.statusCode() >= 300 && resp.statusCode() < 400 && redirects < 5) {
            String location = resp.headers().firstValue("Location").orElse(null);
            // 必须消费并关闭上一跳的空 body，避免连接泄漏
            try (java.io.InputStream is = resp.body()) { is.transferTo(java.io.OutputStream.nullOutputStream()); }
            if (location == null || location.isBlank()) {
                throw new IOException("重定向缺少 Location 头 (HTTP " + resp.statusCode() + "): " + resp.uri());
            }
            // 相对 Location 按当前 URI 解析；非 ASCII 先修复
            java.net.URI base = resp.uri();
            java.net.URI next = base.resolve(sanitizeUrl(location));
            java.net.http.HttpRequest nextReq = java.net.http.HttpRequest.newBuilder()
                    .uri(next)
                    .timeout(firstRequest.timeout().orElse(java.time.Duration.ofMinutes(10)))
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .GET()
                    .build();
            resp = client.send(nextReq, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            redirects++;
        }
        return resp;
    }

    // ==================== 内容 CDN 的 MCIM 镜像 ====================

    /**
     * 内容文件 CDN → MCIM 镜像前缀（与 md 的 MCIM 端点映射一致）：
     * <ul>
     *   <li>{@code cdn.modrinth.com} → {@code mod.mcimirror.top}</li>
     *   <li>{@code edge.forgecdn.net} / {@code mediafilez.forgecdn.net} → {@code mod.mcimirror.top}</li>
     * </ul>
     * 以前这些 CDN 只有 CurseForge 走「直链优先、失败才镜像」，Modrinth 索性直链；
     * 整合包主体/清单内文件更是完全没有镜像。国内直连官方 CDN 慢就是这里，
     * 现在统一成「镜像优先 → 官方直链兜底」，与 API 侧（ModrinthRepository / CurseForgeAPI）策略一致。
     */
    private static final String MCIM_MIRROR = "https://mod.mcimirror.top";

    /** CDN 镜像熔断：连续 2 次失败后 60 秒内直接走官方直链（与 API 侧同参数） */
    private static final com.example.starlight.util.MirrorCircuitBreaker CDN_MIRROR_BREAKER =
            new com.example.starlight.util.MirrorCircuitBreaker(2, 60_000L);

    /** 单个内容 URL 的 MCIM 镜像地址；无需镜像（非 MCIM 覆盖的 CDN）时返回 null */
    private static String mcimMirrorOf(String url) {
        if (url == null) return null;
        for (String host : new String[]{
                "https://cdn.modrinth.com",
                "https://edge.forgecdn.net",
                "https://mediafilez.forgecdn.net"}) {
            if (url.startsWith(host)) {
                return MCIM_MIRROR + url.substring(host.length());
            }
        }
        return null;
    }

    /**
     * 生成内容文件的候选下载地址：镜像优先（熔断期内跳过）→ 官方直链兜底。
     * 非 MCIM 覆盖的地址原样返回。
     */
    public static java.util.List<String> contentCandidates(String url) {
        java.util.List<String> candidates = new java.util.ArrayList<>(2);
        String mirror = mcimMirrorOf(url);
        if (mirror != null && CDN_MIRROR_BREAKER.allowMirror()) {
            candidates.add(mirror);
        }
        if (!candidates.contains(url)) {
            candidates.add(url);
        }
        return candidates;
    }

    /** 供进度探测（totalBytes）用：优先探测镜像地址，与真实下载的首选一致 */
    public static String firstContentCandidate(String url) {
        java.util.List<String> candidates = contentCandidates(url);
        return candidates.isEmpty() ? url : candidates.get(0);
    }

    /**
     * 「镜像优先 → 官方兜底」的内容文件下载（模组 / 资源包 / 光影 / 整合包主体与清单内文件共用）。
     *
     * <p>切换候选前删除上一候选留下的半截文件：跨主机的断点续传没有意义，
     * 若镜像返回的是错误页被写成前缀，续传会把错误字节保留进最终文件。
     * 同一候选自身的重试仍走内部断点续传。
     *
     * @param cancel 取消标志，可为 null
     */
    public static void downloadWithMirrors(String url, Path dest,
                                            java.util.function.IntConsumer progress,
                                            java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        java.util.List<String> candidates = contentCandidates(url);
        IOException lastErr = null;
        for (String candidate : candidates) {
            if (isCancelled(cancel)) throw new IOException(CANCELLED_MESSAGE);
            try {
                downloadFileWithProgress(candidate, dest, progress, cancel);
                if (candidate.startsWith(MCIM_MIRROR)) CDN_MIRROR_BREAKER.recordSuccess();
                return;
            } catch (IOException e) {
                if (CANCELLED_MESSAGE.equals(e.getMessage())) throw e;
                if (candidate.startsWith(MCIM_MIRROR)) CDN_MIRROR_BREAKER.recordFailure();
                lastErr = e;
                // 换源前清掉半截文件，避免错误前缀被下一候选续传
                try { Files.deleteIfExists(dest); } catch (IOException ignored) {}
            }
        }
        throw lastErr != null ? lastErr : new IOException("下载失败: " + url);
    }

    /**
     * 下载内容文件（模组 / 资源包 / 光影包 / 世界 / 整合包主体）。
     *
     * <p>Modrinth 与 CurseForge 的文件直链都落到各自 CDN，国内直连常较慢或不可达，
     * 统一按「MCIM 镜像 → 官方直链」的顺序尝试（镜像连续失败会熔断，冷却期内直接走官方）。
     *
     * @param ver      选中的版本（其 {@code file.url} 为官方直链）
     * @param dest     目标文件
     * @param progress 0-100 进度回调，可为 null
     */
    public static void downloadContentFile(RemoteMod.Version ver, Path dest,
                                            java.util.function.IntConsumer progress) throws IOException {
        downloadContentFile(ver, dest, progress, null);
    }

    /** 带取消支持的 {@link #downloadContentFile}（进度弹窗「取消」按钮依赖此重载） */
    public static void downloadContentFile(RemoteMod.Version ver, Path dest,
                                            java.util.function.IntConsumer progress,
                                            java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        String url = ver.getFile().getUrl();
        downloadWithMirrors(url, dest, progress, cancel);
    }

    /** 下载缓冲区大小（64KB，远大于默认 8KB，减少系统调用显著提速） */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * 下载文件并报告 0-100 进度
     * 大文件（≥4MB 且服务器支持 Range）自动使用多线程分块并发下载；
     * 失败自动重试（最多 3 次，HTTP/2 失败后降级 HTTP/1.1），单线程模式支持断点续传
     */
    public static void downloadFileWithProgress(String url, Path dest, java.util.function.IntConsumer progress)
            throws IOException {
        downloadFileWithProgress(url, dest, progress, null);
    }

    /**
     * 带取消支持的下载：{@code cancel} 置位后立即中断（不再重试），供下载进度弹窗的「取消」使用。
     *
     * @param cancel 取消标志，可为 null 表示不可取消
     */
    public static void downloadFileWithProgress(String url, Path dest, java.util.function.IntConsumer progress,
                                                 java.util.concurrent.atomic.AtomicBoolean cancel)
            throws IOException {
        final int maxAttempts = 3;
        // 第 1 次走 HTTP/2，后续走 HTTP/1.1（避免 RST_STREAM / Connection reset）
        java.net.http.HttpClient[] clients = {HTTP_CLIENT, HTTP_CLIENT_FALLBACK, HTTP_CLIENT_FALLBACK};
        IOException lastErr = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // 取消后直接抛出，不进入重试分支（否则会被当成网络失败重下一遍）
            if (isCancelled(cancel)) throw new IOException(CANCELLED_MESSAGE);
            try {
                if (attempt == 0) {
                    // 探测文件大小：大文件走多线程分块下载，可成倍提升速度
                    long size = probeFileSize(clients[0], url);
                    if (size >= MIN_CHUNK_SIZE) {
                        downloadChunked(clients[0], url, dest, size, progress, cancel);
                        return;
                    }
                }
                downloadStreaming(clients[attempt], url, dest, progress, cancel);
                return;
            } catch (IOException e) {
                if (CANCELLED_MESSAGE.equals(e.getMessage())) throw e;
                lastErr = e;
                if (attempt < maxAttempts - 1) {
                    try { Thread.sleep(500L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        throw lastErr != null ? lastErr : new IOException("下载失败: " + url);
    }

    /** 下载取消的统一异常文案（调用方据此区分「用户取消」与「网络失败」） */
    public static final String CANCELLED_MESSAGE = "已取消";

    private static boolean isCancelled(java.util.concurrent.atomic.AtomicBoolean cancel) {
        return cancel != null && cancel.get();
    }
    /**
     * 探测文件大小与 Range 支持（GET + Range: bytes=0-0）
     *
     * @return 文件总大小；不支持 Range 或探测失败时返回 -1
     */
    public static long probeFileSize(java.net.http.HttpClient client, String url) {
        try {
            java.net.URI uri = safeUri(url);
            if (uri == null) return -1;
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .GET()
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> resp = sendFollowingRedirects(client, req);
            DebugLog.http(true, "GET", url, 0, 0, null, -1);
            // 必须消费并关闭 body，避免连接泄漏
            try (java.io.InputStream is = resp.body()) {
                is.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (resp.statusCode() == 206) {
                String cr = resp.headers().firstValue("Content-Range").orElse(null);
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash > 0) {
                        long size = Long.parseLong(cr.substring(slash + 1).trim());
                        DebugLog.http(false, null, url, resp.statusCode(), size, "[文件探测 Content-Range] " + cr, -1);
                        return size;
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** 多线程分块下载（分块写入临时文件，全部完成后合并） */
    private static void downloadChunked(java.net.http.HttpClient client, String url, Path dest,
                                        long size, java.util.function.IntConsumer progress) throws IOException {
        downloadChunked(client, url, dest, size, progress, null);
    }

    private static void downloadChunked(java.net.http.HttpClient client, String url, Path dest,
                                        long size, java.util.function.IntConsumer progress,
                                        java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        // 线程数取设置页「并发下载数」配置，运行时可调整
        int threads = Math.max(1, DownloadSettings.getDownloadThreads());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "sl-dl-chunk");
            t.setDaemon(true);
            return t;
        });
        Path partDir = null;
        try {
            partDir = Files.createTempDirectory("sl-dl-");
            long chunk = size / threads;
            java.util.concurrent.atomic.AtomicLong downloaded = new java.util.concurrent.atomic.AtomicLong(0);
            java.util.concurrent.atomic.AtomicInteger lastPct = new java.util.concurrent.atomic.AtomicInteger(-1);
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                long start = i * chunk;
                long end = (i == threads - 1) ? size - 1 : start + chunk - 1;
                if (start > end) break;
                Path part = partDir.resolve("part_" + i);
                futures.add(pool.submit((java.util.concurrent.Callable<Void>) () -> {
                    downloadRange(client, url, part, start, end, downloaded, size, lastPct, progress, cancel);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new IOException("分块下载失败: " + cause.getMessage(), cause);
                }
            }
            // 按顺序合并分块为最终文件
            try (var out = Files.newOutputStream(dest,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int i = 0; i < futures.size(); i++) {
                    Files.copy(partDir.resolve("part_" + i), out);
                }
            }
            if (progress != null) progress.accept(100);
        } finally {
            pool.shutdownNow();
            if (partDir != null) {
                try { ArchiveUtils.deleteRecursively(partDir); } catch (Exception ignored) {}
            }
        }
    }

    /** 下载单个分块到 part 文件（带 HTTP/2 → HTTP/1.1 降级重试） */
    private static void downloadRange(java.net.http.HttpClient client, String url, Path part,
                                      long start, long end,
                                      java.util.concurrent.atomic.AtomicLong downloaded, long size,
                                      java.util.concurrent.atomic.AtomicInteger lastPct,
                                      java.util.function.IntConsumer progress,
                                      java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        final int maxAttempts = 3;
        java.net.http.HttpClient[] clients = {client, HTTP_CLIENT_FALLBACK, HTTP_CLIENT_FALLBACK};
        IOException lastErr = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (isCancelled(cancel)) throw new IOException(CANCELLED_MESSAGE);
            try {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(sanitizeUrl(url)))
                        .timeout(java.time.Duration.ofMinutes(10))
                        .header("User-Agent", AppConfig.USER_AGENT)
                        .header("Range", "bytes=" + start + "-" + end)
                        .GET()
                        .build();
                java.net.http.HttpResponse<java.io.InputStream> resp;
                try {
                    resp = sendFollowingRedirects(clients[attempt], req);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("分块下载被中断", e);
                }
                DebugLog.http(true, "GET", url + " [Range " + start + "-" + end + "]", 0, 0, null, -1);
                if (resp.statusCode() != 206) {
                    // 服务器忽略 Range 返回 200，不能分块，抛异常让上层走单线程流式
                    try (java.io.InputStream is = resp.body()) {
                        is.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                    throw new IOException("服务器不支持分块下载 (HTTP " + resp.statusCode() + ")");
                }
                try (java.io.InputStream in = resp.body();
                     var out = Files.newOutputStream(part,
                             java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (isCancelled(cancel)) throw new IOException(CANCELLED_MESSAGE);
                        out.write(buffer, 0, n);
                        if (progress != null && size > 0) {
                            long total = downloaded.addAndGet(n);
                            int pct = (int) (total * 100 / size);
                            int lp = lastPct.get();
                            if (pct > lp && lastPct.compareAndSet(lp, pct)) {
                                progress.accept(pct);
                            }
                        }
                    }
                    DebugLog.http(false, null, url, 206, downloaded.get(), "[二进制分块]", -1);
                }
                return;
            } catch (IOException e) {
                lastErr = e;
                if (attempt < maxAttempts - 1) {
                    try { Thread.sleep(500L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        throw lastErr != null ? lastErr : new IOException("分块下载失败: " + url);
    }

    /** 单次流式下载（支持断点续传） */
    private static void downloadStreaming(java.net.http.HttpClient client, String url, Path dest,
                                          java.util.function.IntConsumer progress) throws IOException {
        downloadStreaming(client, url, dest, progress, null);
    }

    private static void downloadStreaming(java.net.http.HttpClient client, String url, Path dest,
                                          java.util.function.IntConsumer progress,
                                          java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        long existing = Files.exists(dest) ? Files.size(dest) : 0L;
        java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(sanitizeUrl(url)))
                .timeout(java.time.Duration.ofMinutes(10))
                .header("User-Agent", AppConfig.USER_AGENT)
                .GET();
        if (existing > 0) {
            rb.header("Range", "bytes=" + existing + "-");
        }
        java.net.http.HttpResponse<java.io.InputStream> response;
        try {
            response = sendFollowingRedirects(client, rb.build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
        DebugLog.http(true, "GET", url, 0, 0, null, -1);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        boolean resumed = existing > 0 && response.statusCode() == 206;
        long rangeFrom = 0;
        if (resumed) {
            rangeFrom = existing;
        } else if (existing > 0) {
            // 服务器不支持断点续传，从头下载
            Files.deleteIfExists(dest);
        }
        long chunkTotal = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        long overallTotal = resumed && chunkTotal >= 0 ? rangeFrom + chunkTotal : chunkTotal;
        try (java.io.InputStream in = response.body();
             var out = resumed
                     ? Files.newOutputStream(dest,
                     java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                     : Files.newOutputStream(dest,
                     java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long read = rangeFrom;
            int n;
            int lastPct = -1;
            while ((n = in.read(buffer)) != -1) {
                if (isCancelled(cancel)) throw new IOException(CANCELLED_MESSAGE);
                out.write(buffer, 0, n);
                read += n;
                if (overallTotal > 0) {
                    int pct = (int) (read * 100 / overallTotal);
                    if (pct != lastPct) {
                        lastPct = pct;
                        if (progress != null) progress.accept(pct);
                    }
                }
            }
            DebugLog.http(false, null, url, response.statusCode(), read, "[二进制文件]", -1);
        }
        if (progress != null) progress.accept(100);
    }
}
