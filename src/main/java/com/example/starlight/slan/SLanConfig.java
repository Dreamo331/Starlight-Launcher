/**
 * (c) 2026 Starlight Team. All rights reserved.
 * SLAN（SL-MinecraftLAN）联机模块服务器配置读取
 *
 * <p>配置来源：{@code <启动器根目录>/Starlight-Launcher/multiplayer.json}。
 * 首次使用时若文件不存在，会把打包内默认模板 {@code /assets/multiplayer.json} 复制过去，
 * 之后一律以磁盘文件为准（用户可改中继服务器地址、模块下载地址等，改完重启启动器生效）。
 * 这样服务器域名等不再硬编码在 Java 源码里，运营方换服务器只需改配置文件。</p>
 *
 * <p>Native Image 说明：本类用 Gson 树模型（JsonParser）读取，不涉及反射，
 * 无需在 pom 的 reflectionList 里注册。</p>
 */
package com.example.starlight.slan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SLAN 联机模块的外部服务器配置。
 * 所有远端服务器地址（中继服务器、模块下载源）统一从这里读取，禁止在业务代码里再写死。
 */
public final class SLanConfig {

    private static final Logger log = LoggerFactory.getLogger(SLanConfig.class);

    /** 配置文件所在目录（相对启动器工作目录，与 starlight.ini 同一目录） */
    private static final String CONFIG_DIR = "Starlight-Launcher";

    /** 配置文件文件名 */
    private static final String CONFIG_FILE = "multiplayer.json";

    /** 打包内置的默认模板（用于首次生成配置文件） */
    private static final String DEFAULT_TEMPLATE_RESOURCE = "/assets/multiplayer.json";

    /** 传给 SL-MinecraftLAN.exe 的环境变量名（客户端 config.py 读取，覆盖内置默认服务器） */
    private static final String ENV_RELAY_SERVER = "MC_WS_SERVER";

    /** 配置键名 */
    public static final String KEY_RELAY_SERVER = "relayServer";
    public static final String KEY_MODULE_DOWNLOAD_URL = "moduleDownloadUrl";

    /** 是否已尝试过生成默认文件（进程内只补一次） */
    private static volatile boolean ensureAttempted = false;

    private SLanConfig() {
    }

    /** 配置文件完整路径：<user.dir>/Starlight-Launcher/multiplayer.json */
    public static Path configPath() {
        return Paths.get(System.getProperty("user.dir", "."), CONFIG_DIR, CONFIG_FILE);
    }

    // ================================================================
    //  读取
    // ================================================================

    /**
     * 中继（房间）服务器地址，形如 {@code ws://主机:端口}。
     * 实际默认值见打包模板 {@code /assets/multiplayer.json}；首次运行会复制到
     * {@code Starlight-Launcher/multiplayer.json}，之后以磁盘文件为准。
     * 优先读外部配置文件，文件缺失或该项为空时回退到打包模板默认值；
     * 若文件里只填了 {@code 主机:端口}（漏了 ws:// 前缀），这里会自动补全，
     * 否则客户端 WebSocket 会因 URI 缺协议头而连接失败。
     */
    public static String relayServer() {
        return normalizeServer(getString(KEY_RELAY_SERVER));
    }

    /**
     * SL-MinecraftLAN 联机模块安装包下载地址。
     * 优先读外部配置文件，文件缺失或该项为空时回退到打包模板默认值。
     */
    public static String moduleDownloadUrl() {
        return getString(KEY_MODULE_DOWNLOAD_URL);
    }

    /**
     * 把中继服务器地址写入子进程环境变量 {@code MC_WS_SERVER}，
     * 供 SL-MinecraftLAN.exe（Python 客户端）启动时覆盖内置默认服务器。
     * 未配置（空值）时不设置，让模块走自己的内置默认值。
     *
     * @param pb 即将启动模块的 ProcessBuilder
     */
    public static void applyEnvironment(ProcessBuilder pb) {
        String server = relayServer();
        if (server == null || server.isBlank()) {
            return;
        }
        pb.environment().put(ENV_RELAY_SERVER, server.trim());
    }

