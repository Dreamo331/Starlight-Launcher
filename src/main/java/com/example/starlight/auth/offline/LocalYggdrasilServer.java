/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;


import com.example.starlight.util.I18N;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 本地 Yggdrasil 档案服务器（离线披风通道）。
 *
 * <p>原版 / 纯 OptiFine 客户端只从会话档案（textures 的 cape 字段）读取披风，资源包无法覆盖
 * （客户端内不存在"默认披风"贴图，OptiFine 也没有资源包披风通道）。本类在 127.0.0.1 随机端口
 * 起一个极简 Yggdrasil session 端点，配合 authlib-injector 让游戏把当前离线玩家判定为携带
 * 皮肤/披风的档案：{@code /sessionserver/session/minecraft/profile/<uuid>} 返回带 textures
 * 属性的档案 JSON（皮肤 URL + 披风 URL + slim 模型），客户端按 URL 拉取贴图。</p>
 *
 * <p>仅在离线账号配置了披风时由启动流程启用；无披风时不启动，保持纯 PCL2（UUID + 资源包）机制。
 * 未知 UUID 透传到 Mojang sessionserver，避免多人游戏中他人皮肤丢失。</p>
 */
public final class LocalYggdrasilServer {

    private static final Logger LOG = LoggerFactory.getLogger(LocalYggdrasilServer.class);
    private static final String MOJANG_SESSION = "https://sessionserver.mojang.com/session/minecraft/profile/";

