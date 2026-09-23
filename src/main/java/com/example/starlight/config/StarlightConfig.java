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
 */
public class StarlightConfig {

    private static final Logger log = LoggerFactory.getLogger(StarlightConfig.class);

    private static final String CONFIG_DIR = "Starlight-Launcher";
    private static final String CONFIG_FILE = CONFIG_DIR + "/starlight.ini";

    /** Launcher 段的关键键名列表 */
    public static final String[] LAUNCHER_KEYS = {
            "GameDir", "Version", "JavaPath",
            "MinMemory", "MaxMemory", "WindowWidth", "WindowHeight",
            "Fullscreen", "VersionIsolation", "JvmArgs", "GameArgs",
            "PreLaunchCommand", "PostExitCommand",
            "ServerUrl", "GameFolders", "GameDirNames", "GameLanguage", "ParallelLaunch",
            "ParallelVerify", "StreamingSha1", "FastVerify", "SilentLoginMode"
    };

    // ================================================================
    //  读取
    // ================================================================

    /** 读取全部配置 */
    public static Map<String, String> readConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        Path configPath = resolveConfigPath();
        if (configPath == null) return config;
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

    // ================================================================
    //  保存
    // ================================================================

    /** 保存配置（保留其他段，仅覆写 [Launcher] 段） */
    public static void saveConfig(Map<String, String> config) {
        try {
            Path configDir = Paths.get(CONFIG_DIR);
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
            Path configPath = configDir.resolve("starlight.ini");

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

    /** 检查配置是否完整 */
    public static boolean isConfigReady() {
        Map<String, String> cfg = readConfig();
        return !cfg.getOrDefault("Version", "").isEmpty();
    }

    // ================================================================
    //  内部
    // ================================================================

    private static Path resolveConfigPath() {
        Path p = Paths.get(CONFIG_FILE);
        if (Files.exists(p)) return p;
        p = Paths.get(CONFIG_DIR, "starlight.ini");
        return Files.exists(p) ? p : null;
    }

    private static void appendMissingKeys(List<String> lines, Map<String, String> config, Set<String> seen) {
        for (String key : LAUNCHER_KEYS) {
            if (!seen.contains(key)) {
                lines.add(key + "=" + config.getOrDefault(key, ""));
            }
        }
    }
}
