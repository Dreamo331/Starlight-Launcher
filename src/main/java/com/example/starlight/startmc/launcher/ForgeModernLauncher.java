/** (C) Copyright 2026 Starlight. All rights reserved. */
// Forge Modern 加载器启动器（Minecraft 1.17+） //
// 适用于 Forge 1.17+ 使用模块化启动架构的版本。
// 关键特征:
//   - 使用 BootstrapLauncher 作为主类
//   - 需要构建模块路径（module-path）：bootstraplauncher, securejarhandler 等
//   - 需要 --add-opens / --add-exports 模块系统参数
//   - 使用 arguments.game JSON 格式，支持占位符替换
package com.example.starlight.startmc.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.example.starlight.startmc.LaunchInfo;

/**
 * Forge Modern 加载器启动器
 *
 * 启动特征:
 *   - mainClass = cpw.mods.modlauncher.BootstrapLauncher 或类似
 *   - 需要构建 --patch-module / --add-opens 模块参数
 *   - 使用 -p (module-path) 指定引导模块
 *   - 使用 -DignoreList 排除已通过模块加载的库
 *   - 使用 -DmergeModules 合并特定 JAR
 *
 * 必需引导模块:
 *   - cpw.mods:bootstraplauncher
 *   - cpw.mods:securejarhandler
 *   - org.ow2.asm:asm / asm-commons / asm-tree / asm-util / asm-analysis
 *   - net.minecraftforge:JarJarFileSystems
 *
 * 测试版本: 1.20.1-Forge_47.4.16
 */
public class ForgeModernLauncher extends BaseLauncher {

    /** Modern Forge 必需的引导模块列表 */
    private static final List<String> REQUIRED_MODULES = Arrays.asList(
        "cpw.mods:bootstraplauncher",
        "cpw.mods:securejarhandler",
        "org.ow2.asm:asm",
        "org.ow2.asm:asm-commons",
        "org.ow2.asm:asm-tree",
        "org.ow2.asm:asm-util",
        "org.ow2.asm:asm-analysis",
        "net.minecraftforge:JarJarFileSystems"
    );

    public ForgeModernLauncher(LaunchInfo launchInfo) {
        super(launchInfo);
    }

