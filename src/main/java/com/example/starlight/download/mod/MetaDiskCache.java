package com.example.starlight.download.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 小 JSON 元数据磁盘缓存（CacheCow 的极简替代，对标 PCL §7⑥）。
 *
 * <p>PCL 的 CacheCow 只服务小 JSON 请求（版本列表 / Forge/Fabric 元数据），
 * <b>完全不参与大文件下载</b>。本类是其一期最小可行实现：
 * <ul>
 *   <li>按 URL 的 MD5 命名存 {@code {gameDir}/.cache/meta/}；</li>
 *   <li>TTL 1 天（与 PCL asset index「每天更新一次」同款策略）；</li>
 *   <li>{@link #get(String)} 读本地字符串、{@link #put(String, String)} 写；</li>
 *   <li><b>只存/取字符串内容，不做任何 JSON 解析</b>——解析在上层
 *       VersionDownloadService；大文件 / 分块 Range 请求一律不走这里。</li>
 * </ul>
 */
public final class MetaDiskCache {

    /** TTL：1 天 */
    private static final long TTL_MS = 24L * 60L * 60L * 1000L;

    private final Path dir;

    public MetaDiskCache(Path gameDir) throws IOException {
        this.dir = gameDir.resolve(".cache").resolve("meta");
        Files.createDirectories(this.dir);
    }

    /** 读缓存；未命中或已过期返回空 */
    public Optional<String> get(String url) throws IOException {
        if (url == null || url.isBlank()) return Optional.empty();
        Path f = dir.resolve(fileNameOf(url));
        if (!Files.isRegularFile(f)) return Optional.empty();
        long age = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis();
        if (age > TTL_MS) return Optional.empty();
        return Optional.of(Files.readString(f, StandardCharsets.UTF_8));
    }

    /** 写缓存（覆盖） */
    public void put(String url, String content) throws IOException {
        if (url == null || url.isBlank() || content == null) return;
        Path f = dir.resolve(fileNameOf(url));
        Files.writeString(f, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** URL → 缓存文件名：MD5(url) 十六进制 + .json（不暴露 URL 原文） */
    private static String fileNameOf(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + ".json";
        } catch (Exception e) {
            // 兜底：MD5 一定可用；极端情况下退化为 URL 的 hashCode 命名
            return Integer.toHexString(url.hashCode()) + ".json";
        }
    }
}
