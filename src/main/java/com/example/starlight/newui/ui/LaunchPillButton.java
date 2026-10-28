package com.example.starlight.newui.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/**
 * 首页「启动游戏」线框胶囊按钮（交互对齐 references/html/button.html）。
 *
 * <p>本类只管按钮自身的外观状态，不接管点击与启动逻辑：
 * <ul>
 *   <li>常态：线框胶囊 + 播放图标 + 文案（样式见 style.css 的 .home-launch-btn）；</li>
 *   <li>{@link #showLoading()}：宽度收成圆形，文字与图标淡出，细线加载环开始旋转；</li>
 *   <li>{@link #showSuccess()}：线框转绿并画出对勾，停留 1.2s 后自动展开回胶囊；</li>
 *   <li>{@link #resetIdle()}：立即展开回胶囊（启动失败 / 取消）。</li>
 * </ul>
 *
 * <p>启动动画弹窗（{@link LaunchOverlay}）由主壳驱动，与本类互不干涉：
 * 弹窗照常弹出，按钮在背后做自己的收圈 / 对勾动效。
 */
public final class LaunchPillButton extends Button {

    /** 加载环半径（直径 18px，demo 为 22px；胶囊高约 48px，18px 更不容易顶到边框） */
    private static final double RING_RADIUS = 9;

    /** 收圈 / 展开时长，与 demo 的 0.3s 一致 */
    private static final Duration WIDTH_ANIM = Duration.millis(300);

    /** 对勾停留时长（demo 为 1s，这里稍长一点便于看清） */
    private static final Duration SUCCESS_HOLD = Duration.millis(1200);

    /** 未完成布局时的圆形直径兜底（demo 的 50×50） */
    private static final double COLLAPSED_FALLBACK = 50;

    private enum State { IDLE, LOADING, SUCCESS }

    private final Label caption = new Label();
    private final Group spinner = new Group();
    private final SVGPath check = new SVGPath();
    private final RotateTransition spin = new RotateTransition(Duration.millis(800), spinner);
    private final PauseTransition hold = new PauseTransition(SUCCESS_HOLD);

    private Timeline widthAnim;
    /** 宽度动画代次：被打断的旧动画即使回调 onFinished 也不能改动新状态 */
    private int widthGen;
    private State state = State.IDLE;

    /**
     * @param iconName {@link AppIcons} 图标名（常态显示在文案左侧）
     * @param text     常态文案
     */
    public LaunchPillButton(String iconName, String text) {
        getStyleClass().add("home-launch-btn");
        // 常态撑满卡片宽度；收圈时只改 maxWidth，由外层 StackPane 负责居中
        setMaxWidth(Double.MAX_VALUE);

        caption.setText(text);
        caption.getStyleClass().add("home-launch-caption");
        AppIcons.apply(caption, iconName, 15, null);
        caption.setGraphicTextGap(8);

        // 细线加载环：轨道 + 3/4 圆弧，颜色跟随按钮文字色（CSS -fx-text-fill）
        Circle track = new Circle(RING_RADIUS);
        track.getStyleClass().add("home-launch-ring-track");
        track.strokeProperty().bind(textFillProperty());
        track.setOpacity(0.25);

        Arc arc = new Arc(0, 0, RING_RADIUS, RING_RADIUS, 90, 270);
        arc.setType(ArcType.OPEN);
        arc.getStyleClass().add("home-launch-ring");
        arc.strokeProperty().bind(textFillProperty());

        spinner.getChildren().addAll(track, arc);
        spinner.setOpacity(0);
        spinner.setMouseTransparent(true);

        spin.setByAngle(360);
        spin.setCycleCount(Animation.INDEFINITE);
        spin.setInterpolator(Interpolator.LINEAR);

        // 细线对勾：demo 的 24×24 路径，按 0.8 缩到约 19px；描边动画由 strokeDashOffset 画出
        check.setContent("M20 6 9 17l-5-5");
        check.getStyleClass().add("home-launch-check");
        check.setScaleX(0.8);
        check.setScaleY(0.8);
        check.strokeProperty().bind(textFillProperty());
        check.setStrokeDashOffset(30);
        check.getStrokeDashArray().setAll(30.0);
        check.setOpacity(0);
        check.setMouseTransparent(true);

        StackPane body = new StackPane(caption, spinner, check);
        body.setAlignment(Pos.CENTER);
        // 点透到按钮本身：图标 / 圆环 / 对勾都不吞点击
        body.setMouseTransparent(true);
        setGraphic(body);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        hold.setOnFinished(e -> resetIdle());
    }

