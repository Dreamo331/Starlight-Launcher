/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.crash;


import com.example.starlight.util.I18N;
import com.example.starlight.model.ErrorCode;

import java.util.List;

/**
 * 游戏崩溃诊断数据 — 收集启动失败时的所有关联信息
 */
public class CrashDiagnosticData {

    /** 匹配到的错误码 */
    private ErrorCode errorCode;

    /** 错误描述（异常消息全文） */
    private String errorDescription;

    /** 游戏版本 ID */
    private String gameVersion;

    /** 加载器类型（Fabric / Forge / NeoForge / Vanilla） */
    private String loaderType;

    /** Java 版本 */
    private String javaVersion;

    /** Java 路径 */
    private String javaPath;

    /** 分配的最大内存 (MB) */
    private int maxMemory;

    /** 操作系统信息 */
    private String osInfo;

    /** 系统架构 */
    private String osArch;

    /** 游戏目录 */
    private String gameDir;

    /** crash-report 文件完整路径，可能为 null */
    private String crashReportPath;

    /** crash-report 解析出的结构化信息，可能为 null */
    private CrashReportParser.CrashInfo crashInfo;

    /** crash-report 原始全文 */
    private String crashReportContent;

    /** latest.log 末尾关键行 */
    private List<String> latestLogTail;

    /** latest.log 完整路径 */
    private String latestLogPath;

    /** 启动命令全文 */
    private String launchCommand;

    /** 游戏进程退出码 */
    private int exitCode;

    /** 启动器版本 */
    private String launcherVersion;

    /** 系统总物理内存 */
    private String totalMemory;

    /** 环境提示与下一步建议（如「检测到 GraalVM，建议换标准 OpenJDK 对照」），可为 null */
    private String runtimeAdvice;

    // Constructor //

    public CrashDiagnosticData() {
    }

    // 构建辅助 //

    /**
     * 构建用于显示的完整错误摘要文本
     */
    public String buildSummaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append(I18N.get("crash.t0001"));
        sb.append(I18N.get("crash.t0002")).append(errorCode.getCode()).append("\n");
        sb.append(I18N.get("crash.t0003")).append(errorCode.getDisplayName()).append("\n");
        sb.append(I18N.get("crash.t0011")).append(errorDescription != null ? errorDescription : I18N.get("crash.t0012")).append("\n");
        // 退出码是判断「真崩溃 / 被外部结束 / 启动阶段退出」最直接的证据，报告里必须带上
        sb.append(I18N.get("crash.t0004")).append(exitCode).append("\n");
        sb.append(I18N.get("crash.t0005"));
        sb.append(I18N.get("crash.t0013")).append(launcherVersion != null ? launcherVersion : I18N.get("crash.t0014")).append("\n");
        sb.append(I18N.get("crash.t0015")).append(gameVersion != null ? gameVersion : I18N.get("crash.t0016")).append("\n");
        sb.append(I18N.get("crash.t0017")).append(loaderType != null ? loaderType : I18N.get("crash.t0018")).append("\n");
        sb.append("Java: ").append(javaVersion != null ? javaVersion : I18N.get("crash.t0006")).append("\n");
        sb.append(I18N.get("crash.t0019")).append(javaPath != null ? javaPath : I18N.get("crash.t0020")).append("\n");
        sb.append(I18N.get("crash.t0021")).append(maxMemory > 0 ? maxMemory + " MB" : I18N.get("crash.t0022")).append("\n");
        sb.append(I18N.get("crash.t0023")).append(osInfo != null ? osInfo : I18N.get("crash.t0024")).append("\n");
        sb.append(I18N.get("crash.t0025")).append(osArch != null ? osArch : I18N.get("crash.t0026")).append("\n");
        sb.append(I18N.get("crash.t0027")).append(totalMemory != null ? totalMemory : I18N.get("crash.t0028")).append("\n");
        sb.append(I18N.get("crash.t0029")).append(gameDir != null ? gameDir : I18N.get("crash.t0030")).append("\n");

        if (runtimeAdvice != null && !runtimeAdvice.isBlank()) {
            sb.append(I18N.get("crash.t0007")).append(runtimeAdvice).append("\n");
        }

        if (crashReportPath != null) {
            sb.append("\n--- Crash Report ---\n").append(crashReportContent != null ? crashReportContent : crashReportPath).append("\n");
        }

        if (launchCommand != null) {
            sb.append(I18N.get("crash.t0008")).append(launchCommand).append("\n");
        }

        if (latestLogTail != null && !latestLogTail.isEmpty()) {
            sb.append(I18N.get("crash.t0009"));
            for (String line : latestLogTail) {
                sb.append(line).append("\n");
            }
        }

        sb.append(I18N.get("crash.t0010"));
        return sb.toString();
    }

    // Getters & Setters //

    public ErrorCode getErrorCode() { return errorCode; }
    public void setErrorCode(ErrorCode errorCode) { this.errorCode = errorCode; }

    public String getErrorDescription() { return errorDescription; }
    public void setErrorDescription(String errorDescription) { this.errorDescription = errorDescription; }

    public String getGameVersion() { return gameVersion; }
    public void setGameVersion(String gameVersion) { this.gameVersion = gameVersion; }

    public String getLoaderType() { return loaderType; }
    public void setLoaderType(String loaderType) { this.loaderType = loaderType; }

    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }

    public String getJavaPath() { return javaPath; }
    public void setJavaPath(String javaPath) { this.javaPath = javaPath; }

    public int getMaxMemory() { return maxMemory; }
    public void setMaxMemory(int maxMemory) { this.maxMemory = maxMemory; }

    public String getOsInfo() { return osInfo; }
    public void setOsInfo(String osInfo) { this.osInfo = osInfo; }

    public String getOsArch() { return osArch; }
    public void setOsArch(String osArch) { this.osArch = osArch; }

    public String getGameDir() { return gameDir; }
    public void setGameDir(String gameDir) { this.gameDir = gameDir; }

    public String getCrashReportPath() { return crashReportPath; }
    public void setCrashReportPath(String crashReportPath) { this.crashReportPath = crashReportPath; }

    public CrashReportParser.CrashInfo getCrashInfo() { return crashInfo; }
    public void setCrashInfo(CrashReportParser.CrashInfo crashInfo) { this.crashInfo = crashInfo; }

    public String getCrashReportContent() { return crashReportContent; }
    public void setCrashReportContent(String crashReportContent) { this.crashReportContent = crashReportContent; }

    public List<String> getLatestLogTail() { return latestLogTail; }
    public void setLatestLogTail(List<String> latestLogTail) { this.latestLogTail = latestLogTail; }

    public String getLatestLogPath() { return latestLogPath; }
    public void setLatestLogPath(String latestLogPath) { this.latestLogPath = latestLogPath; }

    public String getLaunchCommand() { return launchCommand; }
    public void setLaunchCommand(String launchCommand) { this.launchCommand = launchCommand; }

    public int getExitCode() { return exitCode; }
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }

    public String getLauncherVersion() { return launcherVersion; }
    public void setLauncherVersion(String launcherVersion) { this.launcherVersion = launcherVersion; }

    public String getTotalMemory() { return totalMemory; }
    public void setTotalMemory(String totalMemory) { this.totalMemory = totalMemory; }

    public String getRuntimeAdvice() { return runtimeAdvice; }
    public void setRuntimeAdvice(String runtimeAdvice) { this.runtimeAdvice = runtimeAdvice; }
}
