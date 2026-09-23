package com.example.starlight.newui;

import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.Cursor;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.Bloom;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.net.URL;
import java.util.Random;
import java.util.ResourceBundle;

/**
 * Starlight Launcher · 闪屏控制器（移植自子项目「启动动画」）
 *
 * 结构（FXML）+ 样式（CSS）+ 逐帧绘制（本类）
 * 负责：
 *   · 初始化星尘 / 环绕粒子数据
 *   · 初始化噪点层
 *   · 启动 AnimationTimer 逐帧绘制 Canvas
 *   · 驱动标题 / 副标题 / 进度条动画
 *   · 每帧轮询 {@link SplashProgress}，把后台初始化的真实步骤显示为状态文字
 *   · 外部调用 {@link #requestFinish()} 后播退场动画并回调 onFinished
 *
 * 与子项目原版的差异：进度走满后不再自动退场，而是等外部请求收尾——
 * 启动器利用这段时间等待后台初始化（最多 15 秒超时）完成后再进主界面。
 */
public class StarlightSplashController implements Initializable {

    /* =========================================================
     *  FXML 注入
     * ========================================================= */

    @FXML private StackPane root;
    @FXML private Canvas bgCanvas;
    @FXML private Canvas glowCanvas;
    @FXML private HBox titleBox;
    @FXML private HBox subBox;
    @FXML private VBox loaderPane;
    @FXML private Rectangle progressFill;
    @FXML private Text statusText;
    @FXML private Text footerText;
    @FXML private Region noiseLayer;

    /* =========================================================
     *  常量
     * ========================================================= */

    private static final double W = 520;
    private static final double H = 325;

    private static final double TAU = Math.PI * 2;
    private static final double PHI = 1.6180339887;
    private static final double E   = 2.7182818285;

    private static final double T_BAR_START = 0.90;
    private static final double T_LOAD_SPAN = 1.90;

    private static final double BAR_WIDTH = 200;

    private static final int STAR_COUNT = 90;
    private static final int ORB_COUNT  = 18;

    /** 标题字距动画范围（px） */
    private static final double TITLE_LS_START = 9.5;
    private static final double TITLE_LS_END   = 5.0;
    private static final double SUB_LS_START   = 6.1;
    private static final double SUB_LS_END     = 3.4;

    private static final int[][] TINTS = {
        {255, 255, 255},
        {210, 230, 255},
        {170, 205, 255},
        {255, 232, 196},
    };

    /* =========================================================
     *  运行时状态
     * ========================================================= */

    private GraphicsContext bgGc;
    private GraphicsContext glowGc;

    private double[] starX, starY, starR, starBase,
                     starTwFreq, starTwPhase,
                     starDrA, starDrW, starDrP;
    private int[][] starRgb;

    private double[] orbRx, orbRy, orbAngSpd, orbAngPhase,
                     orbRotSpd, orbRotPhase,
                     orbBreathPer, orbBreathPhase, orbBreathAmp,
                     orbTilt, orbSize;
    private int[][] orbRgb;

    private long t0 = 0;
    private double finishTimer = -1;
    private boolean finishRequested = false;
    private double displayProg = 0;
    private String lastStatus = null;
    private AnimationTimer timer;

    private Runnable onFinished;

    /* =========================================================
     *  生命周期
     * ========================================================= */

    public void setOnFinished(Runnable onFinished) {
        this.onFinished = onFinished;
    }

    /** 请求收尾：等进度条走满后停留 0.35s，再播退场动画并回调 onFinished（须在 FX 线程调用） */
    public void requestFinish() {
        finishRequested = true;
    }

