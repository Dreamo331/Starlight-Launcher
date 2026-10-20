package com.example.starlight.util;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 镜像熔断器：镜像连续失败达到阈值后，在冷却期内直接跳过镜像走官方源。
 *
 * <p>没有这层保护时，镜像（mcimirror / bmclapi）一挂，每个请求都要先付一次完整的
 * 镜像超时才能开始回落官方，一屏十几个请求叠加后列表要等十几秒才有反应。
 * 冷却期内跳过镜像后，冷却结束的下一次请求会再试一次镜像，恢复了就自动切回。
 *
 * <p>此外还提供 {@link #allowMirrorProbe()} 的<b>冷启动并发抑制</b>：进程刚启动、
 * 熔断器还没有任何失败记录时，十几个并发元数据请求会一起撞镜像（惊群），每路都付满
 * 镜像超时；该方法让其中一路当探测、其余立即走官方。
 *
 * <p>阈值 / 冷却的运行时真源在 {@code DownloadSettings}（开发者选项页可调）：
 * 新实例按当前配置构造，{@link #applyGlobalParams(int, long)} 同步更新已存在的全部实例。
 */
public final class MirrorCircuitBreaker {

    /** 全部存活实例（个位数，无需弱引用）：调参时统一刷新 */
    private static final CopyOnWriteArrayList<MirrorCircuitBreaker> LIVE = new CopyOnWriteArrayList<>();

    /** 同步更新全部实例的阈值 / 冷却（开发者选项页「应用」时调用） */
    public static void applyGlobalParams(int failureThreshold, long cooldownMs) {
        int t = Math.max(1, failureThreshold);
        long c = Math.max(1_000L, cooldownMs);
        for (MirrorCircuitBreaker b : LIVE) {
            b.updateParams(t, c);
        }
    }

    private volatile int failureThreshold;
    private volatile long cooldownMs;

    private int consecutiveFailures;
    private long blockedUntil;

    /**
     * 冷启动并发抑制：本进程内镜像<b>一次都没成功过</b>时，只放行一个并发探测请求走镜像，
     * 其余请求立即回落官方。
     *
     * <p>要解决的问题：下载中心打开时会并发发起十几个元数据请求，而此刻熔断器还没有任何
     * 失败记录，于是<b>全部</b>请求都去试镜像、同时各付一次镜像超时（mod.mcimirror.top 挂掉时
     * 十几路 6s 叠加）。放行单个探测后，其余请求零等待走官方，探测成功后立刻恢复全员走镜像。
     */
    private boolean coldProbeInFlight;
    /** 镜像已在本进程内成功过：此后不再做冷启动抑制 */
    private volatile boolean mirrorEverSucceeded;
    /** 冷启动探测的开始时刻（用于探测卡死时兜底放行） */
    private volatile long coldProbeStartedAt;
    /** 冷启动探测最长压制时长：超过它视作探测卡死，放行其余请求，避免全局被一个探测堵死 */
    private static final long COLD_PROBE_MAX_MS = 3_000L;

    /**
     * @param failureThreshold 连续失败多少次后熔断
     * @param cooldownMs       熔断持续毫秒数，冷却结束后放行一次探测请求
     */
    public MirrorCircuitBreaker(int failureThreshold, long cooldownMs) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMs = Math.max(1_000L, cooldownMs);
        LIVE.add(this);
    }

    /** 运行中调参（仅由 {@link #applyGlobalParams(int, long)} 调用） */
    private synchronized void updateParams(int failureThreshold, long cooldownMs) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    /** 当前是否允许尝试镜像（只读判定，无副作用） */
    public synchronized boolean allowMirror() {
        return System.currentTimeMillis() >= blockedUntil;
    }

    /**
     * 与 {@link #allowMirror()} 相同的判定，但额外做<b>冷启动并发抑制</b>：镜像在本进程内
     * 一次都没成功过时，只放行一个并发探测请求走镜像，其余请求零等待回落官方。
     *
     * <p>仅限「会成对调用 {@link #recordSuccess()} / {@link #recordFailure()}」的调用方使用
     * （当前是 Modrinth / CurseForge 的元数据请求）。文件下载路径（{@code contentCandidates}）
     * 只在生成候选时问一次而不回报结果，用本方法会把探测名额一直占住，因此那边继续用
     * {@link #allowMirror()}。
     */
    public synchronized boolean allowMirrorProbe() {
        long now = System.currentTimeMillis();
        if (now < blockedUntil) return false;
        if (mirrorEverSucceeded) return true;
        if (coldProbeInFlight) {
            // 探测卡死（调用方既不记成功也不记失败）时兜底放行，避免一个探测堵死全部请求
            if (now - coldProbeStartedAt < COLD_PROBE_MAX_MS) return false;
            coldProbeInFlight = false;
        }
        coldProbeInFlight = true;
        coldProbeStartedAt = now;
        return true;
    }

    /** 记录一次镜像失败；连续失败达到阈值即进入冷却期 */
    public synchronized void recordFailure() {
        coldProbeInFlight = false;
        if (++consecutiveFailures >= failureThreshold) {
            blockedUntil = System.currentTimeMillis() + cooldownMs;
            consecutiveFailures = 0;
        }
    }

    /** 镜像成功：清零失败计数并解除熔断（此后不再做冷启动探测抑制） */
    public synchronized void recordSuccess() {
        coldProbeInFlight = false;
        mirrorEverSucceeded = true;
        consecutiveFailures = 0;
        blockedUntil = 0;
    }
}
