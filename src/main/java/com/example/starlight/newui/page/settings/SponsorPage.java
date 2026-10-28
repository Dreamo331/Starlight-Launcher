package com.example.starlight.newui.page.settings;

import com.example.starlight.config.Endpoints;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.I18N;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** 设置 → 赞助我们页（从 LauncherView 抽离，逻辑与布局样式均未改动） */
public final class SponsorPage {

    private final LauncherContext ctx;

    public SponsorPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, I18N.get("sponsor.pageTitle"));

        // 单卡片合并（重构 §6.14）：爱发电 + 微信赞助并排，下方说明 + 页底鸣谢 //
        Button sponsorBtn = AppIcons.button("heart", I18N.get("sponsor.afdian"));
        sponsorBtn.getStyleClass().add("btn-primary");
        sponsorBtn.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(
                        new java.net.URI(Endpoints.sponsorUrl()));
            } catch (Exception ex) {
                ctx.toast(I18N.get("sponsor.openBrowserFailed", ex.getMessage()));
            }
        });
        Button wechatBtn = AppIcons.button("heart", I18N.get("sponsor.wechat"));
        wechatBtn.getStyleClass().add("btn-primary");
        wechatBtn.setOnAction(e -> ctx.ui().imagePreview("/images/Appreciate.png"));
        HBox buttons = new HBox(12, sponsorBtn, wechatBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(PageKit.settingsCard(I18N.get("sponsor.cardTitle"),
                I18N.get("sponsor.cardDesc"), buttons));

        // 页底鸣谢名单链接（重构 §6.14 布局第 3 条）
        Label credits = new Label(I18N.get("sponsor.credits"));
        credits.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        credits.setWrapText(true);
        root.getChildren().add(credits);
        return root;
    }
}
