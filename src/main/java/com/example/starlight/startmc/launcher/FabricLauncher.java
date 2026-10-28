// Fabric / Quilt 加载器启动器 //
// 适用于 Fabric Loader 与 Quilt Loader 的 Mod 加载环境。
// Fabric 使用 KnotClient 作为主类，通过 -cp 标准类路径启动。
// mods 目录由 --gameDir（版本隔离时为版本目录）决定，不需要额外参数。
package com.example.starlight.startmc.launcher;


import com.example.starlight.util.I18N;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.starlight.startmc.LaunchInfo;

/**
 * Fabric / Quilt 加载器启动器
 *
 * 启动特征:
 *   - mainClass = net.fabricmc.loader.impl.launch.knot.KnotClient
 *   - 类路径结构与原版一致（版本 JAR + libraries）
 *   - mods 目录跟随 --gameDir：开启版本隔离时即 versions/&lt;版本&gt;/mods
 *
 * 测试版本: 1.20.1-Fabric 0.17.2
 */
public class FabricLauncher extends BaseLauncher {

    public FabricLauncher(LaunchInfo launchInfo) {
        super(launchInfo);
    }

    @Override
    public int launch() throws IOException, InterruptedException {
        writeLog(I18N.get("launcher.t0006"), "INFO");

        // 确保占位符键已初始化
        if (placeholderKeys == null) buildPlaceholderKeys();

        // 版本隔离目录树补齐（失败即中止，避免游戏数据落到全局目录） //
        prepareIsolation();

        // 启动前命令 //
        executePreLaunchCommand();

        // 构建启动参数 //
        List<String> jvmBase = buildJvmBase();

        // 类路径
        List<String> libs = getLibraries(true);
        String cp = formatClassPath(libs);
        writeLog(I18N.get("launcher.p0053", libs.size()), "INFO");

        // 有效游戏数据目录
        String effectiveGameDir = launchInfo.resolveEffectiveGameDir().toString();
        writeLog(I18N.get("launcher.p0054", Path.of(effectiveGameDir, "mods")), "INFO");

        // 完整启动参数
        // 注意：-D 参数必须位于 -cp 之前（JVM 参数区），否则会被当作游戏参数
        List<String> startArgs = new ArrayList<>();
        startArgs.addAll(jvmBase);
        startArgs.add("-cp");
        startArgs.add(cp);
        startArgs.add(mainClass);
        // Fabric 的 version.json 经 inheritsFrom 继承原版 arguments.game，
        // 用 JSON 解析可保留 RuledArgument（如 has_custom_resolution 控制的 --width/--height）
        // 等版本特定参数，与 Forge Modern 一致；无 arguments.game 时内部回退到硬编码参数
        startArgs.addAll(buildGameArgsFromJson(effectiveGameDir));

        // 启动进程 //
        writeLog(I18N.get("launcher.p0055", launchInfo.getVersion() + " ..."), "INFO");
        writeLog(I18N.get("launcher.p0056", logFile), "INFO");

        int exitCode = startProcessWithMonitoring(launchInfo.getJavaPath(), startArgs);

        // 退出后命令 //
        executePostExitCommand();

        return exitCode;
    }
}
