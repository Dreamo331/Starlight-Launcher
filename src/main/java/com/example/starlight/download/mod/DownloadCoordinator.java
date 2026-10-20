package com.example.starlight.download.mod;

import com.example.starlight.download.DownloadSettings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局单例调度器（对标 PCL NetManager）。
 *
 * <p>关键认知：<b>PCL 不是「每文件开 N 个线程」，而是全应用共享一个总线程池配额，
 * 由调度器动态把线程分配给「最值得加速」的文件</b>（正在下且速度慢、且最大碎片足够大的文件）。
 *
 * <ul>
 *   <li>持有全局 {@link ExecutorService}（线程数取
 *       {@link DownloadSettings#getDownloadThreads()}，范围 1~64）；</li>
 *   <li>{@link ConcurrentMap}&lt;Path, DownloadFileTask&gt; 按目标路径去重——
 *       同一文件被多个任务需要时共享一个任务对象；</li>
 *   <li><b>20ms 播种循环</b>：对 WAITING 中的文件，只要全局线程未满就 TryBeginThread；
 *       对进行中文件，仅当全局速度低于慢速触发线（{@link DownloadSettings#getLowSpeedThresholdBps()}，
 *       设置页可调，默认 256KB/s）才追加线程（避免一窝蜂建立连接）；</li>
 *   <li>全局速度统计（近 1 秒滑动窗口字节数）；</li>
 *   <li>{@link #submit(Collection)} 批量提交；{@link #shutdown()} 关闭。</li>
 * </ul>
 *
 * <p>本类只负责「字节怎么高效、可靠地下完一个文件列表」：不碰 UI（对外提供进度查询）、
 * 不解析版本 JSON（由上层 VersionDownloadService 生成 DownloadFileTask 喂入）。
 *
 * <p><b>池容量可运行时调整</b>：因为是「全局配额上限」语义（并非每个提交都占满线程），
 * 用户把「并发下载数」调大/调小时调用 {@link #setThreadLimit(int)} 就地重建线程池：
 * 老池 {@code shutdown()} 让其已跑分块自然跑完（不打断在下的字节），新池承接后续分块。
 */
public final class DownloadCoordinator {

    /** 播种循环间隔：20ms（PCL 同款两个播种线程轮询粒度） */
    public static final long SEED_INTERVAL_MS = 20L;

    /** 全局速度统计窗口：1 秒 */
    private static final long SPEED_WINDOW_MS = 1_000L;

    private static final DownloadCoordinator INSTANCE = new DownloadCoordinator();

    /** 全局下载线程池（线程数 = 用户设置，1~64） */
    private volatile ExecutorService pool;

    /** 当前线程池容量（{@link #setThreadLimit(int)} 重建池时同步更新） */
    private volatile int poolSize;

    /** 被 {@link #setThreadLimit(int)} 淘汰的老池：等其排队任务跑完，由 shutdown() 统一收尾 */
    private final List<ExecutorService> retiredPools = new ArrayList<>();

    /** 兜底池：协调器已关闭后仍要执行的分块（避免在调用方线程同步下载） */
    private final ExecutorService fallbackPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sl-dl-piece-fallback");
        t.setDaemon(true);
        return t;
    });

    /** 20ms 播种循环 + 速度统计 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sl-coordinator");
        t.setDaemon(true);
        return t;
    });

    /** 按目标路径去重的任务表 */
    private final ConcurrentMap<Path, DownloadFileTask> tasks = new ConcurrentHashMap<>();

    /** 当前在跑的全局分块线程数 */
    private final AtomicInteger activeThreads = new AtomicInteger(0);

    /** 近 1 秒窗口内累计字节（全局速度统计） */
    private final AtomicLong windowBytes = new AtomicLong(0);
    private volatile long observedBps = 0L;

    private DownloadCoordinator() {
        this.poolSize = clampThreads(DownloadSettings.getDownloadThreads());
        this.pool = newPool(poolSize);
        // 20ms 播种
        scheduler.scheduleAtFixedRate(this::seedTick, SEED_INTERVAL_MS, SEED_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // 100ms 刷新全局速度（PCL 同款统计周期）
        scheduler.scheduleAtFixedRate(this::refreshSpeed, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** 建一个命名线程池（容量 = 全局配额上限） */
    private static ExecutorService newPool(int threads) {
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "sl-dl-piece");
            t.setDaemon(true);
            return t;
        });
    }

    public static DownloadCoordinator getInstance() {
        return INSTANCE;
    }

    private static int clampThreads(int raw) {
        return Math.max(DownloadSettings.MIN_THREADS, Math.min(DownloadSettings.MAX_THREADS, raw));
    }

    /**
     * 批量提交文件任务（按目标路径去重：已存在的任务不重复提交）。
     */
    public void submit(Collection<DownloadFileTask> newTasks) {
        for (DownloadFileTask t : newTasks) {
            DownloadFileTask existing = tasks.putIfAbsent(t.getTarget(), t);
            if (existing != null) continue; // 同一目标已有任务，共享
            pool.submit(t::run);
        }
    }

    /**
     * 把已到终态（SUCCESS / FAILED / CANCELLED）的任务移出去重表。
     *
     * <p>不移除的话，同路径的下一次提交会被 {@link #submit} 的 putIfAbsent 静默丢弃，
     * 调用方轮询自己的新实例永远拿不到结果（表现为进度 0% 且零网络流量）。
     * 仅当表中仍是本实例时移除（两参 remove），避免误删并发提交的新任务。
     */
    public void forget(DownloadFileTask task) {
        tasks.remove(task.getTarget(), task);
    }

    /** 当前在跑的全局分块线程数 */
    public int getActiveThreads() {
        return activeThreads.get();
    }

    /** 当前全局线程池上限 */
    public int getThreadLimit() {
        return clampThreads(DownloadSettings.getDownloadThreads());
    }

    /**
     * 就地调整全局线程池容量（设置页保存「并发下载数」后调用，立即生效）。
     *
     * <p>容量没变直接返回。老池用 {@code shutdown()}（非 {@code shutdownNow()}）：
     * 已提交的分块与已排队的文件任务继续跑完，不产生半截 .part；新分块走新池。
     * 老池进了 {@link #retiredPools} 由 {@link #shutdown()} 统一收尾。
     *
     * @return 是否真的重建了线程池
     */
    public synchronized boolean setThreadLimit(int threads) {
        int target = clampThreads(threads);
        if (target == poolSize) return false;
        ExecutorService old = pool;
        pool = newPool(target);
        poolSize = target;
        // shutdown 而非 shutdownNow：排队中的分块继续跑完，避免留下半截 .part
        old.shutdown();
        retiredPools.add(old);
        return true;
    }

    /** 当前线程池容量（与 {@link #getThreadLimit()} 的区别：本值反映池的实际大小） */
    public int getPoolSize() {
        return poolSize;
    }

    /** 近 1 秒观测到的全局速度（B/s） */
    public long getObservedBps() {
        return observedBps;
    }

    /** 分块线程上报字节（限速器之外，喂全局速度统计） */
    void recordBytes(int n) {
        windowBytes.addAndGet(n);
    }

    /**
     * 由 DownloadFileTask 调用：把一个分块交给全局池执行。
     * 负责全局线程计数与 piece 结束回调。
     *
     * <p>提交失败要区分两种原因（否则分块会永远停在 WAITING，任务在
     * {@code waitAllPieces} 里死等）：
     * <ul>
     *   <li>恰好撞上 {@link #setThreadLimit(int)} 换池、被老池拒绝 → 往新池再提交一次；</li>
     *   <li>协调器已 {@link #shutdown()} → 走 {@link #fallbackPool}，不要在调用方线程
     *       （可能是 20ms 播种循环）上同步跑下载。</li>
     * </ul>
     */
    void executePiece(DownloadFileTask task, DownloadPiece piece) {
        activeThreads.incrementAndGet();
        Runnable job = () -> {
            try {
                piece.run(task, GlobalSpeedLimiter.getInstance());
                task.onPieceFinished(piece, null);
            } catch (Throwable t) {
                task.onPieceFinished(piece, t);
            }
        };
        try {
            pool.submit(job);
        } catch (RejectedExecutionException rejected) {
            try {
                pool.submit(job);
            } catch (RejectedExecutionException closed) {
                // 协调器已关闭（应用退出等）：换一条独立线程跑完回调，保持计数与状态一致
                fallbackPool.execute(job);
            }
        }
    }

    /** 20ms 播种循环 */
    private void seedTick() {
        try {
            int limit = getThreadLimit();
            boolean speedLow = GlobalSpeedLimiter.isBelowLowSpeedThreshold(observedBps);
            List<DownloadFileTask> snapshot = new ArrayList<>(tasks.values());
            for (DownloadFileTask t : snapshot) {
                DownloadFileTask.State s = t.getState();
                if (s == DownloadFileTask.State.WAITING || s == DownloadFileTask.State.DOWNLOADING) {
                    if (activeThreads.get() < limit) {
                        // WAITING 文件：只要全局线程未满就起线程
                        if (s == DownloadFileTask.State.WAITING) {
                            t.tryBeginThread();
                        } else if (speedLow) {
                            // 进行中文件：仅当全局速度低于低限才追加线程
                            // 且准备中 ≤ 下载中才追加（避免一窝蜂建连）
                            // （tryBeginThread 内部已做 HostPolicy / SourcesOnce 过滤）
                            t.tryBeginThread();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 播种循环绝不能因单任务异常退出
        }
    }

    /** 100ms 刷新近 1 秒平均速度 */
    private void refreshSpeed() {
        long window = windowBytes.getAndSet(0);
        observedBps = window * 1000L / SPEED_WINDOW_MS;
    }

    /** 关闭：停播种循环与线程池；已下完的文件不受影响 */
    public synchronized void shutdown() {
        scheduler.shutdownNow();
        pool.shutdownNow();
        for (ExecutorService retired : retiredPools) retired.shutdownNow();
        retiredPools.clear();
        fallbackPool.shutdownNow();
        tasks.clear();
    }
}
