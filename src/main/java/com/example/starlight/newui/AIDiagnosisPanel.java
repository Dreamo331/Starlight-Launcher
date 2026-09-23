/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.crash.CrashDiagnosticData;
import com.example.starlight.longcatapi.LongCatChat;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 诊断面板 —— 集成在启动器窗口内（替代独立 Stage 的 AIDiagnosisWindow）。
 * 调用 LongCatChat API 分析崩溃日志，以 WebView 渲染 Markdown 结果。
 * 底层已切换为 NVIDIA NIM API（OpenAI 兼容接口）。
 */
public class AIDiagnosisPanel extends VBox {

    private final CrashDiagnosticData crashData;
    private final Consumer<String> toast;   // 提示回调（LauncherView.showToast）
    private final Runnable closeHandler;    // 关闭面板回调（LauncherView.closeModalPanel）

    private Label statusLabel;
    private ProgressIndicator loadingIndicator;
    private WebView webView;
    private Button retryBtn;
    private Button copyBtn;
    private String lastAiResponse;
    private volatile boolean analyzing = false;

    // ===== 流式接收 =====
    /** 正文增量（delta.content） */
    private final StringBuilder streamAnswer = new StringBuilder();
    /** 思考增量（delta.reasoning_content，推理模型会先吐这段） */
    private final StringBuilder streamReasoning = new StringBuilder();
    /** 上次重渲染 WebView 的时刻，用于节流 */
    private long lastRenderAt = 0L;
    private boolean renderPending = false;

    public AIDiagnosisPanel(CrashDiagnosticData crashData, Consumer<String> toast, Runnable closeHandler) {
        super(10);
        this.crashData = crashData;
        this.toast = toast;
        this.closeHandler = closeHandler;
        setPadding(new Insets(10, 4, 0, 4));

        // 顶部：状态 + 加载指示 + 操作按钮
        HBox topRow = new HBox(10);
        topRow.setAlignment(Pos.CENTER_LEFT);
        statusLabel = new Label("准备分析...");
        statusLabel.getStyleClass().add("modal-text-title");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(18, 18);
        loadingIndicator.setVisible(false);
        retryBtn = AppIcons.button("refresh", "重新分析");
        retryBtn.getStyleClass().add("btn-primary");
        retryBtn.setStyle("-fx-padding: 4 14; -fx-font-size: 12px; -fx-cursor: hand;");
        retryBtn.setOnAction(e -> startAnalysis());
        copyBtn = AppIcons.button("copy", "复制结果");
        copyBtn.getStyleClass().add("btn-primary");
        copyBtn.setStyle("-fx-padding: 4 14; -fx-font-size: 12px; -fx-cursor: hand;");
        copyBtn.setOnAction(e -> copyResult());
        Button closeBtn = AppIcons.button("close", "关闭");
        closeBtn.getStyleClass().add("modal-btn-cancel");
        // 与同排按钮（4 14 / 12px）保持一致：.modal-btn-cancel 是弹窗底栏尺寸，直接用会比同排高一截
        closeBtn.setStyle("-fx-padding: 4 14; -fx-font-size: 12px;");
        closeBtn.setOnAction(e -> closeHandler.run());
        topRow.getChildren().addAll(statusLabel, loadingIndicator, retryBtn, copyBtn, closeBtn);

        // 提示文案
        Label subtitle = new Label("将崩溃信息发送至 NVIDIA AI 进行分析，结果以 Markdown 渲染");
        subtitle.getStyleClass().add("modal-hint");

        // WebView 结果区
        webView = new WebView();
        webView.setPrefHeight(400);
        VBox.setVgrow(webView, Priority.ALWAYS);
        webView.getEngine().loadContent(
                "<html><body style='font-family:sans-serif;color:#888;padding:40px;text-align:center;'>" +
                        "<h2>点击上方「重新分析」开始诊断</h2>" +
                        "</body></html>", "text/html");
        // 每次重载完成后再滚到底部，模拟「逐字输出 + 自动跟随」
        webView.getEngine().getLoadWorker().stateProperty().addListener((o, ov, nv) -> {
            if (nv == javafx.concurrent.Worker.State.SUCCEEDED) {
                try {
                    webView.getEngine().executeScript(
                            "window.scrollTo(0, document.body.scrollHeight);");
                } catch (Exception ignored) {
                }
            }
        });

        getChildren().addAll(topRow, subtitle, webView);

        // 面板显示时自动开始分析
        startAnalysis();
    }

