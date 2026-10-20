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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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
        VBox root = PageKit.settingsPage(ctx, "主界面");

        // 卡片「侧边栏」 //
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
        root.getChildren().add(PageKit.settingsCard("显示侧边栏",
                "在设置页面显示左侧导航；关闭后顶部出现下拉导航（分组与侧边栏一致）", toggle));

        // 卡片「滚动」：速度滑杆（拖动结束 500ms 防抖自动保存，重构 §4.1 Slider 行） //
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
        // 防抖自动保存（500ms 内不再拖动才落盘）
        javafx.animation.PauseTransition speedDebounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
        speedDebounce.setOnFinished(e -> {
            int val = (int) speedSlider.getValue();
            if (String.valueOf(val).equals(ctx.config().getOrDefault("ScrollSpeed", "35"))) return;
            ctx.config().put("ScrollSpeed", String.valueOf(val));
            ctx.saveConfig();
            ctx.toast("滚动速度已保存: " + val);
        });
        speedSlider.valueProperty().addListener((obs, old, val) -> speedDebounce.playFromStart());
        HBox speedRow = new HBox(10, speedSlider, speedLabel);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("滚动速度",
                "页面的滚动灵敏度（数值越大滚动越快），拖动结束自动保存，重启后生效", speedRow));

        // 卡片「滚动」第二行：平滑度单选（即时保存，原有逻辑不变） //
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
                "滚轮滚动的顺滑程度（慢滑更绵柔、跟手更迅速），选中即生效", easeRow));

        // 卡片「显示语言」（原 LanguagePage 并入，配置键 Language 不变；重构 §6.8 布局第 3 条） //
        ComboBox<String> langCombo = new ComboBox<>();
        langCombo.getItems().addAll("简体中文", "English", "日本語");
        String curLang = ctx.config().getOrDefault("Language", "zh_CN");
        langCombo.setValue("zh_CN".equals(curLang) ? "简体中文" : "en_US".equals(curLang) ? "English" : "日本語");
        langCombo.getStyleClass().add("select-field");
        langCombo.setOnAction(e -> {
            String val = langCombo.getValue();
            String langCode = "简体中文".equals(val) ? "zh_CN" : "English".equals(val) ? "en_US" : "ja_JP";
            ctx.config().put("Language", langCode);
            ctx.saveConfig();
            ctx.toast("语言已切换，重启后生效");
        });
        root.getChildren().add(PageKit.settingsCard("显示语言",
                "选择启动器界面语言（功能逐步完善，当前界面文本以简体中文为主）", langCombo));

        return root;
    }
}
