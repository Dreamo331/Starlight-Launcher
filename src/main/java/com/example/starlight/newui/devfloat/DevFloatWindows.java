/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui.devfloat;

/**
 * 开发者悬浮窗总入口（单例态管理，开发者选项页的按钮都走这里）。
 *
 * <p>窗口实例在各 XxxFloatWindow 内部持有（静态懒加载），本类只做转调；
 * 设置页重建不影响窗口生命周期，按钮文案用 {@code isOpen*()} 恢复状态。
 */
public final class DevFloatWindows {

    private DevFloatWindows() {
    }

    // 性能监控（CPU / 内存 / 磁盘） //
    public static void togglePerformance() {
        PerformanceFloatWindow.toggle();
    }

    public static boolean isPerformanceOpen() {
        return PerformanceFloatWindow.isOpen();
    }

    // 下载速度曲线 //
    public static void toggleDownloadSpeed() {
        DownloadSpeedFloatWindow.toggle();
    }

    public static boolean isDownloadSpeedOpen() {
        return DownloadSpeedFloatWindow.isOpen();
    }

    // 游戏运行状态挂件 //
    public static void toggleGameStatus() {
        GameStatusFloatWindow.toggle();
    }

    public static boolean isGameStatusOpen() {
        return GameStatusFloatWindow.isOpen();
    }

    // 网络请求统计（发送 / 成功 / 失败 / 空返回 / 流量） //
    public static void toggleNetworkStats() {
        NetworkStatsFloatWindow.toggle();
    }

    public static boolean isNetworkStatsOpen() {
        return NetworkStatsFloatWindow.isOpen();
    }
}
