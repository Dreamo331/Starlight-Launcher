package com.example.starlight.download.task;

import com.example.starlight.download.DownloadProviderHolder;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.source.BMCLAPIDownloadProvider;
import com.example.starlight.download.source.DownloadProvider;
import com.example.starlight.util.DebugLog;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Fetches and manages Minecraft version manifests from Mojang's official API,
 * with BMCLAPI mirror as fallback. Uses Java 17's built-in HttpClient and Gson.
 */
public class VersionManifest {

    private static final String MOJANG_MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String BMCLAPI_MANIFEST_URL =
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest.json";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Gson GSON = new Gson();

    /**
     * 设置下载提供者（可用于切换官方源 / BMCLAPI 镜像）——JUDGE-5 委托 DownloadProviderHolder
     */
    public static void setDownloadProvider(DownloadProvider provider) {
        DownloadProviderHolder.set(Objects.requireNonNull(provider));
    }

    /**
     * 获取当前下载提供者——JUDGE-5 委托 DownloadProviderHolder
     */
    public static DownloadProvider getDownloadProvider() {
        return DownloadProviderHolder.get();
    }

    /**
     * A single entry from the version manifest.
     */
    public static class VersionEntry {
        public final String id;
        public final String type;
        public final String releaseTime;
        public final String url;

        public VersionEntry(String id, String type, String releaseTime, String url) {
            this.id = id;
            this.type = type;
            this.releaseTime = releaseTime;
            this.url = url;
        }

        public String getId() { return id; }

        public String getType() { return type; }

        public String getReleaseTime() { return releaseTime; }

        public String getUrl() { return url; }