    /** 固定 RSA 密钥对：签名 textures payload，配合元数据 signaturePublickey 让客户端验签通过。
     *  authlib-injector 1.2.8 的 KeyUtils.decodePEMPublicKey 只接受 PEM 头尾（裸 X.509 base64 会抛
     *  "Bad key format"），因此 signaturePublickey 必须是 PEM 格式。 */
    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final String SIGNATURE_PUBLIC_KEY = KEY_PAIR == null
            ? null : "-----BEGIN PUBLIC KEY-----\n"
            + java.util.Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----";

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, new SecureRandom("starlight-skin-2026".getBytes(StandardCharsets.UTF_8)));
            return g.generateKeyPair();
        } catch (Exception e) {
            LOG.warn(I18N.get("offline.t0021"), e.getMessage());
            return null;
        }
    }

    private static String signTextures(String payload) {
        if (KEY_PAIR == null) return null;
        try {
            // authlib 2.3.31（1.17.1 所用）的 Property.isSignatureValid 固定用 SHA1withRSA 验签；
            // 签名算法不匹配会导致客户端验签失败（InsecureTextureException）而不下载贴图。
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign((RSAPrivateKey) KEY_PAIR.getPrivate());
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            LOG.warn(I18N.get("offline.t0022"), e.getMessage());
            return null;
        }
    }

    private final HttpServer server;
    private final String apiBase;
    private final String uuid;        // 无连字符 32 位 hex
    private final String username;
    private final byte[] skinPng;     // 可空（仅披风场景）
    private final byte[] capePng;     // 可空（调用方保证非空才启动）
    private final boolean slim;
    private volatile boolean stopped;

    private LocalYggdrasilServer(HttpServer server, String apiBase, String uuid, String username,
                                 byte[] skinPng, byte[] capePng, boolean slim) {
        this.server = server;
        this.apiBase = apiBase;
        this.uuid = uuid;
        this.username = username;
        this.skinPng = skinPng;
        this.capePng = capePng;
        this.slim = slim;
    }

    /**
     * 启动本地档案服务器。
     *
     * @param loaded   已加载的皮肤/披风；未加载出披风时返回 null（不启动）
     * @param uuid     传给游戏的定制 UUID（无连字符）
     * @param username 玩家名
     * @return 服务器实例，或 null（无披风 / 启动失败）
     */
    public static LocalYggdrasilServer start(Skin.LoadedSkin loaded, String uuid, String username) {
        if (loaded == null) return null;
        byte[] skin = toPng(loaded.getSkin());
        byte[] cape = toPng(loaded.getCape());
        boolean slim = loaded.getModel() == Skin.TextureModel.SLIM;
        return startRaw(skin, cape, uuid, username, slim);
    }

    /**
     * 以原始 PNG 字节启动本地档案服务器（不依赖 JavaFX，便于测试与复用）。
     *
     * @return 服务器实例；{@code capePng} 为 null 时不启动（保持纯 PCL2 机制）
     */
    public static LocalYggdrasilServer startRaw(byte[] skinPng, byte[] capePng,
                                                String uuid, String username, boolean slim) {
        if (capePng == null) {
            LOG.info(I18N.get("offline.t0023"));
            return null;
        }
        try {
            HttpServer http = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "local-yggdrasil");
                t.setDaemon(true);
                return t;
            }));
            LocalYggdrasilServer s = new LocalYggdrasilServer(
                    http, "http://127.0.0.1:" + http.getAddress().getPort() + "/",
                    uuid, username, skinPng, capePng, slim);
            http.createContext("/sessionserver/session/minecraft/profile/", s::handleProfile);
            http.createContext("/session/minecraft/profile/", s::handleProfile);
            http.createContext("/skin.png", s::handleSkin);
            http.createContext("/cape.png", s::handleCape);
            http.createContext("/", s::handleMetadata);
            http.start();
            current = s; // 状态查询：同一时刻至多一个游戏会话在用，后启动覆盖前者
            LOG.info(I18N.get("offline.t0050"),
                    s.apiBase, username, skinPng != null ? I18N.get("offline.t0051") : I18N.get("offline.t0052"), I18N.get("offline.t0053"));
            return s;
        } catch (IOException e) {
            LOG.warn(I18N.get("offline.t0024"), e.getMessage());
            return null;
        }
    }

    /** authlib-injector 注入用的 API 根地址（如 {@code http://127.0.0.1:12345/}） */
    public String getApiBase() {
        return apiBase;
    }

    /** 最近一次启动的实例（开发者选项页「本地服务状态」查询用；未启动过为 null） */
    private static volatile LocalYggdrasilServer current;

    /** 当前运行的本地档案服务器（已停止或未启动返回 null） */
    public static LocalYggdrasilServer current() {
        LocalYggdrasilServer s = current;
        return (s == null || s.stopped) ? null : s;
    }

    /** 本地监听端口 */
    public int getPort() {
        return server.getAddress().getPort();
    }

    /** authlib-injector 元数据端点：告知 agent 本服务器支持皮肤且无需签名。
     *  authlib-injector 1.2.x 要求 GET /authlib-injector 返回 200，否则拒绝注入（JVM 启动失败）。
     *  skinDomains：纹理 URL 域名白名单（agent 的 SkinWhitelistTransformUnit 会把本列表并入
     *  authlib 的纹理白名单）；缺失时客户端报 "Textures payload contains blocked domain"
     *  并拒绝加载皮肤/披风（1.17.1 实测）。 */
    private void handleMetadata(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] body = ("{\"meta\":{"
                + "\"serverName\":\"Starlight Local\","
                + "\"implementationName\":\"Starlight Launcher\","
                + "\"implementationVersion\":\"2.0.0\","
                + "\"feature.non_email_login\":true,"
                + "\"feature.authlib_injector_actions\":false,"
                + "\"feature.skins\":true,"
                + "\"feature.no_mojang_account\":true"
                + "},\"skinDomains\":[\"127.0.0.1\"],"
                + "\"signaturePublickey\":\"" + (SIGNATURE_PUBLIC_KEY == null ? "" : SIGNATURE_PUBLIC_KEY)
                + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        server.stop(0);
        LOG.info(I18N.get("offline.t0025"), apiBase);
    }

    /** 是否仍在运行（未 stop 过即视为运行中） */
    public boolean isRunning() {
        return !stopped;
    }

    // HTTP 处理 //
    private void handleProfile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String uuidPart = path.substring(path.lastIndexOf('/') + 1);
        String uri = ex.getRequestURI().toString();
        if (uuidPart.equalsIgnoreCase(uuid)) {
            LOG.info(I18N.get("offline.t0026"), uuidPart, username, uri);
            byte[] body = buildProfileJson().getBytes(StandardCharsets.UTF_8);
            respond(ex, 200, "application/json", body);
        } else {
            LOG.info(I18N.get("offline.t0027"), uuidPart);
            proxyToMojang(ex, uuidPart);
        }
    }

    private void handleSkin(HttpExchange ex) throws IOException {
        if (skinPng == null) {
            LOG.info(I18N.get("offline.t0028"));
            respond(ex, 404, "text/plain", "no skin".getBytes(StandardCharsets.UTF_8));
            return;
        }
        LOG.info(I18N.get("offline.t0029"), skinPng.length, ex.getRequestURI().toString());
        respond(ex, 200, "image/png", skinPng);
    }

    private void handleCape(HttpExchange ex) throws IOException {
        if (capePng == null) {
            LOG.info(I18N.get("offline.t0030"));
            respond(ex, 404, "text/plain", "no cape".getBytes(StandardCharsets.UTF_8));
            return;
        }
        LOG.info(I18N.get("offline.t0031"), capePng.length, ex.getRequestURI().toString());
        respond(ex, 200, "image/png", capePng);
    }

    /** textures 属性 value 的原始 JSON payload（皮肤/披风 URL + slim 模型） */
    private String texturesPayload() {
        StringBuilder textures = new StringBuilder("{");
        boolean first = true;
        if (skinPng != null) {
            textures.append("\"SKIN\":{\"url\":\"").append(apiBase).append("skin.png\"");
            if (slim) {
                textures.append(",\"metadata\":{\"model\":\"slim\"}");
            }
            textures.append("}");
            first = false;
        }
        if (capePng != null) {
            if (!first) textures.append(",");
            textures.append("\"CAPE\":{\"url\":\"").append(apiBase).append("cape.png\"}");
        }
        textures.append("}");
        return "{\"timestamp\":" + System.currentTimeMillis()
                + ",\"profileId\":\"" + uuid + "\",\"profileName\":\"" + escapeJson(username)
                + "\",\"textures\":" + textures + "}";
    }

    /** 构建 sessionserver 档案 JSON（id/name/properties[textures]） */
    private String buildProfileJson() {
        String payload = texturesPayload();
        String value = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        // 客户端 Property.isSignatureValid 是对 value 字段的字节（base64 字符串本身）验签，
        // 因此签名对象必须是 value 而非原始 JSON payload。
        String signature = signTextures(value);
        StringBuilder sb = new StringBuilder("{\"id\":\"").append(uuid)
                .append("\",\"name\":\"").append(escapeJson(username))
                .append("\",\"properties\":[{\"name\":\"textures\",\"value\":\"").append(value).append("\"");
        if (signature != null) {
            sb.append(",\"signature\":\"").append(signature).append("\"");
        }
        sb.append("}]}");
        return sb.toString();
    }

    /**
     * 构建 1.17+ Main 的 {@code --profileProperties} 参数值（PropertyMap JSON）。
     *
     * <p>1.17 起客户端本地玩家的皮肤/披风不再查询会话档案：{@code SkinManager} 对本地玩家
     * 直接取 {@code MinecraftClient.getSession().getProfile()} 的属性（来自启动参数
     * {@code --profileProperties}）。因此离线皮肤/披风要在 1.17+ 显示，必须把 textures
     * 属性随启动参数注入，否则本地玩家 profile 永远没有 textures 属性。</p>
     *
     * <p>格式必须是 {@code {"<name>":["<value>"]}}（值数组）：authlib
     * {@code PropertyMap.Serializer} 对每个值调用 {@code JsonElement.getAsString()}，
     * 传对象数组 {@code {"textures":[{"name":"textures","value":"...","signature":"..."}]}}
     * 会抛 {@code UnsupportedOperationException: JsonObject}（20:13 实机崩溃实证）。
     * 1.17+ 本地玩家 {@code SkinManager} 以 {@code requireSecure=false} 读取（反编译实证），
     * 不验签，因此无需 signature。</p>
     */
    public String buildProfilePropertiesJson() {
        String payload = texturesPayload();
        String value = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "{\"textures\":[\"" + value + "\"]}";
    }

    private void proxyToMojang(HttpExchange ex, String uuidPart) {
        try {
            String rawQuery = ex.getRequestURI().getRawQuery();
            String target = MOJANG_SESSION + uuidPart
                    + (rawQuery != null && !rawQuery.isEmpty() ? "?" + rawQuery : "");
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "StarlightLauncher/2.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            respond(ex, resp.statusCode(),
                    resp.headers().firstValue("Content-Type").orElse("application/json"), resp.body());
        } catch (Exception e) {
            LOG.debug(I18N.get("offline.t0032"), uuidPart, e.getMessage());
            try {
                respond(ex, 204, "application/json", new byte[0]);
            } catch (IOException ignored) {
            }
        }
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] body)
            throws IOException {
        if (status == 204 || body.length == 0) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().put("Content-Type", List.of(contentType));
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] toPng(SkinTexture texture) {
        if (texture == null || texture.getImage() == null) return null;
        try {
            return SkinTexture.toPngBytes(texture.getImage());
        } catch (Exception e) {
            LOG.debug(I18N.get("offline.t0033"), e.getMessage());
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
