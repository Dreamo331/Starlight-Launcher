/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui.devfloat;


import com.example.starlight.util.I18N;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.NetTrafficStats;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * 下载速度悬浮窗：全局实时速率曲线。
 *
 * <p>数据源是 {@link NetTrafficStats#getBytes()}——各下载引擎（协调器 / 分块下载器 /
 * HTTP 引擎 / 资产与库下载器等）的读取循环统一上报字节，1s 差分即全局速率；
 * 不依赖限速开关。
 */
public final class DownloadSpeedFloatWindow extends DevFloatWindow {

    private static DownloadSpeedFloatWindow instance;

    private FloatLineChart chart;
    private Label summary;

    private long lastBytes = -1;
    private long lastNanos = -1;

    private DownloadSpeedFloatWindow() {
        super(I18N.get("devfloat.t0001"), 344, 226);
    }

    @Override
    protected void buildContent(VBox root) {
        // 本方法在父类构造器内被回调，子类字段初始化器此时尚未执行——控件必须在这里创建
        chart = new FloatLineChart(320, 160);
        summary = new Label();
        chart.addSeries(I18N.get("devfloat.t0002"), Color.web("#22d3ee"), v -> FormatUtils.formatSpeed(v));
        summary.setStyle("-fx-text-fill: #d1d5db; -fx-font-size: 11px; -fx-font-family: 'Consolas', monospace;");
        root.getChildren().addAll(chart, summary, dimLabel(I18N.get("devfloat.t0003")));
    }

    @Override
    protected void tick() {
        long now = System.nanoTime();
        long bytes = NetTrafficStats.getBytes();
        double bps = 0;
        if (lastBytes >= 0 && lastNanos > 0 && now > lastNanos) {
            bps = (bytes - lastBytes) / ((now - lastNanos) / 1_000_000_000.0);
            if (bps < 0) bps = 0; // 计数器只在进程内单调，防御性兜底
        }
        lastBytes = bytes;
        lastNanos = now;
        chart.push(bps);
        summary.setText(I18N.get("dsfw.r0001", FormatUtils.formatSpeed(bps), FormatUtils.formatFileSize(bytes)));
    }

    @Override
    protected void onHidden() {
        super.onHidden();
        chart.clearPoints();
        lastBytes = -1;
        lastNanos = -1;
    }

    public static boolean isOpen() {
        return instance != null && instance.isShowing();
    }

    public static void toggle() {
        if (instance == null) instance = new DownloadSpeedFloatWindow();
        if (instance.isShowing()) {
            instance.close();
        } else {
            instance.open();
        }
    }
}
