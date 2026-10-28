package com.example.starlight.download;

import com.example.starlight.download.mod.GlobalSpeedLimiter;

import java.util.Map;

/**
 * 全局下载设置（运行时生效，供各下载组件共享）。
 *
 * <p>本类是 download.mod 提速参数的<b>唯一运行时真源</b>：类内以 volatile 字段持有当前值，
 * 由「设置 → 下载 → 高级下载设置」页（{@link com.example.starlight.newui.page.settings.DownloadSettingsPage}）
 * 与启动时的 {@link #applyAll(Map)} 写入，下载组件每轮循环直接读字段——<b>改完立即生效，无需重启</b>。
 *
 * <p>各参数分区（与 {@code docs/2026-09-30-下载提速优化技术文档.md} §7 参数建议表对应）：
 * <ul>
 *   <li><b>并发</b>：{@link #getDownloadThreads()}——全局线程池配额（默认 8，上限 64）；</li>
 *   <li><b>分块</b>：{@link #getPieceLimitBytes()} 最小碎片、{@link #getSplitRatio()} 分裂点、
 *       {@link #getMultithreadThresholdBytes()} 才值得分块的门槛；</li>
 *   <li><b>速度</b>：{@link #getLowSpeedThresholdBps()} 慢速触发线（低于此线才追加分裂线程）、
 *       {@link #getSpeedLimitKbps()} 限速（0 = 不限速，只做全局令牌桶，不做线程级限速）。</li>
 * </ul>
 *
 * <p>配置项一律以<b>字符串</b>存在 {@code starlight-client.ini}（见
 * {@link com.example.starlight.main.ConfigManager#saveClientConfig}）：新增键必须同时登记到
 * {@code ConfigManager.CLIENT_KEYS}，否则保存后重启即丢。
 *
 * <h3>哪些参数只进开发者选项页</h3>
 * 播种周期（20ms）仍以常量实现；连接超时基数/上限、慢速掐线阈值、合并重试次数与退避、
 * 镜像熔断阈值/冷却已于 2026-10-11 接入本类（KEY_MERGE_RETRY 等 5 键），由开发者选项页
 * 调整——这些值调错会直接伤成功率，故不对普通用户暴露。
 */
public final class DownloadSettings {

    // 配置键（starlight-client.ini） //
    /** 并发下载线程数 */
    public static final String KEY_THREADS = "DownloadThreads";
    /** 最小碎片（KB，小于此值的碎片不再切分） */
    public static final String KEY_PIECE_LIMIT_KB = "DownloadPieceLimitKB";
    /** 分裂点（百分比 30~90，在剩余量的该比例处切出新分块） */
    public static final String KEY_SPLIT_PERCENT = "DownloadSplitPercent";
    /** 才值得分块的门槛（MB，小于此值的文件单线程下完） */
    public static final String KEY_THRESHOLD_MB = "DownloadMultithreadThresholdMB";
    /** 慢速触发线（KB/s，全局实测速度低于此值才追加分裂线程） */
    public static final String KEY_LOW_SPEED_KBPS = "DownloadLowSpeedKBps";
    /** 限速（KB/s，0 或空 = 不限速） */
    public static final String KEY_SPEED_LIMIT_KBPS = "DownloadSpeedLimitKBps";
    /** 停滞判定（秒）：单次 read 超过此时长无字节即判该源卡死并换源（「最后候选」用此值 × 放大系数） */
    public static final String KEY_STALL_TIMEOUT_SEC = "DownloadStallTimeoutSec";
    /** 首试停滞判定（秒）：非最后候选的快速停滞判定（默认 5s），坏源代价降到 1/3 */
    public static final String KEY_FIRST_TRY_STALL_SEC = "DownloadFirstTryStallSec";
    /** 建连超时（秒） */
    public static final String KEY_CONNECT_TIMEOUT_SEC = "DownloadConnectTimeoutSec";

