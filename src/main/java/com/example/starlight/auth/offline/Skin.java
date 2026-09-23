/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.auth.offline;

import com.example.starlight.newui.AppConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * 离线皮肤配置模型
 * 参考 HMCL 的 auth.offline.Skin，简化为本启动器适用的版本
 */
public class Skin {

    private static final Logger LOG = LoggerFactory.getLogger(Skin.class);

    public enum Type {
        DEFAULT,       // 无自定义皮肤
        ALEX, ARI, EFE, KAI, MAKENA, NOOR, STEVE, SUNNY, ZURI,  // 内置默认皮肤
        LOCAL_FILE,    // 本地PNG文件
        LITTLE_SKIN,   // LittleSkin CSL API
        CUSTOM_SKIN_LOADER_API; // 自定义 CSL API

        public static Type fromStorage(String name) {
            if (name == null) return DEFAULT;
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "alex" -> ALEX;
                case "ari" -> ARI;
                case "efe" -> EFE;
                case "kai" -> KAI;
                case "makena" -> MAKENA;
                case "noor" -> NOOR;
                case "steve" -> STEVE;
                case "sunny" -> SUNNY;
                case "zuri" -> ZURI;
                case "local_file" -> LOCAL_FILE;
                case "little_skin" -> LITTLE_SKIN;
                case "custom_skin_loader_api" -> CUSTOM_SKIN_LOADER_API;
                default -> DEFAULT;
            };
        }

