package com.example.starlight.newui.page.settings;


import com.example.starlight.util.I18N;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.framegen.FramegenEngine;
import com.example.starlight.newui.framegen.FramegenNative;
import com.example.starlight.newui.framegen.FramegenStats;
import com.example.starlight.newui.framegen.GameProcessLocator;
import com.example.starlight.newui.framegen.RenderFpsChart;
import com.example.starlight.newui.ui.PageKit;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.IntSupplier;

/**
 * 开发者页的「游戏窗口帧率」卡片。
 *
 * <p>显示的是 <b>渲染出来的帧数</b>（真实帧 + 插值帧），不是显示器刷新的帧数。
 * 三条线：绿色=渲染帧率，蓝色=捕获帧率，橙色虚线=显示器刷新率上限。
 *
 * <p><b>UX 要点</b>：图表最容易「没数据」，原因可能是游戏没开、DLL 没部署、
 * 权限不一致、或者原生侧启动失败。所以这里必须把<b>具体原因</b>写到界面上，
 * 而不是只显示一个空图让人猜。
 *
 * <p><b>修正记录</b>：上一版取 PID 读的是配置键 {@code GamePid/LastGamePid}，
 * 而启动器从不写这些键，导致永远拿不到数据。现在走
 * {@link GameProcessLocator}（GameInstanceRegistry + 进程扫描兜底）。
 */
final class DevFramegenCard {

    /** 采样间隔：100ms，与 RenderFpsChart 的假设一致 */
    private static final Duration SAMPLE = Duration.millis(RenderFpsChart.SAMPLE_INTERVAL_MS);

    private final LauncherContext host;
    private final IntSupplier gamePidSupplier;

    private final RenderFpsChart chart = new RenderFpsChart(820, 200);

    private final Label statusLabel = new Label(I18N.get("fgen.t0001"));
    private final Label renderFpsLabel = new Label("—");
    private final Label captureFpsLabel = new Label("—");
    private final Label interpMsLabel = new Label("—");
    private final Label ratioLabel = new Label("—");
    private final Label displayLabel = new Label("—");
    private final Label detailLabel = new Label("");

    private final Button startButton = new Button(I18N.get("fgen.t0002"));
    private final Button stopButton = new Button(I18N.get("fgen.t0003"));

    private Timeline sampler;

    /** 本次采样绑定的游戏 PID（用于检测游戏是否已退出） */
    private int activePid = -1;

    /** 最近一次启动是否失败（替代「先读状态标签文案再比较」的判断方式） */
    private boolean startFailed;

    DevFramegenCard(LauncherContext host, IntSupplier gamePidSupplier) {
        this.host = host;
        this.gamePidSupplier = gamePidSupplier;
        buildControls();
    }

