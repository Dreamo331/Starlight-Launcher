/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.download;


import com.example.starlight.util.I18N;
import com.example.starlight.download.engine.HttpDownloadEngine;
import com.example.starlight.download.mod.DownloadCoordinator;
import com.example.starlight.download.mod.DownloadFileTask;
import com.example.starlight.download.mod.DownloadSourceChain;
import com.example.starlight.download.task.AssetDownloader;
import com.example.starlight.download.task.GameResourceCompleter;
import com.example.starlight.download.task.LibraryDownloader;
import com.example.starlight.download.task.VersionManifest;
import com.example.starlight.model.CallbackInterfaces;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
            else if (!ok && onResult != null) onResult.onError(I18N.get("download.p0064", versionId));
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
            else if (!ok && onResult != null) onResult.onError(I18N.get("download.t0001"));
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
            else if (!ok && onResult != null) onResult.onError(I18N.get("download.t0002"));
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
                boolean ok = DownloadEngineRouter.completeAll(gameDir, versionId,
                        (pct, msg) -> { if (onProgress != null) onProgress.onProgress(pct, msg); });
                if (onResult != null) {
                    if (ok) onResult.onSuccess(true);
                    else onResult.onSuccess(false);
                }
            } catch (Exception e) {
                if (onResult != null) onResult.onError(I18N.get("download.p0065", e.getMessage()));
            }
        });
    }

    /** 多线程下载文件（异步）
     *  JUDGE-2：内部改调 HttpDownloadEngine.downloadFileWithProgress（自带 IntConsumer 进度回调），
     *  废弃原 MultiThreadDownloader + ByteArrayOutputStream 劫持 System.out 的整套实现。
     *  方法签名保持不变，调用点零改动。
     *  @param threadCount 兼容占位参数：实际线程数由 HttpDownloadEngine 内部策略决定，此处不再使用
     */
    /**
     * 通用单文件下载（mod 提速链路）：多源候选（官方 + DownloadProvider 镜像）+ 多线程动态分块
     * + 全局调度器（DownloadCoordinator）。相比旧 HttpDownloadEngine 单 URL 单线程路径，
     * 接入全局线程池 / 分块再分裂 / 源切换后，大文件下载显著提速，弱网下更稳。
     *
     * @param threadCount 仅为兼容旧调用方保留；实际线程数由 DownloadSettings.getDownloadThreads() 统一控制
     */
    public static void downloadFileAsync(String url, String savePath, int threadCount,
                                          CallbackInterfaces.ProgressCallback onProgress,
                                          CallbackInterfaces.ResultCallback<String> onResult) {
        CompletableFuture.runAsync(() -> {
            Path target = Path.of(savePath);
            try {
                // ① 多源链：官方 URL + provider 注入的镜像候选（BMCLAPI/MCBBS 等）
                DownloadSourceChain chain = DownloadSourceChain.fromProvider(url, DownloadProviderHolder.get());
                if (chain.getSources().isEmpty()) {
                    onResult.onError(I18N.get("download.t0003"));
                    return;
                }
                // ② 单文件任务：通用入口不校验大小/哈希；分块缓存落在目标父目录 .download-cache
                Path cacheRoot = target.getParent() == null ? Path.of(".") : target.getParent();
                DownloadFileTask task = new DownloadFileTask(
                        target, cacheRoot, null,
                        DownloadFileTask.SIZE_UNKNOWN, -1, null,
                        chain, DownloadCoordinator.getInstance());
                // ③ 提交全局调度器（按目标路径去重）
                DownloadCoordinator.getInstance().submit(List.of(task));
                // ④ 轮询进度直至完成（超时保护：30 分钟）
                long deadline = System.currentTimeMillis() + 30L * 60L * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    DownloadFileTask.State s = task.getState();
                    if (s == DownloadFileTask.State.SUCCESS) {
                        onProgress.onProgress(100, I18N.get("download.p0066", savePath));
                        onResult.onSuccess(I18N.get("download.p0067", savePath));
                        return;
                    }
                    if (s == DownloadFileTask.State.FAILED || s == DownloadFileTask.State.CANCELLED) {
                        Throwable err = task.getLastError();
                        onResult.onError(I18N.get("download.p0068", (err == null ? I18N.get("download.t0056") : err.getMessage())));
                        return;
                    }
                    int pct = (int) (task.progress() * 100);
                    onProgress.onProgress(pct, I18N.get("download.p0069", pct + "%"));
                    Thread.sleep(200);
                }
                task.cancel();
                onResult.onError(I18N.get("download.t0004"));
            } catch (Exception e) {
                onResult.onError(I18N.get("download.p0070", e.getMessage()));
            }
        });
    }
}