        public String toStorage() {
            return this == DEFAULT ? "default" : name().toLowerCase(Locale.ROOT);
        }
    }

    /** 是否为 slim（纤细）模型 */
    public enum TextureModel {
        WIDE("default"),
        SLIM("slim");

        public final String modelName;

        TextureModel(String modelName) {
            this.modelName = modelName;
        }
    }

    private final Type type;
    private final String cslApi;
    private final TextureModel textureModel;
    private final String localSkinPath;
    private final String localCapePath;

    public Skin(Type type, String cslApi, TextureModel textureModel, String localSkinPath, String localCapePath) {
        this.type = type;
        this.cslApi = cslApi;
        this.textureModel = textureModel;
        this.localSkinPath = localSkinPath;
        this.localCapePath = localCapePath;
    }

    public Type getType() { return type; }
    public String getCslApi() { return cslApi; }
    public TextureModel getTextureModel() { return textureModel == null ? TextureModel.WIDE : textureModel; }
    public String getLocalSkinPath() { return localSkinPath; }
    public String getLocalCapePath() { return localCapePath; }

    /** 是否为 slim 模型 */
    public boolean isSlim() {
        return getTextureModel() == TextureModel.SLIM;
    }

    /**
     * 加载皮肤纹理
     * @param username 玩家名（用于 CSL API 查询）
     * @return LoadedSkin，或 null（无自定义皮肤时）
     */
    public LoadedSkin load(String username) {
        switch (type) {
            case DEFAULT:
                return null;
            case ALEX: case ARI: case EFE: case KAI:
            case MAKENA: case NOOR: case STEVE: case SUNNY: case ZURI: {
                TextureModel model = textureModel != null ? textureModel :
                        (type == Type.ALEX ? TextureModel.SLIM : TextureModel.WIDE);
                String resourcePath = "/Skins/" + type.name() + ".png";
                Image img = new Image(getClass().getResourceAsStream(resourcePath));
                if (img.isError()) {
                    LOG.warn("Failed to load built-in skin: {}", resourcePath);
                    return null;
                }
                SkinTexture skinTex = SkinTexture.loadTexture(img);
                return new LoadedSkin(model, skinTex, null);
            }
            case LOCAL_FILE: {
                try {
                    SkinTexture skin = null, cape = null;
                    if (localSkinPath != null && !localSkinPath.isEmpty()) {
                        Path skinPath = Paths.get(localSkinPath);
                        if (Files.exists(skinPath)) {
                            skin = SkinTexture.loadTexture(Files.newInputStream(skinPath));
                        }
                    }
                    if (localCapePath != null && !localCapePath.isEmpty()) {
                        Path capePath = Paths.get(localCapePath);
                        if (Files.exists(capePath)) {
                            cape = SkinTexture.loadTexture(Files.newInputStream(capePath));
                        }
                    }
                    return new LoadedSkin(getTextureModel(), skin, cape);
                } catch (IOException e) {
                    LOG.warn("Failed to load local skin: {}", e.getMessage());
                    return null;
                }
            }
            case LITTLE_SKIN: {
                try {
                    return loadFromCslApi("https://littleskin.cn/csl", username);
                } catch (Exception e) {
                    LOG.warn("Failed to load skin from LittleSkin: {}", e.getMessage());
                    return null;
                }
            }
            case CUSTOM_SKIN_LOADER_API: {
                String api = cslApi != null && !cslApi.isEmpty() ? cslApi : "https://littleskin.cn/csl";
                if (!api.startsWith("http://") && !api.startsWith("https://")) {
                    api = "https://" + api;
                }
                if (api.endsWith("/")) api = api.substring(0, api.length() - 1);
                try {
                    return loadFromCslApi(api, username);
                } catch (Exception e) {
                    LOG.warn("Failed to load skin from CSL API [{}]: {}", api, e.getMessage());
                    return null;
                }
            }
            default:
                return null;
        }
    }

    /**
     * 从 Custom Skin Loader API 加载皮肤
     */
    private LoadedSkin loadFromCslApi(String apiBase, String username) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // 获取用户皮肤 JSON
        HttpRequest jsonReq = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/" + username + ".json"))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", AppConfig.USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> jsonResp = client.send(jsonReq, HttpResponse.BodyHandlers.ofString());
        if (jsonResp.statusCode() != 200) {
            LOG.warn("CSL API returned non-200: {}", jsonResp.statusCode());
            return null;
        }

        JsonObject root = JsonParser.parseString(jsonResp.body()).getAsJsonObject();
        String skinHash = null;
        String capeHash = null;
        boolean slim = false;

        // 解析 textures 字段（新格式）或传统字段
        if (root.has("textures") && root.get("textures").isJsonObject()) {
            JsonObject tex = root.getAsJsonObject("textures");
            if (tex.has("slim") && !tex.get("slim").isJsonNull()) {
                skinHash = tex.get("slim").getAsString();
                slim = true;
            } else if (tex.has("default") && !tex.get("default").isJsonNull()) {
                skinHash = tex.get("default").getAsString();
            }
            if (tex.has("cape") && !tex.get("cape").isJsonNull()) {
                capeHash = tex.get("cape").getAsString();
            }
        } else {
            // 传统格式
            if (root.has("skin") && !root.get("skin").isJsonNull()) {
                skinHash = root.get("skin").getAsString();
            }
            if (root.has("cape") && !root.get("cape").isJsonNull()) {
                capeHash = root.get("cape").getAsString();
            }
        }

        if (skinHash == null || skinHash.isEmpty()) return null;

        // 下载皮肤纹理
        SkinTexture skinTex = downloadTexture(client, apiBase + "/textures/" + skinHash);
        SkinTexture capeTex = capeHash != null ? downloadTexture(client, apiBase + "/textures/" + capeHash) : null;

        return new LoadedSkin(slim ? TextureModel.SLIM : TextureModel.WIDE, skinTex, capeTex);
    }

    private SkinTexture downloadTexture(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", AppConfig.USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) return null;
        try (InputStream is = resp.body()) {
            return SkinTexture.loadTexture(is);
        }
    }

    /**
     * 将皮肤配置序列化为 JSON
     */
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type.toStorage());
        if (cslApi != null) obj.addProperty("cslApi", cslApi);
        obj.addProperty("textureModel", getTextureModel().modelName);
        if (localSkinPath != null) obj.addProperty("localSkinPath", localSkinPath);
        if (localCapePath != null) obj.addProperty("localCapePath", localCapePath);
        return obj;
    }

    /**
     * 从 JSON 反序列化皮肤配置
     */
    public static Skin fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return new Skin(Type.DEFAULT, null, null, null, null);
        JsonObject obj = element.getAsJsonObject();
        Type type = Type.fromStorage(getString(obj, "type"));
        String cslApi = getString(obj, "cslApi");
        String texModel = getString(obj, "textureModel");
        TextureModel model = "slim".equals(texModel) ? TextureModel.SLIM : TextureModel.WIDE;
        String localSkin = getString(obj, "localSkinPath");
        String localCape = getString(obj, "localCapePath");
        return new Skin(type, cslApi, model, localSkin, localCape);
    }

    /** 兼容 toStorage() / fromStorage() 的 Map 接口 */
    public java.util.Map<String, Object> toStorage() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("type", type.toStorage());
        if (cslApi != null) map.put("cslApi", cslApi);
        map.put("textureModel", getTextureModel().modelName);
        if (localSkinPath != null) map.put("localSkinPath", localSkinPath);
        if (localCapePath != null) map.put("localCapePath", localCapePath);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Skin fromStorage(Object storage) {
        if (storage == null) return new Skin(Type.DEFAULT, null, null, null, null);
        java.util.Map<String, Object> map;
        if (storage instanceof java.util.Map) {
            map = (java.util.Map<String, Object>) storage;
        } else {
            return new Skin(Type.DEFAULT, null, null, null, null);
        }
        Type type = Type.fromStorage((String) map.get("type"));
        String cslApi = (String) map.get("cslApi");
        String texModel = (String) map.get("textureModel");
        TextureModel model = "slim".equals(texModel) ? TextureModel.SLIM : TextureModel.WIDE;
        String localSkin = (String) map.get("localSkinPath");
        String localCape = (String) map.get("localCapePath");
        return new Skin(type, cslApi, model, localSkin, localCape);
    }

    private static String getString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    @Override
    public String toString() {
        return "Skin{type=" + type + ", model=" + textureModel + "}";
    }

    // ========== 静态内置皮肤枚举 ==========

    /** 获取所有内置皮肤类型（用于UI选择） */
    public static Type[] getBuiltinTypes() {
        return new Type[]{Type.STEVE, Type.ALEX, Type.ARI, Type.EFE, Type.KAI,
                Type.MAKENA, Type.NOOR, Type.SUNNY, Type.ZURI};
    }

    /** Skin 加载结果 */
    public static class LoadedSkin {
        private final TextureModel model;
        private final SkinTexture skin;
        private final SkinTexture cape;

        public LoadedSkin(TextureModel model, SkinTexture skin, SkinTexture cape) {
            this.model = model;
            this.skin = skin;
            this.cape = cape;
        }

        public TextureModel getModel() { return model; }
        public SkinTexture getSkin() { return skin; }
        public SkinTexture getCape() { return cape; }

        public boolean hasSkin() { return skin != null; }
    }
}
