package com.example.starlight.community;


import com.example.starlight.util.I18N;
import com.example.starlight.config.AppDirs;
import com.example.starlight.config.Endpoints;
import com.example.starlight.util.DebugLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 星光MC社区 API 客户端
 * <p>
 * 对接星光MC社区 auth.php 接口（地址见 {@code /assets/endpoints.json}），
 * 封装登录、Token 验证、帖子列表/详情、发帖、评论、点赞等接口。
 * 接口约定见社区仓库 {@code docs/API.md} 与 {@code docs/API-v2-变化与接入文档.md}。
 * </p>
 *
 * <p><b>API v2（2026-11-01）：</b>服务端已下线经济系统，共 27 个 action 移除并统一返回
 * {@code 410}「该功能已下线」。涉及本类的变化：</p>
 * <ul>
 *   <li>签到（{@code checkin}）不再存在 —— 本类不再提供签到方法，调用方请勿再请求；</li>
 *   <li>站点级 {@code X-API-Key} 认证已随经济系统一起删除 —— 本类不再发送该头，
 *       认证只走用户个人 Bearer 令牌；</li>
 *   <li>帖子不再有付费内容 —— {@code posts} 表的 {@code secret_content} {@code has_secret}
 *       {@code price} {@code currency} {@code reward} {@code reward_currency} 六列已删，
 *       模型里同步去掉，列表项改用 {@code excerpt} 做摘要预览。</li>
 * </ul>
 *
 * <p><b>认证：</b>登录后携带 {@code Authorization: Bearer <token>}，本类自动附加。
 * 令牌可在社区网页「个人中心 → 开发者令牌」生成。</p>
 *
 * <p>登录 Token 会以轻量混淆形式持久化到 {@code Starlight-Launcher/.community.dat}，
 * 下次启动自动恢复登录态。</p>
 */
public class CommunityApi {

    private static final String BASE_URL = Endpoints.communityApiUrl();

    /** 站点根地址（头像相对路径补全、官网链接用） */
    private static final String SITE_BASE = Endpoints.communitySiteUrl();

    /**
     * 单次请求超时。
     *
     * <p>原来是 30 秒：社区用 PHP 内置服务器跑时是单线程的，一旦被某个慢请求
     * （外部探测 / Modrinth 拉取）占住，后续请求会排队，UI 就干等半分钟没有任何反馈。
     * 缩短到 12 秒，超时即进入错误态并给重试入口，比“转圈半天”更容易判断。</p>
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final HttpClient httpClient;
    private final Gson gson;

    // 登录态（内存 + 本地持久化） //
    private volatile String token;
    private volatile CommunityUser currentUser;

    /** 最近一次响应的 HTTP 状态码：401 用于兜底「未登录」判定（见 failOnAuthError） */
    private volatile int lastStatus;

    /** 头像缓存：uid -> 头像完整 URL（值为 null 表示该用户未设置头像），避免帖子列表重复请求 */
    private final Map<Integer, String> avatarCache = new ConcurrentHashMap<>();

