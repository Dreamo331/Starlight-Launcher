package com.example.starlight.download.task;

import com.example.starlight.download.engine.HttpDownloadEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * 启动器自更新（新 exe）下载编排抽取类（UI_EMBED-3）。
 *
 * <p>把原 {@code UpdateCoordinator.startAutoUpdate} 内直连 {@link HttpDownloadEngine} 的
 * 「下载新 exe」段抽入本类；下载后的 MD5 校验、自替换 bat 生成仍留在 UpdateCoordinator。
 *
 * <p>本类<b>不 import 任何 newui/gui/update 包</b>，进度走 {@link IntConsumer}、取消走 {@link AtomicBoolean}。
 */
public final class UpdateDownloader {

    private UpdateDownloader() {
    }

    /**
     * 下载新版本启动器 exe 到目标路径。
     *
     * <p>取消语义与原 {@code startAutoUpdate} 保持一致：用户中途取消时<b>不抛错</b>、直接返回
     * {@code false}，由调用方按 {@code cancel} 标志做静默清理（原实现下载跑完后才检查取消并静默删除）；
     * 真正的网络失败仍以 {@link IOException} 上抛。
     *
     * @param url      新版 exe 下载直链
     * @param target   目标文件（原 {@code *.new}）
     * @param progress 0-100 进度回调
     * @param cancel   取消标志
     * @return 下载成功返回 true；用户取消返回 false
     */
    public static boolean downloadExe(String url, Path target,
                                      IntConsumer progress, AtomicBoolean cancel) throws IOException {
        try {
            HttpDownloadEngine.downloadFileWithProgress(url, target, progress, cancel);
            return true;
        } catch (IOException e) {
            // 用户取消：不计为下载失败，交由调用方按 cancel 标志静默清理
            if (cancel != null && cancel.get()) {
                return false;
            }
            throw e;
        }
    }
}
