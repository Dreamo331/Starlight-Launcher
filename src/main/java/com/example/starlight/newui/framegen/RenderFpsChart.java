package com.example.starlight.newui.framegen;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 渲染帧率实时曲线。
 *
 * <p><b>为什么手写 Canvas 而不用 JavaFX 的 LineChart</b>：
 * <ul>
 *   <li>LineChart 每个数据点都是一个对象，按 10Hz 采样跑一整天会持续制造垃圾；
 *       这里只保留定长的 int 环形缓冲，零对象分配。</li>
 *   <li>需要绘制「显示器刷新率上限」参考线与悬停读数，LineChart 要自定义
 *       Node + Tooltip 才能做到，反而更重。</li>
 *   <li>帧率图的视觉诉求很简单（滚动折线 + 一条参考线），Canvas 直接画更可控。</li>
 * </ul>
 *
 * <p><b>显示的是「渲染帧数」而不是「显示器显示帧数」</b>：这正是需求点。
 * 同时用一条虚线标出显示器刷新率，用来直观看出「渲染出来了但屏幕吃不下」。
 */
public final class RenderFpsChart extends Pane {

    /** 采样窗口：保留最近 N 个点 */
    private static final int CAPACITY = 240;

    /** 采样间隔（毫秒）。10Hz 足够看清抖动，又不会让 UI 线程太忙 */
    public static final long SAMPLE_INTERVAL_MS = 100;

    private final Canvas canvas = new Canvas();
    private final Deque<Double> renderSamples = new ArrayDeque<>(CAPACITY);
    private final Deque<Double> captureSamples = new ArrayDeque<>(CAPACITY);

    private double displayHz = 0.0;
    private double peakSeen = 60.0;

    // 配色：跟随启动器深色主题
    private static final Color BG = Color.web("#151a21");
    private static final Color GRID = Color.web("#2a323d");
    private static final Color AXIS_TEXT = Color.web("#7b8794");
    private static final Color RENDER_LINE = Color.web("#3ddc84");
    private static final Color CAPTURE_LINE = Color.web("#4a9eff");
    private static final Color LIMIT_LINE = Color.web("#ffb020");
    private static final Color FILL_TOP = Color.web("#3ddc84", 0.28);
    private static final Color FILL_BOTTOM = Color.web("#3ddc84", 0.02);

    public RenderFpsChart(double prefWidth, double prefHeight) {
        setPrefSize(prefWidth, prefHeight);
        setMinHeight(140);
        getChildren().add(canvas);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        // 尺寸变化时重绘（Canvas 清屏后必须重画）
        canvas.widthProperty().addListener((o, a, b) -> redraw());
        canvas.heightProperty().addListener((o, a, b) -> redraw());

        setOnMouseExited(e -> redraw());
        redraw();
    }

    /** 清空历史（重启插值时调用，避免上一轮的曲线混进来） */
    public void clear() {
        renderSamples.clear();
        captureSamples.clear();
        peakSeen = 60.0;
        redraw();
    }

