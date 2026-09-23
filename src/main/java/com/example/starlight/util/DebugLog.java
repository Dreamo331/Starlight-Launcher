/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import com.example.starlight.newui.AppConfig;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 调试日志工具：调试模式（AppConfig.DEBUG_MODE）开启时，
 * 记录底层页面刷新与网络数据包收发详情，供开发者选项页实时查看。
 *
 * <p>所有方法在调试模式关闭时直接返回（零开销）；
 * 日志文案语言由 AppConfig.CHINESE_LOG 控制（中文 / 英文）。</p>
 */
public final class DebugLog {

    /** 内存环形缓冲上限（超出丢弃最旧条目，防止长时间运行内存膨胀） */
    private static final int MAX_ENTRIES = 500;

    /** 单条内容最大展示长度（超长截断，防止下载/大响应刷屏） */
    private static final int MAX_CONTENT_LEN = 600;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final List<String> buffer = new ArrayList<>();
    private static final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("Debug");

    private DebugLog() {}

    /** 注册实时日志监听器（收到格式化后的一行日志；回调线程不保证为 FX 线程） */
    public static void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    /** 移除实时日志监听器 */
    public static void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    /** 最近日志快照（用于页面重建时回显历史记录） */
    public static synchronized List<String> snapshot() {
        return new ArrayList<>(buffer);
    }

    /** 记录底层事件日志（如页面刷新）；调试模式关闭时忽略 */
    public static void log(String msg) {
        if (!AppConfig.DEBUG_MODE || msg == null || msg.isEmpty()) return;
        String tag = AppConfig.CHINESE_LOG ? "[内部] " : "[APP] ";
        emit(tag + msg);
    }

    /**
     * 记录一个网络数据包收发事件（自动统计字节大小、脱敏敏感字段、截断超长内容）。
     *
     * @param direction 方向：true=发送（请求），false=接收（响应）
     * @param method    HTTP 方法（GET/POST/...），接收方向可为空
     * @param url       请求/响应地址
     * @param status    HTTP 状态码（发送方向可传 0）
     * @param bytes     数据包字节大小（请求体/响应体）
     * @param content   数据包详细内容（自动脱敏 + 截断）
     * @param costMs    耗时（毫秒），未知传 -1
     */
    public static void http(boolean direction, String method, String url,
                            int status, long bytes, String content, long costMs) {
        if (!AppConfig.DEBUG_MODE) return;
        boolean zh = AppConfig.CHINESE_LOG;
        String dir = direction
                ? (zh ? "发送" : "SEND")
                : (zh ? "接收" : "RECV");
        String methodPart = (method == null || method.isEmpty()) ? "" : method + " ";
        String statusPart = status > 0 ? " " + status + (zh ? " " : " ") : "";
        String sizePart = formatBytes(bytes);
        String costPart = costMs >= 0
                ? (zh ? " | 耗时 " + costMs + "ms" : " | cost " + costMs + "ms")
                : "";
        String detail = content == null ? "" : maskSensitive(content);
        if (detail.length() > MAX_CONTENT_LEN) {
            detail = detail.substring(0, MAX_CONTENT_LEN) + (zh ? "…(已截断)" : "…(truncated)");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "[网络 " : "[NET ")
          .append(dir).append("] ")
          .append(methodPart).append(url)
          .append(statusPart)
          .append(" | ").append(zh ? "数据包 " : "pkt ").append(sizePart)
          .append(costPart);
        if (!detail.isEmpty()) {
            sb.append(zh ? "\n    内容: " : "\n    body: ").append(detail);
        }
        emit(sb.toString());
    }

    /** 字节大小格式化：B / KB / MB */
    private static String formatBytes(long bytes) {
        if (bytes < 0) return AppConfig.CHINESE_LOG ? "未知" : "unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    /** 敏感字段脱敏：token / password / authorization 等值打码，避免调试日志泄露凭据 */
    private static String maskSensitive(String s) {
        return s.replaceAll(
                "(?i)(\"?(access_token|refresh_token|token|password|authorization|code|device_code)\"?\\s*[:=]\\s*\"?)[^\\s,}&\"']+",
                "$1***");
    }

    /** 统一输出：带时间戳写入缓冲 + SLF4J 落盘 + 推送给监听器 */
    private static void emit(String line) {
        String entry = "[" + LocalTime.now().format(TS) + "] " + line;
        synchronized (buffer) {
            buffer.add(entry);
            if (buffer.size() > MAX_ENTRIES) buffer.remove(0);
        }
        log.info(entry);
        for (Consumer<String> l : listeners) {
            try {
                l.accept(entry);
            } catch (Exception ignored) {
                // 监听器异常不影响日志主流程
            }
        }
    }
}
