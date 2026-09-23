package com.example.starlight.newui.page.settings;

import com.example.starlight.colorblind.ColorBlindOverlayManager;
import com.example.starlight.download.HttpDownloadEngine;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.download.DownloadTaskCard;
import com.example.starlight.newui.download.DownloadTaskManager;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.File;

/**
 * 设置 → 色盲辅助页：总开关 / 色障类型 / 矫正强度 / 游戏窗口标题 / 自动开启 / 记住配置，
 * 以及「快速测试调节」入口（本地网页工具，改完数值可立即重启矫正工具看效果）。
 *
 * <p>所有改动都走 {@code ctx.config().put(...) + ctx.saveConfig()}，与「辅助功能」页共用
 * {@code ColorBlindMode} 这一个总开关键。
 */
public final class ColorBlindPage {

    private final LauncherContext ctx;

    public ColorBlindPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "色盲辅助");

        // ===== 矫正工具未安装：下载入口（exe 在位时整张卡片不出现）=====
        if (!ColorBlindOverlayManager.isExeAvailable()) {
            root.getChildren().add(buildMissingToolCard());
        }

        // 「启动后自动开启」在总开关之前创建：关闭总开关时要连带把它取消勾选（两个开关不该互相矛盾）
        CheckBox autoEnable = PageKit.toggle(
                !"false".equalsIgnoreCase(ctx.config().getOrDefault(ColorBlindOverlayManager.KEY_AUTO_ENABLE, "true")));
        autoEnable.setOnAction(e -> {
            ctx.config().put(ColorBlindOverlayManager.KEY_AUTO_ENABLE, String.valueOf(autoEnable.isSelected()));
            ctx.saveConfig();
            ctx.toast("启动后自动开启: " + (autoEnable.isSelected() ? "已开启" : "已关闭"));
        });

        // ===== 总开关 =====
        boolean enabled = "true".equalsIgnoreCase(ctx.config().getOrDefault(ColorBlindOverlayManager.KEY_MODE, "false"));
        CheckBox master = PageKit.toggle(enabled);
        master.setOnAction(e -> {
            boolean on = master.isSelected();
            ctx.config().put(ColorBlindOverlayManager.KEY_MODE, String.valueOf(on));
            if (!on) {
                // 一并关掉「启动后自动开启」：留着它，下次启动启动器它会把总开关顶回开启
                ctx.config().put(ColorBlindOverlayManager.KEY_AUTO_ENABLE, "false");
                autoEnable.setSelected(false);
            }
            // 先作用于矫正工具、再 saveConfig()：saveConfig 会把整份配置写回磁盘并重建设置页，
            // 顺序反了重建会读到旧值（「启动后自动开启」又被勾回去）
            ctx.applyColorBlindMode();
            ctx.saveConfig();
            ctx.toast(on
                    ? "色盲辅助: 已开启"
                    : "色盲辅助: 已关闭（「启动后自动开启」已一并关闭）");
        });
        root.getChildren().add(PageKit.settingsCard("色盲辅助",
                "开启后，每次启动游戏都会自动对游戏窗口启用色盲矫正；游戏退出后矫正自动结束（矫正工具不作用于桌面等其它窗口）。"
                        + "关闭后本设置会被记住，重启启动器不会自动打开",
                master));

        // ===== 色障类型 =====
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(ColorBlindOverlayManager.typeLabels());
        String currentType = ColorBlindOverlayManager.resolveType(ctx.config());
        typeCombo.setValue(ColorBlindOverlayManager.typeLabel(currentType));
        typeCombo.getStyleClass().add("select-field");
        typeCombo.setOnAction(e -> {
            String label = typeCombo.getValue();
            if (label == null) {
                return;
            }
            String code = ColorBlindOverlayManager.codeOfLabel(label);
            ctx.config().put(ColorBlindOverlayManager.KEY_TYPE, code);
            ctx.saveConfig();
            ctx.toast("色障类型: " + label + "（" + code + "）");
        });
        root.getChildren().add(PageKit.settingsCard("色障类型",
                "选择与你情况最接近的类型；不确定时先用默认的绿色盲 deuteranopia", typeCombo));

        // ===== 矫正强度 =====
        double currentStrength = ColorBlindOverlayManager.resolveStrength(ctx.config());
        Label strengthLabel = new Label("当前: " + ColorBlindOverlayManager.formatStrength(currentStrength));
        strengthLabel.getStyleClass().add("black-value-label");
        // 锁固有宽度：卡片挤压时数值不许被压成「当前…」
        strengthLabel.setMinWidth(Region.USE_PREF_SIZE);
        Slider strengthSlider = new Slider(0, 3, currentStrength);
        strengthSlider.getStyleClass().add("black-ticks");
        strengthSlider.setShowTickLabels(true);
        strengthSlider.setMajorTickUnit(1);
        strengthSlider.setBlockIncrement(0.1);
        strengthSlider.setPrefWidth(200);
        strengthSlider.valueProperty().addListener((obs, old, val) ->
                strengthLabel.setText("当前: " + ColorBlindOverlayManager.formatStrength(val.doubleValue())));
        Button saveStrength = AppIcons.button("save", "保存");
        saveStrength.getStyleClass().add("btn-primary");
        saveStrength.setOnAction(e -> {
            String value = ColorBlindOverlayManager.formatStrength(strengthSlider.getValue());
            ctx.config().put(ColorBlindOverlayManager.KEY_STRENGTH, value);
            ctx.saveConfig();
            ctx.toast("矫正强度已保存: " + value);
        });
        HBox strengthRow = new HBox(10, strengthSlider, strengthLabel, saveStrength);
        strengthRow.setAlignment(Pos.CENTER_LEFT);
        // 通栏（stacked）而不是与左侧说明挤同一行：说明文字长时右侧控件区会被压到溢出
        root.getChildren().add(PageKit.settingsCardStacked("矫正强度",
                "0.0 ~ 3.0，数值越大颜色偏移越明显、画面色彩也越夸张；建议从 1.0 开始微调", strengthRow));

        // ===== 游戏窗口标题 =====
        TextField windowField = new TextField(ctx.config().getOrDefault(ColorBlindOverlayManager.KEY_WINDOW, ""));
        windowField.getStyleClass().add("input-field");
        windowField.setPrefWidth(360);
        // TextField 的默认最小宽度很小，放在一行里会被长说明挤成一条缝（只剩几个像素宽）
        windowField.setMinWidth(260);
        windowField.setPromptText("留空 = 默认抓取 " + ColorBlindOverlayManager.DEFAULT_WINDOW);
        Button saveWindow = AppIcons.button("save", "保存");
        saveWindow.getStyleClass().add("btn-primary");
        Runnable commitWindow = () -> {
            String value = windowField.getText() == null ? "" : windowField.getText().trim();
            ctx.config().put(ColorBlindOverlayManager.KEY_WINDOW, value);
            ctx.saveConfig();
            ctx.toast(value.isEmpty()
                    ? "已改回默认窗口: " + ColorBlindOverlayManager.DEFAULT_WINDOW
                    : "游戏窗口标题已保存: " + value);
        };
        saveWindow.setOnAction(e -> commitWindow.run());
        // 回车即保存，省得每次都要摸鼠标点按钮
        windowField.setOnAction(e -> commitWindow.run());
        HBox windowRow = new HBox(10, windowField, saveWindow);
        windowRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(windowField, Priority.ALWAYS);
        root.getChildren().add(PageKit.settingsCardStacked("游戏窗口标题",
                "抓取哪个窗口做矫正：标题子串匹配且区分大小写（如填 Minecraft 可匹配「Minecraft 1.20.1」）；"
                        + "不填则使用默认的 " + ColorBlindOverlayManager.DEFAULT_WINDOW + "；填了自定义值就按自定义值匹配",
                windowRow));

        // ===== 自动开启 =====
        // 勾选框与处理逻辑在方法开头已创建（总开关需要引用它），这里只挂卡片
        root.getChildren().add(PageKit.settingsCard("启动后自动开启",
                "开启时：尚未设置过总开关的首次运行，启动器启动后色盲辅助默认开启；之后一律以总开关为准。"
                        + "手动关闭总开关会自动关闭本项，两个开关不会互相打架",
                autoEnable));

        // ===== 记住当前配置 =====
        CheckBox remember = PageKit.toggle(
                !"false".equalsIgnoreCase(ctx.config().getOrDefault(ColorBlindOverlayManager.KEY_REMEMBER, "true")));
        remember.setOnAction(e -> {
            ctx.config().put(ColorBlindOverlayManager.KEY_REMEMBER, String.valueOf(remember.isSelected()));
            ctx.saveConfig();
            ctx.toast("记住当前配置: " + (remember.isSelected() ? "已开启" : "已关闭（下次启动恢复默认值）"));
        });
        root.getChildren().add(PageKit.settingsCard("记住当前配置",
                "开启时：下次启动启动器沿用当前的色障类型 / 强度 / 窗口标题；关闭时：每次启动都恢复默认值",
                remember));

        // ===== 快速测试调节（本地网页工具）=====
        Label statusLabel = new Label(ColorBlindOverlayManager.statusText());
        statusLabel.getStyleClass().add("settings-card-desc");
        statusLabel.setWrapText(true);

        Button openTuneBtn = AppIcons.button("globe", "打开调节网页");
        openTuneBtn.getStyleClass().add("btn-primary");
        openTuneBtn.setOnAction(e -> {
            String url = ColorBlindOverlayManager.tuneServerUrl();
            if (url == null) {
                ctx.toast("无法启动本地调节服务");
                return;
            }
            ctx.openWebUrl(url);
            ctx.toast("已打开调节网页: " + url);
            statusLabel.setText(ColorBlindOverlayManager.statusText());
        });

        Button restartBtn = AppIcons.button("refresh", "重启矫正工具");
        restartBtn.getStyleClass().add("btn-primary");
        restartBtn.setOnAction(e -> {
            ctx.toast(ColorBlindOverlayManager.restartOverlay());
            statusLabel.setText(ColorBlindOverlayManager.statusText());
        });

        Button stopBtn = AppIcons.button("stop", "停止矫正工具");
        stopBtn.getStyleClass().add("btn-primary");
        stopBtn.setOnAction(e -> {
            ctx.toast(ColorBlindOverlayManager.stopOverlay());
            statusLabel.setText(ColorBlindOverlayManager.statusText());
        });

        HBox tuneButtons = new HBox(12, openTuneBtn, restartBtn, stopBtn);
        tuneButtons.setAlignment(Pos.CENTER_LEFT);
        VBox tuneBox = new VBox(10, statusLabel, tuneButtons);
        // 通栏布局：三个按钮挤在右侧控件区时会被卡片右边缘裁掉（「停止矫正工具」只露一半）
        root.getChildren().add(PageKit.settingsCardStacked("快速测试调节（实时看效果）",
                "在网页里做色觉自测小游戏：看图选数字即可推断色障类型、再挑出最清楚的强度档；"
                        + "也可以手动拖动强度 / 换类型。点「保存配置」写入配置文件（下次启动游戏生效）；"
                        + "点「重启矫正工具进程」按当前数值立即重启矫正工具，游戏窗口在运行时马上能看到效果"
                        + "（矫正工具窗口内按 F10 可手动退出）",
                tuneBox));

        return root;
    }

    /** 「矫正工具未安装」卡片：一键下载（走下载管理任务卡）与打开下载页两个入口 */
    private Node buildMissingToolCard() {
        Button downloadBtn = AppIcons.button("download", "下载矫正工具");
        downloadBtn.getStyleClass().add("btn-primary");
        Button openPageBtn = AppIcons.button("external-link", "打开下载页");
        downloadBtn.getStyleClass().add("btn-primary");
        openPageBtn.getStyleClass().add("btn-primary");
        openPageBtn.setOnAction(e -> ctx.openWebUrl(ColorBlindOverlayManager.DOWNLOAD_URL));

        HBox buttons = new HBox(12, downloadBtn, openPageBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox card = PageKit.settingsCardStacked("矫正工具未安装",
                "色盲辅助依赖外部矫正工具 ColorBlindOverlay.exe（不随启动器分发）。点「下载矫正工具」"
                        + "会自动下载并放到 Starlight-Launcher\\ColorBlindOverlay\\ 目录，无需手动安装；"
                        + "也可以到下载页手动获取后自行放置",
                buttons);
        downloadBtn.setOnAction(e -> downloadOverlay(downloadBtn, () -> {
            // 下载成功后把「未安装」卡片从页面上摘掉（页面实例未重建时立即生效）
            if (card.getParent() instanceof Pane holder) {
                holder.getChildren().remove(card);
            }
        }));
        return card;
    }

    /** 通过下载管理任务卡下载矫正工具（进度 / 取消 / 失败重试与下载中心一致） */
    private void downloadOverlay(Button downloadBtn, Runnable onDownloaded) {
        DownloadTaskManager downloads = ctx.downloadTasks();
        if (downloads == null) {
            // 无任务管理器的环境（离屏预览等）：退化为打开浏览器手动下载
            ctx.openWebUrl(ColorBlindOverlayManager.DOWNLOAD_URL);
            return;
        }
        downloadBtn.setDisable(true);
        DownloadTaskCard dialog = downloads.open("下载色盲矫正工具");
        dialog.stage("下载矫正工具...");
        dialog.beginFile("ColorBlindOverlay.exe");
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                File target = ColorBlindOverlayManager.prepareDownloadTarget();
                dialog.totalBytes(HttpDownloadEngine.probeFileSize(
                        HttpDownloadEngine.HTTP_CLIENT, ColorBlindOverlayManager.DOWNLOAD_URL));
                HttpDownloadEngine.downloadFileWithProgress(ColorBlindOverlayManager.DOWNLOAD_URL,
                        target.toPath(), dialog::progress, dialog.cancelFlag());
                dialog.finishFile(true);
                dialog.finish(true, "已下载到 " + target.getAbsolutePath(), null);
                Platform.runLater(() -> {
                    if (onDownloaded != null) {
                        onDownloaded.run();
                    }
                    ctx.toast("色盲矫正工具已下载完成，下次启动游戏时自动生效");
                });
            } catch (Exception ex) {
                boolean cancelled = HttpDownloadEngine.CANCELLED_MESSAGE.equals(ex.getMessage());
                dialog.finishFile(false);
                dialog.finish(false, cancelled ? "已取消下载" : "下载失败: " + ex.getMessage(), null);
                Platform.runLater(() -> downloadBtn.setDisable(false));
            }
        });
    }
}