    // 开发者选项页暴露的重试/熔断参数（二期「运行时参数」） //
    /** 合并重试次数上限（拼接文件失败重试次数，默认 3） */
    public static final String KEY_MERGE_RETRY = "DownloadMergeRetry";
    /** 合并退避基数（毫秒，实际退避 = 基数 × 次数，默认 500） */
    public static final String KEY_MERGE_BACKOFF_MS = "DownloadMergeBackoffMs";
    /** 慢速掐线阈值（秒：两包间隔超过此值且不足 1KB 即断开重连，默认 5） */
    public static final String KEY_SLOW_GAP_SEC = "DownloadSlowGapSec";
    /** 镜像熔断阈值（连续失败多少次后熔断，默认 2） */
    public static final String KEY_BREAKER_THRESHOLD = "DownloadBreakerThreshold";
    /** 镜像熔断冷却（秒，冷却结束放行探测请求，默认 60） */
    public static final String KEY_BREAKER_COOLDOWN_SEC = "DownloadBreakerCooldownSec";

    // 下载引擎选择（2026-10-21） //
    /**
     * 下载引擎键：{@code Starlight}（原引擎，默认）或 {@code PCL}（对标 PCL2 逻辑的新引擎）。
     *
     * <p>两套引擎互不影响：原引擎实现保留在 {@code download.task.GameResourceCompleter}，
     * 新引擎在 {@code download.pcl} 包内，由 {@code download.DownloadEngineRouter} 按本键分流。
     * 切换后下一次补全即生效，无需重启。
     */
    public static final String KEY_ENGINE = "DownloadEngine";

    /** 原引擎（默认；行为与本次改造前完全一致） */
    public static final String ENGINE_STARLIGHT = "Starlight";

    /** 新引擎（对标 PCL2：全局单一并发配额 + 连接复用 + 几何分裂分块 + 速率型停滞判定） */
    public static final String ENGINE_PCL = "PCL";

    /** 默认引擎：保持原引擎，避免升级后行为静默改变 */
    public static final String DEFAULT_ENGINE = ENGINE_STARLIGHT;

    /**
     * PCL 引擎的并发线程数（对标 PCL2 的 {@code ToolDownloadThread + 1}，默认 63+1=64）。
     *
     * <p>与 {@link #KEY_THREADS} 分开是有意为之：原引擎的滑块上限是 32（受其停滞判定粒度限制），
     * 而 PCL 引擎是「全局单一配额 + 长时间占用连接」的模型，64 起步才追得上 PCL2。
     */
    public static final String KEY_PCL_THREADS = "PclDownloadThreads";

    /** 全部可调参数键（诊断 / 批量读写用；设置页「恢复默认」是逐个显式写回默认值） */
    public static final String[] TUNABLE_KEYS = {
            KEY_THREADS, KEY_PIECE_LIMIT_KB, KEY_SPLIT_PERCENT,
            KEY_THRESHOLD_MB, KEY_LOW_SPEED_KBPS, KEY_SPEED_LIMIT_KBPS,
            KEY_STALL_TIMEOUT_SEC, KEY_FIRST_TRY_STALL_SEC, KEY_CONNECT_TIMEOUT_SEC,
            KEY_MERGE_RETRY, KEY_MERGE_BACKOFF_MS, KEY_SLOW_GAP_SEC,
            KEY_BREAKER_THRESHOLD, KEY_BREAKER_COOLDOWN_SEC,
            KEY_ENGINE, KEY_PCL_THREADS
    };

    // PCL 引擎并发参数 //
    /** PCL 引擎并发线程数默认 64（对齐 PCL2 默认 63+1） */
    public static final int DEFAULT_PCL_THREADS = 64;
    public static final int MIN_PCL_THREADS = 1;
    public static final int MAX_PCL_THREADS = 256;

    /** 设置页可拖范围上限（再高对家用宽带无意义，且容易触发镜像风控） */
    public static final int SETTINGS_PCL_THREADS_MAX = 128;

