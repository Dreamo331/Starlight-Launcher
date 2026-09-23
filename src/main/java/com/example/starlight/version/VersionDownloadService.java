package com.example.starlight.version;

import com.example.starlight.download.BMCLAPIDownloadProvider;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.util.DebugLog;
import com.example.starlight.download.DownloadProvider;
import com.example.starlight.download.MCBBSDownloadProvider;
import com.example.starlight.download.MojangDownloadProvider;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    // ==================== 常量 ====================

    private static final String MOJANG_MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String BMCLAPI_MANIFEST_URL =
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest.json";

    /** 公共镜像源，供 LoaderProvider 和 InstallEngine 使用 */
    public static final String BMCLAPI_BASE =
            "https://bmclapi2.bangbang93.com";

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
     * 加载器查询专用线程池。
     *
     * <p>此前这些查询走 {@link java.util.concurrent.ForkJoinPool#commonPool()}：一次查询要并行打
     * 6 个后端（Forge/Fabric/NeoForge/OptiFine/Quilt/QSL），而下载页打开时还会批量预热热门版本，
     * 公共池很快被占满，导致版本详情页的加载器列表要等十几秒才出现。
     * 换成独立的小线程池后，既不与预热带宽互相排队，也不会污染公共池。
     */
    private static final java.util.concurrent.ExecutorService LOADER_QUERY_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "sl-loader-query");
                t.setDaemon(true);
                return t;
            });

    /** 加载器查询线程池（供需要自行并行查询的调用方复用） */
    public static java.util.concurrent.ExecutorService getLoaderQueryPool() {
        return LOADER_QUERY_POOL;
    }

    /** 当前使用的下载提供者（默认官方源，可切换至 BMCLAPI） */
    private static DownloadProvider downloadProvider = new MojangDownloadProvider();

    /**
     * 设置下载提供者
     */
    public static void setDownloadProvider(DownloadProvider provider) {
        downloadProvider = Objects.requireNonNull(provider);
    }

    /**
     * 获取当前下载提供者
     */
    public static DownloadProvider getDownloadProvider() {
        return downloadProvider;
    }

    /**
     * 根据下载源名称应用下载提供者（与设置页「下载源」选项一致）
     * <p>
     * 支持：BMCLAPI / Mojang / MCBBS；未知值回退 BMCLAPI。
     * 切换后立即生效，后续版本清单获取、原版安装、资源补全等均使用新下载源。
     *
     * @param source 下载源名称（大小写不敏感）
     * @return 实际应用后的下载提供者
     */
    public static DownloadProvider applyDownloadSource(String source) {
        DownloadProvider provider;
        if ("Mojang".equalsIgnoreCase(source)) {
            provider = new MojangDownloadProvider();
        } else if ("MCBBS".equalsIgnoreCase(source)) {
            provider = new MCBBSDownloadProvider();
        } else {
            provider = new BMCLAPIDownloadProvider(BMCLAPI_BASE);
        }
        setDownloadProvider(provider);
        return provider;
    }

    // 愚人节版本关键词
    private static final List<String> APRIL_FOOLS_KEYWORDS = Arrays.asList(
            "1.rv", "15w14a", "3d shareware", "20w14∞", "20w14infinite",
            "22w13oneblockatatime", "23w13a_or_b", "24w14potato"
    );

    // ==================== 版本分类 ====================

    public enum VersionCategory {
        RELEASE("release", "正式版", ""),
        SNAPSHOT("snapshot", "预览版", ""),
        OLD("old", "远古版", ""),
        APRIL("april", "愚人节版", ""),
        OTHER("other", "其他", "");

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

    // ==================== 数据模型 ====================

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
            if (available) return version != null ? version : "可用";
            return "暂未找到";
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

    // ==================== 版本清单获取 ====================

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
     * 先尝试 Mojang 官方 API，失败后自动回退到 BMCLAPI。
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
            return future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** 丢弃清单缓存，下次 {@link #fetchVersionManifest()} 强制重新联网拉取（刷新按钮用） */
    public static void invalidateManifestCache() {
        synchronized (MANIFEST_LOCK) {
            manifestCache = Collections.emptyList();
            manifestCacheAt = 0;
            manifestInFlight = null;
        }
    }

    /** 真正联网拉取并解析清单（无缓存，由 {@link #fetchVersionManifest()} 调度） */
    private static List<VersionInfo> doFetchVersionManifest() {
        // 使用 DownloadProvider 获取版本清单 URL
        List<URI> urls = downloadProvider.getVersionListURLs();
        String json = null;
        for (URI uri : urls) {
            json = fetchString(uri.toString());
            if (json != null) break;
        }
        // 回退：尝试 BMCLAPI（如果 DownloadProvider 不是 BMCLAPI）
        if (json == null && !(downloadProvider instanceof BMCLAPIDownloadProvider)) {
            json = fetchString(BMCLAPI_MANIFEST_URL);
        }
        if (json == null) {
            System.err.println("[VersionDownloadService] Failed to fetch version manifest from download source.");
            return Collections.emptyList();
        }
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

    // ==================== 加载器 API ====================

    /** LoaderProvider 实例列表（可扩展） */
    public static final List<LoaderProvider> LOADER_PROVIDERS = List.of(
            new ForgeProvider(), new FabricProvider(), new NeoForgeProvider(), new OptiFineProvider(),
            new FabricAPIProvider(), new QuiltProvider(), new QSLProvider()
    );

    // 内存缓存：key = loaderType + "_" + mcVersion, value = LoaderVersion
    private static final Map<String, LoaderVersion> LOADER_CACHE = new HashMap<>();

    /**
     * 异步获取某版本的所有加载器信息（并行请求，使用 LoaderProvider）
     */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<LoaderInfoBundle> fetchAllLoaderVersionsAsync(String mcVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompletableFuture<LoaderVersion>[] futures = LOADER_PROVIDERS.stream()
                        .map(p -> CompletableFuture.supplyAsync(() -> fetchWithCache(p, mcVersion)))
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
     */
    public static CompletableFuture<List<LoaderVersion>> fetchAllLoaderVersionsListAsync(String mcVersion) {
        return CompletableFuture.supplyAsync(() -> {
            List<CompletableFuture<List<LoaderVersion>>> futures = new ArrayList<>();
            for (LoaderProvider p : LOADER_PROVIDERS) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return p.fetchVersions(mcVersion);
                    } catch (Exception e) {
                        return List.of();
                    }
                }, LOADER_QUERY_POOL));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            List<LoaderVersion> result = new ArrayList<>();
            for (CompletableFuture<List<LoaderVersion>> f : futures) {
                List<LoaderVersion> list = f.getNow(List.of());
                for (LoaderVersion lv : list) {
                    if (lv != null && lv.isAvailable() && lv.getVersion() != null) {
                        result.add(lv);
                    }
                }
            }
            return result;
        }, LOADER_QUERY_POOL);
    }

    /** 同步获取某 MC 版本的全部可用加载器版本列表（阻塞，最长 10s） */
    public static List<LoaderVersion> fetchAllLoaderVersionsList(String mcVersion) {
        try {
            return fetchAllLoaderVersionsListAsync(mcVersion).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return List.of();
        }
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

    /** 通过 LoaderProvider 查询并缓存 */
    private static LoaderVersion fetchWithCache(LoaderProvider provider, String mcVersion) {
        String cacheKey = provider.name().toLowerCase(Locale.ROOT) + "_" + mcVersion;
        LoaderVersion cached = LOADER_CACHE.get(cacheKey);
        if (cached != null) return cached;
        LoaderVersion result = provider.fetchVersion(mcVersion);
        LOADER_CACHE.put(cacheKey, result);
        return result;
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
    public static void clearCache() { LOADER_CACHE.clear(); }

    // ==================== 内部工具方法 ====================

    private static LoaderVersion getCachedOrFallback(String type, String mcVersion) {
        LoaderVersion cached = LOADER_CACHE.get(type + "_" + mcVersion);
        return cached != null ? cached : new LoaderVersion(
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
        if (dateISO == null || dateISO.isEmpty()) return "未知";
        try {
            Instant inst = Instant.parse(dateISO);
            java.time.ZonedDateTime zdt = inst.atZone(java.time.ZoneOffset.UTC);
            return zdt.getYear() + "." + zdt.getMonthValue() + "." + zdt.getDayOfMonth();
        } catch (Exception ignored) { return "未知"; }
    }

    // ==================== HTTP 和 JSON 工具 ====================

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
        // Modrinth 的请求（Fabric API / QSL 等加载器版本查询）先走 MCIM 镜像，失败回落官方域名
        if (url != null && url.startsWith(MODRINTH_API_PREFIX)) {
            if (MODRINTH_MIRROR_BREAKER.allowMirror()) {
                String mirrorUrl = MCIM_MIRROR_PREFIX + url.substring(MODRINTH_API_PREFIX.length());
                String body = fetchStringWith(HTTP_CLIENT, mirrorUrl, MIRROR_TIMEOUT);
                if (body == null) body = fetchStringWith(HTTP_CLIENT_FALLBACK, mirrorUrl, MIRROR_TIMEOUT);
                if (body != null) {
                    MODRINTH_MIRROR_BREAKER.recordSuccess();
                    return body;
                }
                MODRINTH_MIRROR_BREAKER.recordFailure();
            }
        }
        return fetchWithFallback(url);
    }

    /** HTTP/2 取一次，失败再用 HTTP/1.1 重试一次（部分服务器如 meta.quiltmc.org 与 HTTP/2 不兼容） */
    private static String fetchWithFallback(String url) {
        String body = fetchStringWith(HTTP_CLIENT, url);
        if (body == null) {
            body = fetchStringWith(HTTP_CLIENT_FALLBACK, url);
        }
        return body;
    }

    private static String fetchStringWith(HttpClient client, String url) {
        return fetchStringWith(client, url, Duration.ofSeconds(15));
    }

    private static String fetchStringWith(HttpClient client, String url, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .timeout(timeout)
                    .GET()
                    .build();
            DebugLog.http(true, "GET", url, 0, 0, null, -1);
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            DebugLog.http(false, null, url, response.statusCode(),
                    response.body() != null ? response.body().length() : 0,
                    response.body(), -1);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
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
