/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui.devfloat;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * 开发者悬浮窗基类：置顶 UTILITY 小窗 + 1s 采样 Timeline。
 *
 * <p>窗口独立于主窗（不设 owner，游戏全屏时也不被主窗口压住）；
 * 关闭即停采样，不阻止启动器退出（所有线程均为守护 / FX 节点）。
 */
abstract class DevFloatWindow {

    protected final Stage stage;
    private final Timeline timer;

    DevFloatWindow(String title, double width, double height) {
        stage = new Stage(StageStyle.UTILITY);
        stage.setTitle(title);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);

        VBox root = new VBox(8);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #111826; -fx-background-radius: 0;");

        buildContent(root);

        stage.setScene(new Scene(root, width, height));
        stage.setOnHiding(e -> onHidden());

        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        timer.setCycleCount(Animation.INDEFINITE);
    }

    /**
     * 子类构建内容（root 已带深色底与内边距）。
     *
     * <p><b>本方法在父类构造器内被回调</b>：子类的字段初始化器此时尚未执行，
     * 控件必须一律在本方法内创建（不能写成 {@code private final X x = new X();}）。
     */
    protected abstract void buildContent(VBox root);

    /** 每 1s 采样一次（FX 线程） */
    protected abstract void tick();

    void open() {
        if (stage.isShowing()) return;
        stage.show();
        tick();
        timer.playFromStart();
    }

    void close() {
        stage.close();
    }

    /** 窗口是否正在显示（子类另有静态 isOpen() 管理单例态，与实例方法区分命名） */
    boolean isShowing() {
        return stage.isShowing();
    }

    /** 关闭/隐藏时停采样 */
    protected void onHidden() {
        timer.stop();
    }

    /** 深色小字标签（悬浮窗内的说明行） */
    protected static Label dimLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 11px;");
        return l;
    }
}