    /** 当前引擎 */
    private static volatile String engine = DEFAULT_ENGINE;

    /** 当前引擎名（{@code Starlight} / {@code PCL}） */
    public static String getEngine() {
        return engine;
    }

    /** 设置引擎；未知值回落到原引擎，保证不会因脏配置导致补全无引擎可用 */
    public static void setEngine(String name) {
        engine = ENGINE_PCL.equalsIgnoreCase(name == null ? "" : name.trim())
                ? ENGINE_PCL : ENGINE_STARLIGHT;
    }

    /** 当前是否使用 PCL 引擎 */
    public static boolean isPclEngine() {
        return ENGINE_PCL.equals(engine);
    }

    /** PCL 引擎并发线程数 */
    public static int getPclThreads() {
        return pclThreads;
    }

    /** 设置 PCL 引擎并发线程数（自动限制在 1~256） */
    public static void setPclThreads(int threads) {
        pclThreads = clamp(threads, MIN_PCL_THREADS, MAX_PCL_THREADS);
    }

    /** PCL 引擎当前并发线程数（volatile：设置页可在任意线程切换） */
    private static volatile int pclThreads = DEFAULT_PCL_THREADS;

    // 并发线程数 //
    /** 并发下载线程数默认值：8（文档 §4.3 建议默认 8~12） */
    public static final int DEFAULT_THREADS = 8;

    /** 允许的最小/最大并发线程数（上限对齐文档 §4.3 建议的 32~64） */
    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 64;

    /** 设置页滑块 / 数值框的可用上限（拖动比敲数字直观，给到 32 已覆盖多数宽带） */
    public static final int SETTINGS_THREADS_MAX = 32;

    // 分块参数 //
    /** 最小碎片默认 256KB */
    public static final int DEFAULT_PIECE_LIMIT_KB = 256;
    public static final int MIN_PIECE_LIMIT_KB = 64;
    public static final int MAX_PIECE_LIMIT_KB = 4096;
    private static final long KB = 1024L;

    /** 分裂点默认 60%（在已完成点之后 60% 处切出新分块） */
    public static final int DEFAULT_SPLIT_PERCENT = 60;
    public static final int MIN_SPLIT_PERCENT = 30;
    public static final int MAX_SPLIT_PERCENT = 90;

    /** 分块门槛默认 4MB（小于此值的文件单线程下完，省掉合并开销） */
    public static final int DEFAULT_THRESHOLD_MB = 4;
    public static final int MIN_THRESHOLD_MB = 1;
    public static final int MAX_THRESHOLD_MB = 64;
    private static final long MB = 1024L * 1024L;

    // 速度参数 //
    /** 慢速触发线默认 256KB/s（文档 §4.3 建议 512KB/s~1MB/s，此处保留现有基线值） */
    public static final int DEFAULT_LOW_SPEED_KBPS = 256;
    public static final int MIN_LOW_SPEED_KBPS = 64;
    public static final int MAX_LOW_SPEED_KBPS = 8192;

    /** 限速默认 0 = 不限速（文档 §4.5：必须限速时只用全局令牌桶） */
    public static final int DEFAULT_SPEED_LIMIT_KBPS = 0;
    public static final int MAX_SPEED_LIMIT_KBPS = 1024 * 1024;

    // 超时参数（M1 停滞看门狗） //
    /**
     * 停滞判定默认 15 秒。
     *
     * <p><b>这是补全链路上最关键的存活参数</b>：单个 read 超过此时长没有拿到任何字节，
     * 就判定该源「连得上但不干活」，立刻中止本次尝试并换下一个候选源。
     *
     * <p>为什么必须有它：{@code java.net.http.HttpClient} 没有 socket read timeout，
     * 且实测确认「跨线程 close 流 / Thread.interrupt / sendAsync+cancel / ofFile+timeout」
     * <b>四种方式都无法终结卡死的正文读取</b>。所以补全的文件下载改用
     * {@link java.net.HttpURLConnection}（它的 {@code setReadTimeout} 实测有效）。
     *
     * <p>取值权衡：太小会误杀慢但正常的连接（大文件低速下载），太大则卡死源占用线程更久。
     * 15s 对"完全不动"足够灵敏，对"慢但在动"没有影响（超时按「单次 read 间隔」计，不是总时长）。
     */
    public static final int DEFAULT_STALL_TIMEOUT_SEC = 15;
    public static final int MIN_STALL_TIMEOUT_SEC = 3;
    public static final int MAX_STALL_TIMEOUT_SEC = 300;

