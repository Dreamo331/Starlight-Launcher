package com.example.starlight.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 端点与密钥集中配置：从 classpath 资源 {@code /assets/endpoints.json} 读取。
 *
 * <p>密钥与自家服务地址不再以字面量硬编码在各业务类中，统一收口到 assets/endpoints.json
 * 的 {@code keys} 与 {@code services} 两节（{@code reference} 节为归档备查，本类不读取）。
 * 改动配置后需重新打包；文件缺失或键缺失会以明确异常快速失败，避免静默产出空地址。
 */
public final class Endpoints {

    private static final String RESOURCE = "/assets/endpoints.json";

    /** 懒加载持有者：引用 Endpoints 类本身不触发 IO，首次取值才解析 JSON */
    private static final class Holder {
        static final JsonObject ROOT = load();
    }

    private Endpoints() {
    }

    private static JsonObject load() {
        try (InputStream in = Endpoints.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少内置资源 " + RESOURCE + "（打包配置遗漏？）");
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + RESOURCE + " 失败", e);
        }
    }

    /** 按点路径（如 keys.smtp.host）取字符串值，缺失即抛带路径的明确异常 */
    private static String str(String path) {
        JsonObject node = Holder.ROOT;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            if (!node.has(parts[i]) || !node.get(parts[i]).isJsonObject()) {
                throw missing(path);
            }
            node = node.getAsJsonObject(parts[i]);
        }
        String leaf = parts[parts.length - 1];
        if (!node.has(leaf) || node.get(leaf).isJsonNull()) {
            throw missing(path);
        }
        return node.get(leaf).getAsString();
    }

    private static IllegalStateException missing(String path) {
        return new IllegalStateException(RESOURCE + " 缺少配置项 " + path);
    }

    // ==================== 密钥（keys 节） ====================

    /** CurseForge 传统 API Key（x-api-key 认证；以 cfc_pat_ 开头时 CurseForgeAPI 会自动改用 Bearer） */
    public static String curseforgeApiKey() {
        return str("keys.curseforgeApiKey");
    }

    /** NVIDIA NIM（AI 崩溃诊断）API Key，可被系统属性 -Dstarlight.ai.apiKey 覆盖 */
    public static String nvidiaNimApiKey() {
        return str("keys.nvidiaNimApiKey");
    }

    /** 星光MC社区站点级 API Key（签到接口 X-API-Key 头），可被 -Dstarlight.community.apiKey 覆盖 */
    public static String communityApiKey() {
        return str("keys.communityApiKey");
    }

    /** 微软设备码登录的 Azure 公开客户端 ID */
    public static String microsoftClientId() {
        return str("keys.microsoftClientId");
    }

    public static String smtpHost() {
        return str("keys.smtp.host");
    }

    public static String smtpPort() {
        return str("keys.smtp.port");
    }

    public static String smtpUser() {
        return str("keys.smtp.user");
    }

    public static String smtpPassword() {
        return str("keys.smtp.password");
    }

    public static String smtpTo() {
        return str("keys.smtp.to");
    }

    // ==================== 自家服务（services 节） ====================

    /** 星光MC社区接口地址（auth.php） */
    public static String communityApiUrl() {
        return str("services.communityApiUrl");
    }

    /** 星光MC社区站点根地址（头像相对路径补全、官网链接） */
    public static String communitySiteUrl() {
        return str("services.communitySiteUrl");
    }

    /** 日志上传接口（随 starlight.ini 的 ServerUrl 可被用户覆盖） */
    public static String logUploadUrl() {
        return str("services.logUploadUrl");
    }

    /** FRP 联机控制服务器主机名 */
    public static String frpControlHost() {
        return str("services.frpControlHost");
    }

    /** FRP 联机控制服务器端口 */
    public static int frpControlPort() {
        return Integer.parseInt(str("services.frpControlPort"));
    }

    /** 爱发电赞助页链接 */
    public static String sponsorUrl() {
        return str("services.sponsorUrl");
    }

    /** 色盲辅助矫正工具（ColorBlindOverlay.exe）的下载地址 */
    public static String colorBlindOverlayUrl() {
        return str("services.colorBlindOverlayUrl");
    }
}
