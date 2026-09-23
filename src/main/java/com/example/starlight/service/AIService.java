/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;

import com.example.starlight.model.ActionResult;
import com.example.starlight.model.CallbackInterfaces;
import com.example.starlight.longcatapi.LongCatChat;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * AI 日志分析服务
 */
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);

    /** AI 分析错误日志（异步） */
    public static void aiAnalyzeLogAsync(String logFilePath,
                                          CallbackInterfaces.ProgressCallback onProgress,
                                          CallbackInterfaces.ResultCallback<AIAnalysisResult> callback) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                onProgress.onProgress(10, "正在读取日志文件...");
                File logFile = new File(logFilePath);
                if (!logFile.exists()) { callback.onError("文件不存在 " + logFilePath); return; }

                onProgress.onProgress(30, "正在调用 AI 分析...");
                String logContent = Files.readString(logFile.toPath(), StandardCharsets.UTF_8);
                String question = "请详细阅读这个Minecraft错误日志，有什么问题，回答请以json格式，直接给我json内容，格式严格按照json文件的格式，有zh-cn字段（是中文错误原因显示），还要有英文的错误原因显示\n\n日志内容如下：\n" + logContent;

                String response = LongCatChat.chat(question);
                response = removeMarkdownCodeBlocks(response);

                onProgress.onProgress(80, "正在解析分析结果...");
                String chineseReason = "", englishReason = "";
                try {
                    JsonObject json = new Gson().fromJson(response, JsonObject.class);
                    if (json.has("zh-cn")) chineseReason = json.get("zh-cn").getAsString();
                    if (json.has("en")) englishReason = json.get("en").getAsString();
                    if (json.has("english")) englishReason = json.get("english").getAsString();
                } catch (JsonSyntaxException ignored) {}

                String outputPath = "ai_response.json";
                exportResponseToJson(response, outputPath, 4);

                onProgress.onProgress(100, "分析完成");
                callback.onSuccess(new AIAnalysisResult(response, chineseReason, englishReason));
            } catch (IOException e) {
                callback.onError("AI 分析失败: " + e.getMessage());
            }
        });
    }

    /** AI 分析结果 */
    public static class AIAnalysisResult {
        public final String rawJson;
        public final String chineseReason;
        public final String englishReason;
        public AIAnalysisResult(String rawJson, String chineseReason, String englishReason) {
            this.rawJson = rawJson;
            this.chineseReason = chineseReason;
            this.englishReason = englishReason;
        }
    }

    private static String removeMarkdownCodeBlocks(String content) {
        if (content == null) return null;
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) trimmed = trimmed.substring(7).trim();
        else if (trimmed.startsWith("```")) trimmed = trimmed.substring(3).trim();
        if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        return trimmed;
    }

    private static void exportResponseToJson(String jsonContent, String filePath, int indent) {
        try {
            Object obj = new Gson().fromJson(jsonContent, Object.class);
            String pretty = new Gson().toJson(obj);
            Files.writeString(java.nio.file.Paths.get(filePath), pretty, StandardCharsets.UTF_8);
            log.info("AI analysis result saved to: {}", filePath);
        } catch (Exception ignored) {}
    }
}
