package com.example.starlight.longcatapi;

import com.example.starlight.config.AiConfig;
import com.example.starlight.util.DebugLog;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AI 对话客户端（OpenAI 兼容接口，用于分析崩溃 / 错误日志）。
 *
 * <p>服务地址、密钥与模型都来自「设置 → 高级设置 → AI 配置」（见 {@link AiConfig}），
 * <b>默认全部为空</b>：启动器不再内置任何密钥与默认模型，用户点一个预设服务商图标
 * 填好地址与模型、再贴自己的 API Key 即可。每次调用都重新读配置，改完无需重启；
 * 调试时可用系统属性覆盖：{@code -Dstarlight.ai.baseUrl=} / {@code -Dstarlight.ai.apiKey=} /
 * {@code -Dstarlight.ai.model=}。
 *
 * <p>请求体只发通用字段（model / messages / max_tokens / temperature / top_p / stream），
 * NVIDIA NIM 私有的思考参数只对它自己发 —— 别的服务商收到不认识的字段会直接 400。
 */
public class LongCatChat {

    private static final String SYSTEM_PROMPT = "You are a helpful assistant.";

    /** 单条消息字符上限：崩溃日志动辄几十万字符，这里截断，避免白白烧 token */
    private static final int MAX_MESSAGE_CHARS = 120_000;

    // ==================== 配置读取（每次调用实时取，保存后立刻生效） ====================

    private static String apiBase() {
        return AiConfig.normalizeBaseUrl(AiConfig.baseUrl());
    }

    private static String apiKey() {
        return AiConfig.apiKey();
    }

    private static String model() {
        return AiConfig.model();
    }

    /** 还没配好就别发请求了，直接抛一句用户看得懂的话 */
    private static void requireConfigured() throws IOException {
        if (apiBase().isEmpty() || model().isEmpty()) {
            throw new IOException(AiConfig.missingHint());
        }
    }

