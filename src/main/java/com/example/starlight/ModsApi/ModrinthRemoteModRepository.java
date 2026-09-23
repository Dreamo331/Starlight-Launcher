package com.example.starlight.ModsApi;

import com.example.starlight.download.DownloadProvider;
import com.example.starlight.newui.AppConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Modrinth 远程 MOD 仓库实现（参考 HMCL 架构设计）
 * 将 ModrinthAPI 包装为 RemoteModRepository 接口
 */
public class ModrinthRemoteModRepository implements RemoteModRepository {

    public static final ModrinthRemoteModRepository MODS = new ModrinthRemoteModRepository(Type.MOD);
    public static final ModrinthRemoteModRepository MODPACKS = new ModrinthRemoteModRepository(Type.MODPACK);
    public static final ModrinthRemoteModRepository RESOURCE_PACKS = new ModrinthRemoteModRepository(Type.RESOURCE_PACK);
    public static final ModrinthRemoteModRepository SHADER_PACKS = new ModrinthRemoteModRepository(Type.SHADER_PACK);
    public static final ModrinthRemoteModRepository DATAPACKS = new ModrinthRemoteModRepository(Type.CUSTOMIZATION);

    /** Modrinth 官方 API 根地址（各接口按它拼 URL，实际走哪个源由 {@link #fetchString} 决定） */
    private static final String OFFICIAL_BASE = "https://api.modrinth.com/v2/";

    /**
     * MCIM 国内镜像根地址（mod.mcimirror.top）。
     * <p>与 {@code BMCLAPIDownloadProvider} 里的替换规则同一份地址
     * （{@code https://api.modrinth.com} → {@code https://mod.mcimirror.top/modrinth}），
     * 搜索/详情/版本/分类查询先走镜像，失败再回落官方域名。
     */
    private static final String MIRROR_BASE = "https://mod.mcimirror.top/modrinth/v2/";

    private static final String BASE_URL = OFFICIAL_BASE;
    private static final Gson GSON = new GsonBuilder().create();

    private final Type type;

    private ModrinthRemoteModRepository(Type type) {
        this.type = type;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public SearchResult search(DownloadProvider dp, ModSearchQuery query) throws IOException {
        if (query == null) return search(dp, null, null, 0, 20, "", null, SortOrder.DESC);
        try {
            List<String> facets = new ArrayList<>();
            String projectTypeStr = projectTypeFacet();
            if (projectTypeStr != null) {
                facets.add("[\"project_type:" + projectTypeStr + "\"]");
            }
            if (!query.getGameVersion().isEmpty()) {
                facets.add("[\"versions:" + query.getGameVersion() + "\"]");
            }
            // Modrinth 的加载器与分类都挂在 categories facet 上，分两组相加即「与」关系
            if (!query.getLoader().isEmpty()) {
                facets.add("[\"categories:" + query.getLoader() + "\"]");
            }
            if (!query.getCategory().isEmpty()) {
                facets.add("[\"categories:" + query.getCategory() + "\"]");
            }

            int size = Math.max(1, Math.min(query.getPageSize(), 100));
            StringBuilder url = new StringBuilder(BASE_URL + "search?limit=" + size
                    + "&offset=" + (query.getPageOffset() * size));
            if (!facets.isEmpty()) {
                url.append("&facets=").append(URLEncoder.encode(String.valueOf(facets), StandardCharsets.UTF_8));
            }
            if (!query.getQuery().isEmpty()) {
                url.append("&query=").append(URLEncoder.encode(query.getQuery(), StandardCharsets.UTF_8));
            }
            // sortType 为 null 表示「按相关度」，此时不传 index，交给 Modrinth 默认排序
            if (query.getSortType() != null) {
                url.append("&index=").append(toIndex(query.getSortType()));
            }

            return parseSearchResponse(fetchString(url.toString()), query.getPageSize());
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Modrinth search failed", e);
        }
    }

    /** 当前仓库对应的 Modrinth project_type facet 值；未知类型返回 null 表示不加限制 */
    private String projectTypeFacet() {
        return switch (type) {
            case MOD -> "mod";
            case MODPACK -> "modpack";
            case RESOURCE_PACK -> "resourcepack";
            case SHADER_PACK -> "shader";
            case CUSTOMIZATION -> "datapack";
            default -> null;
        };
    }

    /** 统一排序枚举 → Modrinth {@code index} 参数 */
    private static String toIndex(SortType sortType) {
        return switch (sortType) {
            case POPULARITY -> "downloads";
            case NAME -> "title";
            case DATE_CREATED -> "created";
            case LAST_UPDATED -> "updated";
            case AUTHOR -> "author";
            case TOTAL_DOWNLOADS -> "downloads";
        };
    }

    /** 解析 search 响应：命中列表 + 总页数（pageSize 用于换算页数） */
    private static SearchResult parseSearchResponse(String json, int pageSize) {
        if (json == null) return new SearchResult(Stream.empty(), 0);
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        JsonArray hits = root.getAsJsonArray("hits");
        int totalHits = root.get("total_hits") != null ? root.get("total_hits").getAsInt() : 0;
        int pages = (int) Math.ceil(totalHits / (double) Math.max(1, pageSize));
        if (hits == null || hits.isEmpty()) {
            return new SearchResult(Stream.empty(), 0);
        }
        List<RemoteMod> mods = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            mods.add(parseProjectStatic(hits.get(i).getAsJsonObject()));
        }
        return new SearchResult(mods.stream(), pages);
    }

