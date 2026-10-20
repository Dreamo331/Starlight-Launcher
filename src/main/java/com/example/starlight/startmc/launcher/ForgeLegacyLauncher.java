/** (C) Copyright 2026 Starlight. All rights reserved. */
// Forge Legacy 加载器启动器（Minecraft 1.16.5 及以下） //
// 适用于 Forge 1.12.2 ~ 1.16.5 等使用 LaunchWrapper 的版本。
// 关键特征:
//   - 使用 LaunchWrapper 作为主类
//   - 不支持 Java 9+ 模块系统参数（需要过滤 --add-* 等）
//   - 通过 tweakClass 参数指定 Mod 加载器
//   - 使用 minecraftArguments 旧格式而非 arguments.game
package com.example.starlight.startmc.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.example.starlight.startmc.LaunchInfo;

/**
 * Forge Legacy 加载器启动器
 *
 * 启动特征:
 *   - mainClass = net.minecraft.launchwrapper.Launch
 *   - 过滤所有 --add-* / --patch-module / --illegal-access 等 Java 9+ 参数
 *   - 支持从 minecraftArguments 或 arguments.game 提取 tweakClass
 *   - 通过 -Dfml.modFolders 指定 mods 目录
 *
 * 测试版本: 1.12.2-Forge_14.23.5.2860
 */
public class ForgeLegacyLauncher extends BaseLauncher {

    public ForgeLegacyLauncher(LaunchInfo launchInfo) {
        super(launchInfo);
    }

    @Override
    public int launch() throws IOException, InterruptedException {
        writeLog("[模式] Forge 1.16.5- (Legacy)", "INFO");

        // 确保占位符键已初始化
        if (placeholderKeys == null) buildPlaceholderKeys();

        // 版本隔离目录树补齐（失败即中止，避免游戏数据落到全局目录） //
        prepareIsolation();

        // 启动前命令 //
        executePreLaunchCommand();

        // 过滤 Java 9+ 参数 //
        // Legacy Forge 使用 LaunchWrapper，不兼容 Java 模块系统参数
        List<String> jvmBaseForLegacy = buildJvmBase().stream()
                .filter(arg -> !arg.startsWith("--add-")
                            && !arg.startsWith("--patch-module")
                            && !arg.contains("--illegal-access"))
                .collect(Collectors.toList());
        writeLog("已过滤 Java 9+ 参数，剩余 " + jvmBaseForLegacy.size() + " 个 JVM 参数", "INFO");

        // 类路径 //
        List<String> libs = getLibraries(true);
        String cp = formatClassPath(libs);
        writeLog("类路径条目数: " + libs.size(), "INFO");

        // Mods 目录 //
        Path modsDir = resolveModsDir();

        // 提取 tweakClass //
        List<String> tweaks = extractTweakClasses();

        // 有效游戏数据目录 //
        String effectiveGameDir = launchInfo.resolveEffectiveGameDir().toString();

        // 构建游戏参数 //
        // 两种格式:
        //   - arguments.game（JSON 格式，1.16.x 等）: 完整解析（含 --launchTarget/--fml.* 等
        //     Forge 专用参数，且已含可能存在的 --tweakClass），与 buildGameArgs 硬编码
        //     参数二选一，避免重复传入
        //   - minecraftArguments（旧字符串格式，1.12.2 等）: 用标准参数 + 单独提取的 tweakClass
        boolean hasJsonGameArgs = versionJson.has("arguments")
                && versionJson.get("arguments").isJsonObject();
        List<String> gameArgs = hasJsonGameArgs
                ? buildGameArgsFromJson(effectiveGameDir)
                : buildGameArgs(effectiveGameDir);
        // arguments.game 分支（buildGameArgsFromJson）不读 launchInfo.getGameArgs()，
        // 启动器注入的额外参数（如 1.17+ --profileProperties 本地玩家皮肤/披风直传）在此追加；
        // buildGameArgs 分支已含（见 BaseLauncher.buildGameArgs 末尾 addAll），不重复追加。
        if (hasJsonGameArgs) {
            List<String> extra = launchInfo.getGameArgs();
            if (extra != null) {
                for (String e : extra) {
                    if (!gameArgs.contains(e)) gameArgs.add(e);
                }
            }
        }

        // 组装启动参数 //
        List<String> argList = new ArrayList<>(jvmBaseForLegacy);
        argList.add("-Dfml.modFolders=" + modsDir);
        argList.add("-cp");
        argList.add(cp);
        argList.add(mainClass);
        argList.addAll(gameArgs);
        // 附加 tweakClass（arguments.game 路径通常已包含其中，contains 防重复附加）
        for (String t : tweaks) {
            if (!gameArgs.contains(t)) argList.add(t);
        }

        writeLog("启动参数构建完成，共 " + argList.size() + " 个参数", "INFO");
        writeLog("Mods 目录: " + modsDir, "INFO");
        writeLog("日志文件: " + logFile, "INFO");

        // 启动进程 //
        int exitCode = startProcessWithMonitoring(launchInfo.getJavaPath(), argList);

        // 退出后命令 //
        executePostExitCommand();

        return exitCode;
    }