    Node build() {
        VBox box = new VBox(10);

        HBox metrics = new HBox(26);
        metrics.setAlignment(Pos.CENTER_LEFT);
        metrics.getChildren().addAll(
                metric(I18N.get("fgen.t0004"), renderFpsLabel),
                metric(I18N.get("fgen.t0005"), captureFpsLabel),
                metric(I18N.get("fgen.t0006"), interpMsLabel),
                metric(I18N.get("fgen.t0007"), ratioLabel),
                metric(I18N.get("fgen.t0008"), displayLabel),
                metric(I18N.get("fgen.t0009"), statusLabel));

        HBox legend = new HBox(18);
        legend.setAlignment(Pos.CENTER_LEFT);
        legend.getChildren().addAll(
                legendDot("#3ddc84", I18N.get("fgen.t0010")),
                legendDot("#4a9eff", I18N.get("fgen.t0011")),
                legendDot("#ffb020", I18N.get("fgen.t0012")));

        HBox buttons = new HBox(10, startButton, stopButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        detailLabel.setStyle("-fx-text-fill: #7b8794; -fx-font-size: 11px;");
        detailLabel.setWrapText(true);

        box.getChildren().addAll(metrics, chart, legend, buttons, detailLabel);

        return PageKit.settingsCardStacked(
                I18N.get("fgen.t0013"),
                I18N.get("fgen.t0038"),
                box);
    }

    private VBox metric(String caption, Label value) {
        VBox col = new VBox(3);
        Label cap = new Label(caption);
        cap.getStyleClass().add("settings-card-desc");
        value.getStyleClass().add("settings-card-title");
        value.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");
        col.getChildren().addAll(cap, value);
        return col;
    }

    private HBox legendDot(String color, String text) {
        Label dot = new Label("●");
        dot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
        Label cap = new Label(text);
        cap.getStyleClass().add("settings-card-desc");
        HBox h = new HBox(4, dot, cap);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private void buildControls() {
        startButton.getStyleClass().add("btn-primary");
        stopButton.getStyleClass().add("btn-secondary");
        stopButton.setDisable(true);

        startButton.setOnAction(e -> startSampling());
        stopButton.setOnAction(e -> {
            stopSampling(true);
            startButton.setDisable(false);
        });

        refreshAvailability();
    }

    /** 把「DLL 可用性 + 游戏是否在运行」如实显示出来 */
    private void refreshAvailability() {
        if (!FramegenNative.ensureLoaded()) {
            startButton.setDisable(true);
            statusLabel.setText(I18N.get("fgen.t0014"));
            detailLabel.setText(I18N.get("fgen.p0039", FramegenNative.describeInstallation(), FramegenNative.dllPath().getParent()));
            return;
        }

        startButton.setDisable(false);

        final GameProcessLocator.Target t = GameProcessLocator.locate();
        if (t.isValid()) {
            detailLabel.setText("DLL " + FramegenNative.version()
                    + I18N.get("fgen.r0001", t.description())
                    + I18N.get("fgen.r0002", sourceName(t.source())));
        } else {
            detailLabel.setText("DLL " + FramegenNative.version()
                    + I18N.get("fgen.r0003", t.description()));
        }
    }

    private static String sourceName(GameProcessLocator.Source s) {
        switch (s) {
            case REGISTRY:
                return I18N.get("fgen.t0015");
            case SCAN:
                return I18N.get("fgen.t0016");
            default:
                return I18N.get("fgen.t0017");
        }
    }

    // -----------------------------------------------------------------
    // 采样控制
    // -----------------------------------------------------------------

    private void startSampling() {
        if (sampler != null && sampler.getStatus() == Animation.Status.RUNNING) {
            return;
        }
        if (!FramegenNative.ensureLoaded()) {
            toast(I18N.get("fgen.t0018"));
            return;
        }

        // 优先级：外部注入的 PID > 自动解析
        int pid = resolveGamePid();
        if (pid <= 0) {
            final GameProcessLocator.Target t = GameProcessLocator.locate();
            if (t.isValid()) {
                pid = t.pid();
            } else {
                toast(I18N.get("fgen.k0001", t.description()));
                refreshAvailability();
                return;
            }
        }

        // 已经在跑就别重复启动（游戏内可能已经自动拉起过）
        if (!FramegenEngine.isRunning()) {
            final int rc = FramegenEngine.start(pid, 1, 120, FramegenEngine.Rect.ZERO);
            if (rc != 0) {
                final String err = FramegenEngine.lastError();
                toast(I18N.get("fgen.k0020", rc + "）："
                        + (err.isEmpty() ? I18N.get("fgen.t0019") : err)));
                statusLabel.setText(I18N.get("fgen.t0020"));
                startFailed = true;
                detailLabel.setText(explainStartFailure(rc, err));
                return;
            }
            chart.clear();
        }

        activePid = pid;

        sampler = new Timeline(new KeyFrame(SAMPLE, ev -> sample()));
        sampler.setCycleCount(Animation.INDEFINITE);
        sampler.play();

        startButton.setDisable(true);
        stopButton.setDisable(false);
        statusLabel.setText(I18N.get("fgen.t0021"));
    }

    /** 把原生返回码翻译成人话 */
    private static String explainStartFailure(int rc, String err) {
        final StringBuilder sb = new StringBuilder(I18N.get("fgen.t0022")).append(rc).append("：");
        switch (rc) {
            case -1:
                sb.append(I18N.get("fgen.t0023"))
                        .append(I18N.get("fgen.t0024"))
                        .append(I18N.get("fgen.t0025"))
                        .append(I18N.get("fgen.t0026"));
                break;
            case -2:
                sb.append(I18N.get("fgen.t0027"));
                break;
            case -100:
                sb.append(I18N.get("fgen.t0028"))
                        .append(FramegenNative.dllPath());
                break;
            case -102:
                sb.append(I18N.get("fgen.t0029"));
                break;
            default:
                sb.append(err.isEmpty() ? I18N.get("fgen.t0030") : err);
                break;
        }
        return sb.toString();
    }

    private void stopSampling(boolean stopEngine) {
        // Timeline.stop() 只停未来触发；已经在 FX 事件队列里的那一帧还会跑，
        // 所以先把引用置空，让在途的 sample() 直接返回。
        final Timeline old = sampler;
        sampler = null;
        if (old != null) {
            old.stop();
            old.getKeyFrames().clear();
        }

        if (stopEngine) {
            FramegenEngine.stop();
        }
        activePid = -1;
        startButton.setDisable(false);
        stopButton.setDisable(true);
        // 旧实现用 "启动失败".equals(statusLabel.getText()) 判断，语言一换就恒为 false，
        // 失败态会被覆盖成「已停止」。改用显式状态位，文案只负责显示。
        if (!startFailed) {
            statusLabel.setText(I18N.get("fgen.t0031"));
        }
        startFailed = false;
    }

    /** 每次采样：拉一次原生快照，喂给图表与指标 */
    private void sample() {
        if (sampler == null) {
            return;   // 已被 stopSampling 取消，忽略在途的这一帧
        }

        final FramegenStats st = FramegenEngine.poll();

        renderFpsLabel.setText(String.format("%.1f", st.renderFps));
        captureFpsLabel.setText(String.format("%.1f", st.captureFps));
        interpMsLabel.setText(st.interpMs > 0 ? String.format("%.2f ms", st.interpMs) : "—");
        ratioLabel.setText(String.format("%.0f%%", st.interpolationRatio() * 100.0));
        displayLabel.setText(st.displayHz > 0
                ? String.format("%.0f Hz", st.displayHz) : "—");

        chart.addSample(st.renderFps, st.captureFps, st.displayHz);

        // 目标窗口关掉了
        if (st.closed) {
            statusLabel.setText(I18N.get("fgen.t0032"));
            stopSampling(true);
            refreshAvailability();
            return;
        }

        // 游戏进程退出了（原生侧可能还没报 closed，这里主动兜底）
        if (activePid > 0 && !GameProcessLocator.isAlive(activePid)) {
            statusLabel.setText(I18N.get("fgen.t0033"));
            stopSampling(true);
            refreshAvailability();
            return;
        }

        // 原生侧会话没了（异常退出）
        if (!st.presenting && !st.running && !FramegenEngine.isRunning()) {
            statusLabel.setText(I18N.get("fgen.t0034"));
            stopSampling(false);
            refreshAvailability();
            return;
        }

        if (!st.running && st.presenting) {
            statusLabel.setText(I18N.get("fgen.t0035"));
        } else if (st.running) {
            statusLabel.setText(I18N.get("fgen.t0036"));
        }

        detailLabel.setText(String.format(
                I18N.get("fgen.t0037"),
                st.qualityName(), st.width, st.height,
                st.renderedFrames, st.realFrames, st.interpFrames,
                st.capturedFrames, st.droppedFrames, st.rebuildCount));
    }

    // -----------------------------------------------------------------
    // 外部钩子
    // -----------------------------------------------------------------

    /** 页面被销毁时调用：停采样、摘 Timeline */
    void dispose() {
        final Timeline old = sampler;
        sampler = null;
        if (old != null) {
            old.stop();
            old.getKeyFrames().clear();
        }
    }

    /**
     * 页面重新可见时刷新一次可用性（用户可能刚启动/关闭了游戏）。
     * 没在采样时才会去探测。
     */
    void onShown() {
        if (sampler == null) {
            refreshAvailability();
        }
    }

    // -----------------------------------------------------------------

    private int resolveGamePid() {
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
        try {
            host.ui().toast(message);
        } catch (Throwable t) {
            System.err.println("[FrameGen] " + message);
        }
    }
}
