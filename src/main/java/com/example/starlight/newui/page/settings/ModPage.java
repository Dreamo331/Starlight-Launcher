package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;

/** 
设置 → 模组页
（从 LauncherView 抽离，逻辑与样式均未改动） */
public final class ModPage {

    private final LauncherContext ctx;

    public ModPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "模组");
        // 内测功能标注
        Label betaNotice = AppIcons.label("warning", "模组（启动器扩展）功能当前为内测版，可能存在缺陷或行为变更，请谨慎使用。");
        betaNotice.setStyle("-fx-font-size: 12px; -fx-text-fill: #b45309;"
                + " -fx-background-color: rgba(245,158,11,0.12); -fx-background-radius: 8; -fx-padding: 8 12;");
        betaNotice.setWrapText(true);
        root.getChildren().add(betaNotice);
        CheckBox quickModToggle = PageKit.toggle("true".equalsIgnoreCase(ctx.config().getOrDefault("HomeQuickManageMod", "false")));
        quickModToggle.setOnAction(e -> {
            ctx.config().put("HomeQuickManageMod", String.valueOf(quickModToggle.isSelected()));
            ctx.saveConfig();
            ctx.updateModTabVisibility();
            ctx.toast("首页快速管理MOD: " + (quickModToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("首页快速管理MOD",
                "开启后在顶部导航栏显示「模组」页，便于快速管理模组（将用户上传的 .jar 模组文件复制到指定位置）", quickModToggle));
        return root;
    }
}
