package com.example.starlight.newui.ui;

import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.newui.LauncherContext;

import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏启动动画弹窗（集成式：随 rootPane 弹出、覆盖全窗口）。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）：
 * 面板构建、显示 / 隐藏 / 取消、进度推进与成功转场都在本类；
 * 主壳 {@code launchGame()} 只保留异步回调，并调用本类方法驱动动画。
 */
public final class LaunchOverlay {

    private final LauncherContext host;

    // ==================== 游戏启动动画组件 ====================
    private VBox launchPanel;                    // 启动动画弹窗内容面板（集成式弹窗展示）
    private StackPane launchModalOverlayRef;      // 启动动画对应的弹窗 overlay（安全关闭时校验）
    private Label launchStatusLabel;
    private Region launchProgressBar;
    private StackPane spinnerContainer;
    private Node cube;
    private Label successCheck;
    private Label launchTitleLabel;
    private Label launchVersionLabel;
    private Button cancelLaunchBtn;
    private Timeline launchTimeline;
    private Timeline progressBarAnim;
    /** 玫瑰线动画帧驱动（show 时恢复、hide 时停止） */
    private AnimationTimer roseTimer;

    // ==================== 玫瑰线加载动画规格常量（硬编码，勿调） ====================
    /** 脉冲周期 ms：细节缩放 s 波动一次的时长 */
    private static final double ROSE_PULSE_T = 5800;
    /** 旋转周期 ms：整体旋转一圈的时长（顺时针） */
    private static final double ROSE_SPIN_T = 17000;
    /** 曲线周期 ms：粒子沿玫瑰线绕行一圈的时长 */
    private static final double ROSE_CURVE_T = 6100;
    /** 曲线采样段数（j = 0..480 共 481 个点） */
    private static final int ROSE_SAMPLES = 480;
    /** 粒子数量 */
    private static final int ROSE_PARTICLES = 31;
    /** 相邻粒子的拖尾相位间隔 */
    private static final double ROSE_TAIL_GAP = 0.13;
    /** 玫瑰线坐标放大倍数（100×100 视口内） */
    private static final double ROSE_SCALE = 3.25;

    /** 曲线折线起点（每帧改写坐标） */
    private MoveTo roseMoveTo;
    /** 曲线折线的 480 个线段（每帧改写坐标，无逐帧分配） */
    private List<LineTo> roseSegments;
    /** 31 个拖尾粒子（半径/透明度建时已定，每帧只改圆心） */
    private Circle[] roseParticles;
    private boolean isLaunching = false;
    private volatile boolean launchCancelledByUser = false; // 用户主动取消标记：拦截后台线程误报的 onError/onSuccess 回调

    public LaunchOverlay(LauncherContext host) {
        this.host = host;
    }

