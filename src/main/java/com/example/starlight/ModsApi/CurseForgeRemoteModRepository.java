package com.example.starlight.ModsApi;

import com.example.starlight.download.DownloadProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * CurseForge 远程资源仓库实现。
 *
 * <p>把 {@link CurseForgeAPI} 包装为与 Modrinth 一致的 {@link RemoteModRepository} 接口，
 * 使下载中心可以用同一套卡片 / 版本选择 / 安装流程渲染两个数据源。
 *
 * <h3>已接入的分类</h3>
 * <ul>
 *   <li>{@link Type#MOD} —— 模组（classId 6）</li>
 *   <li>{@link Type#RESOURCE_PACK} —— 资源包（classId 12）</li>
 *   <li>{@link Type#SHADER_PACK} —— 光影包（classId 6552）</li>
 *   <li>{@link Type#WORLD} —— 世界存档（classId 17）</li>
 *   <li>{@link Type#CUSTOMIZATION} —— 数据包（classId 6945）</li>
 * </ul>
 *
 * <h3>整合包（{@link Type#MODPACK}）说明</h3>
 * 数据层已可用（搜索 / 版本 / 文件直链），但 <b>安装流程尚未接入</b>：
 * CurseForge 整合包是 {@code .zip + manifest.json}，与现有 Modrinth {@code .mrpack} 的
 * {@code modrinth.index.json} 流程不同，需要单独实现 manifest 解析与逐文件下载。
 * 因此下载中心的「整合包」面板暂不提供 CurseForge 选项（预留）。
 *
 * <p>注意 CurseForge 文件可能因作者关闭「允许第三方分发」而没有直链，
 * 此时 {@code downloadUrl} 为 null，{@link #parseVersion} 会尝试
 * {@code /files/{fileId}/download-url} 兜底；仍为空则界面上会提示前往官网下载。
 */
public class CurseForgeRemoteModRepository implements RemoteModRepository {

    /** 模组仓库（下载中心「模组」面板） */
    public static final CurseForgeRemoteModRepository MODS =
            new CurseForgeRemoteModRepository(Type.MOD, CurseForgeAPI.CLASS_MODS);

    /** 整合包仓库（数据层可用，安装流程预留） */
    public static final CurseForgeRemoteModRepository MODPACKS =
            new CurseForgeRemoteModRepository(Type.MODPACK, CurseForgeAPI.CLASS_MODPACKS);

    /** 资源包仓库 */
    public static final CurseForgeRemoteModRepository RESOURCE_PACKS =
            new CurseForgeRemoteModRepository(Type.RESOURCE_PACK, CurseForgeAPI.CLASS_RESOURCE_PACKS);

    /** 光影包仓库 */
    public static final CurseForgeRemoteModRepository SHADER_PACKS =
            new CurseForgeRemoteModRepository(Type.SHADER_PACK, CurseForgeAPI.CLASS_SHADERS);

    /** 世界存档仓库 */
    public static final CurseForgeRemoteModRepository WORLDS =
            new CurseForgeRemoteModRepository(Type.WORLD, CurseForgeAPI.CLASS_WORLDS);

    /** 数据包仓库 */
    public static final CurseForgeRemoteModRepository DATAPACKS =
            new CurseForgeRemoteModRepository(Type.CUSTOMIZATION, CurseForgeAPI.CLASS_DATA_PACKS);

    /** 单次版本列表拉取的最大条数（官方 pageSize 上限为 50） */
    private static final int MAX_FILES_PER_FETCH = 50;

    private final Type type;
    private final int classId;

    private CurseForgeRemoteModRepository(Type type, int classId) {
        this.type = type;
        this.classId = classId;
    }

    /** 对应的 CurseForge classId */
    public int getClassId() {
        return classId;
    }

    @Override
    public Type getType() {
        return type;
    }

    // ================================================================
    //  搜索
    // ================================================================

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

    /** 带加载器 / 分类筛选的搜索（对应下载页工具条上的五维筛选） */
    @Override
    public SearchResult search(DownloadProvider dp, ModSearchQuery query) throws IOException {
        if (query == null) query = new ModSearchQuery();
        int size = Math.max(1, Math.min(MAX_FILES_PER_FETCH, query.getPageSize()));
        int index = Math.max(0, query.getPageOffset()) * size;

        JsonObject root = CurseForgeAPI.searchMods(
                classId,
                query.getGameVersion(),
                ModCategories.loaderId(query.getLoader()),
                index,
                size,
                query.getQuery(),
                toSortField(query.getSortType()),
                query.getSortOrder() == SortOrder.ASC ? "asc" : "desc",
                query.getCategoryId());

        JsonArray data = asArray(root.get("data"));
        if (data == null || data.isEmpty()) {
            return new SearchResult(Stream.empty(), 0);
        }

        int totalCount = 0;
        JsonObject pagination = asObject(root.get("pagination"));
        if (pagination != null && pagination.has("totalCount") && pagination.get("totalCount").isJsonPrimitive()) {
            try {
                totalCount = pagination.get("totalCount").getAsInt();
            } catch (Exception ignored) {
            }
        }

        List<RemoteMod> mods = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            if (el != null && el.isJsonObject()) {
                mods.add(parseProject(el.getAsJsonObject()));
            }
        }

        int totalPages = totalCount > 0
                ? (int) Math.ceil(totalCount / (double) size)
                : (mods.isEmpty() ? 0 : query.getPageOffset() + 1);
        return new SearchResult(mods.stream(), totalPages);
    }

    /**
     * 与当前 classId 匹配的分类列表。
     * <p>CurseForge 的 {@code /v1/categories} 返回全游戏分类，需要按 {@code classId} 过滤，
     * 否则「模组」的分类下拉里会混进整合包、资源包等其它类目的分类。
     */
    @Override
    public List<Category> getCategoriesForType() throws IOException {
        List<Category> all = getCategories().collect(java.util.stream.Collectors.toList());
        List<Category> filtered = new ArrayList<>();
        for (Category c : all) {
            if (!(c.getSelf() instanceof JsonObject obj)) continue;
            int cid = getInt(obj, "classId", -1);
            if (cid == classId) filtered.add(c);
        }
        return filtered;
    }

    @Override
    public RemoteModDetail getProjectDetail(DownloadProvider dp, String id) throws IOException {
        JsonObject obj = CurseForgeAPI.getMod(id);

        List<String> gallery = new ArrayList<>();
        JsonArray shots = asArray(obj.get("screenshots"));
        if (shots != null) {
            for (JsonElement s : shots) {
                if (s == null || !s.isJsonObject()) continue;
                String u = getString(s.getAsJsonObject(), "thumbnailUrl");
                if (u == null) u = getString(s.getAsJsonObject(), "url");
                if (u != null && !u.isBlank()) gallery.add(u);
            }
        }

        String licenseName = "";
        String licenseUrl = "";
        JsonObject lic = asObject(obj.get("license"));
        if (lic != null) {
            String n = getString(lic, "name");
            licenseName = n != null ? n : "";
            licenseUrl = getString(lic, "url") != null ? getString(lic, "url") : "";
        }

        List<String> categoryNames = new ArrayList<>();
        JsonArray cats = asArray(obj.get("categories"));
        if (cats != null) {
            for (JsonElement c : cats) {
                if (c != null && c.isJsonObject()) {
                    String n = getString(c.getAsJsonObject(), "name");
                    if (n != null) categoryNames.add(n);
                }
            }
        }

        String author = "";
        JsonArray authors = asArray(obj.get("authors"));
        if (authors != null) {
            List<String> names = new ArrayList<>();
            for (JsonElement a : authors) {
                if (a != null && a.isJsonObject()) {
                    String n = getString(a.getAsJsonObject(), "name");
                    if (n != null) names.add(n);
                }
            }
            author = String.join(", ", names);
        }

        String slug = getString(obj, "slug");
        String logoUrl = "";
        JsonObject logo = asObject(obj.get("logo"));
        if (logo != null) {
            String thumb = getString(logo, "thumbnailUrl");
            String full = getString(logo, "url");
            logoUrl = thumb != null ? thumb : (full != null ? full : "");
        }

        JsonObject links = asObject(obj.get("links"));

        // CurseForge 的 /v1/mods/{id} 并不返回完整 description，只有一句 summary。
        // 这里把它包成最小 HTML 并补一句提示，避免详情页「描述」标签页看起来像是内容缺失。
        String body = getString(obj, "description");
        if (body == null || body.isBlank()) {
            String summary = getString(obj, "summary");
            body = "<p>" + (summary == null ? "" : summary) + "</p>"
                    + "<p class=\"md-empty\">CurseForge 接口不提供完整说明，"
                    + "可点击右上角「打开主页」查看作者撰写的详细介绍。</p>";
        }

        return RemoteModDetail.builder()
                .id(getString(obj, "id") != null ? getString(obj, "id") : id)
                .slug(slug != null ? slug : String.valueOf(id))
                .title(getString(obj, "name"))
                .description(getString(obj, "summary"))
                .bodyFormat(RemoteModDetail.BodyFormat.HTML)
                .body(body)
                .iconUrl(logoUrl)
                .author(author)
                .license(licenseName)
                .licenseUrl(licenseUrl)
                .published(getString(obj, "dateCreated"))
                .updated(getString(obj, "dateModified"))
                .downloads(getInt(obj, "downloadCount", 0))
                .followers(getInt(obj, "thumbsUpCount", 0))
                .gallery(gallery)
                .categories(categoryNames)
                .pageUrl(CurseForgeAPI.buildPageUrl(classId,
                        slug != null && !slug.isBlank() ? slug : String.valueOf(id)))
                .sourceUrl(links != null && getString(links, "sourceUrl") != null ? getString(links, "sourceUrl") : "")
                .issuesUrl(links != null && getString(links, "issuesUrl") != null ? getString(links, "issuesUrl") : "")
                .wikiUrl(links != null && getString(links, "wikiUrl") != null ? getString(links, "wikiUrl") : "")
                .build();
    }

    // ================================================================
    //  项目 / 版本 / 文件
    // ================================================================

    @Override
    public RemoteMod getModById(DownloadProvider dp, String id) throws IOException {
        return parseProject(CurseForgeAPI.getMod(id));
    }

    @Override
    public Stream<RemoteMod.Version> getRemoteVersionsById(DownloadProvider dp, String id) throws IOException {
        JsonObject root = CurseForgeAPI.getModFiles(id, 0, MAX_FILES_PER_FETCH, null, CurseForgeAPI.LOADER_ANY);
        JsonArray data = asArray(root.get("data"));
        if (data == null || data.isEmpty()) {
            return Stream.empty();
        }
        String modId = String.valueOf(id).trim();
        List<RemoteMod.Version> versions = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            if (el != null && el.isJsonObject()) {
                versions.add(parseVersion(el.getAsJsonObject(), modId));
            }
        }
        return versions.stream();
    }

    @Override
    public RemoteMod.File getModFile(String modId, String fileId) throws IOException {
        JsonObject file = CurseForgeAPI.getModFile(modId, fileId);
        return parseFile(file, modId);
    }

    @Override
    public Optional<RemoteMod.Version> getRemoteVersionByLocalFile(LocalModFile localModFile, Path file) {
        return Optional.empty(); // 预留：CurseForge 可通过 fingerprint 精确反查，暂未接入
    }

    // ================================================================
    //  分类
    // ================================================================

    @Override
    public Stream<Category> getCategories() throws IOException {
        JsonArray arr = CurseForgeAPI.getCategories();
        List<Category> categories = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            // 只保留分类类目（isClass=true），子分类挂在各自 classId 下
            if (obj.has("isClass") && obj.get("isClass").isJsonPrimitive()
                    && !obj.get("isClass").getAsBoolean()) {
                continue;
            }
            String name = getString(obj, "name");
            categories.add(new Category(obj, name != null ? name : "", List.of()));
        }
        return categories.stream();
    }

    // ================================================================
    //  解析
    // ================================================================

    /** 把 CurseForge 项目对象转换为统一的 {@link RemoteMod}，slug 使用数字项目 ID */
    private RemoteMod parseProject(JsonObject obj) {
        final String id = getString(obj, "id");
        String slug = getString(obj, "slug");
        String name = getString(obj, "name");
        String summary = getString(obj, "summary");

        String author = "";
        JsonArray authors = asArray(obj.get("authors"));
        if (authors != null && !authors.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonElement a : authors) {
                if (a.isJsonObject()) {
                    String n = getString(a.getAsJsonObject(), "name");
                    if (n != null) names.add(n);
                }
            }
            author = String.join(", ", names);
        }

        List<String> categoryNames = new ArrayList<>();
        JsonArray cats = asArray(obj.get("categories"));
        if (cats != null) {
            for (JsonElement c : cats) {
                if (c.isJsonObject()) {
                    String n = getString(c.getAsJsonObject(), "name");
                    if (n != null) categoryNames.add(n);
                }
            }
        }

        int downloads = 0;
        if (obj.has("downloadCount") && obj.get("downloadCount").isJsonPrimitive()) {
            try {
                downloads = obj.get("downloadCount").getAsInt();
            } catch (Exception ignored) {
            }
        }

        // logo.thumbnailUrl 为 256x256 缩略图，实测多为 .png/.jpeg（JavaFX 可直接解码）
        String iconUrl = "";
        JsonObject logo = asObject(obj.get("logo"));
        if (logo != null) {
            String thumb = getString(logo, "thumbnailUrl");
            String full = getString(logo, "url");
            iconUrl = thumb != null ? thumb : (full != null ? full : "");
        }

        final String pageSlug = (slug != null && !slug.isBlank()) ? slug : id;
        return new RemoteMod(
                id,
                author,
                name != null ? name : "",
                summary != null ? summary : "",
                categoryNames,
                CurseForgeAPI.buildPageUrl(classId, pageSlug),
                iconUrl,
                new RemoteMod.IMod() {
                    @Override
                    public List<RemoteMod> loadDependencies(RemoteModRepository repo, DownloadProvider dp) {
                        // 依赖关系随版本文件返回，见 parseVersion
                        return List.of();
                    }

                    @Override
                    public Stream<RemoteMod.Version> loadVersions(RemoteModRepository repo, DownloadProvider dp)
                            throws IOException {
                        return repo.getRemoteVersionsById(dp, id);
                    }
                },
                downloads);
    }

    /** 把 CurseForge 文件对象转换为统一的 {@link RemoteMod.Version} */
    private RemoteMod.Version parseVersion(JsonObject file, String modId) {
        String fileId = getString(file, "id");
        String displayName = getString(file, "displayName");
        String fileName = getString(file, "fileName");
        String fileDate = getString(file, "fileDate");

        // CurseForge 把加载器名混在 gameVersions 里（如 "1.20.1","Fabric","Client"），需分流
        List<String> gameVersions = new ArrayList<>();
        List<ModLoaderType> loaders = new ArrayList<>();
        JsonArray gv = asArray(file.get("gameVersions"));
        if (gv != null) {
            for (JsonElement e : gv) {
                if (e == null || !e.isJsonPrimitive()) continue;
                String v = e.getAsString();
                if (v == null || v.isBlank()) continue;
                ModLoaderType loader = toLoaderType(v);
                if (loader != ModLoaderType.UNKNOWN) {
                    if (!loaders.contains(loader)) loaders.add(loader);
                } else if (looksLikeGameVersion(v)) {
                    gameVersions.add(v);
                }
                // "Client" / "Server" 等其余标记忽略
            }
        }

        RemoteMod.VersionType versionType = switch (getInt(file, "releaseType", CurseForgeAPI.RELEASE_TYPE_RELEASE)) {
            case CurseForgeAPI.RELEASE_TYPE_ALPHA -> RemoteMod.VersionType.Alpha;
            case CurseForgeAPI.RELEASE_TYPE_BETA -> RemoteMod.VersionType.Beta;
            default -> RemoteMod.VersionType.Release;
        };

        Instant published;
        try {
            published = fileDate != null ? Instant.parse(fileDate) : Instant.now();
        } catch (Exception e) {
            published = Instant.now();
        }

        return new RemoteMod.Version(
                (RemoteMod.IVersion) () -> RemoteMod.Type.CURSEFORGE,
                modId,
                displayName != null ? displayName : (fileName != null ? fileName : String.valueOf(fileId)),
                fileName != null ? fileName : String.valueOf(fileId),
                "",
                published,
                versionType,
                parseFile(file, modId),
                parseDependencies(file, modId),
                gameVersions,
                loaders);
    }

    /**
     * 解析文件信息。
     * <p>{@code hashes[].algo}：1=Sha1，2=Md5。
     * <p>{@code downloadUrl} 为空（作者关闭第三方分发）时，尝试 download-url 接口兜底。
     */
    private RemoteMod.File parseFile(JsonObject file, String modId) {
        Map<String, String> hashes = new HashMap<>();
        JsonArray hashArr = asArray(file.get("hashes"));
        if (hashArr != null) {
            for (JsonElement h : hashArr) {
                if (h == null || !h.isJsonObject()) continue;
                JsonObject ho = h.getAsJsonObject();
                String value = getString(ho, "value");
                int algo = getInt(ho, "algo", -1);
                if (value == null) continue;
                if (algo == CurseForgeAPI.HASH_ALGO_SHA1) hashes.put("sha1", value);
                else if (algo == CurseForgeAPI.HASH_ALGO_MD5) hashes.put("md5", value);
            }
        }

        String url = getString(file, "downloadUrl");
        if (url == null) {
            // 部分响应不带 downloadUrl，尝试专用接口（作者禁止分发时同样返回 null）
            String fileId = getString(file, "id");
            if (fileId != null) {
                try {
                    url = CurseForgeAPI.getDownloadUrl(modId, fileId);
                } catch (Exception e) {
                    url = null;
                }
            }
        }

        return new RemoteMod.File(hashes, url, getString(file, "fileName"));
    }

    /** 依赖关系：relationType 1=Embedded 2=Optional 3=Required 4=Tool 5=Incompatible 6=Include */
    private List<RemoteMod.Dependency> parseDependencies(JsonObject file, String modId) {
        List<RemoteMod.Dependency> deps = new ArrayList<>();
        JsonArray arr = asArray(file.get("dependencies"));
        if (arr == null) return deps;
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject dep = el.getAsJsonObject();
            String depModId = getString(dep, "modId");
            if (depModId == null || depModId.isBlank() || "0".equals(depModId)) continue;
            RemoteMod.DependencyType depType = switch (getInt(dep, "relationType", 2)) {
                case 3 -> RemoteMod.DependencyType.REQUIRED;
                case 1 -> RemoteMod.DependencyType.EMBEDDED;
                case 4 -> RemoteMod.DependencyType.TOOL;
                case 5 -> RemoteMod.DependencyType.INCOMPATIBLE;
                case 6 -> RemoteMod.DependencyType.INCLUDE;
                default -> RemoteMod.DependencyType.OPTIONAL;
            };
            deps.add(RemoteMod.Dependency.of(depType, MODS, depModId));
        }
        return deps;
    }

    // ================================================================
    //  工具
    // ================================================================

    /** 接口排序枚举 → CurseForge sortField */
    private static int toSortField(SortType sortType) {
        if (sortType == null) return CurseForgeAPI.SORT_POPULARITY;
        return switch (sortType) {
            case POPULARITY -> CurseForgeAPI.SORT_POPULARITY;
            case NAME -> CurseForgeAPI.SORT_NAME;
            case DATE_CREATED -> CurseForgeAPI.SORT_RELEASED_DATE;
            case LAST_UPDATED -> CurseForgeAPI.SORT_LAST_UPDATED;
            case AUTHOR -> CurseForgeAPI.SORT_AUTHOR;
            case TOTAL_DOWNLOADS -> CurseForgeAPI.SORT_TOTAL_DOWNLOADS;
        };
    }

    /** 加载器名称 → {@link ModLoaderType}（无法识别返回 UNKNOWN） */
    private static ModLoaderType toLoaderType(String name) {
        return switch (name) {
            case "Forge" -> ModLoaderType.FORGE;
            case "NeoForge" -> ModLoaderType.NEO_FORGED;
            case "Fabric" -> ModLoaderType.FABRIC;
            case "Quilt" -> ModLoaderType.QUILT;
            case "LiteLoader" -> ModLoaderType.LITE_LOADER;
            default -> ModLoaderType.UNKNOWN;
        };
    }

    /** 是否为形如 "1.20.1" / "1.20" 的游戏版本号（区别于 "Client" / "Server" 等标记） */
    private static boolean looksLikeGameVersion(String v) {
        if (v.isEmpty() || !Character.isDigit(v.charAt(0))) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (!Character.isDigit(c) && c != '.' && c != '_' && c != '-'
                    && !Character.isLetter(c)) {
                return false;
            }
        }
        return true;
    }

    private static JsonArray asArray(JsonElement el) {
        return (el != null && el.isJsonArray()) ? el.getAsJsonArray() : null;
    }

    private static JsonObject asObject(JsonElement el) {
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private static String getString(JsonObject obj, String member) {
        if (obj == null || !obj.has(member)) return null;
        JsonElement el = obj.get(member);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        String v = el.getAsString();
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static int getInt(JsonObject obj, String member, int fallback) {
        if (obj == null || !obj.has(member)) return fallback;
        JsonElement el = obj.get(member);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }
}
