package com.example.starlight.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Authlib-Injector 运行时支持（参考 HMCL {@code AuthlibInjectorDownloader} 与
 * {@code AuthlibInjectorAccount#getLaunchArguments}）：
 * <ul>
 *   <li>下载 / 复用 authlib-injector.jar（官方 latest.json 提供 sha256 校验，失败回退 GitHub 直链）；</li>
 *   <li>为第三方账号生成启动注入参数：首选 javaagent（HMCL 方案，兼容所有游戏版本），
 *       jar 不可用时回退 {@code -Dminecraft.api.*} 系统属性（老版本兼容）。</li>
 * </ul>
 */
public class AuthlibInjectorSupport {

    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.starlight-launcher";
    private static final String JAR_NAME = "authlib-injector.jar";
    private static final String LATEST_BUILD_URL = "https://authlib-injector.yushi.moe/artifact/latest.json";
    private static final String GITHUB_LATEST_URL = "https://github.com/yushijinhun/authlib-injector/releases/latest/download/authlib-injector.jar";

    /** 低于此大小视为不完整下载（真实 jar 约 300KB+） */
    private static final long MIN_JAR_SIZE = 100_000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private AuthlibInjectorSupport() {
    }

    /**
     * 生成第三方认证服务器注入参数。
     *
     * @param authServer 规范化后的 Authlib-Injector API 地址
     * @return JVM 参数列表，至少返回回退参数（不会为空）
     */
    public static List<String> buildLaunchJvmArgs(String authServer) {
        Path jar = getOrDownloadArtifact();
        if (jar != null) {
            List<String> args = new ArrayList<>();
            args.add("-javaagent:" + jar + "=" + authServer);
            args.add("-Dauthlibinjector.side=client");
            return args;
        }
        List<String> fallback = new ArrayList<>();
        fallback.add("-Dminecraft.api.auth.host=" + authServer);
        fallback.add("-Dminecraft.api.session.host=" + authServer);
        fallback.add("-Dminecraft.api.services.host=" + authServer);
        return fallback;
    }

    /**
     * 返回本机可用的 authlib-injector.jar 路径；不存在则自动下载，失败返回 null。
     * 幂等：同一 JVM 内重复调用只下载一次（文件已存在且大小合理则直接复用）。
     */
    public static synchronized Path getOrDownloadArtifact() {
        try {
            Path dir = Paths.get(CONFIG_DIR);
            Files.createDirectories(dir);
            Path jar = dir.resolve(JAR_NAME);
            if (Files.isRegularFile(jar) && Files.size(jar) >= MIN_JAR_SIZE) {
                return jar;
            }
            try {
                downloadFromOfficial(jar);
            } catch (Exception e) {
                System.err.println("[AuthlibInjector] 官方源下载失败，尝试 GitHub 直链: " + e.getMessage());
                try {
                    downloadTo(GITHUB_LATEST_URL, jar, null);
                } catch (Exception e2) {
                    System.err.println("[AuthlibInjector] GitHub 直链下载失败: " + e2.getMessage());
                }
            }
            if (Files.isRegularFile(jar) && Files.size(jar) >= MIN_JAR_SIZE) {
                return jar;
            }
        } catch (Exception e) {
            System.err.println("[AuthlibInjector] 无法获取 authlib-injector.jar: " + e.getMessage());
        }
        return null;
    }

    /** 官方源：latest.json 提供 downloadUrl + sha256，下载并校验 */
    private static void downloadFromOfficial(Path target) throws Exception {
        String metaJson = httpGetString(LATEST_BUILD_URL);
        JsonObject root = JsonParser.parseString(metaJson).getAsJsonObject();
        if (!root.has("downloadUrl")) {
            throw new IOException("latest.json 缺少 downloadUrl 字段");
        }
        String downloadUrl = root.get("downloadUrl").getAsString();
        String sha256 = null;
        if (root.has("checksums") && root.getAsJsonObject("checksums").has("sha256")) {
            sha256 = root.getAsJsonObject("checksums").get("sha256").getAsString();
        }
        downloadTo(downloadUrl, target, sha256);
    }

    private static void downloadTo(String url, Path target, String expectedSha256) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "StarlightLauncher/2.0.0")
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载 authlib-injector 被中断", e);
        }
        if (resp.statusCode() >= 400) {
            try (InputStream ignored = resp.body()) {
                // drain & close
            }
            throw new IOException("下载 authlib-injector 失败: HTTP " + resp.statusCode());
        }
        try (InputStream is = resp.body()) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String actual = sha256(target);
            if (!expectedSha256.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(target);
                throw new IOException("authlib-injector SHA-256 校验失败 (期望 " + expectedSha256 + "，实际 " + actual + ")");
            }
        }
    }

    private static String httpGetString(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "StarlightLauncher/2.0.0")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + url, e);
        }
        if (resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " (GET " + url + ")");
        }
        return resp.body();
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 算法不可用", e);
        }
    }
}