    @Override
    public List<Category> getCategoriesForType() throws IOException {
        String want = projectTypeFacet();
        List<Category> all = getCategories().collect(Collectors.toList());
        if (want == null) return all;
        List<Category> filtered = new ArrayList<>();
        for (Category c : all) {
            if (!(c.getSelf() instanceof JsonObject obj)) continue;
            String pt = getJsonString(obj, "project_type");
            // 未标注 project_type 的分类（历史数据）按通用分类保留，避免下拉项缺失
            if (pt == null || pt.equals(want)) filtered.add(c);
        }
        return filtered.isEmpty() ? all : filtered;
    }

    @Override
    public RemoteModDetail getProjectDetail(DownloadProvider dp, String id) throws IOException {
        try {
            String url = BASE_URL + "project/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
            String json = fetchString(url);
            if (json == null) throw new IOException("Project not found: " + id);
            JsonObject obj = GSON.fromJson(json, JsonObject.class);

            List<String> gallery = new ArrayList<>();
            if (obj.get("gallery") != null && obj.get("gallery").isJsonArray()) {
                for (var el : obj.getAsJsonArray("gallery")) {
                    if (el == null || !el.isJsonObject()) continue;
                    String gUrl = getJsonString(el.getAsJsonObject(), "url");
                    if (gUrl != null) gallery.add(gUrl);
                }
            }

            String licenseName = "";
            String licenseUrl = "";
            if (obj.get("license") != null && obj.get("license").isJsonObject()) {
                JsonObject lic = obj.getAsJsonObject("license");
                String n = getJsonString(lic, "name");
                String uid = getJsonString(lic, "id");
                licenseName = n != null ? n : (uid != null ? uid : "");
                licenseUrl = getJsonString(lic, "url");
            }

            List<String> categories = new ArrayList<>();
            if (obj.get("categories") != null && obj.get("categories").isJsonArray()) {
                for (var e : obj.getAsJsonArray("categories")) categories.add(e.getAsString());
            }
            // Modrinth 的 additional_categories 是「次要分类」，详情页一并展示更完整
            if (obj.get("additional_categories") != null && obj.get("additional_categories").isJsonArray()) {
                for (var e : obj.getAsJsonArray("additional_categories")) {
                    String c = e.getAsString();
                    if (!categories.contains(c)) categories.add(c);
                }
            }

            String slug = getJsonString(obj, "slug");

            return RemoteModDetail.builder()
                    .id(getJsonString(obj, "id") != null ? getJsonString(obj, "id") : id)
                    .slug(slug != null ? slug : id)
                    .title(getJsonString(obj, "title"))
                    .description(getJsonString(obj, "description"))
                    .bodyFormat(RemoteModDetail.BodyFormat.MARKDOWN)
                    .body(getJsonString(obj, "body"))
                    .iconUrl(getJsonString(obj, "icon_url"))
                    .license(licenseName)
                    .licenseUrl(licenseUrl)
                    .clientSide(getJsonString(obj, "client_side"))
                    .serverSide(getJsonString(obj, "server_side"))
                    .published(getJsonString(obj, "published"))
                    .updated(getJsonString(obj, "updated"))
                    .downloads(obj.get("downloads") != null ? obj.get("downloads").getAsLong() : 0)
                    .followers(obj.get("followers") != null ? obj.get("followers").getAsLong() : 0)
                    .gallery(gallery)
                    .categories(categories)
                    .pageUrl("https://modrinth.com/" + (slug != null ? slug : id))
                    .sourceUrl(getJsonString(obj, "source_url"))
                    .issuesUrl(getJsonString(obj, "issues_url"))
                    .wikiUrl(getJsonString(obj, "wiki_url"))
                    .discordUrl(getJsonString(obj, "discord_url"))
                    .build();
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Failed to get project detail: " + id, e);
        }
    }