    /**
     * 从 version.json 中提取 --tweakClass 参数
     *
     * Forge Legacy 通过 --tweakClass 指定 Mod 加载器入口，
     * 这些参数可能存在于:
     *   1. arguments.game 数组（仅提取 --tweakClass 及其紧随的值；
     *      其余选项如 --username/--gameDir 已由 buildGameArgs/buildGameArgsFromJson
     *      提供，此处不可重复提取，否则游戏参数会被重复且错乱传参）
     *   2. minecraftArguments 字符串（旧格式）
     *
     * @return 形如 ["--tweakClass", "xxx", "--tweakClass", "yyy"] 的参数对列表（已去重）
     */
    private List<String> extractTweakClasses() {
        // LinkedHashSet 去重并保持顺序：arguments.game 与 minecraftArguments 可能同时命中同一个 tweak
        Set<String> tweakSet = new LinkedHashSet<>();

        // 方式1: 从 arguments.game 提取（仅取 --tweakClass 及其紧随的值） //
        if (versionJson.has("arguments") && versionJson.get("arguments").isJsonObject()) {
            JsonObject argsObj = versionJson.getAsJsonObject("arguments");
            if (argsObj.has("game")) {
                JsonArray gameArgs = argsObj.getAsJsonArray("game");
                boolean expectValue = false;
                for (JsonElement arg : gameArgs) {
                    if (arg.isJsonPrimitive()) {
                        String str = arg.getAsString();
                        if (expectValue) {
                            tweakSet.add("--tweakClass");
                            tweakSet.add(str);
                            expectValue = false;
                        } else if ("--tweakClass".equals(str)) {
                            expectValue = true;
                        }
                    } else if (arg.isJsonObject()) {
                        // RuledArgument 形式: {"rules": [...], "value": ["--tweakClass", "xxx"]}
                        JsonObject obj = arg.getAsJsonObject();
                        if (!obj.has("value")) continue;
                        List<String> values = new ArrayList<>();
                        if (obj.get("value").isJsonPrimitive()) {
                            values.add(obj.get("value").getAsString());
                        } else if (obj.get("value").isJsonArray()) {
                            for (JsonElement v : obj.getAsJsonArray("value")) {
                                values.add(v.getAsString());
                            }
                        }
                        for (int i = 0; i + 1 < values.size(); i++) {
                            if ("--tweakClass".equals(values.get(i))) {
                                tweakSet.add("--tweakClass");
                                tweakSet.add(values.get(i + 1));
                            }
                        }
                    }
                }
            }
        }

        // 方式2: 从 minecraftArguments 提取（旧格式） //
        if (versionJson.has("minecraftArguments")) {
            String legacyArgs = versionJson.get("minecraftArguments").getAsString();
            String[] parts = legacyArgs.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if ("--tweakClass".equals(parts[i]) && i + 1 < parts.length) {
                    tweakSet.add("--tweakClass");
                    tweakSet.add(parts[i + 1]);
                }
            }
        }

        List<String> tweaks = new ArrayList<>(tweakSet);
        if (!tweaks.isEmpty()) {
            // 统计实际 --tweakClass 出现的次数（而非 size()/2，避免不成对参数误计数）
            long count = tweaks.stream().filter("--tweakClass"::equals).count();
            writeLog("提取到 " + count + " 个 tweakClass", "INFO");
        }

        return tweaks;
    }
}
