/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.auth.AccountManager.AccountType;
import com.example.starlight.auth.offline.SkinTexture;
import com.example.starlight.newui.AppConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 微软账号头像获取器 —— 通过 Mojang 官方 API 获取正版皮肤：
 * <ol>
 *   <li>第一步：GET api.mojang.com/users/profiles/minecraft/&lt;用户名&gt; → 玩家 UUID（账号已含 UUID 时可跳过）</li>
 *   <li>第二步：GET sessionserver.mojang.com/session/minecraft/profile/&lt;无连字符UUID&gt;
 *       → properties[textures]（Base64）解码 → textures.SKIN.url</li>
 * </ol>
 * 获取到的皮肤图片缓存在本地（~/.starlight-launcher/avatars/{uuid}.png，24 小时有效），
 * 下次显示时直接读缓存，避免重复网络请求。
 */
public final class MojangAvatarFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(MojangAvatarFetcher.class);

    private static final String USERS_API = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_API = "https://sessionserver.mojang.com/session/minecraft/profile/";

    /** 皮肤缓存目录（与账号配置同目录） */
    private static final Path CACHE_DIR = Paths.get(System.getProperty("user.home"),
            ".starlight-launcher", "avatars");

    /** 本地文件缓存有效期（毫秒）：24 小时，过期后重新拉取（皮肤可能更换） */
    private static final long CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 运行期内存缓存：避免每次切页读盘 */
    private static final Map<String, Image> memoryCache = new HashMap<>();

    private MojangAvatarFetcher() {
    }

    /**
     * 获取微软账号的头像皮肤图片（后台线程调用，内部含网络/文件 IO）。
     * 缓存优先级：内存缓存 → 本地文件缓存（24h）→ 官方 API 下载并落盘。
     * 任一步失败均返回 null（由调用方回退默认皮肤）。
     */
    public static Image getAccountAvatar(Account account) {
        if (account == null || (account.type != AccountType.MICROSOFT && account.type != AccountType.THIRD_PARTY)) return null;
        // 第三方账号：皮肤来自其认证服务器（Yggdrasil session API）；微软账号走 Mojang 官方 sessionserver
        String serverBase = (account.type == AccountType.THIRD_PARTY) ? account.authServer : null;
        String uuid = account.id != null ? account.id.replace("-", "") : "";
        if (uuid.isEmpty()) return null;
        // 缓存 key 区分服务器，避免不同皮肤站相同 UUID 串缓存
        String cacheKey = (serverBase == null || serverBase.isBlank())
                ? "mojang/" + uuid
                : serverBase.replaceAll("/+$", "") + "/" + uuid;

        // 1. 内存缓存
        Image cached = memoryCache.get(cacheKey);
        if (cached != null) return cached;

        // 2. 本地文件缓存（未过期）
        Path file = CACHE_DIR.resolve(sanitizeFileName(cacheKey) + ".png");
        if (isCacheValid(file)) {
            try {
                SkinTexture tex = SkinTexture.loadTexture(Files.newInputStream(file));
                if (tex != null) {
                    memoryCache.put(cacheKey, tex.getImage());
                    return tex.getImage();
                }
            } catch (IOException e) {
                LOG.warn("Failed to read avatar cache: {}", e.getMessage());
            }
        }

        // 3. 认证服务器：皮肤 URL → 下载 → 落盘缓存
        try {
            String skinUrl = fetchSkinTextureUrl(serverBase, uuid);
            if (skinUrl == null || skinUrl.isEmpty()) {
                LOG.info("No skin texture for uuid {}, using fallback", uuid);
                return null;
            }
            Image image = downloadSkin(skinUrl);
            if (image == null) return null;
            try {
                Files.createDirectories(CACHE_DIR);
                AvatarGenerator.savePng(image, file);
            } catch (IOException e) {
                LOG.warn("Failed to cache avatar: {}", e.getMessage());
            }
            memoryCache.put(cacheKey, image);
            return image;
        } catch (Exception e) {
            LOG.warn("Failed to fetch avatar from auth server: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 第一步：通过游戏内用户名获取玩家 UUID（无连字符）。
     * 返回 null 表示用户不存在或请求失败。
     */
    public static String fetchUuid(String username) throws IOException, InterruptedException {
        if (username == null || username.isEmpty()) return null;
        HttpResponse<String> resp = CLIENT.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(USERS_API + username))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", AppConfig.USER_AGENT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "GET", USERS_API + username, 0, 0, null, -1);
        DebugLog.http(false, null, USERS_API + username, resp.statusCode(), resp.body().length(), resp.body(), -1);
        if (resp.statusCode() != 200) return null;
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        return root.has("id") && !root.get("id").isJsonNull() ? root.get("id").getAsString() : null;
    }

    /**
     * 第二步：通过无连字符 UUID 获取皮肤图片 URL。
     * 响应中 properties[textures] 为 Base64 编码的 JSON，解码后取 textures.SKIN.url。
     */
    public static String fetchSkinTextureUrl(String uuid) throws IOException, InterruptedException {
        return fetchSkinTextureUrl(null, uuid);
    }

    /**
     * 通过无连字符 UUID 获取皮肤图片 URL。
     * serverBase 为 null/空时使用 Mojang 官方 sessionserver；
     * 否则使用第三方认证服务器的 {serverBase}/sessionserver/session/minecraft/profile/{uuid}
     * （Authlib-Injector / Yggdrasil 协议与官方同构，参考 HMCL AuthlibInjectorServer）。
     */
    public static String fetchSkinTextureUrl(String serverBase, String uuid) throws IOException, InterruptedException {
        if (uuid == null || uuid.isEmpty()) return null;
        String compact = uuid.replace("-", "");
        String sessionApi = (serverBase == null || serverBase.isBlank())
                ? SESSION_API + compact
                : serverBase.replaceAll("/+$", "") + "/sessionserver/session/minecraft/profile/" + compact;
        HttpResponse<String> resp = CLIENT.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(sessionApi))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", AppConfig.USER_AGENT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        DebugLog.http(true, "GET", sessionApi, 0, 0, null, -1);
        DebugLog.http(false, null, sessionApi, resp.statusCode(), resp.body().length(), resp.body(), -1);
        if (resp.statusCode() != 200) return null;

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray props = root.has("properties") && !root.get("properties").isJsonNull()
                ? root.getAsJsonArray("properties") : null;
        if (props == null) return null;

        for (JsonElement el : props) {
            JsonObject prop = el.getAsJsonObject();
            if (!"textures".equals(getStr(prop, "name"))) continue;
            String value = getStr(prop, "value");
            if (value == null || value.isEmpty()) continue;
            try {
                byte[] decoded = Base64.getDecoder().decode(value);
                JsonObject payload = JsonParser.parseString(
                        new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
                if (payload.has("textures") && payload.get("textures").isJsonObject()) {
                    JsonObject textures = payload.getAsJsonObject("textures");
                    if (textures.has("SKIN") && textures.get("SKIN").isJsonObject()) {
                        String url = getStr(textures.getAsJsonObject("SKIN"), "url");
                        if (url != null && !url.isEmpty()) return url;
                    }
                }
            } catch (IllegalArgumentException e) {
                LOG.warn("Invalid base64 texture payload: {}", e.getMessage());
            }
        }
        return null;
    }

    /** 下载皮肤图片并等待加载完成（后台线程调用） */
    private static Image downloadSkin(String skinUrl) throws IOException, InterruptedException {
        HttpResponse<byte[]> resp = CLIENT.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(skinUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", AppConfig.USER_AGENT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        DebugLog.http(true, "GET", skinUrl, 0, 0, null, -1);
        DebugLog.http(false, null, skinUrl, resp.statusCode(), resp.body().length, "[二进制图片数据]", -1);
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) {
            return null;
        }
        SkinTexture tex = SkinTexture.loadTexture(new java.io.ByteArrayInputStream(resp.body()));
        return tex != null ? tex.getImage() : null;
    }

    /** 缓存文件名安全化：服务器地址中的特殊字符替换为下划线 */
    private static String sanitizeFileName(String key) {
        return key.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    /** 本地缓存文件是否存在且未过期 */
    private static boolean isCacheValid(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            return modified.toInstant().isAfter(Instant.now().minusMillis(CACHE_TTL_MILLIS));
        } catch (IOException e) {
            return false;
        }
    }

    private static String getStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
