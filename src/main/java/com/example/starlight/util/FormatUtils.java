package com.example.starlight.util;

/**
 * 通用格式化工具（从 LauncherView 抽离，逻辑与原实现保持一致）。
 *
 * <p>全部为无状态静态方法，不依赖 JavaFX。
 */
public final class FormatUtils {

    private FormatUtils() {
    }

    /** 下载量缩写（10000 -> 1.0万） */
    public static String formatCompactNumber(int n) {
        if (n >= 10000) return String.format("%.1f万", n / 10000.0);
        return String.valueOf(n);
    }

    /** 文件大小格式化：B / KB / MB / GB */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** 下载速率格式化：{@code 1.2 MB/s} / {@code 340 KB/s} */
    public static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024 * 1024));
        }
        if (bytesPerSecond >= 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024);
        }
        return String.format(java.util.Locale.ROOT, "%.0f B/s", bytesPerSecond);
    }

    /** 内存占用率颜色：>85% 红、>60% 黄、其余绿（与首页统计条一致） */
    public static String usageColor(double pct) {
        return pct > 85 ? "#ef4444" : pct > 60 ? "#f59e0b" : "#22c55e";
    }

    /**
     * 运行时长格式化：{@code 45秒} / {@code 12分钟} / {@code 2小时18分}。
     *
     * <p>不足 1 分钟按秒显示，不足 1 小时按分钟显示，超过 1 小时显示「N小时M分」
     * （分钟为 0 时省略「0分」）。
     */
    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return minutes > 0 ? hours + "小时" + minutes + "分" : hours + "小时";
        }
        if (minutes > 0) return minutes + "分钟";
        return totalSeconds + "秒";
    }
}