    // ================================================================
    //  分析流程
    // ================================================================

    private void startAnalysis() {
        if (analyzing) return;
        analyzing = true;

        setLoadingState(true, "正在连接 AI...");
        lastAiResponse = null;
        synchronized (streamAnswer) { streamAnswer.setLength(0); }
        synchronized (streamReasoning) { streamReasoning.setLength(0); }
        renderStream(true);

        // 构建问题
        String question = buildQuestion();

        // 后台线程流式调用 AI：每收到一段增量就追加并（节流地）刷新界面
        new Thread(() -> {
            try {
                String full = LongCatChat.chatStream(question,
                        delta -> {
                            synchronized (streamAnswer) { streamAnswer.append(delta); }
                            scheduleStreamRender();
                        },
                        reasoning -> {
                            synchronized (streamReasoning) { streamReasoning.append(reasoning); }
                            scheduleStreamRender();
                        });
                Platform.runLater(() -> {
                    lastAiResponse = full;
                    setLoadingState(false, "AI 诊断完成");
                    renderStream(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    webView.getEngine().loadContent(
                            "<html><body style='font-family:sans-serif;color:#c0392b;padding:40px;'>" +
                                    "<h2>AI 诊断失败</h2><p>" + escapeHtml(e.getMessage()) + "</p>" +
                                    "<p>请检查网络后点击「重新分析」重试</p>" +
                                    "</body></html>", "text/html");
                    setLoadingState(false, "分析失败");
                });
            } finally {
                analyzing = false;
            }
        }, "ai-diagnosis").start();
    }

    /**
     * 节流地安排一次界面刷新。
     *
     * <p>WebView 的 {@code loadContent} 每次都要重建整篇文档，逐 token 刷新会卡；
     * 这里限制到最多约 8 次/秒，既够「实时」的观感，又不会拖慢接收。
     */
    private void scheduleStreamRender() {
        synchronized (this) {
            if (renderPending || System.currentTimeMillis() - lastRenderAt < 120) return;
            renderPending = true;
        }
        Platform.runLater(() -> {
            synchronized (this) {
                renderPending = false;
                lastRenderAt = System.currentTimeMillis();
            }
            renderStream(analyzing);
        });
    }

    /** 把当前已接收的内容渲染到 WebView；streaming=true 时带闪烁光标 */
    private void renderStream(boolean streaming) {
        String answer, reasoning;
        synchronized (streamAnswer) { answer = streamAnswer.toString(); }
        synchronized (streamReasoning) { reasoning = streamReasoning.toString(); }

        String body;
        if (!answer.isEmpty()) {
            body = answer;
        } else if (!reasoning.isEmpty()) {
            // Nemotron 这类推理模型会先输出思考过程，正文稍后才到
            body = "*正在思考…*\n\n" + reasoning;
        } else {
            body = "*正在等待 AI 返回内容…*";
        }
        if (streaming) {
            body = body + "\n\n<span class=\"typing\">▌</span>";
        }
        webView.getEngine().loadContent(markdownToHtml(body), "text/html");
    }

    /** 构建发送给 AI 的问题 —— 包含崩溃摘要信息 */
    private String buildQuestion() {
        StringBuilder sb = new StringBuilder();
        sb.append("请分析以下 Minecraft 游戏崩溃信息，请用 Markdown 格式回复，包含以下部分：\n\n");
        sb.append("## 1. 错误概述\n简要描述发生了什么错误。\n\n");
        sb.append("## 2. 可能原因\n列出可能导致此崩溃的原因（列表形式）。\n\n");
        sb.append("## 3. 解决方案\n给出详细的解决步骤（列表形式）。\n\n");
        sb.append("## 4. 相关建议\n其他有用的建议或注意事项。\n\n");
        sb.append("---\n\n");
        sb.append("**崩溃信息如下**\n\n");

        if (crashData.getErrorCode() != null) {
            sb.append("- **错误码**: ").append(crashData.getErrorCode().getCode())
                    .append(" (").append(crashData.getErrorCode().getDisplayName()).append(")\n");
        }
        sb.append("- **退出码**: ").append(crashData.getExitCode()).append("\n");
        if (crashData.getGameVersion() != null) {
            sb.append("- **游戏版本**: ").append(crashData.getGameVersion()).append("\n");
        }
        if (crashData.getLoaderType() != null) {
            sb.append("- **加载器**: ").append(crashData.getLoaderType()).append("\n");
        }
        if (crashData.getJavaVersion() != null) {
            sb.append("- **Java**: ").append(crashData.getJavaVersion()).append("\n");
        }
        if (crashData.getMaxMemory() > 0) {
            sb.append("- **最大内存**: ").append(crashData.getMaxMemory()).append(" MB\n");
        }
        if (crashData.getOsInfo() != null) {
            sb.append("- **操作系统**: ").append(crashData.getOsInfo()).append("\n");
        }
        sb.append("\n");

        if (crashData.getErrorDescription() != null) {
            sb.append("### 错误详情\n```\n");
            String desc = crashData.getErrorDescription();
            if (desc.length() > 2000) {
                desc = desc.substring(0, 2000) + "\n...(截断)";
            }
            sb.append(desc).append("\n```\n\n");
        }
        if (crashData.getCrashReportContent() != null) {
            sb.append("### 崩溃报告（部分）\n```\n");
            String report = crashData.getCrashReportContent();
            if (report.length() > 3000) {
                report = report.substring(0, 3000) + "\n...(截断)";
            }
            sb.append(report).append("\n```\n");
        }
        return sb.toString();
    }

    // ================================================================
    //  辅助方法
    // ================================================================

    private void copyResult() {
        if (lastAiResponse == null || lastAiResponse.isEmpty()) {
            toast.accept("暂无可用结果");
            return;
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(lastAiResponse);
        clipboard.setContent(content);
        toast.accept("AI 诊断结果已复制到剪贴板");
    }

    private void setLoadingState(boolean loading, String status) {
        loadingIndicator.setVisible(loading);
        statusLabel.setText(status);
        retryBtn.setDisable(loading);
    }

    /**
     * 简单的 Markdown 转 HTML 转换器（复用 AIDiagnosisWindow 实现）
     * 支持：标题、粗体、斜体、行内代码、代码块、无序列表、有序列表、分隔线、段落
     */
    static String markdownToHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";

        String html = markdown;

        // 1. 转义 HTML 特殊字符（但保留代码块内容稍后单独处理）
        html = escapeHtml(html);

        // 2. 处理代码块（```...```）—— 必须在其他处理之前
        html = convertCodeBlocks(html);

        // 3. 处理分隔线
        html = html.replaceAll("(?m)^\\s*---\\s*$", "<hr>");

        // 4. 处理标题
        html = html.replaceAll("(?m)^######\\s+(.+)$", "<h6>$1</h6>");
        html = html.replaceAll("(?m)^#####\\s+(.+)$", "<h5>$1</h5>");
        html = html.replaceAll("(?m)^####\\s+(.+)$", "<h4>$1</h4>");
        html = html.replaceAll("(?m)^###\\s+(.+)$", "<h3>$1</h3>");
        html = html.replaceAll("(?m)^##\\s+(.+)$", "<h2>$1</h2>");
        html = html.replaceAll("(?m)^#\\s+(.+)$", "<h1>$1</h1>");

        // 5. 处理行内格式
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        html = html.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "<em>$1</em>");
        html = html.replaceAll("`([^`]+)`", "<code>$1</code>");

        // 6. 处理无序列表
        html = convertUnorderedLists(html);

        // 7. 处理有序列表
        html = convertOrderedLists(html);

        // 8. 处理段落（连续文本分行）
        html = convertParagraphs(html);

        // 9. 换行处理
        html = html.replaceAll("\\n", "<br>");

        return "<html><head><meta charset='utf-8'><style>" +
                "body{font-family:'Microsoft YaHei','Segoe UI',sans-serif;" +
                "padding:20px;line-height:1.7;color:#333;font-size:14px;}" +
                "h1,h2,h3,h4,h5,h6{color:#2c3e50;margin:16px 0 8px 0;}" +
                "h1{font-size:22px;border-bottom:2px solid #eee;padding-bottom:6px;}" +
                "h2{font-size:19px;border-bottom:1px solid #eee;padding-bottom:4px;}" +
                "h3{font-size:17px;}h4{font-size:15px;}" +
                "code{background:#f5f5f5;padding:2px 6px;border-radius:3px;" +
                "font-family:'Consolas','Courier New',monospace;font-size:13px;color:#e74c3c;}" +
                "pre{background:#2b2b2b;padding:12px;border-radius:6px;overflow-x:auto;}" +
                "pre code{background:transparent;color:#f8f8f2;padding:0;font-size:13px;}" +
                "hr{border:none;border-top:1px solid #ddd;margin:16px 0;}" +
                "ul,ol{padding-left:24px;margin:8px 0;}" +
                "li{margin:4px 0;}" +
                "strong{color:#c0392b;}" +
                "em{color:#7f8c8d;}" +
                ".ai-paragraph{margin:8px 0;}" +
                // 流式输出时的闪烁光标
                ".typing{color:#3b82f6;font-weight:700;" +
                "animation:caret 1s steps(2,start) infinite;}" +
                "@keyframes caret{to{visibility:hidden;}}" +
                "</style></head><body>" + html + "</body></html>";
    }

