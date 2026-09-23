package com.example.starlight.newui.ui;

import javafx.scene.Node;

/**
 * 界面动效总入口：按钮水波纹、下拉框（箭头/面板/候选行）动画、自定义表单控件动效、进度条动效。
 *
 * <p>都是「装一次覆盖整棵场景」的模式（场景级事件过滤器 + 命中时按需准备/低频巡检），
 * 所以工程里各处 {@code new Button(...)} / {@code new ComboBox(...)} / {@code new CheckBox(...)}
 * / {@code new ProgressBar(...)} 不用逐个改调用点；
 * 由 {@code LauncherView} 在建好根节点后调用一次即可（导航栏动效需要导航栏节点，单独装）。
 */
public final class UiEffects {

    private UiEffects() {
    }

    /** @param rootNode 场景里的任意节点（通常是启动器的 rootPane），未入场景时会等它入场景再装 */
    public static void install(Node rootNode) {
        RippleEffect.install(rootNode);
        ComboBoxAnimator.install(rootNode);
        FormControls.install(rootNode);
        ProgressBars.install(rootNode);
        // NavEffects 需要导航栏节点，由 LauncherView 装（见那里的 install(tabBar)）
        ProgressBars.install(rootNode);
    }
}
