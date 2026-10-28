package com.example.starlight.config;

import com.example.starlight.util.I18N;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        // 用户级全局覆盖：~/.starlight/endpoints.local.json（优先级最高，免重新打包）
        JsonObject global = readGlobalFile();
        if (global != null) {
            merge(base, global);
            // 也支持 reference 形态：{"keys":{...},"services":{...}} 直接递归合并
        }
        return base;
    }

    /**
     * 读取用户数据目录下的 {@code endpoints.local.json}。
     *
     * <p>历史上真实密钥只能塞进 jar 内的 {@code /assets/endpoints.local.json}，
     * 改一次密钥就得重新打包。现在同时支持放在 {@code ~/.starlight/endpoints.local.json}
     * （便携模式下为 {@code <工作目录>/Starlight-Launcher/endpoints.local.json}），
     * 与内置文件按 key 深度合并，缺失或解析失败一律静默跳过。
     */
    private static JsonObject readGlobalFile() {
        try {
            Path p = AppDirs.endpointsLocalJson();
            if (!Files.isRegularFile(p)) {
                return null;
            }
            return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            // 覆盖文件写坏不能拖垮启动：退回内置值
            return null;
        }
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
                    throw new IllegalStateException(I18N.get("endp.r0001", resource));
                }
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException(I18N.get("endp.r0002", resource), e);
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
        return new IllegalStateException(I18N.get("endp.r0003", RESOURCE, path));
    }

    /**
     * 按点路径取字符串值，缺失时返回默认值（必填项请用 {@link #str(String)}）。
     *
     * <p>用于后续新增、老配置文件里可能还不存在的可选键：不写默认值就会让
     * {@code ~/.starlight/endpoints.local.json} 这种历史覆盖文件把新键顶成「缺失」。</p>
     */
    private static String optStr(String path, String fallback) {
        try {
            JsonObject node = Holder.ROOT;
            String[] parts = path.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                if (!node.has(parts[i]) || !node.get(parts[i]).isJsonObject()) {
                    return fallback;
                }
                node = node.getAsJsonObject(parts[i]);
            }
            String leaf = parts[parts.length - 1];
            if (!node.has(leaf) || node.get(leaf).isJsonNull()) {
                return fallback;
            }
            String value = node.get(leaf).getAsString();
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // 密钥（keys 节） //

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

    // 自家服务（services 节） //

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

    /**
     * SakuraFrp 开放 API 基址（联机页 FRP 隧道管理）。
     *
     * <p>第三方服务地址单一真源在 {@code /assets/endpoints.json}，业务代码不写字面量。
     */
    public static String sakuraFrpApiBase() {
        return str("services.sakuraFrpApiBase");
    }

    /** SakuraFrp 官网（账号注册、访问密钥获取入口） */
    public static String sakuraFrpSiteUrl() {
        return str("services.sakuraFrpSiteUrl");
    }

    /** SakuraFrp 访问密钥页面（联机页「获取密钥」按钮打开） */
    public static String sakuraFrpTokenUrl() {
        return str("services.sakuraFrpTokenUrl");
    }

    /** 爱发电赞助页链接 */
    public static String sponsorUrl() {
        return str("services.sponsorUrl");
    }

    /** 色盲辅助矫正工具（ColorBlindOverlay.exe）的下载地址 */
    public static String colorBlindOverlayUrl() {
        return str("services.colorBlindOverlayUrl");
    }

    /**
     * 披风 URL 补丁 agent（{@code cape-url-patch-agent.jar}）的兜底下载地址。
     *
     * <p>正常路径是「从 exe 内置资源释放」，只有 exe 没打到该资源时才会用到本地址。
     * 允许为空串：为空即视为「不做联网兜底」，披风注入直接跳过。</p>
     */
    public static String capeAgentUrl() {
        return optStr("services.capeAgentUrl", "");
    }

    /**
     * 第三方（外置登录）皮肤站预设：每项 {@code {名称, Yggdrasil API 地址, 官网注册页}}。
     *
     * <p>地址单一真源在 {@code /assets/endpoints.json} 的 {@code services.thirdPartyPresets}，
     * 用户级覆盖（{@code ~/.starlight/endpoints.local.json}）同样生效——和其它第三方地址一样，
     * 页面里不写字面量。预设只用于填充「第三方登录」的地址下拉框，<b>选中不发起任何请求</b>；
     * 真正的网络请求由用户点击登录触发。登记见 docs/第三方声明第四节与 docs/隐私政策 3.3。
     *
     * <p>配置缺失或被本地覆盖写坏时返回空列表（页面退化为只能手动填写地址），不抛异常。
     */
    public static List<String[]> thirdPartyPresets() {
        List<String[]> presets = new ArrayList<>();
        try {
            JsonObject services = Holder.ROOT.getAsJsonObject("services");
            if (services == null || !services.has("thirdPartyPresets")) return presets;
            JsonElement node = services.get("thirdPartyPresets");
            if (node == null || !node.isJsonArray()) return presets;
            for (JsonElement el : node.getAsJsonArray()) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject entry = el.getAsJsonObject();
                String name = nonBlank(entry, "name");
                String api = nonBlank(entry, "api");
                if (name == null || api == null) continue;
                String register = nonBlank(entry, "register");
                presets.add(new String[]{name, api, register == null ? "" : register});
            }
        } catch (RuntimeException ignored) {
            // 预设读不出来不影响第三方登录本身：页面仍可手动填写 API 地址
            return presets;
        }
        return presets;
    }

    /** 取对象里的非空字符串字段；缺失、JSON null 或空白都返回 null */
    private static String nonBlank(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            String value = obj.get(key).getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
