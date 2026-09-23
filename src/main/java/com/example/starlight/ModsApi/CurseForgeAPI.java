package com.example.starlight.ModsApi;

import com.example.starlight.config.Endpoints;
import com.example.starlight.newui.AppConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * CurseForge 官方 API 客户端（低层封装）。
 *
 * <p>本类是 CurseForge 接入的<b>唯一凭证持有处</b>：API Key 从 {@code /assets/endpoints.json}
 * （{@link com.example.starlight.config.Endpoints}）读取，
 * 上层（{@link CurseForgeRemoteModRepository}）只调用方法，不接触密钥。
 *
 * <h3>认证方式</h3>
 * <ul>
 *   <li><b>传统 API Key</b>（{@code $2a$10$...}，CurseForge 控制台 "API Keys" 页生成）：
 *       通过 {@code x-api-key} 请求头发送，<b>当前使用的就是这一把</b>；</li>
 *   <li><b>个人访问令牌 PAT</b>（{@code cfc_pat_...}）：通过 {@code Authorization: Bearer} 发送，
 *       本类会根据前缀自动识别。注意实测该令牌对 api.curseforge.com 返回
 *       {@code 403 Forbidden: API Key missing or invalid}（未通过审核/未生效），
 *       因此默认不启用。</li>
 * </ul>
 *
 * <h3>镜像优先</h3>
 * 官方域名 {@code api.curseforge.com} 在国内通常不可直连，因此请求顺序为：
 * mcimirror 反代 {@code https://mod.mcimirror.top/curseforge} 优先（与 Modrinth 的镜像策略一致），
 * 失败后回退官方域名（该反代自带鉴权，无 Key 亦可访问）。
 * 镜像带熔断：连续失败达到阈值后冷却期内直接走官方，避免每个请求都先付一次镜像超时。
 * 切换对调用方透明。
 *
 * <p>所有方法均为阻塞式，调用方需在后台线程（如 {@code UIGeneralControlClass.ASYNC_POOL}）中执行。
 */
public final class CurseForgeAPI {

    // ================================================================
    //  API Key（本类为凭证唯一持有处）
    // ================================================================

    /**
     * CurseForge 传统 API Key（{@code x-api-key} 认证）。
     * <p>换取方式：https://console.curseforge.com/ → API Keys。
     */
    public static final String API_KEY = Endpoints.curseforgeApiKey();

    /** 个人访问令牌（PAT）前缀；带此前缀时改用 {@code Authorization: Bearer} */
    private static final String PAT_PREFIX = "cfc_pat_";

    /** 凭证是否已配置 */
    public static boolean hasApiKey() {
        return API_KEY != null && !API_KEY.isBlank();
    }

    // ================================================================
    //  接口常量
    // ================================================================

    /** Minecraft 在 CurseForge 中的固定 gameId */
    public static final int GAME_ID_MINECRAFT = 432;

    /** 分类 ID（{@code classId}，取自 /v1/categories?gameId=432 中 isClass=true 的项） */
    public static final int CLASS_MODS = 6;
    public static final int CLASS_MODPACKS = 4471;
    public static final int CLASS_RESOURCE_PACKS = 12;
    public static final int CLASS_SHADERS = 6552;
    public static final int CLASS_WORLDS = 17;
    public static final int CLASS_DATA_PACKS = 6945;
    public static final int CLASS_CUSTOMIZATION = 4546;

    /** 模组加载器类型（{@code modLoaderType} 查询参数） */
    public static final int LOADER_ANY = 0;
    public static final int LOADER_FORGE = 1;
    public static final int LOADER_CAULDRON = 2;
    public static final int LOADER_LITE_LOADER = 3;
    public static final int LOADER_FABRIC = 4;
    public static final int LOADER_QUILT = 5;
    public static final int LOADER_NEOFORGE = 6;

    /** 排序字段（{@code sortField} 查询参数） */
    public static final int SORT_FEATURED = 1;
    public static final int SORT_POPULARITY = 2;
    public static final int SORT_LAST_UPDATED = 3;
    public static final int SORT_NAME = 4;
    public static final int SORT_AUTHOR = 5;
    public static final int SORT_TOTAL_DOWNLOADS = 6;
    public static final int SORT_RELEASED_DATE = 11;

    /** 文件发布类型（{@code releaseType}） */
    public static final int RELEASE_TYPE_RELEASE = 1;
    public static final int RELEASE_TYPE_BETA = 2;
    public static final int RELEASE_TYPE_ALPHA = 3;

    /** 哈希算法（{@code hashes[].algo}） */
    public static final int HASH_ALGO_SHA1 = 1;
    public static final int HASH_ALGO_MD5 = 2;

    // ================================================================
    //  地址
    // ================================================================

    /** 官方 API 根地址 */
    private static final String OFFICIAL_BASE = "https://api.curseforge.com";

    /** 国内反代根地址（镜像优先；与 BMCLAPIDownloadProvider 的替换规则保持一致） */
    private static final String MIRROR_BASE = "https://mod.mcimirror.top/curseforge";

    /** 镜像熔断：连续 2 次失败后 60 秒内直接走官方（与 Modrinth 镜像熔断同参数） */
    private static final com.example.starlight.util.MirrorCircuitBreaker MIRROR_BREAKER =
            new com.example.starlight.util.MirrorCircuitBreaker(2, 60_000L);

    /** 编辑器/网页地址前缀（用于拼项目主页链接） */
    private static final String WEBSITE_BASE = "https://www.curseforge.com/minecraft/";

    // ================================================================
    //  HTTP 基础设施
    // ================================================================

    private static final Gson GSON = new GsonBuilder().create();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** 主客户端（HTTP/2） */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 降级客户端（HTTP/1.1，规避部分 CDN/代理的 HTTP/2 兼容问题） */
    private static final HttpClient HTTP_CLIENT_HTTP1 = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ================================================================
    //  业务接口
    // ================================================================

    /**
     * 搜索项目。
     *
     * @param classId       分类 ID，见 {@link #CLASS_MODS} 等
     * @param gameVersion   游戏版本筛选，如 "1.20.1"；为空表示不限
     * @param modLoaderType 加载器筛选，见 {@link #LOADER_FORGE} 等；{@link #LOADER_ANY} 表示不限
     * @param index         起始下标（注意是「第几个结果」而非页码）
     * @param pageSize      每页数量（官方上限 50）
     * @param searchFilter  搜索关键词，可为 null/空
     * @param sortField     排序字段，见 {@link #SORT_POPULARITY} 等
     * @param sortOrder     "asc" 或 "desc"（未知值按 desc 处理）
     * @return 官方响应根对象（含 {@code data} 数组与 {@code pagination}）
     */
    public static JsonObject searchMods(int classId, String gameVersion, int modLoaderType,
                                        int index, int pageSize, String searchFilter,
                                        int sortField, String sortOrder) throws IOException {
        return searchMods(classId, gameVersion, modLoaderType, index, pageSize, searchFilter,
                sortField, sortOrder, -1);
    }

    /**
     * 搜索项目（带分类筛选）。
     *
     * @param categoryId 分类 ID（来自 {@link #getCategories()} 返回项中的 {@code id}）；
     *                   负数表示不限。用于下载页「分类」下拉。
     */
    public static JsonObject searchMods(int classId, String gameVersion, int modLoaderType,
                                        int index, int pageSize, String searchFilter,
                                        int sortField, String sortOrder, int categoryId) throws IOException {
        StringBuilder q = new StringBuilder("/v1/mods/search");
        q.append("?gameId=").append(GAME_ID_MINECRAFT);
        q.append("&classId=").append(classId);
        q.append("&index=").append(Math.max(0, index));
        q.append("&pageSize=").append(Math.max(1, Math.min(50, pageSize)));
        q.append("&sortField=").append(sortField);
        q.append("&sortOrder=").append("asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc");
        if (gameVersion != null && !gameVersion.isBlank()) {
            q.append("&gameVersion=").append(encode(gameVersion.trim()));
        }
        if (modLoaderType != LOADER_ANY) {
            q.append("&modLoaderType=").append(modLoaderType);
        }
        if (categoryId >= 0) {
            q.append("&categoryId=").append(categoryId);
        }
        if (searchFilter != null && !searchFilter.isBlank()) {
            q.append("&searchFilter=").append(encode(searchFilter.trim()));
        }
        return getJson(q.toString());
    }

    /**
     * 按 ID 获取项目详情。
     * <p>注意：CurseForge 的「按 slug 查询」需要走 {@code /v1/mods/search?slug=...}，
     * 传入纯数字 ID 时本方法直接请求 {@code /v1/mods/{id}}。
     */
    public static JsonObject getMod(String modId) throws IOException {
        String id = normalizeModId(modId);
        JsonObject root = getJson("/v1/mods/" + encode(id));
        JsonElement data = root.get("data");
        if (data == null || !data.isJsonObject()) {
            throw new IOException("CurseForge 未返回项目数据: " + modId);
        }
        return data.getAsJsonObject();
    }

    /**
     * 获取项目文件（版本）列表。
     *
     * @param modId         项目 ID
     * @param index         起始下标
     * @param pageSize      每页数量（官方上限 50）
     * @param gameVersion   游戏版本筛选，可空
     * @param modLoaderType 加载器筛选，{@link #LOADER_ANY} 表示不限
     */
    public static JsonObject getModFiles(String modId, int index, int pageSize,
                                         String gameVersion, int modLoaderType) throws IOException {
        StringBuilder q = new StringBuilder("/v1/mods/").append(encode(normalizeModId(modId))).append("/files");
        q.append("?index=").append(Math.max(0, index));
        q.append("&pageSize=").append(Math.max(1, Math.min(50, pageSize)));
        if (gameVersion != null && !gameVersion.isBlank()) {
            q.append("&gameVersion=").append(encode(gameVersion.trim()));
        }
        if (modLoaderType != LOADER_ANY) {
            q.append("&modLoaderType=").append(modLoaderType);
        }
        return getJson(q.toString());
    }

    /** 获取单个文件的元信息 */
    public static JsonObject getModFile(String modId, String fileId) throws IOException {
        JsonObject root = getJson("/v1/mods/" + encode(normalizeModId(modId))
                + "/files/" + encode(fileId));
        JsonElement data = root.get("data");
        if (data == null || !data.isJsonObject()) {
            throw new IOException("CurseForge 未返回文件数据: " + modId + "/" + fileId);
        }
        return data.getAsJsonObject();
    }

    /**
     * 获取文件的直链下载地址。
     * <p>当作者关闭了「允许第三方分发」（{@code allowModDistribution=false}）时，
     * 官方会返回 {@code null}，此时本方法返回 {@code null}，调用方应提示用户前往官网下载。
     */
    public static String getDownloadUrl(String modId, String fileId) throws IOException {
        JsonObject root = getJson("/v1/mods/" + encode(normalizeModId(modId))
                + "/files/" + encode(fileId) + "/download-url");
        JsonElement data = root.get("data");
        if (data == null || data.isJsonNull() || !data.isJsonPrimitive()) {
            return null;
        }
        String url = data.getAsString();
        return (url == null || url.isBlank()) ? null : url;
    }

    /**
     * 批量取文件元信息（含 {@code downloadUrl} / {@code fileName}）。
     *
     * <p>整合包安装动辄要解析上百个 mod，逐个调用 {@code /files/{id}} 既慢又容易触发限流；
     * CurseForge 的 {@code POST /v1/mods/files} 一次最多可查 1000 个 fileId，
     * 这里按 1000 分批。返回的每个对象里带 {@code modId}，可与请求的 fileId 对应。
     */
    public static List<JsonObject> getFilesByIds(List<Integer> fileIds) throws IOException {
        List<JsonObject> out = new ArrayList<>();
        if (fileIds == null || fileIds.isEmpty()) return out;
        for (int i = 0; i < fileIds.size(); i += 1000) {
            List<Integer> chunk = fileIds.subList(i, Math.min(i + 1000, fileIds.size()));
            StringBuilder sb = new StringBuilder("{\"fileIds\":[");
            for (int j = 0; j < chunk.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(chunk.get(j));
            }
            sb.append("]}");
            JsonObject root = postJson("/v1/mods/files", sb.toString());
            JsonElement data = root.get("data");
            if (data != null && data.isJsonArray()) {
                for (JsonElement e : data.getAsJsonArray()) {
                    if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
    }

    /** 获取 Minecraft 的全部分类（含 classId 与子分类） */
    public static JsonArray getCategories() throws IOException {
        JsonObject root = getJson("/v1/categories?gameId=" + GAME_ID_MINECRAFT);
        JsonElement data = root.get("data");
        return (data != null && data.isJsonArray()) ? data.getAsJsonArray() : new JsonArray();
    }

    /** 拼接项目主页地址（用于「打开网页」按钮） */
    public static String buildPageUrl(int classId, String slug) {
        String section = switch (classId) {
            case CLASS_MODS -> "mc-mods";
            case CLASS_MODPACKS -> "modpacks";
            case CLASS_RESOURCE_PACKS -> "texture-packs";
            case CLASS_SHADERS -> "shaders";
            case CLASS_WORLDS -> "worlds";
            case CLASS_DATA_PACKS -> "data-packs";
            case CLASS_CUSTOMIZATION -> "customization";
            default -> "mc-mods";
        };
        return WEBSITE_BASE + section + "/" + (slug == null ? "" : slug);
    }

    // ================================================================
    //  连接自检
    // ================================================================

    /**
     * 连接自检：返回人类可读的结果描述（供设置页「测试连接」按钮展示）。
     * <p>成功形如 {@code "连接成功 · HTTP 200 · 凭证: x-api-key · 通道: 官方"}，
     * 失败返回以 {@code "连接失败"} 开头的描述，不抛出异常。
     */
    public static String testConnection() {
        if (!hasApiKey()) {
            return "未配置 API Key";
        }
        String path = "/v1/mods/search?gameId=" + GAME_ID_MINECRAFT
                + "&classId=" + CLASS_MODS + "&pageSize=1";
        try {
            String body = fetch(path, null).body;
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement data = root.get("data");
            int count = (data != null && data.isJsonArray()) ? data.getAsJsonArray().size() : 0;
            return "连接成功 · 凭证: " + authModeName() + " · 通道: " + lastChannel
                    + " · 返回 " + count + " 条项目";
        } catch (Exception e) {
            return "连接失败: " + describe(e);
        }
    }

    // ================================================================
    //  内部实现
    // ================================================================

    /** 最近一次成功请求使用的通道（"官方" / "镜像"），仅供 {@link #testConnection()} 展示 */
    private static volatile String lastChannel = "-";

    /** 请求结果：正文 + 使用的通道 */
    private record FetchResult(String body, String channel) {}

    /** 带凭证与镜像回退的 GET；全部失败时抛出 {@link IOException} */
    private static JsonObject getJson(String path) throws IOException {
        return JsonParser.parseString(fetch(path, null).body).getAsJsonObject();
    }

    /** 带凭证与镜像回退的 POST（JSON body）；全部失败时抛出 {@link IOException} */
    private static JsonObject postJson(String path, String jsonBody) throws IOException {
        return JsonParser.parseString(fetch(path, jsonBody).body).getAsJsonObject();
    }

    /**
     * 依次尝试：镜像（HTTP/2 → HTTP/1.1）→ 官方域名（HTTP/2 → HTTP/1.1），
     * 第一个成功的即返回；全部失败时抛出携带各次原因的 {@link IOException}。
     * 镜像连续失败熔断后，冷却期内直接跳过镜像走官方。
     *
     * @param postBody 非 null 时用 POST 提交该 JSON body，null 时用 GET
     */
    private static FetchResult fetch(String path, String postBody) throws IOException {
        StringBuilder errors = new StringBuilder();

        boolean mirrorFirst = MIRROR_BREAKER.allowMirror();
        String[] bases = mirrorFirst
                ? new String[]{MIRROR_BASE, OFFICIAL_BASE}
                : new String[]{OFFICIAL_BASE};
        boolean mirrorSuccess = false;
        boolean anyMirrorAttempt = false;
        try {
            for (String base : bases) {
                boolean official = OFFICIAL_BASE.equals(base);
                for (HttpClient client : new HttpClient[]{HTTP_CLIENT, HTTP_CLIENT_HTTP1}) {
                    if (!official) anyMirrorAttempt = true;
                    try {
                        FetchResult result = fetchOnce(client, base + path, official, postBody);
                        if (result != null) {
                            if (!official) mirrorSuccess = true;
                            return result;
                        }
                    } catch (Exception e) {
                        if (errors.length() > 0) errors.append("; ");
                        errors.append(official ? "官方" : "镜像").append(": ").append(describe(e));
                    }
                }
            }
        } finally {
            if (anyMirrorAttempt) {
                if (mirrorSuccess) MIRROR_BREAKER.recordSuccess();
                else MIRROR_BREAKER.recordFailure();
            }
        }
        throw new IOException("CurseForge 请求失败（" + errors + "）");
    }

    /**
     * 单次请求。2xx 返回结果；非 2xx 返回 {@code null}（由上层换通道重试）；
     * 网络异常向上抛出。
     */
    private static FetchResult fetchOnce(HttpClient client, String url, boolean official, String postBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", AppConfig.USER_AGENT);
        applyAuth(builder);

        HttpRequest request;
        if (postBody == null) {
            request = builder.GET().build();
        } else {
            builder.header("Content-Type", "application/json");
            request = builder.POST(HttpRequest.BodyPublishers.ofString(postBody, StandardCharsets.UTF_8)).build();
        }

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            lastChannel = official ? "官方" : "镜像";
            if (AppConfig.DEBUG_MODE) {
                System.err.println("[CurseForge] " + code + " " + url);
            }
            return new FetchResult(response.body(), lastChannel);
        }
        System.err.println("[CurseForge] HTTP " + code + " (" + (official ? "官方" : "镜像") + ") " + url
                + " -> " + truncate(response.body()));
        // 抛出而不是返回 null：上层仍会换通道重试（catch 后继续），
        // 但失败原因（状态码 + 响应片段）会写进最终异常，不再是空括号
        throw new IOException("HTTP " + code + " -> " + truncate(response.body()));
    }

    /** 按凭证形态选择认证头：PAT 用 Bearer，传统 Key 用 x-api-key */
    private static void applyAuth(HttpRequest.Builder builder) {
        if (!hasApiKey()) return;
        if (API_KEY.startsWith(PAT_PREFIX)) {
            builder.header("Authorization", "Bearer " + API_KEY);
        } else {
            builder.header("x-api-key", API_KEY);
        }
    }

    /** 当前认证方式的可读名称 */
    private static String authModeName() {
        return API_KEY.startsWith(PAT_PREFIX) ? "PAT(Bearer)" : "x-api-key";
    }

    /** CurseForge 既接受数字 ID 也接受 slug；此处仅做去空格处理 */
    private static String normalizeModId(String modId) throws IOException {
        if (modId == null || modId.isBlank()) {
            throw new IOException("项目 ID 为空");
        }
        return modId.trim();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "..." : t;
    }

    private static String describe(Exception e) {
        if (e instanceof java.net.http.HttpTimeoutException) return "请求超时";
        if (e instanceof java.net.UnknownHostException) return "域名解析失败";
        if (e instanceof java.net.ConnectException) return "无法连接服务器";
        if (e instanceof javax.net.ssl.SSLException) return "TLS 握手失败";
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }

    private CurseForgeAPI() {
    }
}
