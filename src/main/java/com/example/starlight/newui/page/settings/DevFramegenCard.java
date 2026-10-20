package com.example.starlight.newui.page.settings;

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

    private final Label statusLabel = new Label("未启动");
    private final Label renderFpsLabel = new Label("—");
    private final Label captureFpsLabel = new Label("—");
    private final Label interpMsLabel = new Label("—");
    private final Label ratioLabel = new Label("—");
    private final Label displayLabel = new Label("—");
    private final Label detailLabel = new Label("");

    private final Button startButton = new Button("开始采样");
    private final Button stopButton = new Button("停止");

    private Timeline sampler;

    /** 本次采样绑定的游戏 PID（用于检测游戏是否已退出） */
    private int activePid = -1;

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
                metric("渲染帧率", renderFpsLabel),
                metric("捕获帧率", captureFpsLabel),
                metric("插值耗时", interpMsLabel),
                metric("插值帧占比", ratioLabel),
                metric("显示上限", displayLabel),
                metric("状态", statusLabel));

        HBox legend = new HBox(18);
        legend.setAlignment(Pos.CENTER_LEFT);
        legend.getChildren().addAll(
                legendDot("#3ddc84", "渲染帧率（真实帧 + 插值帧）"),
                legendDot("#4a9eff", "捕获帧率"),
                legendDot("#ffb020", "显示器刷新率上限"));

        HBox buttons = new HBox(10, startButton, stopButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        detailLabel.setStyle("-fx-text-fill: #7b8794; -fx-font-size: 11px;");
        detailLabel.setWrapText(true);

        box.getChildren().addAll(metrics, chart, legend, buttons, detailLabel);

        return PageKit.settingsCardStacked(
                "游戏窗口帧率",
                "渲染帧数（不是显示器显示帧数）。绿色曲线是我们实际生成并提交呈现的帧数，"
                        + "包含真实帧与插值帧；橙色虚线是显示器刷新率上限——"
                        + "绿色高于虚线说明多渲染的帧屏幕吃不下，属于纯浪费。",
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
            statusLabel.setText("DLL 不可用");
            detailLabel.setText("framegen.dll 加载失败：" + FramegenNative.describeInstallation()
                    + "\n请把 build\\framegen.dll 复制到 "
                    + FramegenNative.dllPath().getParent());
            return;
        }

        startButton.setDisable(false);

        final GameProcessLocator.Target t = GameProcessLocator.locate();
        if (t.isValid()) {
            detailLabel.setText("DLL " + FramegenNative.version()
                    + " · 目标：" + t.description()
                    + " · 来源：" + sourceName(t.source()));
        } else {
            detailLabel.setText("DLL " + FramegenNative.version()
                    + " 就绪，但没找到 Minecraft 进程（" + t.description() + "）"
                    + "\n请先启动游戏（窗口化），再点「开始采样」。");
        }
    }

    private static String sourceName(GameProcessLocator.Source s) {
        switch (s) {
            case REGISTRY:
                return "启动器实例注册表";
            case SCAN:
                return "进程扫描";
            default:
                return "无";
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
            toast("framegen.dll 不可用");
            return;
        }

        // 优先级：外部注入的 PID > 自动解析
        int pid = resolveGamePid();
        if (pid <= 0) {
            final GameProcessLocator.Target t = GameProcessLocator.locate();
            if (t.isValid()) {
                pid = t.pid();
            } else {
                toast("没找到 Minecraft 进程：" + t.description());
                refreshAvailability();
                return;
            }
        }

        // 已经在跑就别重复启动（游戏内可能已经自动拉起过）
        if (!FramegenEngine.isRunning()) {
            final int rc = FramegenEngine.start(pid, 1, 120, FramegenEngine.Rect.ZERO);
            if (rc != 0) {
                final String err = FramegenEngine.lastError();
                toast("启动帧插值失败（rc=" + rc + "）："
                        + (err.isEmpty() ? "见 framegen-dll.log" : err));
                statusLabel.setText("启动失败");
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
        statusLabel.setText("采样中");
    }

    /** 把原生返回码翻译成人话 */
    private static String explainStartFailure(int rc, String err) {
        final StringBuilder sb = new StringBuilder("启动失败 rc=").append(rc).append("：");
        switch (rc) {
            case -1:
                sb.append("原生侧初始化失败。常见原因：\n")
                        .append("· 目标不是窗口化的 Minecraft（独占全屏无法捕获）\n")
                        .append("· 启动器与游戏权限层级不一致（同为管理员或同为普通用户）\n")
                        .append("· 系统低于 Windows 10 1903（不支持 Graphics Capture）");
                break;
            case -2:
                sb.append("初始化超时（15 秒）。捕获源可能卡住了，重试一次通常就好。");
                break;
            case -100:
                sb.append("framegen.dll 未加载。检查文件是否在 ")
                        .append(FramegenNative.dllPath());
                break;
            case -102:
                sb.append("PID 无效。");
                break;
            default:
                sb.append(err.isEmpty() ? "未知原因，见 framegen-dll.log" : err);
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
        if (!"启动失败".equals(statusLabel.getText())) {
            statusLabel.setText("已停止");
        }
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
            statusLabel.setText("目标窗口已关闭");
            stopSampling(true);
            refreshAvailability();
            return;
        }

        // 游戏进程退出了（原生侧可能还没报 closed，这里主动兜底）
        if (activePid > 0 && !GameProcessLocator.isAlive(activePid)) {
            statusLabel.setText("游戏已退出");
            stopSampling(true);
            refreshAvailability();
            return;
        }

        // 原生侧会话没了（异常退出）
        if (!st.presenting && !st.running && !FramegenEngine.isRunning()) {
            statusLabel.setText("已结束");
            stopSampling(false);
            refreshAvailability();
            return;
        }

        if (!st.running && st.presenting) {
            statusLabel.setText("直通中");
        } else if (st.running) {
            statusLabel.setText("插值中");
        }

        detailLabel.setText(String.format(
                "%s · %dx%d · 已渲染 %,d 帧（真实 %,d / 插值 %,d）· 捕获 %,d · 丢弃 %d · 重建 %d",
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
