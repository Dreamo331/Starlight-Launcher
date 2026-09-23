package com.example.starlight.pluginapi.server;

import com.example.starlight.pluginapi.API;
import com.example.starlight.pluginapi.ApiException;
import com.example.starlight.pluginapi.ApiRequest;
import com.example.starlight.pluginapi.SemVer;
import com.example.starlight.pluginapi.provider.AccountCurrentApiProvider;
import com.example.starlight.pluginapi.provider.AccountListApiProvider;
import com.example.starlight.pluginapi.provider.AccountLoginOfflineApiProvider;
import com.example.starlight.pluginapi.provider.CrashLatestApiProvider;
import com.example.starlight.pluginapi.provider.FrpStartApiProvider;
import com.example.starlight.pluginapi.provider.FrpStopApiProvider;
import com.example.starlight.pluginapi.provider.GameLaunchApiProvider;
import com.example.starlight.pluginapi.provider.GameLauncherProfilesApiProvider;
import com.example.starlight.pluginapi.provider.GameOptionsApiProvider;
import com.example.starlight.pluginapi.provider.GameOptionsUpdateApiProvider;
import com.example.starlight.pluginapi.provider.GameStopApiProvider;
import com.example.starlight.pluginapi.provider.JavaInstallApiProvider;
import com.example.starlight.pluginapi.provider.JavaListApiProvider;
import com.example.starlight.pluginapi.provider.LanScanApiProvider;
import com.example.starlight.pluginapi.provider.ModsConfigApiProvider;
import com.example.starlight.pluginapi.provider.ModsListApiProvider;
import com.example.starlight.pluginapi.provider.ModsResourcePacksApiProvider;
import com.example.starlight.pluginapi.provider.ModsShaderPacksApiProvider;
import com.example.starlight.pluginapi.provider.ModsToggleApiProvider;
import com.example.starlight.pluginapi.provider.RegionApiProvider;
import com.example.starlight.pluginapi.provider.SavesApiProvider;
import com.example.starlight.pluginapi.provider.SlanHostApiProvider;
import com.example.starlight.pluginapi.provider.SlanJoinApiProvider;
import com.example.starlight.pluginapi.provider.SystemHardwareApiProvider;
import com.example.starlight.pluginapi.provider.SystemPingApiProvider;
import com.example.starlight.pluginapi.provider.VersionInstallApiProvider;
import com.example.starlight.pluginapi.provider.VersionInstalledApiProvider;
import com.example.starlight.pluginapi.provider.VersionLoaderInstallApiProvider;
import com.example.starlight.pluginapi.provider.VersionManifestApiProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 启动器端插件 API 本地 HTTP 服务（纯数据服务端，不含任何 UI 逻辑）。
 *
 * <p>职责与交互流程（需求指定）：
 * <ol>
 *   <li><strong>绑定 0 端口</strong>：{@code HttpServer.create(new InetSocketAddress(host, 0), 0)}，
 *       由操作系统自动分配一个空闲端口；</li>
 *   <li><strong>写端口文件</strong>：服务成功启动后，立即把实际端口号覆盖写入
 *       {@code .minecraft/starlight_api.port}（纯文本，仅含端口号）；</li>
 *   <li><strong>握手端点</strong>{@code POST /api/v1/handshake}：校验插件身份与版本兼容性，
 *       通过后生成 {@code session_token} 并返回可用 API 端点列表；</li>
 *   <li><strong>数据端点</strong>{@code /api/v1/data/{apiId}}：校验 {@code session_token} 后
 *       调用中心调度类 {@link API#dispatch} 返回原始数据字节流。</li>
 * </ol>
 *
 * <p>交互全部由<strong>插件主动发起请求</strong>驱动，启动器从不主动推送。
 *
 * <p>安全说明：默认仅绑定回环地址 {@code 127.0.0.1}（本机插件才能访问，避免局域网内
 * 未授权机器读取世界数据）；如需对局域网开放，把构造方法中的 host 改为
 * {@code new InetSocketAddress(0)}（绑定所有网卡）。
 */
public final class PluginApiServer {

    /** 端口文件名（写入 .minecraft 目录下，纯文本、仅含端口号）。 */
    public static final String PORT_FILE_NAME = "starlight_api.port";

    /** 启动器 API 版本（SemVer）。插件元数据里的 launcher_api_version 与之比对。 */
    public static final String LAUNCHER_API_VERSION = "1.0.0";

    /** 握手端点路径。 */
    public static final String HANDSHAKE_PATH = "/api/v1/handshake";

    /** 数据端点路径前缀，完整形式为 /api/v1/data/{apiId}。 */
    public static final String DATA_PATH_PREFIX = "/api/v1/data/";

    /** 鉴权头名称：插件后续数据请求必须携带该头（值为 session_token）。 */
    public static final String AUTH_HEADER = "X-Session-Token";

    private static final Logger LOGGER = Logger.getLogger(PluginApiServer.class.getName());

    /** Jackson 单例（启动器端 JSON 序列化/反序列化统一入口）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 中心调度类：本服务只做 HTTP 传输层，实际数据查询全部委托给它。 */
    private final API api;

    /** .minecraft 游戏目录，端口文件写入位置。 */
    private final Path minecraftDir;

    /** 会话管理：session_token → 会话。 */
    private final SessionManager sessions = new SessionManager();

    private HttpServer server;
    private ExecutorService executor;

    /** 实际分配到的端口；-1 表示服务尚未启动。 */
    private int port = -1;

    public PluginApiServer(API api, Path minecraftDir) {
        this.api = api;
        this.minecraftDir = minecraftDir;
    }

    /**
     * 启动本地 HTTP 服务。
     * 流程：注册内置功能类 → 绑定 0 端口并启动 → 读取实际端口 → 写入端口文件。
     *
     * @throws IOException 服务创建/启动失败（此时端口文件不会写入）
     */
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("HTTP 服务已启动，请勿重复调用 start()");
        }

        // ---- 第 0 步：注册内置功能类（SystemPing / Region）----
        // API.java 保持纯净（不含业务逻辑），具体功能类的注册统一放在服务启动处。
        // 官方与社区插件通过 HTTP 访问，与这些内置功能类地位平等。
        registerBuiltinProviders();

        // ---- 第 1 步：绑定 0 端口，由操作系统自动分配空闲端口 ----
        // 127.0.0.1 = 仅本机可访问（见类注释安全说明）；改 new InetSocketAddress(0) 即绑定所有网卡。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(HANDSHAKE_PATH, this::handleHandshake);
        server.createContext(DATA_PATH_PREFIX, this::handleData);

        // 显式线程池：HTTP 请求在独立线程处理，互不阻塞
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();

        // ---- 第 2 步：服务启动成功后，立即获取实际端口并写入端口文件 ----
        port = server.getAddress().getPort();
        writePortFile(port);

        // 退出时清理：停服 + 删除端口文件，避免插件读到过期端口
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "starlight-api-shutdown"));

        LOGGER.info("插件 API 服务已启动: http://localhost:" + port
                + "  握手端点: " + HANDSHAKE_PATH + "  端口文件: "
                + minecraftDir.resolve(PORT_FILE_NAME).toAbsolutePath());
    }

    /**
     * 注册内置功能类（共 29 个）。
     * 新增功能时在此追加一行注册即可；官方与社区插件通过 HTTP 访问，与这些内置功能类地位平等。
     */
    private void registerBuiltinProviders() {
        // ---- 系统 ----
        api.registerProvider(new SystemPingApiProvider());          // system/ping
        api.registerProvider(new SystemHardwareApiProvider());      // system/hardware
        // ---- 世界/游戏数据 ----
        api.registerProvider(new RegionApiProvider());              // world/region/chunk
        api.registerProvider(new SavesApiProvider());               // world/saves/list
        api.registerProvider(new GameOptionsApiProvider());         // game/options
        api.registerProvider(new GameLauncherProfilesApiProvider()); // game/launcher_profiles
        // ---- 模组 ----
        api.registerProvider(new ModsListApiProvider());            // mods/list
        api.registerProvider(new ModsResourcePacksApiProvider());   // mods/resourcepacks
        api.registerProvider(new ModsShaderPacksApiProvider());     // mods/shaders
        api.registerProvider(new ModsConfigApiProvider());          // mods/config
        // ---- 崩溃/Java/版本 ----
        api.registerProvider(new CrashLatestApiProvider());         // crash/latest
        api.registerProvider(new JavaListApiProvider());            // java/list
        api.registerProvider(new VersionManifestApiProvider());     // version/manifest
        api.registerProvider(new VersionInstalledApiProvider());    // version/installed
        // ---- 账号（脱敏）----
        api.registerProvider(new AccountListApiProvider());         // account/list
        api.registerProvider(new AccountCurrentApiProvider());      // account/current
        api.registerProvider(new AccountLoginOfflineApiProvider()); // account/login/offline
        // ---- 游戏/模组动作 ----
        api.registerProvider(new GameLaunchApiProvider());          // game/launch
        api.registerProvider(new GameStopApiProvider());            // game/stop
        api.registerProvider(new GameOptionsUpdateApiProvider());   // game/options/update
        api.registerProvider(new ModsToggleApiProvider());          // mods/toggle
        api.registerProvider(new VersionInstallApiProvider());      // version/install
        api.registerProvider(new VersionLoaderInstallApiProvider()); // version/loader/install
        api.registerProvider(new JavaInstallApiProvider());         // java/install
        // ---- 网络 ----
        api.registerProvider(new FrpStartApiProvider());            // net/frp/start
        api.registerProvider(new FrpStopApiProvider());             // net/frp/stop
        api.registerProvider(new SlanHostApiProvider());            // net/slan/host
        api.registerProvider(new SlanJoinApiProvider());            // net/slan/join
        api.registerProvider(new LanScanApiProvider());             // net/lan/scan
    }

    /** @return 实际分配到的端口；未启动时返回 -1 */
    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return server != null;
    }

    /** 停止服务并清理端口文件（幂等，可被 shutdown hook 调用）。 */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            LOGGER.info("插件 API 服务已停止");
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        // 清理端口文件，避免下一次启动/插件读取到过期端口
        try {
            Files.deleteIfExists(minecraftDir.resolve(PORT_FILE_NAME));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "删除端口文件失败（不影响本次运行）: " + PORT_FILE_NAME, e);
        }
    }

    // ------------------------------------------------------------------
    // 端口文件
    // ------------------------------------------------------------------

    /**
     * 将实际端口号覆盖写入 {@code .minecraft/starlight_api.port}（纯文本，仅含端口号）。
     * 写入失败（文件被占用/权限不足）时只记 ERROR 日志、服务继续运行
     * （用户已确认此策略：插件将无法发现端口，但启动器核心功能不受影响）。
     */
    private void writePortFile(int port) {
        Path file = minecraftDir.resolve(PORT_FILE_NAME);
        try {
            Files.createDirectories(minecraftDir);
            Files.writeString(file, String.valueOf(port), StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            LOGGER.info("端口文件已写入: " + file.toAbsolutePath() + " -> " + port);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,
                    "写入端口文件失败（HTTP 服务继续运行，插件将无法发现 API 端口）: " + file, e);
        }
    }

    // ------------------------------------------------------------------
    // 端点 1：POST /api/v1/handshake —— 握手
    // ------------------------------------------------------------------

    /**
     * 握手处理流程：
     * 1. 读取请求体 JSON（plugin_id / plugin_name / plugin_version / launcher_api_version / required_apis）；
     * 2. 校验 launcher_api_version：启动器 API 主版本一致、次/修订 &gt;= 插件要求；
     * 3. 校验 required_apis：每个 API 都已注册且版本兼容（SemVer：主版本一致、次/修订 &gt;=）；
     * 4. 通过后生成 session_token 存入 SessionManager，返回可用 API 端点列表。
     */
    private void handleHandshake(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, errorJson(405, "握手端点仅支持 POST"));
            return;
        }

        byte[] body = exchange.getRequestBody().readAllBytes();
        HandshakeRequest req;
        try {
            req = MAPPER.readValue(body, HandshakeRequest.class);
        } catch (Exception e) {
            sendJson(exchange, 400, errorJson(400, "无效的握手请求 JSON: " + e.getMessage()));
            return;
        }

        // ---- 1. 必填字段校验 ----
        if (isBlank(req.pluginId) || isBlank(req.pluginName) || isBlank(req.pluginVersion)) {
            sendJson(exchange, 400, errorJson(400, "plugin_id / plugin_name / plugin_version 不能为空"));
            return;
        }

        // ---- 2. 校验 launcher_api_version（可选字段，缺省跳过）----
        if (!isBlank(req.launcherApiVersion)) {
            try {
                if (!SemVer.parse(LAUNCHER_API_VERSION).isCompatibleWith(SemVer.parse(req.launcherApiVersion))) {
                    sendJson(exchange, 400, errorJson(400,
                            "launcher_api_version 不兼容：插件要求 " + req.launcherApiVersion
                                    + "，启动器提供 " + LAUNCHER_API_VERSION
                                    + "（主版本必须一致且启动器版本 >= 插件要求）"));
                    return;
                }
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, errorJson(400, "launcher_api_version 非法: " + req.launcherApiVersion));
                return;
            }
        }

        // ---- 3. 校验 required_apis：已注册 + 版本兼容 ----
        Map<String, String> available = api.availableApis();
        if (req.requiredApis != null && !req.requiredApis.isEmpty()) {
            for (Map.Entry<String, String> entry : req.requiredApis.entrySet()) {
                String apiId = entry.getKey();
                String requiredVersion = entry.getValue();
                String providedVersion = available.get(apiId);
                if (providedVersion == null) {
                    sendJson(exchange, 400, errorJson(400, "启动器未提供所需 API: " + apiId));
                    return;
                }
                try {
                    if (!SemVer.parse(providedVersion).isCompatibleWith(SemVer.parse(requiredVersion))) {
                        sendJson(exchange, 400, errorJson(400,
                                "API 版本不兼容: " + apiId + " 插件要求 " + requiredVersion
                                        + "，启动器提供 " + providedVersion));
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    sendJson(exchange, 400, errorJson(400, "required_apis 中存在非法版本号: " + apiId + "=" + requiredVersion));
                    return;
                }
            }
        }

        // ---- 4. 校验通过：生成 session_token，返回可用 API 端点列表 ----
        String token = sessions.createSession(req.pluginId, req.pluginName, req.pluginVersion);
        LOGGER.info("握手成功: plugin_id=" + req.pluginId + " plugin_version=" + req.pluginVersion
                + " required_apis=" + req.requiredApis);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("session_token", token);
        resp.put("launcher_api_version", LAUNCHER_API_VERSION);
        resp.put("available_apis", available);
        sendJson(exchange, 200, resp);
    }

    // ------------------------------------------------------------------
    // 端点 2：/api/v1/data/{apiId} —— 数据请求（需鉴权）
    // ------------------------------------------------------------------

    /**
     * 数据请求处理流程：
     * 1. 从路径解析 apiId；
     * 2. 鉴权：从 {@code X-Session-Token} 请求头（或 {@code session_token} query 参数）取 token，
     *    无效则 401；
     * 3. 解析 query 参数 + 请求体字节流，构造 {@link ApiRequest}；
     * 4. 调用 {@link API#dispatch}，成功返回原始数据字节流（octet-stream）；
     *    失败按 {@link ApiException} 携带的状态码返回统一错误 JSON。
     */
    private void handleData(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(DATA_PATH_PREFIX)) {
            sendJson(exchange, 404, errorJson(404, "未找到该端点: " + path));
            return;
        }
        String apiId = URLDecoder.decode(path.substring(DATA_PATH_PREFIX.length()), StandardCharsets.UTF_8);
        if (apiId.isEmpty()) {
            sendJson(exchange, 400, errorJson(400, "缺少 apiId，正确格式: " + DATA_PATH_PREFIX + "{apiId}"));
            return;
        }

        // ---- 鉴权：后续所有数据请求必须携带 session_token ----
        String token = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
        if (token == null) {
            token = parseQuery(exchange.getRequestURI().getRawQuery()).get("session_token");
        }
        if (!sessions.isValid(token)) {
            sendJson(exchange, 401, errorJson(401, "无效或缺失的 session_token（请先握手获取）"));
            return;
        }

        // ---- 组装请求并调度 ----
        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        ApiRequest request = new ApiRequest(apiId, params, requestBody);

        try {
            byte[] result = api.dispatch(request);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, result.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(result);
            }
        } catch (ApiException e) {
            // API.dispatch 统一抛出的带状态码异常（404 未知API / 400 参数错误 / 500 内部错误）
            sendJson(exchange, e.statusCode(), errorJson(e.statusCode(), e.getMessage()));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "数据端点内部错误: apiId=" + apiId, e);
            sendJson(exchange, 500, errorJson(500, "内部错误: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 解析 URL query 字符串为参数 Map（重复键后者覆盖；UTF-8 解码）。 */
    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return map;
    }

    /** 发送 JSON 响应。 */
    private static void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 统一的错误响应体结构：{"status": <code>, "error": "<message>"}。 */
    private static Map<String, Object> errorJson(int statusCode, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", statusCode);
        m.put("error", message);
        return m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 握手请求体 POJO（Jackson 反序列化目标）。
     * 字段与协议一致：
     * <ul>
     *   <li>{@code plugin_id}：插件唯一标识</li>
     *   <li>{@code plugin_name}：插件展示名称</li>
     *   <li>{@code plugin_version}：插件版本（SemVer）</li>
     *   <li>{@code launcher_api_version}（可选）：依赖的启动器 API 版本</li>
     *   <li>{@code required_apis}：需要的 API 列表（apiId → 要求版本）</li>
     * </ul>
     */
    public static final class HandshakeRequest {

        @JsonProperty("plugin_id")
        public String pluginId;

        @JsonProperty("plugin_name")
        public String pluginName;

        @JsonProperty("plugin_version")
        public String pluginVersion;

        @JsonProperty("launcher_api_version")
        public String launcherApiVersion;

        @JsonProperty("required_apis")
        public Map<String, String> requiredApis;
    }
}
