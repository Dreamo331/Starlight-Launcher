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

/** 设置 → 关于页（从 LauncherView 抽离，逻辑与布局样式均未改动） */
public final class AboutPage {

    /** 开源仓库地址：后续把下面两个常量填成真实地址即可，其余代码无需改动 */
    private static final String GITHUB_URL = "https://github.com/";
    private static final String GITEE_URL = "https://gitee.com/";

    private final LauncherContext ctx;

    public AboutPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "关于");
        String version = AppConfig.DISPLAY_VERSION;
        root.getChildren().add(PageKit.settingsCard("当前版本", version, new Label() {{ setStyle("-fx-text-fill: -sl-text-dim;"); }}));
        Button checkBtn = AppIcons.button("search", "检查");
        checkBtn.getStyleClass().add("btn-primary");
        checkBtn.setOnAction(e -> ctx.checkForUpdates());
        root.getChildren().add(PageKit.settingsCard("检查更新", "检查是否有新版本可用", checkBtn));

        // 开源仓库入口：GitHub / Gitee 两个按钮（图标取自 resources/svg 下的官方 logo）
        Button githubBtn = AppIcons.button("github", "GitHub");
        githubBtn.getStyleClass().add("btn-primary");
        githubBtn.setOnAction(e -> ctx.openWebUrl(GITHUB_URL));
        Button giteeBtn = AppIcons.button("gitee", "Gitee");
        giteeBtn.getStyleClass().add("btn-primary");
        giteeBtn.setOnAction(e -> ctx.openWebUrl(GITEE_URL));
        HBox repoButtons = new HBox(10, githubBtn, giteeBtn);
        repoButtons.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(PageKit.settingsCard("开源仓库", "查看源代码、提交问题反馈或参与开发", repoButtons));

        // 内测阶段提示
        Label betaHint = new Label("当前启动器为内测阶段，部分功能尚未完善");
        betaHint.setStyle("-fx-background-color: #fef3c7; -fx-text-fill: #92400e;" +
                " -fx-padding: 10 14; -fx-background-radius: 8; -fx-font-size: 12px;");
        root.getChildren().add(betaHint);
        return root;
    }
}