    /** 公共请求头：Key 为空时不发 Authorization（局域网本地推理服务一般不需要） */
    private static void applyHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("Content-Type", "application/json");
        String key = apiKey();
        if (!key.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("x-api-key", key);        // Anthropic 的 OpenAI 兼容层
        }
        if (apiBase().toLowerCase(java.util.Locale.ROOT).contains("anthropic")) {
            connection.setRequestProperty("anthropic-version", "2023-06-01");
        }
    }

    /** NVIDIA NIM / Nemotron 认这几个私有字段，别的服务商不认（会 400），所以只对它发 */
    private static boolean useNvidiaThinkingParams() {
        String base = apiBase().toLowerCase(java.util.Locale.ROOT);
        String m = model().toLowerCase(java.util.Locale.ROOT);
        return base.contains("nvidia") || m.contains("nemotron");
    }

    public static void main(String[] args) {
        // 通过命令行获取文件路径
        if (args.length < 1) {
            System.err.println("Pass arguments in this exact format: java LongCatChat <log file path>");
            System.exit(1);
        }
        String logFilePath = args[0];
    
        // 固定问题
        String baseQuestion = "请详细阅读这个Minecraft错误日志，有什么问题，回答请以json格式，直接给我json内容，格式严格按照json文件的格式，有zh-cn字段（是中文错误原因显示），还要有英文的错误原因显示";

        try {
            // 读取log日志文件
            String logContent = readFileContent(logFilePath);

            // 拼接问题
            String userQuestion = baseQuestion + "\n\n日志内容如下：\n" + logContent;

            // 5. 调用配置好的 AI 服务（地址 / Key / 模型见高级设置 → AI 配置）
            String response = chat(userQuestion);
            
            // 去除Markdown代码块标记
            response = removeMarkdownCodeBlocks(response);
            
            System.out.println("Problem cause (AI answer): " + response);
            
            // 导出AI回复到JSON文件（带缩进）
            String outputPath = "ai_response.json";
            exportResponseToJson(response, outputPath, 4); // 4空格缩进
            System.out.println("AI reply exported to: " + outputPath);
            
        } catch (IOException e) {
            System.err.println("Operation failed: " + e.getMessage());
        }
    }
    
    /**
     * 去除Markdown代码块标记（```json 和 ```）
     */
    private static String removeMarkdownCodeBlocks(String content) {
        if (content == null) return null;
        
        String trimmed = content.trim();
        
        // 去除开头的 ```json 或 ```
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7).trim();
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3).trim();
        }
        
        // 去除结尾的 ```
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        
        return trimmed;
    }
    
    // 读取log日志文件
    private static String readFileContent(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("File does not exist: " + filePath);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    public static String chat(String userMessage) throws IOException {
        requireConfigured();
        String url = apiBase() + AiConfig.CHAT_ENDPOINT;

        // 构建请求体
        String requestBody = buildRequestBody(userMessage);

        // 创建连接
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

        // 设置请求方法和超时处理
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);  // 连接超时 10 秒
        connection.setReadTimeout(120000);     // 获取log超时 60 秒

        // 设置请求头（Authorization 跟随配置的 Key，没填就不带）
        applyHeaders(connection);
        connection.setDoOutput(true); 

        // 发送请求体
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // 读取响应码
        int responseCode = connection.getResponseCode();
        DebugLog.http(true, "POST", url, 0, requestBody.length(), requestBody, -1);
        InputStream inputStream;
        if (responseCode >= 200 && responseCode < 300) {
            inputStream = connection.getInputStream();
        } else {
            inputStream = connection.getErrorStream();
        }

        // 读取响应内容
        StringBuilder responseBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }
        }

        String responseJson = responseBuilder.toString();
        DebugLog.http(false, null, url, responseCode, responseJson.length(), responseJson, -1);

        if (responseCode >= 200 && responseCode < 300) {
            return extractContentFromJson(responseJson);
        } else {
            throw new IOException("HTTP error " + responseCode + ": " + responseJson);
        }
    }

    private static String buildRequestBody(String userMessage) {
        return buildRequestBody(userMessage, false);
    }

    private static String buildRequestBody(String userMessage, boolean stream) {
        String text = userMessage == null ? "" : userMessage;
        if (text.length() > MAX_MESSAGE_CHARS) {
            text = text.substring(0, MAX_MESSAGE_CHARS) + "\n...(内容过长已截断)";
        }
        String escapedUserMessage = escapeJson(text);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{")
                .append("\"model\": \"").append(escapeJson(model())).append("\",")
                .append("\"messages\": [")
                .append("{\"role\": \"system\", \"content\": \"").append(SYSTEM_PROMPT).append("\"},")
                .append("{\"role\": \"user\", \"content\": \"").append(escapedUserMessage).append("\"}")
                .append("],")
                .append("\"max_tokens\": 8192,")
                .append("\"temperature\": 0.7,")
                .append("\"top_p\": 0.95,")
                .append("\"stream\": ").append(stream);
        if (useNvidiaThinkingParams()) {
            // NVIDIA Nemotron：先思考再回答，思考过程走 reasoning_content
            sb.append(",\"chat_template_kwargs\": {\"enable_thinking\": true}")
                    .append(",\"reasoning_budget\": 4096");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 流式对话：开启 {@code stream=true}，按 SSE 逐块把增量文本回调出去。
     *
     * <p>OpenAI 兼容的流式响应形如每行 {@code data: {...}}，末尾一行 {@code data: [DONE]}。
     * 每个 chunk 里正文在 {@code choices[0].delta.content}，推理模型的思考过程在
     * {@code delta.reasoning_content}（Nemotron 这类模型会先吐思考、再吐正文）。
     * 两类增量分别回调，调用方可自行决定怎么展示。
     *
     * @param onDelta 收到一段正文增量时回调（在调用线程上执行，通常是后台线程）
     * @param onReasoning 收到一段思考增量时回调，可为 null
     * @return 拼好的完整正文（思考过程不计入）
     */
    public static String chatStream(String userMessage, java.util.function.Consumer<String> onDelta,
                                    java.util.function.Consumer<String> onReasoning) throws IOException {
        requireConfigured();
        String url = apiBase() + AiConfig.CHAT_ENDPOINT;
        String requestBody = buildRequestBody(userMessage, true);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        // 流式：读超时只作用于「两次数据之间」的间隔，这里给足思考停顿的余量
        connection.setReadTimeout(60000);
        applyHeaders(connection);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        DebugLog.http(true, "POST", url, 0, requestBody.length(), requestBody, -1);
        if (responseCode < 200 || responseCode >= 300) {
            String err = "";
            try (InputStream es = connection.getErrorStream()) {
                if (es != null) {
                    err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
            throw new IOException("HTTP error " + responseCode + ": " + err);
        }

        StringBuilder answer = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || !line.startsWith("data:")) {
                    continue;   // SSE 的心跳/注释行直接跳过
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                // 思考增量优先判断：reasoning_content 里也含 "content"，但带下划线前缀不会误匹配
                String reasoning = extractFieldValue(payload, "reasoning_content");
                if (reasoning != null && !reasoning.isEmpty()) {
                    if (onReasoning != null) onReasoning.accept(reasoning);
                    continue;
                }
                String delta = extractFieldValue(payload, "content");
                if (delta != null && !delta.isEmpty()) {
                    answer.append(delta);
                    if (onDelta != null) onDelta.accept(delta);
                }
            }
        }
        return answer.toString();
    }

    //对字符串进行简单的 JSON 转义
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // 从响应的 JSON 字符串中提取字段值（如 choices[0].message.content）
    private static String extractContentFromJson(String json) {
        // 优先取最终回答 content；Nemotron 推理模型 content 为 null 时兜底取思考过程
        String content = extractFieldValue(json, "content");
        if (content == null || content.isEmpty()) {
            content = extractFieldValue(json, "reasoning_content");
        }
        return content == null || content.isEmpty() ? "无法解析回复内容" : content;
    }

    // 查找 "<field>":" 并解析其字符串值（处理 JSON 转义）
    private static String extractFieldValue(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start == -1) {
            return null;
        }
        start += marker.length();

        // 寻找结束的引号
        StringBuilder content = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                // 处理转义字符
                switch (c) {
                    case 'n' -> content.append('\n');
                    case 'r' -> content.append('\r');
                    case 't' -> content.append('\t');
                    case '"' -> content.append('"');
                    case '\\' -> content.append('\\');
                    case 'b' -> content.append('\b');
                    case 'f' -> content.append('\f');
                    default -> content.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break; // 内容结束
            } else {
                content.append(c);
            }
        }
        return content.toString();
    }
    
    // 导出AI回复到JSON文件，支持自定义缩进
    private static void exportResponseToJson(String response, String filePath, int indentSize) throws IOException {
        // 格式化JSON（添加缩进）
        String formattedJson = formatJsonWithIndent(response, indentSize);
        
        // 写入文件
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(filePath), StandardCharsets.UTF_8))) {
            writer.write(formattedJson);
        }
    }
    
    //格式化JSON字符串，添加缩进
    private static String formatJsonWithIndent(String json, int indentSize) {
        if (json == null || json.trim().isEmpty()) {
            return json;
        }
        
        StringBuilder result = new StringBuilder();
        int indentLevel = 0;
        boolean inString = false;
        boolean escaped = false;
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            
            if (escaped) {
                result.append(c);
                escaped = false;
                continue;
            }
            
            if (c == '\\') {
                result.append(c);
                escaped = true;
                continue;
            }
            
            if (c == '"') {
                result.append(c);
                inString = !inString;
                continue;
            }
            
            if (inString) {
                result.append(c);
                continue;
            }
            
            switch (c) {
                case '{', '[' -> {
                    result.append(c);
                    result.append('\n');
                    indentLevel++;
                    appendIndent(result, indentLevel, indentSize);
                }
                case '}', ']' -> {
                    result.append('\n');
                    indentLevel--;
                    appendIndent(result, indentLevel, indentSize);
                    result.append(c);
                }
                    
                case ',' -> {
                    result.append(c);
                    result.append('\n');
                    appendIndent(result, indentLevel, indentSize);
                    while (i + 1 < json.length() && json.charAt(i + 1) == ' ') {
                        i++;
                    }
                }
                case ':' -> {
                    result.append(c);
                    result.append(' ');
                }
                    
                default -> {
                    if (!Character.isWhitespace(c)) {
                        result.append(c);
                    }
                }
            }
        }
        
        return result.toString();
    }
    
    //添加缩进空格
    private static void appendIndent(StringBuilder sb, int level, int size) {
        for (int i = 0; i < level * size; i++) {
            sb.append(' ');
        }
    }
}
