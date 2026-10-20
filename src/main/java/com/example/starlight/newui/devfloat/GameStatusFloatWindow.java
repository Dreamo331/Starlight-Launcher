/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui.devfloat;

import com.example.starlight.service.GameInstanceRegistry;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * 游戏运行状态悬浮挂件：游戏运行时常驻显示实例信息。
 *
 * <p>数据来自 {@link GameInstanceRegistry}（本启动器拉起的实例，PID / 版本 / 玩家 / 启动时间）；
 * 运行时长按秒刷新。游戏未运行时显示占位文案（窗口保留，不自动关闭，方便挂机盯着启动时机）。
 */
public final class GameStatusFloatWindow extends DevFloatWindow {

    private static GameStatusFloatWindow instance;

    private Label title;
    private VBox rows;

    private GameStatusFloatWindow() {
        super("游戏状态", 300, 150);
    }

    @Override
    protected void buildContent(VBox root) {
        // 本方法在父类构造器内被回调，子类字段初始化器此时尚未执行——控件必须在这里创建
        title = new Label();
        rows = new VBox(4);
        title.setStyle("-fx-text-fill: #d1d5db; -fx-font-size: 13px;");
        rows.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(title, rows, dimLabel("数据来自本启动器拉起的游戏实例"));
    }

    @Override
    protected void tick() {
        List<GameInstanceRegistry.Instance> alive = GameInstanceRegistry.aliveInstances();
        rows.getChildren().clear();
        if (alive.isEmpty()) {
            title.setText("游戏未运行");
            rows.getChildren().add(dimLabel("启动游戏后这里会常驻显示实例状态"));
            return;
        }
        title.setText("游戏运行中（" + alive.size() + "）");
        for (GameInstanceRegistry.Instance ins : alive) {
            Label row = new Label(ins.displayName() + " · " + FormatDuration.uptime(ins.uptimeMillis())
                    + " · PID " + ins.pid);
            row.setStyle("-fx-text-fill: #d1d5db; -fx-font-size: 11px; -fx-font-family: 'Consolas', monospace;");
            rows.getChildren().add(row);
        }
    }

    /** 时长格式化（hh:mm:ss，本类专用避免与 FormatUtils.formatDuration 的分钟口径混淆） */
    private static final class FormatDuration {
        static String uptime(long millis) {
            long s = Math.max(0, millis / 1000);
            return String.format("%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        }
    }

    public static boolean isOpen() {
        return instance != null && instance.isShowing();
    }

    public static void toggle() {
        if (instance == null) instance = new GameStatusFloatWindow();
        if (instance.isShowing()) {
            instance.close();
        } else {
            instance.open();
        }
    }
}