        @Override
        public String toString() {
            return "VersionEntry{id='" + id + "', type='" + type + "'}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof VersionEntry that)) return false;
            return Objects.equals(id, that.id)
                    && Objects.equals(type, that.type)
                    && Objects.equals(releaseTime, that.releaseTime)
                    && Objects.equals(url, that.url);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, type, releaseTime, url);
        }
    }

    /**
     * Callback interface to report download progress.
     */
    public interface ProgressCallback {
        /**
         * Called periodically during download operations.
         *
         * @param percent estimated progress (0-100)
         * @param message human-readable description of the current step
         */
        void onProgress(int percent, String message);
    }

    // Manifest fetching //

    /**
     * Fetches the version manifest from Mojang's official API. Falls back to
     * BMCLAPI if the Mojang endpoint is unreachable or returns an error.
     *
     * @return list of all available {@link VersionEntry}s, or an empty list if
     *         both sources fail
     */
    public static List<VersionEntry> fetchVersionList() {
        // 候选链并行竞速：选中源 + BMCLAPI + 官方 piston-meta 同发，任一成功即用。
        // 旧串行逐个尝试在「BMCLAPI 抖动 + 国内直连官方被墙」时是 3~4 个 10~15s 超时叠加
        // （2026-10-11 用户反馈「版本列表特别慢/有时无法获取」的根因），并行后最坏 ≈ 单个超时。
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        for (URI uri : DownloadProviderHolder.get().getVersionListURLs()) {
            urls.add(uri.toString());
        }
        urls.add(BMCLAPI_MANIFEST_URL);
        urls.add("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        String json = fetchFirstSuccessJson(urls);
        if (json == null) {
            // 全部失败：用过期磁盘缓存兜底（有数据总比空列表好）
            json = loadManifestDiskCache(false);
            if (json != null) {
                System.err.println("[VersionManifest] 网络全部失败，回退过期磁盘缓存");
            }
        }
        if (json == null) {
            System.err.println("[VersionManifest] Failed to fetch manifest from any source.");
            return Collections.emptyList();
        }
        saveManifestDiskCache(json);
        return parseManifestJson(json);
    }

    // 清单获取的公共底座：并行竞速 + 磁盘缓存（VersionDownloadService 共用） //

    /** 清单磁盘缓存路径（与 source-health.json 同目录，统一走 AppDirs） */
    private static final Path MANIFEST_DISK_CACHE =
            com.example.starlight.config.AppDirs.versionManifestCacheJson();
    /** 磁盘缓存「新鲜」阈值：6h 内冷启动直接用缓存秒开，不联网 */
    private static final long MANIFEST_DISK_FRESH_MS = 6L * 60 * 60 * 1000L;

    /**
     * 并行竞速取清单 JSON：全部候选同时发出，任一成功立即返回（按候选顺序取最先完成者），
     * 全部失败返回 {@code null}。被墙/失效的候选只会并行空转，不再串行拖慢整体。
     */
    public static String fetchFirstSuccessJson(Collection<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        List<CompletableFuture<String>> pending = new ArrayList<>();
        for (String url : urls) {
            pending.add(CompletableFuture.supplyAsync(() -> fetchString(url))
                    .exceptionally(e -> null)); // 单个候选异常不拖垮整组
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            // 等到任一完成再收集本轮已完成者（fetchString 自带 15s 超时，整体有 deadline 兜底）
            try {
                CompletableFuture.anyOf(pending.toArray(new CompletableFuture[0]))
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                continue;
            }
            String firstSuccess = null;
            Iterator<CompletableFuture<String>> it = pending.iterator();
            while (it.hasNext()) {
                CompletableFuture<String> f = it.next();
                if (!f.isDone()) continue;
                it.remove();
                if (firstSuccess != null) continue;
                try {
                    String json = f.join();
                    if (json != null && !json.isEmpty()) firstSuccess = json;
                } catch (Exception ignored) {
                    // 该候选失败，继续看其他
                }
            }
            if (firstSuccess != null) return firstSuccess;
        }
        return null;
    }

    /** 读清单磁盘缓存；{@code onlyFresh} 时仅返回 6h 内的，否则任意年龄都返回 */
    public static String loadManifestDiskCache(boolean onlyFresh) {
        try {
            if (!Files.isRegularFile(MANIFEST_DISK_CACHE)) return null;
            if (onlyFresh && System.currentTimeMillis() - Files.getLastModifiedTime(MANIFEST_DISK_CACHE).toMillis()
                    > MANIFEST_DISK_FRESH_MS) {
                return null;
            }
            String json = Files.readString(MANIFEST_DISK_CACHE);
            return json.isBlank() ? null : json;
        } catch (Exception e) {
            return null;
        }
    }

    /** 写清单磁盘缓存（失败静默——缓存只是兜底，不能反过来影响主流程） */
    public static void saveManifestDiskCache(String json) {
        try {
            if (json == null || json.isEmpty()) return;
            Files.createDirectories(MANIFEST_DISK_CACHE.getParent());
            Files.writeString(MANIFEST_DISK_CACHE, json);
        } catch (Exception ignored) {
        }
    }

    /** 删除清单磁盘缓存（「刷新」按钮强制重新联网时调用） */
    public static void deleteManifestDiskCache() {
        try {
            Files.deleteIfExists(MANIFEST_DISK_CACHE);
        } catch (Exception ignored) {
        }
    }

    // Filtering //

    /**
     * Filters the given version list to only entries whose type matches the
     * supplied type string. Common values: {@code "release"}, {@code "snapshot"},
     * {@code "old_beta"}, {@code "old_alpha"}.
     *
     * @param versions the full version list (not mutated)
     * @param type     the required type value (case-sensitive)
     * @return filtered list, never {@code null}
     */
    public static List<VersionEntry> filterByType(List<VersionEntry> versions, String type) {
        if (versions == null || versions.isEmpty()) {
            return Collections.emptyList();
        }
        return versions.stream()
                .filter(v -> v != null && v.getType().equals(type))
                .collect(Collectors.toList());
    }

    // Version download //

    /**
     * Downloads the version JSON and client JAR for the given version into
     * {@code gameDir/versions/<versionId>/}. If the version metadata also
     * contains a server download URL, the server JAR is downloaded as well.
     *
     * @param versionId the Minecraft version id (e.g. "1.20.4")
     * @param gameDir   the launcher's game root directory
     * @param progress  optional callback for progress reporting (may be {@code null})
     * @return {@code true} if all required files were downloaded successfully;
     *         {@code false} otherwise
     */
    public static boolean downloadVersion(String versionId, String gameDir, ProgressCallback progress) {
        Objects.requireNonNull(versionId, "versionId must not be null");
        Objects.requireNonNull(gameDir, "gameDir must not be null");

        // 1. Locate the version entry in the manifest
        report(progress, 0, "Fetching version manifest...");
        List<VersionEntry> allVersions = fetchVersionList();
        if (allVersions.isEmpty()) {
            report(progress, 0, "Failed to fetch version manifest.");
            return false;
        }

        VersionEntry target = null;
        for (VersionEntry entry : allVersions) {
            if (entry.getId().equals(versionId)) {
                target = entry;
                break;
            }
        }
        if (target == null) {
            report(progress, 0, "Version \"" + versionId + "\" not found in manifest.");
            return false;
        }

        // 2. Prepare output directory
        Path versionDir = Paths.get(gameDir, "versions", versionId);
        try {
            Files.createDirectories(versionDir);
        } catch (IOException e) {
            System.err.println("[VersionManifest] Failed to create directory: " + versionDir);
            report(progress, 0, "Failed to create directory: " + e.getMessage());
            return false;
        }

        // 3. Fetch the version metadata JSON（走当前下载源：BMCLAPI 等镜像优先，失败回退官方原始 URL）
        report(progress, 10, "Downloading version metadata...");
        String versionJsonStr = fetchStringViaProvider(target.getUrl());
        if (versionJsonStr == null) {
            report(progress, 10, "Failed to download version metadata.");
            return false;
        }

        // 4. Save version JSON
        Path versionJsonPath = versionDir.resolve(versionId + ".json");
        try {
            Files.writeString(versionJsonPath, versionJsonStr);
        } catch (IOException e) {
            System.err.println("[VersionManifest] Failed to write version JSON: " + versionJsonPath);
            report(progress, 10, "Failed to save version JSON.");
            return false;
        }

        // 5. Parse the version JSON to extract download URLs
        JsonObject versionJson;
        try {
            versionJson = GSON.fromJson(versionJsonStr, JsonObject.class);
        } catch (Exception e) {
            System.err.println("[VersionManifest] Failed to parse version JSON: " + e.getMessage());
            report(progress, 20, "Failed to parse version metadata.");
            return false;
        }

        JsonObject downloads = versionJson.getAsJsonObject("downloads");
        if (downloads == null) {
            report(progress, 20, "Version metadata has no 'downloads' section.");
            return false;
        }

        // 6. Download client.jar
        JsonObject clientObj = downloads.getAsJsonObject("client");
        if (clientObj == null) {
            report(progress, 20, "No client download URL in version metadata.");
            return false;
        }
        String clientUrl = getJsonString(clientObj, "url");
        if (clientUrl == null || clientUrl.isBlank()) {
            report(progress, 20, "Client download URL is empty.");
            return false;
        }

        Path clientJarPath = versionDir.resolve(versionId + ".jar");
        boolean clientOk = downloadFile(clientUrl, clientJarPath, 20, 80, progress,
                "Downloading client JAR...");
        if (!clientOk) {
            report(progress, 80, "Failed to download client JAR.");
            return false;
        }

        // 7. Optionally download server.jar
        JsonObject serverObj = downloads.getAsJsonObject("server");
        if (serverObj != null) {
            String serverUrl = getJsonString(serverObj, "url");
            if (serverUrl != null && !serverUrl.isBlank()) {
                Path serverJarPath = versionDir.resolve(versionId + "-server.jar");
                boolean serverOk = downloadFile(serverUrl, serverJarPath, 80, 99, progress,
                        "Downloading server JAR...");
                if (!serverOk) {
                    System.err.println("[VersionManifest] Warning: failed to download server JAR for " + versionId);
                    // Non-fatal — continue
                }
            }
        }

        report(progress, 100, "Done.");
        return true;
    }

    // Internal helpers //

    /**
     * Performs an HTTP GET and returns the response body as a string.
     * Returns {@code null} on any failure.
     */
    private static String fetchString(String url) {        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            DebugLog.http(true, "GET", url, 0, 0, null, -1);
            DebugLog.http(false, null, url, response.statusCode(), response.body().length(), response.body(), -1);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                System.err.println("[VersionManifest] HTTP " + response.statusCode() + " for " + url);
                return null;
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[VersionManifest] Request failed for " + url + ": " + e.getMessage());
            // Restore interrupted status if applicable
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Downloads a remote file to the given local path, reporting progress
     * along a percentage range.
     *
     * @param remoteUrl  source URL
     * @param localPath  destination path
     * @param rangeStart start of the percentage range for this download
     * @param rangeEnd   end of the percentage range for this download
     * @param progress   callback
     * @param message    description shown during download
     * @return {@code true} on success
     */
    private static boolean downloadFile(String remoteUrl, Path localPath,
                                        int rangeStart, int rangeEnd,
                                        ProgressCallback progress, String message) {
        // 走当前下载源：镜像候选在前、官方原始 URL 兜底（此前只用 JSON 里写死的官方直链，
        // 国内下载 client.jar 特别慢就是这里）
        List<URI> candidates = DownloadProviderHolder.get().injectURLWithCandidates(remoteUrl);
        if (candidates.isEmpty()) {
            candidates = List.of(URI.create(remoteUrl));
        }
        for (URI candidate : candidates) {
            if (downloadSingle(candidate.toString(), localPath, rangeStart, rangeEnd, progress, message)) {
                return true;
            }
        }
        return false;
    }

    /** 单次尝试：GET 到指定路径，按字节回报映射到 [rangeStart,rangeEnd] 的进度 */
    private static boolean downloadSingle(String remoteUrl, Path localPath,
                                          int rangeStart, int rangeEnd,
                                          ProgressCallback progress, String message) {
        // HttpURLConnection（带 connect/read 超时）而不是 HttpClient：后者没有 socket read
        // timeout，「连上但不吐字节」的镜像会把 read 挂到 5 分钟请求总超时，进度卡 0%；
        // read timeout 与停滞看门狗同值（默认 15s），超时立刻返回，外层换下一个候选源。
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) URI.create(remoteUrl).toURL().openConnection();
            conn.setConnectTimeout(DownloadSettings.getConnectTimeoutMs());
            conn.setReadTimeout(DownloadSettings.getStallTimeoutMs());
            conn.setInstanceFollowRedirects(true);
            int status = conn.getResponseCode();
            DebugLog.http(true, "GET", remoteUrl, 0, 0, null, -1);
            if (status < 200 || status >= 300) {
                DebugLog.http(false, null, remoteUrl, status, 0, null, -1);
                System.err.println("[VersionManifest] HTTP " + status + " for " + remoteUrl);
                return false;
            }

            // Stream to file with progress reporting
            long contentLength = conn.getContentLengthLong();

            try (InputStream in = conn.getInputStream();
                 var out = Files.newOutputStream(localPath)) {

                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;
                int lastReportedPercent = -1;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    com.example.starlight.download.mod.GlobalSpeedLimiter.getInstance().acquire(bytesRead);
                    com.example.starlight.util.NetTrafficStats.recordBytes(bytesRead); // 全局流量统计
                    if (contentLength > 0) {
                        totalRead += bytesRead;
                        int stepPercent = (int) (totalRead * 100 / contentLength);
                        if (stepPercent != lastReportedPercent) {
                            lastReportedPercent = stepPercent;
                            int mappedPercent = mapRange(stepPercent, 0, 100, rangeStart, rangeEnd);
                            report(progress, mappedPercent, message);
                        }
                    }
                }
                DebugLog.http(false, null, remoteUrl, status, totalRead, "[二进制文件]", -1);
            }

            return true;
        } catch (IOException e) {
            System.err.println("[VersionManifest] Download failed for " + remoteUrl + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 依次尝试当前下载源注入后的候选地址（BMCLAPI 等镜像优先，最后兜底官方原始 URL），
     * 任一成功即返回；全部失败返回 {@code null}。
     */
    private static String fetchStringViaProvider(String url) {
        for (URI uri : DownloadProviderHolder.get().injectURLWithCandidates(url)) {
            String body = fetchString(uri.toString());
            if (body != null) return body;
        }
        return null;
    }

    /**
     * Parses a raw manifest JSON string into a list of {@link VersionEntry}.
     */
    private static List<VersionEntry> parseManifestJson(String json) {
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonArray versionsArray = root.getAsJsonArray("versions");
            if (versionsArray == null || versionsArray.isEmpty()) {
                return Collections.emptyList();
            }
            List<VersionEntry> entries = new ArrayList<>(versionsArray.size());
            for (JsonElement element : versionsArray) {
                JsonObject obj = element.getAsJsonObject();
                String id = getJsonString(obj, "id");
                String type = getJsonString(obj, "type");
                String releaseTime = getJsonString(obj, "releaseTime");
                String url = getJsonString(obj, "url");
                if (id != null && type != null && url != null) {
                    entries.add(new VersionEntry(id, type, releaseTime, url));
                }
            }
            return entries;
        } catch (Exception e) {
            System.err.println("[VersionManifest] Failed to parse manifest JSON: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Safely extracts a string value from a {@link JsonObject}, returning
     * {@code null} if the member is missing or is not a primitive string.
     */
    private static String getJsonString(JsonObject obj, String memberName) {
        JsonElement element = obj.get(memberName);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return value.isEmpty() ? null : value;
    }

    /**
     * Linear mapping of a value from one range to another.
     */
    private static int mapRange(int value, int inMin, int inMax, int outMin, int outMax) {
        return outMin + (value - inMin) * (outMax - outMin) / (inMax - inMin);
    }

    /**
     * Convenience: invokes the progress callback if it is not {@code null}.
     */
    private static void report(ProgressCallback callback, int percent, String message) {
        if (callback != null) {
            callback.onProgress(percent, message);
        }
    }
}
