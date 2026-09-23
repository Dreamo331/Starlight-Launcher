/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;

import com.example.starlight.newui.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.time.Duration;
import java.util.UUID;

/**
 * 离线皮肤管理器
 * 负责皮肤服务器生命周期和 authlib-injector 管理
 */
public class OfflineSkinManager {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineSkinManager.class);

    private static OfflineSkinServer skinServer;

    /**
     * 启动离线皮肤服务器并注册角色
     */
    public static synchronized OfflineSkinServer startSkinServer(UUID uuid, String username, Skin skin)
            throws IOException {
        // 如果已有服务器，先停止
        stopSkinServer();

        // 加载皮肤
        Skin.LoadedSkin loadedSkin = skin.load(username);
        if (loadedSkin == null || !loadedSkin.hasSkin()) {
            LOG.warn("Skin is empty or failed to load, skin server not started");
            return null;
        }

        OfflineSkinServer server = new OfflineSkinServer();
        server.addCharacter(uuid, username, loadedSkin);
        server.start();
        skinServer = server;
        return server;
    }

    /**
     * 停止皮肤服务器
     */
    public static synchronized void stopSkinServer() {
        if (skinServer != null) {
            skinServer.stop();
            skinServer = null;
        }
    }

    /**
     * 获取 authlib-injector 的 JVM 参数
     */
    public static String[] getAuthlibInjectorArgs(OfflineSkinServer server) {
        if (server == null) return new String[0];

        // 查找 authlib-injector jar
        Path injectorPath = findAuthlibInjector();
        if (injectorPath == null) {
            LOG.warn("authlib-injector.jar not found, cannot inject offline skin server");
            return new String[0];
        }

        String agentArg = "-javaagent:" + injectorPath.toAbsolutePath()
                + "=http://localhost:" + server.getPort();
        return new String[]{
                agentArg,
                "-Dauthlibinjector.side=client"
        };
    }

    /**
     * authlib-injector 下载地址（GitHub Releases）
     */
    private static final String AUTH_INJECTOR_VERSION = "1.2.6";
    private static final String DOWNLOAD_URL =
            "https://github.com/yushijinhun/authlib-injector/releases/download/v"
                    + AUTH_INJECTOR_VERSION + "/authlib-injector-" + AUTH_INJECTOR_VERSION + ".jar";

    /** 下载超时（秒） */
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 30;

    /**
     * 查找可用的 authlib-injector jar，若未找到则尝试自动下载
     */
    private static Path findAuthlibInjector() {
        // 在启动器目录查找
        String userDir = System.getProperty("user.dir", ".");
        Path localPath = Paths.get(userDir, "authlib-injector.jar");
        if (Files.exists(localPath)) return localPath;

        // 在配置目录查找
        Path configDir = Paths.get(System.getProperty("user.home"), ".starlight-launcher");
        Path configPath = configDir.resolve("authlib-injector.jar");
        if (Files.exists(configPath)) return configPath;

        // 在 Starlight-Launcher 目录查找
        Path launcherPath = Paths.get(userDir, "Starlight-Launcher", "authlib-injector.jar");
        if (Files.exists(launcherPath)) return launcherPath;

        // 未找到，尝试自动下载到配置目录
        LOG.warn("authlib-injector.jar not found, auto-downloading to: {}", configPath);
        try {
            return tryDownloadAuthlibInjector(configDir, configPath);
        } catch (Exception e) {
            LOG.error("Failed to auto-download authlib-injector: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 GitHub Releases 下载 authlib-injector
     */
    private static Path tryDownloadAuthlibInjector(Path configDir, Path targetPath) throws IOException, InterruptedException {
        Files.createDirectories(configDir);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DOWNLOAD_URL))
                .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .header("User-Agent", AppConfig.USER_AGENT)
                .GET()
                .build();

        LOG.info("Downloading authlib-injector from {}...", DOWNLOAD_URL);

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download authlib-injector, HTTP " + response.statusCode());
        }

        // 先下载到临时文件，避免下载中断导致文件损坏
        Path tempFile = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
        try (InputStream in = response.body()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        // 下载完成后重命名
        Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);

        LOG.info("authlib-injector downloaded to: {} ({} bytes)", targetPath, Files.size(targetPath));
        return targetPath;
    }

    /**
     * 判断是否需要启动皮肤服务器（无皮肤或默认皮肤不需要）
     */
    public static boolean needsSkinServer(Skin skin) {
        return skin != null && skin.getType() != Skin.Type.DEFAULT;
    }
}
