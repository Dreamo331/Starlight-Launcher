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
import javafx.scene.layout.FlowPane;
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

        // 自定义背景色：折叠调色盘 + 十六进制输入 + 恢复默认 //
        root.getChildren().add(buildCustomColorCard());

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

        // 节日主题开关：默认开启；关闭后问候语/启动按钮/灯笼/节日壁纸全部恢复默认
        boolean festivalOn = com.example.starlight.newui.festival.FestivalCalendar.isEnabled(ctx.config());
        CheckBox festivalToggle = PageKit.toggle(festivalOn);
        festivalToggle.setOnAction(e -> {
            ctx.config().put(com.example.starlight.newui.festival.FestivalCalendar.CONFIG_KEY,
                    festivalToggle.isSelected() ? "auto" : "off");
            ctx.saveConfig();
            ctx.toast("节日主题: " + (festivalToggle.isSelected() ? "开启" : "关闭") + "（重启后完全生效）");
        });
        root.getChildren().add(PageKit.settingsCard("节日主题",
                "节日（春节/中秋/圣诞等）自动更换问候语、启动按钮、灯笼与背景壁纸；关闭后恢复默认", festivalToggle));
        return root;
    }

    // 自定义背景色 //

    /** 调色盘预设色：按「中性 → 冷暖 → 深浅」排布，覆盖常见审美需求 */
    private static final String[][] PRESET_COLORS = {
            {"#F8FAFC", "雾白"}, {"#EEF2F7", "云灰"}, {"#E0E7FF", "浅靛"},
            {"#DBEAFE", "天蓝"}, {"#CFFAFE", "青碧"}, {"#DCFCE7", "新绿"},
            {"#FEF3C7", "米黄"}, {"#FFE4E6", "樱粉"}, {"#F3E8FF", "薰衣草"},
            {"#1E293B", "石板"}, {"#0F2847", "深海"}, {"#1A2E35", "墨青"},
            {"#2D2438", "暗紫"}, {"#33241F", "咖褐"}, {"#14532D", "森林"}
    };

    /** 折叠状态：同一会话内保持（页面重建时把值传回来） */
    private boolean colorPaletteOpen;

    /**
     * 自定义背景色卡片：可折叠的调色盘。
     *
     * <p>交互：
     * <ul>
     *   <li>点任意预设色块 → 立即应用并持久化，色块描边高亮为当前色；</li>
     *   <li>十六进制输入框 → 回车或点「应用」生效，支持 {@code #RGB} / {@code #RRGGBB}；</li>
     *   <li>「恢复默认」→ 清空自定义色，回到主题默认渐变；</li>
     *   <li>整张卡片可点击标题折叠/展开（复用 PageKit.collapsibleSection）。</li>
     * </ul>
     *
     * <p>有背景图片时自定义色不生效（壁纸优先），卡片里会给出说明。
     */
    private Node buildCustomColorCard() {
        final com.example.starlight.newui.ui.CustomThemeColor manager = ctx.customThemeColor();
        final String currentColor = manager == null ? "" : manager.current();

        VBox content = new VBox(12);

        // ① 预设色盘
        FlowPane palette = new FlowPane(8, 8);
        palette.getStyleClass().add("color-palette");
        java.util.List<Region> swatches = new java.util.ArrayList<>();
        java.util.List<String> swatchColors = new java.util.ArrayList<>();

        for (String[] preset : PRESET_COLORS) {
            String hex = preset[0];
            Region sw = new Region();
            sw.getStyleClass().add("color-swatch");
            sw.setStyle("-fx-background-color: " + hex + ";");
            sw.setPrefSize(38, 30);
            sw.setMinSize(38, 30);
            sw.setMaxSize(38, 30);
            // 悬停提示给出色名与色值，便于用户复现
            javafx.scene.control.Tooltip tip =
                    new javafx.scene.control.Tooltip(preset[1] + "  " + hex);
            javafx.scene.control.Tooltip.install(sw, tip);
            if (hex.equalsIgnoreCase(currentColor)) sw.getStyleClass().add("selected");
            final String color = hex;
            sw.setOnMouseClicked(e -> {
                if (manager != null) manager.setColor(color);
                syncSwatchSelection(swatches, swatchColors, color);
                ctx.toast("背景色已设为 " + color);
            });
            swatches.add(sw);
            swatchColors.add(hex);
            palette.getChildren().add(sw);
        }
        content.getChildren().add(sectionLabel("预设颜色"));
        content.getChildren().add(palette);

        // ② 十六进制输入
        Label hexLabel = new Label("自定义");
        hexLabel.getStyleClass().add("settings-card-desc");
        javafx.scene.control.TextField hexField = new javafx.scene.control.TextField(currentColor);
        hexField.setPromptText("#RRGGBB，如 #1E293B");
        hexField.getStyleClass().add("input-field");
        hexField.setPrefWidth(150);
        hexField.setMaxWidth(150);

        Button applyBtn = AppIcons.button("check", "应用");
        applyBtn.getStyleClass().add("btn-primary");
        applyBtn.setStyle("-fx-padding: 5 14; -fx-font-size: 12px;");

        Label hint = new Label();
        hint.getStyleClass().add("settings-card-desc");

        Runnable applyHex = () -> {
            String raw = hexField.getText();
            if (raw == null || raw.isBlank()) {
                hint.setText("请输入色值，或点上面的预设色块");
                return;
            }
            if (!com.example.starlight.newui.ui.CustomThemeColor.isValid(raw)) {
                hint.setText("格式不对：请填 #RGB 或 #RRGGBB（例如 #1E293B）");
                return;
            }
            String normalized = com.example.starlight.newui.ui.CustomThemeColor.normalize(raw);
            if (manager != null) manager.setColor(normalized);
            hexField.setText(normalized);
            syncSwatchSelection(swatches, swatchColors, normalized);
            hint.setText("已应用 " + normalized);
            ctx.toast("背景色已设为 " + normalized);
        };
        applyBtn.setOnAction(e -> applyHex.run());
        hexField.setOnAction(e -> applyHex.run());

        HBox hexRow = new HBox(8, hexLabel, hexField, applyBtn);
        hexRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(hexRow);

        // ③ 恢复默认
        Button resetBtn = AppIcons.button("refresh", "恢复默认");
        resetBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626;"
                + " -fx-font-size: 12px; -fx-padding: 6 12; -fx-background-radius: 6;");
        resetBtn.setOnAction(e -> {
            if (manager != null) manager.reset();
            hexField.setText("");
            syncSwatchSelection(swatches, swatchColors, "");
            hint.setText("已恢复默认背景");
            ctx.toast("已恢复默认背景");
        });

        HBox bottomRow = new HBox(10, resetBtn, hint);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(bottomRow);

        // 有壁纸时说明为什么看不到效果
        String bgPath = ctx.config().getOrDefault("BackgroundImage", "");
        if (bgPath != null && !bgPath.isBlank()) {
            Label warn = new Label("当前已设置背景图片，自定义背景色不会生效（壁纸优先）。"
                    + "如需使用纯色，请先在上方「背景图片」里点「恢复默认」。");
            warn.setWrapText(true);
            warn.getStyleClass().add("settings-card-desc");
            warn.setStyle("-fx-text-fill: #b45309;");
            content.getChildren().add(warn);
        }

        String summary = currentColor.isEmpty()
                ? "使用默认渐变背景" : "当前：" + currentColor;
        return PageKit.collapsibleSection("自定义背景色", summary, content,
                colorPaletteOpen, open -> colorPaletteOpen = open);
    }

    /** 小标题（调色盘内部分区） */
    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-card-title");
        return l;
    }

    /** 同步色块选中态：只有与当前色一致的色块高亮 */
    private static void syncSwatchSelection(java.util.List<Region> swatches,
                                            java.util.List<String> colors, String selected) {
        for (int i = 0; i < swatches.size(); i++) {
            swatches.get(i).getStyleClass().remove("selected");
            if (selected != null && !selected.isEmpty()
                    && colors.get(i).equalsIgnoreCase(selected)) {
                swatches.get(i).getStyleClass().add("selected");
            }
        }
    }
}