    private static String escapeHtml(String text) {
        text = text.replace("```", "\u0000CODEBLOCK\u0000");
        text = text.replace("&", "&amp;");
        text = text.replace("<", "&lt;");
        text = text.replace(">", "&gt;");
        text = text.replace("\"", "&quot;");
        text = text.replace("\u0000CODEBLOCK\u0000", "```");
        return text;
    }

    private static String convertCodeBlocks(String html) {
        StringBuilder sb = new StringBuilder();
        int lastIdx = 0;
        int idx;
        int blockCount = 0;
        while ((idx = html.indexOf("```", lastIdx)) != -1) {
            sb.append(html, lastIdx, idx);
            if (blockCount % 2 == 0) {
                sb.append("<pre><code>");
            } else {
                sb.append("</code></pre>");
            }
            lastIdx = idx + 3;
            blockCount++;
        }
        sb.append(html.substring(lastIdx));
        if (blockCount % 2 != 0) {
            sb.append("</code></pre>");
        }
        return sb.toString();
    }

    private static String convertUnorderedLists(String html) {
        StringBuilder sb = new StringBuilder();
        String[] lines = html.split("\\n");
        boolean inList = false;
        for (String line : lines) {
            if (line.matches("^\\s*[-*+]\\s+.*")) {
                if (!inList) {
                    sb.append("<ul>");
                    inList = true;
                }
                String item = line.replaceFirst("^\\s*[-*+]\\s+", "");
                sb.append("<li>").append(item).append("</li>");
            } else {
                if (inList) {
                    sb.append("</ul>");
                    inList = false;
                }
                sb.append(line).append("\n");
            }
        }
        if (inList) sb.append("</ul>");
        return sb.toString();
    }

