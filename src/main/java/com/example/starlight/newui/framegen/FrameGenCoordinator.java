package com.example.starlight.newui.framegen;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 帧生成器协调器（伴生模块，整合自原独立版 FrameGen 伴侣）。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）：
 * 引擎懒启动（配置 / 进程监听 / 核心控制器 / 关闭钩子）、系统托盘、
 * 帧生成设置页与状态刷新都在本类；主壳只保留 {@code frameGen} 单例与 4 个委派入口。
 */
public final class FrameGenCoordinator {

    private final LauncherContext host;

    // ==================== 帧生成器（伴生模块，原独立版整合进启动器） ====================
    // 说明：原 INS 键全局热键与悬浮控制页（OverlayMenu/GlobalKeyHook/FrameGenApp）已随整合移除，
    //       帧生成控制页内置于本启动器，系统托盘由「帧生成器」页内开关控制。
    private org.framegen.AppConfig fgConfig;                 // 帧生成配置（%APPDATA%\StarlightFrameGen\config.properties）
    private org.framegen.GameMonitor fgMonitor;              // Minecraft 进程监听（引擎懒启动后常驻）
    private org.framegen.FrameGenController fgController;     // 帧生成核心（LS 进程 / options.txt maxFps）
    private org.framegen.TrayManager fgTray;                 // 系统托盘（仅由开关创建）
    private boolean fgShutdownHookAdded = false;
    // 帧生成器页面内需实时刷新的控件引用（页面构建后缓存，不重建）
    private Label fgStatusGameLabel;
    private Label fgStatusFrameLabel;
    private Label fgOptionsLabel;          // options.txt 自动定位结果显示
    private Label fgLsStatusLabel;         // Lossless Scaling 当前模式显示
    private Label fgTrayStateLabel;
    private CheckBox fgTrayToggle;
    private Button[] fgMultiplierButtons;

    public FrameGenCoordinator(LauncherContext host) {
        this.host = host;
    }

    public void init() {
        try {
            org.framegen.AppConfig probe = new org.framegen.AppConfig();
            boolean needed = probe.isTrayEnabled() || probe.getFrameMultiplier() > 0;
            if (needed) {
                ensureFrameGenEngine();
            }
        }
        catch (Throwable t) {
            System.err.println("[FrameGen] 启动前检查失败: " + t);
        }
    }

