package com.example.starlight.newui.ai;

import com.example.starlight.config.AiConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型列表获取：向服务商的 OpenAI 兼容 {@code /models} 接口要一份可用模型清单。
 *
 * <p>用于「选择模型」伪弹窗里的「从服务商获取模型列表」按钮。各家返回结构基本一致
 * （{@code {"data":[{"id":"..."}]}}），但也有把模型塞在 {@code models}/{@code result}
 * 里、或直接返回数组的，所以解析走「递归找 id/model/name」这条路；完全解析不出来时
 * 再用正则从原文里抠 {@code "id"}，尽量不出现「有数据却显示为空」。
 *
 * <p>顺手过滤掉明显不是聊天模型的条目（向量化 / 语音 / 绘图 / 重排），
 * 免得 OpenRouter、SiliconFlow 这种上百个模型的列表里全是噪声。
 */
public final class AiModelCatalog {

    /** 连接 / 读取超时（毫秒）：模型列表是个小请求，不用等太久 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    /** 明显不属于对话模型的 id 片段（小写匹配） */
    private static final String[] NON_CHAT_MARKERS = {
            "embed", "rerank", "whisper", "tts", "dall-e", "moderation", "stable-diffusion",
            "bge-", "audio", "speech", "ocr", "video-"
    };

    private AiModelCatalog() {
    }

    /**
     * 拉取模型列表（阻塞，调用方放后台线程）。
     *
     * @param baseUrl API 基址（会自动规范化，允许带结尾斜杠或误粘的接口后缀）
     * @param apiKey  API Key，可为空（本地推理服务不需要）
     * @return 去重并按名称排序的模型 id；服务商没返回任何模型时抛 IOException
     */
    public static List<String> fetch(String baseUrl, String apiKey) throws IOException {
        String base = AiConfig.normalizeBaseUrl(baseUrl);
        if (base.isEmpty()) {
            throw new IOException("尚未填写 API 地址");
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(base + AiConfig.MODELS_ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                String key = apiKey.trim();
                conn.setRequestProperty("Authorization", "Bearer " + key);
                // Anthropic 的 OpenAI 兼容层同时认 x-api-key + 版本头
                conn.setRequestProperty("x-api-key", key);
            }
            String lower = base.toLowerCase(Locale.ROOT);
            if (lower.contains("anthropic")) {
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            } else if (lower.contains("openrouter")) {
                conn.setRequestProperty("HTTP-Referer", "https://github.com/Starlight-Launcher");
                conn.setRequestProperty("X-Title", "Starlight Launcher");
            }

            int code = conn.getResponseCode();
            String body = readAll(code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + (body.isBlank() ? "" : "：" + shorten(body)));
            }

            List<String> models = parseModelIds(body);
            if (models.isEmpty()) {
                throw new IOException("服务商没有返回模型列表（可直接手动输入模型名）");
            }
            return models;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== 解析 ====================

    /** 从响应正文里解析模型 id（Gson 结构化解析 → 正则兜底） */
    static List<String> parseModelIds(String body) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (body == null || body.isBlank()) return new ArrayList<>();

        try {
            collect(JsonParser.parseString(body), ids);
        } catch (Exception ignored) {
            // 不是合法 JSON（HTML 错误页 / 被网关改写）时走下面的正则兜底
        }
        if (ids.isEmpty()) {
            Matcher m = Pattern.compile("\"(?:id|model|name)\"\\s*:\\s*\"([^\"]{1,120})\"").matcher(body);
            while (m.find()) ids.add(stripPrefix(m.group(1).trim()));
        }

        List<String> chat = new ArrayList<>();
        for (String id : ids) {
            if (!id.isEmpty() && isLikelyChatModel(id)) chat.add(id);
        }
        // 全是非聊天模型（过滤光了）时退回未过滤的原始结果，至少让用户能看到东西
        List<String> result = chat.isEmpty() ? new ArrayList<>(ids) : chat;
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /** 递归找模型条目：对象里出现 id/model/name 就收下，否则继续往 data/models/result 等字段里钻 */
    private static void collect(JsonElement el, LinkedHashSet<String> out) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonPrimitive()) return;
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) collect(e, out);
            return;
        }
        JsonObject o = el.getAsJsonObject();
        for (String field : new String[]{"id", "model", "model_name", "name"}) {
            if (o.has(field) && o.get(field).isJsonPrimitive()) {
                String v = o.get(field).getAsString().trim();
                if (!v.isEmpty()) {
                    out.add(stripPrefix(v));
                    return;
                }
            }
        }
        for (String field : new String[]{"data", "models", "result", "items", "list"}) {
            if (o.has(field) && !o.get(field).isJsonNull()) collect(o.get(field), out);
        }
    }

    /** Gemini 的 OpenAI 兼容层返回 {@code models/gemini-2.0-flash}，调用时要的是后半截 */
    private static String stripPrefix(String id) {
        String v = id;
        if (v.toLowerCase(Locale.ROOT).startsWith("models/")) v = v.substring("models/".length());
        return v.trim();
    }

    /** 粗略判断是不是对话模型（向量 / 语音 / 绘图模型对崩溃分析没用，列表里不显示） */
    static boolean isLikelyChatModel(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        for (String marker : NON_CHAT_MARKERS) {
            if (lower.contains(marker)) return false;
        }
        return true;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String shorten(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 180 ? flat : flat.substring(0, 180) + "…";
    }
}
