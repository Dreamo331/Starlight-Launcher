/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;

import com.example.starlight.download.AssetDownloader;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.GameResourceCompleter;
import com.example.starlight.download.LibraryDownloader;
import com.example.starlight.model.CallbackInterfaces;
import com.example.starlight.multithreadeddownload.MultiThreadDownloader;
import com.example.starlight.version.VersionManifest;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 下载编排服务 — 整合版本下载、资源补全、多线程下载
 */
public class DownloadService {

    /** 获取 Minecraft 版本列表 */
    public static List<VersionManifest.VersionEntry> fetchVersionList() {
        return VersionManifest.fetchVersionList();
    }

    /** 按类型筛选版本 */
    public static List<VersionManifest.VersionEntry> filterVersionsByType(
            List<VersionManifest.VersionEntry> versions, String type) {
        return VersionManifest.filterByType(versions, type);
    }

    /** 下载指定版本（异步） */
    public static void downloadVersionAsync(String versionId, String gameDir,
                                             CallbackInterfaces.ProgressCallback progress,
                                             CallbackInterfaces.ResultCallback<Boolean> onResult) {
        CompletableFuture.runAsync(() -> {
            boolean ok = VersionManifest.downloadVersion(versionId, gameDir,
                    (pct, msg) -> { if (progress != null) progress.onProgress(pct, msg); });
            if (ok && onResult != null) onResult.onSuccess(true);
            else if (!ok && onResult != null) onResult.onError("下载版本失败: " + versionId);
        });
    }

    /** 下载资源索引文件 */
    public static void downloadAssetIndexAsync(String assetId, String gameDir,
                                                CallbackInterfaces.ProgressCallback progress,
                                                CallbackInterfaces.ResultCallback<Boolean> onResult) {
        CompletableFuture.runAsync(() -> {
            boolean ok = AssetDownloader.downloadAssetIndex(assetId, gameDir,
                    (pct, msg) -> { if (progress != null) progress.onProgress(pct, msg); });
            if (ok && onResult != null) onResult.onSuccess(true);
            else if (!ok && onResult != null) onResult.onError("下载资源索引失败");
        });
    }

    /** 下载所有资源文件 */
    public static void downloadAssetsAsync(String gameDir,
                                            CallbackInterfaces.ProgressCallback progress,
                                            CallbackInterfaces.ResultCallback<Boolean> onResult) {
        CompletableFuture.runAsync(() -> {
            boolean ok = AssetDownloader.downloadAssets(gameDir,
                    (pct, msg) -> { if (progress != null) progress.onProgress(pct, msg); });
            if (ok && onResult != null) onResult.onSuccess(true);
            else if (!ok && onResult != null) onResult.onError("下载资源文件失败");
        });
    }

    /** 补全缺失的库文件 */
    public static int downloadMissingLibraries(String gameDir, String versionJson,
                                                CallbackInterfaces.ProgressCallback progress) {
        return LibraryDownloader.downloadMissingLibraries(gameDir, versionJson,
                (pct, msg) -> { if (progress != null) progress.onProgress(pct, msg); });
    }

    /** 补全所有游戏资源（异步） */
    public static void completeGameResourcesAsync(String gameDir, String versionId,
                                                   CallbackInterfaces.ProgressCallback onProgress,
                                                   CallbackInterfaces.ResultCallback<Boolean> onResult) {
        CompletableFuture.runAsync(() -> {
            try {
                boolean ok = GameResourceCompleter.completeAll(gameDir, versionId,
                        (pct, msg) -> { if (onProgress != null) onProgress.onProgress(pct, msg); });
                if (onResult != null) {
                    if (ok) onResult.onSuccess(true);
                    else onResult.onSuccess(false);
                }
            } catch (Exception e) {
                if (onResult != null) onResult.onError("资源补全失败: " + e.getMessage());
            }
        });
    }

    /** 多线程下载文件（异步） */
    public static void downloadFileAsync(String url, String savePath, int threadCount,
                                          CallbackInterfaces.ProgressCallback onProgress,
                                          CallbackInterfaces.ResultCallback<String> onResult) {
        // 未指定线程数时使用设置页「并发下载数」配置
        int effectiveThreadCount = threadCount <= 0 ? DownloadSettings.getDownloadThreads() : threadCount;
        CompletableFuture.runAsync(() -> {
            PrintStream originalOut = System.out;
            try {
                MultiThreadDownloader downloader = new MultiThreadDownloader(url, savePath, effectiveThreadCount);
                ByteArrayOutputStream baos = new ByteArrayOutputStream() {
                    @Override
                    public synchronized void write(byte[] b, int off, int len) {
                        super.write(b, off, len);
                        String chunk = new String(b, off, len, StandardCharsets.UTF_8);
                        int pIdx = chunk.indexOf('%');
                        if (pIdx > 0) {
                            for (int i = pIdx - 1; i >= 0; i--) {
                                if (!Character.isDigit(chunk.charAt(i))) {
                                    try {
                                        int pct = Integer.parseInt(chunk.substring(i + 1, pIdx));
                                        onProgress.onProgress(pct, chunk.trim());
                                    } catch (NumberFormatException ignored) {}
                                    break;
                                }
                            }
                        }
                        if (chunk.contains("下载完成")) onProgress.onProgress(100, chunk.trim());
                    }
                };
                System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
                downloader.start();
                System.out.flush();
                onResult.onSuccess("下载完成: " + savePath);
            } catch (Exception e) {
                onResult.onError("下载失败: " + e.getMessage());
            } finally {
                System.setOut(originalOut);
            }
        });
    }
}
