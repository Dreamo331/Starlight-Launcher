package com.example.starlight.util;

/**
 * 启动流程日志（PCL 标准）。
 *
 * <p>对齐 PCL（Plain Craft Launcher 2）的启动日志体系（ModLaunch.vb 的 McLaunchLog）：
 * 行格式由 logback 统一输出为 {@code [HH:mm:ss.SSS] [级别] 文本}，对应 PCL 界面
 * {@code [HH:mm:ss.fff] 文本} 与文件日志（Log1.txt）带级别前缀的标准；
 * 启动关键节点采用 PCL 文案（预检测已通过 / 登录方式 / 选择的 Java /
 * 启动参数区块 / 已启动游戏进程 / ~ 基础参数 ~ / ~ 登录参数 ~ / 开始启动结束处理 等）。
 *
 * <p>只写日志文件（logs/starlight.log，与启动器运行日志同一文件，类似 PCL 的 Log1.txt），
 * 不触碰任何 UI。
 *
 * <p>安全：输出 AccessToken / UUID / ClientToken 等敏感字段前必须经 {@link #mask(String)}
 * 打码，与 PCL PclLogger 的 FilterAccessToken 行为一致。
 */
public final class LaunchLog {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger("LaunchLog");

    private LaunchLog() {}

    /** 普通启动流程节点（Info） */
    public static void info(String msg) {
        log.info(msg);
    }

    /** 启动流程告警节点（Warn） */
    public static void warn(String msg) {
        log.warn(msg);
    }

    /** 启动流程错误节点（Error） */
    public static void error(String msg) {
        log.error(msg);
    }

    /**
     * 敏感信息打码：保留前 4 与后 4 位，中间用 **** 覆盖；短串整体打码。
     */
    public static String mask(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }
}