    private static String convertOrderedLists(String html) {
        StringBuilder sb = new StringBuilder();
        String[] lines = html.split("\\n");
        boolean inList = false;
        for (String line : lines) {
            if (line.matches("^\\s*\\d+\\.\\s+.*")) {
                if (!inList) {
                    sb.append("<ol>");
                    inList = true;
                }
                String item = line.replaceFirst("^\\s*\\d+\\.\\s+", "");
                sb.append("<li>").append(item).append("</li>");
            } else {
                if (inList) {
                    sb.append("</ol>");
                    inList = false;
                }
                sb.append(line).append("\n");
            }
        }
        if (inList) sb.append("</ol>");
        return sb.toString();
    }

    private static String convertParagraphs(String html) {
        StringBuilder sb = new StringBuilder();
        String[] lines = html.split("\\n");
        StringBuilder paragraph = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (paragraph.length() > 0) {
                    sb.append("<div class=\"ai-paragraph\">")
                            .append(paragraph.toString().trim())
                            .append("</div>");
                    paragraph.setLength(0);
                }
                continue;
            }
            if (trimmed.startsWith("<h") || trimmed.startsWith("<ul") || trimmed.startsWith("<ol")
                    || trimmed.startsWith("<li") || trimmed.startsWith("<pre") || trimmed.startsWith("<hr")
                    || trimmed.startsWith("</ul") || trimmed.startsWith("</ol")) {
                if (paragraph.length() > 0) {
                    sb.append("<div class=\"ai-paragraph\">")
                            .append(paragraph.toString().trim())
                            .append("</div>");
                    paragraph.setLength(0);
                }
                sb.append(trimmed);
            } else if (trimmed.startsWith("<code") || trimmed.startsWith("</code")
                    || trimmed.startsWith("</pre")) {
                sb.append(trimmed);
            } else {
                if (paragraph.length() > 0) paragraph.append(" ");
                paragraph.append(trimmed);
            }
        }
        if (paragraph.length() > 0) {
            sb.append("<div class=\"ai-paragraph\">")
                    .append(paragraph.toString().trim())
                    .append("</div>");
        }
        return sb.toString();
    }
}