    public void build() {
        // 启动动画改为集成式弹窗：launchPanel 作为弹窗卡片内容，样式随 modal-card 主题切换
        launchPanel = new VBox(16);
        launchPanel.setAlignment(Pos.CENTER);
        launchPanel.setPadding(new Insets(6, 24, 18, 24));

        // 玫瑰线 + 粒子拖尾加载动画（替换原三个旋转方块）
        spinnerContainer = new StackPane();
        spinnerContainer.setAlignment(Pos.CENTER);

        Node roseNode = buildRoseSpinner();
        cube = roseNode;
        spinnerContainer.getChildren().add(cube);

        // 成功图标
        successCheck = new Label();
        successCheck.setGraphic(AppIcons.icon("check", 34, Color.WHITE));
        successCheck.setAlignment(Pos.CENTER);
        successCheck.setStyle(
                "-fx-font-size: 32px; -fx-text-fill: white;" +
                        "-fx-min-width: 64px; -fx-min-height: 64px;" +
                        "-fx-max-width: 64px; -fx-max-height: 64px;" +
                        "-fx-background-radius: 999;" +
                        "-fx-background-color: #22c55e;" +
                        "-fx-alignment: center;"
        );
        successCheck.setVisible(false);
        successCheck.setScaleX(0);
        successCheck.setScaleY(0);

        StackPane iconContainer = new StackPane();
        iconContainer.setPrefSize(64, 64);
        iconContainer.setAlignment(Pos.CENTER);
        iconContainer.getChildren().addAll(spinnerContainer, successCheck);

        // 标题（随状态切换：正在启动游戏 → 游戏已启动）
        launchTitleLabel = new Label("正在启动游戏");
        launchTitleLabel.getStyleClass().add("modal-text-title");
        launchTitleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: 700;");

        // 版本信息
        launchVersionLabel = new Label("版本信息加载中...");
        launchVersionLabel.getStyleClass().add("modal-hint");

        // 进度条：HBox 容器背景作为轨道，子 Region 宽度由 prefWidth 控制
        // （注意：StackPane 会拉伸子节点导致 prefWidth 失效，必须用 HBox）
        HBox progressPane = new HBox();
        progressPane.setPrefSize(320, 4);
        progressPane.setMinWidth(320);
        progressPane.setMaxWidth(320);
        progressPane.getStyleClass().add("launch-progress-track");
        progressPane.setAlignment(Pos.CENTER_LEFT);

        launchProgressBar = new Region();
        launchProgressBar.setPrefSize(0, 4);
        launchProgressBar.setMaxHeight(4);
        launchProgressBar.setMaxWidth(320);
        launchProgressBar.getStyleClass().add("launch-progress-fill");

        progressPane.getChildren().add(launchProgressBar);

        // 状态文字
        launchStatusLabel = new Label("准备中...");
        launchStatusLabel.getStyleClass().add("modal-hint");
        launchStatusLabel.setMinHeight(20);
        launchStatusLabel.setAlignment(Pos.CENTER);

        // 取消按钮（与弹窗按钮风格一致）
        cancelLaunchBtn = AppIcons.button("close", "取消启动");
        cancelLaunchBtn.getStyleClass().add("modal-btn-cancel");
        cancelLaunchBtn.setMinWidth(120);
        cancelLaunchBtn.setOnAction(e -> cancel());

        launchPanel.getChildren().addAll(
                iconContainer,
                launchTitleLabel,
                launchVersionLabel,
                progressPane,
                launchStatusLabel,
                cancelLaunchBtn
        );
    }

