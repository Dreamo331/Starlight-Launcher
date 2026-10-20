package com.example.starlight.newui.page.settings;

import com.example.starlight.config.Endpoints;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

/** 设置 → 赞助我们页（从 LauncherView 抽离，逻辑与布局样式均未改动） */
public final class SponsorPage {

    private final LauncherContext ctx;

    public SponsorPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "赞助我们");

        // 单卡片合并（重构 §6.14）：爱发电 + 微信赞助并排，下方说明 + 页底鸣谢 //
        Button sponsorBtn = AppIcons.button("heart", "爱发电赞助");
        sponsorBtn.getStyleClass().add("btn-primary");
        sponsorBtn.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(
                        new java.net.URI(Endpoints.sponsorUrl()));
            } catch (Exception ex) {
                ctx.toast("打开浏览器失败: " + ex.getMessage());
            }
        });
        Button wechatBtn = AppIcons.button("heart", "微信赞助");
        wechatBtn.getStyleClass().add("btn-primary");
        wechatBtn.setOnAction(e -> ctx.ui().imagePreview("/images/Appreciate.png"));
        HBox buttons = new HBox(12, sponsorBtn, wechatBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(PageKit.settingsCard("支持开发",
                "支持开发，获得专属徽章；微信赞助打开二维码预览，可随时关闭", buttons));

        // 页底鸣谢名单链接（重构 §6.14 布局第 3 条）
        Label credits = new Label("鸣谢：BMCLAPI（下载镜像）· Modrinth / CurseForge（资源数据）· HMCL / PCL2（交互参考）");
        credits.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        credits.setWrapText(true);
        root.getChildren().add(credits);
        return root;
    }
}