    @Override
    public int launch() throws IOException, InterruptedException {
        writeLog("[模式] Forge 1.17+ (Modern)", "INFO");

        // 确保占位符键已初始化
        if (placeholderKeys == null) buildPlaceholderKeys();

        // 版本隔离目录树补齐（失败即中止，避免游戏数据落到全局目录） //
        prepareIsolation();

        // 启动前命令 //
        executePreLaunchCommand();

        // 构建模块路径 //
        List<String> modulePaths = buildModulePath();
        String modulePathStr = modulePaths.stream()
                .collect(Collectors.joining(PlatformUtils.getModulePathSeparator()));
        writeLog("动态构建模块路径: 共 " + modulePaths.size() + " 个模块", "INFO");

        // 类路径 //
        List<String> libs = getLibraries(true);
        String cp = formatClassPath(libs);

        // Mods 目录 //
        Path modsDir = resolveModsDir();

        // 有效游戏数据目录 //
        String effectiveGameDir = launchInfo.resolveEffectiveGameDir().toString();

        // 构建 ignoreList //
        // ignoreList 以逗号分隔，版本名中若含逗号会破坏列表结构，需剔除
        String versionJarEntry = launchInfo.getVersion() + ".jar";
        if (versionJarEntry.contains(",")) {
            versionJarEntry = versionJarEntry.replace(",", "");
            writeLog("版本名包含逗号，ignoreList 条目已清理为: " + versionJarEntry, "WARN");
        }
        String ignoreList = REQUIRED_MODULES.stream()
                .map(m -> m.split(":")[1])
                .collect(Collectors.joining(","))
                + ",client-extra,fmlcore,javafmllanguage,lowcodelanguage,mclanguage,forge-,"
                + versionJarEntry;

        // 组装 JVM 参数 //
        List<String> jvmBase = buildJvmBase();

        List<String> startArgs = new ArrayList<>();
        startArgs.addAll(jvmBase);
        startArgs.add("-Djava.net.preferIPv6Addresses=system");
        startArgs.add("-DignoreList=" + ignoreList);
        startArgs.add("-DmergeModules=" + resolveMergeModules());
        startArgs.add("-DlibraryDirectory=" + libsRoot);
        startArgs.add("-Dfml.modFolders=" + modsDir);

        // 版本 JSON 自带的 arguments.jvm（Forge 1.17+ 必需的 --add-opens/--add-exports/-Dxxx/-p）：
        // 以前硬编码在下面，换 Forge 小版本就可能漏参导致 BootstrapLauncher 起不来。
        // 现在先解析 JSON 的 jvm 参数，硬编码只保留版本无关的兜底。
        List<String> jsonJvm = buildJvmArgsFromJson(cp);
        if (!jsonJvm.isEmpty()) {
            writeLog("已解析 version.json arguments.jvm: " + jsonJvm.size() + " 条", "INFO");
        }

        // 模块路径与模块系统参数
        // JSON 的 jvm 参数通常已含 -p ${library_directory}/...，这里动态扫出的模块路径作为兜底追加，
        // 若 JSON 未提供 -p 才使用本启动器构建的模块路径。
        startArgs.addAll(jsonJvm);
        if (jsonJvm.stream().noneMatch(a -> a.equals("-p"))) {
            startArgs.add("-p");
            startArgs.add(modulePathStr);
            startArgs.add("--add-modules");
            startArgs.add("ALL-MODULE-PATH");
        }
        // 硬编码 --add-opens/--add-exports 仅在 JSON 未提供任何同类参数时兜底
        if (jsonJvm.stream().noneMatch(a -> a.startsWith("--add-opens") || a.startsWith("--add-exports"))) {
            startArgs.add("--add-opens");
            startArgs.add("java.base/java.util.jar=cpw.mods.securejarhandler");
            startArgs.add("--add-opens");
            startArgs.add("java.base/java.lang.invoke=cpw.mods.securejarhandler");
            startArgs.add("--add-exports");
            startArgs.add("java.base/sun.security.util=cpw.mods.securejarhandler");
            startArgs.add("--add-exports");
            startArgs.add("jdk.naming.dns/com.sun.jndi.dns=java.naming");
        }

        // 类路径（version.json 的 jvm 参数通常以 -cp ${classpath} 结尾，这里兜底补上）
        if (jsonJvm.stream().noneMatch(a -> a.equals("-cp"))) {
            startArgs.add("-cp");
            startArgs.add(cp);
        }

        // 主类及游戏参数
        startArgs.add(mainClass);
        startArgs.addAll(buildGameArgsFromJson(effectiveGameDir));

        // 启动进程 //
        writeLog("启动命令构建完成，正在启动 " + launchInfo.getVersion() + " ...", "INFO");
        writeLog("Mods 目录: " + modsDir, "INFO");
        writeLog("日志文件: " + logFile, "INFO");

        int exitCode = startProcessWithMonitoring(launchInfo.getJavaPath(), startArgs);

        // 退出后命令 //
        executePostExitCommand();

        return exitCode;
    }

