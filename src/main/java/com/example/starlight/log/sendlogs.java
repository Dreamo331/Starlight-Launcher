/*
 * sendlogs.java
 * 
 * 这个类负责把错误日志上传到日志分享服务器（默认 Logshare API），
 * 支持：单日志上传、多文件打包上传、上传后撤回（deleteLog）、二维码生成辅助。
 * 上传端点优先级：显式传入的 serverUrl > /assets/endpoints.json 的 services.logUploadUrl > 默认端点。
 */

package com.example.starlight.log;

import com.example.starlight.config.Endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.WriterException;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;

public class sendlogs {

    private static final String DEFAULT_LOG_SHARE_ENDPOINT = "https://api.logshare.cn/v1/log";
    private static final String SOURCE_ID = "starlight-launcher/1.0.0";
    private static final long MAX_FILE_READ_BYTES = 4 * 1024 * 1024; // 单文件最多读取 4MB 尾部

    // 命令行入口（保留兼容：至少传日志路径，serverUrl 可选） //
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java log.sendlogs <log file path> [server URL]");
            System.exit(1);
        }
        String logFilePath = args[0];
        String serverUrl = args.length >= 2 ? args[1] : null;

        try {
            String resultUrl = uploadLogFile(logFilePath, serverUrl);
            System.out.println("Upload successful, result link: " + resultUrl);

            // 生成二维码文件名：当前日期 + 三位随机数字 + .png
            String fileName = generateFileName();
            BufferedImage qrImage = QRcode.generateQRCodeImage(resultUrl);
            File outputFile = new File(fileName);
            ImageIO.write(qrImage, "png", outputFile);
            System.out.println("QR code generated successfully!");
            System.out.println("Link: " + resultUrl);
            System.out.println("Saved to: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Upload failed: " + e.getMessage());
            System.exit(1);
        } catch (WriterException e) {
            System.err.println("QR code generation failed: " + e.getMessage());
            System.exit(1);
        }
    }

    // 上传接口 //
    /** 上传单个日志文件，返回分享链接 */
    public static String uploadLogFile(String logFilePath, String serverUrl) throws IOException {
        return uploadLogFileEx(logFilePath, serverUrl).url;
    }

    /** 上传单个日志文件，返回完整结果（含 id / token，供撤回使用） */
    public static UploadResult uploadLogFileEx(String logFilePath, String serverUrl) throws IOException {
        File logFile = new File(logFilePath);
        if (!logFile.exists()) {
            throw new IOException("File does not exist: " + logFilePath);
        }
        String content = readFileTail(logFile, MAX_FILE_READ_BYTES);
        JsonObject payload = new JsonObject();
        payload.addProperty("source", SOURCE_ID);
        payload.addProperty("content", content);
        payload.addProperty("name", logFile.getName());
        return uploadJson(endpoint(serverUrl), payload);
    }

    /** 上传主日志 + 附加文件列表，返回分享链接 */
    public static String uploadLogBundle(String serverUrl, String mainContent, String mainName,
                                         List<LogFileEntry> extraFiles) throws IOException {
        return uploadLogBundleEx(serverUrl, mainContent, mainName, extraFiles).url;
    }

    /** 上传主日志 + 附加文件列表，返回完整结果 */
    public static UploadResult uploadLogBundleEx(String serverUrl, String mainContent, String mainName,
                                                 List<LogFileEntry> extraFiles) throws IOException {
        if (mainContent == null || mainContent.isEmpty()) {
            throw new IOException("日志内容为空，无法上传");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("source", SOURCE_ID);
        payload.addProperty("content", mainContent);
        if (mainName != null && !mainName.isEmpty()) {
            payload.addProperty("name", mainName);
        }
        if (extraFiles != null && !extraFiles.isEmpty()) {
            JsonArray files = new JsonArray();
            for (LogFileEntry f : extraFiles) {
                if (f == null || f.content == null || f.content.isEmpty()) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("name", f.name != null && !f.name.isEmpty() ? f.name : "log.txt");
                o.addProperty("content", f.content);
                files.add(o);
            }
            if (files.size() > 0) {
                payload.add("files", files);
            }
        }
        return uploadJson(endpoint(serverUrl), payload);
    }

    /** 撤回（删除）已上传的日志。id 必填；token 为空时仅尝试无鉴权删除 */
    public static boolean deleteLog(String id, String token) throws IOException {
        if (id == null || id.trim().isEmpty()) {
            throw new IOException("缺少日志 ID，无法删除");
        }
        URL url = new URL("https://api.logshare.cn/v1/log/" + id.trim());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int responseCode = conn.getResponseCode();
            if (responseCode == 404) {
                return true; // 已被删除/不存在，视为撤回成功
            }
            if (responseCode >= 200 && responseCode < 300) {
                return true;
            }
            InputStream raw = conn.getErrorStream();
            String body = "";
            if (raw != null) {
                try (InputStream in = raw) {
                    body = readAll(in);
                } catch (IOException ignored) {
                    // 读错误响应体失败不影响主流程
                }
            }
            if (!isLogNotFound(body)) {
                throw new IOException("删除失败, HTTP " + responseCode
                        + (body.isEmpty() ? "" : ": " + brief(body)));
            }
            return true;
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
                // 忽略断开异常
            }
        }
    }

    /** 构造附加文件条目（内容超长时自动截取尾部） */
    public static LogFileEntry entry(String name, String content) {
        if (content == null) {
            content = "";
        }
        if ((long) content.length() > MAX_FILE_READ_BYTES) {
            content = content.substring(content.length() - (int) MAX_FILE_READ_BYTES);
        }
        return new LogFileEntry(name, content);
    }

    // 内部实现 //
    private static UploadResult uploadJson(String endpointUrl, JsonObject payload) throws IOException {
        URL url = new URL(endpointUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int responseCode = conn.getResponseCode();
            InputStream raw = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (raw == null) {
                throw new IOException("服务器无响应, HTTP " + responseCode);
            }
            String encoding = conn.getHeaderField("Content-Encoding");
            try (InputStream in = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")
                    ? new GZIPInputStream(raw) : raw) {
                String responseBody = readAll(in);
                if (responseCode >= 400) {
                    throw new IOException("上传失败, HTTP " + responseCode + ": " + brief(responseBody));
                }
                return parseUploadResult(responseBody);
            }
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
                // 忽略断开异常
            }
        }
    }

    /** 解析服务器返回：优先取 url / shortUrl，其次按 id 拼默认短链 */
    private static UploadResult parseUploadResult(String responseBody) throws IOException {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            throw new IOException("服务器返回空响应");
        }
        String trimmed = responseBody.trim();
        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            if (obj.has("success") && !obj.get("success").getAsBoolean()) {
                String err = obj.has("error") && !obj.get("error").isJsonNull()
                        ? obj.get("error").getAsString() : "未知错误";
                throw new IOException("上传被服务器拒绝: " + err);
            }
            String id = obj.has("id") && !obj.get("id").isJsonNull()
                    ? obj.get("id").getAsString() : null;
            String token = obj.has("token") && !obj.get("token").isJsonNull()
                    ? obj.get("token").getAsString() : null;
            if (obj.has("url") && !obj.get("url").isJsonNull()) {
                String u = obj.get("url").getAsString();
                if (!u.isEmpty()) {
                    return new UploadResult(id, u, token);
                }
            }
            if (obj.has("shortUrl") && !obj.get("shortUrl").isJsonNull()) {
                String u = obj.get("shortUrl").getAsString();
                if (!u.isEmpty()) {
                    return new UploadResult(id, u, token);
                }
            }
            if (id != null && !id.isEmpty()) {
                return new UploadResult(id, "https://logshare.cn/" + id, token);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable ignored) {
            // 不是 JSON，尝试按纯文本链接处理
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return new UploadResult(null, trimmed, null);
        }
        throw new IOException("无法解析服务器返回的链接: " + brief(responseBody));
    }

    /** 解析上传端点：显式参数 > 配置 > 默认 */
    private static String endpoint(String serverUrl) {
        if (serverUrl != null && !serverUrl.trim().isEmpty()) {
            return serverUrl.trim();
        }
        try {
            String configured = Endpoints.logUploadUrl();
            if (configured != null && !configured.trim().isEmpty()) {
                return configured.trim();
            }
        } catch (Throwable ignored) {
            // 配置读取失败时回退默认端点
        }
        return DEFAULT_LOG_SHARE_ENDPOINT;
    }

    /** 读取文件尾部（最多 maxBytes 字节），并去掉开头的半个 UTF-8 字符（\uFFFD） */
    private static String readFileTail(File file, long maxBytes) throws IOException {
        long size = file.length();
        long offset = Math.max(0L, size - maxBytes);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            if (offset > 0L) {
                in.skip(offset);
            }
            bytes = in.readAllBytes();
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        int start = 0;
        while (start < text.length() && text.charAt(start) == '\uFFFD') {
            start++;
        }
        return start > 0 ? text.substring(start) : text;
    }

    /** 读取整个输入流为字符串 */
    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 判断错误响应体是否为「日志不存在」 */
    private static boolean isLogNotFound(String body) {
        if (body == null || body.trim().isEmpty()) {
            return false;
        }
        String t = body.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("not found") || lower.contains("不存在")) {
            return true;
        }
        try {
            JsonObject obj = JsonParser.parseString(t).getAsJsonObject();
            if (obj.has("code") && !obj.get("code").isJsonNull() && obj.get("code").getAsInt() == 404) {
                return true;
            }
            if (obj.has("errors") && obj.get("errors").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("errors")) {
                    if (el.isJsonObject()
                            && el.getAsJsonObject().has("code")
                            && !el.getAsJsonObject().get("code").isJsonNull()
                            && el.getAsJsonObject().get("code").getAsInt() == 404) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 非 JSON 响应按文本判断
        }
        return false;
    }

    /** 截断超长响应体，用于错误提示 */
    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** 生成二维码文件名，格式：yyyyMMdd + 三位随机数字 + .png */
    private static String generateFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        String dateStr = sdf.format(new Date());
        Random random = new Random();
        int randomNum = random.nextInt(1000); // 0-999
        String randomStr = String.format("%03d", randomNum);
        return dateStr + randomStr + ".png";
    }

    // 数据模型 //
    /** 附加文件条目 */
    public static final class LogFileEntry {
        public final String name;
        public final String content;

        public LogFileEntry(String name, String content) {
            this.name = name;
            this.content = content;
        }
    }

    /** 上传结果：id / 分享链接 / 撤回 token */
    public static final class UploadResult {
        public final String id;
        public final String url;
        public final String token;

        public UploadResult(String id, String url, String token) {
            this.id = id;
            this.url = url;
            this.token = token;
        }
    }
}
