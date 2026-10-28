package com.example.starlight.version;


import com.example.starlight.util.I18N;
import com.example.starlight.download.DownloadProviderHolder;
import com.example.starlight.download.source.BMCLAPIDownloadProvider;
import com.example.starlight.download.source.DownloadProvider;
import com.example.starlight.download.source.MCBBSDownloadProvider;
import com.example.starlight.download.source.MojangDownloadProvider;
import com.example.starlight.download.task.VersionManifest;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.DebugLog;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Minecraft 游戏版本下载服务
 * <p>
 * 功能：
 * - 从 Mojang 官方 API 获取版本清单（BMCLAPI 备用）
 * - 版本分类（正式版/预览版/远古版/愚人节版）
 * - 按关键词搜索版本
 * - 查询各版本对应的加载器版本（通过 LoaderProvider 接口）
 * - 内存缓存加载器查询结果
 */
public class VersionDownloadService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(VersionDownloadService.class);

    // 常量 //

    private static final String BMCLAPI_MANIFEST_URL =
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest.json";

    /** 公共镜像源，供 LoaderProvider 和 InstallEngine 使用 */
    public static final String BMCLAPI_BASE =
            "https://bmclapi2.bangbang93.com";

    /** BMCLAPI 镜像的静态实例：无论当前选中哪个下载源，元数据请求都自动把 BMCLAPI 镜像纳入兜底链 */
    private static final BMCLAPIDownloadProvider BMCLAPI_PROVIDER =
            new BMCLAPIDownloadProvider(BMCLAPI_BASE);

    /** 元数据请求的镜像候选超时（较短：镜像只是首选，失败就切下一候选） */
    private static final Duration METADATA_MIRROR_TIMEOUT = Duration.ofSeconds(6);

    /** 元数据请求的官方候选超时（较长：官方是最后兜底，弱网下也能等它响应） */
    private static final Duration METADATA_OFFICIAL_TIMEOUT = Duration.ofSeconds(20);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * HTTP/1.1 备用客户端：部分服务器（如 meta.quiltmc.org）对 HTTP/2 兼容性差，
     * 会返回 RST_STREAM: Internal error，降级到 HTTP/1.1 重试可避免该问题
     */
    private static final HttpClient HTTP_CLIENT_FALLBACK = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Gson GSON = new Gson();

    /**
     * 加载器元数据候选链竞速线程池：每条候选（镜像/官方）同时发出，先成功者胜。
     * <p>大小按「最坏情况同时竞速的候选数」估：单个 fetchString 最多 2~3 条候选，
     * 7 个加载器并行时约 14~21 个候选同时在线；给 16 个守护线程足够又不至于连接风暴。
     */
    private static final ExecutorService METADATA_RACE_POOL = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "sl-metadata-race");
        t.setDaemon(true);
        return t;
    });

    /**
     * 加载器查询专用线程池。
     *
     * <p>此前这些查询走 {@link java.util.concurrent.ForkJoinPool#commonPool()}：一次查询要并行打
     * 6 个后端（Forge/Fabric/NeoForge/OptiFine/Quilt/QSL），而下载页打开时还会批量预热热门版本，
     * 公共池很快被占满，导致版本详情页的加载器列表要等十几秒才出现。
     * 换成独立的小线程池后，既不与预热带宽互相排队，也不会污染公共池。
     */
    private static final java.util.concurrent.ExecutorService LOADER_QUERY_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(16, r -> {
                Thread t = new Thread(r, "sl-loader-query");
                t.setDaemon(true);
                return t;
            });

    /** 加载器查询线程池（供需要自行并行查询的调用方复用） */
    public static java.util.concurrent.ExecutorService getLoaderQueryPool() {
        return LOADER_QUERY_POOL;
    }

    /**
     * 设置下载提供者（JUDGE-5：委托全局唯一持有者 DownloadProviderHolder，与其余下载类共享同一源）
     */
    public static void setDownloadProvider(DownloadProvider provider) {
        DownloadProviderHolder.set(Objects.requireNonNull(provider));
    }

    /**
     * 获取当前下载提供者（JUDGE-5：委托全局唯一持有者 DownloadProviderHolder）
     */
    public static DownloadProvider getDownloadProvider() {
        return DownloadProviderHolder.get();
    }

    /**
     * 根据下载源名称应用下载提供者（与设置页「下载源」选项一致）
     * <p>
     * 支持：BMCLAPI / Mojang / MCBBS；未知值回退 BMCLAPI。
     * 切换后立即生效，后续版本清单获取、原版安装、资源补全等均使用新下载源。
     * <p>
     * JUDGE-5：源名解析与收敛统一委托 DownloadProviderHolder，本类不再自持静态 provider 字段，
     * 与 GameResourceCompleter / VersionManifest / LoaderInstallEngine 共享同一下载源。
     *
     * @param source 下载源名称（大小写不敏感）
     * @return 实际应用后的下载提供者
     */
    public static DownloadProvider applyDownloadSource(String source) {
        DownloadProvider p = DownloadProviderHolder.createForSource(source);
        DownloadProviderHolder.set(p);
        return p;
    }

    // 愚人节版本关键词
    private static final List<String> APRIL_FOOLS_KEYWORDS = Arrays.asList(
            "1.rv", "15w14a", "3d shareware", "20w14∞", "20w14infinite",
            "22w13oneblockatatime", "23w13a_or_b", "24w14potato"
    );

    // 版本分类 //

    public enum VersionCategory {
        RELEASE("release", I18N.get("version.t0005"), ""),
        SNAPSHOT("snapshot", I18N.get("version.t0006"), ""),
        OLD("old", I18N.get("version.t0007"), ""),
        APRIL("april", I18N.get("version.t0008"), ""),
        OTHER("other", I18N.get("version.t0009"), "");

        private final String key;
        private final String displayName;
        private final String icon;

        VersionCategory(String key, String displayName, String icon) {
            this.key = key;
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getKey() { return key; }
        public String getDisplayName() { return displayName; }
        public String getIcon() { return icon; }
    }

    // 数据模型 //

    /** 版本条目（含分类信息） */
    public static class VersionInfo {
        private final String id;
        private final String type;
        private final String releaseTime;
        private final String url;
        private final VersionCategory category;

        public VersionInfo(String id, String type, String releaseTime, String url, VersionCategory category) {
            this.id = id;
            this.type = type;
            this.releaseTime = releaseTime;
            this.url = url;
            this.category = category;
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public String getReleaseTime() { return releaseTime; }
        public String getUrl() { return url; }
        public VersionCategory getCategory() { return category; }
    }

    /** 加载器版本信息 */
    public static class LoaderVersion {
        private final String loaderName;
        private final String version;
        private final boolean available;
        private final String downloadUrl;

        public LoaderVersion(String loaderName, String version, boolean available, String downloadUrl) {
            this.loaderName = loaderName;
            this.version = version;
            this.available = available;
            this.downloadUrl = downloadUrl;
        }

        public String getLoaderName() { return loaderName; }
        public String getVersion() { return version; }
        public boolean isAvailable() { return available; }
        public String getDownloadUrl() { return downloadUrl; }

        public String getDisplayText() {
            if (available) return version != null ? version : I18N.get("version.t0001");
            return I18N.get("version.t0002");
        }
    }

    /** 某个版本的所有加载器信息 */
    public static class LoaderInfoBundle {
        private final String mcVersion;
        private final LoaderVersion forge;
        private final LoaderVersion fabric;
        private final LoaderVersion neoforge;
        private final LoaderVersion optifine;
        private final LoaderVersion fabricapi;
        private final LoaderVersion quilt;
        private final LoaderVersion qsl;

        public LoaderInfoBundle(String mcVersion,
                                LoaderVersion forge, LoaderVersion fabric,
                                LoaderVersion neoforge, LoaderVersion optifine) {
            this(mcVersion, forge, fabric, neoforge, optifine,
                    new LoaderVersion("FabricAPI", null, false, null),
                    new LoaderVersion("Quilt", null, false, null),
                    new LoaderVersion("QSL", null, false, null));
        }

        public LoaderInfoBundle(String mcVersion,
                                LoaderVersion forge, LoaderVersion fabric,
                                LoaderVersion neoforge, LoaderVersion optifine,
                                LoaderVersion fabricapi, LoaderVersion quilt, LoaderVersion qsl) {
            this.mcVersion = mcVersion;
            this.forge = forge;
            this.fabric = fabric;
            this.neoforge = neoforge;
            this.optifine = optifine;
            this.fabricapi = fabricapi;
            this.quilt = quilt;
            this.qsl = qsl;
        }

        public String getMcVersion() { return mcVersion; }
        public LoaderVersion getForge() { return forge; }
        public LoaderVersion getFabric() { return fabric; }
        public LoaderVersion getNeoforge() { return neoforge; }
        public LoaderVersion getOptifine() { return optifine; }
        public LoaderVersion getFabricapi() { return fabricapi; }
        public LoaderVersion getQuilt() { return quilt; }
        public LoaderVersion getQsl() { return qsl; }

        public boolean hasAnyLoader() {
            return forge.isAvailable() || fabric.isAvailable()
                    || neoforge.isAvailable() || optifine.isAvailable()
                    || fabricapi.isAvailable() || quilt.isAvailable() || qsl.isAvailable();
        }

        public List<LoaderVersion> getAvailableLoaders() {
            List<LoaderVersion> list = new ArrayList<>();
            if (forge.isAvailable()) list.add(forge);
            if (fabric.isAvailable()) list.add(fabric);
            if (neoforge.isAvailable()) list.add(neoforge);
            if (optifine.isAvailable()) list.add(optifine);
            if (fabricapi.isAvailable()) list.add(fabricapi);
            if (quilt.isAvailable()) list.add(quilt);
            if (qsl.isAvailable()) list.add(qsl);
            return list;
        }
    }

    // 版本清单获取 //

    /** 清单成功缓存 TTL：5 分钟内的重复调用直接用内存结果 */
    private static final long MANIFEST_TTL_MS = 5 * 60_000L;
    /** 清单失败缓存 TTL：刚拉取失败时 30 秒内不再打网络，避免各调用点叠加成请求风暴 */
    private static final long MANIFEST_FAILURE_TTL_MS = 30_000L;
    private static final Object MANIFEST_LOCK = new Object();
    private static List<VersionInfo> manifestCache = Collections.emptyList();
    private static long manifestCacheAt = 0;
    private static CompletableFuture<List<VersionInfo>> manifestInFlight;

    /**
     * 获取版本清单，按发布时间降序排列，并附带分类信息。
     * 候选源（选中源 / BMCLAPI / piston-meta）并行竞速，任一成功即用；
     * 冷启动 6h 内直接用磁盘缓存秒开，全部失败回退过期磁盘缓存。
     *
     * <p>成功结果缓存 5 分钟、失败结果缓存 30 秒；并发调用共享同一次网络拉取
     * （in-flight 去重）。之前页面里多个调用点各自拉全量清单，下载源不可用时
     * 一个页面能打出十几个超时请求。需要强制刷新时先调 {@link #invalidateManifestCache()}。
     */
    public static List<VersionInfo> fetchVersionManifest() {
        CompletableFuture<List<VersionInfo>> future;
        synchronized (MANIFEST_LOCK) {
            long now = System.currentTimeMillis();
            if (manifestCacheAt != 0 && now - manifestCacheAt <
                    (!manifestCache.isEmpty() ? MANIFEST_TTL_MS : MANIFEST_FAILURE_TTL_MS)) {
                return manifestCache;
            }
            if (manifestInFlight == null) {
                CompletableFuture<List<VersionInfo>> task =
                        CompletableFuture.supplyAsync(VersionDownloadService::doFetchVersionManifest);
                task.whenComplete((infos, err) -> {
                    synchronized (MANIFEST_LOCK) {
                        // invalidate 后可能有旧请求才返回：不再是当前 in-flight 就不回写缓存
                        if (manifestInFlight == task) {
                            manifestCache = infos == null ? Collections.emptyList() : infos;
                            manifestCacheAt = System.currentTimeMillis();
                        }
                        manifestInFlight = null;
                    }
                });
                manifestInFlight = task;
            }
            future = manifestInFlight;
        }
        try {
            return future.get(90, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 丢弃清单缓存（含磁盘缓存），下次 {@link #fetchVersionManifest()} 强制重新联网拉取（刷新按钮用） */
    public static void invalidateManifestCache() {
        synchronized (MANIFEST_LOCK) {
            manifestCache = Collections.emptyList();
            manifestCacheAt = 0;
            manifestInFlight = null;
        }
        VersionManifest.deleteManifestDiskCache();
    }

    /** 真正联网拉取并解析清单（无内存缓存，由 {@link #fetchVersionManifest()} 调度） */
    private static List<VersionInfo> doFetchVersionManifest() {
        // 冷启动秒开：磁盘缓存 6h 内直接用，不联网（刷新按钮 invalidate 时连磁盘缓存一起清）
        String cached = VersionManifest.loadManifestDiskCache(true);
        if (cached != null) {
            return parseManifestJson(cached);
        }
        // 候选链并行竞速：选中源 + BMCLAPI + 官方 piston-meta 同发，任一成功即用。
        // 旧串行逐个尝试（选中源 → BMCLAPI → MCBBS → 官方×2）在「BMCLAPI 抖动 + 国内直连官方
        // 被墙 + MCBBS 镜像已失效」时是 3~4 个 10~15s 超时叠加（2026-10-11 用户反馈
        // 「版本列表特别慢/有时无法获取」的根因），并行后最坏延迟 ≈ 单个请求超时。
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        DownloadProvider provider = DownloadProviderHolder.get();
        for (URI uri : provider.getVersionListURLs()) {
            urls.add(uri.toString());
        }
        urls.add(BMCLAPI_MANIFEST_URL);
        urls.add("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        String json = VersionManifest.fetchFirstSuccessJson(urls);
        if (json == null) {
            // 全部失败：用过期磁盘缓存兜底（有数据总比空列表好）
            json = VersionManifest.loadManifestDiskCache(false);
            if (json != null) {
                System.err.println("[VersionDownloadService] 网络全部失败，回退过期磁盘缓存");
            }
        }
        if (json == null) {
            System.err.println("[VersionDownloadService] Failed to fetch version manifest from download source.");
            return Collections.emptyList();
        }
        VersionManifest.saveManifestDiskCache(json);
        return parseManifestJson(json);
    }

    public static List<VersionInfo> searchVersions(List<VersionInfo> versions, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return versions;
        String lower = keyword.trim().toLowerCase(Locale.ROOT);
        return versions.stream()
                .filter(v -> v.getId().toLowerCase(Locale.ROOT).contains(lower))
                .collect(Collectors.toList());
    }

    public static List<VersionInfo> filterByCategory(List<VersionInfo> versions, VersionCategory category) {
        return versions.stream()
                .filter(v -> v.getCategory() == category)
                .collect(Collectors.toList());
    }

    public static Map<VersionCategory, VersionInfo> getLatestVersions(List<VersionInfo> versions) {
        Map<VersionCategory, VersionInfo> result = new HashMap<>();
        for (VersionInfo v : versions) {
            if (!result.containsKey(VersionCategory.RELEASE) && v.getCategory() == VersionCategory.RELEASE) {
                result.put(VersionCategory.RELEASE, v);
            }
            if (!result.containsKey(VersionCategory.SNAPSHOT) && v.getCategory() == VersionCategory.SNAPSHOT) {
                result.put(VersionCategory.SNAPSHOT, v);
            }
            if (result.containsKey(VersionCategory.RELEASE) && result.containsKey(VersionCategory.SNAPSHOT)) break;
        }
        return result;
    }

    // 加载器 API //

    /** LoaderProvider 实例列表（可扩展） */
    public static final List<LoaderProvider> LOADER_PROVIDERS = List.of(
            new ForgeProvider(), new FabricProvider(), new NeoForgeProvider(), new OptiFineProvider(),
            new FabricAPIProvider(), new QuiltProvider(), new QSLProvider()
    );

    /** 内存缓存：key = loaderType + "_" + mcVersion，value = 带写入时间的条目（成功结果才入缓存） */
    private record CacheEntry(LoaderVersion version, long cachedAt) {}

    private static final long LOADER_CACHE_TTL_MS = 30 * 60_000L;

    // 用 ConcurrentHashMap：多个加载器查询线程会并发 get/put（预加载与配置页查询时间重叠），
    // 普通 HashMap 在此场景非线程安全（读到半构建的 entry / 丢失 put）。
    private static final Map<String, CacheEntry> LOADER_CACHE = new ConcurrentHashMap<>();

    /** in-flight 去重：key 同上，value = 正在进行的查询。并发调用同一 key 只发一次网络请求 */
    private static final Map<String, CompletableFuture<LoaderVersion>> LOADER_IN_FLIGHT =
            new ConcurrentHashMap<>();

    /**
     * 异步获取某版本的所有加载器信息（并行请求，使用 LoaderProvider）
     */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<LoaderInfoBundle> fetchAllLoaderVersionsAsync(String mcVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompletableFuture<LoaderVersion>[] futures = LOADER_PROVIDERS.stream()
                        // 必须显式指定专用池：不带 executor 的 supplyAsync 落在 ForkJoinPool.commonPool，
                        // 会与其他模块的并行流/公共任务互相排队，版本详情页加载器列表因此慢十几秒
                        .map(p -> CompletableFuture.supplyAsync(() -> fetchWithCache(p, mcVersion), LOADER_QUERY_POOL))
                        .toArray(CompletableFuture[]::new);

                CompletableFuture.allOf(futures).get(10, java.util.concurrent.TimeUnit.SECONDS);
                LoaderVersion forge = null, fabric = null, neoforge = null, optifine = null,
                        fabricapi = null, quilt = null, qsl = null;
                for (int i = 0; i < LOADER_PROVIDERS.size(); i++) {
                    LoaderVersion lv = futures[i].getNow(
                            new LoaderVersion(LOADER_PROVIDERS.get(i).name(), null, false, null));
                    switch (LOADER_PROVIDERS.get(i).name()) {
                        case "Forge" -> forge = lv;
                        case "Fabric" -> fabric = lv;
                        case "NeoForge" -> neoforge = lv;
                        case "OptiFine" -> optifine = lv;
                        case "FabricAPI" -> fabricapi = lv;
                        case "Quilt" -> quilt = lv;
                        case "QSL" -> qsl = lv;
                    }
                }
                return new LoaderInfoBundle(mcVersion,
                        forge != null ? forge : new LoaderVersion("Forge", null, false, null),
                        fabric != null ? fabric : new LoaderVersion("Fabric", null, false, null),
                        neoforge != null ? neoforge : new LoaderVersion("NeoForge", null, false, null),
                        optifine != null ? optifine : new LoaderVersion("OptiFine", null, false, null),
                        fabricapi != null ? fabricapi : new LoaderVersion("FabricAPI", null, false, null),
                        quilt != null ? quilt : new LoaderVersion("Quilt", null, false, null),
                        qsl != null ? qsl : new LoaderVersion("QSL", null, false, null));
            } catch (Exception e) {
                System.err.println("[VersionDownloadService] Loader request timeout: " + e.getMessage());
                return new LoaderInfoBundle(mcVersion,
                        getCachedOrFallback("forge", mcVersion),
                        getCachedOrFallback("fabric", mcVersion),
                        getCachedOrFallback("neoforge", mcVersion),
                        getCachedOrFallback("optifine", mcVersion),
                        getCachedOrFallback("fabricapi", mcVersion),
                        getCachedOrFallback("quilt", mcVersion),
                        getCachedOrFallback("qsl", mcVersion));
            }
        });
    }

    /**
     * 异步获取某 MC 版本的全部可用加载器版本列表（各 Provider 并行查询完整列表）
     * @return 平铺列表（含 loaderName 分组信息），无可用版本时返回空列表
     *
     * <p>超时策略：等待 {@value #LOADER_LIST_DEADLINE_SECONDS} 秒（比底层单条候选的官方 20s 上限
     * 略宽，给竞速留余量），到点后<b>保留已完成的加载器</b>，未完成的单独记入
     * {@link LoaderQueryResult#timedOutLoaders()}，不再像旧实现那样把整批结果丢弃。
     * 调用方据此把超时的加载器标成「可重试」，而不是误显示成「无」。
     */
    public static CompletableFuture<LoaderQueryResult> fetchAllLoaderVersionsListAsync(String mcVersion) {
        return CompletableFuture.supplyAsync(() -> {
            List<CompletableFuture<List<LoaderVersion>>> futures = new ArrayList<>();
            for (LoaderProvider p : LOADER_PROVIDERS) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return p.fetchVersions(mcVersion);
                    } catch (Exception e) {
                        LOG.warn("Loader list query failed: {} - {}", p.name(), e.getMessage());
                        return List.of();
                    }
                }, LOADER_QUERY_POOL));
            }
            boolean timedOut = false;
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(LOADER_LIST_DEADLINE_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                timedOut = true;
            }
            List<LoaderVersion> result = new ArrayList<>();
            Set<String> timedOutNames = new LinkedHashSet<>();
            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<List<LoaderVersion>> f = futures.get(i);
                List<LoaderVersion> list = f.getNow(null);
                if (list == null) {
                    // 到点仍未完成：该加载器「本轮超时」，记名供 UI 显示「重试」
                    timedOutNames.add(LOADER_PROVIDERS.get(i).name());
                    continue;
                }
                for (LoaderVersion lv : list) {
                    if (lv != null && lv.isAvailable() && lv.getVersion() != null) {
                        result.add(lv);
                    }
                }
            }
            return new LoaderQueryResult(result, timedOutNames, timedOut);
        }, LOADER_QUERY_POOL);
    }

    /** 加载器列表查询的等待上限（秒）：略宽于单条官方候选的 20s，给并行竞速一点余量 */
    private static final int LOADER_LIST_DEADLINE_SECONDS = 22;

    /**
     * 加载器列表查询结果：成功项 + 本轮超时的加载器名（供 UI 显示「重试」而非「无」）。
     */
    public static class LoaderQueryResult {
        private final List<LoaderVersion> versions;
        private final Set<String> timedOutLoaders;
        private final boolean timedOut;

        public LoaderQueryResult(List<LoaderVersion> versions, Set<String> timedOutLoaders, boolean timedOut) {
            this.versions = versions == null ? List.of() : versions;
            this.timedOutLoaders = timedOutLoaders == null ? Set.of() : timedOutLoaders;
            this.timedOut = timedOut;
        }

        public List<LoaderVersion> versions() { return versions; }
        /** 本轮因超时未查到的加载器名 */
        public Set<String> timedOutLoaders() { return timedOutLoaders; }
        /** 是否发生了整体超时（存在未完成的加载器） */
        public boolean timedOut() { return timedOut; }
    }

    /**
     * 加载器版本号从新到旧排序（按数字段比较）。
     *
     * <p>各加载器的元数据大多是按时间升序返回的，直接展示会让界面上「最新版」变成最老的一个；
     * 这里统一排一次。非数字段（如 OptiFine 的 {@code I5}、Fabric 的 {@code beta.9}）
     * 退回字符串比较，保证不会抛异常。
     */
    public static void sortLoaderVersionsDesc(List<LoaderVersion> list) {
        if (list == null || list.size() < 2) return;
        list.sort((a, b) -> compareLoaderVersionDesc(a.getVersion(), b.getVersion()));
    }

    private static int compareLoaderVersionDesc(String a, String b) {
        String[] pa = String.valueOf(a).split("[._\\-+]");
        String[] pb = String.valueOf(b).split("[._\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "";
            String sb = i < pb.length ? pb[i] : "";
            Integer na = parseIntOrNull(sa);
            Integer nb = parseIntOrNull(sb);
            int cmp;
            if (na != null && nb != null) {
                cmp = Integer.compare(nb, na);        // 数字段：大的更新
            } else if (na != null) {
                cmp = -1;                              // 纯数字段（正式）排在字母段（pre/beta）之前
            } else if (nb != null) {
                cmp = 1;
            } else {
                cmp = sb.compareToIgnoreCase(sa);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 同步获取所有加载器信息（阻塞） */
    public static LoaderInfoBundle fetchAllLoaderVersions(String mcVersion) {
        try {
            return fetchAllLoaderVersionsAsync(mcVersion).get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return new LoaderInfoBundle(mcVersion,
                    new LoaderVersion("Forge", null, false, null),
                    new LoaderVersion("Fabric", null, false, null),
                    new LoaderVersion("NeoForge", null, false, null),
                    new LoaderVersion("OptiFine", null, false, null),
                    new LoaderVersion("FabricAPI", null, false, null),
                    new LoaderVersion("Quilt", null, false, null),
                    new LoaderVersion("QSL", null, false, null));
        }
    }

    /** 通过 LoaderProvider 查询并缓存（带 in-flight 去重） */
    private static LoaderVersion fetchWithCache(LoaderProvider provider, String mcVersion) {
        String cacheKey = provider.name().toLowerCase(Locale.ROOT) + "_" + mcVersion;
        CacheEntry entry = LOADER_CACHE.get(cacheKey);
        if (entry != null && System.currentTimeMillis() - entry.cachedAt() < LOADER_CACHE_TTL_MS) {
            return entry.version();
        }

        // 并发去重：同一 key 的并发调用共享同一次网络拉取，避免预加载与配置页查询时间重叠时
        // 对同一个 URL 打两遍（日志里曾出现 fabric-meta/neoforge 同 URL 4 秒内发两次）
        CompletableFuture<LoaderVersion> inFlight = LOADER_IN_FLIGHT.get(cacheKey);
        if (inFlight != null) {
            try {
                return inFlight.join();
            } catch (Exception ignored) {
                return new LoaderVersion(provider.name(), null, false, null);
            }
        }
        CompletableFuture<LoaderVersion> task = new CompletableFuture<>();
        CompletableFuture<LoaderVersion> previous = LOADER_IN_FLIGHT.putIfAbsent(cacheKey, task);
        if (previous != null) {
            // 竞态：另一线程先放入了，共享它的结果
            try {
                return previous.join();
            } catch (Exception ignored) {
                return new LoaderVersion(provider.name(), null, false, null);
            }
        }
        try {
            LoaderVersion result = provider.fetchVersion(mcVersion);
            // 只缓存成功结果：失败（available=false）不进缓存，下次查询立刻重试。
            // 旧实现把失败结果也 put 进来且永不过期，一次网络抖动 = 本次运行期间
            // 该加载器永远显示「无」，重启才恢复
            if (result != null && result.isAvailable() && result.getVersion() != null) {
                LOADER_CACHE.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
            }
            task.complete(result);
            return result;
        } catch (Exception e) {
            LoaderVersion fail = new LoaderVersion(provider.name(), null, false, null);
            task.complete(fail);
            return fail;
        } finally {
            LOADER_IN_FLIGHT.remove(cacheKey);
        }
    }

    /** 预加载热门版本的加载器信息（静默，不阻塞） */
    public static void preloadPopularVersions(List<VersionInfo> allVersions) {
        if (allVersions == null || allVersions.isEmpty()) return;
        List<String> topReleases = allVersions.stream()
                .filter(v -> v.getCategory() == VersionCategory.RELEASE)
                .limit(3)
                .map(VersionInfo::getId)
                .collect(Collectors.toList());

        // 并行预加载：限流 4 并发避免连接风暴，串行请求最坏需 21×超时，并行可显著缩短
        ExecutorService preloadPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "loader-preload");
            t.setDaemon(true);
            return t;
        });
        preloadPool.submit(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (String mcVer : topReleases) {
                for (LoaderProvider p : LOADER_PROVIDERS) {
                    tasks.add(preloadPool.submit(() -> fetchWithCache(p, mcVer)));
                }
            }
            for (java.util.concurrent.Future<?> f : tasks) {
                try { f.get(); } catch (Exception ignored) {}
            }
            preloadPool.shutdown();
        });
    }

    public static int getCacheSize() { return LOADER_CACHE.size(); }
    public static void clearCache() {
        LOADER_CACHE.clear();
        LOADER_IN_FLIGHT.clear();
    }

    // 内部工具方法 //

    private static LoaderVersion getCachedOrFallback(String type, String mcVersion) {
        CacheEntry entry = LOADER_CACHE.get(type + "_" + mcVersion);
        if (entry != null && System.currentTimeMillis() - entry.cachedAt() < LOADER_CACHE_TTL_MS) {
            return entry.version();
        }
        return new LoaderVersion(
                type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1),
                null, false, null);
    }

    public static boolean isAprilFoolsVersion(String id, String releaseTime) {
        if (id == null) return false;
        String lowerId = id.toLowerCase(Locale.ROOT);
        for (String kw : APRIL_FOOLS_KEYWORDS) {
            if (lowerId.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        try {
            Instant inst = Instant.parse(releaseTime);
            java.time.ZonedDateTime zdt = inst.atZone(java.time.ZoneOffset.UTC);
            return zdt.getMonthValue() == 4 && zdt.getDayOfMonth() == 1;
        } catch (Exception ignored) {}
        return false;
    }

    public static VersionCategory categorizeVersion(String id, String type, String releaseTime) {
        if (isAprilFoolsVersion(id, releaseTime)) return VersionCategory.APRIL;
        if ("release".equals(type)) return VersionCategory.RELEASE;
        if ("snapshot".equals(type)) return VersionCategory.SNAPSHOT;
        if ("old_alpha".equals(type) || "old_beta".equals(type)) return VersionCategory.OLD;
        return VersionCategory.OTHER;
    }

    public static String formatDate(String dateISO) {
        if (dateISO == null || dateISO.isEmpty()) return I18N.get("version.t0003");
        try {
            Instant inst = Instant.parse(dateISO);
            java.time.ZonedDateTime zdt = inst.atZone(java.time.ZoneOffset.UTC);
            return zdt.getYear() + "." + zdt.getMonthValue() + "." + zdt.getDayOfMonth();
        } catch (Exception ignored) { return I18N.get("version.t0004"); }
    }

    // HTTP 和 JSON 工具 //

    /** Modrinth 官方 API 前缀与其 MCIM 镜像前缀（mod.mcimirror.top，与下载源的替换规则同一份地址） */
    private static final String MODRINTH_API_PREFIX = "https://api.modrinth.com";
    private static final String MCIM_MIRROR_PREFIX = "https://mod.mcimirror.top/modrinth";

    /** 镜像尝试的超时（比官方短）：镜像不可用时也不让回落官方等太久 */
    private static final Duration MIRROR_TIMEOUT = Duration.ofSeconds(6);

    /** Modrinth 镜像熔断：连续 2 次失败后 60 秒内直接走官方，不再每个请求都先付一次镜像超时 */
    private static final com.example.starlight.util.MirrorCircuitBreaker MODRINTH_MIRROR_BREAKER =
            new com.example.starlight.util.MirrorCircuitBreaker(2, 60_000L);

    /** 公共可见，供 LoaderProvider 和 InstallEngine 使用 */
    public static String fetchString(String url) {
        if (url == null || url.isBlank()) return null;
        // Modrinth 的请求（Fabric API / QSL 等加载器版本查询）先走 MCIM 镜像，失败回落官方域名
        if (url.startsWith(MODRINTH_API_PREFIX)) {
            if (MODRINTH_MIRROR_BREAKER.allowMirror()) {
                String mirrorUrl = MCIM_MIRROR_PREFIX + url.substring(MODRINTH_API_PREFIX.length());
                // 镜像与官方并行竞速：谁先拿到正文就用谁（镜像慢时官方往往更快，见下方主候选链说明）
                String body = fetchFirstSuccess(List.of(mirrorUrl, url));
                if (body != null) {
                    // 竞速无法区分谁赢，按「官方是否也在」保守更新熔断：正文非空即说明至少一条成功，
                    // 镜像是否健康留待下一次判断；这里不记录失败，避免把「镜像慢但官方快」误判成镜像挂掉
                    MODRINTH_MIRROR_BREAKER.recordSuccess();
                    return body;
                }
                MODRINTH_MIRROR_BREAKER.recordFailure();
                return null;
            }
            return fetchFirstSuccess(List.of(url));
        }
        // 候选链（对标 PCL DlSourceOrder：镜像优先、官方兜底）：
        //   1) 当前选中下载源的镜像（BMCLAPI/MCBBS 的 URL 替换表，注入后已含官方原始 URL 兜底）
        //   2) BMCLAPI 镜像（自动兜底：选中源为官方时，也给一条国内可达的镜像路径）
        //   3) 官方原始 URL（最终兜底）
        // 改为<b>全部候选并行竞速</b>：旧串行逐候选尝试（每个还 HTTP/2 + HTTP/1.1 两次、镜像 6s 官方 20s）
        // 最坏要 12+40=52 秒叠加，超过 UI 的 10 秒预算，导致加载器显示「无」。
        // 并行后最坏 ≈ 单条候选的最坏延迟，任何一条先成功即返回。
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (URI u : DownloadProviderHolder.get().injectURLWithCandidates(url)) {
            candidates.add(u.toString());
        }
        candidates.add(BMCLAPI_PROVIDER.injectURL(url));
        candidates.add(url);
        return fetchFirstSuccess(candidates);
    }

    /**
     * 候选链并行竞速：全部候选同时发出（每条先 HTTP/2，失败再 HTTP/1.1），
     * 取先拿到正文的那个；全部失败返回 {@code null}。
     *
     * <p>与 {@link VersionManifest#fetchFirstSuccessJson} 同一思路：不押注单一路径，
     * 被墙/失效的候选只会并行空转，不再串行拖慢整体。HTTP/1.1 降级只发生在
     * 该候选的 HTTP/2 明确失败之后（例如 RST_STREAM），而不是无条件二次请求。
     */
    private static String fetchFirstSuccess(Collection<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        List<CompletableFuture<String>> pending = new ArrayList<>();
        for (String url : urls) {
            pending.add(CompletableFuture.supplyAsync(() -> fetchOnceWithFallback(url), METADATA_RACE_POOL)
                    .exceptionally(e -> null));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            try {
                CompletableFuture.anyOf(pending.toArray(new CompletableFuture[0]))
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                continue;
            }
            String firstSuccess = null;
            Iterator<CompletableFuture<String>> it = pending.iterator();
            while (it.hasNext()) {
                CompletableFuture<String> f = it.next();
                if (!f.isDone()) continue;
                it.remove();
                if (firstSuccess != null) continue;
                try {
                    String body = f.join();
                    if (body != null && !body.isEmpty()) firstSuccess = body;
                } catch (Exception ignored) {
                    // 该候选失败，继续看其他
                }
            }
            if (firstSuccess != null) return firstSuccess;
        }
        return null;
    }

    /**
     * 单条候选：先 HTTP/2，明确失败（如 RST_STREAM）再降级 HTTP/1.1 重试一次。
     * <p>超时按「是否官方域名」区分：镜像 6s、官方 20s。只有该候选的 HTTP/2 真的
     * 失败才付第二次请求的代价，不再无条件两连。
     */
    private static String fetchOnceWithFallback(String url) {
        boolean official = isOfficialCandidate(url);
        Duration timeout = official ? METADATA_OFFICIAL_TIMEOUT : METADATA_MIRROR_TIMEOUT;
        String body = fetchStringWith(HTTP_CLIENT, url, timeout);
        if (body != null) return body;
        body = fetchStringWith(HTTP_CLIENT_FALLBACK, url, timeout);
        return body;
    }

    /** 是否为官方域名候选（官方给更长超时，镜像给短超时快速换源） */
    private static boolean isOfficialCandidate(String url) {
        if (url == null) return false;
        return url.startsWith("https://meta.fabricmc.net")
                || url.startsWith("https://maven.minecraftforge.net")
                || url.startsWith("https://maven.neoforged.net")
                || url.startsWith("https://meta.quiltmc.org")
                || url.startsWith("https://api.modrinth.com");
    }

    private static String fetchStringWith(HttpClient client, String url, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .timeout(timeout)
                    .GET();
            // 显式请求 gzip：JDK HttpClient 不会自动带 Accept-Encoding。
            // 这条路径供各 LoaderProvider 查 Modrinth 版本列表（FabricAPI / QSL 的
            // /project/{id}/version 可达数 MB），不压缩会明显拖慢加载器版本查询。
            com.example.starlight.util.HttpText.acceptGzip(builder);
            HttpRequest request = builder.build();
            DebugLog.http(true, "GET", url, 0, 0, null, -1);
            HttpResponse<byte[]> response = client.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            String body = response.statusCode() >= 200 && response.statusCode() < 300
                    ? com.example.starlight.util.HttpText.decode(response) : null;
            DebugLog.http(false, null, url, response.statusCode(),
                    body != null ? body.length() : 0, body, -1);
            if (body != null) {
                return body;
            } else {
                System.err.println("[VersionDownloadService] HTTP " + response.statusCode() + " for " + url);
                return null;
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[VersionDownloadService] Request failed: " + url + " -> " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 公共可见，供 LoaderProvider 实现类使用 */
    public static String getJsonString(JsonObject obj, String memberName) {
        if (obj == null || !obj.has(memberName)) return null;
        var element = obj.get(memberName);
        if (element == null || !element.isJsonPrimitive()) return null;
        String value = element.getAsString();
        return value.isEmpty() ? null : value;
    }

    /** 获取 JSON 布尔值，供 LoaderProvider 实现类使用 */
    public static boolean getJsonBoolean(JsonObject obj, String memberName) {
        if (obj == null || !obj.has(memberName)) return false;
        var element = obj.get(memberName);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static List<VersionInfo> parseManifestJson(String json) {
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonArray versionsArray = root.getAsJsonArray("versions");
            if (versionsArray == null || versionsArray.isEmpty()) return Collections.emptyList();
            List<VersionInfo> entries = new ArrayList<>(versionsArray.size());
            for (int i = 0; i < versionsArray.size(); i++) {
                JsonObject obj = versionsArray.get(i).getAsJsonObject();
                String id = getJsonString(obj, "id");
                String type = getJsonString(obj, "type");
                String releaseTime = getJsonString(obj, "releaseTime");
                String url = getJsonString(obj, "url");
                if (id != null && type != null && url != null) {
                    VersionCategory cat = categorizeVersion(id, type, releaseTime);
                    if (cat != VersionCategory.OTHER) {
                        entries.add(new VersionInfo(id, type, releaseTime, url, cat));
                    }
                }
            }
            entries.sort((a, b) -> b.releaseTime.compareTo(a.releaseTime));
            return entries;
        } catch (Exception e) {
            System.err.println("[VersionDownloadService] Failed to parse version manifest: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
