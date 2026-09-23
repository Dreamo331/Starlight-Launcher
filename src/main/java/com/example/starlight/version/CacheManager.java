package com.example.starlight.version;

import com.google.gson.Gson;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件缓存管理器
 * L1: 内存 ConcurrentHashMap（应用生命周期）
 * L2: 磁盘JSON缓存（24h有效期）
 */
public class CacheManager {

    private static final Path CACHE_DIR = Path.of(System.getProperty("user.home"),
            ".starlight", "cache");
    private static final Duration TTL = Duration.ofHours(24);
    private static final Gson GSON = new Gson();
    private static final ConcurrentHashMap<String, Object> MEM = new ConcurrentHashMap<>();

    public static Path cacheDir() {
        try { Files.createDirectories(CACHE_DIR); } catch (IOException ignored) {}
        return CACHE_DIR;
    }

    /** 从缓存获取 */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key, Type type) {
        // L1: 内存
        Object m = MEM.get(key);
        if (m != null) return (T) m;
        // L2: 磁盘
        Path f = cacheDir().resolve(sanitize(key) + ".json");
        if (!Files.exists(f)) return null;
        try {
            String json = Files.readString(f);
            var wrapper = GSON.fromJson(json, CacheEntry.class);
            if (wrapper == null || isExpired(wrapper.timestamp)) {
                Files.deleteIfExists(f);
                return null;
            }
            T val = GSON.fromJson(wrapper.data, type);
            MEM.put(key, val);
            return val;
        } catch (Exception e) { return null; }
    }

    /** 写入缓存 */
    public static void put(String key, Object value) {
        MEM.put(key, value);
        try {
            CacheEntry entry = new CacheEntry();
            entry.timestamp = Instant.now().toEpochMilli();
            entry.data = GSON.toJsonTree(value);
            Files.writeString(cacheDir().resolve(sanitize(key) + ".json"),
                    GSON.toJson(entry));
        } catch (Exception ignored) {}
    }

    public static void clear() {
        MEM.clear();
        try {
            Files.walk(cacheDir())
                    .filter(Files::isRegularFile)
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        } catch (IOException ignored) {}
    }

    private static boolean isExpired(long ts) {
        return Instant.now().toEpochMilli() - ts > TTL.toMillis();
    }

    private static String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    static class CacheEntry {
        long timestamp;
        com.google.gson.JsonElement data;
    }
}