    /**
     * 更新外部配置项并落盘（供 UI 保存）。
     * 保留文件里其它已有字段（含 {@code __comment__}）；传 null 的键不修改。
     * 每次读取都直读磁盘文件、无进程内缓存，因此保存后下一次建房/加房即生效。
     *
     * @param relayServer       新的中继服务器地址（可为 null 表示不改）
     * @param moduleDownloadUrl 新的模块下载地址（可为 null 表示不改）
     * @throws IOException 目录创建或文件写入失败
     */
    public static void update(String relayServer, String moduleDownloadUrl) throws IOException {
        Path path = configPath();
        ensureDefaultFile();

        JsonObject obj = null;
        try {
            if (Files.isRegularFile(path)) {
                obj = parseObject(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read multiplayer.json before update, will rewrite from scratch: {}", e.getMessage());
        }
        if (obj == null) {
            obj = new JsonObject();
        }
        if (relayServer != null && !relayServer.isBlank()) {
            obj.addProperty(KEY_RELAY_SERVER, normalizeServer(relayServer.trim()));
        }
        if (moduleDownloadUrl != null && !moduleDownloadUrl.isBlank()) {
            obj.addProperty(KEY_MODULE_DOWNLOAD_URL, moduleDownloadUrl.trim());
        }

        Files.createDirectories(path.getParent());
        String json = new com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson(obj);
        Files.writeString(path, json, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Multiplayer config updated: {}", path.toAbsolutePath());
    }

    /**
     * 对外暴露的地址归一化：未带协议头（如只写了 {@code 主机:端口}）时补 {@code ws://}。
     * 供「检测服务器」等功能在未保存前校验输入框内容。
     */
    public static String normalizeRelayServer(String raw) {
        return normalizeServer(raw);
    }

    /** 通用读取：外部文件 → 模板兜底 */
    private static String getString(String key) {
        JsonObject fileCfg = readExternalFile();
        if (fileCfg != null) {
            String v = stringValue(fileCfg.get(key));
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        // 外部文件缺失键/为空 → 用打包模板的默认值（保证缺文件也能正常工作）
        JsonObject template = readTemplateResource();
        if (template != null) {
            String v = stringValue(template.get(key));
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    /**
     * 读取外部配置文件；若文件不存在，先尝试从打包模板生成一份默认文件再读。
     * 读取/解析失败返回 null（不抛异常，避免影响联机主流程）。
     */
    private static JsonObject readExternalFile() {
        ensureDefaultFile();
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return parseObject(text);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to parse multiplayer.json, fallback to template default: {}", e.getMessage());
            return null;
        }
    }

    /** 进程内只生成一次默认文件：磁盘上没有 multiplayer.json 时从打包模板复制 */
    private static void ensureDefaultFile() {
        if (ensureAttempted) {
            return;
        }
        synchronized (SLanConfig.class) {
            if (ensureAttempted) {
                return;
            }
            ensureAttempted = true;
            Path path = configPath();
            try {
                if (Files.isRegularFile(path)) {
                    return;
                }
                Files.createDirectories(path.getParent());
                try (InputStream in = SLanConfig.class.getResourceAsStream(DEFAULT_TEMPLATE_RESOURCE)) {
                    if (in == null) {
                        log.warn("Cannot find bundled default template {}", DEFAULT_TEMPLATE_RESOURCE);
                        return;
                    }
                    Files.copy(in, path);
                    log.info("Generated default multiplayer config: {}", path.toAbsolutePath());
                }
            } catch (IOException e) {
                log.warn("Failed to generate default multiplayer config {}: {}",
                        path.toAbsolutePath(), e.getMessage());
            }
        }
    }

    /** 读取打包内置模板（含注释与默认值），失败返回 null */
    private static JsonObject readTemplateResource() {
        try (InputStream in = SLanConfig.class.getResourceAsStream(DEFAULT_TEMPLATE_RESOURCE)) {
            if (in == null) {
                return null;
            }
            return parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to load bundled multiplayer template: {}", e.getMessage());
            return null;
        }
    }

    private static JsonObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonElement el = JsonParser.parseString(json);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static String stringValue(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        return el.getAsString();
    }

    /** 地址缺协议头（只写了 host:port）时自动补 ws://，避免 WebSocket URI 解析失败 */
    private static String normalizeServer(String server) {
        if (server == null || server.isBlank()) {
            return "";
        }
        String v = server.trim();
        return v.contains("://") ? v : "ws://" + v;
    }
}
