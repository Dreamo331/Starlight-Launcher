package com.example.starlight.newui.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;

/**
 * 两条进度条的动效：标准款填充上的白色扫光、斜纹款条纹循环滚动。
 *
 * <p>对齐设计稿：
 * <ul>
 *   <li><b>标准进度条</b>：轨道 6px、填充蓝→浅蓝渐变（都在样式表里）；
 *       填充宽度变化 0.4s {@code cubic-bezier(0.4,0,0.2,1)}；
 *       填充上叠一道白色高光从左扫到右，1.6s 一轮无限循环。</li>
 *   <li><b>标准进度条</b>：蓝→浅蓝渐变填充 + 一段白色扫光 1.6s 线性循环。</li>
 * </ul>
 *
 * <h3>JavaFX 侧的落地差异</h3>
 * 没有 {@code repeating-linear-gradient}，也没有可动画的背景图偏移；往 ProgressBar 里塞节点同样做不到
 * （{@code Parent.getChildren()} 是 protected）。所以扫光是「在进度条所在的 {@link Pane} 父容器上
 * 叠一层、裁剪成填充的形状、再平移它」。
 */
public final class ProgressBars {

    /** 扫光：1.6s 一轮、线性、无限循环 */
    private static final Duration SHINE_LOOP = Duration.millis(1600);
    /** 填充宽度变化：0.4s cubic-bezier(0.4,0,0.2,1) */
    private static final Duration WIDTH_EASE = Duration.millis(400);
    private static final Interpolator EVEN = new CubicBezierInterpolator(0.40, 0.0, 0.20, 1.0);
    /** 扫光条宽度 */
    private static final double SHINE_WIDTH = 44;

    private static final String STANDARD = "starlight.bar.standard";
    private static final String KEY = "starlight.progressBars.installed";

    private ProgressBars() {
    }

    /**
     * 在当前场景上装进度条动效。
     *
     * <p>进度条没有交互可以触发「按需准备」，只能自己冒出来就被认领：用低频巡检认领新出现的进度条
     * （进度条数量很少，代价可忽略），页面重建、下载弹窗新建的都能覆盖到。
     */
    public static void install(Node anyNodeInScene) {
        if (anyNodeInScene == null) return;
        if (anyNodeInScene.getScene() != null) {
            sweep(anyNodeInScene.getScene());
        } else {
            anyNodeInScene.sceneProperty().addListener((o, was, scene) -> {
                if (scene != null) sweep(scene);
            });
        }
    }

