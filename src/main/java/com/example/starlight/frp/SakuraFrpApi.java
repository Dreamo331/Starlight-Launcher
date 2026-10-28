/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.frp;


import com.example.starlight.util.I18N;
import com.example.starlight.config.Endpoints;
import com.example.starlight.util.HttpText;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SakuraFrp 开放 API v4 客户端（联机页「FRP 联机」唯一使用的服务商）。
 *
 * <p>接口与字段以官方定义为准：{@code https://api.natfrp.com/v4}
 * （OpenAPI: {@code https://github.com/natfrp/api}）。鉴权统一走 <b>查询参数
 * {@code ?token=}</b>，与插件版实现保持一致；不依赖自定义 Header，避免代理 / 插件通道丢头。
 *
 * <p>本类只做「请求 → 解析为不可变数据对象」，不持有 UI、不持久化令牌，也不启动
 * frpc 进程——进程与配置文件由 {@link SakuraFrpService} 负责。
 */
public final class SakuraFrpApi {

    /** 官方文档目前给出的 SakuraFrp 分发版本，仅作为 /system/clients 不可用时的兜底 */
    public static final String DEFAULT_FRPC_VERSION = "0.52.3-sakura-7.2";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String USER_AGENT = "Starlight-Launcher (SakuraFrp)";

    private final String base;

    public SakuraFrpApi() {
        this(Endpoints.sakuraFrpApiBase());
    }

    /** 测试 / 备用入口：允许指向另一份兼容 API */
    public SakuraFrpApi(String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    // 数据模型 //

    /** 用户信息（冻结账号时仅有 id/name/ban） */
    public record UserInfo(int id, String name, String avatar, int tunnelLimit,
                           long usedToday, long trafficLeft, String speed,
                           String groupName, int groupLevel, long groupExpires) {
    }

    /** 节点 */
    public record Node(int id, String name, String host, String description, int vip, int flag) {

        /** flag 1<<2：节点是否允许创建隧道（满载时为 0） */
        public boolean canCreate() {
            return (flag & (1 << 2)) != 0;
        }

        /** flag 1<<3：内地节点 */
        public boolean mainland() {
            return (flag & (1 << 3)) != 0;
        }

        /**
         * 是否允许穿透「公开游戏服务器」：flag 含 2048，或节点名含「多线」。
         *
         * <p>这是 SakuraFrp 的硬性规则，仅供 UI 提示；是否公开由用户自行判断。
         */
        public boolean allowsPublicGame() {
            // i18n-lock: 不要翻译此行中文——SakuraFrp 接口字段值，翻译即功能失效
            return (flag & 2048) != 0 || (name != null && name.contains("多线"));
        }
    }

    /** 隧道 */
    public record Tunnel(int id, String name, String type, int node, boolean online, int status,
                         String statusReason, String note, String remote,
                         String localIp, int localPort, String export) {

        /** 远程端口：优先取 remote，其次取 export 的第三段（类型|IP|端口） */
        public int remotePort() {
            Integer direct = parseIntOrNull(remote);
            if (direct != null && direct > 0) return direct;
            if (export != null) {
                String[] parts = export.split("\\|");
                if (parts.length >= 3) {
                    Integer exported = parseIntOrNull(parts[2]);
                    if (exported != null && exported > 0) return exported;
                }
            }
            return -1;
        }

        /** 隧道是否被封禁（status 2） */
        public boolean banned() {
            return status == 2;
        }

        /**
         * 面向玩家的连接地址。
         *
         * @param nodeHost 该隧道所在节点的域名（来自 /nodes）
         */
        public String publicAddress(String nodeHost) {
            String host = nodeHost == null ? "" : nodeHost.trim();
            if ("http".equalsIgnoreCase(type)) return "http://" + nullToEmpty(remote);
            if ("https".equalsIgnoreCase(type)) return "https://" + nullToEmpty(remote);
            int port = remotePort();
            if (host.isEmpty()) return port > 0 ? String.valueOf(port) : "";
            return port > 0 ? host + ":" + port : host;
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s.trim();
        }
    }

    /** /system/clients 中某个平台架构的 frpc 下载项 */
    public record ClientFile(String version, String arch, String url, long size, String hash) {
    }

    // 账户 //

    /** 用访问密钥换取用户信息；密钥无效 / 账号冻结时抛 {@link ApiException} */
    public UserInfo userInfo(String token) throws IOException, InterruptedException {
        JsonObject root = getJson("/user/info", token);
        if (root.has("ban")) {
            JsonObject ban = root.getAsJsonObject("ban");
            String title = optString(ban, "title", I18N.get("frp.t0001"));
            String reason = optString(ban, "reason", "");
            throw new ApiException(200, 0, reason.isBlank() ? title : title + "：" + reason);
        }
        long[] traffic = longPair(root.get("traffic"));
        JsonObject group = root.has("group") && root.get("group").isJsonObject()
                ? root.getAsJsonObject("group") : new JsonObject();
        return new UserInfo(
                intValue(root, "id", 0),
                optString(root, "name", ""),
                optString(root, "avatar", ""),
                intValue(root, "tunnels", 0),
                traffic[0],
                traffic[1],
                optString(root, "speed", ""),
                optString(group, "name", ""),
                intValue(group, "level", 0),
                longValue(group, "expires", 0L));
    }

    // 节点 //

    /** 节点列表（key 为节点 ID） */
    public Map<Integer, Node> nodes(String token) throws IOException, InterruptedException {
        JsonObject root = getJson("/nodes", token);
        Map<Integer, Node> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            Integer id = parseIntOrNull(entry.getKey());
            if (id == null || !entry.getValue().isJsonObject()) continue;
            JsonObject o = entry.getValue().getAsJsonObject();
            result.put(id, new Node(
                    id,
                    optString(o, "name", ""),
                    optString(o, "host", ""),
                    optString(o, "description", ""),
                    intValue(o, "vip", 0),
                    intValue(o, "flag", 0)));
        }
        return result;
    }

    // 隧道 //

    /** 隧道列表 */
    public List<Tunnel> tunnels(String token) throws IOException, InterruptedException {
        JsonArray root = getArray("/tunnels", token);
        List<Tunnel> result = new ArrayList<>();
        for (JsonElement el : root) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            result.add(new Tunnel(
                    intValue(o, "id", 0),
                    optString(o, "name", ""),
                    optString(o, "type", "tcp"),
                    intValue(o, "node", 0),
                    boolValue(o, "online", false),
                    intValue(o, "status", 0),
                    optString(o, "status_reason", ""),
                    optString(o, "note", ""),
                    optString(o, "remote", ""),
                    optString(o, "local_ip", "127.0.0.1"),
                    intValue(o, "local_port", 0),
                    optString(o, "export", "")));
        }
        return result;
    }

    /**
     * 创建隧道。
     *
     * @return 创建成功的隧道 ID
     */
    public int createTunnel(String token, String name, String type, int nodeId,
                            String localIp, int localPort, String remote, String note)
            throws IOException, InterruptedException {
        StringBuilder form = new StringBuilder();
        appendForm(form, "name", name);
        appendForm(form, "type", type);
        appendForm(form, "node", String.valueOf(nodeId));
        appendForm(form, "local_ip", localIp);
        appendForm(form, "local_port", String.valueOf(localPort));
        if (remote != null && !remote.isBlank()) appendForm(form, "remote", remote.trim());
        if (note != null && !note.isBlank()) appendForm(form, "note", note.trim());

        JsonObject root = postJson("/tunnels", token, form.toString());
        int id = intValue(root, "id", 0);
        if (id <= 0) {
            throw new ApiException(200, 0, I18N.get("frp.t0002"));
        }
        return id;
    }

    /** 编辑隧道（本地 IP / 端口 / 备注） */
    public void editTunnel(String token, int id, String note, String localIp, int localPort)
            throws IOException, InterruptedException {
        StringBuilder form = new StringBuilder();
        appendForm(form, "id", String.valueOf(id));
        appendForm(form, "note", note == null ? "" : note);
        appendForm(form, "local_ip", localIp);
        appendForm(form, "local_port", String.valueOf(localPort));
        postJson("/tunnel/edit", token, form.toString());
    }

    /** 删除隧道 */
    public void deleteTunnel(String token, int id) throws IOException, InterruptedException {
        postJson("/tunnel/delete", token, "ids=" + id);
    }

    // 配置与客户端 //

    /**
     * 获取可供 frpc 直接加载的 ini 配置文本。
     *
     * @param tunnelIds      要启动的隧道 ID（可多条）
     * @param frpcVersion    目标 frpc 版本（应与本机实际运行的 frpc 一致）
     */
    public String tunnelConfig(String token, List<Integer> tunnelIds, String frpcVersion)
            throws IOException, InterruptedException {
        String query = tunnelIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        StringBuilder form = new StringBuilder();
        appendForm(form, "query", query);
        appendForm(form, "frpc", frpcVersion == null || frpcVersion.isBlank()
                ? DEFAULT_FRPC_VERSION : frpcVersion);
        String body = postText("/tunnel/config", token, form.toString(), "text/plain");
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException(200, 0, I18N.get("frp.t0003"));
        }
        // 个别错误会以 HTTP 200 + JSON 错误体返回，避免把 JSON 当 ini 写盘
        if (trimmed.startsWith("{") && !trimmed.contains("[common]")) {
            try {
                JsonObject err = JsonParser.parseString(trimmed).getAsJsonObject();
                if (err.has("msg") || err.has("code")) {
                    throw new ApiException(200, intValue(err, "code", 0),
                            optString(err, "msg", I18N.get("frp.t0004")));
                }
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException ignored) {
                // 不是错误 JSON，继续按配置文本处理
            }
        }
        return trimmed;
    }

    /** 获取最新的 Windows frpc 下载项（/system/clients 为公开接口，无需 token） */
    public ClientFile latestFrpc() throws IOException, InterruptedException {
        JsonObject root = getJson("/system/clients", null);
        JsonObject frpc = root.has("frpc") && root.get("frpc").isJsonObject()
                ? root.getAsJsonObject("frpc") : null;
        if (frpc == null) {
            throw new ApiException(200, 0, I18N.get("frp.t0005"));
        }
        String version = optString(frpc, "ver", DEFAULT_FRPC_VERSION);
        JsonObject archs = frpc.has("archs") && frpc.get("archs").isJsonObject()
                ? frpc.getAsJsonObject("archs") : new JsonObject();
        String archKey = pickArch(archs);
        if (archKey == null) {
            throw new ApiException(200, 0, I18N.get("frp.t0006"));
        }
        JsonObject arch = archs.getAsJsonObject(archKey);
        String url = optString(arch, "url", "");
        if (url.isBlank()) {
            throw new ApiException(200, 0, I18N.get("frp.t0007"));
        }
        return new ClientFile(version, archKey, url,
                longValue(arch, "size", 0L), optString(arch, "hash", ""));
    }

    /** 按运行平台选择 frpc 架构键（Windows / Linux / macOS） */
    private static String pickArch(JsonObject archs) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String prefix = os.contains("win") ? "windows_"
                : os.contains("mac") ? "darwin_" : "linux_";
        String suffix = arch.contains("aarch64") || arch.contains("arm64") ? "arm64"
                : arch.contains("x86_64") || arch.contains("amd64") || arch.contains("64") ? "amd64"
                : arch.contains("86") || arch.contains("x86") ? "386" : "amd64";
        String[] candidates = {
                prefix + suffix,
                prefix + "amd64",
                prefix + "x86_64",
                prefix + "arm64",
                prefix + "386"
        };
        for (String key : candidates) {
            if (archs.has(key)) return key;
        }
        // 兜底：任意含 windows/linux/darwin 且含 amd64 的键
        for (Map.Entry<String, JsonElement> entry : archs.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(prefix) && key.toLowerCase(Locale.ROOT).contains("amd64")) {
                return key;
            }
        }
        return null;
    }

    // HTTP 核心 //

    private JsonObject getJson(String path, String token) throws IOException, InterruptedException {
        String text = send("GET", path, token, null, "application/json");
        JsonElement el = JsonParser.parseString(text);
        if (!el.isJsonObject()) throw new ApiException(200, 0, I18N.get("frp.t0008"));
        return el.getAsJsonObject();
    }

    private JsonArray getArray(String path, String token) throws IOException, InterruptedException {
        String text = send("GET", path, token, null, "application/json");
        JsonElement el = JsonParser.parseString(text);
        if (!el.isJsonArray()) throw new ApiException(200, 0, I18N.get("frp.t0009"));
        return el.getAsJsonArray();
    }

    private JsonObject postJson(String path, String token, String form)
            throws IOException, InterruptedException {
        String text = send("POST", path, token, form, "application/json");
        if (text == null || text.isBlank()) return new JsonObject();
        JsonElement el = JsonParser.parseString(text);
        if (!el.isJsonObject()) throw new ApiException(200, 0, I18N.get("frp.t0010"));
        return el.getAsJsonObject();
    }

    private String postText(String path, String token, String form, String accept)
            throws IOException, InterruptedException {
        return send("POST", path, token, form, accept);
    }

    /**
     * 唯一 HTTP 出口：鉴权 append 到 query，正文统一 UTF-8 表单。
     *
     * <p>错误响应优先解析 {@code {code,msg}}，解析失败时保留 HTTP 状态码。
     */
    private String send(String method, String path, String token, String form, String accept)
            throws IOException, InterruptedException {
        String url = base + path;
        if (token != null && !token.isBlank()) {
            url += "?token=" + encode(token.trim());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept == null ? "application/json" : accept);
        HttpText.acceptGzip(builder);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            builder.POST(HttpRequest.BodyPublishers.ofString(form == null ? "" : form, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            // 异常文案里可能带完整 URL（含 token），先脱敏再抛给 UI / 日志
            throw new IOException(I18N.get("frp.p0031", sanitize(e.getMessage(), token)), e);
        }
        String text = HttpText.decode(response);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw errorFrom(status, text);
        }
        return text;
    }

    /** 从错误文案中抹掉访问密钥，避免异常信息把 token 带进日志 */
    private static String sanitize(String message, String token) {
        if (message == null || message.isBlank()) return I18N.get("frp.t0011");
        if (token == null || token.isBlank()) return message;
        return message.replace(token, "***");
    }

    /** 把服务端错误体解析成人类可读异常 */
    private static ApiException errorFrom(int status, String text) {
        int code = 0;
        String msg = null;
        if (text != null && !text.isBlank() && text.trim().startsWith("{")) {
            try {
                JsonObject err = JsonParser.parseString(text).getAsJsonObject();
                code = intValue(err, "code", 0);
                msg = optString(err, "msg", "");
            } catch (RuntimeException ignored) {
                // 非 JSON 错误体（网关 HTML 等），走下面的状态码文案
            }
        }
        if (msg == null || msg.isBlank()) {
            msg = switch (status) {
                case 401, 403 -> I18N.get("frp.t0012");
                case 404 -> I18N.get("frp.t0013");
                case 429 -> I18N.get("frp.t0014");
                default -> I18N.get("frp.p0032", status + "）");
            };
        }
        return new ApiException(status, code, msg);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void appendForm(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append('&');
        sb.append(encode(key)).append('=').append(encode(value == null ? "" : value));
    }

    // JSON 小工具 //

    private static String optString(JsonObject o, String key, String fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try {
            String s = o.get(key).getAsString();
            return s == null ? fallback : s;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int intValue(JsonObject o, String key, int fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            Integer parsed = parseIntOrNull(optString(o, key, null));
            return parsed == null ? fallback : parsed;
        }
    }

    private static long longValue(JsonObject o, String key, long fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try {
            return o.get(key).getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean boolValue(JsonObject o, String key, boolean fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try {
            return o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return "1".equals(optString(o, key, ""));
        }
    }

    /** traffic: [本日消耗流量, 总剩余流量] */
    private static long[] longPair(JsonElement el) {
        long[] result = {0L, 0L};
        if (el == null || !el.isJsonArray()) return result;
        JsonArray arr = el.getAsJsonArray();
        for (int i = 0; i < Math.min(2, arr.size()); i++) {
            try {
                result[i] = arr.get(i).getAsLong();
            } catch (RuntimeException ignored) {
                result[i] = 0L;
            }
        }
        return result;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** SakuraFrp API 业务异常：带 HTTP 状态码与官方错误码 */
    public static final class ApiException extends IOException {
        private final int httpStatus;
        private final int code;

        public ApiException(int httpStatus, int code, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.code = code;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public int code() {
            return code;
        }
    }
}
