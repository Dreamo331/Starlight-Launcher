package com.example.starlight.version;

import com.example.starlight.download.BMCLAPIDownloadProvider;
import com.example.starlight.download.DownloadProvider;
import com.example.starlight.download.MojangDownloadProvider;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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

    /** 当前使用的下载提供者（可切换：官方 / BMCLAPI 镜像） */
    private static DownloadProvider downloadProvider = new MojangDownloadProvider();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Gson GSON = new Gson();

    /**
     * 设置下载提供者（可用于切换官方源 / BMCLAPI 镜像）
     */
    public static void setDownloadProvider(DownloadProvider provider) {
        downloadProvider = Objects.requireNonNull(provider);
    }

    /**
     * 获取当前下载提供者
     */
    public static DownloadProvider getDownloadProvider() {
        return downloadProvider;
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

    // -----------------------------------------------------------------------
    // Manifest fetching
    // -----------------------------------------------------------------------

    /**
     * Fetches the version manifest from Mojang's official API. Falls back to
     * BMCLAPI if the Mojang endpoint is unreachable or returns an error.
     *
     * @return list of all available {@link VersionEntry}s, or an empty list if
     *         both sources fail
     */
    public static List<VersionEntry> fetchVersionList() {
        // 使用 DownloadProvider 获取版本清单 URL 列表
        List<URI> urls = downloadProvider.getVersionListURLs();
        String json = null;
        for (URI uri : urls) {
            json = fetchString(uri.toString());
            if (json != null) break;
        }
        // 回退：尝试 BMCLAPI（如果 DownloadProvider 不是 BMCLAPI）
        if (json == null && !(downloadProvider instanceof BMCLAPIDownloadProvider)) {
            json = fetchString(BMCLAPI_MANIFEST_URL);
        }
        if (json == null) {
            System.err.println("[VersionManifest] Failed to fetch manifest from any source.");
            return Collections.emptyList();
        }
        return parseManifestJson(json);
    }

    // -----------------------------------------------------------------------
    // Filtering
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Version download
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

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
        List<URI> candidates = downloadProvider.injectURLWithCandidates(remoteUrl);
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
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(remoteUrl))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            DebugLog.http(true, "GET", remoteUrl, 0, 0, null, -1);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                DebugLog.http(false, null, remoteUrl, response.statusCode(), 0, null, -1);
                System.err.println("[VersionManifest] HTTP " + response.statusCode() + " for " + remoteUrl);
                return false;
            }

            // Stream to file with progress reporting
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);

            try (InputStream in = response.body();
                 var out = Files.newOutputStream(localPath)) {

                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;
                int lastReportedPercent = -1;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
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
                DebugLog.http(false, null, remoteUrl, response.statusCode(), totalRead, "[二进制文件]", -1);
            }

            return true;
        } catch (IOException | InterruptedException e) {
            System.err.println("[VersionManifest] Download failed for " + remoteUrl + ": " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * 依次尝试当前下载源注入后的候选地址（BMCLAPI 等镜像优先，最后兜底官方原始 URL），
     * 任一成功即返回；全部失败返回 {@code null}。
     */
    private static String fetchStringViaProvider(String url) {
        for (URI uri : downloadProvider.injectURLWithCandidates(url)) {
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
