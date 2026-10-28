package com.example.starlight.newui.page.settings;


import com.example.starlight.util.I18N;
import com.example.starlight.colorblind.ColorBlindOverlayManager;
import com.example.starlight.download.task.OverlayDownloader;
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
        VBox root = PageKit.settingsPage(ctx, I18N.get("colorblind.t0001"));

        // 未安装矫正工具：仅显示安装卡片（隔离引导），不渲染后续任何设置项。
        // 下载成功后就地补全完整设置面板（无需手动重建页面）。
        if (!ColorBlindOverlayManager.isExeAvailable()) {
            root.getChildren().add(buildMissingToolCard(root));
            return root;
        }

        buildSettingsCards(root);
        return root;
    }

    /** 已安装形态的全部设置卡片（build() 主路径与下载后就地补全共用，避免两份逻辑漂移） */
    private void buildSettingsCards(VBox root) {
        // 「启动后自动开启」在总开关之前创建：关闭总开关时要连带把它取消勾选（两个开关不该互相矛盾）
        CheckBox autoEnable = PageKit.toggle(
                !"false".equalsIgnoreCase(ctx.config().getOrDefault(ColorBlindOverlayManager.KEY_AUTO_ENABLE, "true")));
        autoEnable.setOnAction(e -> {
            ctx.config().put(ColorBlindOverlayManager.KEY_AUTO_ENABLE, String.valueOf(autoEnable.isSelected()));
            ctx.saveConfig();
            ctx.toast(I18N.get("colorblind.k0017", (autoEnable.isSelected() ? I18N.get("colorblind.t0002") : I18N.get("colorblind.t0003"))));
        });

        // 总开关 //
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
                    ? I18N.get("colorblind.t0004")
                    : I18N.get("colorblind.t0005"));
        });
        root.getChildren().add(PageKit.settingsCard(I18N.get("colorblind.t0006"),
                I18N.get("colorblind.t0031"),
                master));

        // 色障类型 //
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
            ctx.toast(I18N.get("colorblind.k0018", label + "（" + code + "）"));
        });
        root.getChildren().add(PageKit.settingsCard(I18N.get("colorblind.t0007"),
                I18N.get("colorblind.t0008"), typeCombo));

        // 矫正强度 //
        double currentStrength = ColorBlindOverlayManager.resolveStrength(ctx.config());
        Label strengthLabel = new Label(I18N.get("colorblind.k0001", ColorBlindOverlayManager.formatStrength(currentStrength)));
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
                strengthLabel.setText(I18N.get("colorblind.k0002", ColorBlindOverlayManager.formatStrength(val.doubleValue()))));
        Button saveStrength = AppIcons.button("save", I18N.get("colorblind.t0009"));
        saveStrength.getStyleClass().add("btn-primary");
        saveStrength.setOnAction(e -> {
            String value = ColorBlindOverlayManager.formatStrength(strengthSlider.getValue());
            ctx.config().put(ColorBlindOverlayManager.KEY_STRENGTH, value);
            ctx.saveConfig();
            ctx.toast(I18N.get("colorblind.k0003", value));
        });
        HBox strengthRow = new HBox(10, strengthSlider, strengthLabel, saveStrength);
        strengthRow.setAlignment(Pos.CENTER_LEFT);
        // 通栏（stacked）而不是与左侧说明挤同一行：说明文字长时右侧控件区会被压到溢出
        root.getChildren().add(PageKit.settingsCardStacked(I18N.get("colorblind.t0010"),
                I18N.get("colorblind.t0011"), strengthRow));

        // 游戏窗口标题 //
        TextField windowField = new TextField(ctx.config().getOrDefault(ColorBlindOverlayManager.KEY_WINDOW, ""));
        windowField.getStyleClass().add("input-field");
        windowField.setPrefWidth(360);
        // TextField 的默认最小宽度很小，放在一行里会被长说明挤成一条缝（只剩几个像素宽）
        windowField.setMinWidth(260);
        windowField.setPromptText(I18N.get("colorblind.k0004", ColorBlindOverlayManager.DEFAULT_WINDOW));
        Button saveWindow = AppIcons.button("save", I18N.get("colorblind.t0012"));
        saveWindow.getStyleClass().add("btn-primary");
        Runnable commitWindow = () -> {
            String value = windowField.getText() == null ? "" : windowField.getText().trim();
            ctx.config().put(ColorBlindOverlayManager.KEY_WINDOW, value);
            ctx.saveConfig();
            ctx.toast(value.isEmpty()
                    ? I18N.get("colorblind.r0001", ColorBlindOverlayManager.DEFAULT_WINDOW)
                    : I18N.get("colorblind.r0002", value));
        };
        saveWindow.setOnAction(e -> commitWindow.run());
        // 回车即保存，省得每次都要摸鼠标点按钮
        windowField.setOnAction(e -> commitWindow.run());
        HBox windowRow = new HBox(10, windowField, saveWindow);
        windowRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(windowField, Priority.ALWAYS);
        root.getChildren().add(PageKit.settingsCardStacked(I18N.get("colorblind.t0013"),
                I18N.get("colorblind.p0035", ColorBlindOverlayManager.DEFAULT_WINDOW),
                windowRow));

        // 自动开启 //
        // 勾选框与处理逻辑在方法开头已创建（总开关需要引用它），这里只挂卡片
        root.getChildren().add(PageKit.settingsCard(I18N.get("colorblind.t0014"),
                I18N.get("colorblind.t0032"),
                autoEnable));

        // 记住当前配置 //
        CheckBox remember = PageKit.toggle(
                !"false".equalsIgnoreCase(ctx.config().getOrDefault(ColorBlindOverlayManager.KEY_REMEMBER, "true")));
        remember.setOnAction(e -> {
            ctx.config().put(ColorBlindOverlayManager.KEY_REMEMBER, String.valueOf(remember.isSelected()));
            ctx.saveConfig();
            ctx.toast(I18N.get("colorblind.k0019", (remember.isSelected() ? I18N.get("colorblind.t0015") : I18N.get("colorblind.t0016"))));
        });
        root.getChildren().add(PageKit.settingsCard(I18N.get("colorblind.t0017"),
                I18N.get("colorblind.t0018"),
                remember));

        // 快速测试调节（本地网页工具） //
        Label statusLabel = new Label(ColorBlindOverlayManager.statusText());
        statusLabel.getStyleClass().add("settings-card-desc");
        statusLabel.setWrapText(true);

        Button openTuneBtn = AppIcons.button("globe", I18N.get("colorblind.t0019"));
        openTuneBtn.getStyleClass().add("btn-primary");
        openTuneBtn.setOnAction(e -> {
            String url = ColorBlindOverlayManager.tuneServerUrl();
            if (url == null) {
                ctx.toast(I18N.get("colorblind.t0020"));
                return;
            }
            ctx.openWebUrl(url);
            ctx.toast(I18N.get("colorblind.k0005", url));
            statusLabel.setText(ColorBlindOverlayManager.statusText());
        });

        Button restartBtn = AppIcons.button("refresh", I18N.get("colorblind.t0021"));
        restartBtn.getStyleClass().add("btn-primary");
        restartBtn.setOnAction(e -> {
            ctx.toast(ColorBlindOverlayManager.restartOverlay());
            statusLabel.setText(ColorBlindOverlayManager.statusText());
        });

        Button stopBtn = AppIcons.button("stop", I18N.get("colorblind.t0022"));
        stopBtn.getStyleClass().add("btn-primary");
        stopBtn.setOnAction(e -> {
            ctx.toast(ColorBlindOverlayManager.stopOverlay());
            statusLabel.setText(ColorBlindOverlayManager.statusText());
        });

        HBox tuneButtons = new HBox(12, openTuneBtn, restartBtn, stopBtn);
        tuneButtons.setAlignment(Pos.CENTER_LEFT);
        VBox tuneBox = new VBox(10, statusLabel, tuneButtons);
        // 通栏布局：三个按钮挤在右侧控件区时会被卡片右边缘裁掉（「停止矫正工具」只露一半）
        root.getChildren().add(PageKit.settingsCardStacked(I18N.get("colorblind.t0023"),
                I18N.get("colorblind.t0033"),
                tuneBox));
    }

    /**
     * 「矫正工具未安装」卡片：一键下载（走下载管理任务卡）与打开下载页两个入口。
     *
     * <p>未安装形态下本页只渲染这张卡（§6.9 隔离引导）。下载成功后把本卡从页面摘除，
     * 并把完整设置面板就地补进 root，使用户不用离开页面即可继续配置。
     */
    private Node buildMissingToolCard(VBox root) {
        Button downloadBtn = AppIcons.button("download", I18N.get("colorblind.t0024"));
        downloadBtn.getStyleClass().add("btn-primary");
        Button openPageBtn = AppIcons.button("external-link", I18N.get("colorblind.t0025"));
        downloadBtn.getStyleClass().add("btn-primary");
        openPageBtn.getStyleClass().add("btn-primary");
        openPageBtn.setOnAction(e -> ctx.openWebUrl(ColorBlindOverlayManager.DOWNLOAD_URL));

        HBox buttons = new HBox(12, downloadBtn, openPageBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox card = PageKit.settingsCardStacked(I18N.get("colorblind.t0026"),
                I18N.get("colorblind.t0034"),
                buttons);
        downloadBtn.setOnAction(e -> downloadOverlay(downloadBtn, () -> {
            // 下载成功：先摘掉「未安装」卡片，再补全完整设置面板（页面实例未重建时立即生效）
            if (card.getParent() instanceof Pane holder) {
                holder.getChildren().remove(card);
            }
            appendInstalledPanels(root);
        }));
        return card;
    }

    /** 下载成功后就地补全的完整设置面板（与 build() 已安装形态共用 buildSettingsCards，避免两份逻辑漂移） */
    private void appendInstalledPanels(VBox root) {
        if (!ColorBlindOverlayManager.isExeAvailable()) return;
        buildSettingsCards(root);
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
        DownloadTaskCard dialog = downloads.open(I18N.get("colorblind.t0027"));
        dialog.stage(I18N.get("colorblind.t0028"));
        dialog.beginFile("ColorBlindOverlay.exe");
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                File target = ColorBlindOverlayManager.prepareDownloadTarget();
                dialog.totalBytes(OverlayDownloader.probeSize(ColorBlindOverlayManager.DOWNLOAD_URL));
                OverlayDownloader.download(ColorBlindOverlayManager.DOWNLOAD_URL,
                        target.toPath(), dialog::progress, dialog.cancelFlag());
                dialog.finishFile(true);
                dialog.finish(true, I18N.get("colorblind.k0006", target.getAbsolutePath()), null);
                Platform.runLater(() -> {
                    if (onDownloaded != null) {
                        onDownloaded.run();
                    }
                    ctx.toast(I18N.get("colorblind.t0029"));
                });
            } catch (Exception ex) {
                boolean cancelled = OverlayDownloader.CANCELLED_MESSAGE.equals(ex.getMessage());
                dialog.finishFile(false);
                dialog.finish(false, cancelled ? I18N.get("colorblind.t0030") : I18N.get("colorblind.k0007", ex.getMessage()), null);
                Platform.runLater(() -> downloadBtn.setDisable(false));
            }
        });
    }
}
