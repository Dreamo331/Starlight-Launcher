package com.example.starlight.gui;

import com.example.starlight.gamebat.MinecraftLauncherBuilder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 独立游戏启动器 - 解决启动器未响应问题
 */
public class IndependentGameLauncher {

    private static Process gameProcess = null;
    private static final ExecutorService GAME_POOL = Executors.newFixedThreadPool(2);

    /**
     * 启动游戏（完全独立，不阻塞任何线程）
     */
    public static void launchGameIndependently(UIGeneralControlClass.LaunchConfig config) {
        GAME_POOL.submit(() -> {
            try {
                // 1. 读取版本 JSON
                Path jsonPath = Paths.get(config.gameDir, "versions",
                        config.version, config.version + ".json");
                String jsonStr = Files.readString(jsonPath);

                // 2. 构建启动命令
                String launchCommand = MinecraftLauncherBuilder.buildLaunchCommand(
                        jsonPath.toString(),
                        config.gameDir,
                        config.version,
                        config.javaPath,
                        config.userName,
                        config.uuid,
                        config.accessToken,
                        config.gameArgs,
                        config.jvmArgs,
                        config.userType
                );

                System.out.println("Launch command: " + launchCommand);

                // 3. 在新进程中启动游戏（关键！）
                ProcessBuilder pb = new ProcessBuilder();
                pb.directory(new File(config.gameDir));

                // Windows 用 cmd /c，Linux/mac 用 bash -c
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    pb.command("cmd.exe", "/c", launchCommand);
                } else {
                    pb.command("bash", "-c", launchCommand);
                }

                // 重定向输出到日志文件
                Path logDir = Paths.get("logs");
                if (!Files.exists(logDir)) Files.createDirectories(logDir);

                String logFile = "logs/game_" + config.version + "_" +
                        System.currentTimeMillis() + ".log";
                pb.redirectOutput(new File(logFile));
                pb.redirectErrorStream(true);

                // 4. 启动进程（不等待，立刻返回）
                gameProcess = pb.start();

                System.out.println("Game started, PID: " + gameProcess.pid());
                System.out.println("  Log file: " + logFile);

                // 5. 在后台等待进程结束
                GAME_POOL.submit(() -> {
                    try {
                        int exitCode = gameProcess.waitFor();
                        System.out.println("Game exited, exit code: " + exitCode);
                        gameProcess = null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

            } catch (Exception e) {
                System.err.println("Launch failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 强制停止游戏
     */
    public static void stopGame() {
        if (gameProcess != null && gameProcess.isAlive()) {
            gameProcess.destroyForcibly();
            System.out.println("Game force-stopped");
            gameProcess = null;
        }
    }

    /**
     * 检查游戏是否正在运行
     */
    public static boolean isGameRunning() {
        return gameProcess != null && gameProcess.isAlive();
    }
}