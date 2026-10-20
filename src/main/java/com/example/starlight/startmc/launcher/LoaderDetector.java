/** (C) Copyright 2026 Starlight. All rights reserved. */
// 加载器类型检测工具 //
// 根据 version.json 的 mainClass、版本名称等特征，
// 自动判断当前版本使用的 Mod 加载器类型，供后续分支启动使用。
package com.example.starlight.startmc.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Mod 加载器类型检测器（静态工具类）
 *
 * 检测优先级:
 *   1. mainClass 全限定名（最准确）
 *   2. 版本目录中的 JSON 的 inheritsFrom 字段
 *   3. 版本名称字符串包含的关键词（降级方案）
 *
 * 测试版本参考
 *   - 1.20.1（原版）              → Vanilla
 *   - 1.12.2-Forge_14.23.5.2860  → Forge (Legacy)
 *   - 1.20.1-Fabric 0.17.2        → Fabric
 *   - 1.20.1-Forge_47.4.16        → Forge (Modern)
 */
public final class LoaderDetector {

    // 加载器类型常量 //

    /** 原版 Minecraft，无 Mod 加载器 */
    public static final String VANILLA        = "Vanilla";
    /** Fabric Mod 加载器（含 Quilt） */
    public static final String FABRIC         = "Fabric";
    /** Quilt Mod 加载器（Fabric 分支） */
    public static final String QUILT          = "Quilt";
    /** NeoForge 加载器（Forge 1.20.1+ 分支） */
    public static final String NEOFORGE       = "NeoForge";
    /** Forge Modern（1.17+，使用模块化启动） */
    public static final String FORGE_MODERN   = "Forge (Modern)";
    /** Forge Legacy（1.16.5-，使用 tweakClass 启动） */
    public static final String FORGE_LEGACY   = "Forge (Legacy)";

    // mainClass 检测特征串 //

    /** Fabric 主类（简写） */
    private static final String FABRIC_KNOT          = "KnotClient";
    /** Fabric 启动器主类 */
    private static final String FABRIC_MAIN_LAUNCHER = "net.fabricmc";
    /** Quilt 主类 */
    private static final String QUILT_MAIN           = "org.quiltmc";
    /** Forge Legacy 主类（1.12.2 及以前） */
    private static final String FORGE_LEGACY_MAIN    = "LaunchWrapper";
    /** Forge Modern 主类 */
    private static final String FORGE_MODERN_BOOTSTRAP = "BootstrapLauncher";
    /** Forge Modern 主类（备选） */
    private static final String FORGE_MODERN_MODULE  = "ModuleClassLoader";
    /** NeoForge 主类标识 */
    private static final String NEOFORGE_MAIN        = "net.neoforged";

    private static final int FORGE_MODERN_MIN_MINOR  = 17;

    // 核心检测方法 //
    private LoaderDetector() {}

    /**
     * 从 version.json 中检测加载器类型
     *
     * @param versionJson  已解析的版本 JSON 对象
     * @param versionName  游戏版本名（用于降级检测）
     * @return 加载器类型常量，如 "Vanilla" / "Fabric" / "Forge (Modern)" 等
     */
    public static String detect(JsonObject versionJson, String versionName) {
        // 第一优先级：从 mainClass 检测（最可靠） //
        String mainClass = versionJson.has("mainClass")
                ? versionJson.get("mainClass").getAsString()
                : "";

        if (!mainClass.isEmpty()) {
            String result = detectByMainClass(mainClass, versionJson);
            if (result != null) return result;
        }

        // 第二优先级：通过 inheritsFrom 字段检测（继承的父版本） //
        // Fabric/Quilt 的版本 JSON 通常会继承父版本，如:
        //   "inheritsFrom": "1.20.1"
        // 但原版 JSON 不会有此字段，可从父版本的 JSON 中找线索
        if (versionJson.has("inheritsFrom")) {
            String parent = versionJson.get("inheritsFrom").getAsString();
            // 若父版本名包含特征关键词，适用同样规则
            String parentResult = detectByName(parent);
            if (parentResult != null) return parentResult;
        }

        // 第三优先级：通过版本名称降级检测 //
        String nameResult = detectByName(versionName);
        if (nameResult != null) return nameResult;

        // 兜底：无法识别时默认原版 //
        return VANILLA;
    }

    // 子检测逻辑 //

