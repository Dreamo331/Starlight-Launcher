package com.example.starlight.newui.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * 顶部导航栏动效：弹性滑动指示条、页签水波纹、滚动收缩、图标微交互、通知红点。
 *
 * <p>对齐设计稿：
 * <ol>
 *   <li><b>弹性滑动指示条</b>：导航底部 2.5px 圆角条，蓝色渐变 + 辉光；位移 0.42s
 *       {@code cubic-bezier(0.34,1.56,0.64,1)}（弹性、慢），宽度 0.32s
 *       {@code cubic-bezier(0.4,0,0.2,1)}（均匀、快）—— 宽度先到、位置后到，
 *       切换过程中指示条被拉长/压缩，就是设计稿要的橡皮筋手感。
 *       位置取当前选中页签的 layoutX / layoutWidth（等价于网页里的 offsetLeft / offsetWidth）。</li>
 *   <li><b>页签水波纹</b>：点击处生成圆形，scale(0)→scale(2.4)，0.55s ease-out，播完移除；
 *       页签做圆角裁剪（等价于 position:relative + overflow:hidden）。</li>
 *   <li><b>滚动收缩</b>：内容滚动超过 20px 时进入 scrolled：高度 -10px（设计稿 64→54）、
 *       背景变实、出现投影，过渡 0.32s {@code cubic-bezier(0.4,0,0.2,1)}，不回弹。</li>
 *   <li><b>图标微交互</b>：页签图标 hover 放大 1.18 + 上移 1px（0.35s 弹性）；
 *       右侧图标按钮 hover 上移 1px + 放大 1.06、按下缩到 0.94 再回弹。</li>
 *   <li><b>通知红点</b>：8px 红点外扩光环呼吸（{@link #pulseDot(Region)}）。</li>
 * </ol>
 *
 * <p>装在 tabBar 上即可：页签选中态、显隐、容器宽高变化都会自动重算指示条。
 */
public final class NavEffects {

    // ===== 指示条（设计稿数值）=====
    private static final double INDICATOR_H = 2.5;
    private static final Duration MOVE = Duration.millis(420);
    private static final Duration WIDTH = Duration.millis(320);
    /** 位移：弹性曲线，带回弹 */
    private static final Interpolator ELASTIC = new CubicBezierInterpolator(0.34, 1.56, 0.64, 1);
    /** 宽度：均匀曲线，更快到位 */
    private static final Interpolator EVEN = new CubicBezierInterpolator(0.40, 0.0, 0.20, 1.0);

    // ===== 页签水波纹 =====
    private static final Duration TAB_RIPPLE = Duration.millis(550);
    private static final double TAB_RIPPLE_SCALE = 2.4;
    private static final Interpolator RIPPLE_EASE = new CubicBezierInterpolator(0.0, 0.0, 0.58, 1.0);

    // ===== 滚动收缩 =====
    private static final Duration SHRINK = Duration.millis(320);
    /** 收缩高度：设计稿 64 → 54 */
    private static final double SHRINK_PX = 10;
    /** 触发阈值：scrollY > 20 */
    private static final double SCROLL_THRESHOLD = 20;

    // ===== 图标微交互 =====
    private static final Duration TAB_ICON = Duration.millis(350);
    private static final Duration NAV_BTN = Duration.millis(300);
    private static final Duration LOGO = Duration.millis(400);
    private static final Interpolator MICRO = new CubicBezierInterpolator(0.34, 1.56, 0.64, 1);
    private static final Interpolator EVEN_MICRO = new CubicBezierInterpolator(0.40, 0.0, 0.20, 1.0);

    private static final Duration DOT_PULSE = Duration.seconds(2);
    private static final String TAB_ITEM = "tab-item";
    private static final String KEY = "starlight.navFx";

    /** 当前已装的导航栏动效（本启动器只有一个顶部导航栏） */
    private static NavEffects installed;

    private final HBox tabBar;
    private final Region indicator = new Region();
    private final Region bg = new Region();
    /** 指示条的宽度与位移：两个属性分开动画，才能分别用不同曲线与时长 */
    private final SimpleDoubleProperty indWidth = new SimpleDoubleProperty(0);
    private final SimpleDoubleProperty indX = new SimpleDoubleProperty(0);
    /** 收缩前的导航条高度，首次布局后记录 */
    private double baseHeight;
    private boolean scrolled;
    private boolean placed;
    /** 「量不到尺寸」时的重试次数，避免死循环 */
    private int retry;
    private static final int MAX_RETRY = 20;

    private NavEffects(HBox tabBar) {
        this.tabBar = tabBar;
    }

    /** 给顶部导航栏装动效（幂等） */
    public static void install(HBox tabBar) {
        if (tabBar == null || tabBar.getProperties().containsKey(KEY)) return;
        NavEffects fx = new NavEffects(tabBar);
        tabBar.getProperties().put(KEY, fx);
        fx.attach();
    }

    /**
     * 让导航栏跟随某个滚动面板收缩。
     *
     * <p>页面每次切页都会新建外层 ScrollPane（自带内部滚动区的页面没有外层），
     * 所以切页时对新面板调一次即可。
     */
    public static void watchScroll(ScrollPane scrollPane) {
        if (scrollPane == null) return;
        scrollPane.vvalueProperty().addListener((o, a, b) -> syncScroll(scrollPane));
        scrollPane.viewportBoundsProperty().addListener((o, a, b) -> syncScroll(scrollPane));
    }

    /** 滚动位置换算成像素：超过阈值进入 scrolled 状态 */
    private static void syncScroll(ScrollPane sp) {
        double content = sp.getContent() == null ? 0 : sp.getContent().getBoundsInLocal().getHeight();
        double view = sp.getViewportBounds() == null ? 0 : sp.getViewportBounds().getHeight();
        double offset = Math.max(0, content - view) * sp.getVvalue();
        if (installed != null) installed.applyScrolled(offset > SCROLL_THRESHOLD);
    }

    // ==================== 组装 ====================

    private void attach() {
        installed = this;

        // 背景层：垫在页签下面，收缩时淡入（更实的底色 + 投影）
        bg.getStyleClass().add("nav-bg");
        bg.setManaged(false);
        bg.setMouseTransparent(true);
        bg.setOpacity(0);
        tabBar.getChildren().add(0, bg);

        indicator.getStyleClass().add("nav-indicator");
        indicator.setManaged(false);
        indicator.setMouseTransparent(true);
        // 指示条是非托管的，父容器不会布局它，尺寸要自己 resize
        indWidth.addListener((o, a, b) -> indicator.resize(b.doubleValue(), INDICATOR_H));
        indX.addListener((o, a, b) -> indicator.setTranslateX(b.doubleValue()));
        indicator.resize(0, INDICATOR_H);
        tabBar.getChildren().add(indicator);

        tabBar.widthProperty().addListener((o, a, b) -> {
            syncBg();
            syncIndicator(false);
        });
        tabBar.heightProperty().addListener((o, a, b) -> {
            rememberBaseHeight();
            positionIndicator();
            syncBg();
        });
        tabBar.layoutBoundsProperty().addListener((o, a, b) -> {
            rememberBaseHeight();
            positionIndicator();
            syncBg();
            syncIndicator(false);
        });

        for (Node tab : tabBar.getChildren()) {
            if (!(tab instanceof Button btn) || !btn.getStyleClass().contains(TAB_ITEM)) continue;
            // 选中态是加/删 selected 样式类：监听它就能同时覆盖「点击切换」与「程序切页」
            btn.getStyleClass().addListener((ListChangeListener<String>) c -> syncIndicator(true));
            btn.visibleProperty().addListener((o, a, b) -> syncIndicator(false));
            btn.managedProperty().addListener((o, a, b) -> syncIndicator(false));
            // 页签的尺寸/位置变化（首次布局、窗口缩放）都要重算
            btn.widthProperty().addListener((o, a, b) -> syncIndicator(false));
            btn.layoutXProperty().addListener((o, a, b) -> syncIndicator(false));
            installTabInteractions(btn);
        }
    }

    /** 记录收缩前的导航条高度；只在还没记录时量，且不区分当前是否已收缩（否则首次收缩会因 0 直接返回并卡住） */
    private void rememberBaseHeight() {
        if (baseHeight <= 0 && tabBar.getHeight() > 0) baseHeight = tabBar.getHeight();
    }

    private void syncBg() {
        bg.resizeRelocate(0, 0, tabBar.getWidth(), tabBar.getHeight());
    }

    private void positionIndicator() {
        // 贴着导航底边，上面留 1px 给底部分隔线
        indicator.setLayoutY(Math.max(0, tabBar.getHeight() - INDICATOR_H - 1));
    }

    /**
     * 把指示条移到当前选中页签上：宽度 0.32s 均匀曲线、位移 0.42s 弹性曲线，两条并行。
     * 宽度先到位、位移后到，切换过程中指示条被拉长/压缩，形成橡皮筋手感。
     */
    private void syncIndicator(boolean animate) {
        Button selected = selectedTab();
        double targetW = selected == null ? 0 : selected.getWidth();
        if (selected == null || tabBar.getWidth() <= 0 || targetW <= 0) {
            // 容器「布局完成」的通知早于子节点拿到尺寸，首次同步常常量不到宽度。
            // 不补这一次重试的话，指示条要等到用户第一次点页签才会出现。
            retryLater();
            return;
        }
        double targetX = selected.getLayoutX();
        retry = 0;
        if (!placed || !animate) {
            indX.set(targetX);
            indWidth.set(targetW);
            placed = true;
            return;
        }
        new Timeline(new KeyFrame(WIDTH, new KeyValue(indWidth, targetW, EVEN))).play();
        new Timeline(new KeyFrame(MOVE, new KeyValue(indX, targetX, ELASTIC))).play();
    }

    /** 尺寸还没量到时排一次重试（最多几拍，成功即停） */
    private void retryLater() {
        if (retry > MAX_RETRY) return;
        retry++;
        javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(Duration.millis(32));
        wait.setOnFinished(e -> syncIndicator(false));
        wait.play();
    }

    private Button selectedTab() {
        for (Node n : tabBar.getChildren()) {
            if (n instanceof Button b && b.getStyleClass().contains(TAB_ITEM)
                    && b.getStyleClass().contains("selected") && b.isVisible() && b.isManaged()) {
                return b;
            }
        }
        return null;
    }

    /** 收缩：高度 -10px（设计稿 64→54）+ 背景层淡入；0.32s 均匀曲线，不回弹 */
    private void applyScrolled(boolean on) {
        if (scrolled == on) return;
        // 先量基准高度再改状态：反过来的话，第一次收缩时基准还没量到（比如导航栏早于安装就完成了布局），
        // 就会直接 return，而 scrolled 已经置位 —— 之后所有事件都被开头的相等判断挡掉，永远收缩不了。
        rememberBaseHeight();
        if (baseHeight <= 0) return;
        scrolled = on;
        double target = on ? baseHeight - SHRINK_PX : baseHeight;

        FadeTransition fade = new FadeTransition(SHRINK, bg);
        fade.setToValue(on ? 1 : 0);
        fade.setInterpolator(EVEN_MICRO);
        fade.play();

        Timeline shrink = new Timeline(new KeyFrame(SHRINK,
                new KeyValue(tabBar.minHeightProperty(), target, EVEN_MICRO),
                new KeyValue(tabBar.prefHeightProperty(), target, EVEN_MICRO),
                new KeyValue(tabBar.maxHeightProperty(), target, EVEN_MICRO)));
        shrink.play();
    }

    // ==================== 页签交互 ====================

    private void installTabInteractions(Button tab) {
        // 图标 hover：放大 1.18 + 上移 1px，带回弹
        Node icon = tab.getGraphic();
        if (icon != null) {
            icon.setMouseTransparent(true);
            tab.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> animateIcon(icon, true));
            tab.addEventHandler(MouseEvent.MOUSE_EXITED, e -> animateIcon(icon, false));
        }
        // 页签专用水波纹（通用按钮水波纹已跳过 .tab-bar 内的按钮）
        tab.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || tab.isDisabled()) return;
            playTabRipple(tab, e);
        });
    }

    private void animateIcon(Node icon, boolean on) {
        new Timeline(new KeyFrame(TAB_ICON,
                new KeyValue(icon.scaleXProperty(), on ? 1.18 : 1, MICRO),
                new KeyValue(icon.scaleYProperty(), on ? 1.18 : 1, MICRO),
                new KeyValue(icon.translateYProperty(), on ? -1 : 0, MICRO))).play();
    }

    /**
     * 页签水波纹：点击处圆形，scale(0)→scale(2.4)，0.55s ease-out，播完移除。
     *
     * <p>JavaFX 没有 DOM：页签是 Button，{@code Parent.getChildren()} 是 protected，
     * 所以这层叠在导航栏里、铺满该页签并做圆角裁剪，等价于给页签加
     * {@code position:relative + overflow:hidden}。
     * 起始圆直径取「页签对角线 / 2.4」，这样 2.4 倍正好覆盖整颗页签（比例仍是设计稿的 2.4）。
     */
    private void playTabRipple(Button tab, MouseEvent e) {
        Bounds lb = tab.getLayoutBounds();
        double w = lb.getWidth();
        double h = lb.getHeight();
        if (w <= 0 || h <= 0) return;
        double originX = tab.getLayoutX() + lb.getMinX();
        double originY = tab.getLayoutY() + lb.getMinY();

        Point2D hit = tabBar.sceneToLocal(e.getSceneX(), e.getSceneY());
        double x = hit.getX() - originX;
        double y = hit.getY() - originY;
        double base = Math.hypot(w, h) / TAB_RIPPLE_SCALE;

        Region ripple = new Region();
        ripple.getStyleClass().add("nav-tab-ripple");
        ripple.setManaged(false);
        ripple.setMouseTransparent(true);
        ripple.resize(base, base);
        ripple.relocate(x - base / 2, y - base / 2);
        ripple.setScaleX(0);
        ripple.setScaleY(0);

        Pane layer = new Pane(ripple);
        layer.setManaged(false);
        layer.setMouseTransparent(true);
        layer.resizeRelocate(originX, originY, w, h);
        Rectangle clip = new Rectangle(w, h);
        double radius = Math.max(0, Math.min(6, Math.min(w, h) / 2));
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);
        layer.setClip(clip);
        tabBar.getChildren().add(layer);

        ScaleTransition grow = new ScaleTransition(TAB_RIPPLE, ripple);
        grow.setToX(TAB_RIPPLE_SCALE);
        grow.setToY(TAB_RIPPLE_SCALE);
        grow.setInterpolator(RIPPLE_EASE);
        grow.setOnFinished(e2 -> tabBar.getChildren().remove(layer));
        grow.play();
    }

    // ==================== 其它导航控件的静态工具 ====================

    /** 右侧图标按钮：hover 上移 1px + 放大 1.06，按下缩到 0.94 再回弹（幂等） */
    public static void installNavButton(Node button) {
        if (!(button instanceof ButtonBase b)
                || Boolean.TRUE.equals(b.getProperties().get("starlight.navBtnFx"))) {
            return;
        }
        b.getProperties().put("starlight.navBtnFx", Boolean.TRUE);
        // 命中区取整个按钮方块：圆点按钮默认只按背景形状命中，实测只有很局部的一块点得中
        b.setPickOnBounds(true);
        b.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> animateNavButton(b, 1.06, -1));
        b.addEventHandler(MouseEvent.MOUSE_EXITED, e -> animateNavButton(b, 1, 0));
        b.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> animateNavButton(b, 0.94, 0));
        b.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> animateNavButton(b, b.isHover() ? 1.06 : 1,
                b.isHover() ? -1 : 0));
    }

    private static void animateNavButton(Node node, double scale, double translateY) {
        new Timeline(new KeyFrame(NAV_BTN,
                new KeyValue(node.scaleXProperty(), scale, MICRO),
                new KeyValue(node.scaleYProperty(), scale, MICRO),
                new KeyValue(node.translateYProperty(), translateY, MICRO))).play();
    }

    /** logo：hover 转 90° + 放大 1.08，0.4s 带回弹（幂等） */
    public static void installLogoHover(Node logo) {
        if (logo == null || Boolean.TRUE.equals(logo.getProperties().get("starlight.logoFx"))) return;
        logo.getProperties().put("starlight.logoFx", Boolean.TRUE);
        logo.setOnMouseEntered(e -> animateLogo(logo, true));
        logo.setOnMouseExited(e -> animateLogo(logo, false));
    }

    private static void animateLogo(Node logo, boolean on) {
        new Timeline(new KeyFrame(LOGO,
                new KeyValue(logo.rotateProperty(), on ? 90 : 0, MICRO),
                new KeyValue(logo.scaleXProperty(), on ? 1.08 : 1, MICRO),
                new KeyValue(logo.scaleYProperty(), on ? 1.08 : 1, MICRO))).play();
    }

    /**
     * 通知红点呼吸：8px 红点，光环从 0 外扩到 7px 并淡出，2s 一轮无限循环（幂等）。
     *
     * <p>设计稿用 box-shadow 动画；JavaFX 的等价做法是给红点挂 {@link DropShadow}，
     * 循环动画它的半径与颜色 —— 红点本身不动，只有光环在扩散淡出。
     */
    public static void pulseDot(Region dot) {
        if (dot == null || Boolean.TRUE.equals(dot.getProperties().get("starlight.dotFx"))) return;
        dot.getProperties().put("starlight.dotFx", Boolean.TRUE);
        dot.getStyleClass().add("nav-dot");
        DropShadow halo = new DropShadow();
        halo.setOffsetX(0);
        halo.setOffsetY(0);
        halo.setRadius(0);
        halo.setSpread(1);
        halo.setColor(Color.web("#ef4444", 0.45));
        dot.setEffect(halo);
        Timeline pulse = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(halo.radiusProperty(), 0),
                        new KeyValue(halo.colorProperty(), Color.web("#ef4444", 0.45))),
                new KeyFrame(DOT_PULSE,
                        new KeyValue(halo.radiusProperty(), 7, Interpolator.EASE_OUT),
                        new KeyValue(halo.colorProperty(), Color.web("#ef4444", 0.0), Interpolator.EASE_OUT)));
        pulse.setCycleCount(Timeline.INDEFINITE);
        pulse.play();
    }
}