    // 2026-09-30 下载等待池增强 B：停滞判定分级。
    // 上方的 DownloadStallTimeoutSec 仅用于「最后可用候选」（再乘 LAST_RESORT_TIMEOUT_FACTOR），
    // 前面的候选统一用更快的首试停滞判定，坏源代价从 15s 降到 5s。
    /** 首试停滞判定默认 5 秒 */
    public static final int DEFAULT_FIRST_TRY_STALL_SEC = 5;

    /**
     * 停滞判定的「速率阈值」（字节/秒）：窗口内实收字节低于此值才判该源卡死。
     *
     * <p><b>为什么必须有速率条件（2026-10-20）</b>：原来的判定是「单次 read 超时即判停滞」，
     * 实测本机到 {@code resources.download.minecraft.net} 的<b>首包</b>耗时就有 5.7~8.85 秒，
     * 与默认 5 秒判定线重叠——于是合法的慢响应被当成卡死，反复换源重试，
     * 每次重试还要再付一遍首包延迟。日志里 90 秒只下 255 个文件、71 条「源停滞」告警即此。
     *
     * <p>PCL2 的判据是<b>速率</b>而非时长（ModNet.vb:1025-1028：间隔 &gt;5s <b>且</b> &lt;1KB/s 才断开）。
     * 本常量即该语义：慢但在动的连接不掐，完全不动（或滴流）的才掐。
     */
    public static final long STALL_MIN_PROGRESS_BPS = 1024L;
    public static final int MIN_FIRST_TRY_STALL_SEC = 1;
    public static final int MAX_FIRST_TRY_STALL_SEC = 60;

    /** 建连超时默认 15 秒（与停滞判定同量级，避免把时间浪费在建连上） */
    public static final int DEFAULT_CONNECT_TIMEOUT_SEC = 15;
    public static final int MIN_CONNECT_TIMEOUT_SEC = 3;
    public static final int MAX_CONNECT_TIMEOUT_SEC = 120;

    // 重试 / 熔断参数（开发者选项页可调，默认与原 RetryPolicy / MirrorCircuitBreaker 常量一致） //
    /** 合并重试次数默认 3 */
    public static final int DEFAULT_MERGE_RETRY = 3;
    public static final int MIN_MERGE_RETRY = 1;
    public static final int MAX_MERGE_RETRY = 10;

    /** 合并退避基数默认 500ms */
    public static final int DEFAULT_MERGE_BACKOFF_MS = 500;
    public static final int MIN_MERGE_BACKOFF_MS = 100;
    public static final int MAX_MERGE_BACKOFF_MS = 5000;

    /** 慢速掐线阈值默认 5 秒（原 RetryPolicy.SLOW_GAP_MS） */
    public static final int DEFAULT_SLOW_GAP_SEC = 5;
    public static final int MIN_SLOW_GAP_SEC = 1;
    public static final int MAX_SLOW_GAP_SEC = 60;

    /** 镜像熔断阈值默认 2 次连续失败 */
    public static final int DEFAULT_BREAKER_THRESHOLD = 2;
    public static final int MIN_BREAKER_THRESHOLD = 1;
    public static final int MAX_BREAKER_THRESHOLD = 10;

    /** 镜像熔断冷却默认 60 秒 */
    public static final int DEFAULT_BREAKER_COOLDOWN_SEC = 60;
    public static final int MIN_BREAKER_COOLDOWN_SEC = 10;
    public static final int MAX_BREAKER_COOLDOWN_SEC = 600;

