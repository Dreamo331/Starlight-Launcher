/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui.devfloat;

import com.example.starlight.util.SystemInfoMonitor;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * 悬浮性能监控窗：CPU / 内存 / 磁盘读写三条实时曲线。
 *
 * <p>数据来自 {@link SystemInfoMonitor} 的后台采样线程（启动时已预热，无额外开销）；
 * 磁盘为读写 MB/s 之和，首次采样前（-1）按 0 画。
 */
public final class PerformanceFloatWindow extends DevFloatWindow {

    private static PerformanceFloatWindow instance;

    private FloatLineChart chart;
    private Label summary;

    private PerformanceFloatWindow() {
        super("性能监控", 344, 226);
    }

    @Override
    protected void buildContent(VBox root) {
        // 本方法在父类构造器内被回调，子类字段初始化器此时尚未执行——控件必须在这里创建
        chart = new FloatLineChart(320, 160);
        summary = new Label();
        chart.addSeries("CPU", Color.web("#4f8ef7"), v -> String.format("%.0f%%", v));
        chart.addSeries("内存", Color.web("#22c55e"), v -> String.format("%.0f%%", v));
        chart.addSeries("磁盘", Color.web("#f59e0b"), v -> String.format("%.1fMB/s", v));
        summary.setStyle("-fx-text-fill: #d1d5db; -fx-font-size: 11px; -fx-font-family: 'Consolas', monospace;");
        root.getChildren().addAll(chart, summary, dimLabel("采样自 SystemInfoMonitor 后台线程，1s 一点"));
    }

    @Override
    protected void tick() {
        double cpu = SystemInfoMonitor.getCpuPercent();
        double mem = SystemInfoMonitor.getMemPercent();
        double read = SystemInfoMonitor.getDiskReadMBps();
        double write = SystemInfoMonitor.getDiskWriteMBps();
        double disk = (read < 0 ? 0 : read) + (write < 0 ? 0 : write);
        chart.push(cpu < 0 ? 0 : cpu, mem < 0 ? 0 : mem, disk);
        summary.setText(String.format("CPU %.0f%%  ·  内存 %.0f%%  ·  磁盘 读%.1f 写%.1f MB/s",
                cpu < 0 ? 0 : cpu, mem < 0 ? 0 : mem,
                read < 0 ? 0 : read, write < 0 ? 0 : write));
    }

    @Override
    protected void onHidden() {
        super.onHidden();
        chart.clearPoints();
    }

    public static boolean isOpen() {
        return instance != null && instance.isShowing();
    }

    public static void toggle() {
        if (instance == null) instance = new PerformanceFloatWindow();
        if (instance.isShowing()) {
            instance.close();
        } else {
            instance.open();
        }
    }
}