    /**
     * 通过 mainClass 字符串检测加载器
     *
     * @param mainClass   版本 JSON 的 mainClass 字段值
     * @param versionJson 已解析的版本 JSON（用于区分 mainClass 相同的加载器）
     * @return 检测到的类型，无法识别返回 null
     */
    private static String detectByMainClass(String mainClass, JsonObject versionJson) {
        // Fabric 系列
        if (mainClass.contains(FABRIC_KNOT) ||
            mainClass.contains(FABRIC_MAIN_LAUNCHER)) {
            return FABRIC;
        }

        // Quilt 系列
        if (mainClass.contains(QUILT_MAIN)) {
            return QUILT;
        }

        // NeoForge 系列（优先于 Forge Modern，因二者 mainClass 同为 BootstrapLauncher）
        // NeoForge 的 mainClass 通常也是 cpw.mods.bootstraplauncher.BootstrapLauncher，
        // 不含 "net.neoforged" 字样，必须结合 libraries 中的 net.neoforged 依赖区分
        if (mainClass.contains(NEOFORGE_MAIN) ||
            ((mainClass.contains(FORGE_MODERN_BOOTSTRAP) ||
              mainClass.contains(FORGE_MODERN_MODULE)) && hasNeoForgeLibraries(versionJson))) {
            return NEOFORGE;
        }

        // Forge Legacy（LaunchWrapper）
        if (mainClass.contains(FORGE_LEGACY_MAIN)) {
            return FORGE_LEGACY;
        }

        // Forge Modern（BootstrapLauncher / ModuleClassLoader）
        if (mainClass.contains(FORGE_MODERN_BOOTSTRAP) ||
            mainClass.contains(FORGE_MODERN_MODULE)) {
            return FORGE_MODERN;
        }

        return null; // 无法通过 mainClass 识别
    }

    /**
     * 通过 libraries 检测是否包含 NeoForge 依赖
     * 用于区分 shared mainClass（如 BootstrapLauncher）的 NeoForge vs Forge Modern
     *
     * @return true 如果 libraries 中包含 net.neoforged 组织的依赖
     *         （含 net.neoforged.fancymodloader 等子包，统称 net.neoforged 前缀）
     */
    private static boolean hasNeoForgeLibraries(JsonObject versionJson) {
        if (versionJson == null || !versionJson.has("libraries")) return false;
        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        for (JsonElement elem : libraries) {
            if (elem.isJsonObject() && elem.getAsJsonObject().has("name")) {
                String name = elem.getAsJsonObject().get("name").getAsString();
                if (name.startsWith("net.neoforged")) return true;
            }
        }
        return false;
    }

    /**
     * 通过版本名称字符串检测加载器
     * @return 检测到的类型，无法识别返回 null
     */
    private static String detectByName(String name) {
        if (name == null || name.isEmpty()) return null;

        String lower = name.toLowerCase();

        // 注意检测顺序：NeoForge > Forge > Fabric > Quilt，避免子串误匹配
        if (lower.contains("neoforge")) return NEOFORGE;
        if (lower.contains("fabric"))   return FABRIC;
        if (lower.contains("quilt"))    return QUILT;

        if (lower.contains("forge")) {
            // 根据版本号区分 Legacy 与 Modern
            // Modern: 1.17+, Legacy: 1.16.5-
            return isModernForgeVersion(name) ? FORGE_MODERN : FORGE_LEGACY;
        }

        return null;
    }

    /**
     * 判断 Forge 版本是否需要 Modern（模块化）启动
     *
     * Forge 在 1.17+ 切换了启动架构，使用 Java 模块系统。
     *   - Modern Forge:  需要 --add-opens、module-path 等 JVM 参数
     *   - Legacy Forge:  使用 LaunchWrapper，需过滤 Java 9+ 参数
     */
    private static boolean isModernForgeVersion(String versionName) {
        // 尝试从版本名中提取主版本号
        // 格式示例: "1.20.1-Forge_47.4.16" 或 "1.20.1-forge-47.4.16"
        try {
            // 匹配 "1.xx" 格式
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("1\\.(\\d+)")
                    .matcher(versionName);
            if (m.find()) {
                int minor = Integer.parseInt(m.group(1));
                return minor >= FORGE_MODERN_MIN_MINOR;
            }
        } catch (NumberFormatException ignored) {
            // 解析失败时降级为 Legacy（兼容旧版本）
        }

        // 硬编码调整 //
        // 若版本名不符合 "1.xx" 格式，无法判断新旧架构，默认按 Legacy 处理。
        // 如需为特定版本强制指定，可在下述 return 前写入版本名判断：
        // if (versionName.contains("MyCustomForge")) return true;

        return false;
    }

    /**
     * 判断加载器类型是否属于 Forge 系列
     */
    public static boolean isForge(String loaderType) {
        return FORGE_LEGACY.equals(loaderType) ||
               FORGE_MODERN.equals(loaderType) ||
               NEOFORGE.equals(loaderType);
    }

    /**
     * 判断加载器类型是否属于 Fabric 系列（含 Quilt）
     */
    public static boolean isFabricLike(String loaderType) {
        return FABRIC.equals(loaderType) || QUILT.equals(loaderType);
    }
}
