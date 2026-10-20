package com.example.starlight.download.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 容器级「主机 + 路径前缀」健康表：某个镜像地址段连续 404 之后，在冷却期内直接跳过它。
 *
 * <h3>为什么需要它</h3>
 * 镜像某个地址段一旦持续失效（404 / 超时 / 空正文），没有这层保护时<b>每个文件都要重新试一遍</b>，
 * 白付一次往返才回落官方源；对上千个 assets + 几百个库，这是成片的浪费。
 *
 * <p><b>已知的一次真实故障</b>（已从根上修复，本表是第二道防线）：
 * 资产对象原本请求 {@code https://bmclapi2.bangbang93.com/assets/<sha1>}，
 * <b>漏掉了 sha1 前两位目录</b>。BMCLAPI 的 assets 端点完全镜像官方目录结构，实测对照：
 * <pre>
 *   官方    .../2d/2d888ac…           → 200 (605451 B)
 *   BMCLAPI /assets/2d/2d888ac…       → 200 (605451 B)   ← 正确形态
 *   BMCLAPI /assets/2d888ac…          → 404               ← 旧代码的写法
 * </pre>
 * 根因修复见 {@link com.example.starlight.download.source.DownloadProvider#getAssetObjectCandidates}，
 * 各 provider 还加了「误传裸 hash 自动补前两位」的容错。
 * 本表仍然必要：镜像真的挂掉时，它负责把该地址段整体跳过。
 *
 * <h3>为什么按「主机 + 路径前缀」而不是只按主机</h3>
 * 只拉黑整个 host 会连带干掉同一台机器上<b>正常</b>的端点（例如 bmclapi 的
 * {@code /libraries/}、{@code /version/}、{@code /maven/} 都是好的）。而 404 的语义是
 * 「这段路径在镜像上不存在」，所以把拉黑范围收敛到「目录前缀」（如
 * {@code bmclapi2.bangbang93.com/assets/}）既精确又不会误伤，且冷却结束后自动重试。
 *
 * <h3>为什么别处已有的熔断不够</h3>
 * {@link com.example.starlight.util.MirrorCircuitBreaker} 是<b>每个文件一个实例</b>
 * （{@link DownloadSource} 持有一个），跨文件不共享计数；补全流程给每个文件新建一条候选链，
 * 于是「镜像挂了」这个事实每个文件都要重新学一遍。本类是容器级单例，跨文件、跨任务累积。
 *
 * <h3>误判风险与抑制</h3>
 * 只有连续 404 达到 {@link #DEFAULT_404_THRESHOLD} 次才拉黑，且任一成功立即清零：
 * 偶发的单文件缺失（两台服务器都没有的对象）打不到阈值；阈值只在镜像端点上<em>系统性</em>失效时触发。
 */
public final class SourceHealth {

    private static final Logger log = LoggerFactory.getLogger(SourceHealth.class);

    /** 连续 404 多少次后拉黑该「主机 + 路径前缀」 */
    public static final int DEFAULT_404_THRESHOLD = 8;

    /** 拉黑持续时长：冷却结束后放行一次探测请求，恢复就自动继续用 */
    public static final long DEFAULT_COOLDOWN_MS = 120_000L;

    /**
     * 连续「请求整体超时」多少次后拉黑该地址段。
     *
     * <p>比 404 阈值低：404 是「这段路径没有」，超时是「连上了但不干活」，
     * 后者对补全的伤害大得多（每个卡死请求都要占满一个工作线程整整一个超时周期），
     * 所以更快拉黑。但也不能太低——弱网下偶发超时是正常的。
     */
    public static final int DEFAULT_TIMEOUT_THRESHOLD = 3;

    /** 超时拉黑的冷却时长：比 404 短，源恢复后尽快重新用上 */
    public static final long DEFAULT_TIMEOUT_COOLDOWN_MS = 60_000L;

    /**
     * 连续「HTTP 200 但正文 0 字节」多少次后拉黑该地址段。
     *
     * <p>这是 BMCLAPI 的典型故障形态：服务活着、HTTP 头正常，但资产端点对每个对象都返回
     * 空正文。此时既不是 404（不触发 {@link #record404}），也不是超时（不触发
     * {@link #recordTimeout}），所以必须单独计数——否则每个文件都要白跑一次
     * 「请求 → 写出空文件 → 算一遍 SHA-1 → 失败」才换源。
     */
    public static final int DEFAULT_EMPTY_THRESHOLD = 5;

    /** 空正文拉黑的冷却时长 */
    public static final long DEFAULT_EMPTY_COOLDOWN_MS = 90_000L;

    /**
     * 同一源连续多少批补全均「进入等待池且重试仍失败」后，额外触发一次拉黑（下载等待池方案 §2.3）。
     *
     * <p>慢源若永远只进软计数 {@link #slowCount}，将一直无法被熔断；此规则兜底「连续多批慢源」场景。
     */
    public static final int DEFAULT_SLOW_BATCH_THRESHOLD = 3;

    /** 连续多批慢源触发额外拉黑的冷却时长 */
    public static final long SLOW_BATCH_BLACKLIST_MS = 120_000L;

    /** 健康表容量上限：超出按最久未访问淘汰，避免长期运行无限增长 */
    private static final int MAX_TRACKED = 256;

    /**
     * 源健康持久化文件（2026-09-30 增强：跨启动记忆「最近成功源」）。
     *
     * <p>只持久化<b>成功经验</b>（blockKey → 最近成功耗时 + 时间戳）；失败计数 / 拉黑 / slowCount
     * 保持内存态——镜像恢复后不被历史失败拖累。每次启动补全不再是冷启动探测，
     * {@link SourceRanking} 直接按上次实测排序，前期（前 150 个）文件优先命中快源。
     */
    private static final Path PERSIST_FILE =
            com.example.starlight.config.AppDirs.sourceHealthJson();

    private static final SourceHealth INSTANCE = new SourceHealth();

    static {
        // 进程退出兜底：正常退出 / 异常退出都尽量把本批成功经验落盘
        Runtime.getRuntime().addShutdownHook(new Thread(() -> INSTANCE.persistIfDirty(), "sl-source-health-persist"));
    }

    /** 成功经验是否有未落盘的更新（节流写盘的脏标志） */
    private volatile boolean dirty;

    /** 连续 404 计数（access-order LRU，容量见 {@link #MAX_TRACKED}） */
    private final Map<String, Integer> consecutive404 =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_TRACKED;
                }
            });

    /** 连续「请求整体超时」计数（同样的 LRU 语义） */
    private final Map<String, Integer> consecutiveTimeout =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_TRACKED;
                }
            });

    /** 连续「HTTP 200 但正文 0 字节」计数（同样的 LRU 语义） */
    private final Map<String, Integer> consecutiveEmpty =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_TRACKED;
                }
            });

    /**
     * 软计数 slowCount（下载等待池方案 §2.3 / §2.4）：
     * 本批中该源出现「疑似慢源」首见失败（0B / 停滞）的次数，只用于源评分排序，
     * <b>不触发拉黑</b>；批次结束时由 {@link #advanceBatch()} 重置（语义为「本次批中」）。
     */
    private final Map<String, Integer> slowCount =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_TRACKED;
                }
            });

    /** 最近一次成功下载的耗时（ms，key = blockKey）；从未成功过则缺省（getter 返回 -1） */
    private final Map<String, Long> lastSuccessCostMs =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_TRACKED;
                }
            });

    /** 最近一次成功下载的时间戳（ms，key = blockKey） */
    private final Map<String, Long> lastSuccessAt =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_TRACKED;
                }
            });

    /** 连续批慢源计数：本批被 {@link #markSlowBatch} 的源 → 批结束时 {@link #advanceBatch()} 累加（§2.3） */
    private final Map<String, Integer> consecutiveSlowBatches =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_TRACKED;
                }
            });

    /** 本批被标记「进入等待池且重试仍失败」的源（advanceBatch 时结算） */
    private final Set<String> currentBatchSlowMarks = ConcurrentHashMap.newKeySet();

    /** 已拉黑的 key → 解禁时间戳（毫秒） */
    private final ConcurrentHashMap<String, Long> blockedUntil = new ConcurrentHashMap<>();

    /** 被跳过的请求数（观测用：没有它就看不出「省了多少白请求」） */
    private final AtomicLong skippedRequests = new AtomicLong();

    private SourceHealth() {
        loadFromDisk();
    }

    public static SourceHealth getInstance() {
        return INSTANCE;
    }

    // 查询 //
    /**
     * 该 URL 当前是否被判定为不可用（应跳过）。
     *
     * @param url 候选下载 URL，可为 null（返回 false）
     */
    public boolean isBlocked(String url) {
        if (blockedUntil.isEmpty()) return false;
        String key = blockKey(url);
        if (key == null) return false;
        Long until = blockedUntil.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            // 冷却结束：放行探测，并清掉失败计数重新观察
            blockedUntil.remove(key);
            consecutive404.remove(key);
            consecutiveTimeout.remove(key);
            consecutiveEmpty.remove(key);
            slowCount.remove(key);
            consecutiveSlowBatches.remove(key);
            return false;
        }
        skippedRequests.incrementAndGet();
        return true;
    }

    /** 当前被拉黑的「主机 + 路径前缀」快照（诊断 / 日志用） */
    public Set<String> blockedPrefixes() {
        return Set.copyOf(blockedUntil.keySet());
    }

    /** 累计被跳过的请求数 */
    public long getSkippedRequests() {
        return skippedRequests.get();
    }

    // 上报 //
    /**
     * 上报一次成功：该地址段的 404 计数清零并解除拉黑。
     *
     * @param url 成功的 URL
     */
    public void recordSuccess(String url) {
        String key = blockKey(url);
        if (key == null) return;
        consecutive404.remove(key);
        consecutiveTimeout.remove(key);
        consecutiveEmpty.remove(key);
        if (blockedUntil.remove(key) != null) {
            log.info("镜像地址段已恢复，重新启用: {}", key);
        }
    }

    /**
     * 上报一次非 404 失败（5xx / 连接失败等）。
     *
     * <p><b>故意不计数</b>：这些既可能是对方暂时抽风，也可能是本地网络抖动，
     * 用重试与既有熔断处理即可；算进来会让镜像因为一次抖动被整段拉黑。
     * 真正值得快速拉黑的是「超时」（见 {@link #recordTimeout}）。
     */
    public void recordOtherFailure(String url) {
        // 无状态变更
    }

    /**
     * 上报一次请求整体超时（{@link java.net.http.HttpTimeoutException}）。
     *
     * <p>这是「源连得上但不干活」的强信号：每个这样的请求都会占满一个工作线程
     * 整整一个超时周期，而补全用的是固定线程池——几个死源就能把整批下载拖垮，
     * 表现为「越到后期越慢」。因此用比 404 更低的阈值、更短的冷却把它跳过去。
     *
     * @param url    超时的 URL
     * @param reason 触发来源说明（写进日志）
     */
    public void recordTimeout(String url, String reason) {
        report(url, consecutiveTimeout, DEFAULT_TIMEOUT_THRESHOLD,
                DEFAULT_TIMEOUT_COOLDOWN_MS, "请求超时", reason);
    }

    /**
     * 上报一次「HTTP 200 但正文 0 字节」。
     *
     * <p>BMCLAPI 的典型故障：服务活着、状态码 200、但资产正文为空。它既不是 404 也不是超时，
     * 若不计入健康表，每个文件都要白跑一次「请求 → 写空文件 → 算 SHA-1 → 失败」才换源。
     *
     * @param url    返回空正文的 URL
     * @param reason 触发来源说明（写进日志）
     */
    public void recordEmpty(String url, String reason) {
        report(url, consecutiveEmpty, DEFAULT_EMPTY_THRESHOLD,
                DEFAULT_EMPTY_COOLDOWN_MS, "空正文(200)", reason);
    }

    // 2026-09-30 下载等待池：软计数 / 源排序数据 / 批次慢源拉黑 //
    /**
     * 上报一次「疑似慢源」的首见失败（0B / 停滞读超时）：只累加软计数 {@link #slowCount}，
     * <b>不触发拉黑</b>（§2.3）。重试仍失败才由 {@link #recordEmpty} / {@link #recordTimeout} 正式计数。
     *
     * @param url    慢响应的 URL
     * @param reason 触发来源说明（写进日志）
     */
    public void recordSlow(String url, String reason) {
        String key = blockKey(url);
        if (key == null) return;
        int count;
        synchronized (slowCount) {
            count = slowCount.merge(key, 1, Integer::sum);
        }
        if (count == 1) {
            log.debug("源慢响应（软计数，暂不拉黑）: {}（来源: {}）", key, reason);
        }
    }

    /** 软计数查询：本批中该源「疑似慢源」失败的次数（无记录返回 0，用于源评分排序） */
    public int getSlowCount(String url) {
        String key = blockKey(url);
        if (key == null) return 0;
        Integer c;
        synchronized (slowCount) {
            c = slowCount.get(key);
        }
        return c == null ? 0 : c;
    }

    /**
     * 记录一次成功下载的耗时与时间戳（§2.4 源动态排序数据，不新增网络请求）。
     *
     * @param url    成功的 URL
     * @param costMs 本次下载耗时（毫秒，从建连前到落盘）
     */
    public void recordSuccessCost(String url, long costMs) {
        String key = blockKey(url);
        if (key == null) return;
        long now = System.currentTimeMillis();
        synchronized (lastSuccessCostMs) {
            lastSuccessCostMs.put(key, Math.max(0L, costMs));
            lastSuccessAt.put(key, now);
        }
        dirty = true;
    }

    /** 最近一次成功下载耗时（ms）；从未成功过返回 -1 */
    public long getLastSuccessCostMs(String url) {
        String key = blockKey(url);
        if (key == null) return -1L;
        Long v;
        synchronized (lastSuccessCostMs) {
            v = lastSuccessCostMs.get(key);
        }
        return v == null ? -1L : v;
    }

    /** 最近一次成功下载时间戳（ms）；从未成功过返回 -1 */
    public long getLastSuccessAt(String url) {
        String key = blockKey(url);
        if (key == null) return -1L;
        Long v;
        synchronized (lastSuccessAt) {
            v = lastSuccessAt.get(key);
        }
        return v == null ? -1L : v;
    }

    // 诊断快照（开发者选项页「下载源健康面板」用） //

    /** 单条「主机 + 路径前缀」健康行（只读快照，字段直接公开） */
    public static final class HealthRow {
        /** 地址段 key（host + 路径前缀） */
        public final String key;
        /** 拉黑解禁时间戳（ms）；0 = 未拉黑（已过冷却的条目也按 0 报告） */
        public final long blockedUntilMs;
        /** 连续 404 计数 */
        public final int consecutive404;
        /** 连续超时计数 */
        public final int consecutiveTimeout;
        /** 连续空正文计数 */
        public final int consecutiveEmpty;
        /** 本批慢源软计数 */
        public final int slowCount;
        /** 最近一次成功耗时（ms，-1 = 从未成功） */
        public final long lastSuccessCostMs;
        /** 最近一次成功时间戳（ms，-1 = 从未成功） */
        public final long lastSuccessAt;

        HealthRow(String key, long blockedUntilMs, int consecutive404, int consecutiveTimeout,
                  int consecutiveEmpty, int slowCount, long lastSuccessCostMs, long lastSuccessAt) {
            this.key = key;
            this.blockedUntilMs = blockedUntilMs;
            this.consecutive404 = consecutive404;
            this.consecutiveTimeout = consecutiveTimeout;
            this.consecutiveEmpty = consecutiveEmpty;
            this.slowCount = slowCount;
            this.lastSuccessCostMs = lastSuccessCostMs;
            this.lastSuccessAt = lastSuccessAt;
        }
    }

    /**
     * 全部已跟踪地址段的只读快照（开发者选项页「下载源健康面板」渲染用）。
     *
     * <p>union 各计数表的 key；冷却已过的拉黑条目按未拉黑（blockedUntilMs=0）报告，
     * 与 {@link #isBlocked(String)} 的惰性清零语义一致。
     */
    public List<HealthRow> snapshot() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(blockedUntil.keySet());
        keys.addAll(consecutive404.keySet());
        keys.addAll(consecutiveTimeout.keySet());
        keys.addAll(consecutiveEmpty.keySet());
        keys.addAll(slowCount.keySet());
        keys.addAll(lastSuccessCostMs.keySet());
        List<HealthRow> rows = new ArrayList<>(keys.size());
        long now = System.currentTimeMillis();
        for (String key : keys) {
            Long until = blockedUntil.get(key);
            long blockedUntil = (until == null || now >= until) ? 0L : until;
            Integer c404 = consecutive404.get(key);
            Integer cTimeout = consecutiveTimeout.get(key);
            Integer cEmpty = consecutiveEmpty.get(key);
            Integer slow = slowCount.get(key);
            Long cost = lastSuccessCostMs.get(key);
            Long at = lastSuccessAt.get(key);
            rows.add(new HealthRow(key,
                    blockedUntil,
                    c404 == null ? 0 : c404,
                    cTimeout == null ? 0 : cTimeout,
                    cEmpty == null ? 0 : cEmpty,
                    slow == null ? 0 : slow,
                    cost == null ? -1L : cost,
                    at == null ? -1L : at));
        }
        rows.sort((a, b) -> Long.compare(b.blockedUntilMs, a.blockedUntilMs));
        return rows;
    }

    /**
     * 标记该源「本批进入等待池且重试仍失败」（§2.3）。
     * 连续 {@link #DEFAULT_SLOW_BATCH_THRESHOLD} 批被标记时，由 {@link #advanceBatch()} 额外拉黑。
     *
     * @param url 最终失败任务中产生可重试失败的源 URL
     */
    public void markSlowBatch(String url) {
        String key = blockKey(url);
        if (key == null) return;
        synchronized (consecutiveSlowBatches) {
            currentBatchSlowMarks.add(key);
        }
    }

    /**
     * 一批补全结束时结算（§2.3）：本批被 {@link #markSlowBatch} 标记的源累计连续批数，
     * 达到 {@link #DEFAULT_SLOW_BATCH_THRESHOLD} 触发一次额外拉黑；本批未被标记的源连续计数清零
     * （保持「连续」语义）；软计数 {@link #slowCount} 随批重置（其语义为「本次批中」）。
     */
    public void advanceBatch() {
        synchronized (consecutiveSlowBatches) {
            for (String key : currentBatchSlowMarks) {
                int count = consecutiveSlowBatches.merge(key, 1, Integer::sum);
                if (count >= DEFAULT_SLOW_BATCH_THRESHOLD && !blockedUntil.containsKey(key)) {
                    blockedUntil.put(key, System.currentTimeMillis() + SLOW_BATCH_BLACKLIST_MS);
                    log.warn("镜像地址段连续 {} 批补全均慢且重试失败，额外拉黑 {}ms: {}",
                            count, SLOW_BATCH_BLACKLIST_MS, key);
                }
            }
            // 「连续」语义：本批未被标记的源清零重计
            consecutiveSlowBatches.keySet().removeIf(k -> !currentBatchSlowMarks.contains(k));
            currentBatchSlowMarks.clear();
            slowCount.clear();
        }
    }

    /**
     * 上报一次「请求成功但资源不存在」（HTTP 404 / 410）。
     *
     * <p>连续达到阈值即拉黑该「主机 + 路径前缀」，冷却 {@link #DEFAULT_COOLDOWN_MS} 后自动放行。
     *
     * @param url      返回 404 的 URL
     * @param status   HTTP 状态码（404 / 410）
     * @param reason   触发来源说明（写进日志，便于定位是哪条链路）
     */
    public void record404(String url, int status, String reason) {
        report(url, consecutive404, DEFAULT_404_THRESHOLD, DEFAULT_COOLDOWN_MS,
                "HTTP " + status, reason);
    }

    // 内部 //
    /**
     * 共用上报逻辑：累计某类失败次数，达到阈值就把该地址段拉黑一个冷却期。
     *
     * @param counters  该类失败的计数表
     * @param threshold 触发拉黑的连续次数
     * @param cooldown  拉黑时长（ms）
     * @param what      失败类型文案（写进日志）
     * @param reason    触发来源（写进日志）
     */
    private void report(String url, Map<String, Integer> counters, int threshold,
                        long cooldown, String what, String reason) {
        String key = blockKey(url);
        if (key == null) return;
        int count;
        synchronized (counters) {
            count = counters.merge(key, 1, Integer::sum);
        }
        if (count >= threshold && !blockedUntil.containsKey(key)) {
            blockedUntil.put(key, System.currentTimeMillis() + cooldown);
            log.warn("镜像地址段连续 {} 次{}，{}ms 内跳过该地址段: {}（来源: {}）",
                    count, what, cooldown, key, reason);
        }
    }

    /**
     * 拉黑键 = {@code host + 目录前缀}。
     *
     * <p>取最后一个 '/' 之前的部分作为目录前缀，因此
     * {@code .../assets/<sha1>} → {@code bmclapi2.bangbang93.com/assets/}。
     * 路径没有目录层级（如 {@code https://host/file}）时退化为 {@code host/}。
     */
    static String blockKey(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) return null;
            return host.toLowerCase(java.util.Locale.ROOT) + pathPrefix(uri.getPath());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pathPrefix(String path) {
        if (path == null || path.isEmpty()) return "/";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : path.substring(0, lastSlash + 1);
    }

    /**
     * 把未落盘的成功经验写入磁盘（节流：只在 {@link #dirty} 时写，原子替换防半截文件）。
     *
     * <p>调用点：{@link com.example.starlight.download.task.GameResourceCompleter} 每批补全结束时调用一次
     * （与校验缓存落盘同一处），进程退出由 shutdown hook 兜底；不阻塞下载线程（同步写但数据量 ≤256 条）。
     */
    public synchronized void persistIfDirty() {
        if (!dirty) {
            return;
        }
        try {
            Path dir = PERSIST_FILE.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            List<String> lines = new ArrayList<>(lastSuccessCostMs.size() + 8);
            synchronized (lastSuccessCostMs) {
                for (Map.Entry<String, Long> e : lastSuccessCostMs.entrySet()) {
                    Long at = lastSuccessAt.get(e.getKey());
                    if (at == null) continue;
                    lines.add(e.getKey() + "|" + e.getValue() + "|" + at);
                }
            }
            Path tmp = PERSIST_FILE.resolveSibling(PERSIST_FILE.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, PERSIST_FILE, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
            log.info("源健康持久化已保存: {}（{} 条成功经验）", PERSIST_FILE, lines.size());
        } catch (Exception e) {
            log.debug("源健康持久化保存失败（忽略，下次再试）: {}", e.toString());
        }
    }

    /**
     * 启动时从磁盘加载上次成功经验（行格式 {@code key|costMs|lastAt}，URL 中不出现 '|'）。
     * 解析失败 / 文件缺失一律忽略——冷启动探测兜底，不因持久化损坏阻塞补全。
     */
    private void loadFromDisk() {
        try {
            if (!Files.isRegularFile(PERSIST_FILE)) {
                return;
            }
            int loaded = 0;
            synchronized (lastSuccessCostMs) {
                for (String line : Files.readAllLines(PERSIST_FILE, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\\|", -1);
                    if (parts.length != 3) continue;
                    long cost = Long.parseLong(parts[1]);
                    long at = Long.parseLong(parts[2]);
                    if (cost < 0L || at <= 0L) continue;
                    lastSuccessCostMs.put(parts[0], cost);
                    lastSuccessAt.put(parts[0], at);
                    loaded++;
                }
            }
            if (loaded > 0) {
                log.info("源健康持久化已加载: {}（{} 条）", PERSIST_FILE, loaded);
            }
        } catch (Exception e) {
            log.debug("源健康持久化加载失败（忽略，本次冷启动）: {}", e.toString());
        }
    }

    /** 测试/诊断用：清空全部状态 */
    public void reset() {
        consecutive404.clear();
        consecutiveTimeout.clear();
        consecutiveEmpty.clear();
        blockedUntil.clear();
        skippedRequests.set(0);
        slowCount.clear();
        lastSuccessCostMs.clear();
        lastSuccessAt.clear();
        consecutiveSlowBatches.clear();
        currentBatchSlowMarks.clear();
        dirty = false;
    }
}