    /** 设置底部版权文字（内容由 SplashView 从 AppConfig 注入） */
    public void setFooterText(String text) {
        footerText.setText(text);
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 圆角裁剪
        Rectangle clip = new Rectangle(W, H);
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        root.setClip(clip);

        // 闪屏期间光标显示为 Loading（等待）状态，进主界面后随场景切换自动恢复
        root.setCursor(Cursor.WAIT);

        // 画布上下文
        bgGc = bgCanvas.getGraphicsContext2D();
        glowGc = glowCanvas.getGraphicsContext2D();

        // 光效层：加法混合 + Bloom 后处理
        glowCanvas.setBlendMode(BlendMode.ADD);
        glowCanvas.setEffect(new Bloom(0.25));

        // 数据与噪点
        initData();
        initNoise();

        // 初始 UI 状态
        titleBox.setOpacity(0);
        subBox.setOpacity(0);
        loaderPane.setOpacity(0);
        footerText.setOpacity(0);
        progressFill.setWidth(0);

        // 窗口入场
        playEnter();

        // 主循环
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (t0 == 0) t0 = now;
                double t = (now - t0) / 1e9;
                render(t);
            }
        };
        timer.start();
    }

    /** 外部可调：窗口关闭时停止定时器 */
    public void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    /* =========================================================
     *  数学工具
     * ========================================================= */

    private static double clamp01(double x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    private static double seg(double t, double a, double b) {
        return clamp01((t - a) / (b - a));
    }

    private static double easeOutCubic(double t) { return 1 - Math.pow(1 - t, 3); }
    private static double easeOutQuint(double t) { return 1 - Math.pow(1 - t, 5); }

    private static double easeOutBack(double t, double s) {
        return 1 + (s + 1) * Math.pow(t - 1, 3) + s * Math.pow(t - 1, 2);
    }

    private static double multiSine(double th) {
        return (Math.sin(th)
              + 0.5  * Math.sin(PHI * th + 2.1)
              + 0.25 * Math.sin(E   * th + 0.7)) / 1.75;
    }

    private static double breathe(double t, double period, double phase, double amp) {
        return 1 + amp * multiSine(TAU * t / period + phase);
    }

    /* =========================================================
     *  数据初始化
     * ========================================================= */

    private void initData() {
        Random rnd = new Random(20260921);

        /* ---------- 星尘 ---------- */
        starX        = new double[STAR_COUNT];
        starY        = new double[STAR_COUNT];
        starR        = new double[STAR_COUNT];
        starBase     = new double[STAR_COUNT];
        starTwFreq   = new double[STAR_COUNT];
        starTwPhase  = new double[STAR_COUNT];
        starDrA      = new double[STAR_COUNT];
        starDrW      = new double[STAR_COUNT];
        starDrP      = new double[STAR_COUNT];
        starRgb      = new int[STAR_COUNT][3];

        for (int i = 0; i < STAR_COUNT; i++) {
            double layer = rnd.nextDouble();
            double z     = rnd.nextDouble();
            double depth = 0.35 + 0.65 * z;

            starX[i]       = rnd.nextDouble();
            starY[i]       = rnd.nextDouble();
            starR[i]       = 0.22 + layer * 0.85 + z * 0.22;
            starBase[i]    = 0.14 + layer * 0.46 + z * 0.08;
            starTwFreq[i]  = 0.05 + rnd.nextDouble() * 0.35;
            starTwPhase[i] = rnd.nextDouble() * TAU;
            starDrA[i]     = (0.0025 + rnd.nextDouble() * 0.008) * depth;
            starDrW[i]     = (0.006  + rnd.nextDouble() * 0.038) * (0.6 + 0.4 * z);
            starDrP[i]     = rnd.nextDouble() * TAU;
            starRgb[i]     = TINTS[rnd.nextInt(TINTS.length)];
        }

        /* ---------- 环绕粒子 ---------- */
        orbRx          = new double[ORB_COUNT];
        orbRy          = new double[ORB_COUNT];
        orbAngSpd      = new double[ORB_COUNT];
        orbAngPhase    = new double[ORB_COUNT];
        orbRotSpd      = new double[ORB_COUNT];
        orbRotPhase    = new double[ORB_COUNT];
        orbBreathPer   = new double[ORB_COUNT];
        orbBreathPhase = new double[ORB_COUNT];
        orbBreathAmp   = new double[ORB_COUNT];
        orbTilt        = new double[ORB_COUNT];
        orbSize        = new double[ORB_COUNT];
        orbRgb         = new int[ORB_COUNT][3];

        for (int i = 0; i < ORB_COUNT; i++) {
            double rx = 0.10 + rnd.nextDouble() * 0.28;
            orbRx[i]          = rx;
            orbRy[i]          = rx * (0.24 + rnd.nextDouble() * 0.14);
            orbAngSpd[i]      = (0.05 + rnd.nextDouble() * 0.16) * (rnd.nextBoolean() ? 1 : -1);
            orbAngPhase[i]    = rnd.nextDouble() * TAU;
            orbRotSpd[i]      = (rnd.nextDouble() - 0.5) * 0.10;
            orbRotPhase[i]    = rnd.nextDouble() * TAU;
            orbBreathPer[i]   = 4.5 + rnd.nextDouble() * 6.0;
            orbBreathPhase[i] = rnd.nextDouble() * TAU;
            orbBreathAmp[i]   = 0.05 + rnd.nextDouble() * 0.10;
            orbTilt[i]        = (rnd.nextDouble() - 0.5) * 0.9;
            orbSize[i]        = 0.5 + rnd.nextDouble() * 1.0;
            orbRgb[i]         = TINTS[rnd.nextInt(2)];
        }
    }

    /** 噪点层：程序化生成 160×160 灰度图，平铺为 ImagePattern */
    private void initNoise() {
        int size = 160;
        WritableImage img = new WritableImage(size, size);
        PixelWriter pw = img.getPixelWriter();
        Random rnd = new Random(20260921);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = rnd.nextInt(256);
                pw.setColor(x, y, Color.rgb(v, v, v, 1.0));
            }
        }

        noiseLayer.setBackground(new Background(new BackgroundFill(
            new ImagePattern(img, 0, 0, size, size, false), null, null)));
    }

    /* =========================================================
     *  入场 / 退场
     * ========================================================= */

    private void playEnter() {
        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(root.opacityProperty(),   0,    Interpolator.LINEAR),
                new KeyValue(root.scaleXProperty(),    0.94, Interpolator.LINEAR),
                new KeyValue(root.scaleYProperty(),    0.94, Interpolator.LINEAR),
                new KeyValue(root.translateYProperty(), 12,  Interpolator.LINEAR)),
            new KeyFrame(Duration.millis(620),
                new KeyValue(root.opacityProperty(),   1, Interpolator.EASE_OUT),
                new KeyValue(root.scaleXProperty(),    1, Interpolator.SPLINE(0.22, 1, 0.36, 1)),
                new KeyValue(root.scaleYProperty(),    1, Interpolator.SPLINE(0.22, 1, 0.36, 1)),
                new KeyValue(root.translateYProperty(), 0, Interpolator.SPLINE(0.22, 1, 0.36, 1)))
        );
        tl.play();
    }

    private void playExit() {
        Timeline tl = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(root.opacityProperty(),   root.getOpacity()),
                new KeyValue(root.scaleXProperty(),    root.getScaleX()),
                new KeyValue(root.scaleYProperty(),    root.getScaleY()),
                new KeyValue(root.translateYProperty(), root.getTranslateY())),
            new KeyFrame(Duration.millis(700),
                new KeyValue(root.opacityProperty(),   0,     Interpolator.EASE_BOTH),
                new KeyValue(root.scaleXProperty(),    0.965, Interpolator.EASE_BOTH),
                new KeyValue(root.scaleYProperty(),    0.965, Interpolator.EASE_BOTH),
                new KeyValue(root.translateYProperty(), -10,   Interpolator.EASE_BOTH))
        );
        tl.setOnFinished(e -> {
            stop();
            if (onFinished != null) onFinished.run();
        });
        tl.play();
    }

    /* =========================================================
     *  主渲染
     * ========================================================= */

    private void render(double t) {
        bgGc.clearRect(0, 0, W, H);
        glowGc.clearRect(0, 0, W, H);

        double cx = W / 2;
        double cy = H * 0.36;

        /* 第 1 层：背景 */
        drawStars(bgGc, t);
        drawNebula(bgGc, t, cx, cy);

        /* 第 2 层：光效 */
        drawRipples(glowGc, t, cx, cy);
        drawOrbiters(glowGc, t, cx, cy);
        drawBeam(glowGc, t, cx, cy);
        drawCrest(glowGc, t, cx, cy);

        /* UI 驱动 */
        if (finishTimer < 0) {
            updateUI(t);

            // 收尾由外部触发（启动器借此等待后台初始化完成）：进度走满后才开始退场流程
            if (finishRequested && displayProg >= 0.999) {
                finishTimer = t;
            }
        } else if (finishTimer != Double.MAX_VALUE) {
            if (t - finishTimer > 0.35) {
                finishTimer = Double.MAX_VALUE;
                setStatus("准备就绪");
                playExit();
            }
        }
    }

    private double autoProgress(double t) {
        // 时间基线兜底：后台初始化未汇报时进度条仍能走满，保证闪屏一定能退场
        double p = easeOutCubic(seg(t, T_BAR_START, T_BAR_START + T_LOAD_SPAN));
        double jitter = 0.014 * multiSine(TAU * t * 1.45) * (1 - p);
        return clamp01(p + jitter);
    }

    /* =========================================================
     *  UI 更新（驱动 FXML 节点）
     * ========================================================= */

    private void updateUI(double t) {
        // 进度条：真实初始化进度优先（与时间基线取大者），逐帧平滑逼近避免跳变
        double target = Math.max(autoProgress(t), SplashProgress.getFraction());
        displayProg += (target - displayProg) * 0.10;
        if (target - displayProg < 0.002) displayProg = target;
        progressFill.setWidth(BAR_WIDTH * displayProg);

        // 标题：字距收拢 + 上浮淡入
        double pTitle = easeOutCubic(seg(t, 0.72, 1.55));
        titleBox.setOpacity(pTitle);
        titleBox.setSpacing(TITLE_LS_START + (TITLE_LS_END - TITLE_LS_START) * pTitle);
        titleBox.setTranslateY((1 - pTitle) * 12);

        // 副标题
        double pSub = easeOutCubic(seg(t, 0.90, 1.80));
        subBox.setOpacity(pSub * 0.9);
        subBox.setSpacing(SUB_LS_START + (SUB_LS_END - SUB_LS_START) * pSub);
        subBox.setTranslateY((1 - pSub) * 8);

        // 加载区
        double pBar = easeOutCubic(seg(t, 0.86, 1.40));
        loaderPane.setOpacity(pBar);

        // 版权
        double pFoot = easeOutCubic(seg(t, 1.15, 1.95));
        footerText.setOpacity(pFoot * 0.95);
        footerText.setTranslateY(-9 - (1 - pFoot) * 6);

        // 状态文字：显示后台初始化的真实步骤（SplashProgress 由 runBackgroundInit 汇报）
        String msg = SplashProgress.getMessage();
        if (!msg.isEmpty() && !msg.equals(lastStatus)) {
            lastStatus = msg;
            setStatus(msg);
        }
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }

    /* =========================================================
     *  绘制：星尘
     * ========================================================= */

    private void drawStars(GraphicsContext gc, double t) {
        double appear = easeOutCubic(seg(t, 0, 1.0));
        if (appear <= 0) return;

        for (int i = 0; i < STAR_COUNT; i++) {
            double tw = 0.5 + 0.5 * multiSine(TAU * starTwFreq[i] * t + starTwPhase[i]);
            double a  = appear * starBase[i] * (0.28 + 0.72 * tw * tw);
            if (a < 0.004) continue;

            double thD = TAU * starDrW[i] * t + starDrP[i];
            double dx  = starDrA[i] * Math.sin(thD);
            double dy  = starDrA[i] * Math.cos(thD * 0.73);

            double x = (starX[i] + dx) * W;
            double y = (starY[i] + dy) * H;

            gc.setGlobalAlpha(a);
            gc.setFill(Color.rgb(starRgb[i][0], starRgb[i][1], starRgb[i][2]));
            gc.fillOval(x - starR[i], y - starR[i], starR[i] * 2, starR[i] * 2);
        }
        gc.setGlobalAlpha(1);
    }

    /* =========================================================
     *  绘制：星云
     * ========================================================= */

    private void drawNebula(GraphicsContext gc, double t, double cx, double cy) {
        double inA = easeOutCubic(seg(t, 0.10, 1.40));
        if (inA <= 0) return;

        double base = Math.min(W, H);
        double br = breathe(t, 5.0, 0, 0.045) * breathe(t, 7.3, 1.7, 0.030);

        RadialGradient neb = new RadialGradient(0, 0, cx, cy, base * 0.58 * br,
            false, CycleMethod.NO_CYCLE,
            new Stop(0.00, Color.rgb(80, 130, 255, 0.16 * inA)),
            new Stop(0.35, Color.rgb(60,  95, 220, 0.08 * inA)),
            new Stop(1.00, Color.rgb(15,  25,  70, 0)));
        gc.setFill(neb);
        gc.fillRect(0, 0, W, H);

        double wob1 = 0.04 * multiSine(TAU * t / 6.5);
        double wob2 = 0.04 * multiSine(TAU * t / 8.3 + 1.1);
        double ox = cx + (Math.cos(t * 0.11) + wob1) * base * 0.22;
        double oy = cy + (Math.sin(t * 0.14) + wob2) * base * 0.14;

        RadialGradient purple = new RadialGradient(0, 0, ox, oy, base * 0.34,
            false, CycleMethod.NO_CYCLE,
            new Stop(0, Color.rgb(140, 90, 255, 0.10 * inA)),
            new Stop(1, Color.rgb(80, 40, 160, 0)));
        gc.setFill(purple);
        gc.fillRect(0, 0, W, H);
    }

    /* =========================================================
     *  绘制：启动波纹
     * ========================================================= */

    private void drawRipples(GraphicsContext gc, double t, double cx, double cy) {
        double t0 = 0.42;
        if (t < t0) return;

        double period = 2.4;
        double maxR   = Math.min(W, H) * 0.28;
        double inA    = easeOutCubic(seg(t, t0, t0 + 0.9));

        gc.setLineWidth(1.0);
        for (int k = 0; k < 3; k++) {
            double p = (((t - t0) / period) + k / 3.0) % 1;
            double r = 24 + easeOutQuint(p) * maxR;
            double a = Math.pow(1 - p, 2.2) * 0.38 * inA;
            if (a < 0.004) continue;

            gc.setStroke(Color.rgb(140, 180, 255, a));
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }
    }

    /* =========================================================
     *  绘制：环绕粒子
     * ========================================================= */

    private void drawOrbiters(GraphicsContext gc, double t, double cx, double cy) {
        double inA = easeOutCubic(seg(t, 0.62, 1.60));
        if (inA <= 0) return;

        double base = Math.min(W, H) * 0.52;

        for (int i = 0; i < ORB_COUNT; i++) {
            double ang = TAU * orbAngSpd[i] * t + orbAngPhase[i];
            double rr  = breathe(t, orbBreathPer[i], orbBreathPhase[i], orbBreathAmp[i]);
            double rot = orbRotPhase[i] + orbRotSpd[i] * t + orbTilt[i];

            double lx = Math.cos(ang) * orbRx[i] * base * rr;
            double ly = Math.sin(ang) * orbRy[i] * base * rr;

            double cosR = Math.cos(rot), sinR = Math.sin(rot);
            double x = cx + lx * cosR - ly * sinR;
            double y = cy + lx * sinR + ly * cosR;

            double depth = 0.5 + 0.5 * Math.sin(ang);
            double a = inA * (0.14 + 0.80 * depth) * 0.80;
            double r = orbSize[i] * (0.40 + 0.90 * depth);

            gc.setGlobalAlpha(a);
            gc.setFill(Color.rgb(orbRgb[i][0], orbRgb[i][1], orbRgb[i][2]));
            gc.fillOval(x - r, y - r, r * 2, r * 2);
        }
        gc.setGlobalAlpha(1);
    }

    /* =========================================================
     *  绘制：单条渐隐光束
     * ========================================================= */

    private void drawSpike(GraphicsContext gc, double cx, double cy,
                           double len, double alpha, double angle) {
        if (alpha <= 0.004 || len <= 0.5) return;

        double x1 = cx + Math.cos(angle) * len;
        double y1 = cy + Math.sin(angle) * len;

        LinearGradient g = new LinearGradient(cx, cy, x1, y1, false, CycleMethod.NO_CYCLE,
            new Stop(0.00, Color.rgb(200, 225, 255, alpha)),
            new Stop(0.30, Color.rgb(200, 225, 255, alpha * 0.30)),
            new Stop(1.00, Color.rgb(200, 225, 255, 0)));

        gc.setStroke(g);
        gc.strokeLine(cx, cy, x1, y1);
    }

    /* =========================================================
     *  绘制：主光束
     * ========================================================= */

    private void drawBeam(GraphicsContext gc, double t, double cx, double cy) {
        double inA = easeOutCubic(seg(t, 0.45, 1.40));
        if (inA <= 0) return;

        double pulse = 0.5 + 0.5 * multiSine(TAU * t / 2.8);
        double base  = Math.min(W, H);
        double L     = base * (0.13 + 0.05 * pulse) * inA;

        gc.setLineWidth(1.1);
        for (int i = 0; i < 4; i++) {
            drawSpike(gc, cx, cy, L, inA * 0.45, i * Math.PI / 2);
        }
        for (int i = 0; i < 4; i++) {
            drawSpike(gc, cx, cy, L * 0.42, inA * 0.22, Math.PI / 4 + i * Math.PI / 2);
        }
    }

    /* =========================================================
     *  绘制：透视光环
     * ========================================================= */

    private void drawRing(GraphicsContext gc, double cx, double cy, double r,
                          double alpha, double rot, double fade) {
        if (fade <= 0 || alpha <= 0 || r <= 1) return;

        gc.save();
        gc.translate(cx, cy);
        gc.rotate(Math.toDegrees(rot));
        gc.scale(1, 0.30);
        gc.setStroke(Color.rgb(130, 175, 255, alpha * fade));
        gc.setLineWidth(1.0);
        gc.strokeOval(-r, -r, r * 2, r * 2);
        gc.restore();
    }

    /* =========================================================
     *  绘制：中央星徽（星形线）
     * ========================================================= */

    private void drawCrest(GraphicsContext gc, double t, double cx, double cy) {
        double inP = seg(t, 0.26, 1.05);
        if (inP <= 0) return;

        double pop   = Math.max(0, easeOutBack(inP, 2.0));
        double scale = breathe(t, 3.4, 0, 0.045) * breathe(t, 5.9, 1.3, 0.030);
        double R     = Math.min(W, H) * 0.085 * pop * scale;
        if (R < 0.5) return;

        /* ① 外层光晕 */
        double haloR = R * 4.0;
        double haloBreathe = 0.5 + 0.5 * multiSine(TAU * t / 3.4);
        double haloA = (0.20 + 0.18 * haloBreathe) * inP;

        RadialGradient halo = new RadialGradient(0, 0, cx, cy, haloR,
            false, CycleMethod.NO_CYCLE,
            new Stop(0.00, Color.rgb(150, 195, 255, haloA)),
            new Stop(0.12, Color.rgb(110, 155, 255, haloA * 0.60)),
            new Stop(0.38, Color.rgb(70,  110, 230, haloA * 0.18)),
            new Stop(1.00, Color.rgb(20,   30,  80, 0)));
        gc.setFill(halo);
        gc.fillOval(cx - haloR, cy - haloR, haloR * 2, haloR * 2);

        /* ② 两道光环（反向旋转 + 慢摆） */
        double rot1 =  t * 0.22 + 0.04 * multiSine(TAU * t / 9.0);
        double rot2 = -t * 0.15 + 0.04 * multiSine(TAU * t / 11.0 + 2.0);
        drawRing(gc, cx, cy, R * 2.45, 0.28, rot1, inP * 0.55);
        drawRing(gc, cx, cy, R * 3.25, 0.18, rot2, inP * 0.38);

        /* ③ 星形线本体：x = a·cos³θ, y = a·sin³θ */
        double bodyRot = t * 0.10 + 0.06 * multiSine(TAU * t / 13.0);

        gc.save();
        gc.translate(cx, cy);
        gc.rotate(Math.toDegrees(bodyRot));

        gc.beginPath();
        int SEG = 160;
        for (int i = 0; i <= SEG; i++) {
            double th = (i / (double) SEG) * TAU;
            double c = Math.cos(th), s = Math.sin(th);
            double px = R * c * c * c;
            double py = R * s * s * s;
            if (i == 0) gc.moveTo(px, py);
            else        gc.lineTo(px, py);
        }
        gc.closePath();

        LinearGradient bodyGrad = new LinearGradient(-R, -R, R, R, false, CycleMethod.NO_CYCLE,
            new Stop(0.00, Color.rgb(255, 255, 255, 1.00)),
            new Stop(0.45, Color.rgb(205, 228, 255, 0.98)),
            new Stop(1.00, Color.rgb(150, 190, 255, 0.88)));
        gc.setFill(bodyGrad);
        gc.fill();
        gc.restore();

        /* ④ 中心高光 */
        double coreR = R * 0.45;
        RadialGradient core = new RadialGradient(0, 0, cx, cy, coreR,
            false, CycleMethod.NO_CYCLE,
            new Stop(0, Color.rgb(255, 255, 255, 0.92 * inP)),
            new Stop(1, Color.rgb(255, 255, 255, 0)));
        gc.setFill(core);
        gc.fillOval(cx - coreR, cy - coreR, coreR * 2, coreR * 2);
    }
}
