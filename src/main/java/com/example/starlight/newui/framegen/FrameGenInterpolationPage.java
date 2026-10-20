package com.example.starlight.newui.framegen;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.IntSupplier;

/**
 * 「帧插值」设置页。
 *
 * <p>与现有「帧生成器」页面（倍率 + maxFps 语义）并存：
 * 那边管理旧的 javaagent / 倍率逻辑，这边管理与 {@code framegen-host.exe}
 * 对接的新插值链路。两者互不改写对方的设置文件。
 */
public final class FrameGenInterpolationPage {

    private final LauncherContext host;
    /** 游戏 PID 提供者；可空，为空时视为「游戏未启动」 */
    private final IntSupplier gamePidSupplier;

    private final Label backendLabel = new Label("未连接");
    private final Label captureFpsLabel = new Label("—");
    private final Label presentFpsLabel = new Label("—");
    private final Label interpMsLabel = new Label("—");
    private final Label dropLabel = new Label("—");
    private final Label paceLabel = new Label("—");
    private final Label modeLabel = new Label("—");

    private final CheckBox enableToggle;
    private final CheckBox competitiveToggle;
    private final Slider targetSlider;
    private final Label targetLabel;
    private final Button[] qualityButtons = new Button[2];

    private final FrameGenSettings settings;
    private final FrameGenHostManager manager;
    private final Timeline refresh;

    // 差分算 fps 用的上一次采样
    private long lastCaptureCount;
    private long lastPresentCount;
    private long lastSampleTimeMs;
    private double lastCaptureFps;
    private double lastPresentFps;

    public FrameGenInterpolationPage(LauncherContext host) {
        this(host, null);
    }