    /**
     * 从 libraries 中动态解析 mergeModules 所需的 JNA jar 文件名
     *
     * JNA 版本随 Forge 小版本变化（如 5.10.0 / 5.12.1），硬编码会导致
     * 版本不匹配时 BootstrapLauncher 合并模块失败。按 net.java.dev.jna 组
     * 前缀从 version.json 的 libraries 中查找实际文件名；查不到时回退默认值。
     */
    private String resolveMergeModules() {
        List<String> jnaJars = new ArrayList<>();
        if (versionJson != null && versionJson.has("libraries")) {
            for (JsonElement libElem : versionJson.getAsJsonArray("libraries")) {
                if (!libElem.isJsonObject()) continue;
                JsonObject lib = libElem.getAsJsonObject();
                if (!lib.has("name")) continue;
                String name = lib.get("name").getAsString();
                if (!name.startsWith("net.java.dev.jna:")) continue;

                String fileName = null;
                if (lib.has("downloads")
                        && lib.getAsJsonObject("downloads").has("artifact")
                        && lib.getAsJsonObject("downloads").getAsJsonObject("artifact").has("path")) {
                    String path = lib.getAsJsonObject("downloads")
                            .getAsJsonObject("artifact").get("path").getAsString();
                    fileName = java.nio.file.Paths.get(path).getFileName().toString();
                } else {
                    // 降级：从 name 推导 Maven 文件名
                    String[] parts = name.split(":");
                    if (parts.length >= 3) {
                        fileName = parts[1] + "-" + parts[2] + ".jar";
                    }
                }
                if (fileName != null && !fileName.isEmpty() && !jnaJars.contains(fileName)) {
                    jnaJars.add(fileName);
                }
            }
        }

        if (jnaJars.isEmpty()) {
            // 未在 libraries 中声明 JNA 时使用历史默认值（与旧版行为一致）
            return "jna-5.10.0.jar,jna-platform-5.10.0.jar";
        }
        return String.join(",", jnaJars);
    }

    /**
     * 从 libraries 中查找必需的引导模块 JAR 文件
     *
     * 扫描 libsRoot 目录，根据模块坐标 (group:artifact) 匹配对应的 JAR。
     * 同时支持:
     *   - downloads.artifact.path（精确路径）
     *   - name 字段推导的 Maven 路径（降级方案）
     *
     * 缺失任何必需模块都会记录 WARN 日志，帮助排查启动失败原因。
     */
    private List<String> buildModulePath() {
        List<String> modulePaths = new ArrayList<>();
        JsonArray libraries = versionJson.getAsJsonArray("libraries");

        if (libraries == null) {
            writeLog("警告: version.json 中无 libraries 字段，无法构建模块路径", "WARN");
            return modulePaths;
        }

        for (String module : REQUIRED_MODULES) {
            String[] parts = module.split(":");
            String group = parts[0];
            String artifact = parts[1];
            boolean found = false;

            for (JsonElement libElem : libraries) {
                JsonObject lib = libElem.getAsJsonObject();
                if (!lib.has("name")) continue;

                String name = lib.get("name").getAsString();
                if (!name.startsWith(group + ":" + artifact + ":")) continue;

                // 优先使用 downloads.artifact.path
                if (lib.has("downloads")
                    && lib.getAsJsonObject("downloads").has("artifact")) {
                    String path = lib.getAsJsonObject("downloads")
                            .getAsJsonObject("artifact")
                            .get("path").getAsString();
                    Path libPath = libsRoot.resolve(path);
                    if (Files.exists(libPath)) {
                        modulePaths.add(libPath.toString());
                        found = true;
                        break;
                    }
                }

                // 降级: 从 name 推导
                String[] nameParts = name.split(":");
                String groupPath = nameParts[0].replace('.', '/');
                String art = nameParts[1];
                String ver = nameParts[2];
                String classifier = nameParts.length >= 4 ? "-" + nameParts[3] : "";
                String path = groupPath + "/" + art + "/" + ver + "/" + art + "-" + ver + classifier + ".jar";
                Path libPath = libsRoot.resolve(path);
                if (Files.exists(libPath)) {
                    modulePaths.add(libPath.toString());
                    found = true;
                }
                // 只在真正命中时 break；未命中继续尝试下一个同名 library 条目
                // （同一 artifact 多版本并存时避免模块路径缺条目）
                if (found) break;
            }

            if (!found) {
                writeLog("警告: 必需引导模块未找到: " + module + "，启动可能失败", "WARN");
            }
        }

        return modulePaths.stream().distinct().collect(Collectors.toList());
    }
}
