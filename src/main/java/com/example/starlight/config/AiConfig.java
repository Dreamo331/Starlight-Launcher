package com.example.starlight.config;

import com.example.starlight.main.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 服务配置（设置 → 高级设置 → AI 配置）。
 *
 * <p>启动器里的 AI 只干一件事：<b>分析错误脚本</b>——把崩溃日志 / 错误日志丢给大模型，
 * 让它给出中文错误原因与解决步骤（见 {@code AIDiagnosisPanel} 与 {@code AIService}）。
 *
 * <p>因为每个人用的服务商都不一样，这里<b>默认全部为空</b>：不带内置密钥、不带默认模型，
 * 由用户在高级设置里点一个预设服务商（自动填地址 + 默认模型）再贴自己的 API Key。
 * 只有地址与模型都填了才算配置完成；Key 允许为空，方便对接局域网里的本地推理服务。
 *
 * <p>键名沿用仓库里已有的 {@code AiApi} / {@code Aiapikey}（历史版本已在使用），
 * 新增的只有模型与预设服务商两项。读取顺序：客户端配置（starlight-client.ini）→
 * 启动器配置（starlight.ini，兼容早期写入）→ 系统属性覆盖（{@code -Dstarlight.ai.xxx}）。
 */
public final class AiConfig {

    /** API 地址：OpenAI 兼容接口基址，如 {@code https://api.deepseek.com/v1} */
    public static final String KEY_BASE_URL = "AiApi";

    /** API Key（各服务商控制台申请） */
    public static final String KEY_API_KEY = "Aiapikey";

    /** 模型名，如 {@code deepseek-chat} */
    public static final String KEY_MODEL = "AiModel";

    /** 选中的预设服务商 id（见 {@code com.example.starlight.newui.ai.AiProviders}） */
    public static final String KEY_PROVIDER = "AiProvider";

    /** 聊天的接口后缀（OpenAI 兼容） */
    public static final String CHAT_ENDPOINT = "/chat/completions";

    /** 模型列表接口后缀（OpenAI 兼容） */
    public static final String MODELS_ENDPOINT = "/models";

    private AiConfig() {
    }

    // ==================== 读取 ====================

    private static String read(String key) {
        try {
            Map<String, String> client = ConfigManager.readClientConfig();
            String v = client.get(key);
            if (v != null && !v.isBlank()) return v.trim();
        } catch (Exception ignored) {
            // 读不到客户端配置（离屏预览 / 首次启动）时继续走下一级
        }
        String legacy = StarlightConfig.get(key);   // 兼容早期写在 starlight.ini 的同名键
        return legacy == null ? "" : legacy.trim();
    }

    /** 系统属性优先（调试用：-Dstarlight.ai.baseUrl=...） */
    private static String property(String name, String fallback) {
        String v = System.getProperty(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** 未配置时返回空串 */
    public static String baseUrl() {
        return property("starlight.ai.baseUrl", read(KEY_BASE_URL));
    }

    /** 未配置时返回空串（本地推理服务可以留空） */
    public static String apiKey() {
        return property("starlight.ai.apiKey", read(KEY_API_KEY));
    }

    /** 未配置时返回空串 */
    public static String model() {
        return property("starlight.ai.model", read(KEY_MODEL));
    }

    /** 配置文件里选中的预设服务商 id，没选过返回空串 */
    public static String providerId() {
        return read(KEY_PROVIDER);
    }

    /** 地址与模型都已填写（Key 允许为空） */
    public static boolean isConfigured() {
        return !baseUrl().isEmpty() && !model().isEmpty();
    }

    /** 未配置时直接展示给用户的提示（不含敏感信息） */
    public static String missingHint() {
        boolean noUrl = baseUrl().isEmpty();
        boolean noModel = model().isEmpty();
        if (noUrl && noModel) {
            return "尚未配置 AI 服务：请到「设置 → 高级设置 → AI 配置」点一个服务商图标并填写 API Key";
        }
        if (noUrl) {
            return "尚未填写 AI 服务的 API 地址：请到「设置 → 高级设置 → AI 配置」补全";
        }
        return "尚未选择 AI 模型：请到「设置 → 高级设置 → AI 配置」点「选择模型」挑一个";
    }

    /**
     * 把用户填的地址规范化成「基址」：去掉结尾斜杠，并容忍把整个接口地址粘进来的情况
     * （如 {@code https://api.deepseek.com/v1/chat/completions} → {@code https://api.deepseek.com/v1}）。
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) return "";
        String url = raw.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        for (String suffix : new String[]{CHAT_ENDPOINT, MODELS_ENDPOINT}) {
            if (url.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
                url = url.substring(0, url.length() - suffix.length());
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            }
        }
        return url;
    }

    /** 当前生效配置的快照（调试 / 日志用；不含 Key 本体） */
    public static Map<String, String> snapshot() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("baseUrl", baseUrl());
        map.put("model", model());
        map.put("provider", providerId());
        map.put("apiKey", apiKey().isEmpty() ? "" : "***");
        return map;
    }
}
