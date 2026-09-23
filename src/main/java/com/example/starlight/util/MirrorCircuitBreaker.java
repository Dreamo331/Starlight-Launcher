package com.example.starlight.util;

/**
 * 镜像熔断器：镜像连续失败达到阈值后，在冷却期内直接跳过镜像走官方源。
 *
 * <p>没有这层保护时，镜像（mcimirror / bmclapi）一挂，每个请求都要先付一次完整的
 * 镜像超时才能开始回落官方，一屏十几个请求叠加后列表要等十几秒才有反应。
 * 冷却期内跳过镜像后，冷却结束的下一次请求会再试一次镜像，恢复了就自动切回。
 */
public final class MirrorCircuitBreaker {

    private final int failureThreshold;
    private final long cooldownMs;

    private int consecutiveFailures;
    private long blockedUntil;

    /**
     * @param failureThreshold 连续失败多少次后熔断
     * @param cooldownMs       熔断持续毫秒数，冷却结束后放行一次探测请求
     */
    public MirrorCircuitBreaker(int failureThreshold, long cooldownMs) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMs = cooldownMs;
    }

    /** 当前是否允许尝试镜像 */
    public synchronized boolean allowMirror() {
        return System.currentTimeMillis() >= blockedUntil;
    }

    /** 记录一次镜像失败；连续失败达到阈值即进入冷却期 */
    public synchronized void recordFailure() {
        if (++consecutiveFailures >= failureThreshold) {
            blockedUntil = System.currentTimeMillis() + cooldownMs;
            consecutiveFailures = 0;
        }
    }

    /** 镜像成功：清零失败计数并解除熔断 */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        blockedUntil = 0;
    }
}
