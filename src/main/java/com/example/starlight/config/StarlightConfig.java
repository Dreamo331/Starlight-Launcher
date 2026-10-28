/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * 配置管理器，统一读写 starlight.ini
 *
 * <p>版本选择（{@code Version}）<b>不在</b>本文件管辖：它存在各游戏目录内的
 * {@code starlight.json}，统一走 {@link SelectedVersion}。
 */
public class StarlightConfig {

    private static final Logger log = LoggerFactory.getLogger(StarlightConfig.class);

    /**
     * Launcher 段的关键键名列表。
     * <p>注意：这里<b>没有</b> {@code Version}——版本选择已改为各游戏目录内的
     * {@code starlight.json}（见 {@link SelectedVersion}），ini 不再保存该键。
     */
    public static final String[] LAUNCHER_KEYS = {
            "GameDir", "JavaPath",
            "MinMemory", "MaxMemory", "WindowWidth", "WindowHeight",
            "Fullscreen", "VersionIsolation", "JvmArgs", "GameArgs",
            "PreLaunchCommand", "PostExitCommand",
            // 高级设置：自定义游戏窗口标题 / 进程优先级 / 游戏启动后的启动器可见性
            "GameWindowTitle", "GameProcessPriority", "LauncherVisibility",
            "ServerUrl", "GameFolders", "GameDirNames", "GameLanguage", "ParallelLaunch",
            "ParallelVerify", "StreamingSha1", "FastVerify", "SilentLoginMode"
    };

    // 读取 //

    /** 读取全部配置 */
    public static Map<String, String> readConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        Path configPath = AppDirs.starlightIni();
        if (!Files.isRegularFile(configPath)) return config;
        try {
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#") || line.startsWith("["))
                    continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    config.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read config file: {}", e.getMessage());
        }
        return config;
    }

    /** 读取单个配置项 */
    public static String get(String key) {
        return readConfig().getOrDefault(key, "");
    }

    /** 获取全部配置项的 Map 副本 */
    public static Map<String, String> getAll() {
        return new LinkedHashMap<>(readConfig());
    }

    // 保存 //

    /** 保存配置（保留其他段，仅覆写 [Launcher] 段） */
    public static void saveConfig(Map<String, String> config) {
        try {
            Path configDir = AppDirs.root();
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
            Path configPath = AppDirs.starlightIni();

            List<String> oldLines = Files.exists(configPath)
                    ? Files.readAllLines(configPath, StandardCharsets.UTF_8)
                    : new ArrayList<>();

            List<String> newLines = new ArrayList<>();
            boolean inLauncherSection = false;
            boolean hasLauncherSection = false;
            Set<String> seenKeys = new HashSet<>();

            for (String line : oldLines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    if (inLauncherSection) {
                        appendMissingKeys(newLines, config, seenKeys);
                    }
                    inLauncherSection = trimmed.equalsIgnoreCase("[Launcher]");
                    if (inLauncherSection) {
                        hasLauncherSection = true;
                        seenKeys.clear();
                    }
                    newLines.add(line);
                    continue;
                }
                if (inLauncherSection) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        // 旧版遗留的 Version 行一律丢弃：版本选择的真源是各游戏目录内的 starlight.json。
                        // 这里必须显式删行——"不在 map 里就保留原行"的分支会把它留下。
                        if ("Version".equals(key)) continue;
                        seenKeys.add(key);
                        // 调用方 map 里没有的键（如 ConsoleMode 等隐藏字段）保留原行，
                        // 不能写成 "key=null"——否则一次保存就把隐藏开关冲掉了
                        if (config.containsKey(key)) {
                            newLines.add(key + "=" + config.get(key));
                        } else {
                            newLines.add(line);
                        }
                    } else {
                        newLines.add(line);
                    }
                } else {
                    newLines.add(line);
                }
            }
            if (inLauncherSection) {
                appendMissingKeys(newLines, config, seenKeys);
            }
            if (!hasLauncherSection) {
                if (!newLines.isEmpty() && !newLines.get(newLines.size() - 1).isEmpty())
                    newLines.add("");
                newLines.add("[Launcher]");
                for (String key : LAUNCHER_KEYS) {
                    newLines.add(key + "=" + config.getOrDefault(key, ""));
                }
            }

            Files.write(configPath, newLines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Config saved to: {}", configPath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save config: {}", e.getMessage());
        }
    }

    /** 保存单个配置项 */
    public static void setValue(String key, String value) {
        Map<String, String> config = readConfig();
        config.put(key, value);
        saveConfig(config);
    }

    /** 检查配置是否完整（当前游戏目录是否已选好版本） */
    public static boolean isConfigReady() {
        // 版本选择在游戏目录内的 starlight.json，不在 ini
        return !SelectedVersion.of(readConfig()).isEmpty();
    }

    // 内部 //

    private static void appendMissingKeys(List<String> lines, Map<String, String> config, Set<String> seen) {
        for (String key : LAUNCHER_KEYS) {
            if (!seen.contains(key)) {
                lines.add(key + "=" + config.getOrDefault(key, ""));
            }
        }
    }
}