    /**
     * 构建玫瑰线 + 粒子拖尾加载动画（替换原三个旋转方块）。
     *
     * <p>规格：脉冲细节缩放 s（周期 5800ms，相位 +0.55）、整体旋转 -360°/17s、
     * 粒子沿五瓣玫瑰线绕行 6100ms；曲线为 481 点采样折线（线宽 4.5、透明度 0.1、圆头），
     * 31 个拖尾粒子按 fade^0.56 衰减半径与透明度。全部节点预建，
     * 动画循环每帧只改坐标/角度，无逐帧节点分配。
     */
    private Node buildRoseSpinner() {
        // 固定 100×100 设计画布：容器尺寸不随粒子移动变化，StackPane 居中稳定不抖动
        Pane holder = new Pane();
        holder.setPrefSize(100, 100);
        holder.setMinSize(100, 100);
        holder.setMaxSize(100, 100);

        Color fg = Color.rgb(2, 112, 225);   // 前景色：与原三方块同色系

        // ---- 曲线：481 点玫瑰线折线（预建节点，每帧只改坐标） ----
        Path curve = new Path();
        curve.setStroke(fg);
        curve.setStrokeWidth(4.5);
        curve.setStrokeLineCap(StrokeLineCap.ROUND);
        curve.setOpacity(0.1);
        roseMoveTo = new MoveTo();
        curve.getElements().add(roseMoveTo);
        roseSegments = new ArrayList<>(ROSE_SAMPLES);
        for (int j = 0; j < ROSE_SAMPLES; j++) {
            LineTo seg = new LineTo();
            roseSegments.add(seg);
            curve.getElements().add(seg);
        }

        // ---- 粒子拖尾：31 个圆，半径/透明度建时已定（fade 只依赖尾距），每帧只改圆心 ----
        Circle[] particles = new Circle[ROSE_PARTICLES];
        double[] tailOffsets = new double[ROSE_PARTICLES];
        for (int i = 0; i < ROSE_PARTICLES; i++) {
            double tailOffset = i / (double) (ROSE_PARTICLES - 1);
            tailOffsets[i] = tailOffset;
            double fade = Math.pow(1 - tailOffset, 0.56);
            Circle p = new Circle(0.9 + 2.7 * fade);
            p.setFill(fg);
            p.setOpacity(0.04 + 0.96 * fade);
            particles[i] = p;
        }
        roseParticles = particles;

        Group canvas = new Group(curve);
        canvas.getChildren().addAll(particles);
        // 整体旋转（-360°/17s 顺时针）以设计中心 (50,50) 为轴
        Rotate spin = new Rotate(0, 50, 50);
        canvas.getTransforms().add(spin);

        // 公式文本（等宽字体，弱化呈现）
        javafx.scene.text.Text formula = new javafx.scene.text.Text("");
        formula.setFill(Color.web("#8b8ea5"));
        formula.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 5px;");
        formula.setX(1);
        formula.setY(99);
        formula.setOpacity(0.5);


        holder.getChildren().addAll(canvas, formula);
        // 图标槽 64×64，100×100 画布按 0.9 缩放居中（StackPane 不裁剪，轻微溢出与原方块行为一致）
        holder.setScaleX(0.9);
        holder.setScaleY(0.9);


        // ---- 动画循环：逐帧更新曲线、粒子和旋转 ----
        final Rotate spinRef = spin;
        roseTimer = new AnimationTimer() {
            private final long startNanos = System.nanoTime();

            @Override
            public void handle(long now) {
                double tMs = (now - startNanos) / 1_000_000.0;

                // 1. 脉冲细节缩放 s = 0.52 + 0.48 × (1 + sin(2π·phase + 0.55)) / 2
                double s = 0.52 + 0.48
                        * (1 + Math.sin(2 * Math.PI * ((tMs % ROSE_PULSE_T) / ROSE_PULSE_T) + 0.55)) / 2;

                // 2. 整体旋转角 R = -(t mod 17000)/17000 × 360
                spinRef.setAngle(-((tMs % ROSE_SPIN_T) / ROSE_SPIN_T) * 360);

                // 3. 曲线进度 u = (t mod 6100)/6100
                double u = (tMs % ROSE_CURVE_T) / ROSE_CURVE_T;

                // 曲线：481 点采样重算（s 每帧变，半径与幅度同步脉冲）
                double curveAmp = (9.2 + 0.6 * s) * (0.72 + 0.28 * s) * ROSE_SCALE;
                for (int j = 0; j <= ROSE_SAMPLES; j++) {
                    double tj = 2 * Math.PI * (j / (double) ROSE_SAMPLES);
                    double r = curveAmp * Math.cos(5 * tj);
                    double x = 50 + Math.cos(tj) * r;
                    double y = 50 + Math.sin(tj) * r;
                    if (j == 0) {
                        roseMoveTo.setX(x);
                        roseMoveTo.setY(y);
                    } else {
                        LineTo seg = roseSegments.get(j - 1);
                        seg.setX(x);
                        seg.setY(y);
                    }
                }

                // 粒子：u_i = normalize(u − tailOffset_i × 0.13)，同一条玫瑰线
                for (int i = 0; i < ROSE_PARTICLES; i++) {
                    double ti = 2 * Math.PI * normalize(u - tailOffsets[i] * ROSE_TAIL_GAP);
                    double ri = curveAmp * Math.cos(5 * ti);
                    Circle p = roseParticles[i];
                    p.setCenterX(50 + Math.cos(ti) * ri);
                    p.setCenterY(50 + Math.sin(ti) * ri);
                }
            }
        };
        // 不在构造期 start：show() 启动、hide()/markSuccess() 停止，避免弹窗未显示时空转
        return holder;
    }

    /** 归一化到 [0,1)：((p mod 1) + 1) mod 1 */
    private static double normalize(double p) {
        double m = p % 1.0;
        return m < 0 ? m + 1.0 : m;
    }

    public void show(String versionInfo) {
        if (isLaunching) return;
        isLaunching = true;

        if (launchVersionLabel != null) {
            launchVersionLabel.setText(versionInfo);
        }

        if (progressBarAnim != null) {
            progressBarAnim.stop();
            progressBarAnim = null;
        }
        launchProgressBar.setPrefWidth(0);
        launchStatusLabel.setText("准备中...");
        spinnerContainer.setVisible(true);
        successCheck.setVisible(false);
        successCheck.setScaleX(0);
        successCheck.setScaleY(0);
        launchTitleLabel.setText("正在启动游戏");
        cancelLaunchBtn.setVisible(true);
        cancelLaunchBtn.setDisable(false);

        // 上次 hide() 停了动画循环：重新显示弹窗时恢复玫瑰线动画
        if (roseTimer != null) {
            roseTimer.start();
        }

        // 集成式弹窗展示（与登录弹窗同款卡片样式；无关闭按钮，只能通过「取消启动」关闭）
        host.ui().modal("", launchPanel, 400, 350, false);
        launchModalOverlayRef = host.ui().currentModalOverlay();

        // 不再创建假进度Timeline，真实进度由 launchGame 中的 launcMinecraftAsync 回调驱动
        launchTimeline = null;
    }

