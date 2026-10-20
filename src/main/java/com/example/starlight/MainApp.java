package com.example.starlight;

import com.example.starlight.config.AppDirs;
import com.example.starlight.main.CliHandler;
import com.example.starlight.main.ConsoleMode;
import javafx.application.Application;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

public class MainApp {

    public static void main(String[] args) {
        // 必须最先执行：确定用户级数据根目录（默认 ~/.starlight，--portable 回落工作目录），
        // 并完成一次性历史数据迁移。任何早于这里的配置读取都会写错目录。
        String[] afterPortable = AppDirs.initAndStrip(args);
        installCrashLogger();
        installJulBridge();
        // --console 是「命令行启动模式」的显式开关，剥离后再进入 GUI/CLI 分流
        // （必须在分流之前处理：带参数启动会走 CliHandler 而开不了界面）
        String[] rest = ConsoleMode.stripAndMaybeEnable(afterPortable);
        if (rest.length > 0) {
            CliHandler.processCommandArgs(rest);
        } else {
            Application.launch(JavaFXLauncher.class, rest);
        }
    }

    /**
     * 安装 JUL→SLF4J 桥：插件系统（PluginManager / PluginScanner / PluginApiServer / API）
     * 使用 java.util.logging 输出，默认不走 logback，导致其日志没有级别颜色；
     * 桥接后统一走 logback 的级别标识与控制台颜色。
     */
    private static void installJulBridge() {
        try {
            java.util.logging.LogManager.getLogManager().reset();
            org.slf4j.bridge.SLF4JBridgeHandler.install();
        } catch (Throwable ignored) {
            // 桥安装失败不影响启动器主功能
        }
    }

    /**
     * 全局未捕获异常兜底：双击 exe 启动时没有控制台，stderr 看不见任何报错，
     * 崩溃会被静默吞掉（表现为窗口卡死/闪退且无任何线索）。
     * 把未捕获异常追加写入 logs/launcher-crash.log（相对工作目录），命令行下同时打印 stderr。
     */
    private static void installCrashLogger() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                Path log = Path.of("logs", "launcher-crash.log");
                if (log.getParent() != null) {
                    Files.createDirectories(log.getParent());
                }
                String text = "[" + LocalDateTime.now() + "] uncaught in thread \"" + t.getName()
                        + "\":\n" + stackTrace(e) + "\n";
                Files.writeString(log, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // 崩溃日志写不进文件就算了，别再抛
            }
            System.err.println("Uncaught exception in thread \"" + t.getName() + "\":");
            e.printStackTrace();
        });
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