    private static void sweep(Scene scene) {
        if (Boolean.TRUE.equals(scene.getProperties().get(KEY))) return;
        scene.getProperties().put(KEY, Boolean.TRUE);
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(600), e -> prepareAll(scene)));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();
        Platform.runLater(() -> prepareAll(scene));
    }

    private static void prepareAll(Scene scene) {
        Parent root = scene.getRoot();
        if (root == null) return;
        for (Node n : new ArrayList<>(root.lookupAll(".progress-bar"))) {
            if (n instanceof ProgressBar bar) prepare(bar);
        }
    }

    /** 按样式类准备：标准扫光条 */
    public static void prepare(ProgressBar bar) {
        if (bar == null) return;
        if (Boolean.TRUE.equals(bar.getProperties().get(STANDARD))) return;
        // 先不标记已准备：皮肤还没建好时 attach 会退出，标记了就不会再试，扫光永远挂不上
        if (new Bar(bar).attach()) {
            bar.getProperties().put(STANDARD, Boolean.TRUE);
        }
    }

    /** 单条进度条：填充叠加层 + 宽度补间 */
    private static final class Bar {

        private final ProgressBar bar;
        /** 扫光条 */
        private final Region shine = new Region();
        /** 扫光进度 0→1 的驱动属性（不直接动画节点的位移，避免监听里回写同一属性形成递归） */
        private final SimpleDoubleProperty shinePhase = new SimpleDoubleProperty(0);

        private Pane host;
        private Region fill;
        private double shownProgress = Double.NaN;
        private Timeline widthAnim;

        Bar(ProgressBar bar) {
            this.bar = bar;
        }

        /** @return 是否真正挂上了（没挂上就等下一轮巡检重试） */
        boolean attach() {
            if (!(bar.getParent() instanceof Pane parent)) return false;      // 还没进容器
            host = parent;
            if (!(bar.lookup(".bar") instanceof Region fillRegion)) return false;  // 皮肤还没建好
            fill = fillRegion;

            shine.setManaged(false);
            shine.setMouseTransparent(true);
            shine.setBackground(new Background(new BackgroundFill(
                    new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                            new Stop(0, Color.web("#ffffff", 0)),
                            new Stop(0.5, Color.web("#ffffff", 0.55)),
                            new Stop(1, Color.web("#ffffff", 0))),
                    new CornerRadii(999), Insets.EMPTY)));
            host.getChildren().add(shine);
            shinePhase.addListener((o, a, b) -> layoutShine());
            playLoop(shinePhase, SHINE_LOOP);

            // 填充位置/尺寸一变就重新对齐（进度变化、窗口缩放、布局完成）
            fill.layoutBoundsProperty().addListener((o, a, b) -> sync());
            bar.widthProperty().addListener((o, a, b) -> sync());
            bar.heightProperty().addListener((o, a, b) -> sync());
            bar.progressProperty().addListener((o, a, b) -> tweenTo(b.doubleValue()));
            shownProgress = bar.getProgress();
            sync();
            // 刚显示时填充还没有真实宽度，多同步两拍
            Platform.runLater(this::sync);
            Platform.runLater(() -> Platform.runLater(this::sync));
            return true;
        }

        /** 相位 0→1 的无限循环（线性） */
        private static void playLoop(SimpleDoubleProperty phase, Duration period) {
            Timeline loop = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(phase, 0, Interpolator.LINEAR)),
                    new KeyFrame(period, new KeyValue(phase, 1, Interpolator.LINEAR)));
            loop.setCycleCount(Timeline.INDEFINITE);
            loop.play();
        }

        /**
         * 填充宽度变化 0.4s cubic-bezier(0.4,0,0.2,1)：从<b>当前实际显示值</b>补间到新值。
         *
         * <h3>这里修掉的两个 bug（「进度条不会被填满 / 下载越快越短」的根因）</h3>
         * <ol>
         *   <li><b>{@code Timeline.stop()} 不会触发 {@code setOnFinished}</b>（已实测确认）。
         *       原先用 {@code tweening} 标志 + {@code setOnFinished} 复位：一旦某次进度变化
         *       打断上一段动画，{@code tweening} 就<b>永久</b>留在 {@code true}，
         *       此后监听器里 {@code if (tweening) return;} 把所有进度更新全部丢弃
         *       —— 包括收尾时 {@code finish()} 设的 100%。进度条于是永远停在半路。
         *       现在不再用标志位，改为直接用「当前 progress 值」作为补间起点，天然可重入。</li>
         *   <li><b>补间起点用错</b>：原先从 {@code shownProgress}（目标值）起补，
         *       而实际可见值是上一条动画被打断时的中间值。连续高频回调下（下载每 64KB 一次），
         *       动画永远只跑最前面一小段，显示值严重滞后于真实进度
         *       —— 这就是「下载越快、进度条越短」（快下载回调更密集、动画更没机会跑完）。</li>
         * </ol>
         *
         * <p>判定「进度已到终值」时直接跳到 1.0 并跳过动画，保证收尾一定满格。
         */
        private void tweenTo(double target) {
            if (Double.isNaN(shownProgress)) {
                shownProgress = target;
                return;
            }
            // 目标已到 100%：直接落到终值，不让 0.4s 动画拖住「满格」这个终态
            if (target >= 1.0) {
                if (widthAnim != null) {
                    widthAnim.stop();
                    widthAnim = null;
                }
                shownProgress = 1.0;
                bar.setProgress(1.0);
                return;
            }
            double from = bar.getProgress();      // 当前真实可见值（可能是上一段动画的中间态）
            if (Math.abs(target - from) < 0.001) {
                shownProgress = target;
                return;
            }
            if (widthAnim != null) widthAnim.stop();
            shownProgress = target;
            widthAnim = new Timeline(new KeyFrame(WIDTH_EASE,
                    new KeyValue(bar.progressProperty(), target, EVEN)));
            widthAnim.play();
        }

        /** 把叠加层对齐到填充（.bar）的位置与尺寸 */
        private void sync() {
            if (host == null || fill == null) return;
            // 记下填充的几何（父容器坐标系），两个布局方法都基于它
            Bounds b = host.sceneToLocal(fill.localToScene(fill.getBoundsInLocal()));
            fillX = b.getMinX();
            fillY = b.getMinY();
            fillW = Math.max(0, b.getWidth());
            fillH = Math.max(0, b.getHeight());
            layoutShine();
        }
        private double fillX;
        private double fillY;
        private double fillW;
        private double fillH;

        /** 扫光：x = 相位 ×（填充宽度 + 自身宽度）− 自身宽度，即从左侧外部扫到右侧外部 */
        private void layoutShine() {
            if (host == null) return;
            double h = Math.max(1, fillH);
            boolean visible = fillW > 1;
            shine.setVisible(visible);
            if (!visible) return;
            shine.resizeRelocate(fillX + shinePhase.get() * (fillW + SHINE_WIDTH) - SHINE_WIDTH,
                    fillY, SHINE_WIDTH, h);
            Rectangle clip = new Rectangle(SHINE_WIDTH, h);
            clip.setArcWidth(h);
            clip.setArcHeight(h);
            shine.setClip(clip);
        }
    }
}
