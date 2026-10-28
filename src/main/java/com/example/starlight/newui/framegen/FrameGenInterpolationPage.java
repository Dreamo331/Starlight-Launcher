package com.example.starlight.newui.framegen;


import com.example.starlight.util.I18N;
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

    private final Label backendLabel = new Label(I18N.get("framegen.t0053"));
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
                err -> Platform.runLater(() -> toast(I18N.get("framegen.p0140", err), true)));

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
        VBox root = PageKit.settingsPage(host, I18N.get("framegen.t0054"));

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
                metricColumn(I18N.get("framegen.t0101"), backendLabel),
                metricColumn(I18N.get("framegen.t0102"), captureFpsLabel),
                metricColumn(I18N.get("framegen.t0103"), presentFpsLabel),
                metricColumn(I18N.get("framegen.t0104"), interpMsLabel),
                metricColumn(I18N.get("framegen.t0105"), dropLabel),
                metricColumn(I18N.get("framegen.t0106"), paceLabel));

        Pane statusHost = new VBox(10, row, modeLabel);
        modeLabel.getStyleClass().add("settings-card-desc");
        return PageKit.settingsCardStacked(
                I18N.get("framegen.t0107"),
                I18N.get("framegen.t0108"),
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
        HBox enableRow = new HBox(14, enableToggle, new Label(I18N.get("framegen.t0055")));
        enableRow.setAlignment(Pos.CENTER_LEFT);
        enableToggle.setOnAction(e -> {
            boolean on = enableToggle.isSelected();
            settings.setEnabled(on);
            if (on) {
                int pid = currentGamePid();
                if (pid > 0) {
                    if (!FrameGenHostManager.isHostAvailable()) {
                        toast(I18N.get("framegen.t0056"), true);
                        enableToggle.setSelected(false);
                        settings.setEnabled(false);
                    } else if (manager.start(pid)) {
                        toast(I18N.get("framegen.t0057"));
                    }
                } else {
                    toast(I18N.get("framegen.t0058"), true);
                    enableToggle.setSelected(false);
                    settings.setEnabled(false);
                }
            } else {
                manager.stop();
                toast(I18N.get("framegen.t0059"));
            }
            refreshView();
        });

        HBox compRow = new HBox(14, competitiveToggle,
                new Label(I18N.get("framegen.t0060")));
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
            toast(on ? I18N.get("framegen.t0109") : I18N.get("framegen.t0110"));
            refreshView();
        });

        VBox box = new VBox(12, enableRow, compRow);
        return PageKit.settingsCardStacked(
                I18N.get("framegen.t0111"),
                I18N.get("framegen.t0112"),
                box);
    }

    private Node buildQualityCard() {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);

        String[] names = {I18N.get("framegen.t0113"), I18N.get("framegen.t0114")};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = new Button(names[i]);
            b.getStyleClass().add("btn-primary");
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
            b.setOnAction(e -> {
                settings.setQuality(idx);
                manager.sendConfig();
                toast(I18N.get("framegen.p0141", names[idx]));
                refreshView();
            });
            qualityButtons[i] = b;
            row.getChildren().add(b);
        }

        Label hint = PageKit.hintLabel(
                I18N.get("framegen.t0089"));

        VBox box = new VBox(10, row, hint);
        return PageKit.settingsCardStacked(I18N.get("framegen.t0115"),
                I18N.get("framegen.t0116"), box);
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
            toast(I18N.get("framegen.p0142", v + " fps"));
        });

        return PageKit.settingsCardStacked(
                I18N.get("framegen.t0117"),
                I18N.get("framegen.t0118"),
                row);
    }

    private Node buildWarningCard() {
        Label warn = new Label(
                I18N.get("framegen.t0090"));
        warn.setWrapText(true);
        warn.getStyleClass().add("settings-card-desc");
        return PageKit.settingsCard(I18N.get("framegen.t0119"),
                I18N.get("framegen.t0120"), warn);
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
            backendLabel.setText(manager.isConnected() ? I18N.get("framegen.t0121") : I18N.get("framegen.t0122"));
            captureFpsLabel.setText("—");
            presentFpsLabel.setText("—");
            interpMsLabel.setText("—");
            dropLabel.setText("—");
            paceLabel.setText("—");
            modeLabel.setText(FrameGenHostManager.isHostAvailable()
                    ? I18N.get("framegen.t0123")
                    : I18N.get("framegen.p0143", FrameGenHostManager.hostExePath()));
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
                pace = pace + I18N.get("fgip.r0001");
            }
            paceLabel.setText(pace);
        } else {
            paceLabel.setText("—");
        }

        StringBuilder mode = new StringBuilder();
        if (st.closed) {
            mode.append(I18N.get("framegen.t0061"));
        } else if (!st.presenting) {
            mode.append(I18N.get("framegen.t0062"));
        } else if (!st.running) {
            mode.append(I18N.get("framegen.t0063"));
        } else {
            mode.append(I18N.get("framegen.t0064"));
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