    // 当前值（volatile：设置页可在任意线程切换） //
    private static volatile int downloadThreads = DEFAULT_THREADS;
    private static volatile int pieceLimitKb = DEFAULT_PIECE_LIMIT_KB;
    private static volatile int splitPercent = DEFAULT_SPLIT_PERCENT;
    private static volatile int thresholdMb = DEFAULT_THRESHOLD_MB;
    private static volatile int lowSpeedKbps = DEFAULT_LOW_SPEED_KBPS;
    private static volatile int speedLimitKbps = DEFAULT_SPEED_LIMIT_KBPS;
    private static volatile int stallTimeoutSec = DEFAULT_STALL_TIMEOUT_SEC;
    private static volatile int firstTryStallSec = DEFAULT_FIRST_TRY_STALL_SEC;
    private static volatile int connectTimeoutSec = DEFAULT_CONNECT_TIMEOUT_SEC;
    private static volatile int mergeRetry = DEFAULT_MERGE_RETRY;
    private static volatile int mergeBackoffMs = DEFAULT_MERGE_BACKOFF_MS;
    private static volatile int slowGapSec = DEFAULT_SLOW_GAP_SEC;
    private static volatile int breakerThreshold = DEFAULT_BREAKER_THRESHOLD;
    private static volatile int breakerCooldownSec = DEFAULT_BREAKER_COOLDOWN_SEC;

    private DownloadSettings() {
    }

    // 并发线程数 //
    /** 获取当前并发下载线程数 */
    public static int getDownloadThreads() {
        return downloadThreads;
    }

    /** 设置并发下载线程数（自动限制在 MIN~MAX 范围内；线程池配额改上限后立即放宽） */
    public static void setDownloadThreads(int threads) {
        downloadThreads = clamp(threads, MIN_THREADS, MAX_THREADS);
    }

    // 分块参数（字节 / 比例） //
    /** 最小碎片（KB） */
    public static int getPieceLimitKb() {
        return pieceLimitKb;
    }

    /** 设置最小碎片（KB），自动限制在 64~4096 */
    public static void setPieceLimitKb(int kb) {
        pieceLimitKb = clamp(kb, MIN_PIECE_LIMIT_KB, MAX_PIECE_LIMIT_KB);
    }

    /** 最小碎片（字节）：剩余量小于此值的分块不再分裂 */
    public static long getPieceLimitBytes() {
        return pieceLimitKb * KB;
    }

    /** 分裂点百分比（30~90） */
    public static int getSplitPercent() {
        return splitPercent;
    }

    /** 设置分裂点百分比，自动限制在 30~90 */
    public static void setSplitPercent(int percent) {
        splitPercent = clamp(percent, MIN_SPLIT_PERCENT, MAX_SPLIT_PERCENT);
    }

    /** 分裂点比例 0~1：在剩余量的该比例处切出新分块（默认 0.6） */
    public static double getSplitRatio() {
        return splitPercent / 100.0;
    }

    /** 才值得分块的门槛（MB） */
    public static int getMultithreadThresholdMb() {
        return thresholdMb;
    }

    /** 设置分块门槛（MB），自动限制在 1~64 */
    public static void setMultithreadThresholdMb(int mb) {
        thresholdMb = clamp(mb, MIN_THRESHOLD_MB, MAX_THRESHOLD_MB);
    }

    /** 才值得分块的门槛（字节）：小于此值的文件不开分裂线程 */
    public static long getMultithreadThresholdBytes() {
        return thresholdMb * MB;
    }

    // 速度参数 //
    /** 慢速触发线（KB/s）：全局实测速度低于此值才为慢文件追加分裂线程 */
    public static int getLowSpeedThresholdKbps() {
        return lowSpeedKbps;
    }

