/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

import com.example.starlight.config.StarlightConfig;
import java.util.Map;

/**
 * Minecraft 启动配置
 */
public class LaunchConfig {
    public String gameDir = ".minecraft";
    public String version = "";
    public String javaPath = "java";
    public String userName = "Player";
    public String uuid = "00000000-0000-0000-0000-000000000000";
    public String accessToken = "dummy_token";
    public String userType = "msa";
    public int minMemory = 2048;
    public int maxMemory = 4096;
    public int windowWidth = 854;
    public int windowHeight = 480;
    public boolean fullscreen = false;
    public boolean versionIsolation = false;
    public String jvmArgs = "";
    public String gameArgs = "";
    public String preLaunchCommand = "";
    public String postExitCommand = "";

    /** 从 ini 配置 Map 构建 */
    public static LaunchConfig fromIni(Map<String, String> ini) {
        LaunchConfig c = new LaunchConfig();
        c.gameDir   = ini.getOrDefault("GameDir", ".minecraft");
        c.version   = ini.getOrDefault("Version", "");
        c.javaPath  = ini.getOrDefault("JavaPath", "java");
        c.userName  = ini.getOrDefault("UserName", "Player");
        c.uuid      = ini.getOrDefault("UUID", "00000000-0000-0000-0000-000000000000");
        c.accessToken = ini.getOrDefault("AccessToken", "dummy_token");
        c.userType  = ini.getOrDefault("UserType", "msa");
        c.minMemory = parseIntSafe(ini.getOrDefault("MinMemory", "2048"), 2048);
        c.maxMemory = parseIntSafe(ini.getOrDefault("MaxMemory", "4096"), 4096);
        c.windowWidth  = parseIntSafe(ini.getOrDefault("WindowWidth", "854"), 854);
        c.windowHeight = parseIntSafe(ini.getOrDefault("WindowHeight", "480"), 480);
        c.fullscreen = "true".equalsIgnoreCase(ini.getOrDefault("Fullscreen", "false"));
        c.versionIsolation = "true".equalsIgnoreCase(ini.getOrDefault("VersionIsolation", "false"));
        c.jvmArgs  = ini.getOrDefault("JvmArgs", "");
        c.gameArgs = ini.getOrDefault("GameArgs", "");
        c.preLaunchCommand = ini.getOrDefault("PreLaunchCommand", "");
        c.postExitCommand  = ini.getOrDefault("PostExitCommand", "");
        return c;
    }

    /** 从 StarlightConfig 构建 */
    public static LaunchConfig readFromConfig() {
        return fromIni(StarlightConfig.getAll());
    }

    private static int parseIntSafe(String s, int defaultValue) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
