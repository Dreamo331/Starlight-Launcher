package com.example.starlight.newui.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * 拨动开关的动态效果：滑块回弹、按压挤压、✓/✕ 图标交叉淡入淡出，以及键盘焦点环与禁用态。
 *
 * <p>对齐设计稿的三个动作：
 * <ol>
 *   <li><b>位移回弹</b>：滑块位移 0.34s，曲线 {@code cubic-bezier(0.34, 1.3, 0.64, 1)}
 *       ——第二个参数 1.3 让滑块到位时轻微过冲再回弹，不是线性或 ease。</li>
 *   <li><b>按压挤压</b>：按住时滑块宽度 24px → 28px，0.22s {@code cubic-bezier(0.4, 0, 0.2, 1)}。
 *       关闭态按压时左边缘不动向右伸，开启态按压时右边缘不动向左伸（靠同时给中心让 2px 实现）；
 *       松手后宽度 0.22s 回到 24px，位移 0.34s 带着回弹走到目标位，形成「捏一下再滑过去」的手感。</li>
 *   <li><b>图标交叉</b>：滑块里叠一个 ✓ 和一个 ✕，切换时出去的 scale(1)→scale(0.4) 淡出
 *       （0.2s），进来的 scale(0.4)→scale(1) 淡入（0.3s），带回弹。</li>
 * </ol>
 *
 * <h3>几何与样式表的约定</h3>
 * 轨道 52×30（无边框，内区即 52×30）、滑块 24×24、四周留 3px（GAP），于是行程正好 22px。
 * <b>滑块的尺寸与位置必须由代码独占</b>：作者样式表（{@code style.css}）的优先级高于代码，
 * 一旦在 CSS 里再写 {@code -fx-pref-width} / {@code -fx-translate-x}，下一次样式重算
 * （悬停、聚焦、选中都会触发）就会把动画中的值打回原状。所以这两项在 CSS 里已删掉，
 * 由 {@link #initNodes()} 锁定并驱动。
 *
 * <h3>无障碍</h3>
 * JavaFX 没有 {@code role="switch"}，等价的映射是 {@code AccessibleRole.CHECK_BOX}（CheckBox 的默认值，
 * 勾选状态会一并上报）；Tab 聚焦与空格切换是 CheckBox 自带行为。焦点环只在「键盘聚焦」时显示：
 * 鼠标按下会挂上 {@code no-focus-ring} 样式类抑制焦点环，按键或失焦时摘掉，
 * 以此近似 CSS 的 {@code :focus-visible}。禁用态由 {@code setDisable(true)} 阻断交互，
 * 外观（opacity 0.45 / cursor）写在 CSS 的 {@code .toggle-switch:disabled} 里。
 */
public final class ToggleSwitchAnimator {

    // ===== 几何：与 style.css 的轨道尺寸严格对应 =====
    private static final double TRACK_W = 52;
    private static final double TRACK_H = 30;
    private static final double BORDER = 0;
    /** 滑块与轨道内边缘的间距 */
    private static final double GAP = 3;
    private static final double KNOB_W = 24;
    private static final double KNOB_H = 24;
    /** 按压时挤到的宽度（比常态宽 4px，向内侧伸） */
    private static final double KNOB_PRESSED_W = 28;
    private static final double INNER_W = TRACK_W - 2 * BORDER;
    private static final double INNER_H = TRACK_H - 2 * BORDER;
    /** 按压时中心让出的距离，让外侧那条边保持不动 */
    private static final double PRESS_SHIFT = (KNOB_PRESSED_W - KNOB_W) / 2;

    /**
     * 静止位（相对 StackPane 居中位置）的 translateX：滑块在 {@code .box} 里居中，
     * 所以位移要用「目标中心 − 盒子中心」换算。关闭位左边缘贴住左内边，开启位右边缘贴住右内边。
     */
    private static final double REST_OFF_X = GAP + KNOB_W / 2 - INNER_W / 2;
    private static final double REST_ON_X = INNER_W - GAP - KNOB_W / 2 - INNER_W / 2;

    // ===== 曲线与时长 =====
    /** 位移：cubic-bezier(0.34, 1.3, 0.64, 1)，1.3 > 1 才有过冲回弹（SPLINE 收不了这种曲线） */
    private static final Interpolator BOUNCE = new CubicBezierInterpolator(0.34, 1.30, 0.64, 1);
    /** 挤压：cubic-bezier(0.4, 0, 0.2, 1) */
    private static final Interpolator SQUEEZE = new CubicBezierInterpolator(0.40, 0.0, 0.20, 1.0);
    private static final Duration SLIDE = Duration.millis(340);
    private static final Duration PRESS = Duration.millis(220);
    /** 图标交叉淡切：出去的淡出更快、进来的淡入稍慢（基准 200ms / 300ms） */
    private static final Duration ICON_OUT = Duration.millis(200);
    private static final Duration ICON_IN = Duration.millis(300);
    /** 图标隐藏侧的缩放（基准 0.4） */
    private static final double ICON_HIDDEN_SCALE = 0.4;

    /** 鼠标聚焦时挂上它抑制焦点环，近似 :focus-visible */
    private static final String NO_RING = "no-focus-ring";
    /** 状态挂在控件的 properties 上：避免重复安装，也不用静态表持有控件 */
    private static final String KEY = "starlight.toggleSwitch";

    // ===== 轨道底色过渡（与 style.css 各规则的层结构一一对应） =====
    /** 关闭态轨道灰 = .toggle-switch 底色 */
    private static final Color TRACK_OFF = new Color(0, 0, 0, 0.12);
    /** 开启态轨道蓝 = :selected 本体层 */
    private static final Color TRACK_ON = Color.web("#3b82f6");
    /** 开启态光晕 = :selected 光晕层（四周 4px） */
    private static final Color GLOW = new Color(59 / 255.0, 130 / 255.0, 246 / 255.0, 0.14);
    /** 键盘焦点环 = :focused 外层（向外 3px） */
    private static final Color FOCUS_RING = new Color(59 / 255.0, 130 / 255.0, 246 / 255.0, 0.35);
    private static final Duration TRACK_FADE = Duration.millis(300);
    private static final Interpolator EASE_SOFT = new CubicBezierInterpolator(0.40, 0.0, 0.20, 1.0);
    private Timeline trackFade;

    private static final Color CHECK_COLOR = Color.web("#3b82f6");
    /** 关闭态 ✕ 取灰色：滑块已是白底，白色图标在上面不可见（基准 .ico.off color #9ca3af） */
    private static final Color CROSS_COLOR = Color.web("#9ca3af");

    private final CheckBox toggle;
    private Region box;
    private StackPane mark;
    private Node checkIcon;
    private Node closeIcon;
    /** 正在跑的动画：新动画开始前先停掉上一条，避免两条时间线抢同一个属性 */
    private Timeline running;
    /** 是否处于按住状态（按住时不跑松手动画，交给 release 收尾） */
    private boolean pressed;

    private ToggleSwitchAnimator(CheckBox toggle) {
        this.toggle = toggle;
    }

    /** 给一个已经带 {@code toggle-switch} 样式类的 CheckBox 装上动态效果（重复调用无副作用） */
    public static void install(CheckBox toggle) {
        if (toggle == null || toggle.getProperties().containsKey(KEY)) return;
        ToggleSwitchAnimator animator = new ToggleSwitchAnimator(toggle);
        toggle.getProperties().put(KEY, animator);
        animator.attach();
    }

    private void attach() {
        toggle.selectedProperty().addListener((o, was, on) -> {
            initNodes();
            animateTrack(on);
            if (mark == null) return;
            if (pressed) return;              // 按压中交给松手后的 settle 收尾
            settle();
            crossFade(on);
        });
        toggle.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (toggle.isDisabled()) return;
            // 鼠标按下不给焦点环，键盘聚焦才给（近似 :focus-visible）
            if (!toggle.getStyleClass().contains(NO_RING)) toggle.getStyleClass().add(NO_RING);
            press();
        });
        toggle.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (toggle.isDisabled()) return;
            release();
        });
        toggle.addEventFilter(KeyEvent.ANY, e -> toggle.getStyleClass().remove(NO_RING));
        toggle.focusedProperty().addListener((o, was, on) -> {
            if (!on) toggle.getStyleClass().remove(NO_RING);
        });

        if (toggle.getSkin() != null) {
            initNodes();
        } else {
            toggle.skinProperty().addListener((o, old, skin) -> {
                if (skin != null) initNodes();
            });
        }
    }

    /** 皮肤就绪后拿到 .box / .mark，锁定滑块几何并放上两个图标 */
    private void initNodes() {
        if (mark != null) return;
        if (toggle.lookup(".box") instanceof Region b) box = b;
        if (toggle.lookup(".mark") instanceof StackPane m) mark = m;
        if (mark == null) return;

        // 尺寸与位移由代码独占（见类注释：CSS 的优先级高于代码，会打回动画中的值）
        mark.setPrefSize(KNOB_W, KNOB_H);
        mark.minWidthProperty().bind(mark.prefWidthProperty());
        mark.maxWidthProperty().bind(mark.prefWidthProperty());
        mark.minHeightProperty().bind(mark.prefHeightProperty());
        mark.maxHeightProperty().bind(mark.prefHeightProperty());
        mark.setTranslateX(restX());

        if (box != null) {
            box.setMinSize(INNER_W, INNER_H);
            box.setPrefSize(INNER_W, INNER_H);
            box.setMaxSize(INNER_W, INNER_H);
        }

        checkIcon = AppIcons.icon("check", 13, CHECK_COLOR);
        closeIcon = AppIcons.icon("close", 11, CROSS_COLOR);
        checkIcon.setMouseTransparent(true);
        closeIcon.setMouseTransparent(true);
        mark.getChildren().addAll(checkIcon, closeIcon);
        applyIconState(toggle.isSelected());
    }

    /** 关闭位 / 开启位的静止 translateX */
    private double restX() {
        return toggle.isSelected() ? REST_ON_X : REST_OFF_X;
    }

    /** 按住：宽度挤到 28px，同时中心向内让 2px，于是外侧那条边不动 */
    private void press() {
        initNodes();
        if (mark == null) return;
        pressed = true;
        double rest = restX();
        double shifted = rest + (rest < 0 ? PRESS_SHIFT : -PRESS_SHIFT);
        play(new Timeline(new KeyFrame(PRESS,
                new KeyValue(mark.prefWidthProperty(), KNOB_PRESSED_W, SQUEEZE),
                new KeyValue(mark.translateXProperty(), shifted, SQUEEZE))));
    }

    /** 松手：宽度先回弹到 24px，位移继续用回弹曲线走到目标位 */
    private void release() {
        pressed = false;
        settle();
    }

    private void settle() {
        initNodes();
        if (mark == null) return;
        play(new Timeline(
                // 宽度 0.22s 先到位，位移 0.34s 还在路上 —— 「捏一下再滑过去」的层次就来自这两段时长
                new KeyFrame(PRESS, new KeyValue(mark.prefWidthProperty(), KNOB_W, SQUEEZE)),
                new KeyFrame(SLIDE, new KeyValue(mark.translateXProperty(), restX(), BOUNCE))));
    }

    private void play(Timeline next) {
        if (running != null) running.stop();
        running = next;
        running.play();
    }

    // ===== 轨道底色过渡（设计 D4：setBackground 插值，不用 inline style） =====

    /**
     * 底色 0.3s 过渡：光晕 alpha 与本体色同时插值。
     * 终帧背景按「是否开启 × 是否有键盘焦点环」拼成与 CSS 规则完全相同的层结构，
     * 终帧后的样式重算无论落到哪条规则都不会产生跳变。
     */
    private void animateTrack(boolean on) {
        Color ring = ringColor();
        boolean hasRing = ring != null;
        Color bodyFrom = on ? TRACK_OFF : TRACK_ON;
        Color bodyTo = on ? TRACK_ON : TRACK_OFF;
        double glowFrom = on ? 0 : GLOW.getOpacity();
        double glowTo = on ? GLOW.getOpacity() : 0;
        DoubleProperty t = new SimpleDoubleProperty(0);
        t.addListener((o, a, v) -> toggle.setBackground(trackBackground(
                bodyFrom.interpolate(bodyTo, v.doubleValue()),
                glowFrom + (glowTo - glowFrom) * v.doubleValue(),
                hasRing, ring)));
        if (trackFade != null) trackFade.stop();
        trackFade = new Timeline(new KeyFrame(TRACK_FADE, new KeyValue(t, 1, EASE_SOFT)));
        trackFade.setOnFinished(e ->
                toggle.setBackground(trackBackground(bodyTo, glowTo, hasRing, ring)));
        trackFade.play();
    }

    /** 键盘焦点环是否在显示：no-focus-ring 类由鼠标按压挂上，键盘 Tab 聚焦时没有 */
    private Color ringColor() {
        return toggle.isFocused() && !toggle.getStyleClass().contains(NO_RING) ? FOCUS_RING : null;
    }

    /** 层结构：焦点环（insets -3）→ 光晕（insets -4）→ 本体（0），与 CSS 规则一一对应 */
    private static Background trackBackground(Color body, double glowAlpha, boolean ring, Color ringColor) {
        boolean glow = glowAlpha > 0.001;
        BackgroundFill[] fills = new BackgroundFill[1 + (glow ? 1 : 0) + (ring ? 1 : 0)];
        CornerRadii r = new CornerRadii(999);
        int i = 0;
        if (ring) fills[i++] = new BackgroundFill(ringColor, r, new Insets(-3));
        if (glow) fills[i++] = new BackgroundFill(
                new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), glowAlpha), r, new Insets(-4));
        fills[i] = new BackgroundFill(body, r, Insets.EMPTY);
        return new Background(fills);
    }

    // ===== 图标 =====

    private void applyIconState(boolean on) {
        if (checkIcon == null) return;
        setIcon(checkIcon, on ? 1 : 0, on ? 1 : ICON_HIDDEN_SCALE);
        setIcon(closeIcon, on ? 0 : 1, on ? ICON_HIDDEN_SCALE : 1);
    }

    private static void setIcon(Node icon, double opacity, double scale) {
        icon.setOpacity(opacity);
        icon.setScaleX(scale);
        icon.setScaleY(scale);
    }

    /** 切换时两个图标交叉：出去的缩小淡出（0.2s），进来的放大淡入（0.3s） */
    private void crossFade(boolean on) {
        if (checkIcon == null) return;
        Node in = on ? checkIcon : closeIcon;
        Node out = on ? closeIcon : checkIcon;
        ParallelTransition anim = new ParallelTransition(
                fade(in, 1, ICON_IN, BOUNCE), scale(in, 1, ICON_IN, BOUNCE),
                fade(out, 0, ICON_OUT, SQUEEZE), scale(out, ICON_HIDDEN_SCALE, ICON_OUT, SQUEEZE));
        anim.play();
    }

    private static FadeTransition fade(Node node, double to, Duration d, Interpolator ip) {
        FadeTransition ft = new FadeTransition(d, node);
        ft.setToValue(to);
        ft.setInterpolator(ip);
        return ft;
    }

    private static ScaleTransition scale(Node node, double to, Duration d, Interpolator ip) {
        ScaleTransition st = new ScaleTransition(d, node);
        st.setToX(to);
        st.setToY(to);
        st.setInterpolator(ip);
        return st;
    }
}
