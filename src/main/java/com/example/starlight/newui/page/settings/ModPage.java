package com.example.starlight.newui.page.settings;


import com.example.starlight.util.I18N;
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
        VBox root = PageKit.settingsPage(ctx, I18N.get("modtab.t0001"));
        // 内测功能标注
        Label betaNotice = AppIcons.label("warning", I18N.get("modtab.t0002"));
        betaNotice.setStyle("-fx-font-size: 12px; -fx-text-fill: #b45309;"
                + " -fx-background-color: rgba(245,158,11,0.12); -fx-background-radius: 8; -fx-padding: 8 12;");
        betaNotice.setWrapText(true);
        root.getChildren().add(betaNotice);
        CheckBox quickModToggle = PageKit.toggle("true".equalsIgnoreCase(ctx.config().getOrDefault("HomeQuickManageMod", "false")));
        quickModToggle.setOnAction(e -> {
            ctx.config().put("HomeQuickManageMod", String.valueOf(quickModToggle.isSelected()));
            ctx.saveConfig();
            ctx.updateModTabVisibility();
            ctx.toast(I18N.get("modtab.k0038", (quickModToggle.isSelected() ? I18N.get("modtab.t0003") : I18N.get("modtab.t0004"))));
        });
        root.getChildren().add(PageKit.settingsCard(I18N.get("modtab.t0005"),
                I18N.get("modtab.t0006"), quickModToggle));
        return root;
    }
}