    /**
     * 追加一次采样。
     *
     * @param renderFps  渲染帧率（真实帧 + 插值帧）
     * @param captureFps 捕获帧率
     * @param displayHz  当前显示器刷新率，用于画上限参考线
     */
    public void addSample(double renderFps, double captureFps, double displayHz) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> addSample(renderFps, captureFps, displayHz));
            return;
        }

        this.displayHz = displayHz;

        if (renderSamples.size() >= CAPACITY) {
            renderSamples.removeFirst();
        }
        if (captureSamples.size() >= CAPACITY) {
            captureSamples.removeFirst();
        }
        renderSamples.addLast(Math.max(0.0, renderFps));
        captureSamples.addLast(Math.max(0.0, captureFps));

        // 纵轴上限跟着实际峰值走，但至少留 60 的余量
        final double observed = Math.max(renderFps, Math.max(captureFps, displayHz));
        peakSeen = Math.max(peakSeen * 0.995, Math.max(60.0, observed));

        redraw();
    }

    // -----------------------------------------------------------------
    // 绘制
    // -----------------------------------------------------------------

    private void redraw() {
        final double w = canvas.getWidth();
        final double h = canvas.getHeight();
        if (w <= 1 || h <= 1) {
            return;
        }

        final GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        // 背景
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        // 内边距：左侧留给纵轴刻度，底部留给时间轴
        final double padLeft = 42;
        final double padRight = 10;
        final double padTop = 10;
        final double padBottom = 20;
        final double plotW = w - padLeft - padRight;
        final double plotH = h - padTop - padBottom;
        if (plotW <= 4 || plotH <= 4) {
            return;
        }

        // 纵轴上限：向上取整到 30 的倍数，读数更好看
        final double yMax = Math.max(60.0, Math.ceil(peakSeen / 30.0) * 30.0);

        drawGrid(g, padLeft, padTop, plotW, plotH, yMax);

        // 显示器刷新率上限参考线
        if (displayHz > 0.5 && displayHz <= yMax) {
            final double y = padTop + plotH * (1.0 - displayHz / yMax);
            g.setStroke(LIMIT_LINE);
            g.setLineWidth(1.2);
            g.setLineDashes(6, 4);
            g.strokeLine(padLeft, y, padLeft + plotW, y);
            g.setLineDashes(null);

            g.setFill(LIMIT_LINE);
            g.setFont(Font.font(10));
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(String.format("屏幕 %.0f Hz", displayHz),
                    padLeft + plotW - 4, y - 3);
        }

        // 曲线：先画捕获（在下层），再画渲染（在上层）
        drawSeries(g, captureSamples, CAPTURE_LINE, null, padLeft, padTop, plotW, plotH, yMax);
        drawSeries(g, renderSamples, RENDER_LINE, new Color[]{FILL_TOP, FILL_BOTTOM},
                padLeft, padTop, plotW, plotH, yMax);

        // 右上角当前读数
        drawCurrentReading(g, padLeft, padTop, plotW);

        // 空数据提示
        if (renderSamples.isEmpty()) {
            g.setFill(AXIS_TEXT);
            g.setFont(Font.font(12));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("等待数据…（启动帧插值后开始采样）",
                    padLeft + plotW / 2, padTop + plotH / 2);
        }
    }

    private void drawGrid(GraphicsContext g, double x, double y,
                          double w, double h, double yMax) {
        g.setFont(Font.font(10));
        g.setTextAlign(TextAlignment.RIGHT);

        // 横网格线 + 纵轴刻度：4 等分
        final int steps = 4;
        for (int i = 0; i <= steps; i++) {
            final double value = yMax * i / steps;
            final double gy = y + h * (1.0 - (double) i / steps);

            g.setStroke(GRID);
            g.setLineWidth(1);
            g.strokeLine(x, gy, x + w, gy);

            g.setFill(AXIS_TEXT);
            g.fillText(String.format("%.0f", value), x - 6, gy + 3.5);
        }

        // 时间轴：240 点 × 100ms ≈ 24 秒
        g.setTextAlign(TextAlignment.CENTER);
        final double spanSec = CAPACITY * SAMPLE_INTERVAL_MS / 1000.0;
        for (int i = 0; i <= 4; i++) {
            final double gx = x + w * i / 4.0;
            final double sec = spanSec * (1.0 - (double) i / 4.0);
            g.setFill(AXIS_TEXT);
            g.fillText(sec <= 0.1 ? "现在" : String.format("-%.0fs", sec),
                    gx, y + h + 14);
        }
    }

    private void drawSeries(GraphicsContext g, Deque<Double> data, Color stroke,
                            Color[] fill, double x, double y, double w, double h,
                            double yMax) {
        final int n = data.size();
        if (n < 2) {
            return;
        }

        final List<Double> pts = new ArrayList<>(n);
        pts.addAll(data);

        final double stepX = w / (CAPACITY - 1);
        // 右对齐：最新的点贴在最右边
        final double startX = x + w - (n - 1) * stepX;

        final double[] xs = new double[n];
        final double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = startX + i * stepX;
            final double v = Math.min(Math.max(0.0, pts.get(i)), yMax);
            ys[i] = y + h * (1.0 - v / yMax);
        }

        // 渐变填充
        if (fill != null && n >= 2) {
            g.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true,
                    javafx.scene.paint.CycleMethod.NO_CYCLE,
                    new javafx.scene.paint.Stop(0, fill[0]),
                    new javafx.scene.paint.Stop(1, fill[1])));
            g.beginPath();
            g.moveTo(xs[0], y + h);
            for (int i = 0; i < n; i++) {
                g.lineTo(xs[i], ys[i]);
            }
            g.lineTo(xs[n - 1], y + h);
            g.closePath();
            g.fill();
        }

        // 折线
        g.setStroke(stroke);
        g.setLineWidth(1.6);
        g.setLineDashes(null);
        g.beginPath();
        g.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            g.lineTo(xs[i], ys[i]);
        }
        g.stroke();
    }

    private void drawCurrentReading(GraphicsContext g, double x, double y, double w) {
        if (renderSamples.isEmpty()) {
            return;
        }
        final double cur = renderSamples.peekLast();
        final double cap = captureSamples.isEmpty() ? 0 : captureSamples.peekLast();

        g.setFont(Font.font(13));
        g.setTextAlign(TextAlignment.RIGHT);

        g.setFill(RENDER_LINE);
        g.fillText(String.format("渲染 %.0f fps", cur), x + w, y + 14);

        g.setFont(Font.font(10));
        g.setFill(CAPTURE_LINE);
        g.fillText(String.format("捕获 %.0f fps", cap), x + w, y + 28);

        if (displayHz > 0.5) {
            final double over = cur / displayHz;
            g.setFill(over > 1.05 ? LIMIT_LINE : AXIS_TEXT);
            g.fillText(String.format("%.2f× 屏幕", over), x + w, y + 42);
        }
    }
}
