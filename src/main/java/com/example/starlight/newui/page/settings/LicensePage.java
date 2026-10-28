package com.example.starlight.newui.page.settings;


import com.example.starlight.util.I18N;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import javafx.scene.control.Button;

/** 设置 → 版权页：项目自身许可（MIT）+ 第三方组件许可列表 */
public final class LicensePage {

    private final LauncherContext ctx;

    public LicensePage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, I18N.get("license.t0001"));

        // 项目自身协议：MIT（Copyright (c) 2026 Starlight Launcher Contributors）
        Button mitBtn = AppIcons.button("view", I18N.get("license.t0002"));
        mitBtn.getStyleClass().add("btn-primary");
        mitBtn.setOnAction(e -> ctx.ui().mitLicenseDialog());
        root.getChildren().add(PageKit.settingsCard(I18N.get("license.t0003"),
                I18N.get("license.t0004"), mitBtn));

        // 第三方组件（含名称、版本、许可证）
        Button viewBtn = AppIcons.button("view", I18N.get("license.t0005"));
        viewBtn.getStyleClass().add("btn-primary");
        viewBtn.setOnAction(e -> ctx.ui().licenseDialog());
        root.getChildren().add(PageKit.settingsCard(I18N.get("license.t0006"), I18N.get("license.t0007"), viewBtn));
        return root;
    }
}
