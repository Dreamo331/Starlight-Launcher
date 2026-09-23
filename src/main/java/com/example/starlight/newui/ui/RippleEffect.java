package com.example.starlight.newui.ui;

import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * 按钮水波纹：在点击位置生成一个圆形，从 scale(0) 扩散到覆盖整颗按钮，0.6s ease-out，结束自动移除。
 *
 * <p>JavaFX 侧的做法：点击时在按钮所在的父容器里叠一层铺满按钮的透明 {@link Pane}（带按钮圆角裁剪），
 * 层里放一个圆形 Region，用 {@link ScaleTransition} 从 0 缩放到 1，播完把这层整个摘掉。
 * 装一次即可覆盖整棵场景子树（场景级 MOUSE_PRESSED 过滤器往上找 {@link ButtonBase}），
 * 所以工程里各处 {@code new Button(...)} 都自动生效，不用改调用点。
 *
 * <h3>与设计稿的两点差异（都是 JavaFX 侧的限制/适配）</h3>
 * <ul>
 *   <li><b>水波纹颜色</b>：设计稿固定白色 0.35，但本工程大量按钮本身是白底（{@code .btn-primary}），
 *       白色水波纹在上面完全看不见。这里按按钮底色的亮度自动选档：深色底用白色 0.35，
 *       浅色底加 {@code .button-ripple.on-light}（两个颜色都写在 style.css 里，方便调）。</li>
 *   <li><b>圆角裁剪</b>：CSS 的 {@code overflow:hidden} 没有对应物；直接给按钮设 clip 会连按钮自己的
 *       投影一起裁掉，所以改用「父容器里叠一层带圆角 clip 的透明层」。
 *       另外 {@code Parent.getChildren()} 是 protected，这层只能挂到按钮所在的
 *       {@link Pane} 父容器上（{@code Pane.getChildren()} 才是 public）；父容器不是 Pane 时跳过。</li>
 * </ul>
 */
public final class RippleEffect {

    /** 扩散 + 回收的总时长（原来 0.6s 偏慢，收到 0.45s） */
    private static final Duration DURATION = Duration.millis(450);
    /** CSS 的 ease-out：cubic-bezier(0, 0, 0.58, 1) */
    private static final Interpolator EASE_OUT = new CubicBezierInterpolator(0.0, 0.0, 0.58, 1.0);
    /** 场景上标记「已装过」，避免重复注册过滤器 */
    private static final String KEY = "starlight.ripple.installed";
    /** 顶部导航栏用自己的页签水波纹（NavEffects），这里要放过它 */
    private static final String TAB_ITEM = "tab-item";
    private static final String TAB_BAR = "tab-bar";

    private RippleEffect() {
    }

    /** 在某个节点所属的场景上装水波纹（节点还没进场景时，等它进场景再装） */
    public static void install(Node anyNodeInScene) {
        if (anyNodeInScene == null) return;
        if (anyNodeInScene.getScene() != null) {
            hook(anyNodeInScene.getScene());
        } else {
            anyNodeInScene.sceneProperty().addListener((o, was, scene) -> {
                if (scene != null) hook(scene);
            });
        }
    }

