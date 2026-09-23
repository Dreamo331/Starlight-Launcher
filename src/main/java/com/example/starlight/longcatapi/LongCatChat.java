package com.example.starlight.longcatapi;

import com.example.starlight.config.Endpoints;
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

public class LongCatChat {

    // 硬编码配置（NVIDIA NIM API，OpenAI 兼容接口）
    // 可通过系统属性覆盖：-Dstarlight.ai.apiKey=<key> / -Dstarlight.ai.baseUrl=<url> / -Dstarlight.ai.model=<model>
    private static final String API_KEY = System.getProperty("starlight.ai.apiKey",
            Endpoints.nvidiaNimApiKey());
    private static final String BASE_URL = System.getProperty("starlight.ai.baseUrl",
            "https://integrate.api.nvidia.com/v1");
    private static final String MODEL = System.getProperty("starlight.ai.model",
            "nvidia/nemotron-3.5-lightning-30b-a3b");
    private static final String CHAT_ENDPOINT = "/chat/completions";

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

            // 5. 调用 NVIDIA AI API
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
        // 构建请求体
        String requestBody = buildRequestBody(userMessage);

        // 创建连接
        URL url = new URL(BASE_URL + CHAT_ENDPOINT);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        // 设置请求方法和超时处理
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);  // 连接超时 10 秒
        connection.setReadTimeout(120000);     // 获取log超时 60 秒

        // 设置请求头
        connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true); 

        // 发送请求体
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // 读取响应码
        int responseCode = connection.getResponseCode();
        DebugLog.http(true, "POST", BASE_URL + CHAT_ENDPOINT, 0, requestBody.length(), requestBody, -1);
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
        DebugLog.http(false, null, BASE_URL + CHAT_ENDPOINT, responseCode, responseJson.length(), responseJson, -1);

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
        String escapedUserMessage = escapeJson(userMessage);
        return "{"
                + "\"model\": \"" + MODEL + "\","
                + "\"messages\": ["
                + "{\"role\": \"system\", \"content\": \"You are a helpful assistant.\"},"
                + "{\"role\": \"user\", \"content\": \"" + escapedUserMessage + "\"}"
                + "],"
                + "\"max_tokens\": 8192,"
                + "\"temperature\": 0.7,"
                + "\"top_p\": 0.95,"
                + "\"stream\": " + stream + ","
                + "\"chat_template_kwargs\": {\"enable_thinking\": true},"
                + "\"reasoning_budget\": 4096"
                + "}";
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
        String requestBody = buildRequestBody(userMessage, true);

        URL url = new URL(BASE_URL + CHAT_ENDPOINT);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        // 流式：读超时只作用于「两次数据之间」的间隔，这里给足思考停顿的余量
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = connection.getResponseCode();
        DebugLog.http(true, "POST", BASE_URL + CHAT_ENDPOINT, 0, requestBody.length(), requestBody, -1);
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