    /** 设置慢速触发线（KB/s），自动限制在 64~8192 */
    public static void setLowSpeedThresholdKbps(int kbps) {
        lowSpeedKbps = clamp(kbps, MIN_LOW_SPEED_KBPS, MAX_LOW_SPEED_KBPS);
    }

    /** 慢速触发线（B/s） */
    public static long getLowSpeedThresholdBps() {
        return lowSpeedKbps * KB;
    }

    /** 限速值（KB/s）；0 = 不限速 */
    public static int getSpeedLimitKbps() {
        return speedLimitKbps;
    }

    /** 设置限速值（KB/s），0 = 不限速；自动限制在 0~1048576 */
    public static void setSpeedLimitKbps(int kbps) {
        speedLimitKbps = clamp(kbps, 0, MAX_SPEED_LIMIT_KBPS);
    }

    /** 是否开启限速 */
    public static boolean isSpeedLimitEnabled() {
        return speedLimitKbps > 0;
    }

    // 超时参数 //
    /** 停滞判定（秒）：单次 read 超过此时长无字节 → 判该源卡死并换源 */
    public static int getStallTimeoutSec() {
        return stallTimeoutSec;
    }

    /** 设置停滞判定（秒），自动限制在 3~300 */
    public static void setStallTimeoutSec(int sec) {
        stallTimeoutSec = clamp(sec, MIN_STALL_TIMEOUT_SEC, MAX_STALL_TIMEOUT_SEC);
    }

    /** 停滞判定（毫秒），直接喂给 HttpURLConnection.setReadTimeout */
    public static int getStallTimeoutMs() {
        return stallTimeoutSec * 1000;
    }

    /** 首试停滞判定（秒）：非最后候选的候选源用此快速判定（默认 5s） */
    public static int getFirstTryStallSec() {
        return firstTryStallSec;
    }

    /** 设置首试停滞判定（秒），自动限制在 1~60 */
    public static void setFirstTryStallSec(int sec) {
        firstTryStallSec = clamp(sec, MIN_FIRST_TRY_STALL_SEC, MAX_FIRST_TRY_STALL_SEC);
    }

    /** 首试停滞判定（毫秒） */
    public static int getFirstTryStallMs() {
        return firstTryStallSec * 1000;
    }

    // 停滞判定（速率条件，2026-10-20） //
    /**
     * 停滞「窗口」长度（毫秒）：窗口到期时按窗口内实收字节判定，而不是按单次 read 是否超时。
     *
     * <p>与 socket 读超时的分工（对标 PCL2 ModNet.vb:1025-1032）：
     * <ul>
     *   <li><b>socket 读超时</b>（{@link #getStallTimeoutMs()}，15s）是外层兜底：连接彻底无响应时靠它抛异常；</li>
     *   <li><b>本窗口 + 速率阈值</b>是内层判据：数据在动但慢到「滴流」的连接，在窗口到期时就被判掉，
     *       不必等满每个 read 的超时。</li>
     * </ul>
     * 首试窗口默认 5 秒（比最后候选短），坏源付出更小；最后候选放宽到 30 秒，slow 但可用的源能下完。
     */
    public static long getStallWindowMs(boolean lastResort) {
        return lastResort ? getStallTimeoutMs() * 2L : Math.max(3_000L, getFirstTryStallMs());
    }

    /**
     * 判定「停滞窗口内是否毫无进展」。
     *
     * <p>窗口到期后，实收字节低于 {@code STALL_MIN_PROGRESS_BPS × 窗口秒数} 才算卡死；
     * 慢但在动的连接（例如实测 259KB/s 的官方源）不满足该条件，会继续下完而不是被掐断重来。
     *
     * @param windowMs      本窗口已持续的毫秒数
     * @param bytesInWindow 本窗口内实收字节数
     */
    public static boolean isStalled(long windowMs, long bytesInWindow) {
        long required = STALL_MIN_PROGRESS_BPS * Math.max(1L, windowMs) / 1000L;
        return bytesInWindow < required;
    }