    private static void hook(Scene scene) {
        if (Boolean.TRUE.equals(scene.getProperties().get(KEY))) return;
        scene.getProperties().put(KEY, Boolean.TRUE);
        // 用过滤器而不是处理器：在按钮自身的按下逻辑之前跑，水波纹先出现
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            ButtonBase button = findButton(e.getTarget());
            if (button == null || button.isDisabled()) return;
            // 顶部导航页签有自己专用的水波纹（NavEffects），不走这套通用按钮水波纹
            if (isNavTab(button)) return;
            play(button, e);
        });
    }

    /** 是否是顶部导航栏里的页签（含后代：页签的图标等） */
    private static boolean isNavTab(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains(TAB_ITEM) || n.getStyleClass().contains(TAB_BAR)) return true;
        }
        return false;
    }

    /** 从事件目标往上找最近的按钮 */
    private static ButtonBase findButton(Object target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof ButtonBase b) return b;
            node = node.getParent();
        }
        return null;
    }

    private static void play(ButtonBase button, MouseEvent e) {
        if (!(button.getParent() instanceof Pane parent)) return;
        // 用 layoutBounds + layoutX/Y（不含投影特效）定位：用 boundsInParent 的话，
        // 按钮 hover 投影会把包围盒撑大十来像素，水波纹层就会跟着偏移、尺寸也变大，看着「没贴在按钮上」。
        Bounds lb = button.getLayoutBounds();
        double w = lb.getWidth();
        double h = lb.getHeight();
        if (w <= 0 || h <= 0) return;
        double originX = button.getLayoutX() + lb.getMinX() + button.getTranslateX();
        double originY = button.getLayoutY() + lb.getMinY() + button.getTranslateY();

        // 点击点在父容器坐标系里的位置（事件的 x/y 相对事件目标，统一用场景坐标换算）
        Point2D hit = parent.sceneToLocal(e.getSceneX(), e.getSceneY());
        double x = hit.getX() - originX;
        double y = hit.getY() - originY;
        // 半径取「点击点到四个角」的最大距离，保证扩散后盖住整颗按钮
        double radius = Math.max(Math.hypot(x, y), Math.hypot(x - w, y));
        radius = Math.max(radius, Math.max(Math.hypot(x, y - h), Math.hypot(x - w, y - h)));
        if (radius <= 0) return;

        Region ripple = new Region();
        ripple.getStyleClass().add("button-ripple");
        if (isLightButton(button)) ripple.getStyleClass().add("on-light");
        ripple.setManaged(false);
        ripple.setMouseTransparent(true);
        ripple.resize(radius * 2, radius * 2);
        ripple.relocate(x - radius, y - radius);
        ripple.setScaleX(0);
        ripple.setScaleY(0);

        // 裁剪层：铺满按钮、带按钮自己的圆角，水波纹不会溢出圆角
        Pane layer = new Pane(ripple);
        layer.setManaged(false);
        layer.setMouseTransparent(true);
        layer.resizeRelocate(originX, originY, w, h);
        layer.setClip(roundedClip(button, w, h));
        parent.getChildren().add(layer);
        ensureFill(ripple);
        // 布局/滚动让按钮动起来时，水波纹层要跟着走，否则会「脱开」按钮
        trackTo(parent, layer, button);

        ScaleTransition grow = new ScaleTransition(DURATION, ripple);
        grow.setToX(1);
        grow.setToY(1);
        grow.setInterpolator(EASE_OUT);
        grow.setOnFinished(e2 -> parent.getChildren().remove(layer));
        grow.play();
    }

    /** 让水波纹层始终贴合按钮：按钮位置/尺寸一变就同步（最多跟到动画结束，层被移除后监听自动失效） */
    private static void trackTo(Pane parent, Pane layer, ButtonBase button) {
        Runnable sync = () -> {
            if (layer.getParent() != parent) return;
            Bounds lb = button.getLayoutBounds();
            layer.resizeRelocate(button.getLayoutX() + lb.getMinX() + button.getTranslateX(),
                    button.getLayoutY() + lb.getMinY() + button.getTranslateY(),
                    lb.getWidth(), lb.getHeight());
        };
        button.layoutXProperty().addListener((o, a, b) -> sync.run());
        button.layoutYProperty().addListener((o, a, b) -> sync.run());
        button.translateXProperty().addListener((o, a, b) -> sync.run());
        button.translateYProperty().addListener((o, a, b) -> sync.run());
        button.widthProperty().addListener((o, a, b) -> sync.run());
        button.heightProperty().addListener((o, a, b) -> sync.run());
    }

    /** 圆角矩形裁剪：半径取按钮背景自身的圆角（拿不到就按 6px 兜底） */
    private static Rectangle roundedClip(Region button, double w, double h) {
        double radius = 6;
        Background bg = button.getBackground();
        if (bg != null && !bg.getFills().isEmpty()) {
            CornerRadii radii = bg.getFills().get(0).getRadii();
            if (radii != null) {
                radius = radii.isUniform()
                        ? radii.getTopLeftHorizontalRadius()
                        : Math.max(radii.getTopLeftHorizontalRadius(), radii.getBottomRightHorizontalRadius());
            }
        }
        radius = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);
        return clip;
    }

    /** 底色偏亮就用深色水波纹（白色在浅底上看不见） */
    private static boolean isLightButton(Region button) {
        Background bg = button.getBackground();
        if (bg == null || bg.getFills().isEmpty()) return false;
        Paint fill = bg.getFills().get(0).getFill();
        if (!(fill instanceof Color c)) return false;
        double luminance = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        return luminance * c.getOpacity() > 0.6;
    }

    /**
     * 颜色写在 style.css 的 {@code .button-ripple} / {@code .button-ripple.on-light} 里（方便调），
     * 这里只确认它生效；万一没生效（节点还没进场景等）给个兜底色，不至于画出个透明圆。
     */
    private static void ensureFill(Region ripple) {
        ripple.applyCss();
        Background bg = ripple.getBackground();
        if (bg == null || bg.getFills().isEmpty()) {
            ripple.setBackground(new Background(new BackgroundFill(
                    Color.web("#ffffff", 0.35), new CornerRadii(999), Insets.EMPTY)));
        }
    }
}
