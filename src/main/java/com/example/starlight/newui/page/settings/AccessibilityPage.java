package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.InertiaScrollSupport;
import com.example.starlight.newui.ui.PageKit;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;

/** 设置 → 辅助功能页（从 LauncherView 抽离，逻辑与布局样式均未改动） */
public final class AccessibilityPage {

    private final LauncherContext ctx;

    public AccessibilityPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "辅助功能");
        CheckBox toggle = PageKit.toggle("true".equalsIgnoreCase(ctx.config().getOrDefault("HighContrast", "false")));
        toggle.setOnAction(e -> {
            ctx.config().put("HighContrast", String.valueOf(toggle.isSelected()));
            ctx.saveConfig();
            ctx.applyHighContrast();
            ctx.toast("高对比度: " + (toggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("高对比度", "提高界面对比度（文字更清晰）", toggle));

        //色盲辅助（与「色盲辅助」设置页共用 ColorBlindMode 这一个总开关）
        CheckBox colorBlindToggle = PageKit.toggle("true".equalsIgnoreCase(ctx.config().getOrDefault("ColorBlindMode", "false")));
        colorBlindToggle.setOnAction(e -> {
            boolean on = colorBlindToggle.isSelected();
            ctx.config().put("ColorBlindMode", String.valueOf(on));
            // 先作用于矫正工具、再 saveConfig()：saveConfig 会重建设置页，
            // 而关闭总开关会连带把「启动后自动开启」也关掉（见 applyMasterSwitch），顺序反了会写回旧值
            ctx.applyColorBlindMode();
            ctx.saveConfig();
            ctx.toast(on ? "色盲辅助: 开启" : "色盲辅助: 关闭（「启动后自动开启」已一并关闭）");
        });
        // 详细配置（色障类型 / 强度 / 游戏窗口标题 / 快速调节工具）在独立的「色盲辅助」设置页
        Button configBtn = AppIcons.button("sliders", "详细配置");
        configBtn.getStyleClass().add("btn-primary");
        configBtn.setOnAction(e -> ctx.switchToSettings("sidebarColorBlind"));
        HBox colorBlindRow = new HBox(12, colorBlindToggle, configBtn);
        colorBlindRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("色盲辅助",
                "启动游戏时自动对游戏窗口启用色盲矫正（游戏退出后自动结束），关闭后会被记住、重启启动器不会自动打开；"
                        + "类型 / 强度 / 窗口标题点右侧「详细配置」调节",
                colorBlindRow));

        // 停用惯性滚动（无障碍开关，实时生效；规格见 spec 5.8。选中即停用）
        CheckBox inertiaToggle = PageKit.toggle(
                !"true".equals(ctx.config().getOrDefault(InertiaScrollSupport.CONFIG_ENABLED, "true")));
        inertiaToggle.setOnAction(e -> {
            ctx.config().put(InertiaScrollSupport.CONFIG_ENABLED, String.valueOf(!inertiaToggle.isSelected()));
            ctx.saveConfig();
            ctx.toast(inertiaToggle.isSelected() ? "惯性滚动: 关闭" : "惯性滚动: 开启");
        });
        root.getChildren().add(PageKit.settingsCard("停用惯性滚动",
                "关闭后滚轮/拖拽/键盘改为直接跳转、不再播放滚动动画，适合对动效敏感、易眩晕的用户",
                inertiaToggle));
        return root;
    }
}
