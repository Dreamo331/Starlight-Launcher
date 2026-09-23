package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.InertiaScrollSupport;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

/** 设置 → 主界面页（从 LauncherView 抽离，逻辑与布局样式均未改动） */
public final class MainUiPage {

    private final LauncherContext ctx;

    public MainUiPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "主界面");
        CheckBox toggle = PageKit.toggle(!"false".equalsIgnoreCase(ctx.config().getOrDefault("ShowSidebar", "true")));
        toggle.setOnAction(e -> {
            ctx.config().put("ShowSidebar", String.valueOf(toggle.isSelected()));
            ctx.saveConfig();
            ctx.applySidebarVisibility();
            // 侧边栏显隐影响设置页导航形态（隐藏时显示下拉导航），重建全部设置页
            ctx.rebuildSettingsPages();
            if (ctx.currentSidebarItemId() != null) ctx.switchToPage(ctx.currentSidebarItemId());
            ctx.toast("显示侧边栏: " + (toggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("显示侧边栏", "在设置页面显示侧边导航", toggle));

        // 滚动速度
        int curSpeed = PageKit.parseIntSafe(ctx.config().getOrDefault("ScrollSpeed", "35"), 35);
        Label speedLabel = new Label("当前: " + curSpeed);
        speedLabel.getStyleClass().add("black-value-label");
        Slider speedSlider = new Slider(5, 200, curSpeed);
        speedSlider.getStyleClass().add("black-ticks");
        speedSlider.setShowTickLabels(true);
        speedSlider.setMajorTickUnit(20);
        speedSlider.setBlockIncrement(5);
        speedSlider.setPrefWidth(200);
        speedSlider.valueProperty().addListener((obs, old, val) -> speedLabel.setText("当前: " + val.intValue()));
        Button saveSpeed = AppIcons.button("save", "保存");
        saveSpeed.getStyleClass().add("btn-primary");
        saveSpeed.setOnAction(e -> {
            int val = (int) speedSlider.getValue();
            ctx.config().put("ScrollSpeed", String.valueOf(val));
            ctx.saveConfig();
            ctx.toast("滚动速度已保存，重启后生效");
        });
        HBox speedRow = new HBox(10, speedSlider, speedLabel, saveSpeed);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("滚动速度", "页面的滚动灵敏度（数值越大滚动越快）", speedRow));

        // 滚动平滑度（惯性滚动 ease 三档，立即生效；规格见 spec 5.3）
        String curEase = InertiaScrollSupport.readEaseValue(ctx);
        String[] easeNames = {"慢滑", "标准", "跟手"};
        String[] easeValues = {"0.05", "0.1", "0.18"};
        String[] easeLabels = {"慢滑 (0.05)", "标准 (0.1)", "跟手 (0.18)"};
        ToggleGroup easeGroup = new ToggleGroup();
        RadioButton[] easeRadios = new RadioButton[easeValues.length];
        for (int i = 0; i < easeValues.length; i++) {
            RadioButton rb = new RadioButton(easeLabels[i]);
            rb.setToggleGroup(easeGroup);
            rb.setUserData(easeValues[i]);
            rb.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
            if (easeValues[i].equals(curEase)) {
                rb.setSelected(true);
            }
            easeRadios[i] = rb;
        }
        for (int i = 0; i < easeValues.length; i++) {
            final int idx = i;
            easeRadios[i].setOnAction(e -> {
                ctx.config().put(InertiaScrollSupport.CONFIG_EASE, easeValues[idx]);
                ctx.saveConfig();
                ctx.toast("滚动平滑度: " + easeNames[idx]);
            });
        }
        HBox easeRow = new HBox(24, easeRadios);
        easeRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("滚动平滑度",
                "滚轮/拖拽滚动的顺滑程度（慢滑更绵柔、跟手更迅速）", easeRow));

        return root;
    }
}