    public void hide() {
        // 停止玫瑰线动画与进度动画（弹窗关闭后节点脱离场景，动画需回收）
        if (roseTimer != null) roseTimer.stop();
        if (progressBarAnim != null) {
            progressBarAnim.stop();
            progressBarAnim = null;
        }
        if (launchTimeline != null) launchTimeline.stop();

        // 仅当当前弹窗仍是启动动画弹窗时才关闭：
        // 异常退出场景下，崩溃诊断面板会先打开并替换 modalOverlay，此时不能误关新弹窗
        if (launchModalOverlayRef != null && host.ui().currentModalOverlay() == launchModalOverlayRef) {
            host.ui().closeModal();
        }
        launchModalOverlayRef = null;
        isLaunching = false;
    }

    public void cancel() {
        if (!isLaunching) return;

        // 标记用户主动取消：后台启动线程仍在跑，后续 onError/onSuccess 回调不再展示任何提示
        launchCancelledByUser = true;

        // 停止假进度动画
        if (launchTimeline != null) {
            launchTimeline.stop();
        }
        if (progressBarAnim != null) {
            progressBarAnim.stop();
            progressBarAnim = null;
        }

        // 实际取消游戏启动进程：只杀「正在启动的这一个」——
        // 多实例下用户可能已经开着另一个游戏在玩，不能一并关掉
        UIGeneralControlClass.stopLaunchingInstance();

        isLaunching = false;
        launchStatusLabel.setText("已取消");

        Timeline cancelTimer = new Timeline(new KeyFrame(Duration.millis(400), e -> {
            hide();
        }));
        cancelTimer.play();
    }

    /** 启动进度回调：平滑推进进度条 + 淡入状态文案（原 launchGame 内联块） */
    public void onProgress(int percent, String message) {
        if (!isLaunching) return;
        double targetWidth = 320 * (Math.min(percent, 100) / 100.0);
        // 平滑过渡进度条宽度，避免频繁回调时进度跳动生硬
        if (progressBarAnim != null) {
            progressBarAnim.stop();
        }
        progressBarAnim = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(
                        launchProgressBar.prefWidthProperty(), launchProgressBar.getPrefWidth())),
                new KeyFrame(Duration.millis(200), new KeyValue(
                        launchProgressBar.prefWidthProperty(), targetWidth))
        );
        progressBarAnim.play();
        launchStatusLabel.setText(message);
        launchStatusLabel.setOpacity(0);
        launchStatusLabel.setTranslateY(4);
        FadeTransition ft = new FadeTransition(Duration.millis(300), launchStatusLabel);
        ft.setToValue(1.0);
        TranslateTransition tt = new TranslateTransition(Duration.millis(300), launchStatusLabel);
        tt.setToY(0);
        new ParallelTransition(ft, tt).play();
    }

    /** 游戏窗口已出现：进度补满、转场对勾、标题与按钮收尾（原 launchGame 内联块） */
    public void markSuccess() {
        if (!isLaunching) return;
        if (launchTimeline != null) launchTimeline.stop();
        // 游戏窗口已出现：进度条补满至 100%（真实 100% 回调要等游戏退出后才触发）
        if (progressBarAnim != null) {
            progressBarAnim.stop();
            progressBarAnim = null;
        }
        Timeline fillAnim = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(
                        launchProgressBar.prefWidthProperty(), launchProgressBar.getPrefWidth())),
                new KeyFrame(Duration.millis(300), new KeyValue(
                        launchProgressBar.prefWidthProperty(), 320))
        );
        fillAnim.play();
        spinnerContainer.setVisible(false);
        if (roseTimer != null) roseTimer.stop();   // 转场对勾后动画不再需要
        successCheck.setVisible(true);
        ScaleTransition st = new ScaleTransition(Duration.millis(400), successCheck);
        st.setToX(1.0);
        st.setToY(1.0);
        st.setInterpolator(Interpolator.EASE_BOTH);
        st.play();
        launchTitleLabel.setText("游戏已启动");
        cancelLaunchBtn.setVisible(false);
        launchStatusLabel.setText("游戏窗口已出现");
    }

    /** 是否正在启动中（启动动画显示期间） */
    public boolean isLaunching() {
        return isLaunching;
    }

    /** 用户是否点了「取消启动」（用于拦截后台线程误报的 onError/onSuccess） */
    public boolean isCancelledByUser() {
        return launchCancelledByUser;
    }

    /** 每次启动前重置取消标记 */
    public void resetCancelled() {
        launchCancelledByUser = false;
    }

}

