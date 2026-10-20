/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.util;

import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 原版版本号解析（面向离线皮肤等需要按原版版本判断特性的逻辑）。
 *
 * <p>与 PCL2 的 {@code McInstanceSelected.Version.Vanilla} 对应：从版本目录名 / 版本 JSON 的
 * {@code inheritsFrom} 里提取原版 {@code major.minor}（如 {@code 1.20.1} → major=20, minor=1），
 * 快照版或无法识别的版本归为 {@link #UNKNOWN}（snapshot=true）。</p>
 *
 * <p>注意：Fabric 目录名形如 {@code fabric-loader-0.14.22-1.20.1}、Forge 形如
 * {@code 1.20.1-forge-47.2.0}、自定义版本形如 {@code 1.21.1-123}，均能命中 {@code 1.x.y}；
 * 与 {@code major.minor} 不冲突的纯名字（如 {@code MyPack}）会回退到 {@code inheritsFrom}。</p>
 */
public record VanillaVersion(int major, int minor, boolean snapshot) {

    /** 无法识别（快照 / 过新 / 命名无版本号）时的占位：major=9999，资源包格式走最新兼容档 */
    public static final VanillaVersion UNKNOWN = new VanillaVersion(9999, 0, true);

    private static final Pattern VERSION_PATTERN = Pattern.compile("1\\.(\\d+)(?:\\.(\\d+))?");

    /**
     * 从版本名字符串解析原版版本号；解析不到返回 {@link #UNKNOWN}。
     */
    public static VanillaVersion parse(String name) {
        if (name == null || name.isBlank()) return UNKNOWN;
        Matcher m = VERSION_PATTERN.matcher(name);
        if (m.find()) {
            int major = Integer.parseInt(m.group(1));
            int minor = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            return new VanillaVersion(major, minor, false);
        }
        return UNKNOWN;
    }

    /**
     * 解析「当前启动版本」的原版版本：先看版本名；解析不到时回读该版本 JSON 的
     * {@code inheritsFrom}（整合包壳 JSON 场景，父版本号里通常带原版版本）。
     *
     * @param gameDir 游戏根目录（{@code .minecraft}），用于定位 {@code versions/<version>/<version>.json}
     * @param version 版本目录名
     */
    public static VanillaVersion resolve(Path gameDir, String version) {
        VanillaVersion direct = parse(version);
        if (!direct.snapshot()) return direct;
        if (gameDir == null || version == null || version.isBlank()) return UNKNOWN;

        try {
            Path jsonPath = gameDir.resolve("versions").resolve(version).resolve(version + ".json");
            if (Files.exists(jsonPath)) {
                var root = JsonParser.parseString(Files.readString(jsonPath)).getAsJsonObject();
                if (root.has("inheritsFrom") && !root.get("inheritsFrom").isJsonNull()) {
                    VanillaVersion inherited = parse(root.get("inheritsFrom").getAsString());
                    if (!inherited.snapshot()) return inherited;
                }
            }
        } catch (Exception ignored) {
            // 读不到 / 解析失败时按未知版本处理
        }
        return UNKNOWN;
    }

    @Override
    public String toString() {
        return snapshot() ? "snapshot" : major + "." + minor;
    }
}
