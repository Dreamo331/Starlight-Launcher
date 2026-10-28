package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.InertiaScrollSupport;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.I18N;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

/**
 * 设置 → 主界面页（重构 §6.8：吸收原「语言」页）。
 *
 * <p>侧边栏显隐 / 滚动速度（拖动结束自动保存，不再单独放保存按钮）/ 滚动平滑度 / 显示语言。
 * 原独立「语言」页不再单独占侧边栏，旧导航 id sidebarLanguage 由 NavRouter 重定向到本页。
 */
public final class MainUiPage {

    private final LauncherContext ctx;

    public MainUiPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, I18N.get("mainui.t0001"));

        // 卡片「侧边栏」 //
        CheckBox toggle = PageKit.toggle(!"false".equalsIgnoreCase(ctx.config().getOrDefault("ShowSidebar", "true")));
        toggle.setOnAction(e -> {
            ctx.config().put("ShowSidebar", String.valueOf(toggle.isSelected()));
            ctx.saveConfig();
            ctx.applySidebarVisibility();
            // 侧边栏显隐影响设置页导航形态（隐藏时显示下拉导航），重建全部设置页
            ctx.rebuildSettingsPages();
            if (ctx.currentSidebarItemId() != null) ctx.switchToPage(ctx.currentSidebarItemId());
            ctx.toast(I18N.get("mainui.k0037", (toggle.isSelected() ? I18N.get("mainui.t0002") : I18N.get("mainui.t0003"))));
        });
        root.getChildren().add(PageKit.settingsCard(I18N.get("mainui.t0004"),
                I18N.get("mainui.t0005"), toggle));

        // 卡片「滚动」：速度滑杆（拖动结束 500ms 防抖自动保存，重构 §4.1 Slider 行） //
        int curSpeed = PageKit.parseIntSafe(ctx.config().getOrDefault("ScrollSpeed", "35"), 35);
        Label speedLabel = new Label(I18N.get("mainui.k0001", curSpeed));
        speedLabel.getStyleClass().add("black-value-label");
        Slider speedSlider = new Slider(5, 200, curSpeed);
        speedSlider.getStyleClass().add("black-ticks");
        speedSlider.setShowTickLabels(true);
        speedSlider.setMajorTickUnit(20);
        speedSlider.setBlockIncrement(5);
        speedSlider.setPrefWidth(200);
        speedSlider.valueProperty().addListener((obs, old, val) -> speedLabel.setText(I18N.get("mainui.k0002", val.intValue())));
        // 防抖自动保存（500ms 内不再拖动才落盘）
        javafx.animation.PauseTransition speedDebounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
        speedDebounce.setOnFinished(e -> {
            int val = (int) speedSlider.getValue();
            if (String.valueOf(val).equals(ctx.config().getOrDefault("ScrollSpeed", "35"))) return;
            ctx.config().put("ScrollSpeed", String.valueOf(val));
            ctx.saveConfig();
            ctx.toast(I18N.get("mainui.k0003", val));
        });
        speedSlider.valueProperty().addListener((obs, old, val) -> speedDebounce.playFromStart());
        HBox speedRow = new HBox(10, speedSlider, speedLabel);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard(I18N.get("mainui.t0006"),
                I18N.get("mainui.t0007"), speedRow));

        // 卡片「滚动」第二行：平滑度单选（即时保存，原有逻辑不变） //
        String curEase = InertiaScrollSupport.readEaseValue(ctx);
        String[] easeNames = {I18N.get("mainui.t0008"), I18N.get("mainui.t0009"), I18N.get("mainui.t0010")};
        String[] easeValues = {"0.05", "0.1", "0.18"};
        String[] easeLabels = {I18N.get("mainui.t0011"), I18N.get("mainui.t0012"), I18N.get("mainui.t0013")};
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
                ctx.toast(I18N.get("mainui.k0004", easeNames[idx]));
            });
        }
        HBox easeRow = new HBox(24, easeRadios);
        easeRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard(I18N.get("mainui.t0014"),
                I18N.get("mainui.t0015"), easeRow));

        // 卡片「显示语言」（原 LanguagePage 并入，配置键 Language 不变；重构 §6.8 布局第 3 条） //
        // 下拉项存语言代码、显示名走 LanguageChoices：不再用中文字面量反推代码。
        // 界面语言为「重启生效」，这里只写配置并如实提示。
        ComboBox<String> langCombo = new ComboBox<>();
        langCombo.getItems().addAll(LanguageChoices.CODES);
        langCombo.setValue(LanguageChoices.normalize(ctx.config().getOrDefault("Language", "zh_CN")));
        langCombo.setCellFactory(cb -> languageCell());
        langCombo.setButtonCell(languageCell());
        langCombo.getStyleClass().add("select-field");
        langCombo.setOnAction(e -> LanguageChoices.apply(ctx, langCombo.getValue()));
        root.getChildren().add(PageKit.settingsCard(
                I18N.get("lang.displayLanguage"),
                I18N.get("lang.displayLanguageDesc"), langCombo));

        return root;
    }

    /** 语言下拉的显示单元：值仍是语言代码，只把代码渲染成名称 */
    private static ListCell<String> languageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : LanguageChoices.displayName(item));
            }
        };
    }
}