    public FrameGenInterpolationPage(LauncherContext host, IntSupplier gamePidSupplier) {
        this.host = host;
        this.gamePidSupplier = gamePidSupplier;
        this.settings = FrameGenSettings.load();

        this.enableToggle = PageKit.toggle(settings.isEnabled());
        this.competitiveToggle = PageKit.toggle(settings.isCompetitiveMode());

        this.targetSlider = new Slider(60, 240, settings.getTargetFps());
        this.targetSlider.setMajorTickUnit(30);
        this.targetSlider.setMinorTickCount(0);
        this.targetSlider.setSnapToTicks(true);
        this.targetSlider.setShowTickLabels(true);
        this.targetLabel = new Label(settings.getTargetFps() + " fps");
        this.targetLabel.getStyleClass().add("settings-card-title");

        this.manager = new FrameGenHostManager(
                settings,
                st -> Platform.runLater(() -> applyStatus(st)),
                err -> Platform.runLater(() -> toast("帧插值：" + err, true)));

        this.refresh = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshView()));
        this.refresh.setCycleCount(Animation.INDEFINITE);
        this.refresh.play();

        Runtime.getRuntime().addShutdownHook(
                new Thread(manager::shutdown, "FrameGen-Interp-Shutdown"));
    }

    // -----------------------------------------------------------------
    // 页面构建
    // -----------------------------------------------------------------

    public Node build() {
        VBox root = PageKit.settingsPage(host, "帧插值");

        root.getChildren().add(buildStatusCard());
        root.getChildren().add(buildModeCard());
        root.getChildren().add(buildQualityCard());
        root.getChildren().add(buildTargetCard());
        root.getChildren().add(buildWarningCard());

        refreshView();
        return root;
    }

    private Node buildStatusCard() {
        HBox row = new HBox(36);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(
                metricColumn("捕获后端", backendLabel),
                metricColumn("捕获 FPS", captureFpsLabel),
                metricColumn("呈现 FPS", presentFpsLabel),
                metricColumn("插值耗时", interpMsLabel),
                metricColumn("丢弃帧", dropLabel),
                metricColumn("实际节奏", paceLabel));

        Pane statusHost = new VBox(10, row, modeLabel);
        modeLabel.getStyleClass().add("settings-card-desc");
        return PageKit.settingsCardStacked(
                "运行状态",
                "实时遥测由 framegen-host.exe 通过本地 IPC 推送，无需手动刷新。"
                        + "呈现 FPS 以「实际显示到屏幕的次数」为准，不是提交次数。",
                statusHost);
    }

    private VBox metricColumn(String caption, Label value) {
        VBox col = new VBox(4);
        Label cap = new Label(caption);
        cap.getStyleClass().add("settings-card-desc");
        value.getStyleClass().add("settings-card-title");
        value.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");
        col.getChildren().addAll(cap, value);
        return col;
    }

    private Node buildModeCard() {
        HBox enableRow = new HBox(14, enableToggle, new Label("启用帧插值"));
        enableRow.setAlignment(Pos.CENTER_LEFT);
        enableToggle.setOnAction(e -> {
            boolean on = enableToggle.isSelected();
            settings.setEnabled(on);
            if (on) {
                int pid = currentGamePid();
                if (pid > 0) {
                    if (!FrameGenHostManager.isHostAvailable()) {
                        toast("未找到 framegen-host.exe，请先完成部署", true);
                        enableToggle.setSelected(false);
                        settings.setEnabled(false);
                    } else if (manager.start(pid)) {
                        toast("帧插值已启动");
                    }
                } else {
                    toast("请先启动 Minecraft，再开启帧插值", true);
                    enableToggle.setSelected(false);
                    settings.setEnabled(false);
                }
            } else {
                manager.stop();
                toast("帧插值已停止");
            }
            refreshView();
        });

        HBox compRow = new HBox(14, competitiveToggle,
                new Label("竞技模式（直通画面，延迟最低）"));
        compRow.setAlignment(Pos.CENTER_LEFT);
        competitiveToggle.setOnAction(e -> {
            boolean on = competitiveToggle.isSelected();
            settings.setCompetitiveMode(on);
            if (manager.isRunning()) {
                // stop 只关插值、不关呈现，所以画面不会冻结
                if (on) {
                    manager.sendStop();
                } else {
                    manager.sendStart();
                }
            }
            toast(on ? "已切换为直通（不插值）" : "已恢复插值");
            refreshView();
        });

        VBox box = new VBox(12, enableRow, compRow);
        return PageKit.settingsCardStacked(
                "开关",
                "竞技模式会保留捕获与呈现链路，只关闭插值；"
                        + "画面延迟恢复到接近原生，适合 PVP、速通等对输入延迟敏感的场景。",
                box);
    }

    private Node buildQualityCard() {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        String[] names = {"基础 · 帧混合", "标准 · 块匹配"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = new Button(names[i]);
            b.getStyleClass().add("btn-primary");
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
            b.setOnAction(e -> {
                settings.setQuality(idx);
                manager.sendConfig();
                toast("质量档位：" + names[idx]);
                refreshView();
            });
            qualityButtons[i] = b;
            row.getChildren().add(b);
        }

        Label hint = PageKit.hintLabel(
                "基础 · 帧混合：最省算力，任何显卡可跑；快速平移会有拖影。\n"
                        + "标准 · 块匹配：带运动估计，画面更连贯；独显推荐。\n"
                        + "神经（DirectML）尚未实现，会被明确降级为标准档并在上方状态里如实显示。");

        VBox box = new VBox(10, row, hint);
        return PageKit.settingsCardStacked("质量档位",
                "插值算法决定画面质量与 GPU 占用。", box);
    }

    private Node buildTargetCard() {
        HBox row = new HBox(14, targetSlider, targetLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(targetSlider, Priority.ALWAYS);

        targetSlider.valueProperty().addListener((obs, oldV, newV) -> {
            int v = (int) Math.round(newV.doubleValue() / 30.0) * 30;
            targetLabel.setText(v + " fps");
        });
        targetSlider.setOnMouseReleased(e -> {
            int v = (int) Math.round(targetSlider.getValue() / 30.0) * 30;
            settings.setTargetFps(v);
            manager.sendConfig();
            toast("目标帧率：" + v + " fps");
        });

        return PageKit.settingsCardStacked(
                "目标帧率",
                "输出帧率上限。固定为 2 倍插值——游戏 60 fps 时输出 120 fps。\n"
                        + "注意：输出受显示器刷新率限制。60Hz 显示器即使设 120 也只有 60 帧，"
                        + "界面会如实显示钳制后的实际节奏。",
                row);
    }

    private Node buildWarningCard() {
        Label warn = new Label(
                "⚠ 使用前提\n"
                        + "· Minecraft 必须使用「窗口化」或「窗口化全屏」，独占全屏无法捕获\n"
                        + "· 插值会引入约 1 帧额外延迟，竞技场景建议开启直通\n"
                        + "· 与 Lossless Scaling 同时运行会冲突，请二选一\n"
                        + "· 捕获黄框：Windows 11 已自动隐藏；Windows 10 可能显示，属系统行为\n"
                        + "· 系统需 Windows 10 1903 及以上（Graphics Capture），更低版本不支持\n"
                        + "· 启动器与游戏必须处于同一权限层级（同为管理员或同为普通用户）");
        warn.setWrapText(true);
        warn.getStyleClass().add("settings-card-desc");
        return PageKit.settingsCard("使用说明",
                "以下限制由架构决定，无法通过设置绕过。", warn);
    }

    // -----------------------------------------------------------------
    // 状态刷新
    // -----------------------------------------------------------------

    private void refreshView() {
        boolean running = manager.isRunning();
        enableToggle.setSelected(settings.isEnabled());
        competitiveToggle.setSelected(settings.isCompetitiveMode());

        for (int i = 0; i < qualityButtons.length; i++) {
            if (qualityButtons[i] == null) {
                continue;
            }
            boolean sel = settings.getQuality() == i;
            qualityButtons[i].setStyle(sel
                    ? "-fx-background-color: #3b82f6; -fx-text-fill: white;"
                    : "");
        }
        targetLabel.setText(settings.getTargetFps() + " fps");

        if (!running) {
            backendLabel.setText(manager.isConnected() ? "连接中" : "未运行");
            captureFpsLabel.setText("—");
            presentFpsLabel.setText("—");
            interpMsLabel.setText("—");
            dropLabel.setText("—");
            paceLabel.setText("—");
            modeLabel.setText(FrameGenHostManager.isHostAvailable()
                    ? "host 已就绪，等待游戏启动"
                    : "未找到 framegen-host.exe：请把它放到 "
                      + FrameGenHostManager.hostExePath());
        }
    }

    private void applyStatus(FrameGenHostManager.HostStatus st) {
        if (st == null) {
            return;
        }

        backendLabel.setText(st.captureBackend.isEmpty()
                ? "—" : st.captureBackend.toUpperCase());

        // host 每 500ms 推一次累计帧数，用差分算瞬时 fps
        double captureFps = computeFps(st.captureFrames, true);
        double submitFps = computeFps(st.presentFrames, false);

        captureFpsLabel.setText(captureFps > 0 ? String.format("%.1f", captureFps) : "—");

        // 呈现 FPS 以「host 实际显示到屏幕的次数」为准，而不是提交次数。
        // 60Hz 屏上提交两次不等于显示两帧，用提交数会虚高。
        if (st.shownFrames > 0) {
            presentFpsLabel.setText(String.valueOf(st.shownFrames));
        } else {
            presentFpsLabel.setText(submitFps > 0 ? String.format("%.1f", submitFps) : "—");
        }

        interpMsLabel.setText(st.interpMs > 0 ? String.format("%.2f ms", st.interpMs) : "—");
        dropLabel.setText(String.valueOf(st.dropFrames));

        if (st.displayHz > 0) {
            String pace = String.format("%.0f / %.0f Hz", st.targetHz, st.displayHz);
            if (st.targetHz + 0.5 < st.displayHz) {
                pace = pace + "（已钳制）";
            }
            paceLabel.setText(pace);
        } else {
            paceLabel.setText("—");
        }

        StringBuilder mode = new StringBuilder();
        if (st.closed) {
            mode.append("目标窗口已关闭");
        } else if (!st.presenting) {
            mode.append("未呈现");
        } else if (!st.running) {
            mode.append("直通中（不插值，画面仍在呈现）");
        } else {
            mode.append("插值中");
        }
        mode.append(" · rebuild=").append(st.rebuildCount);
        if (st.lastError != null && !st.lastError.isEmpty()) {
            mode.append(" · ").append(st.lastError);
        }
        modeLabel.setText(mode.toString());
    }

    private double computeFps(long current, boolean capture) {
        long now = System.currentTimeMillis();
        if (lastSampleTimeMs == 0) {
            lastSampleTimeMs = now;
            lastCaptureCount = current;
            lastPresentCount = current;
            return 0;
        }
        long dt = now - lastSampleTimeMs;
        if (dt < 400) {
            return capture ? lastCaptureFps : lastPresentFps;
        }
        long prev = capture ? lastCaptureCount : lastPresentCount;
        double fps = (current - prev) * 1000.0 / dt;

        if (capture) {
            lastCaptureCount = current;
            lastCaptureFps = fps;
        } else {
            lastPresentCount = current;
            lastPresentFps = fps;
        }
        lastSampleTimeMs = now;
        return Math.max(0, fps);
    }

    // -----------------------------------------------------------------
    // 外部入口（由 FrameGenCoordinator 委派调用）
    // -----------------------------------------------------------------

    /** 游戏启动：若设置允许则自动拉起 host */
    public void onGameStarted(int gamePid) {
        if (!settings.isEnabled() || !settings.isAutoStart()) {
            return;
        }
        if (!FrameGenHostManager.isHostAvailable()) {
            System.err.println("[FrameGen] host 不存在，跳过自动启动");
            return;
        }
        manager.start(gamePid);
        refreshView();
    }

    /** 游戏退出：停止 host */
    public void onGameStopped() {
        if (manager.isRunning()) {
            manager.stop();
        }
        refreshView();
    }

    public FrameGenHostManager manager() {
        return manager;
    }

    /** 页面销毁时调用，避免 Timeline 与子进程泄漏 */
    public void dispose() {
        refresh.stop();
        manager.shutdown();
    }

    private int currentGamePid() {
        if (gamePidSupplier == null) {
            return -1;
        }
        try {
            return gamePidSupplier.getAsInt();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void toast(String message) {
        toast(message, false);
    }

    private void toast(String message, boolean error) {
        try {
            host.ui().toast(message, error ? "error" : "info");
        } catch (Throwable t) {
            System.err.println("[FrameGen] " + message);
        }
    }
}