    /**
     * 旧版搜索入口（无加载器/分类维度）。
     * <p>为保证只有一条实现路径，这里把参数装进 {@link ModSearchQuery} 后复用新版搜索；
     * {@code category} 参数历史上就被忽略（Modrinth 分类通过 facet 表达），保持一致。
     */
    @Override
    public SearchResult search(DownloadProvider dp, String gameVersion, Category category,
                               int pageOffset, int pageSize, String searchFilter,
                               SortType sortType, SortOrder sortOrder) throws IOException {
        return search(dp, new ModSearchQuery()
                .setGameVersion(gameVersion)
                .setPageOffset(pageOffset)
                .setPageSize(pageSize)
                .setQuery(searchFilter)
                .setSortType(sortType)
                .setSortOrder(sortOrder));
    }

    @Override
    public Optional<RemoteMod.Version> getRemoteVersionByLocalFile(LocalModFile localModFile, Path file) {
        return Optional.empty(); // TODO
    }

    @Override
    public RemoteMod getModById(DownloadProvider dp, String id) throws IOException {
        try {
            String url = BASE_URL + "project/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
            String json = fetchString(url);
            if (json == null) throw new IOException("Project not found: " + id);
            return parseProject(GSON.fromJson(json, JsonObject.class));
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Failed to get mod: " + id, e);
        }
    }

    @Override
    public RemoteMod.File getModFile(String modId, String fileId) throws IOException {
        try {
            // Modrinth 文件信息内嵌在版本中
            List<RemoteMod.Version> versions = getRemoteVersionsById(null, modId)
                    .collect(Collectors.toList());
            for (RemoteMod.Version v : versions) {
                if (v.getFile() != null) return v.getFile();
            }
            throw new IOException("No file found for mod: " + modId);
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Failed to get mod file", e);
        }
    }