    /** 建连超时（秒） */
    public static int getConnectTimeoutSec() {
        return connectTimeoutSec;
    }

    /** 设置建连超时（秒），自动限制在 3~120 */
    public static void setConnectTimeoutSec(int sec) {
        connectTimeoutSec = clamp(sec, MIN_CONNECT_TIMEOUT_SEC, MAX_CONNECT_TIMEOUT_SEC);
    }

    /** 建连超时（毫秒） */
    public static int getConnectTimeoutMs() {
        return connectTimeoutSec * 1000;
    }

    // 重试 / 熔断参数 //
    /** 合并重试次数上限（1~10） */
    public static int getMergeRetry() {
        return mergeRetry;
    }

    /** 设置合并重试次数上限，自动限制在 1~10 */
    public static void setMergeRetry(int attempts) {
        mergeRetry = clamp(attempts, MIN_MERGE_RETRY, MAX_MERGE_RETRY);
    }

    /** 合并退避基数（毫秒，100~5000） */
    public static int getMergeBackoffMs() {
        return mergeBackoffMs;
    }

    /** 设置合并退避基数（毫秒），自动限制在 100~5000 */
    public static void setMergeBackoffMs(int ms) {
        mergeBackoffMs = clamp(ms, MIN_MERGE_BACKOFF_MS, MAX_MERGE_BACKOFF_MS);
    }

    /** 慢速掐线阈值（秒，1~60） */
    public static int getSlowGapSec() {
        return slowGapSec;
    }

    /** 设置慢速掐线阈值（秒），自动限制在 1~60 */
    public static void setSlowGapSec(int sec) {
        slowGapSec = clamp(sec, MIN_SLOW_GAP_SEC, MAX_SLOW_GAP_SEC);
    }

    /** 镜像熔断阈值（连续失败次数，1~10） */
    public static int getBreakerThreshold() {
        return breakerThreshold;
    }

    /** 设置镜像熔断阈值，自动限制在 1~10，并同步到已存在的熔断器实例 */
    public static void setBreakerThreshold(int threshold) {
        breakerThreshold = clamp(threshold, MIN_BREAKER_THRESHOLD, MAX_BREAKER_THRESHOLD);
        com.example.starlight.util.MirrorCircuitBreaker.applyGlobalParams(breakerThreshold, breakerCooldownMs());
    }

    /** 镜像熔断冷却（秒，10~600） */
    public static int getBreakerCooldownSec() {
        return breakerCooldownSec;
    }

    /** 设置镜像熔断冷却（秒），自动限制在 10~600，并同步到已存在的熔断器实例 */
    public static void setBreakerCooldownSec(int sec) {
        breakerCooldownSec = clamp(sec, MIN_BREAKER_COOLDOWN_SEC, MAX_BREAKER_COOLDOWN_SEC);
        com.example.starlight.util.MirrorCircuitBreaker.applyGlobalParams(breakerThreshold, breakerCooldownMs());
    }

    /** 镜像熔断冷却（毫秒） */
    public static long breakerCooldownMs() {
        return breakerCooldownSec * 1000L;
    }

    // 应用 / 加载 //
    /**
     * 把当前全部参数实际压到下载组件上（线程池配额是「上限」语义，无需重建池）。
     *
     * <p>设置页保存、启动加载、恢复默认三处都走这一个入口，避免出现「UI 显示新值、
     * 组件仍跑旧值」的分叉。
     */
    public static void applyAll() {
        applySpeedLimit();
    }

    /** 把限速值压到全局令牌桶（0 = {@code setLimit(-1)} 不限速） */
    public static void applySpeedLimit() {
        GlobalSpeedLimiter.getInstance().setLimit(isSpeedLimitEnabled() ? speedLimitKbps * KB : -1L);
    }

