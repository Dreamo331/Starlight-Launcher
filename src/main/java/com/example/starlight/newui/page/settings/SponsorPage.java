package com.example.starlight.newui.page.settings;

import com.example.starlight.config.Endpoints;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
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
        HBox card = PageKit.settingsCard("支持开发", "支持开发，获得专属徽章", null);
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
        card.getChildren().add(sponsorBtn);
        root.getChildren().add(card);

        // 微信赞助卡片
        Button wechatBtn = AppIcons.button("heart", "微信赞助");
        wechatBtn.getStyleClass().add("btn-primary");
        wechatBtn.setOnAction(e -> ctx.ui().imagePreview("/images/Appreciate.png"));
        HBox wechatCard = PageKit.settingsCard("微信赞助", "扫描二维码赞助，支持开发", wechatBtn);
        root.getChildren().add(wechatCard);
        return root;
    }
}
