package com.example.starlight.listjava;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Java 安装扫描结果缓存管理器。
 * <p>
 * 全盘扫描 Java（注册表 + 常见目录 + 各盘根目录）较慢，每次启动都扫会拖慢启动流程。
 * 方案：扫描一次后把结果写入 {@code Starlight-Launcher/java-cache.json}（与 starlight.ini 同目录，
 * 相对运行目录），下次启动直接读缓存（毫秒级）；缓存中任一路径失效（Java 被卸载/移动）时自动重新全扫。
 * <p>
 * 缓存文件结构：
 * <pre>
 * {
 *   "scannedAt": 1750000000000,
 *   "entries": [
 *     {"homePath": "D:\\java17", "version": "17.0.19", "majorVersion": 17,
 *      "vendor": "Microsoft", "isJDK": true}
 *   ]
 * }
 * </pre>
 */
public final class JavaCacheManager {

    private static final Logger log = LoggerFactory.getLogger(JavaCacheManager.class);

    /** 与 starlight.ini / launcher.json 同目录：相对运行目录的 Starlight-Launcher 文件夹 */
    private static final String CONFIG_DIR = "Starlight-Launcher";
    private static final String CACHE_FILE = CONFIG_DIR + "/java-cache.json";

    private JavaCacheManager() {}

    /**
     * 获取本机所有 Java 安装（优先读缓存；缓存缺失/失效时全盘扫描并写缓存）。
     * 线程安全：预热线程与启动线程可能同时访问。
     */
    public static synchronized List<FindAllJavaWindows.JavaEntry> getAvailable() {
        List<FindAllJavaWindows.JavaEntry> cached = loadCache();
        if (cached != null) {
            return cached;
        }
        // 缓存缺失或失效：全盘扫描并写入缓存
        List<FindAllJavaWindows.JavaEntry> entries = FindAllJavaWindows.findAll();
        saveCache(entries);
        return entries;
    }

    /**
     * 强制重新扫描并更新缓存（设置页「搜索」按钮使用，用户主动要求刷新）。
     */
    public static synchronized List<FindAllJavaWindows.JavaEntry> refresh() {
        List<FindAllJavaWindows.JavaEntry> entries = FindAllJavaWindows.findAll();
        saveCache(entries);
        return entries;
    }

    /**
     * 预热缓存（启动器闪屏后台调用）：缓存有效则不扫描，避免重复耗时。
     */
    public static void warmUp() {
        try {
            getAvailable();
        } catch (Exception e) {
            log.warn("Java cache warm-up failed: {}", e.getMessage());
        }
    }

    // ======================== 缓存读写 ========================

    /**
     * 读取缓存。缓存有效（文件存在 + 每条路径的 java.exe 仍存在）返回条目列表，
     * 否则返回 null（调用方触发重新扫描）。
     */
    private static List<FindAllJavaWindows.JavaEntry> loadCache() {
        Path file = Paths.get(CACHE_FILE);
        if (!Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (!root.has("entries") || !root.get("entries").isJsonArray()) return null;
            JsonArray arr = root.getAsJsonArray("entries");
            List<FindAllJavaWindows.JavaEntry> entries = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String homePath = obj.has("homePath") ? obj.get("homePath").getAsString() : null;
                if (homePath == null || homePath.isBlank()) continue;
                // 校验 java.exe 仍存在：任一失效即整体视为缓存过期（可能有新装/卸载的 Java）
                Path javaExe = Paths.get(homePath, "bin", "java.exe");
                if (!Files.isExecutable(javaExe)) return null;
                String version = obj.has("version") ? obj.get("version").getAsString() : "未知";
                String vendor = obj.has("vendor") && !obj.get("vendor").isJsonNull()
                        ? obj.get("vendor").getAsString() : null;
                boolean isJDK = obj.has("isJDK") && obj.get("isJDK").getAsBoolean();
                entries.add(new FindAllJavaWindows.JavaEntry(homePath, version, vendor, isJDK));
            }
            if (entries.isEmpty()) return null;
            return entries;
        } catch (Exception e) {
            log.warn("Java cache read failed, will rescan: {}", e.getMessage());
            return null;
        }
    }

    /** 写入缓存（原子写入：临时文件 + 重命名，避免半截文件） */
    private static void saveCache(List<FindAllJavaWindows.JavaEntry> entries) {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            JsonObject root = new JsonObject();
            root.addProperty("scannedAt", System.currentTimeMillis());
            JsonArray arr = new JsonArray();
            for (FindAllJavaWindows.JavaEntry entry : entries) {
                JsonObject obj = new JsonObject();
                obj.addProperty("homePath", entry.homePath);
                obj.addProperty("version", entry.version);
                obj.addProperty("majorVersion", entry.majorVersion);
                if (entry.vendor != null) {
                    obj.addProperty("vendor", entry.vendor);
                }
                obj.addProperty("isJDK", entry.isJDK);
                arr.add(obj);
            }
            root.add("entries", arr);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Path file = Paths.get(CACHE_FILE);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Java cache write failed: {}", e.getMessage());
        }
    }
}
