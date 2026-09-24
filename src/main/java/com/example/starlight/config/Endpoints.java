package com.example.starlight.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 端点与密钥集中配置：从 classpath 资源 {@code /assets/endpoints.json} 读取。
 *
 * <p>密钥与自家服务地址不再以字面量硬编码在各业务类中，统一收口到 assets/endpoints.json
 * 的 {@code keys} 与 {@code services} 两节（{@code reference} 节为归档备查，本类不读取）。
 * 改动配置后需重新打包；文件缺失或键缺失会以明确异常快速失败，避免静默产出空地址。
 *
 * <p><b>本地覆盖</b>：仓库里那份 endpoints.json 只放占位符（公开仓库不能出现真实密钥）。
 * 真实密钥写在 {@code /assets/endpoints.local.json}（已加入 .gitignore，不会入库）里，
 * 存在时按 key 覆盖前者——本地开发照常用真值，不必改代码，也不会误把密钥提交上去。
 */
public final class Endpoints {

    private static final String RESOURCE = "/assets/endpoints.json";

    /** 本地专用覆盖文件（不入库）；缺失时静默跳过，仅用 {@link #RESOURCE} */
    private static final String LOCAL_RESOURCE = "/assets/endpoints.local.json";

    /** 懒加载持有者：引用 Endpoints 类本身不触发 IO，首次取值才解析 JSON */
    private static final class Holder {
        static final JsonObject ROOT = load();
    }

    private Endpoints() {
    }

    private static JsonObject load() {
        JsonObject base = read(RESOURCE, true);
        JsonObject local = read(LOCAL_RESOURCE, false);
        if (local != null) {
            merge(base, local);
        }
        return base;
    }

    /**
     * 读 classpath 上的 JSON 资源。
     *
     * @param required true=文件必须存在，缺失即快速失败；false=可选，缺失返回 null
     */
    private static JsonObject read(String resource, boolean required) {
        try (InputStream in = Endpoints.class.getResourceAsStream(resource)) {
            if (in == null) {
                if (required) {
                    throw new IllegalStateException("缺少内置资源 " + resource + "（打包配置遗漏？）");
                }
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + resource + " 失败", e);
        }
    }

    /** 递归合并：override 里出现的键覆盖 base 的同名键，未出现的保持 base 原值 */
    private static void merge(JsonObject base, JsonObject override) {
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()
                    && base.has(key) && base.get(key).isJsonObject()) {
                merge(base.getAsJsonObject(key), value.getAsJsonObject());
            } else {
                base.add(key, value);
            }
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

    /**
     * 启动器开源仓库（GitHub），格式 {@code owner/repo}。
     * 官方更新接口（communityApiUrl）不可用时，由 UpdateChecker 降级到这里做版本检查。
     */
    public static String githubRepo() {
        return str("services.githubRepo");
    }

    /**
     * 启动器开源仓库（Gitee，公开仓库），格式 {@code owner/repo}。
     * 与 {@link #githubRepo()} 一样只在官方更新接口不可用时作为降级来源；
     * 两者在 UpdateChecker 里并发竞速，用户所在位置网速快的那家先返回即被采用。
     */
    public static String giteeRepo() {
        return str("services.giteeRepo");
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
