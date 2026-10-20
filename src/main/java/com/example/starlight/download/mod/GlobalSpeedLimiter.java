package com.example.starlight.download.mod;

import com.example.starlight.download.DownloadSettings;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 全局令牌桶限速（对标 PCL SpeedLimitLeft，单例）。
 *
 * <p>限速放在「全局总量层」，不是线程/文件层：一个 JVM 级令牌桶，所有分块线程共享。
 *
 * <ul>
 *   <li>{@link #tokens} 为剩余可下载字节预算；</li>
 *   <li>后台调度每 <b>100ms</b> 滴答一次，向桶里补充 {@code bytesPerSecond / 10}（1 秒预算分 10 次补）；</li>
 *   <li>每个读循环每消费 N 字节就 {@code tokens.addAndGet(-N)}；耗尽时该线程
 *       {@link LockSupport#parkNanos} 16ms 自旋等待补充（与 PCL 一致的 16ms 自旋粒度）；</li>
 *   <li>{@link #setLimit(long)} 传 -1 表示不限速（不 refill，桶可空转，acquire 直接放行）。</li>
 * </ul>
 *
 * <p>「低限」不是死限速，而是 PCL §3 里的<b>加速触发线</b>：当实测全局速度低于此线时，
 * {@link DownloadCoordinator} 才为慢文件追加分裂分块线程。该线由
 * {@link DownloadSettings#getLowSpeedThresholdBps()} 提供（设置页「高级下载设置」可调，
 * 默认 256KB/s，文档建议 512KB/s~1MB/s）；PCL 原实现还会把低限抬到「近 1 秒实测均速的 85%」，
 * 列为二期增强。
 */
public final class GlobalSpeedLimiter {

    /** 加速触发线：全局实测速度低于此值才追加分块线程（默认 256KB/s）。 */
    public static final long LOW_SPEED_THRESHOLD_BPS = 256L * 1024L;

    /** 令牌桶补充周期：100ms（PCL 同款） */
    private static final long REFILL_INTERVAL_MS = 100L;

    /** acquire 自旋 park 粒度：16ms（PCL 同款） */
    private static final long PARK_NANOS = 16_000_000L;

    private static final GlobalSpeedLimiter INSTANCE = new GlobalSpeedLimiter();

    /** 剩余字节预算 */
    private final AtomicLong tokens = new AtomicLong(0);

    /** 当前限速 bytes/sec；-1 表示不限速 */
    private volatile long bytesPerSecond = -1L;

    private final ScheduledExecutorService refillLoop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sl-speed-limiter");
        t.setDaemon(true);
        return t;
    });

    private GlobalSpeedLimiter() {
        refillLoop.scheduleAtFixedRate(this::refillOnce, REFILL_INTERVAL_MS, REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public static GlobalSpeedLimiter getInstance() {
        return INSTANCE;
    }

    /** 100ms 滴答：按 1 秒预算的 1/10 补充令牌；不限速时不 refill */
    private void refillOnce() {
        long limit = bytesPerSecond;
        if (limit <= 0) {
            // 不限速：桶不补，但 acquire 侧对 -1 直接放行，这里无需动作
            return;
        }
        tokens.addAndGet(limit / 10L);
    }

    /**
     * 消费 bytes 字节预算；桶空则 park 16ms 自旋等待补充。
     * 不限速（limit=-1）时直接返回。
     *
     * <p><b>不限速守卫不可省略</b>：refillOnce 在不限速时永不补充令牌，缺失守卫的
     * acquire 会在第一次调用时把 tokens 扣成负数并无限自旋（2026-10-11 实测事故：
     * 全部分块卡死在第一次 read 之后的 acquire，0 字节 0 进度且永不失败）。
     */
    public void acquire(int bytes) {
        if (bytes <= 0) return;
        if (bytesPerSecond <= 0) return; // 不限速：不进桶
        // 只扣可用额度，不足部分等待补充——不能每轮整额重扣（tokens 越扣越负永不收敛）
        while (true) {
            long current = tokens.get();
            if (current >= bytes) {
                if (tokens.compareAndSet(current, current - bytes)) return;
            } else if (current > 0) {
                // 部分消费：先拿走现有预算，剩余字节等下一轮 refill
                if (tokens.compareAndSet(current, 0)) bytes -= current;
            } else {
                LockSupport.parkNanos(PARK_NANOS);
            }
        }
    }

    /** 设置限速（bytes/sec）；-1 表示不限速 */
    public void setLimit(long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
        if (bytesPerSecond <= 0) {
            tokens.set(0);
        }
    }

    /** 当前限速值（bytes/sec，-1 = 不限速） */
    public long getLimit() {
        return bytesPerSecond;
    }

    /** 加速触发线查询：全局实测速度是否低于慢速触发线（设置页可调，默认 256KB/s） */
    public static boolean isBelowLowSpeedThreshold(long observedBps) {
        return observedBps < DownloadSettings.getLowSpeedThresholdBps();
    }
}
