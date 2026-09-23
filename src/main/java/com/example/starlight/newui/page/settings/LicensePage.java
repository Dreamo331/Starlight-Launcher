package com.example.starlight.newui.page.settings;

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
        VBox root = PageKit.settingsPage(ctx, "版权");

        // 项目自身协议：MIT（Copyright (c) 2026 Starlight Launcher Contributors）
        Button mitBtn = AppIcons.button("view", "查看");
        mitBtn.getStyleClass().add("btn-primary");
        mitBtn.setOnAction(e -> ctx.ui().mitLicenseDialog());
        root.getChildren().add(PageKit.settingsCard("项目许可",
                "Starlight Launcher 本体以 MIT License 开源发布", mitBtn));

        // 第三方组件（含名称、版本、许可证）
        Button viewBtn = AppIcons.button("view", "查看");
        viewBtn.getStyleClass().add("btn-primary");
        viewBtn.setOnAction(e -> ctx.ui().licenseDialog());
        root.getChildren().add(PageKit.settingsCard("开源许可", "第三方组件列表（含名称、版本、许可证）", viewBtn));
        return root;
    }
}
