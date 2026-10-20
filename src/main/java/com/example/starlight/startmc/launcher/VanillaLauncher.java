// 原版 Minecraft 启动器 //
// 适用于无 Mod 加载器的纯净原版游戏启动。
// 不需要特殊的 classpath 处理或模块路径。
package com.example.starlight.startmc.launcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.example.starlight.startmc.LaunchInfo;

/**
 * 原版 Minecraft 启动器
 * 启动特征:
 *   - 标准 classpath（版本 JAR + libraries）
 *   - 标准游戏参数（--username, --gameDir 等）
 *   - 无需模块系统、tweakClass
 * 测试版本: 1.20.1（原版）
 */
public class VanillaLauncher extends BaseLauncher {

    public VanillaLauncher(LaunchInfo launchInfo) {
        super(launchInfo);
    }

    @Override
    public int launch() throws IOException, InterruptedException {
        writeLog("[模式] 原版 Minecraft", "INFO");

        // 确保占位符键已初始化
        if (placeholderKeys == null) buildPlaceholderKeys();

        // 版本隔离目录树补齐（失败即中止，避免游戏数据落到全局目录） //
        prepareIsolation();

        // 启动前命令 //
        executePreLaunchCommand();

        // 构建启动参数 //
        List<String> jvmBase = buildJvmBase();

        // 类路径：版本 JAR + 所有 libraries
        List<String> libs = getLibraries(true);
        String cp = formatClassPath(libs);
        writeLog("类路径条目数: " + libs.size(), "INFO");

        // 有效游戏数据目录（考虑版本隔离）
        String effectiveGameDir = launchInfo.resolveEffectiveGameDir().toString();

        // 完整启动参数
        List<String> startArgs = new ArrayList<>();
        startArgs.addAll(jvmBase);
        startArgs.add("-cp");
        startArgs.add(cp);
        startArgs.add(mainClass);
        startArgs.addAll(buildGameArgs(effectiveGameDir));

        // 启动进程 //
        writeLog("启动命令构建完成，正在启动 " + launchInfo.getVersion() + " ...", "INFO");
        writeLog("日志文件: " + logFile, "INFO");

        int exitCode = startProcessWithMonitoring(launchInfo.getJavaPath(), startArgs);

        // 退出后命令 //
        executePostExitCommand();

        return exitCode;
    }
}
