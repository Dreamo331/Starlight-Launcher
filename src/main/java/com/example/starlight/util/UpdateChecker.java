package com.example.starlight.util;

import com.example.starlight.config.Endpoints;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 更新检查服务：请求星光MC社区启动器更新 API（auth.php?action=launcher_update）
 * 获取最新版本并与当前版本比对。
 * 纯逻辑类，不依赖任何 UI；调用方负责展示检查结果（弹窗等）。
 * 接口定义见《星光MC社区_启动器更新API文档.md》，服务端代码位于项目「检查更新PHP」目录。
 */
public final class UpdateChecker {

    /**
     * 检测更新 API（公开接口，无需 API Key）。
     * 返回结构：
     *   {"ok":true,"version":"1.0.1","download_url":"...","md5":"...",
     *    "release_notes":"更新说明","force_update":false,"check_time":"..."}
     */
    public static final String RELEASES_API_URL =
            Endpoints.communityApiUrl() + "?action=launcher_update";

    /** 更新检查专用线程池（守护线程，不阻止 JVM 退出） */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-checker");
        t.setDaemon(true);
        return t;
    });

    /**
     * 检查结果：
     * - latestTag      显示用版本标签（含 v 前缀，如 v1.0.1）
     * - latestVersion  服务端 version 字段（如 1.0.1）
     * - releaseBody    更新说明（release_notes）
     * - downloadUrl    安装包下载地址（download_url）
     * - md5            安装包 MD5（校验完整性）
     * - forceUpdate    是否强制更新（true=必须更新）
     * - error          失败原因（非空表示检查失败）
     */
    public static final class UpdateResult {
        public final String latestTag;
        public final String latestVersion;
        public final String releaseBody;
        public final String downloadUrl;
        public final String md5;
        public final boolean forceUpdate;
        public final String error;

        public UpdateResult(String latestTag, String latestVersion, String releaseBody,
                            String downloadUrl, String md5, boolean forceUpdate, String error) {
            this.latestTag = latestTag;
            this.latestVersion = latestVersion;
            this.releaseBody = releaseBody;
            this.downloadUrl = downloadUrl;
            this.md5 = md5;
            this.forceUpdate = forceUpdate;
            this.error = error;
        }
    }

    private UpdateChecker() {}

    /**
     * 异步检查更新：请求星光MC社区更新 API，解析最新版本号与更新说明。
     * 回调在 JavaFX 应用线程执行，可直接操作 UI。
     *
     * @param currentVersion 当前版本号（不含 v 前缀），用于 User-Agent 与版本比对
     * @param callback       检查完成后的回调，结果中 error 非空表示检查失败
     */
    public static void checkUpdate(String currentVersion, Consumer<UpdateResult> callback) {
        POOL.submit(() -> {
            String latestVersion = null;
            String releaseNotes = "";
            String downloadUrl = "";
            String md5 = "";
            boolean forceUpdate = false;
            String error = null;
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(RELEASES_API_URL))
                        .timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "Starlight-Launcher/" + currentVersion)
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                DebugLog.http(true, "GET", RELEASES_API_URL, 0, 0, null, -1);
                DebugLog.http(false, null, RELEASES_API_URL, resp.statusCode(), resp.body().length(), resp.body(), -1);
                if (resp.statusCode() == 200) {
                    JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                    // ok=false 表示服务端认为更新服务不可用
                    if (obj.has("ok") && !obj.get("ok").isJsonNull() && !obj.get("ok").getAsBoolean()) {
                        error = "更新服务暂不可用（服务端 ok=false）";
                    }
                    latestVersion = obj.has("version") && !obj.get("version").isJsonNull()
                            ? obj.get("version").getAsString() : null;
                    if (obj.has("release_notes") && !obj.get("release_notes").isJsonNull()) {
                        releaseNotes = obj.get("release_notes").getAsString();
                    }
                    if (obj.has("download_url") && !obj.get("download_url").isJsonNull()) {
                        downloadUrl = obj.get("download_url").getAsString();
                    }
                    if (obj.has("md5") && !obj.get("md5").isJsonNull()) {
                        md5 = obj.get("md5").getAsString();
                    }
                    forceUpdate = obj.has("force_update") && !obj.get("force_update").isJsonNull()
                            && obj.get("force_update").getAsBoolean();
                } else {
                    error = "服务器响应异常 (HTTP " + resp.statusCode() + ")";
                    // 尝试读取服务端返回的具体错误信息（服务端返回 {"error":"..."} 时）
                    try {
                        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                        if (obj.has("error") && !obj.get("error").isJsonNull()) {
                            error = obj.get("error").getAsString();
                        }
                    } catch (Exception ignored) {
                        // 非 JSON 响应（如服务器默认错误页），保留通用错误信息
                    }
                }
            } catch (Exception ex) {
                error = ex.getMessage();
            }

            // latestTag 仅用于界面显示（补 v 前缀）
            final String version = latestVersion;
            final String tag = version != null && !version.startsWith("v") ? "v" + version : version;
            final String notes = releaseNotes;
            final String dlUrl = downloadUrl;
            final String md5sum = md5;
            final boolean force = forceUpdate;
            final String errMsg = error;
            Platform.runLater(() -> callback.accept(
                    new UpdateResult(tag, version, notes, dlUrl, md5sum, force, errMsg)));
        });
    }

    /** 简单版本号比较：仅比较数字部分，SNAPSHOT 视为低于同号正式版 */
    public static int compareVersions(String v1, String v2) {
        String norm1 = v1.replaceAll("[^0-9.]", "").replaceAll("\\.+$", "");
        String norm2 = v2.replaceAll("[^0-9.]", "").replaceAll("\\.+$", "");
        String[] p1 = norm1.split("\\.");
        String[] p2 = norm2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int a = i < p1.length && !p1[i].isEmpty() ? Integer.parseInt(p1[i]) : 0;
            int b = i < p2.length && !p2[i].isEmpty() ? Integer.parseInt(p2[i]) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        boolean snap1 = v1.toUpperCase().contains("SNAPSHOT");
        boolean snap2 = v2.toUpperCase().contains("SNAPSHOT");
        if (snap1 && !snap2) return -1;
        if (!snap1 && snap2) return 1;
        return 0;
    }
}
