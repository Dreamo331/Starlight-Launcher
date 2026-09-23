package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import java.io.File;

/** 设置 → 主题与背景页（从 LauncherView 抽离，逻辑与布局样式均未改动） */
public final class ThemePage {

    private final LauncherContext ctx;

    public ThemePage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "主题与背景");
        HBox selector = new HBox(20);
        selector.getStyleClass().add("theme-selector");
        String[][] themes = {
                {"浅色模式", "light", "#f8fafc"},
                {"跟随系统", "system", "linear-gradient(to right, #f8fafc 50%, #1e293b 50%)"},
                {"深色模式", "dark", "#1e293b"}
        };
        for (String[] t : themes) {
            VBox opt = new VBox(6);
            opt.setAlignment(Pos.CENTER);
            opt.getStyleClass().add("theme-option");
            if (t[1].equals(ctx.currentTheme())) opt.getStyleClass().add("selected");
            Region preview = new Region();
            preview.setPrefSize(100, 75);
            preview.setStyle("-fx-background-color: " + t[2] + "; -fx-background-radius: 8;");
            preview.getStyleClass().add("theme-preview");
            Label label = new Label(t[0]);
            label.getStyleClass().add("theme-label");
            opt.getChildren().addAll(preview, label);
            final String themeKey = t[1];
            opt.setOnMouseClicked(e -> {
                selector.getChildren().forEach(n -> n.getStyleClass().remove("selected"));
                opt.getStyleClass().add("selected");
                ctx.applyTheme(themeKey);
            });
            selector.getChildren().add(opt);
        }
        root.getChildren().add(selector);

        // 背景图片：选择文件后立即应用并持久化，可恢复默认
        String bgPath = ctx.config().getOrDefault("BackgroundImage", "");
        Label bgLabel = new Label(bgPath.isEmpty() ? "未设置（使用默认渐变背景）" : new File(bgPath).getName());
        bgLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        Button chooseBgBtn = AppIcons.button("image", "选择图片");
        chooseBgBtn.getStyleClass().add("btn-primary");
        chooseBgBtn.setOnAction(e -> ctx.chooseBackgroundImage(bgLabel));
        Button clearBgBtn = AppIcons.button("refresh", "恢复默认");
        clearBgBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-size: 12px; -fx-padding: 6 12; -fx-background-radius: 6;");
        clearBgBtn.setOnAction(e -> {
            // 写入空值而非删除：saveClientConfig 为读-改-写合并，删除的键会保留旧值
            ctx.config().put("BackgroundImage", "");
            ctx.saveConfig();
            bgLabel.setText("未设置（使用默认渐变背景）");
            ctx.applyBackgroundImage();
            ctx.toast("已恢复默认背景");
        });
        HBox bgRow = new HBox(10, chooseBgBtn, clearBgBtn, bgLabel);
        bgRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("背景图片", "更换启动器背景图片（支持 png/jpg）", bgRow));

        // 沿用上次关闭的窗口位置：开启恢复上次位置；关闭后每次启动窗口居中显示
        CheckBox keepPosToggle = PageKit.toggle(!"false".equalsIgnoreCase(ctx.config().getOrDefault("KeepWindowPosition", "true")));
        keepPosToggle.setOnAction(e -> {
            ctx.config().put("KeepWindowPosition", String.valueOf(keepPosToggle.isSelected()));
            ctx.saveConfig();
            ctx.toast("沿用上次关闭的窗口位置: " + (keepPosToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("沿用上次关闭的窗口位置",
                "开启后恢复上次关闭时的窗口位置；关闭后每次启动窗口居中显示", keepPosToggle));

        // 标题栏窗口按钮样式：默认仿 macOS 三点式；关闭后改成「减号 + 叉号」两个按钮
        CheckBox macBtnToggle = PageKit.toggle(!"false".equalsIgnoreCase(
                ctx.config().getOrDefault("MacStyleWindowButtons", "true")));
        macBtnToggle.setOnAction(e -> {
            ctx.config().put("MacStyleWindowButtons", String.valueOf(macBtnToggle.isSelected()));
            ctx.saveConfig();
            ctx.applyWindowButtonStyle();
            ctx.toast("仿 macOS 三点式窗口按钮: " + (macBtnToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("仿 macOS 三点式窗口按钮",
                "开启后标题栏右上角是红黄绿三个圆点（最小化 / 最大化 / 关闭）；"
                        + "关闭后改为减号与叉号（减号=最小化，叉号=关闭）", macBtnToggle));
        return root;
    }
}
