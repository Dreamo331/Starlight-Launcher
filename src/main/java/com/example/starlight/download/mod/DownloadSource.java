package com.example.starlight.download.mod;

import com.example.starlight.util.MirrorCircuitBreaker;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 候选下载源（对标 PCL NetSource）。
 *
 * <p>每个文件持有一组按优先级排序的 URL（如 官方 → BMCLAPI → MCBBS），
 * 每条源记录 failCount 与 disabled 标记。
 *
 * <p><b>禁用触发条件（PCL §2.2，满足任一即禁用）</b>：
 * <ol>
 *   <li>{@link RangeNotSupportedException} 或 HTTP 416；</li>
 *   <li>HTTP 502 / 404；</li>
 *   <li>DNS 解析失败 / 连接无返回数据 / 磁盘空间不足；</li>
 *   <li>HTTP 403 / 429（<b>例外：bmclapi 的 403/429 是频率限制，不禁用</b>）；</li>
 *   <li>该源从未拿到任何字节（done&lt;1）且 failCount ≥ clamp(全局线程数, 5, 30)；</li>
 *   <li>failCount &gt; 全局线程数 + 2。</li>
 * </ol>
 *
 * <p>与域名级 {@link MirrorCircuitBreaker} 两级并存串联：请求前先问
 * {@link MirrorCircuitBreaker#allowMirror()}，再问 {@link #disabled}。
 */
public final class DownloadSource {

    /** 候选 URL */
    private final String url;

    /** host（从 URI 解析，用于 HostPolicy / bmclapi 例外判定） */
    private final String host;

    /** 已失败次数（成功即清零） */
    private final AtomicInteger failCount = new AtomicInteger(0);

    /** 是否已禁用（PCL IsFailed） */
    private volatile boolean disabled = false;

    /** 是否单线程源（不支持 Range 的源 / 命中 HostPolicy 的源） */
    private volatile boolean singleThread = false;

    /** 最近一次错误文案 */
    private volatile String lastError;

    /** 域名级熔断器（可空；与文件级 disabled 串联判定） */
    private final MirrorCircuitBreaker breaker;

    public DownloadSource(String url) {
        this(url, null);
    }

    public DownloadSource(String url, MirrorCircuitBreaker breaker) {
        this.url = url;
        this.breaker = breaker;
        String h;
        try {
            h = URI.create(url).getHost();
        } catch (Exception e) {
            h = null;
        }
        this.host = h;
    }

    public String getUrl() {
        return url;
    }

    public String getHost() {
        return host;
    }

    public int getFailCount() {
        return failCount.get();
    }

    public boolean isDisabled() {
        return disabled;
    }

    public boolean isSingleThread() {
        return singleThread;
    }

    public void setSingleThread(boolean singleThread) {
        this.singleThread = singleThread;
    }

    public String getLastError() {
        return lastError;
    }

    /** 是否 bmclapi 源（其 403/429 是限流，不禁用） */
    public boolean isBmclapi() {
        return host != null && host.toLowerCase().contains("bmclapi");
    }

    /**
     * 该源当前是否允许发请求：域名熔断放行 且 文件级未禁用 且 地址段未被
     * {@link SourceHealth} 判定为系统性 404。
     * 三级串联——任一不过都不试。
     */
    public boolean isAllowed() {
        if (disabled) return false;
        if (breaker != null && !breaker.allowMirror()) return false;
        // 容器级健康表：镜像某个地址段连续 404 后跳过它，避免每个文件都白付一次 404
        return !SourceHealth.getInstance().isBlocked(url);
    }

    /** 任一时刻拿到有效字节即清零（PCL 成功即清零） */
    public void recordSuccess() {
        failCount.set(0);
        if (breaker != null) breaker.recordSuccess();
        SourceHealth.getInstance().recordSuccess(url);
    }

    /** 记录一次失败；返回失败后的 failCount */
    public int recordFailure(Throwable error) {
        this.lastError = error == null ? null : error.toString();
        if (breaker != null) breaker.recordFailure();
        return failCount.incrementAndGet();
    }

    /** 标记禁用（由第一个判定它的线程执行，其余线程静默退出） */
    public void markDisabled(String reason) {
        this.disabled = true;
        this.lastError = reason;
    }

    /**
     * PCL §2.2 条件 1/2/4：按 HTTP 状态码判断是否应禁用本源。
     *
     * @param statusCode 响应状态码
     * @return true 应禁用
     */
    public boolean shouldDisableOnHttpStatus(int statusCode) {
        switch (statusCode) {
            case 416:                     // 条件 1：Range 不可满足
            case 502:                     // 条件 2
            case 404:
                return true;
            case 403:                     // 条件 4：bmclapi 例外
            case 429:
                return !isBmclapi();
            default:
                return false;
        }
    }

    /**
     * PCL §2.2 条件 5/6：按 failCount 与是否「从未拿到字节」判断。
     *
     * @param gotAnyBytes    本源到目前为止是否拿到过任何字节
     * @param globalThreads  当前全局线程数
     * @return true 应禁用
     */
    public boolean shouldDisableOnFailCount(boolean gotAnyBytes, int globalThreads) {
        int fc = failCount.get();
        // 条件 6：failCount > 全局线程数 + 2
        if (fc > globalThreads + 2) return true;
        // 条件 5：从未拿到字节 且 failCount ≥ clamp(全局线程数, 5, 30)
        if (!gotAnyBytes) {
            int threshold = Math.max(5, Math.min(30, globalThreads));
            if (fc >= threshold) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "DownloadSource{url=" + url + ", host=" + host + ", fail=" + failCount.get()
                + ", disabled=" + disabled + ", singleThread=" + singleThread + '}';
    }
}