    /** 点击后真正开始启动：胶囊收成圆形，文字淡出，加载环转起来 */
    public void showLoading() {
        if (state == State.LOADING) return;
        state = State.LOADING;
        getStyleClass().remove("is-success");
        if (!getStyleClass().contains("is-loading")) getStyleClass().add("is-loading");
        spin.playFromStart();
        fade(caption, 0, 120);
        fade(check, 0, 80);
        fade(spinner, 1, 200);
        collapse();
    }

    /** 游戏进程已拉起：绿框 + 对勾，停留一会儿后展开回胶囊 */
    public void showSuccess() {
        if (state == State.SUCCESS) return;
        state = State.SUCCESS;
        getStyleClass().remove("is-loading");
        if (!getStyleClass().contains("is-success")) getStyleClass().add("is-success");
        spin.stop();
        fade(caption, 0, 80);
        fade(spinner, 0, 120);
        collapseIfExpanded();
        check.setStrokeDashOffset(30);
        check.setOpacity(1);
        new Timeline(new KeyFrame(Duration.millis(400),
                new KeyValue(check.strokeDashOffsetProperty(), 0, Interpolator.EASE_BOTH))).play();
        hold.stop();
        hold.playFromStart();
    }

    /** 启动失败 / 取消：立即展开回胶囊 */
    public void resetIdle() {
        if (state == State.IDLE) return;
        state = State.IDLE;
        hold.stop();
        spin.stop();
        getStyleClass().removeAll("is-loading", "is-success");
        check.setOpacity(0);
        check.setStrokeDashOffset(30);
        fade(spinner, 0, 120);
        fade(caption, 1, 160);
        expand();
    }

    // 宽度动画 //

    /** 收成圆形：目标宽度取按钮高度，配合 CSS 的 999 圆角就是一个正圆 */
    private void collapse() {
        animateWidth(collapsedDiameter(), null);
    }

    /** 展开回胶囊：目标宽度取父级（外层 StackPane）宽度，结束后恢复可自适应 */
    private void expand() {
        if (getWidth() <= 1) {
            setMaxWidth(Double.MAX_VALUE);
            return;
        }
        double target = getWidth();
        if (getParent() instanceof Region parent && parent.getWidth() > 1) {
            target = parent.getWidth();
        }
        animateWidth(target, () -> setMaxWidth(Double.MAX_VALUE));
    }

    /** 成功 / 失败落到一个还没布局的新节点上时，直接定成圆形，布局后就是圆的 */
    private void collapseIfExpanded() {
        if (getWidth() <= 1) {
            setMaxWidth(collapsedDiameter());
            return;
        }
        if (getWidth() > collapsedDiameter() + 1) collapse();
    }

    private double collapsedDiameter() {
        // 圆角恒为 999：宽度收到高度即成圆；未布局时用 50 兜底
        return getHeight() > 1 ? getHeight() : COLLAPSED_FALLBACK;
    }

    private void animateWidth(double target, Runnable onFinished) {
        if (widthAnim != null) {
            widthAnim.stop();
        }
        if (getWidth() > 1) {
            // 直接从 MAX_VALUE 插值到几十像素会在末帧被浮点精度吃成 0（52 - Double.MAX_VALUE 舍入为 -MAX），
            // 先把 maxWidth 落成当前实际宽度，再从像素值之间插值
            setMaxWidth(getWidth());
        }
        final int gen = ++widthGen;
        Timeline anim = new Timeline(new KeyFrame(WIDTH_ANIM,
                new KeyValue(maxWidthProperty(), target, Interpolator.EASE_BOTH)));
        widthAnim = anim;
        anim.setOnFinished(e -> {
            // stop() 打断的旧动画不能把新状态（尤其展开后的 maxWidth）改回去
            if (gen != widthGen) return;
            widthAnim = null;
            if (onFinished != null) onFinished.run();
        });
        anim.play();
    }

    private static void fade(Node node, double to, double millis) {
        FadeTransition ft = new FadeTransition(Duration.millis(millis), node);
        ft.setToValue(to);
        ft.play();
    }
}