    /** 帧生成引擎懒启动（幂等）：配置 / 进程监听 / 核心控制器 / 监听器 / 托盘 / 关闭钩子 */
    private void ensureFrameGenEngine() {
        if (fgController != null) {
            return;
        }
        try {
            org.framegen.AppConfig cfg = new org.framegen.AppConfig();
            fgConfig = cfg;
            // 傻瓜式：不手动写入游戏目录。每次搜索 options.txt 时实时读取启动器当前配置
            // （游戏目录 + 版本隔离目录，随用户切换版本/开关自动生效）
            fgMonitor = new org.framegen.GameMonitor(cfg);
            fgMonitor.setAuxiliaryGameDirProvider(() -> buildFrameGenSearchDirs());
            fgController = new org.framegen.FrameGenController(cfg, fgMonitor);

            // 游戏启动时：沿用已设倍率自动应用帧生成（原版行为），托盘可用则弹提示
            fgMonitor.addListener(new org.framegen.GameMonitor.GameStateListener() {
                @Override
                public void onGameStarted(String name, int pid) {
                    Platform.runLater(() -> {
                        try {
                            if (fgController != null && fgController.getCurrentMultiplier() > 0) {
                                fgController.applyFrameGen(fgController.getCurrentMultiplier());
                            }
                            if (fgTray != null && fgTray.isActive()) {
                                String detail = (pid > 0) ? (name + " (PID " + pid + ")") : name;
                                fgTray.showMessage("游戏已启动", detail, java.awt.TrayIcon.MessageType.INFO);
                            }
                        }
                        catch (Throwable t) {
                            t.printStackTrace();
                        }
                    });
                }

                @Override
                public void onGameStopped() {
                    // 保留当前帧生成设置；无需额外动作
                }
            });

            // 倍率变化（本页 / 托盘触发）时：托盘可用则弹气泡提示
            fgController.addListener((n, msg) -> {
                if (n > 0 && fgTray != null && fgTray.isActive()) {
                    Platform.runLater(() -> fgTray.showMessage("帧生成已启用", msg, java.awt.TrayIcon.MessageType.INFO));
                }
            });

            // 沿用原版行为：恢复上次保存的倍率（开启 Lossless Scaling 或写入 maxFps）
            if (cfg.getFrameMultiplier() > 0) {
                fgController.applyFrameGen(cfg.getFrameMultiplier());
            }
            fgMonitor.start();

            // 系统托盘：按上次保存的开关状态恢复
            if (cfg.isTrayEnabled()) {
                applyFrameGenTray(true);
            }

            // 退出清理：停掉 Lossless Scaling、还原 options.txt 的 maxFps
            if (!fgShutdownHookAdded) {
                fgShutdownHookAdded = true;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        if (fgController != null) {
                            fgController.shutdown();
                        }
                    }
                    catch (Throwable ignored) {
                    }
                }, "FrameGenShutdownHook"));
            }
        }
        catch (Throwable t) {
            System.err.println("[FrameGen] 引擎初始化失败: " + t);
            fgConfig = null;
            fgMonitor = null;
            fgController = null;
            fgTray = null;
        }
    }

    /** 系统托盘启停：true=显示托盘图标，false=移除 */
    private void applyFrameGenTray(boolean on) {
        if (fgConfig == null || fgMonitor == null || fgController == null) {
            return;
        }
        if (on) {
            if (fgTray == null) {
                fgTray = new org.framegen.TrayManager(fgConfig, fgMonitor, fgController,
                        () -> Platform.runLater(() -> openFrameGenFromTray()));
            }
            boolean ok = fgTray.start();
            if (!ok) {
                fgTray = null;
                // 启动失败（系统托盘不支持等）：回退已保存的开关状态，避免下次启动反复尝试
                fgConfig.setTrayEnabled(false);
                host.toast("系统托盘不可用，无法启用帧生成托盘");
            }
            else {
                refreshFrameGenTrayControl();
            }
        }
        else if (fgTray != null) {
            fgTray.stop();
            fgTray = null;
            refreshFrameGenTrayControl();
        }
    }

    /** 托盘开关状态回显（页面内控件与真实托盘状态保持一致） */
    private void refreshFrameGenTrayControl() {
        boolean active = fgTray != null && fgTray.isActive();
        if (fgTrayToggle != null) {
            fgTrayToggle.setSelected(active);
        }
        if (fgTrayStateLabel != null) {
            if (active) {
                fgTrayStateLabel.setText("已启用");
            }
            else if (fgConfig != null && fgConfig.isTrayEnabled()) {
                fgTrayStateLabel.setText("启用失败（系统托盘不可用）");
            }
            else {
                fgTrayStateLabel.setText("未启用");
            }
        }
    }

    /** 托盘入口：恢复启动器窗口并切换到「帧生成器」页面 */
    private void openFrameGenFromTray() {
        ensureFrameGenEngine();
        if (host.stage() != null) {
            host.stage().setIconified(false);
            if (!host.stage().isShowing()) {
                host.stage().show();
            }
            host.stage().toFront();
        }
        ensureFrameGenPage();
        host.switchToPage("frameGen");
    }

    /** 确保「帧生成器」页面已构建并缓存 */
    private void ensureFrameGenPage() {
        if (host.cachedPage("frameGen") == null) {
            host.cachePage("frameGen", buildPage());
        }
    }

    /**
     * 构建帧生成器的 options.txt 自动搜索目录（实时反映启动器配置，支持“版本隔离”）：
     * 启用版本隔离时优先搜索 “游戏目录\versions\当前版本”，否则游戏目录，再补相对/绝对形态。
     */
    private List<String> buildFrameGenSearchDirs() {
        List<String> dirs = new ArrayList<>();
        if (host.config() == null) {
            return dirs;
        }
        String gameDir = host.config().getOrDefault("GameDir", ".minecraft");
        if (gameDir == null || gameDir.trim().isEmpty()) {
            gameDir = ".minecraft";
        }
        gameDir = gameDir.trim();
        boolean isolated = "true".equalsIgnoreCase(host.config().getOrDefault("VersionIsolation", "false"));
        String version = host.config().getOrDefault("Version", "");
        String versionDir = "";
        if (version != null && !version.trim().isEmpty()) {
            versionDir = gameDir + File.separator + "versions" + File.separator + version.trim();
        }
        if (isolated && !versionDir.isEmpty()) {
            dirs.add(versionDir);   // 版本隔离：options.txt 在该版本独立目录内，优先
            dirs.add(gameDir);      // 兜底仍检查游戏根目录
        } else {
            dirs.add(gameDir);
            if (!versionDir.isEmpty()) {
                dirs.add(versionDir);
            }
        }
        // 相对路径补充绝对形态（相对启动器运行目录解析），提高命中率
        if (!gameDir.contains(":") && !gameDir.startsWith("/") && !gameDir.startsWith("\\\\")) {
            String userDir = System.getProperty("user.dir");
            if (userDir != null) {
                dirs.add(userDir + File.separator + gameDir);
            }
        }
        return dirs;
    }

    /** 启动器拉起游戏后通知帧生成器（仅当帧生成引擎已启用/历史使用过时生效） */
    public void notifyGameStarted(UIGeneralControlClass.LaunchConfig config, String version) {
        if (fgMonitor == null) {
            // 引擎未初始化：若历史保存过倍率/启用过托盘则即时补启动，避免错过启动信号
            try {
                org.framegen.AppConfig probe = new org.framegen.AppConfig();
                if (!probe.isTrayEnabled() && probe.getFrameMultiplier() <= 0) {
                    return;
                }
            }
            catch (Throwable ignored) {
                return;
            }
            ensureFrameGenEngine();
            if (fgMonitor == null) {
                return;
            }
        }
        try {
            String name = (config != null && config.version != null && !config.version.isEmpty())
                    ? "Minecraft " + config.version : "Minecraft";
            fgMonitor.notifyGameStarted(name, -1);
        }
        catch (Throwable t) {
            System.err.println("[FrameGen] 通知游戏启动失败: " + t);
        }
    }

    /** 启动器收到游戏退出后通知帧生成器（仅当帧生成引擎已启用时生效） */
    public void notifyGameStopped() {
        if (fgMonitor == null) {
            return;
        }
        try {
            fgMonitor.notifyGameStopped();
        }
        catch (Throwable t) {
            System.err.println("[FrameGen] 通知游戏退出失败: " + t);
        }
    }

    /** 保存 Lossless Scaling 路径并即时生效（已启用倍率时自动重新应用以切换模式） */
    private void fgSaveLosslessScalingPath(String path) {
        if (fgConfig == null) {
            return;
        }
        String v = (path == null) ? "" : path.trim();
        fgConfig.setLosslessScalingPath(v);
        if (fgController != null && fgController.getCurrentMultiplier() > 0) {
            fgController.applyFrameGen(fgController.getCurrentMultiplier());
        }
        refreshFrameGenUi();
    }

    /** 帧生成器页面 1s 定时刷新：游戏状态 / 帧生成状态 / 倍率高亮 / 托盘开关回显 */
    private void refreshFrameGenUi() {
        if (fgStatusGameLabel == null && fgStatusFrameLabel == null) {
            return;
        }
        boolean game = fgMonitor != null && fgMonitor.isGameDetected();
        if (fgStatusGameLabel != null) {
            if (game) {
                int pid = fgMonitor != null ? fgMonitor.getGamePid() : -1;
                fgStatusGameLabel.setText(pid > 0 ? "运行中（PID " + pid + "）" : "运行中");
                fgStatusGameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: -sl-ok;");
            }
            else {
                fgStatusGameLabel.setText("等待 Minecraft 启动…");
                fgStatusGameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: -sl-idle;");
            }
        }
        int mult = fgController != null ? fgController.getCurrentMultiplier() : 0;
        int base = fgConfig != null ? fgConfig.getBaseFps() : 60;
        if (fgStatusFrameLabel != null) {
            if (mult > 0) {
                fgStatusFrameLabel.setText(mult + "x 已启用（目标 " + (base * mult) + " FPS）");
                fgStatusFrameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: -sl-ok;");
            }
            else {
                fgStatusFrameLabel.setText("未启用");
                fgStatusFrameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: -sl-idle;");
            }
        }
        if (fgMultiplierButtons != null) {
            int[] vals = {0, 2, 4, 6, 8};
            for (int i = 0; i < fgMultiplierButtons.length && i < vals.length; i++) {
                Button btn = fgMultiplierButtons[i];
                if (btn == null) {
                    continue;
                }
                // 选中态：真正生效的倍率用绿色，关闭态用主题蓝 —— 否则「关闭」显示成绿色会让人
                // 误以为帧生成是开着的
                btn.setStyle(vals[i] == mult
                        ? (mult > 0 ? "-fx-background-color: #16a34a; -fx-text-fill: white;"
                                    : "-fx-background-color: #3b82f6; -fx-text-fill: white;")
                        : "");
            }
        }
        if (fgOptionsLabel != null) {
            File optionsFile = fgMonitor != null ? fgMonitor.findOptionsFile() : null;
            if (optionsFile != null) {
                fgOptionsLabel.setText("options.txt 已自动定位: " + optionsFile.getAbsolutePath());
            }
            else {
                AppIcons.setText(fgOptionsLabel, "file", "options.txt 尚未找到（游戏首次启动后会自动生成，届时自动生效，无需任何设置）");
            }
        }
        if (fgLsStatusLabel != null) {
            String lsPath = fgConfig != null ? fgConfig.getLosslessScalingPath() : "";
            boolean lsReady = lsPath != null && !lsPath.trim().isEmpty()
                    && new File(lsPath.trim()).isFile();
            fgLsStatusLabel.setText(lsReady
                    ? "当前模式：Lossless Scaling（lsfg 帧生成）"
                    : "当前模式：options.txt 自动（FPS 限制）");
        }
        refreshFrameGenTrayControl();
    }

    /** 构建「帧生成器」页面（构建时懒启动帧生成引擎） */
    public Node buildPage() {
        ensureFrameGenEngine();
        VBox root = new VBox(12);
        root.setPadding(new Insets(0, 4, 0, 0));

        // 标题行：← 返回首页
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Button backBtn = AppIcons.button("back", "返回");
        backBtn.getStyleClass().add("back-btn");
        backBtn.setOnAction(e -> host.switchToPage("home"));
        Label titleLabel = new Label("帧生成器");
        titleLabel.getStyleClass().add("content-title");
        header.getChildren().addAll(backBtn, titleLabel);
        root.getChildren().add(header);

        // ===== 运行状态 =====
        VBox statusCard = new VBox(10);
        statusCard.getStyleClass().add("settings-card");
        Label statusTitle = AppIcons.label("desktop", "运行状态");
        statusTitle.getStyleClass().add("settings-card-title");
        statusTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");
        HBox statusCols = new HBox(50);
        VBox gameCol = new VBox(4);
        Label gameCap = new Label("游戏状态");
        gameCap.getStyleClass().add("settings-card-desc");
        fgStatusGameLabel = new Label("等待 Minecraft 启动…");
        fgStatusGameLabel.getStyleClass().add("settings-card-title");
        fgStatusGameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");
        fgStatusGameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: -sl-idle;");
        gameCol.getChildren().addAll(gameCap, fgStatusGameLabel);
        VBox frameCol = new VBox(4);
        Label frameCap = new Label("当前帧生成");
        frameCap.getStyleClass().add("settings-card-desc");
        fgStatusFrameLabel = new Label("未启用");
        fgStatusFrameLabel.getStyleClass().add("settings-card-title");
        fgStatusFrameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");
        fgStatusFrameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: -sl-idle;");
        frameCol.getChildren().addAll(frameCap, fgStatusFrameLabel);
        statusCols.getChildren().addAll(gameCol, frameCol);
        Label modeHint = new Label("全程自动：选中倍率后，检测到 Minecraft 启动会自动定位其 options.txt 并写入 maxFps（目标 = 基础 FPS × 倍率），关闭倍率或退出启动器时自动还原；如需使用 Lossless Scaling 硬件帧生成，可在下方“Lossless Scaling（可选）”中自行配置其路径，配置后自动优先使用。");
        modeHint.getStyleClass().add("settings-card-desc");
        modeHint.setWrapText(true);
        fgOptionsLabel = new Label("正在自动搜索 options.txt …");
        fgOptionsLabel.getStyleClass().add("settings-card-desc");
        fgOptionsLabel.setWrapText(true);
        statusCard.getChildren().addAll(statusTitle, statusCols, modeHint, fgOptionsLabel);
        root.getChildren().add(statusCard);

        // ===== 倍率选择 =====
        VBox rateCard = new VBox(10);
        rateCard.getStyleClass().add("settings-card");
        Label rateTitle = AppIcons.label("bolt", "帧生成倍率");
        rateTitle.getStyleClass().add("settings-card-title");
        rateTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");
        Label rateDesc = new Label("关闭或选择 2x / 4x / 6x / 8x 立即生效（目标 FPS = 基础 FPS × 倍率）；无需先配置任何目录，options.txt 全自动定位");
        rateDesc.getStyleClass().add("settings-card-desc");
        HBox rateRow = new HBox(8);
        rateRow.setAlignment(Pos.CENTER_LEFT);
        int[] multVals = {0, 2, 4, 6, 8};
        String[] multNames = {"关闭", "2x", "4x", "6x", "8x"};
        fgMultiplierButtons = new Button[multVals.length];
        for (int i = 0; i < multVals.length; i++) {
            final int mv = multVals[i];
            Button btn = new Button(multNames[i]);
            btn.getStyleClass().add("btn-primary");
            btn.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(btn, Priority.ALWAYS);
            btn.setOnAction(e -> {
                if (fgController == null) {
                    return;
                }
                fgController.applyFrameGen(mv);
                host.toast(mv == 0 ? "帧生成已关闭" : "帧生成 " + mv + "x 已应用");
                refreshFrameGenUi();
            });
            fgMultiplierButtons[i] = btn;
            rateRow.getChildren().add(btn);
        }
        rateCard.getChildren().addAll(rateTitle, rateDesc, rateRow);
        root.getChildren().add(rateCard);

        // ===== Lossless Scaling（可选 · 自主配置）=====
        VBox lsCard = new VBox(10);
        lsCard.getStyleClass().add("settings-card");
        Label lsTitle = new Label("Lossless Scaling（可选）");
        lsTitle.getStyleClass().add("settings-card-title");
        lsTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");
        Label lsDesc = new Label("自主配置：设置 Lossless Scaling.exe 路径后，帧生成优先使用其 lsfg 模式；留空则自动使用 options.txt FPS 限制模式（无需其它任何配置）。");
        lsDesc.getStyleClass().add("settings-card-desc");
        lsDesc.setWrapText(true);
        TextField lsPathField = new TextField(fgConfig != null ? fgConfig.getLosslessScalingPath() : "");
        lsPathField.getStyleClass().add("input-field");
        lsPathField.setPrefWidth(360);
        lsPathField.setPromptText("LosslessScaling.exe 完整路径（留空 = options.txt 自动模式）");
        Button lsBrowseBtn = AppIcons.button("folder", "浏览…");
        lsBrowseBtn.getStyleClass().add("btn-primary");
        lsBrowseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择 Lossless Scaling.exe");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("可执行文件", "*.exe"));
            File f = fc.showOpenDialog(host.stage());
            if (f != null) {
                lsPathField.setText(f.getAbsolutePath());
                fgSaveLosslessScalingPath(f.getAbsolutePath());
                host.toast("Lossless Scaling 路径已保存");
            }
        });
        Button lsSaveBtn = AppIcons.button("save", "保存");
        lsSaveBtn.getStyleClass().add("btn-primary");
        lsSaveBtn.setOnAction(e -> {
            String v = lsPathField.getText() == null ? "" : lsPathField.getText().trim();
            fgSaveLosslessScalingPath(v);
            host.toast(v.isEmpty() ? "已改用 options.txt 自动模式" : "Lossless Scaling 路径已保存");
        });
        Button lsClearBtn = AppIcons.button("close", "清除");
        lsClearBtn.getStyleClass().add("btn-primary");
        lsClearBtn.setOnAction(e -> {
            lsPathField.setText("");
            fgSaveLosslessScalingPath("");
            host.toast("已清除，改用 options.txt 自动模式");
        });
        HBox lsRow = new HBox(10, lsPathField, lsBrowseBtn, lsSaveBtn, lsClearBtn);
        lsRow.setAlignment(Pos.CENTER_LEFT);
        fgLsStatusLabel = new Label("");
        fgLsStatusLabel.getStyleClass().add("settings-card-desc");
        lsCard.getChildren().addAll(lsTitle, lsDesc, lsRow, fgLsStatusLabel);
        root.getChildren().add(lsCard);

        // ===== 系统托盘 =====
        fgTrayToggle = PageKit.toggle(false);
        boolean trayActive = fgTray != null && fgTray.isActive();
        fgTrayToggle.setSelected(trayActive);
        fgTrayStateLabel = new Label("");
        fgTrayStateLabel.getStyleClass().add("settings-card-desc");
        fgTrayToggle.setOnAction(e -> {
            if (fgConfig == null) {
                return;
            }
            boolean on = fgTrayToggle.isSelected();
            fgConfig.setTrayEnabled(on);
            applyFrameGenTray(on);
            host.toast(on ? "正在启用帧生成系统托盘…" : "帧生成系统托盘已停用");
            refreshFrameGenTrayControl();
        });
        HBox trayRow = new HBox(14, fgTrayToggle, fgTrayStateLabel);
        trayRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("系统托盘", "在系统托盘显示帧生成图标：左键打开本页面，右键快捷切换倍率（原 INS 键全局呼出方式已移除）", trayRow));

        // ===== 定时刷新（1s） =====
        Timeline fgRefresh = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshFrameGenUi()));
        fgRefresh.setCycleCount(Animation.INDEFINITE);
        fgRefresh.play();
        refreshFrameGenUi();
        return root;
    }
}

