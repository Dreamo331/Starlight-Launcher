/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局网络请求与流量统计（开发者悬浮窗数据源；与调试模式无关，全程计数）。
 *
 * <p><b>请求计数</b>挂在 {@link DebugLog#http} 的入口——全应用的 HTTP 埋点统一经过这里
 * （下载引擎 / 版本清单 / 登录认证 / 模组 API 等 13 处调用方），把计数放在
 * {@code DEBUG_MODE} 判断之前即实现「不开调试也统计」。
 *
 * <p><b>字节计数</b>挂各下载引擎的读取循环（DownloadPiece / ChunkedDownloader /
 * HttpDownloadEngine / AssetDownloader / VersionManifest / GameResourceCompleter /
 * LibraryDownloader / InstallEngine / JavaInstall）；FileVerifier 是本地文件哈希读取，不计。
 *
 * <p>口径：{@code 成功} = 2xx 且正文非空；{@code 空返回} = 2xx 但正文 0 字节（BMCLAPI
 * 典型故障形态，与 SourceHealth 的空正文判定同口径）；{@code 失败} = HTTP ≥ 400；
 * 3xx / 状态未知不计入成功失败。
 */
public final class NetTrafficStats {

    private static final AtomicLong SENT = new AtomicLong();
    private static final AtomicLong SUCCESS = new AtomicLong();
    private static final AtomicLong FAILURE = new AtomicLong();
    private static final AtomicLong EMPTY = new AtomicLong();
    private static final AtomicLong RECEIVED = new AtomicLong();
    private static final AtomicLong BYTES = new AtomicLong();

    private NetTrafficStats() {
    }

    /** DebugLog.http 入口统一回调（direction：true=发送请求，false=收到响应） */
    public static void onHttp(boolean direction, int status, long bytes) {
        if (direction) {
            SENT.incrementAndGet();
            return;
        }
        RECEIVED.incrementAndGet();
        if (status >= 400) {
            FAILURE.incrementAndGet();
        } else if (status >= 200 && status < 300) {
            if (bytes <= 0) {
                EMPTY.incrementAndGet();
            } else {
                SUCCESS.incrementAndGet();
            }
        }
    }

    /** 下载引擎读取循环逐块回调（仅网络读取，本地文件哈希不调） */
    public static void recordBytes(long n) {
        if (n > 0) {
            BYTES.addAndGet(n);
        }
    }

    public static long getSent() {
        return SENT.get();
    }

    public static long getSuccess() {
        return SUCCESS.get();
    }

    public static long getFailure() {
        return FAILURE.get();
    }

    public static long getEmpty() {
        return EMPTY.get();
    }

    public static long getReceived() {
        return RECEIVED.get();
    }

    public static long getBytes() {
        return BYTES.get();
    }

    /** 清零（开发者悬浮窗「清零」按钮） */
    public static void reset() {
        SENT.set(0);
        SUCCESS.set(0);
        FAILURE.set(0);
        EMPTY.set(0);
        RECEIVED.set(0);
        BYTES.set(0);
    }
}
