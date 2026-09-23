package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

/** 设置 → 语言页（从 LauncherView 抽离，逻辑与布局样式均未改动） */
public final class LanguagePage {

    private final LauncherContext ctx;

    public LanguagePage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "语言");
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll("简体中文", "English", "日本語");
        String curLang = ctx.config().getOrDefault("Language", "zh_CN");
        combo.setValue("zh_CN".equals(curLang) ? "简体中文" : "en_US".equals(curLang) ? "English" : "日本語");
        combo.getStyleClass().add("select-field");
        combo.setOnAction(e -> {
            String val = combo.getValue();
            String langCode = "简体中文".equals(val) ? "zh_CN" : "English".equals(val) ? "en_US" : "ja_JP";
            ctx.config().put("Language", langCode);
            ctx.saveConfig();
            ctx.toast("语言已切换，重启后生效");
        });
        root.getChildren().add(PageKit.settingsCard("显示语言", "选择启动器界面语言", combo));
        //提示框
        Label tipLabel = new Label("提示：功能尚未实现。");
        tipLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        root.getChildren().add(tipLabel);
        return root;
    }
}
