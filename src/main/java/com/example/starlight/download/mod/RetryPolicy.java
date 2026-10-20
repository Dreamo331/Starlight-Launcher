package com.example.starlight.download.mod;

import com.example.starlight.download.DownloadSettings;

/**
 * 重试与掐线参数/计算（对标 PCL §5.1，纯参数/计算类，无状态）。
 *
 * <ul>
 *   <li><b>连接超时自适应</b>：{@code timeout = min(max(历史平均连接耗时, 15s) × (1 + 源.failCount), 30s)}
 *       ——失败越多容忍越久，但封顶 30s；</li>
 *   <li><b>慢速掐线</b>：两包间隔 &gt; 阈值秒 且该间隔内只收到不足约 1KB 且<b>非单线程源</b>
 *       → 主动断开重连（单线程源掐了就没了，不掐）；</li>
 *   <li><b>合并退避</b>：拼接文件最多重试 N 次，退避 基数ms × 次数；用尽后删残文件、
 *       把当前源标记失败并整体换源重下一轮。</li>
 *   <li>任一时刻收到有效字节，该源与任务的 FailCount 立即归零（成功即清零，见
 *       {@link DownloadSource#recordSuccess()}）。</li>
 * </ul>
 *
 * <p>慢速掐线阈值 / 合并重试次数 / 退避基数的<b>运行时真源</b>是 {@link DownloadSettings}
 * （默认值与下方常量一致；开发者选项页可在运行中调整），常量仅作为文档与兜底保留。
 */
public final class RetryPolicy {

    /** 连接超时基数：15s */
    public static final long CONNECT_TIMEOUT_BASE_MS = 15_000L;

    /** 连接超时封顶：30s */
    public static final long CONNECT_TIMEOUT_CAP_MS = 30_000L;

    /** 慢速掐线：两包间隔阈值默认 5s（运行时值见 {@link DownloadSettings#getSlowGapSec()}） */
    public static final long SLOW_GAP_MS = 5_000L;

    /** 慢速掐线：该间隔内不足约 1KB 即判死 */
    public static final long SLOW_GAP_BYTES = 1_024L;

    /** 合并重试次数上限默认 3（运行时值见 {@link DownloadSettings#getMergeRetry()}） */
    public static final int MERGE_MAX_ATTEMPTS = 3;

    /** 合并退避基数默认 500ms × 次数（运行时值见 {@link DownloadSettings#getMergeBackoffMs()}） */
    public static final long MERGE_BACKOFF_BASE_MS = 500L;

    private RetryPolicy() {
    }

    /**
     * 自适应连接超时：失败越多容忍越久，但封顶 30s。
     *
     * @param avgConnectMs    历史平均连接耗时（ms）
     * @param sourceFailCount 当前源已失败次数
     * @return 本次连接应使用的超时（ms）
     */
    public static long connectTimeoutMs(long avgConnectMs, int sourceFailCount) {
        long base = Math.max(avgConnectMs, CONNECT_TIMEOUT_BASE_MS);
        long scaled = base * (1L + Math.max(0, sourceFailCount));
        return Math.min(scaled, CONNECT_TIMEOUT_CAP_MS);
    }

    /**
     * 慢速掐线判定。
     *
     * @param gapMs            距上一包的间隔（ms）
     * @param bytesInGap       该间隔内收到的字节数
     * @param singleThreadSource 是否当前挂在单线程源上（单线程源不掐线）
     * @return true 应断开重连
     */
    public static boolean isTooSlow(long gapMs, long bytesInGap, boolean singleThreadSource) {
        if (singleThreadSource) return false;
        return gapMs > DownloadSettings.getSlowGapSec() * 1000L && bytesInGap < SLOW_GAP_BYTES;
    }

    /** 合并重试次数上限（运行时值，开发者选项页可调） */
    public static int mergeMaxAttempts() {
        return DownloadSettings.getMergeRetry();
    }

    /** 合并第 attempt 次（0 起）失败后的退避时长 */
    public static long mergeBackoffMs(int attempt) {
        return DownloadSettings.getMergeBackoffMs() * (attempt + 1L);
    }
}
