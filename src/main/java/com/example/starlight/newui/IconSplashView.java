package com.example.starlight.newui;

import com.example.starlight.Controllers.PageView;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * 启动动画（图标式）—— 屏幕正中央显示一张 {@code /images/icon.png}，不做任何动画。
 *
 * <p>与 {@link SplashView}（星轨动画，Canvas 逐帧绘制星空）互为备选：
 * 设置 → 主题与背景 →「启动动画」二选一，配置键 {@code SplashStyle=icon|starlight}。
 *
 * <p>窗口是 {@code StageStyle.TRANSPARENT}，画布完全透明，只有这一张图：
 * 没有卡片底板、没有标题、没有进度条、没有版权行，也没有淡入淡出 / 呼吸 / 缩放 ——
 * 就是「屏幕中心一张静态图片，停一会儿，然后换成主界面」（PCL2 的那种开屏）。
 *
 * <p>尺寸：窗口与图片同为 {@value #SPLASH_WIDTH}px 见方。窗口尺寸必须等于图片尺寸 ——
 * 透明窗口里多出来的任何留白都是看不见的，只会让「居中」算不准。
 * 想换大小只改 {@link #SPLASH_WIDTH} / {@link #SPLASH_HEIGHT} 两个常量即可。
 *
 * <p>停留时长由启动器决定（{@link #playAnimation(Duration)}）：后台初始化跑完之后，
 * 再补足最短播放时长的剩余量，到点直接回调 {@link #setOnFinished(Runnable)} 进入主界面。
 */
public final class IconSplashView implements PageView, SplashScreen {

    /** 闪屏窗口尺寸 = 图片显示尺寸（正方形，与 PCL 开屏图的实际体量相当：约 130px） */
    public static final double SPLASH_WIDTH = 128;
    public static final double SPLASH_HEIGHT = 128;

    private final StackPane root = new StackPane();
    private Timeline holdTimeline;
    private Runnable onFinished;

    public IconSplashView() {
        root.setAlignment(Pos.CENTER);
        root.setPrefSize(SPLASH_WIDTH, SPLASH_HEIGHT);
        root.setMinSize(SPLASH_WIDTH, SPLASH_HEIGHT);
        root.setMaxSize(SPLASH_WIDTH, SPLASH_HEIGHT);
        root.getChildren().add(createMark());
    }

    /** 画面主体：一张图。读不到图时退化为应用名文字，至少不是空白窗口 */
    private Node createMark() {
        Image icon = loadIcon();
        if (icon == null) {
            javafx.scene.control.Label fallback =
                    new javafx.scene.control.Label(AppConfig.APP_DISPLAY_NAME);
            fallback.setStyle("-fx-font-size: 20px; -fx-font-weight: 600; -fx-text-fill: #2b6bff;");
            return fallback;
        }
        ImageView iconView = new ImageView(icon);
        iconView.setFitWidth(SPLASH_WIDTH);
        iconView.setFitHeight(SPLASH_HEIGHT);
        iconView.setPreserveRatio(true);
        iconView.setSmooth(true);
        return iconView;
    }

    /** 读图标：classpath 里的 icon.png（与任务栏图标同源） */
    private static Image loadIcon() {
        try (java.io.InputStream in =
                     IconSplashView.class.getResourceAsStream("/images/icon.png")) {
            if (in != null) return new Image(in);
        } catch (Exception ignored) {
            // 读不到图标不能阻断启动，交给 createMark 走文字兜底
        }
        return null;
    }

    // 生命周期 //

    @Override
    public Node getView() {
        return root;
    }

    @Override
    public double width() { return SPLASH_WIDTH; }

    @Override
    public double height() { return SPLASH_HEIGHT; }

    @Override
    public void setOnFinished(Runnable onFinished) {
        this.onFinished = onFinished;
    }

    /**
     * 停留 {@code duration} 之后直接收尾 —— 不播放任何动画。
     *
     * @param duration 还需要在屏幕上停留多久（补足最短播放时长）；0 / 负值时立即收尾
     */
    @Override
    public void playAnimation(Duration duration) {
        if (duration == null || duration.lessThanOrEqualTo(Duration.ZERO)) {
            finish();
            return;
        }
        if (holdTimeline != null) holdTimeline.stop();
        holdTimeline = new Timeline(new KeyFrame(duration, e -> finish()));
        holdTimeline.play();
    }

    @Override
    public void stop() {
        if (holdTimeline != null) {
            holdTimeline.stop();
            holdTimeline = null;
        }
    }

    private void finish() {
        stop();
        if (onFinished != null) onFinished.run();
    }
}
