package com.example.starlight.download.task;

import com.example.starlight.download.engine.HttpDownloadEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * 色盲矫正工具（ColorBlindOverlay.exe）下载编排抽取类（UI_EMBED-2）。
 *
 * <p>把原 {@code ColorBlindPage.downloadOverlay} 直连 {@link HttpDownloadEngine} 的
 * 「探测大小 + 流式下载」抽入本类，UI 侧只保留任务卡对话框与下载后动作。
 *
 * <p>本类<b>不 import 任何 newui/gui 包</b>，进度走 {@link IntConsumer}、取消走 {@link AtomicBoolean}。
 */
public final class OverlayDownloader {

    private OverlayDownloader() {
    }

    /** 下载取消的统一异常文案（透传 {@link HttpDownloadEngine}，供 UI catch 区分「取消」与「失败」） */
    public static final String CANCELLED_MESSAGE = HttpDownloadEngine.CANCELLED_MESSAGE;

    /**
     * 探测矫正工具大小（供任务卡 {@code totalBytes} / 速度显示）。
     *
     * @param url 下载直链
     * @return 文件总大小；探测失败时返回 -1
     */
    public static long probeSize(String url) {
        return HttpDownloadEngine.probeFileSize(HttpDownloadEngine.HTTP_CLIENT, url);
    }

    /**
     * 下载色盲矫正工具 exe 到目标路径。
     *
     * @param url      下载直链
     * @param target   目标文件
     * @param progress 0-100 进度回调
     * @param cancel   取消标志
     * @return 下载成功返回 true
     */
    public static boolean download(String url, Path target,
                                   IntConsumer progress, AtomicBoolean cancel) throws IOException {
        HttpDownloadEngine.downloadFileWithProgress(url, target, progress, cancel);
        return true;
    }
}
