/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui.devfloat;

import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.NetTrafficStats;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.example.starlight.newui.ui.PageKit;

/**
 * 网络请求统计悬浮窗：发送 / 成功 / 失败 / 空返回 / 累计流量。
 *
 * <p>数据来自 {@link NetTrafficStats}（全程计数，与调试模式开关无关）；
 * 口径见其类注释。成功/失败/空返回按 HTTP 状态与正文字节数判定。
 */
public final class NetworkStatsFloatWindow extends DevFloatWindow {

    private static NetworkStatsFloatWindow instance;

    private Label sentLabel;
    private Label okLabel;
    private Label failLabel;
    private Label emptyLabel;
    private Label recvLabel;
    private Label bytesLabel;

    private NetworkStatsFloatWindow() {
        super("网络请求统计", 280, 216);
    }

    @Override
    protected void buildContent(VBox root) {
        // 本方法在父类构造器内被回调，子类字段初始化器此时尚未执行——控件必须在这里创建
        sentLabel = statRow(root, "发送", "#d1d5db");
        okLabel = statRow(root, "成功", "#22c55e");
        failLabel = statRow(root, "失败", "#ef4444");
        emptyLabel = statRow(root, "空返回", "#f59e0b");
        recvLabel = statRow(root, "响应合计", "#9ca3af");
        bytesLabel = statRow(root, "下载流量", "#22d3ee");

        Button reset = PageKit.cardActionButton("清零");
        reset.setOnAction(e -> NetTrafficStats.reset());
        HBox footer = new HBox(8, reset, dimLabel("口径：成功=2xx有正文 · 空返回=2xx无正文 · 失败≥400"));
        footer.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(footer);
    }

    /** 一行统计：左侧名称 + 右侧数值（FX 线程每秒刷新） */
    private Label statRow(VBox root, String name, String color) {
        Label value = new Label("0");
        value.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;"
                + " -fx-font-family: 'Consolas', monospace;");
        Label nameL = new Label(name);
        nameL.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 12px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, nameL, spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(row);
        return value;
    }

    @Override
    protected void tick() {
        sentLabel.setText(String.valueOf(NetTrafficStats.getSent()));
        okLabel.setText(String.valueOf(NetTrafficStats.getSuccess()));
        failLabel.setText(String.valueOf(NetTrafficStats.getFailure()));
        emptyLabel.setText(String.valueOf(NetTrafficStats.getEmpty()));
        recvLabel.setText(String.valueOf(NetTrafficStats.getReceived()));
        bytesLabel.setText(FormatUtils.formatFileSize(NetTrafficStats.getBytes()));
    }

    public static boolean isOpen() {
        return instance != null && instance.isShowing();
    }

    public static void toggle() {
        if (instance == null) instance = new NetworkStatsFloatWindow();
        if (instance.isShowing()) {
            instance.close();
        } else {
            instance.open();
        }
    }
}
