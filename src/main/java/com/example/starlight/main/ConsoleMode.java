/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.main;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.LoggerFactory;

/**
 * 命令行启动模式（隐藏调试开关，不向普通用户暴露，设置页不提供入口）。
 *
 * <p>开启需要<b>两个条件同时满足</b>：
 * <ol>
 *   <li>配置文件 {@code Starlight-Launcher/starlight.ini} 的 [Launcher] 段写入
 *       {@code ConsoleMode=true}（该键不登记进任何保存白名单，界面保存配置时原样保留）；</li>
 *   <li>启动命令行带 {@code --console} 参数（该参数会在进入 GUI/CLI 前被剥离，不影响后续参数解析）。</li>
 * </ol>
 *
 * <p>效果：把与 {@code logback.xml} 文件日志同样式的控制台 appender 挂到根日志器，
 * 日志实时输出到<b>启动本程序的那个命令行窗口</b>（不新建窗口）。
 * 双击 exe 启动时没有控制台，输出静默丢弃，无副作用。
 *
 * <p>默认关闭时控制台无输出（logback 仅写文件），exe 被误从终端拉起也不会刷屏。
 */
public final class ConsoleMode {

    /** 命令行参数（不带值）：显式开启信号之一 */
    public static final String ARG = "--console";

    /** 配置文件隐藏键：[Launcher] ConsoleMode=true */
    public static final String CONFIG_KEY = "ConsoleMode";

    private static volatile boolean enabled = false;

    private ConsoleMode() {
    }

    /**
     * 从命令行参数中剥离 {@link #ARG}；若「配置键 + 命令行参数」两条件同时满足则开启控制台日志。
     *
     * <p>开发环境（JVM、classpath 含 classes 目录）自动开启，保持 IDEA 控制台看日志的习惯不变；
     * 打包后的 exe 没有 classpath，严格走双条件门控。
     *
     * @return 剥离 {@code --console} 后的参数数组（供后续 CLI 解析 / JavaFX 启动使用）
     */
    public static String[] stripAndMaybeEnable(String[] args) {
        boolean hasArg = false;
        java.util.List<String> rest = new java.util.ArrayList<>();
        if (args != null) {
            for (String a : args) {
                if (ARG.equalsIgnoreCase(a)) {
                    hasArg = true;
                } else {
                    rest.add(a);
                }
            }
        }
        if (hasArg && isConfigEnabled()) {
            enable();
        } else if (isDevRuntime()) {
            enable();
        }
        return rest.toArray(new String[0]);
    }

    /** 是否为开发环境 JVM 运行（native-image exe 的 classpath 为空，天然判否） */
    private static boolean isDevRuntime() {
        String cp = System.getProperty("java.class.path", "");
        return cp.contains("classes");
    }

    /** 配置键是否开启（读不到配置 / 解析异常均视为关闭） */
    private static boolean isConfigEnabled() {
        try {
            return "true".equalsIgnoreCase(
                    com.example.starlight.config.StarlightConfig.get(CONFIG_KEY));
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前是否已开启控制台日志 */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 把与 logback.xml CONSOLE 同样式的控制台 appender 挂到根日志器（幂等）。
     * appender 在此用代码构造而不引用 logback.xml 里的定义：xml 默认不挂 CONSOLE，
     * 也不受 logback 版本对「具名 appender 查找」API 差异的影响。
     */
    public static synchronized void enable() {
        if (enabled) return;
        enabled = true;
        try {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(ctx);
            encoder.setPattern("%d{HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n");
            encoder.start();

            ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
            appender.setContext(ctx);
            appender.setName("SL_CONSOLE_MODE");
            appender.setEncoder(encoder);
            appender.start();

            Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
            root.addAppender(appender);
            root.info("[ConsoleMode] 命令行日志已开启（ConsoleMode=true 且启动参数含 --console）");
        } catch (Throwable ignored) {
            // 控制台日志是调试附加品，挂载失败不影响启动器运行
        }
    }
}