    @Override
    public Stream<RemoteMod.Version> getRemoteVersionsById(DownloadProvider dp, String id) throws IOException {
        try {
            String url = BASE_URL + "project/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/version";
            String json = fetchString(url);
            if (json == null) return Stream.empty();

            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null) return Stream.empty();

            List<RemoteMod.Version> versions = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                versions.add(parseVersion(arr.get(i).getAsJsonObject(), id));
            }
            return versions.stream();
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Failed to get versions for: " + id, e);
        }
    }

    @Override
    public Stream<Category> getCategories() throws IOException {
        try {
            String url = BASE_URL + "tag/category";
            String json = fetchString(url);
            if (json == null) return Stream.empty();
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null) return Stream.empty();
            List<Category> cats = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                String name = obj.get("name") != null ? obj.get("name").getAsString() : "";
                cats.add(new Category(obj, name, List.of()));
            }
            return cats.stream();
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Failed to get categories", e);
        }
    }

    // ==================== 内部辅助 ====================

    private RemoteMod parseProject(JsonObject obj) {
        return parseProjectStatic(obj);
    }

    /** 解析项目对象为 {@link RemoteMod}（静态实现，便于新老搜索路径共用） */
    private static RemoteMod parseProjectStatic(JsonObject obj) {
        String slug = getJsonString(obj, "slug");
        String title = getJsonString(obj, "title");
        String desc = getJsonString(obj, "description");
        String iconUrl = getJsonString(obj, "icon_url");
        String author = getJsonString(obj, "author");
        String projectId = getJsonString(obj, "project_id");
        if (projectId == null) projectId = getJsonString(obj, "id");
        final String finalProjectId = projectId;

        int downloads = 0;
        if (obj.get("downloads") != null && obj.get("downloads").isJsonPrimitive()) {
            try { downloads = obj.get("downloads").getAsInt(); } catch (Exception ignored) {}
        }

        List<String> categories = new ArrayList<>();
        if (obj.get("categories") != null && obj.get("categories").isJsonArray()) {
            for (var e : obj.getAsJsonArray("categories")) categories.add(e.getAsString());
        }

        String pageUrl = "https://modrinth.com/" + (slug != null ? slug : projectId);

        return new RemoteMod(
                slug != null ? slug : projectId,
                author != null ? author : "",
                title != null ? title : "",
                desc != null ? desc : "",
                categories,
                pageUrl,
                iconUrl != null ? iconUrl : "",
                new RemoteMod.IMod() {
                    @Override
                    public List<RemoteMod> loadDependencies(RemoteModRepository repo, DownloadProvider dp) throws IOException {
                        return List.of();
                    }

                    @Override
                    public Stream<RemoteMod.Version> loadVersions(RemoteModRepository repo, DownloadProvider dp) throws IOException {
                        return repo.getRemoteVersionsById(dp, finalProjectId);
                    }
                },
                downloads
        );
    }

    private RemoteMod.Version parseVersion(JsonObject obj, String projectId) {
        String id = getJsonString(obj, "id");
        String name = getJsonString(obj, "name");
        String versionNumber = getJsonString(obj, "version_number");
        String datePublished = getJsonString(obj, "date_published");
        String changelog = getJsonString(obj, "changelog");

        // 版本类型：release / beta / alpha —— 下载页的稳定版·测试版·内测版徽章依赖该字段，
        // 早期实现统一按 Release 返回，导致所有版本都显示为正式版
        RemoteMod.VersionType versionType = switch (getJsonString(obj, "version_type") != null
                ? getJsonString(obj, "version_type") : "release") {
            case "beta" -> RemoteMod.VersionType.Beta;
            case "alpha" -> RemoteMod.VersionType.Alpha;
            default -> RemoteMod.VersionType.Release;
        };

        List<String> gameVersions = new ArrayList<>();
        if (obj.get("game_versions") != null && obj.get("game_versions").isJsonArray()) {
            for (var e : obj.getAsJsonArray("game_versions")) gameVersions.add(e.getAsString());
        }

        List<String> loaders = new ArrayList<>();
        if (obj.get("loaders") != null && obj.get("loaders").isJsonArray()) {
            for (var e : obj.getAsJsonArray("loaders")) loaders.add(e.getAsString());
        }
        List<ModLoaderType> loaderTypes = loaders.stream()
                .map(l -> switch (l) {
                    case "forge" -> ModLoaderType.FORGE;
                    case "neoforge" -> ModLoaderType.NEO_FORGED;
                    case "fabric" -> ModLoaderType.FABRIC;
                    case "quilt" -> ModLoaderType.QUILT;
                    default -> ModLoaderType.UNKNOWN;
                })
                .collect(Collectors.toList());

        RemoteMod.File file = null;
        if (obj.get("files") != null && obj.get("files").isJsonArray()) {
            JsonArray files = obj.getAsJsonArray("files");
            for (int i = 0; i < files.size(); i++) {
                JsonObject f = files.get(i).getAsJsonObject();
                String fUrl = getJsonString(f, "url");
                String fName = getJsonString(f, "filename");
                boolean primary = f.get("primary") != null && f.get("primary").getAsBoolean();
                if (primary || file == null) {
                    Map<String, String> hashes = new HashMap<>();
                    if (f.get("hashes") != null && f.get("hashes").isJsonObject()) {
                        JsonObject h = f.getAsJsonObject("hashes");
                        if (h.get("sha1") != null) hashes.put("sha1", h.get("sha1").getAsString());
                        if (h.get("sha512") != null) hashes.put("sha512", h.get("sha512").getAsString());
                    }
                    file = new RemoteMod.File(hashes, fUrl, fName);
                    if (primary) break;
                }
            }
        }

        // ====== 解析依赖关系 ======
        List<RemoteMod.Dependency> dependencyList = new ArrayList<>();
        if (obj.has("dependencies") && obj.get("dependencies").isJsonArray()) {
            JsonArray depsArray = obj.getAsJsonArray("dependencies");
            for (var de : depsArray) {
                JsonObject depObj = de.getAsJsonObject();
                String depProjId = getJsonString(depObj, "project_id");
                String depType = getJsonString(depObj, "dependency_type");
                if (depProjId != null) {
                    RemoteMod.DependencyType type = switch (depType != null ? depType : "optional") {
                        case "required" -> RemoteMod.DependencyType.REQUIRED;
                        case "optional" -> RemoteMod.DependencyType.OPTIONAL;
                        case "incompatible" -> RemoteMod.DependencyType.INCOMPATIBLE;
                        case "embedded" -> RemoteMod.DependencyType.EMBEDDED;
                        default -> RemoteMod.DependencyType.OPTIONAL;
                    };
                    dependencyList.add(RemoteMod.Dependency.of(type, MODS, depProjId));
                }
            }
        }

        return new RemoteMod.Version(
                (RemoteMod.IVersion) () -> RemoteMod.Type.MODRINTH,
                projectId,
                name != null ? name : versionNumber,
                versionNumber,
                changelog != null ? changelog : "",
                datePublished != null ? Instant.parse(datePublished) : Instant.now(),
                versionType,
                file,
                dependencyList,
                gameVersions,
                loaderTypes
        );
    }

    // ==================== HTTP 工具 ====================

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 取接口返回的 JSON：<b>先走 MCIM 镜像</b>（{@link #MIRROR_BASE}），失败再回落官方域名。
     *
     * <p>镜像与官方完全同构，只换前缀，因此这里统一在取数处改写，各接口照旧按官方地址拼 URL。
     * 两处都失败时返回 null（调用方按「查询失败/无结果」处理）。
     *
     * <p>镜像带熔断：连续 2 次失败后 60 秒内直接走官方——否则镜像一挂，每个请求都要
     * 先付满 6 秒镜像超时，一页五六个请求叠加后列表要等十几秒才有反应。
     */
    private static final com.example.starlight.util.MirrorCircuitBreaker MIRROR_BREAKER =
            new com.example.starlight.util.MirrorCircuitBreaker(2, 60_000L);

    private static String fetchString(String url) {
        String mirrored = url.startsWith(OFFICIAL_BASE)
                ? MIRROR_BASE + url.substring(OFFICIAL_BASE.length())
                : url;
        if (mirrored.equals(url)) return fetchOnce(url, false);
        if (!MIRROR_BREAKER.allowMirror()) return fetchOnce(url, false);
        String body = fetchOnce(mirrored, !mirrored.equals(url));
        if (body != null) {
            MIRROR_BREAKER.recordSuccess();
            return body;
        }
        MIRROR_BREAKER.recordFailure();
        return fetchOnce(url, false);   // 镜像没取到，回落官方
    }

    /** 镜像尝试的超时（比官方短）：镜像挂了也不会让回落官方等太久 */
    private static final Duration MIRROR_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** 单次 GET：2xx 返回响应体，其余情况返回 null（由上层决定是否换源重试） */
    private static String fetchOnce(String url, boolean mirror) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", AppConfig.USER_AGENT)
                    .timeout(mirror ? MIRROR_TIMEOUT : REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    // 显式 UTF-8：MCIM 镜像的 Content-Type 不带 charset，
                    // ofString() 无参时回退 ISO-8859-1，会把中文文件名的 UTF-8 字节解成控制字符乱码，
                    // 后续 URI.create(url) 直接抛 Illegal character in path（整合包“枪战.mrpack”实测踩中）
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                System.err.println("[ModrinthRepo] HTTP " + response.statusCode()
                        + (mirror ? " (镜像)" : "") + " for " + url);
                return null;
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[ModrinthRepo] Request failed" + (mirror ? " (镜像)" : "")
                    + ": " + url + " -> " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String getJsonString(JsonObject obj, String member) {
        if (obj == null || !obj.has(member)) return null;
        var el = obj.get(member);
        if (el == null || !el.isJsonPrimitive()) return null;
        String val = el.getAsString();
        return val.isEmpty() ? null : val;
    }
}
