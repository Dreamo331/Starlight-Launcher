package com.example.starlight.download.mod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 批内下载等待池（2026-09-30 下载等待池方案 §2.2 / §4）。
 *
 * <p>补全批量下载时，0B / 停滞等「疑似慢源」失败不再当场判死，而是先进等待池：
 * 主波次结束后按退避窗口延迟重试，重试仍失败才计最终失败。本类只负责<b>任务容器</b>，
 * 具体下载编排（候选源循环、健康表）由调用方 {@code GameResourceCompleter} 完成。
 *
 * <p>特性：
 * <ul>
 *   <li>保序去重：按入池顺序（{@link LinkedHashSet}），同一任务重复 offer 只保留一份；</li>
 *   <li>容量上限：超出 {@link #DEFAULT_CAPACITY} 的部分 {@link #offer} 返回 false，
 *       由调用方直接计失败，防止内存膨胀；</li>
 *   <li>线程安全：全部操作在同一把锁内，可被多个工作线程并发 offer；</li>
 *   <li>取消检查：传入 {@link AtomicBoolean} 取消标志后，取消状态下 {@link #offer} /
 *       {@link #drain} 直接失效，已入池任务不残留；</li>
 *   <li>退避计算：{@link #backoffMs(int)} 供批主线程等待用（等待不占线程池槽位）。</li>
 * </ul>
 *
 * @param <T> 等待重试的任务类型（如补全的 AssetTask）
 */
public final class RetryQueue<T> {

    /** 等待池容量上限（方案 §4-6）：超出部分直接计失败 */
    public static final int DEFAULT_CAPACITY = 2000;

    /** 退避基数（方案 §2.5）：1s 起指数增长 */
    public static final long WAIT_BASE_MS = 1_000L;

    /** 退避上限（方案 §2.5）：30s */
    public static final long WAIT_MAX_MS = 30_000L;

    /** 退避随机抖动比例（方案 §2.2）：±20%，让各任务错峰重试 */
    public static final double JITTER = 0.2;

    private final int capacity;
    private final AtomicBoolean cancel;
    private final Set<T> items = new LinkedHashSet<>();
    private final Object lock = new Object();

    /**
     * @param capacity 池容量上限（&gt;=1）
     * @param cancel   取消标志（可为 null；非 null 时取消状态下入池/取出失效）
     */
    public RetryQueue(int capacity, AtomicBoolean cancel) {
        this.capacity = Math.max(1, capacity);
        this.cancel = cancel;
    }

    public RetryQueue() {
        this(DEFAULT_CAPACITY, null);
    }

    /**
     * 入池。取消状态下或容量已满返回 false（调用方按「直接计失败」处理，方案 §4-6）。
     *
     * @return true 表示已入池（或已存在）；false 表示被拒（取消 / 容量满）
     */
    public boolean offer(T item) {
        if (isCancelled()) return false;
        synchronized (lock) {
            if (items.size() >= capacity) {
                return false;
            }
            return items.add(item);
        }
    }

    /** 当前池中任务数 */
    public int size() {
        synchronized (lock) {
            return items.size();
        }
    }

    /** 池是否为空 */
    public boolean isEmpty() {
        synchronized (lock) {
            return items.isEmpty();
        }
    }

    /**
     * 取出全部等待任务并清空（本轮重试的输入快照）。
     * 取消状态下返回空列表（由调用方中止重试流程）。
     */
    public List<T> drain() {
        if (isCancelled()) {
            return new ArrayList<>();
        }
        synchronized (lock) {
            List<T> out = new ArrayList<>(items);
            items.clear();
            return out;
        }
    }

    /** 快照（不改变池内容，诊断 / 日志用） */
    public List<T> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(items);
        }
    }

    /** 清空池（取消清理用） */
    public void clear() {
        synchronized (lock) {
            items.clear();
        }
    }

    /**
     * 退避时长：1s 起按 2 的幂指数增长（1s / 2s / 4s / 8s …），上限 {@link #WAIT_MAX_MS}，
     * 叠加 ±{@link #JITTER}（20%）随机抖动，避免所有重试任务同一时刻再次集中请求（§2.2）。
     *
     * @param round 重试轮次（从 1 开始）
     */
    public static long backoffMs(int round) {
        int shift = Math.max(0, Math.min(round - 1, 10));
        long base = WAIT_BASE_MS << shift;
        long capped = Math.min(base, WAIT_MAX_MS);
        long jitterRange = Math.max(1L, Math.round(capped * JITTER));
        double delta = (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * jitterRange;
        return Math.max(100L, capped + Math.round(delta));
    }

    private boolean isCancelled() {
        return cancel != null && cancel.get();
    }
}
