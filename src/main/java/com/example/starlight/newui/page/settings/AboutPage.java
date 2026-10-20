package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import com.example.starlight.newui.AppConfig;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * 设置 → 关于页（重构 §6.2：吸收原「版权」页）。
 *
 * <p>信息聚合：版本 + 检查更新 + 开源仓库 + 许可信息（项目 MIT / 第三方组件），
 * 原独立「版权」页不再单独占侧边栏，旧导航 id sidebarLicense 由 NavRouter 重定向到本页。
 */
public final class AboutPage {

    /** 开源仓库地址：后续把下面两个常量填成真实地址即可，其余代码无需改动 */
    private static final String GITHUB_URL = "https://github.com/Dreamo331/Starlight-Launcher";
    private static final String GITEE_URL = "https://gitee.com/Horses-always-love-to-run/starlight-launcher-cn";

    private final LauncherContext ctx;

    public AboutPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "关于");

        // 页头：当前版本 + 检查更新（非卡片区，重构 §6.2 布局第 1/2 条） //
        String version = AppConfig.DISPLAY_VERSION;
        HBox versionRow = new HBox(12);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        Label versionLabel = new Label("当前版本: " + version);
        versionLabel.getStyleClass().add("settings-card-title");
        Button checkBtn = AppIcons.button("search", "检查更新");
        checkBtn.getStyleClass().add("btn-primary");
        checkBtn.setOnAction(e -> ctx.checkForUpdates());
        versionRow.getChildren().addAll(versionLabel, checkBtn);
        root.getChildren().add(versionRow);

        // 卡片「开源仓库」：GitHub / Gitee 并排 //
        Button githubBtn = AppIcons.button("github", "GitHub");
        githubBtn.getStyleClass().add("btn-primary");
        githubBtn.setOnAction(e -> ctx.openWebUrl(GITHUB_URL));
        Button giteeBtn = AppIcons.button("gitee", "Gitee");
        giteeBtn.getStyleClass().add("btn-primary");
        giteeBtn.setOnAction(e -> ctx.openWebUrl(GITEE_URL));
        HBox repoButtons = new HBox(10, githubBtn, giteeBtn);
        repoButtons.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(PageKit.settingsCard("开源仓库", "查看源代码、提交问题反馈或参与开发", repoButtons));

        // 卡片「许可信息」（原版权页内容并入，重构 §6.2 布局第 4 条） //
        Button mitBtn = AppIcons.button("view", "查看");
        mitBtn.getStyleClass().add("btn-primary");
        mitBtn.setOnAction(e -> ctx.ui().mitLicenseDialog());
        root.getChildren().add(PageKit.settingsCard("项目许可",
                "Starlight Launcher 本体以 MIT License 开源发布", mitBtn));

        Button viewBtn = AppIcons.button("view", "查看");
        viewBtn.getStyleClass().add("btn-primary");
        viewBtn.setOnAction(e -> ctx.ui().licenseDialog());
        root.getChildren().add(PageKit.settingsCard("开源许可", "第三方组件列表（含名称、版本、许可证）", viewBtn));

        // 页底版权声明（重构 §6.2 布局第 5 条） //
        Label copyright = new Label("Copyright (c) 2026 Starlight Launcher Contributors · 以 MIT License 开源发布");
        copyright.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        root.getChildren().add(copyright);

        return root;
    }
}
