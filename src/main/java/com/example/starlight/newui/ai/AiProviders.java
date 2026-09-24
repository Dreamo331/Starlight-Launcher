package com.example.starlight.newui.ai;

import com.example.starlight.config.AiConfig;

import javafx.scene.image.Image;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预设 AI 服务商目录：服务商名、OpenAI 兼容基址、默认模型与内置常见模型。
 *
 * <p>图标全部取自 {@code src/main/resources/logo}（其中 groq / minimax / openrouter 原本是
 * .ico，JavaFX 解不了 ico，已按原始图标转成同名的 .png）。
 *
 * <p>点一个服务商图标即把「API 地址 + 默认模型」写进配置，用户只需要再贴自己的 API Key；
 * 「选择模型」伪弹窗里则用 {@link AiModelCatalog} 去服务商在线拉完整模型列表，
 * 拉不到时回落到这里的 {@link Provider#models()} 常见模型。
 *
 * <p>表格只是「省得手打」的模板，地址随时可以在高级设置里手动改（比如走中转 / 本地 Ollama）。
 */
public final class AiProviders {

    /**
     * 一个预设服务商。
     *
     * @param id           稳定标识（写入配置 {@link AiConfig#KEY_PROVIDER}）
     * @param name         界面上显示的名字
     * @param baseUrl      OpenAI 兼容基址（不含 /chat/completions）
     * @param defaultModel 点击图标时自动填入的模型
     * @param logo         {@code /logo} 下的图标文件名
     * @param models       内置常见模型（在线获取失败时列表里显示这些）
     */
    public record Provider(String id, String name, String baseUrl, String defaultModel,
                           String logo, List<String> models) {

        /** classpath 上的图标路径 */
        public String logoPath() {
            return "/logo/" + logo;
        }
    }

    private AiProviders() {
    }

    /** 预设服务商（顺序即界面上的排列顺序：海外通用 → 国内常用） */
    public static final List<Provider> ALL = List.of(
            new Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "openai.png",
                    List.of("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "o4-mini")),
            new Provider("anthropic", "Claude", "https://api.anthropic.com/v1", "claude-3-5-haiku-latest",
                    "anthropic.png",
                    List.of("claude-3-5-haiku-latest", "claude-3-5-sonnet-latest",
                            "claude-3-7-sonnet-latest", "claude-sonnet-4-5")),
            new Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                    "gemini-2.0-flash", "gemini.png",
                    List.of("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro")),
            new Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat",
                    "deepseek.png",
                    List.of("deepseek-chat", "deepseek-reasoner")),
            new Provider("moonshot", "Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k",
                    "moonshot.png",
                    List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k",
                            "kimi-k2-0711-preview")),
            new Provider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
                    "zhipu.png",
                    List.of("glm-4-flash", "glm-4-air", "glm-4-plus", "glm-4-long", "glm-z1-air")),
            new Provider("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen-plus", "qwen.png",
                    List.of("qwen-plus", "qwen-turbo", "qwen-max", "qwen-long",
                            "qwen2.5-72b-instruct")),
            new Provider("doubao", "豆包", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k",
                    "doubao.png",
                    List.of("doubao-pro-32k", "doubao-pro-128k", "doubao-lite-32k")),
            new Provider("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1",
                    "Qwen/Qwen2.5-7B-Instruct", "siliconflow.png",
                    List.of("Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-72B-Instruct",
                            "deepseek-ai/DeepSeek-V3")),
            new Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                    "openai/gpt-4o-mini", "openrouter.png",
                    List.of("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet",
                            "google/gemini-flash-1.5", "deepseek/deepseek-chat")),
            new Provider("groq", "Groq", "https://api.groq.com/openai/v1",
                    "llama-3.3-70b-versatile", "groq.png",
                    List.of("llama-3.3-70b-versatile", "llama-3.1-8b-instant",
                            "mixtral-8x7b-32768")),
            new Provider("minimax", "MiniMax", "https://api.minimaxi.com/v1", "MiniMax-Text-01",
                    "minimax.png",
                    List.of("MiniMax-Text-01", "MiniMax-M1", "abab6.5s-chat")),
            new Provider("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1",
                    "hunyuan-turbos-latest", "hunyuan.png",
                    List.of("hunyuan-turbos-latest", "hunyuan-turbo-latest", "hunyuan-large-latest")),
            new Provider("wenxin", "文心一言", "https://qianfan.baidubce.com/v2",
                    "ernie-4.0-8k", "wenxin.png",
                    List.of("ernie-4.0-8k", "ernie-4.0-turbo-8k", "ernie-speed-128k")),
            new Provider("spark", "讯飞星火", "https://spark-api-open.xf-yun.com/v1", "generalv3.5",
                    "spark.png",
                    List.of("generalv3.5", "4.0Ultra", "max-32k", "lite")),
            new Provider("stepfun", "阶跃星辰", "https://api.stepfun.com/v1", "step-1-8k",
                    "stepfun.png",
                    List.of("step-1-8k", "step-1-32k", "step-2-16k")),
            new Provider("baichuan", "百川智能", "https://api.baichuan-ai.com/v1", "Baichuan4",
                    "baichuan.png",
                    List.of("Baichuan4", "Baichuan3-Turbo", "Baichuan3-Turbo-128k")));

    // ==================== 查询 ====================

    /** 按 id 取服务商；没有匹配返回 null */
    public static Provider byId(String id) {
        if (id == null || id.isBlank()) return null;
        for (Provider p : ALL) {
            if (p.id().equalsIgnoreCase(id.trim())) return p;
        }
        return null;
    }

    /**
     * 按 API 地址反查服务商：先比完整基址，再退化到比域名
     * （用户可能自己补了路径，如 {@code https://api.deepseek.com/v1/}）。
     */
    public static Provider matchByBaseUrl(String baseUrl) {
        String url = AiConfig.normalizeBaseUrl(baseUrl).toLowerCase(Locale.ROOT);
        if (url.isEmpty()) return null;
        for (Provider p : ALL) {
            if (AiConfig.normalizeBaseUrl(p.baseUrl()).toLowerCase(Locale.ROOT).equals(url)) return p;
        }
        String host = hostOf(url);
        if (host.isEmpty()) return null;
        for (Provider p : ALL) {
            if (hostOf(p.baseUrl()).equals(host)) return p;
        }
        return null;
    }

    private static String hostOf(String url) {
        try {
            String u = url;
            int scheme = u.indexOf("://");
            if (scheme >= 0) u = u.substring(scheme + 3);
            int slash = u.indexOf('/');
            if (slash >= 0) u = u.substring(0, slash);
            return u.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** 某个地址对应的内置模型列表（匹配不到服务商时返回全部预设模型） */
    public static List<String> presetModels(String baseUrl) {
        Provider p = matchByBaseUrl(baseUrl);
        if (p != null) return p.models();
        return allPresetModels();
    }

    /** 所有服务商的预设模型（去重，保持登记顺序） */
    public static List<String> allPresetModels() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (Provider p : ALL) set.addAll(p.models());
        return new ArrayList<>(set);
    }

    // ==================== 图标 ====================

    /** 已解码的图标缓存：文件名 → Image（缺失记 null，避免反复读盘） */
    private static final Map<String, Image> LOGO_CACHE = new ConcurrentHashMap<>();

    /**
     * 读取 {@code /logo} 下的服务商图标。
     *
     * @return 图片；文件缺失或解码失败返回 null（调用方回退到纯文字，不显示裂图）
     */
    public static Image logo(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        Image cached = LOGO_CACHE.get(fileName);
        if (cached != null) return cached;
        try (InputStream in = AiProviders.class.getResourceAsStream("/logo/" + fileName)) {
            if (in == null) {
                LOGO_CACHE.put(fileName, null);
                return null;
            }
            Image img = new Image(in);
            if (img.isError() || img.getWidth() <= 0) {
                LOGO_CACHE.put(fileName, null);
                return null;
            }
            LOGO_CACHE.put(fileName, img);
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}
