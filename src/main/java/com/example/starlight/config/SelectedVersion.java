/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.config;

import com.example.starlight.newui.AppConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 「当前游戏目录要启动哪个版本」的<b>唯一真源</b>。
 *
 * <h3>为什么改到这里</h3>
 * 原先版本选择是 {@code starlight.ini} 的全局单值 {@code Version}，不随游戏目录走。
 * 多目录场景下就会出现「在 A 目录选了 1.20.1，切到只装了 1.19.4 的 B 目录后仍指向 1.20.1」——
 * 轻则提示重选，重则用错版本。现在每个游戏目录自己记一份，切目录即读该目录的记录。
 *
 * <h3>存储</h3>
 * <pre>
 * {游戏目录}/starlight.json
 * {
 *   "schemaVersion": 1,
 *   "selectedVersion": "1.20.1-forge_47.2.0",   // 空串 = 明确未选择
 *   "updatedAt": "2026-10-25T16:03:00+08:00",
 *   "updateBy": "Starlight Launcher 1.0.0-RELEASE"
 * }
 * </pre>
 * 与 {@code launcher_profiles.json} 同级放置，便于用户备份 / 复制整合包时一并带走；
 * 只放版本，不放内存 / Java 路径等设置（那些仍归全局 {@code starlight.ini}）。
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>任何读取都不得抛异常：文件缺失 / 损坏 / 路径非法 → 返回空串并记一条日志。</li>
 *   <li>{@link #of(String)} 会顺带校验「该版本确实装在这个目录里」，悬空记录按「未选择」返回，
 *       调用方因此无需各自做存在性判断（要拿原始记录用 {@link #recorded(String)}）。</li>
 *   <li>写失败（目录只读 / 无权限）只记日志，不阻断启动流程。</li>
 *   <li>本类不依赖任何 UI 类，UI 与命令行共用同一入口。</li>
 * </ul>
 */
public final class SelectedVersion {

    private static final Logger log = LoggerFactory.getLogger(SelectedVersion.class);

    /** 配置文件文件名（放在游戏目录根下） */
    public static final String FILENAME = "starlight.json";

    /** 结构版本号（后续加字段时用于兼容处理） */
    public static final int SCHEMA_VERSION = 1;

    /** 「该目录应启动哪个版本」的字段名 */
    public static final String KEY_SELECTED = "selectedVersion";

    private static final String KEY_SCHEMA = "schemaVersion";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String KEY_UPDATED_BY = "updateBy";

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create();

    private SelectedVersion() {}

    // 路径 //

    /** 某游戏目录的版本选择文件路径；路径非法时返回 null */
    public static Path fileOf(String gameDir) {
        String dir = trim(gameDir);
        if (dir.isEmpty()) dir = GameDirManager.DEFAULT_DIR;
        try {
            return Paths.get(dir, FILENAME);
        } catch (InvalidPathException e) {
            log.warn("Invalid game dir for selected-version file: {}", dir);
            return null;
        }
    }

    // 读取 //

    /**
     * 该目录当前应启动的版本；未记录、记录损坏、或记录的版本已不在该目录时返回空串。
     * <p>这是所有调用方的标准入口：返回值可直接当作「当前选中的版本」使用。
     */
    public static String of(String gameDir) {
        String recorded = recorded(gameDir);
        if (recorded.isEmpty()) return "";
        // 悬空记录（版本已被删除 / 目录被外部搬走）按「未选择」处理，避免启动到不存在的版本
        return isInstalled(gameDir, recorded) ? recorded : "";
    }

    /** 当前活动游戏目录应启动的版本 */
    public static String of(Map<String, String> config) {
        return of(GameDirManager.activePath(config));
    }

    /**
     * 该目录文件里记录的<b>原始</b>值（不做「是否已安装」校验）。
     * <p>供诊断提示与迁移使用；业务逻辑请用 {@link #of(String)}。
     */
    public static String recorded(String gameDir) {
        Path path = fileOf(gameDir);
        if (path == null || !Files.isRegularFile(path)) return "";
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json.isBlank()) return "";
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has(KEY_SELECTED) || !root.get(KEY_SELECTED).isJsonPrimitive()) return "";
            String value = root.get(KEY_SELECTED).getAsString();
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            // 文件被写坏 / 写到一半：按「未选择」处理，并留下可排查的日志
            log.warn("Failed to read selected version from {}: {}", path, e.getMessage());
            return "";
        }
    }

    // 写入 //

    /**
     * 记录 / 清除某目录的版本选择。
     * @param version 版本目录名；空串表示「明确清除记录」（区别于点「尚未选择」）
     * @return 是否写盘成功
     */
    public static boolean set(String gameDir, String version) {
        Path path = fileOf(gameDir);
        if (path == null) return false;
        String value = trim(version);

        JsonObject root = new JsonObject();
        root.addProperty(KEY_SCHEMA, SCHEMA_VERSION);
        root.addProperty(KEY_SELECTED, value);
        root.addProperty(KEY_UPDATED_AT,
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        root.addProperty(KEY_UPDATED_BY, AppConfig.APP_DISPLAY_NAME + " " + AppConfig.APP_VERSION);

        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(path, PRETTY_GSON.toJson(root).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Selected version for {} -> '{}'", path.getParent(), value);
            return true;
        } catch (Exception e) {
            // 目录只读 / 无权限：内存与本次启动照常，下次启动退回「未选择」
            log.warn("Failed to write selected version to {}: {}", path, e.getMessage());
            return false;
        }
    }

    /**
     * 把活动目录的版本选择同步进内存配置表（{@code config.put("Version", ...)}）。
     *
     * <p><b>这不是真源</b>，只是运行时镜像：真源始终是各目录的 {@code starlight.json}。
     * 保留镜像的目的是兜底——万一还有漏改的读取点，它读到的也是当前目录的正确值，
     * 而不是空值或别的目录留下的值。镜像永不落盘（两个 {@code saveConfig} 都会丢弃 {@code Version} 行）。
     */
    public static void syncCache(Map<String, String> config) {
        if (config == null) return;
        config.put("Version", of(config));
    }

    // 校验 //

    /**
     * 指定游戏目录的 {@code versions/} 下是否已安装该版本。
     * <p>判定与 {@code util.ResourceScanner#versionInstalledIn} 一致（以版本目录是否存在为准）；
     * 这里自带一份实现是为了不让 {@code config} 包反向依赖 {@code util}/{@code gui}。
     */
    public static boolean isInstalled(String gameDir, String version) {
        String dir = trim(gameDir);
        String ver = trim(version);
        if (dir.isEmpty() || ver.isEmpty()) return false;
        try {
            return Files.isDirectory(Paths.get(dir, "versions", ver));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}