    public CommunityApi() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new GsonBuilder().create();
        loadTokenFromDisk();
    }

    // 对外公开方法 //

    /** 是否已持有 Token（不代表 Token 一定有效，可用 profile() 校验） */
    public boolean hasToken() {
        return token != null && !token.isEmpty();
    }

    /** 当前登录用户（未登录返回 null） */
    public CommunityUser getCurrentUser() {
        return currentUser;
    }

    /**
     * 用户登录（获取 token）
     *
     * @param username 玩家名
     * @param password 密码
     * @return 成功时 data 为完整用户信息（含明文 token，仅此一次返回）
     */
    public ApiResult<CommunityUser> login(String username, String password) throws IOException, InterruptedException {
        // 服务端仅解析表单参数（$_POST），不解析 JSON body；action 需随表单提交
        String body = "action=launcher_login"
                + "&username=" + encode(username)
                + "&password=" + encode(password);
        String json = sendPostForm(body);
        JsonObject root = parseRoot(json);
        if (!isOk(root)) {
            return ApiResult.failure(root);
        }
        CommunityUser user = gson.fromJson(root.get("user"), CommunityUser.class);
        if (user == null || user.token == null || user.token.isEmpty()) {
            return ApiResult.success(user, root);
        }
        this.token = user.token;
        // 回验 token：服务端 launcher_login 曾出现返回数据库哈希（sha256:...）而非
        // 有效令牌的情况，若不校验会陷入“登录成功但发帖/签到全失败”的假登录态。
        ApiResult<CommunityUser> verify = null;
        try {
            verify = profile();
        } catch (Exception ignored) {
            // 网络异常时保留本次登录态，后续接口会再次验证
        }
        if (verify != null && !verify.ok && ("it".equals(verify.code) || "nl".equals(verify.code))) {
            logout();
            return ApiResult.failure(verify.code, I18N.get("community.t0001"));
        }
        // profile() 成功时已刷新 currentUser，网络异常时保留登录接口返回的用户信息
        if (verify == null || !verify.ok) {
            this.currentUser = user;
        }
        saveTokenToDisk();
        return ApiResult.success(this.currentUser, root);
    }

    /**
     * 令牌登录（直接使用已有令牌，无需用户名密码）
     * <p>与密码登录一致：先调 launcher_profile 回验令牌，有效才持久化，
     * 避免"假登录态"导致后续发帖/签到全部失败。</p>
     *
     * @param token 社区登录令牌（自动去除 Bearer 前缀与首尾空白）
     * @return 成功时 data 为用户信息；令牌无效时返回 it/nl 错误并保持未登录
     */
    public ApiResult<CommunityUser> loginWithToken(String token) throws IOException, InterruptedException {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.startsWith("Bearer ")) {
            trimmed = trimmed.substring(7).trim();
        }
        if (trimmed.isEmpty()) {
            return ApiResult.failure("it", I18N.get("community.t0002"));
        }
        this.token = trimmed;
        try {
            ApiResult<CommunityUser> result = profile();
            if (result.ok) {
                saveTokenToDisk();
                return result;
            }
            // profile() 对 it/nl 已清理登录态；其余错误码（如服务端异常）也需清理，
            // 避免把未经回验的令牌当作登录态持久化
            logout();
            return result;
        } catch (Exception e) {
            // 网络异常：不持久化，恢复未登录态，下次可重新尝试
            this.token = null;
            this.currentUser = null;
            throw e;
        }
    }

    /**
     * 验证 Token（启动器自动登录用）
     * 成功后刷新本地缓存的用户信息
     */
    public ApiResult<CommunityUser> profile() throws IOException, InterruptedException {
        if (!hasToken()) {
            return ApiResult.failure("nl", I18N.get("community.t0003"));
        }
        String json = sendGet(appendParam(BASE_URL, "action", "launcher_profile"));
        JsonObject root = parseRoot(json);
        if (isOk(root)) {
            CommunityUser user = gson.fromJson(root.get("user"), CommunityUser.class);
            if (user != null) {
                // 接口返回的 token 字段为占位文案，保留本地已有 token
                user.token = this.token;
                this.currentUser = user;
            }
            return ApiResult.success(user, root);
        }
        // Token 失效时清空本地登录态（nl=未登录，it=令牌无效，服务端可能返回其中任意一种）
        String code = root.has("code") && !root.get("code").isJsonNull()
                ? root.get("code").getAsString() : "";
        if ("nl".equals(code) || "it".equals(code)) {
            logout();
        }
        return ApiResult.failure(root);
    }

    /**
     * 帖子列表
     *
     * @param page   页码（从 1 开始），传 0 表示不传
     * @param q      关键词搜索，null 表示不传
     * @param random 是否随机排序
     * @return 帖子列表（不含正文，正文需调 postView）
     */
    public ApiResult<List<CommunityPost>> postList(int page, String q, boolean random)
            throws IOException, InterruptedException {
        String url = BASE_URL + "?action=post_list";
        if (page > 0) url += "&page=" + page;
        if (q != null && !q.trim().isEmpty()) url += "&q=" + encode(q.trim());
        if (random) url += "&random=1";

        String json = sendGet(url);
        JsonObject root = parseRoot(json);
        if (!isOk(root)) {
            return ApiResult.failure(root);
        }
        List<CommunityPost> posts = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("posts");
        if (arr != null) {
            for (JsonElement el : arr) {
                posts.add(gson.fromJson(el, CommunityPost.class));
            }
        }
        return ApiResult.success(posts, root);
    }

    /** 帖子详情（含完整正文） */
    public ApiResult<CommunityPost> postView(int id) throws IOException, InterruptedException {
        String url = appendParam(BASE_URL, "action", "post_view");
        url = appendParam(url, "id", String.valueOf(id));

        String json = sendGet(url);
        JsonObject root = parseRoot(json);
        if (!isOk(root)) {
            return ApiResult.failure(root);
        }
        return ApiResult.success(gson.fromJson(root.get("post"), CommunityPost.class), root);
    }

    /**
     * 发帖（需登录）
     *
     * @return 成功时 data 为新帖子的 id
     */
    public ApiResult<Integer> postPublish(String title, String content, String category)
            throws IOException, InterruptedException {
        String body = "action=post_publish"
                + "&title=" + encode(title)
                + "&content=" + encode(content)
                + "&category=" + encode(category == null ? "" : category);
        String json = sendPostForm(body);
        JsonObject root = parseRoot(json);
        if (!isOk(root)) {
            return failOnAuthError(root);
        }
        int id = root.has("id") ? root.get("id").getAsInt() : 0;
        return ApiResult.success(id, root);
    }

    /** 评论列表 */
    public ApiResult<List<CommunityComment>> commentList(int postId) throws IOException, InterruptedException {
        String url = appendParam(BASE_URL, "action", "comment_list");
        url = appendParam(url, "id", String.valueOf(postId));

        String json = sendGet(url);
        JsonObject root = parseRoot(json);
        if (!isOk(root)) {
            return ApiResult.failure(root);
        }
        List<CommunityComment> comments = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("comments");
        if (arr != null) {
            for (JsonElement el : arr) {
                comments.add(gson.fromJson(el, CommunityComment.class));
            }
        }
        return ApiResult.success(comments, root);
    }

    /** 发表评论（需登录） */
    public ApiResult<Void> commentAdd(int postId, String content) throws IOException, InterruptedException {
        String body = "action=comment_add"
                + "&post_id=" + postId
                + "&content=" + encode(content);
        String json = sendPostForm(body);
        JsonObject root = parseRoot(json);
        return isOk(root) ? ApiResult.success(null, root) : failOnAuthError(root);
    }

    /** 点赞（需登录），成功时 data 为最新点赞数 */
    public ApiResult<Integer> postLike(int id) throws IOException, InterruptedException {
        String body = "action=post_like&id=" + id;
        String json = sendPostForm(body);
        JsonObject root = parseRoot(json);
        if (!isOk(root)) {
            return failOnAuthError(root);
        }
        return ApiResult.success(root.has("likes") ? root.get("likes").getAsInt() : -1, root);
    }

    /** 取消点赞（需登录），成功时 data 为最新点赞数 */
    public ApiResult<Integer> postUnlike(int id) throws IOException, InterruptedException {
        String body = "action=post_unlike&id=" + id;
        String json = sendPostForm(body);
        JsonObject root = parseRoot(json);
        if (!isOk(root)) {
            return failOnAuthError(root);
        }
        return ApiResult.success(root.has("likes") ? root.get("likes").getAsInt() : -1, root);
    }

    /** 查询当前用户是否已点赞（需登录） */
    public ApiResult<Boolean> isPostLiked(int id) throws IOException, InterruptedException {
        if (!hasToken()) {
            return ApiResult.failure("nl", I18N.get("community.t0004"));
        }
        String url = appendParam(BASE_URL, "action", "post_liked");
        url = appendParam(url, "id", String.valueOf(id));

        String json = sendGet(url);
        JsonObject root = parseRoot(json);
        if (!isOk(root)) {
            return ApiResult.failure(root);
        }
        boolean liked = root.has("liked") && root.get("liked").getAsBoolean();
        return ApiResult.success(liked, root);
    }

    /**
     * 获取用户头像（公开接口，无需登录）
     * <p>成功时 data 为头像完整 URL（未设置头像时返回 ok=false，msg 为 "未设置头像"）。
     * 结果按 uid 缓存，同一用户只请求一次。</p>
     *
     * @param uid 用户 ID
     */
    public ApiResult<String> avatarGet(int uid) throws IOException, InterruptedException {
        if (avatarCache.containsKey(uid)) {
            String cached = avatarCache.get(uid);
            return cached == null || cached.isEmpty()
                    ? ApiResult.failure("na", I18N.get("community.t0005"))
                    : ApiResult.success(cached, null);
        }
        String url = appendParam(BASE_URL, "action", "avatar_get");
        url = appendParam(url, "uid", String.valueOf(uid));
        String json = sendGet(url);
        JsonObject root = parseRoot(json);
        if (!isOk(root)) {
            return ApiResult.failure(root);
        }
        String avatar = root.has("avatar") && !root.get("avatar").isJsonNull()
                ? root.get("avatar").getAsString() : null;
        // 服务端返回形态有两种：相对路径（uploads/avatars/6.png）与写死域名的绝对地址
        // （https://slmc.cc.cd/uploads/avatars/6.png）。后者必须按 endpoints.json 里的站点
        // 地址改写，否则本地调试（127.0.0.1:8123）会去请求线上域名，头像必然加载失败
        avatar = resolveAvatarUrl(avatar);
        // 未设置头像时缓存空串（ConcurrentHashMap 不允许 null 值）
        avatarCache.put(uid, avatar == null ? "" : avatar);
        return avatar == null
                ? ApiResult.failure("na", I18N.get("community.t0006"))
                : ApiResult.success(avatar, root);
    }

    /**
     * 头像上传目录（服务端 {@code uploads/} 下的子目录，与 avatar_get 返回的路径一致）。
     * 帖子 / 评论 / 用户对象里只带头像文件名（如 {@code 23.jpg}），拼出可加载地址需要这个前缀。
     * 服务端若改了目录结构，这里的快拼会 404 —— 图片加载失败会自动退回 emoji 占位，
     * 也可以改走 {@link #avatarGet(int)} 取服务端给的完整地址。
     */
    private static final String AVATAR_DIR = "uploads/avatars/";

    /**
     * 把帖子 / 评论里带的头像文件名拼成可直接加载的完整 URL；为空或没有头像时返回 null。
     *
     * <p>只给文件名时补 {@link #AVATAR_DIR}；已经是相对路径或绝对地址时按
     * {@link #resolveAvatarUrl(String)} 处理。</p>
     */
    public static String resolveAvatarFile(String file) {
        if (file == null) return null;
        String f = file.trim();
        if (f.isEmpty()) return null;
        if (f.startsWith("http://") || f.startsWith("https://") || f.indexOf('/') >= 0) {
            return resolveAvatarUrl(f);
        }
        return resolveAvatarUrl(AVATAR_DIR + f);
    }

    /**
     * 把头像地址（{@code avatar_get} 的返回值，或帖子/评论里带的头像文件名）
     * 归一化为可直接加载的完整 URL；为空或空白时返回 null。
     *
     * <p>绝对地址只保留其路径部分，域名统一换成 {@code endpoints.json} 里配置的社区站点地址
     * —— 这样站点地址仍然只有一处配置源，本机调试与正式环境都不用改代码。
     * 相对路径（{@code uploads/avatars/6.png}）按同样规则补全。</p>
     */
    public static String resolveAvatarUrl(String avatar) {
        if (avatar == null) return null;
        String path = avatar.trim();
        if (path.isEmpty()) return null;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash < 0 ? "" : path.substring(slash + 1);
        }
        path = path.replaceFirst("^/+", "");
        if (path.isEmpty()) return avatar;
        return SITE_BASE.replaceAll("/+$", "") + "/" + path;
    }

    /** 退出登录：清空内存与本地 Token */
    public void logout() {
        this.token = null;
        this.currentUser = null;
        try {
            Files.deleteIfExists(Paths.get(TOKEN_FILE));
        } catch (IOException ignored) {
            // 删除失败不影响内存态
        }
    }

    // 内部请求方法 //

    /**
     * 失败结果包装：token 失效（未登录 / 令牌无效）时同步清理本地登录态，
     * 避免 UI 停留在“已登录”假象，下次操作仍反复报错。
     *
     * <p>v2 起部分写接口（如 post_publish / post_like）未登录时返回 {@code 401} 且
     * 响应里不带 {@code code} 字段，只有 {@code msg:"未登录"}；这里按「无 code 且 401」
     * 兜底判定，否则取不到 it/nl 就永远无法退出假登录态。</p>
     */
    private <T> ApiResult<T> failOnAuthError(JsonObject root) {
        ApiResult<T> result = ApiResult.failure(root);
        boolean noCode = result.code == null || result.code.isEmpty() || "unknown".equals(result.code);
        if ("it".equals(result.code) || "nl".equals(result.code) || (noCode && lastStatus == 401)) {
            logout();
        }
        return result;
    }

    private String sendGet(String url) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        addAuthHeader(builder);
        DebugLog.http(true, "GET", url, 0, 0, null, -1);
        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        DebugLog.http(false, null, url, resp.statusCode(), resp.body().length(), resp.body(), -1);
        return handleResponse(resp);
    }

    /** POST 表单请求（发帖 / 评论 / 点赞等，需登录） */
    private String sendPostForm(String formBody) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8));
        addAuthHeader(builder);
        DebugLog.http(true, "POST", BASE_URL, 0, formBody.length(), formBody, -1);
        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        DebugLog.http(false, null, BASE_URL, resp.statusCode(), resp.body().length(), resp.body(), -1);
        return handleResponse(resp);
    }


    /**
     * 统一响应处理：200 直接返回 body；
     * 非 200 但 body 为带 ok 字段的 JSON 时视为业务响应（如签到接口 409 = 今天已签到），
     * 否则抛出异常。
     */
    private String handleResponse(HttpResponse<String> resp) throws IOException {
        String body = resp.body();
        // v2 的「未登录」在部分接口上只表现为 401（响应体不带 code），记录下来供 failOnAuthError 兜底
        lastStatus = resp.statusCode();
        if (resp.statusCode() == 200) {
            return body;
        }
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root != null && root.has("ok")) {
                return body;
            }
        } catch (Exception ignored) {
            // 非 JSON 响应，按普通错误处理
        }
        throw new IOException("Community API request failed, status code: " + resp.statusCode());
    }

    /** 自动附加用户 Token（Bearer 认证） */
    private void addAuthHeader(HttpRequest.Builder builder) {
        if (hasToken()) {
            builder.header("Authorization", "Bearer " + token);
        }
    }

    /** 解析响应 JSON，失败时抛出异常 */
    private JsonObject parseRoot(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("Community API returned empty response");
        }
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null) {
            throw new IOException("Community API returned malformed response");
        }
        return root;
    }

    private boolean isOk(JsonObject root) {
        return root.has("ok") && root.get("ok").getAsBoolean();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String appendParam(String url, String key, String value) {
        return url + (url.contains("?") ? "&" : "?") + key + "=" + encode(value);
    }

    // Token 本地持久化 //

    private static final String TOKEN_FILE = AppDirs.communityTokenFile().toString();
    // 独立于 CredentialStore 的轻量混淆密钥（非强加密，仅防明文扫描）
    private static final byte[] XOR_KEY = {(byte) 0x7C, (byte) 0x2A, (byte) 0x5E, (byte) 0x91, (byte) 0x4D, (byte) 0x63, (byte) 0x8F, (byte) 0x1B};

    private void saveTokenToDisk() {
        if (token == null || token.isEmpty()) return;
        try {
            Path dir = AppDirs.root();
            if (!Files.exists(dir)) Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("token=").append(token);
            byte[] obfuscated = xor(sb.toString().getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(obfuscated);
            Files.writeString(Paths.get(TOKEN_FILE), encoded, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // 持久化失败不影响本次会话登录态
        }
    }

    private void loadTokenFromDisk() {
        try {
            Path path = Paths.get(TOKEN_FILE);
            if (!Files.exists(path)) return;
            String encoded = Files.readString(path, StandardCharsets.UTF_8).trim();
            byte[] plainBytes = xor(Base64.getDecoder().decode(encoded));
            String plain = new String(plainBytes, StandardCharsets.UTF_8);
            for (String pair : plain.split("\\|")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if ("token".equals(key) && !value.isEmpty()) {
                    this.token = value;
                }
            }
        } catch (Exception ignored) {
            // 读取失败视为未登录
        }
    }

    private static byte[] xor(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return result;
    }

    // 统一返回包装 //

    /**
     * API 调用结果包装
     *
     * @param <T> 成功时的数据类型
     */
    public static class ApiResult<T> {
        public final boolean ok;
        public final String code;
        public final String msg;
        public final T data;

        private ApiResult(boolean ok, String code, String msg, T data) {
            this.ok = ok;
            this.code = code;
            this.msg = msg;
            this.data = data;
        }

        static <T> ApiResult<T> success(T data, JsonObject root) {
            // root 可能为 null（如 avatarGet 缓存命中分支传入 null），必须判空避免 NPE
            String msg = root != null && root.has("msg") && !root.get("msg").isJsonNull()
                    ? root.get("msg").getAsString() : "";
            return new ApiResult<>(true, "0", msg, data);
        }

        static <T> ApiResult<T> failure(JsonObject root) {
            String code = root != null && root.has("code") && !root.get("code").isJsonNull()
                    ? root.get("code").getAsString() : "unknown";
            String msg = root != null && root.has("msg") && !root.get("msg").isJsonNull()
                    ? root.get("msg").getAsString() : "";
            if (msg.isEmpty()) {
                msg = describeError(code);
            }
            return new ApiResult<>(false, code, msg, null);
        }

        static <T> ApiResult<T> failure(String code, String msg) {
            return new ApiResult<>(false, code, msg, null);
        }

        /** 将服务端错误码映射为可读中文描述 */
        public static String describeError(String code) {
            switch (code == null ? "" : code) {
                case "0":   return I18N.get("community.t0009");
                case "ue":  return I18N.get("community.t0010");
                case "pe":  return I18N.get("community.t0011");
                case "nl":  return I18N.get("community.t0012");
                case "it":  return I18N.get("community.t0013");
                case "np":  return I18N.get("community.t0014");
                case "ec":  return I18N.get("community.t0015");
                case "el":  return I18N.get("community.t0016");
                case "al":  return I18N.get("community.t0017");
                case "nla": return I18N.get("community.t0018");
                default:    return I18N.get("community.p0019", code + "）");
            }
        }
    }

    // 数据模型 //

    /** 社区用户 */
    public static class CommunityUser {
        public int id;
        public String username;
        public String email;
        public String token;
        @SerializedName("created_at")
        public String createdAt;
    }

    /** 帖子（列表项不含正文，正文需 postView） */
    public static class CommunityPost {
        public int id;
        public String title;
        public String category;
        public int views;
        public int likes;
        @SerializedName("created_at")
        public String createdAt;
        public String username;
        @SerializedName("user_id")
        public int userId;
        @SerializedName("tag_name")
        public List<String> tagName;
        /** 列表项摘要（服务端截取正文前若干字）；详情接口不返回，此时为 null */
        public String excerpt;
        /** 作者头像文件名（如 23.jpg），未设置头像时为 null；可直接拼出头像地址，省一次 avatar_get */
        public String avatar;
        /** 仅帖子详情接口返回 */
        public String content;
    }

    /** 评论 */
    public static class CommunityComment {
        public int id;
        public String username;
        public String content;
        @SerializedName("created_at")
        public String createdAt;
        @SerializedName("user_id")
        public int userId;
        /** 评论者头像文件名，未设置头像时为 null */
        public String avatar;
    }
}