    /**
     * 从配置表加载全部下载参数并立即生效（启动时调用一次）。
     *
     * <p>缺失的键回落到默认值，因此老版本配置文件无需迁移。
     *
     * @param config 客户端配置表（{@code starlight-client.ini}），可为 null
     */
    public static void applyAll(Map<String, String> config) {
        if (config != null) {
            setDownloadThreads(parseInt(config.get(KEY_THREADS), DEFAULT_THREADS));
            setPieceLimitKb(parseInt(config.get(KEY_PIECE_LIMIT_KB), DEFAULT_PIECE_LIMIT_KB));
            setSplitPercent(parseInt(config.get(KEY_SPLIT_PERCENT), DEFAULT_SPLIT_PERCENT));
            setMultithreadThresholdMb(parseInt(config.get(KEY_THRESHOLD_MB), DEFAULT_THRESHOLD_MB));
            setLowSpeedThresholdKbps(parseInt(config.get(KEY_LOW_SPEED_KBPS), DEFAULT_LOW_SPEED_KBPS));
            setSpeedLimitKbps(parseInt(config.get(KEY_SPEED_LIMIT_KBPS), DEFAULT_SPEED_LIMIT_KBPS));
            setStallTimeoutSec(parseInt(config.get(KEY_STALL_TIMEOUT_SEC), DEFAULT_STALL_TIMEOUT_SEC));
            setFirstTryStallSec(parseInt(config.get(KEY_FIRST_TRY_STALL_SEC), DEFAULT_FIRST_TRY_STALL_SEC));
            setConnectTimeoutSec(parseInt(config.get(KEY_CONNECT_TIMEOUT_SEC), DEFAULT_CONNECT_TIMEOUT_SEC));
            setMergeRetry(parseInt(config.get(KEY_MERGE_RETRY), DEFAULT_MERGE_RETRY));
            setMergeBackoffMs(parseInt(config.get(KEY_MERGE_BACKOFF_MS), DEFAULT_MERGE_BACKOFF_MS));
            setSlowGapSec(parseInt(config.get(KEY_SLOW_GAP_SEC), DEFAULT_SLOW_GAP_SEC));
            setBreakerThreshold(parseInt(config.get(KEY_BREAKER_THRESHOLD), DEFAULT_BREAKER_THRESHOLD));
            setBreakerCooldownSec(parseInt(config.get(KEY_BREAKER_COOLDOWN_SEC), DEFAULT_BREAKER_COOLDOWN_SEC));
        } else {
            resetToDefaults();
        }
        applyAll();
    }

    /** 全部参数复位为出厂默认值（设置页「恢复默认」） */
    public static void resetToDefaults() {
        downloadThreads = DEFAULT_THREADS;
        pieceLimitKb = DEFAULT_PIECE_LIMIT_KB;
        splitPercent = DEFAULT_SPLIT_PERCENT;
        thresholdMb = DEFAULT_THRESHOLD_MB;
        lowSpeedKbps = DEFAULT_LOW_SPEED_KBPS;
        speedLimitKbps = DEFAULT_SPEED_LIMIT_KBPS;
        stallTimeoutSec = DEFAULT_STALL_TIMEOUT_SEC;
        firstTryStallSec = DEFAULT_FIRST_TRY_STALL_SEC;
        connectTimeoutSec = DEFAULT_CONNECT_TIMEOUT_SEC;
        mergeRetry = DEFAULT_MERGE_RETRY;
        mergeBackoffMs = DEFAULT_MERGE_BACKOFF_MS;
        slowGapSec = DEFAULT_SLOW_GAP_SEC;
        setBreakerThreshold(DEFAULT_BREAKER_THRESHOLD);
        setBreakerCooldownSec(DEFAULT_BREAKER_COOLDOWN_SEC);
        // 2026-10-21：引擎与 PCL 并发数一并复位（复位后回到原引擎，行为与改造前一致）
        engine = DEFAULT_ENGINE;
        pclThreads = DEFAULT_PCL_THREADS;
    }

    /** 容错解析：空值 / 非法值回落默认值 */
    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
