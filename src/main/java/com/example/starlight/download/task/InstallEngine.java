package com.example.starlight.download.task;

import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.mod.DownloadCoordinator;
import com.example.starlight.download.mod.DownloadFileTask;
import com.example.starlight.download.mod.DownloadSourceChain;
import com.example.starlight.download.mod.GlobalSpeedLimiter;
import com.example.starlight.download.source.DownloadProvider;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.DebugLog;
import com.example.starlight.version.VersionDownloadService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Minecraft 原版安装引擎
 * 流程: 获取版本JSON → 下载JAR → 下载libraries → SHA1校验
 */
public class InstallEngine {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(InstallEngine.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Gson GSON = new Gson();

    /** 进度回调: (percent 0-100, message) */
    public interface ProgressCallback { void onProgress(int pct, String msg); }

    /**
     * 安装原版 Minecraft（使用 DownloadProvider 替换硬编码镜像）
     * @param versionEntry   版本清单中的条目
     * @param gameDir        游戏根目录(.minecraft)
     * @param downloadProvider 下载提供者（Mojang 官方 / BMCLAPI 镜像等）
     * @param cb             进度回调
     */
    public static boolean installVanilla(VersionDownloadService.VersionInfo versionEntry,
                                          String gameDir, DownloadProvider downloadProvider,
                                          ProgressCallback cb) {
        return installVanilla(versionEntry, gameDir, downloadProvider, cb, null);
    }

    /**
     * 安装原版 Minecraft 到自定义版本目录
     * @param versionEntry   版本清单中的条目
     * @param gameDir        游戏根目录(.minecraft)
     * @param downloadProvider 下载提供者（Mojang 官方 / BMCLAPI 镜像等）
     * @param cb             进度回调
     * @param folderName     自定义版本文件夹名（null/空则使用版本号），同时改写 json 的 id 保持一致性
     */
    public static boolean installVanilla(VersionDownloadService.VersionInfo versionEntry,
                                          String gameDir, DownloadProvider downloadProvider,
                                          ProgressCallback cb, String folderName) {
        String vId = versionEntry.getId();
        String dirName = (folderName == null || folderName.isBlank()) ? vId : folderName.trim();
        Path verDir = Path.of(gameDir, "versions", dirName);
        try { Files.createDirectories(verDir); } catch (IOException e) { return fail(cb, "Failed to create directory: "+e); }

        // 1. 获取版本JSON（通过 DownloadProvider 注入 URL）
        report(cb, 5, "获取版本元数据...");
        String versionUrl = versionEntry.getUrl();
        String injectedUrl = downloadProvider.injectURL(versionUrl);
        String json = fetchString(injectedUrl);
        // 回退：若注入后的 URL 失败，尝试原始 URL
        if (json == null && !injectedUrl.equals(versionUrl)) {
            json = fetchString(versionUrl);
        }
        if (json == null) return fail(cb, "Failed to fetch version JSON");
        // 自定义目录名时改写 json 的 id，保证启动器按文件夹名识别；
        // 同时保留一份原版到标准版本目录，作为加载器（Fabric）的继承基底
        if (!dirName.equals(vId)) {
            try {
                Path stdDir = Path.of(gameDir, "versions", vId);
                Files.createDirectories(stdDir);
                Files.writeString(stdDir.resolve(vId + ".json"), json);
            } catch (IOException ignored) {}
            try {
                JsonObject root0 = GSON.fromJson(json, JsonObject.class);
                if (root0 != null) {
                    root0.addProperty("id", dirName);
                    json = root0.toString();
                }
            } catch (Exception ignored) {}
        }
        try { Files.writeString(verDir.resolve(dirName + ".json"), json); } catch (IOException e) {}

        JsonObject root = GSON.fromJson(json, JsonObject.class);

        // 2. 下载 client.jar（15→70）：按字节回报进度，进度条实时推进；
        //    调用方的进度回调在用户取消时会抛 CANCELLED_MESSAGE，从下载循环里穿透出来。
        //    sha1/size 传给下载引擎做落盘后校验与大小校验：BMCLAPI 实测会对大文件「吐出
        //    前 329B 后断流」，不校验的话残片会被当成功安装（2026-10-11 用户日志实证）
        report(cb, 15, "下载客户端JAR...");
        JsonObject downloads = root.getAsJsonObject("downloads");
        if (downloads == null) return fail(cb, "No downloads field");
        JsonObject clientObj = downloads.getAsJsonObject("client");
        if (clientObj == null) return fail(cb, "No client download URL");
        String jarUrl = jsonStr(clientObj, "url");
        if (jarUrl == null) return fail(cb, "No client download URL");
        String jarSha1 = jsonStr(clientObj, "sha1");
        long jarSize = optLong(clientObj, "size", -1L);
        Path jarPath = verDir.resolve(dirName + ".jar");
        if (!downloadFileWithProvider(jarUrl, jarPath, downloadProvider, jarSha1, jarSize,
                (pct, msg) -> report(cb, mapRange(pct, 0, 100, 15, 70), "下载客户端JAR " + pct + "%")))
            return fail(cb, "Client JAR download failed");
        // 自定义目录时同步标准版本目录的基底 jar（供 Fabric 等加载器继承）
        if (!dirName.equals(vId)) {
            try {
                Files.copy(jarPath, Path.of(gameDir, "versions", vId, vId + ".jar"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }

        // 3. 下载 libraries（70→95）：每个文件都回报一次进度（此前每 5 个才报一次，
        //    首次安装几十个库文件时状态栏长时间不动）
        report(cb, 70, "下载依赖库...");
        var libs = root.getAsJsonArray("libraries");
        if (libs != null && libs.size() > 0) {
            Path libDir = Path.of(gameDir, "libraries");
            int total = libs.size(), done = 0;
            for (int i = 0; i < total; i++) {
                JsonObject lib = libs.get(i).getAsJsonObject();
                if (!shouldDownloadLib(lib)) continue;
                JsonObject libDownloads = lib.getAsJsonObject("downloads");
                if (libDownloads == null) continue; // 无 downloads 字段的库（如部分 natives）跳过
                JsonObject artifact = libDownloads.getAsJsonObject("artifact");
                if (artifact == null) continue;
                String libUrl = jsonStr(artifact, "url");
                String libPath = jsonStr(artifact, "path");
                if (libUrl == null || libPath == null) continue;

                Path dest = libDir.resolve(libPath);
                if (!Files.exists(dest)) {
                    try { Files.createDirectories(dest.getParent()); } catch (IOException e) { continue; }
                    downloadFileWithProvider(libUrl, dest, downloadProvider,
                            jsonStr(artifact, "sha1"), optLong(artifact, "size", -1L), null);
                }
                done++;
                report(cb, mapRange(done, 0, Math.max(total, 1), 70, 95), "库文件: " + done + "/" + total);
            }
        }

        // 4. SHA1 校验客户端JAR（致命：残片 jar 装出去只会在启动游戏时报难查的加密错误）
        report(cb, 96, "校验文件完整性...");
        String sha1 = jsonStr(clientObj, "sha1");
        if (sha1 != null && !verifySha1(jarPath, sha1)) {
            try { Files.deleteIfExists(jarPath); } catch (IOException ignored) {}
            return fail(cb, "客户端JAR校验失败（下载不完整），请重试安装");
        }

        report(cb, 100, "安装完成: " + dirName);
        return true;
    }

    /**
     * 安装原版 Minecraft 到指定版本目录
     * @param versionEntry 版本清单中的条目
     * @param gameDir      游戏根目录(.minecraft)
     * @param mirrorChain  镜像源列表(优先使用第一个)
     * @param cb           进度回调
     */
    public static boolean installVanilla(VersionDownloadService.VersionInfo versionEntry,
                                          String gameDir, String[] mirrorChain, ProgressCallback cb) {
        String vId = versionEntry.getId();
        Path verDir = Path.of(gameDir, "versions", vId);
        try { Files.createDirectories(verDir); } catch (IOException e) { return fail(cb, "Failed to create directory: "+e); }

        // 1. 获取版本JSON
        report(cb, 5, "获取版本元数据...");
        String json = fetchWithMirrors(versionEntry.getUrl(), mirrorChain);
        if (json == null) return fail(cb, "Failed to fetch version JSON");
        try { Files.writeString(verDir.resolve(vId + ".json"), json); } catch (IOException e) {}

        JsonObject root = GSON.fromJson(json, JsonObject.class);

        // 2. 下载 client.jar
        report(cb, 15, "下载客户端JAR...");
        JsonObject downloads = root.getAsJsonObject("downloads");
        if (downloads == null) return fail(cb, "No downloads field");
        String jarUrl = jsonStr(downloads.getAsJsonObject("client"), "url");
        if (jarUrl == null) return fail(cb, "No client download URL");
        Path jarPath = verDir.resolve(vId + ".jar");
        if (!downloadFile(jarUrl, jarPath, mirrorChain, null))
            return fail(cb, "Client JAR download failed");

        // 3. 下载 libraries
        report(cb, 40, "下载依赖库...");
        var libs = root.getAsJsonArray("libraries");
        if (libs != null && libs.size() > 0) {
            Path libDir = Path.of(gameDir, "libraries");
            int total = libs.size(), done = 0;
            for (int i = 0; i < total; i++) {
                JsonObject lib = libs.get(i).getAsJsonObject();
                // 跳过不符合当前平台规则的库
                if (!shouldDownloadLib(lib)) continue;
                JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                if (artifact == null) continue;
                String libUrl = jsonStr(artifact, "url");
                String libPath = jsonStr(artifact, "path");
                if (libUrl == null || libPath == null) continue;

                Path dest = libDir.resolve(libPath);
                if (!Files.exists(dest)) {
                    try { Files.createDirectories(dest.getParent()); } catch (IOException e) { continue; }
                    downloadFile(libUrl, dest, mirrorChain, null);
                }
                done++;
                if (done % 5 == 0)
                    report(cb, 40 + (done * 50 / total), "库文件: " + done + "/" + total);
            }
        }

        // 4. SHA1 校验客户端JAR
        report(cb, 95, "校验文件完整性...");
        String sha1 = jsonStr(downloads.getAsJsonObject("client"), "sha1");
        if (sha1 != null && !verifySha1(jarPath, sha1))
            System.err.println("[InstallEngine] SHA1 verification failed: " + jarPath);

        report(cb, 100, "安装完成: " + vId);
        return true;
    }

    /** 判断library是否需要下载(platform rules) */
    private static boolean shouldDownloadLib(JsonObject lib) {
        var rules = lib.getAsJsonArray("rules");
        if (rules == null) return true; // 无rules默认全平台
        for (var r : rules) {
            JsonObject rule = r.getAsJsonObject();
            String action = jsonStr(rule, "action");
            var os = rule.getAsJsonObject("os");
            if (os == null && "allow".equals(action)) return true;
            if (os != null) {
                String name = jsonStr(os, "name");
                boolean matches = name == null || name.contains("windows");
                if ("allow".equals(action) && matches) return true;
                if ("disallow".equals(action) && matches) return false;
            }
        }
        return true;
    }

    // 工具方法 //

    /** 多镜像源尝试下载 */
    public static String fetchWithMirrors(String url, String[] mirrors) {
        // 先尝试原始URL
        String result = fetchString(url);
        if (result != null) return result;
        // 尝试镜像
        if (mirrors != null) {
            for (String mirror : mirrors) {
                String mirrorUrl = url.replace("https://launchermeta.mojang.com", mirror)
                                      .replace("https://libraries.minecraft.net", mirror)
                                      .replace("https://launcher.mojang.com", mirror);
                result = fetchString(mirrorUrl);
                if (result != null) return result;
            }
        }
        // 默认BMCLAPI回退
        String bmcl = url.replace("https://launchermeta.mojang.com", VersionDownloadService.BMCLAPI_BASE)
                         .replace("https://libraries.minecraft.net", VersionDownloadService.BMCLAPI_BASE + "/libraries")
                         .replace("https://launcher.mojang.com", VersionDownloadService.BMCLAPI_BASE);
        if (!bmcl.equals(url)) return fetchString(bmcl);
        return null;
    }

    /** 下载文件(带SHA1校验)，使用 DownloadProvider 注入 URL */
    public static boolean downloadFileWithProvider(String url, Path dest,
                                                    DownloadProvider provider, String expectedSha1) {
        return downloadFileWithProvider(url, dest, provider, expectedSha1, -1L, null);
    }

    public static boolean downloadFileWithProvider(String url, Path dest, DownloadProvider provider,
                                                    String expectedSha1, long expectedSize,
                                                    ProgressCallback progress) {
        try {
            DownloadSourceChain chain = DownloadSourceChain.fromProvider(url, provider);
            if (chain != null && !chain.getSources().isEmpty()) {
                if (downloadViaCoordinator(dest, chain, expectedSha1, expectedSize, progress)) {
                    return true;
                }
                LOG.warn("协调器未完成，回退单流兜底: {}", url);
            }
        } catch (RuntimeException e) {
            if (isCancellation(e)) throw e;
            LOG.warn("协调器链路异常，回退单流兜底: {} -> {}", url, e.getMessage());
        } catch (Exception e) {
            LOG.warn("协调器链路异常，回退单流兜底: {} -> {}", url, e.getMessage());
        }
        // 旧单流链路兜底：先注入镜像再回退官方原始 URL，downloadSingle 已带读超时与换候选
        String injected = provider.injectURL(url);
        boolean ok = downloadSingle(dest, injected, expectedSha1, progress);
        if (!ok && !injected.equals(url)) {
            ok = downloadSingle(dest, url, expectedSha1, progress);
        }
        return ok;
    }

    /**
     * 经全局协调器下载单个文件（同步桥）：提交任务 → 轮询进度直至完成/失败/取消。
     *
     * <p>分块缓存目录 = 目标父目录 {@code .download-cache/<sha1>}；expectedSha1 同时作为
     * 文件标识与校验值，expectedSize &gt; 0 时协调器会做 Content-Length 大小校验
     * （不符即切源——「200 但截断」的响应在这里就被拦下）。
     */
    private static boolean downloadViaCoordinator(Path dest, DownloadSourceChain chain,
                                                  String expectedSha1, long expectedSize,
                                                  ProgressCallback progress) throws Exception {
        Path cacheRoot = dest.getParent() == null ? Path.of(".") : dest.getParent();
        DownloadFileTask task = new DownloadFileTask(
                dest, cacheRoot, expectedSha1,
                expectedSize, -1, expectedSha1,
                chain, DownloadCoordinator.getInstance());
        DownloadCoordinator.getInstance().submit(List.of(task));
        // 轮询直至终态（30 分钟兜底，防协调器侧意外挂起）
        long deadline = System.currentTimeMillis() + 30L * 60L * 1000L;
        int lastPct = -1;
        try {
            while (System.currentTimeMillis() < deadline) {
                DownloadFileTask.State s = task.getState();
                if (s == DownloadFileTask.State.SUCCESS) {
                    if (progress != null) progress.onProgress(100, null);
                    return true;
                }
                if (s == DownloadFileTask.State.FAILED || s == DownloadFileTask.State.CANCELLED) {
                    Throwable err = task.getLastError();
                    LOG.warn("协调器下载未完成: {} -> {}", dest,
                            err == null ? "未知原因" : err.getMessage());
                    return false;
                }
                if (progress != null) {
                    int pct = (int) (task.progress() * 100);
                    // 只在百分比变化时上报：轮询是 200ms 一次，原样透传会把
                    // 「下载客户端JAR 0%」按 5 行/秒的频率刷满任务日志框
                    pct = Math.max(0, Math.min(99, pct));
                    if (pct != lastPct) {
                        lastPct = pct;
                        progress.onProgress(pct, null);
                    }
                }
                Thread.sleep(200);
            }
        } catch (Exception e) {
            task.cancel(); // 取消/异常都要停掉协调器侧的分块线程
            throw e;
        }
        task.cancel();
        return false;
    }

    private static boolean downloadSingle(Path dest, String url, String expectedSha1,
                                          ProgressCallback progress) {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    return downloadOnce(dest, url, expectedSha1, progress);
                } catch (Exception e) {
                    // 用户取消（回调抛出的 CANCELLED_MESSAGE）：清掉半截文件并立刻穿透，
                    // 不能被下面的重试逻辑吞掉，否则「取消」要等到下载完才生效
                    if (isCancellation(e)) {
                        try { Files.deleteIfExists(dest); } catch (Exception ignored) {}
                        throw e;
                    }
                    if (isTimeout(e)) {
                        // 连接/读取超时 = 源当前不可用（镜像连上不吐字节是 BMCLAPI 的常见病态）。
                        // 同 URL 重试大概率还是超时，立刻返回让上层换下一个候选（官方兜底），
                        // 否则 3 次重试 × 5 分钟请求超时能把 0% 卡十几分钟。
                        System.err.println("[InstallEngine] 下载超时，跳到下一候选: " + url);
                        return false;
                    }
                    if (attempt >= 2) throw e;
                }
            }
        } catch (Exception e) {
            if (isCancellation(e)) throw (RuntimeException) e;
            System.err.println("[InstallEngine] Download failed: " + url + " -> " + e.getMessage());
        }
        return false;
    }

    /**
     * 单次下载尝试。必须用 HttpURLConnection：HttpClient 没有 socket read timeout，
     * 「连上但不吐字节」的镜像会把 read 一直挂到请求总超时（5 分钟）。这里 read timeout
     * 与停滞看门狗共用一套值（{@link DownloadSettings#getStallTimeoutMs()}，默认 15s），
     * 卡死立刻断开换源；慢但在动（包间隔 < 15s）不受影响。
     */
    private static boolean downloadOnce(Path dest, String url, String expectedSha1,
                                        ProgressCallback progress) throws IOException {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(DownloadSettings.getConnectTimeoutMs());
        conn.setReadTimeout(DownloadSettings.getStallTimeoutMs());
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            DebugLog.http(true, "GET", url, 0, 0, null, -1);
            if (code < 200 || code >= 300) {
                DebugLog.http(false, null, url, code, 0, null, -1);
                return false; // 非 2xx：同 URL 重试无意义，交由上层换候选源
            }
            long contentLength = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                long totalRead = 0;
                int lastPct = -1;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    GlobalSpeedLimiter.getInstance().acquire(n); // 与其他引擎同一限速口径
                    com.example.starlight.util.NetTrafficStats.recordBytes(n); // 全局流量统计
                    totalRead += n;
                    if (progress != null && contentLength > 0) {
                        int pct = (int) (totalRead * 100 / contentLength);
                        if (pct != lastPct) {
                            lastPct = pct;
                            progress.onProgress(pct, null);
                        }
                    }
                }
                DebugLog.http(false, null, url, code, totalRead, "[单文件下载]", -1);
            }
            if (expectedSha1 != null && !verifySha1(dest, expectedSha1)) {
                Files.deleteIfExists(dest);
                throw new IOException("SHA1 mismatch: " + url); // 触发同 URL 重试
            }
            if (progress != null) progress.onProgress(100, null);
            return true;
        } finally {
            conn.disconnect();
        }
    }

    /** 是否为连接/读取超时类失败（源不可用，换源比重试更快） */
    private static boolean isTimeout(Throwable e) {
        while (e != null) {
            if (e instanceof java.net.SocketTimeoutException
                    || e instanceof java.net.ConnectException
                    || e instanceof java.net.UnknownHostException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    /** 是否为调用方取消（进度回调抛出的统一文案） */
    private static boolean isCancellation(Throwable e) {
        return e instanceof RuntimeException
                && com.example.starlight.download.engine.HttpDownloadEngine.CANCELLED_MESSAGE.equals(e.getMessage());
    }

    /** 将 [inMin,inMax] 区间的值线性映射到 [outMin,outMax]（inMax <= inMin 时返回 outMin） */
    private static int mapRange(int value, int inMin, int inMax, int outMin, int outMax) {
        if (inMax <= inMin) return outMin;
        int clamped = Math.max(inMin, Math.min(inMax, value));
        return outMin + (clamped - inMin) * (outMax - outMin) / (inMax - inMin);
    }

    /** 下载文件(带SHA1校验) */
    public static boolean downloadFile(String url, Path dest, String[] mirrors, String expectedSha1) {
        try {
            String finalUrl = url;
            // 尝试下载
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(finalUrl))
                            .timeout(Duration.ofMinutes(5)).GET().build();
                    HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(dest)) {
                            in.transferTo(out);
                        }
                        if (expectedSha1 != null && !verifySha1(dest, expectedSha1)) {
                            Files.deleteIfExists(dest);
                            continue;
                        }
                        return true;
                    }
                } catch (Exception e) {
                    if (attempt < 2 && mirrors != null && mirrors.length > 0) {
                        finalUrl = mirrors[Math.min(attempt, mirrors.length - 1)];
                        if (!finalUrl.startsWith("http")) finalUrl = url.replace(
                                "https://libraries.minecraft.net", finalUrl);
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return false;
    }

    public static boolean verifySha1(Path file, String expected) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] data = Files.readAllBytes(file);
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(expected);
        } catch (Exception e) { return false; }
    }

    private static String fetchString(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) { return null; }
    }

    private static String jsonStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        var e = obj.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** 容错取数值字段（缺失/类型不符返回 fallback） */
    private static long optLong(JsonObject obj, String key, long fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean fail(ProgressCallback cb, String msg) {
        if (cb != null) cb.onProgress(0, msg);
        System.err.println("[InstallEngine] " + msg);
        return false;
    }

    private static void report(ProgressCallback cb, int pct, String msg) {
        if (cb != null) cb.onProgress(pct, msg);
    }
}
