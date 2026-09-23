/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;

import com.example.starlight.newui.AppConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线皮肤本地 Yggdrasil 服务器
 * 在启动Minecraft 前启动，为游戏提供皮肤纹理
 * 参考HMCL's auth.offline.YggdrasilServer
 */
public class OfflineSkinServer {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineSkinServer.class);
    private static final Gson GSON = new GsonBuilder().create();

    private final HttpServer server;
    private final Map<UUID, CharacterEntry> charactersByUuid = new HashMap<>();
    private final Map<String, CharacterEntry> charactersByName = new HashMap<>();

    private int port;

    public OfflineSkinServer() throws IOException {
        this(0); // 随机端口
    }

    public OfflineSkinServer(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newSingleThreadExecutor());

        // 注册路由
        server.createContext("/", new RootHandler());
        server.createContext("/status", new StatusHandler());
        server.createContext("/api/profiles/minecraft", new ProfilesHandler());
        server.createContext("/sessionserver/session/minecraft/hasJoined", new HasJoinedHandler());
        server.createContext("/sessionserver/session/minecraft/join", new JoinServerHandler());
        server.createContext("/sessionserver/session/minecraft/profile/", new ProfileHandler());
        server.createContext("/textures/", new TextureHandler());

        this.port = server.getAddress().getPort();
    }

    public int getPort() {
        return port;
    }

    public void start() {
        server.start();
        LOG.info("Offline skin server started, port: {}", port);
    }

    public void stop() {
        server.stop(0);
        LOG.info("Offline skin server stopped");
    }

    public void addCharacter(UUID uuid, String name, Skin.LoadedSkin skin) {
        CharacterEntry entry = new CharacterEntry(uuid, name, skin);
        charactersByUuid.put(uuid, entry);
        charactersByName.put(name, entry);
    }

    public void clearCharacters() {
        charactersByUuid.clear();
        charactersByName.clear();
    }

    // ========== HTTP Handlers ==========

    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("signaturePublickey", getPublicKeyPEM());
            resp.put("skinDomains", Arrays.asList("127.0.0.1", "localhost"));
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("serverName", "StarlightLauncher");
            meta.put("implementationName", "StarlightLauncher");
            meta.put("implementationVersion", AppConfig.APP_VERSION);
            meta.put("feature.non_email_login", true);
            resp.put("meta", meta);
            sendJson(exchange, 200, GSON.toJson(resp));
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("user.count", charactersByUuid.size());
            resp.put("token.count", 0);
            resp.put("pendingAuthentication.count", 0);
            sendJson(exchange, 200, GSON.toJson(resp));
        }
    }

    private class ProfilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String json = new String(body, StandardCharsets.UTF_8);
            String[] names = GSON.fromJson(json, String[].class);

            List<Map<String, Object>> profiles = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String name : names) {
                if (!seen.add(name)) continue;
                CharacterEntry entry = charactersByName.get(name);
                if (entry != null) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("id", entry.uuid.toString().replace("-", ""));
                    p.put("name", entry.name);
                    profiles.add(p);
                }
            }
            sendJson(exchange, 200, GSON.toJson(profiles));
        }
    }

    private class HasJoinedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String username = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if ("username".equals(kv[0]) && kv.length > 1) {
                        username = kv[1];
                    }
                }
            }
            if (username == null) {
                sendJson(exchange, 400, "{\"error\":\"Missing username\"}");
                return;
            }
            CharacterEntry entry = charactersByName.get(username);
            if (entry != null) {
                sendJson(exchange, 200, entry.toCompleteResponse(getBaseUrl(exchange)));
            } else {
                sendJson(exchange, 204, "");
            }
        }
    }

    private class JoinServerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendJson(exchange, 204, "");
        }
    }

    private class ProfileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            // /sessionserver/session/minecraft/profile/{uuid}
            String uuidStr = path.substring(path.lastIndexOf('/') + 1);
            UUID uuid;
            try {
                if (uuidStr.length() == 32) {
                    uuid = UUID.fromString(uuidStr.replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
                } else {
                    uuid = UUID.fromString(uuidStr);
                }
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, "{\"error\":\"Invalid UUID\"}");
                return;
            }

            CharacterEntry entry = charactersByUuid.get(uuid);
            if (entry != null) {
                sendJson(exchange, 200, entry.toCompleteResponse(getBaseUrl(exchange)));
            } else {
                sendJson(exchange, 204, "");
            }
        }
    }

    private class TextureHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            // /textures/{hash}
            String hash = path.substring(path.lastIndexOf('/') + 1);

            if (SkinTexture.hasTexture(hash)) {
                SkinTexture tex = SkinTexture.getTexture(hash);
                // 将JavaFX Image转换为PNG字节
                try {
                    byte[] pngData = convertImageToPng(tex.getImage());
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.getResponseHeaders().set("ETag", "\"" + hash + "\"");
                    exchange.getResponseHeaders().set("Cache-Control", "max-age=2592000, public");
                    exchange.sendResponseHeaders(200, pngData.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(pngData);
                    }
                } catch (Exception e) {
                    LOG.warn("Texture output failed: {}", e.getMessage());
                    sendJson(exchange, 500, "{\"error\":\"Internal error\"}");
                }
            } else {
                sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        }
    }

    // ========== 工具方法 ==========

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String getBaseUrl(HttpExchange exchange) {
        String host = exchange.getLocalAddress().getHostString();
        return "http://" + host + ":" + port;
    }

    // ========== 密钥与签名 ==========

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getPublicKeyPEM() {
        PublicKey pub = KEY_PAIR.getPublic();
        String base64 = Base64.getEncoder().encodeToString(pub.getEncoded());
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < base64.length(); i += 76) {
            pem.append(base64, i, Math.min(i + 76, base64.length())).append('\n');
        }
        pem.append("-----END PUBLIC KEY-----\n");
        return pem.toString();
    }

    private static String sign(String data) {
        try {
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(KEY_PAIR.getPrivate(), new SecureRandom());
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    // ========== 字符条目 ==========

    private static class CharacterEntry {
        final UUID uuid;
        final String name;
        final Skin.LoadedSkin skin;

        CharacterEntry(UUID uuid, String name, Skin.LoadedSkin skin) {
            this.uuid = uuid;
            this.name = name;
            this.skin = skin;
        }

        String toCompleteResponse(String rootUrl) {
            Map<String, Object> realTextures = new LinkedHashMap<>();
            if (skin != null && skin.getSkin() != null) {
                Map<String, Object> skinEntry = new LinkedHashMap<>();
                skinEntry.put("url", rootUrl + "/textures/" + skin.getSkin().getHash());
                if (skin.getModel() == Skin.TextureModel.SLIM) {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("model", "slim");
                    skinEntry.put("metadata", metadata);
                }
                realTextures.put("SKIN", skinEntry);
            }
            if (skin != null && skin.getCape() != null) {
                Map<String, Object> capeEntry = new LinkedHashMap<>();
                capeEntry.put("url", rootUrl + "/textures/" + skin.getCape().getHash());
                realTextures.put("CAPE", capeEntry);
            }

            Map<String, Object> texturePayload = new LinkedHashMap<>();
            texturePayload.put("timestamp", System.currentTimeMillis());
            texturePayload.put("profileId", uuid.toString().replace("-", ""));
            texturePayload.put("profileName", name);
            texturePayload.put("textures", realTextures);

            String encodedTextures = Base64.getEncoder().encodeToString(
                    GSON.toJson(texturePayload).getBytes(StandardCharsets.UTF_8));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", uuid.toString().replace("-", ""));
            response.put("name", name);

            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("name", "textures");
            prop.put("value", encodedTextures);
            prop.put("signature", sign(encodedTextures));
            response.put("properties", Collections.singletonList(prop));

            return GSON.toJson(response);
        }
    }

    // ========== Image转换工具 ==========

    private static byte[] convertImageToPng(javafx.scene.image.Image fxImage) throws IOException {
        int width = (int) fxImage.getWidth();
        int height = (int) fxImage.getHeight();
        BufferedImage buf = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = fxImage.getPixelReader();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                buf.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(buf, "png", baos);
        return baos.toByteArray();
    }
}
