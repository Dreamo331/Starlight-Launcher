package com.example.starlight.newui.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;

import java.util.ArrayList;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * 自定义表单控件的动效：单选框蓝点弹入、复选框白色勾描边画出、滑块滑块悬停/拖动状态。
 *
 * <p>对齐设计稿：
 * <ul>
 *   <li><b>单选</b>：选中时内圆点 scale(0) → scale(1)，0.3s {@code cubic-bezier(0.34,1.56,0.64,1)}
 *       （外圈边框变蓝 + 3px 外发光在 style.css 里）。</li>
 *   <li><b>复选</b>：白色勾用描边虚线画（{@code stroke-dashoffset} 20 → 0，0.3s ease，延迟 0.05s）；
 *       方块本身 0.3s {@code cubic-bezier(0.34,1.56,0.64,1)} 弹一下；按下时缩到 0.9。</li>
 *   <li><b>滑块</b>：滑块悬停 scale(1.18)（0.25s 同款回弹曲线），拖动 scale(1.35) 并加 8px 光晕。</li>
 * </ul>
 *
 * <h3>为什么按需准备而不是逐个改调用点</h3>
 * 场景级过滤器在首次交互（按下/悬停）时准备对应控件：单选/复选的动效都发生在状态变化的那一刻，
 * 而状态变化必然由交互触发，所以「交互前准备」时序上一定来得及；这样工程里各处 new 出来的控件都自动生效。
 * 静态外观（圆环、方块、轨道、滑块）全部由样式表负责，未准备前也是规格里的样子。
 *
 * <h3>复选框的勾为什么是叠出来的</h3>
 * JavaFX 的 {@code .mark} 是「填充形状」，没有描边，做不了 stroke-dashoffset 的描画效果；
 * 而 {@code Parent.getChildren()} 是 protected，不能往复选框里塞节点。所以勾是一根
 * {@link Polyline}，叠在复选框所在的 {@link Pane} 父容器上、对齐到方块位置（与 RippleEffect 同一套做法）。
 */
public final class FormControls {

    /** 单选蓝点弹入 / 复选方块回弹（同一根回弹曲线） */
    private static final Interpolator POP = new CubicBezierInterpolator(0.34, 1.56, 0.64, 1);
    /** 勾的描画用 CSS 的 ease */
    private static final Interpolator EASE = new CubicBezierInterpolator(0.25, 0.10, 0.25, 1);
    private static final Duration DOT_POP = Duration.millis(300);
    private static final Duration BOX_POP = Duration.millis(300);
    private static final Duration CHECK_DRAW = Duration.millis(300);
    /** 勾的绘制延迟（设计稿 0.05s） */
    private static final Duration CHECK_DELAY = Duration.millis(50);
    /** 描边虚线的初始偏移：20 = 完全隐藏（设计稿值） */
    private static final double DASH = 20;
    /** 勾的横向坐标范围（Polyline 点位范围，缩放以它为准） */
    private static final double CHECK_W = 10.4;
    /** 滑块悬停 / 拖动的缩放与时长 */
    private static final double THUMB_SIZE = 18;
    /** 自绘滑道的高度（设计稿 5px） */
    private static final double TRACK_H = 5;
    private static final double THUMB_HOVER = 1.18;
    private static final double THUMB_DRAG = 1.35;
    private static final Duration THUMB_ANIM = Duration.millis(250);

    private static final String RADIO = "starlight.fx.radio";
    private static final String CHECK = "starlight.fx.check";
    private static final String SLIDER = "starlight.fx.slider";
    private static final String KEY = "starlight.formControls.installed";
    /** 节点上正在跑的缩放动画（同一节点同时只允许一条） */
    private static final String SCALE_ANIM = "starlight.scaleAnim";

    private FormControls() {
    }

    /** 在当前场景上装表单控件动效（命中交互时按需准备，幂等） */
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
        // 交互时立刻准备（首次悬停/按下就生效，不用等巡检）
        for (javafx.event.EventType<MouseEvent> type :
                new javafx.event.EventType[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_ENTERED}) {
            scene.addEventFilter(type, e -> prepareUp(e.getTarget() instanceof Node n ? n : null));
        }
        // 再加一圈低频巡检：**预先选中的**复选框/单选如果不巡检就永远等不到交互，
        // 「已勾选却看不到勾」看着就像没勾上（实测截图抓到过）。
        javafx.animation.Timeline poll = new javafx.animation.Timeline(
                new KeyFrame(Duration.millis(600), e -> prepareAll(scene)));
        poll.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        poll.play();
        javafx.application.Platform.runLater(() -> prepareAll(scene));
    }

    /** 认领场景里所有还没准备的表单控件 */
    private static void prepareAll(Scene scene) {
        Parent root = scene.getRoot();
        if (root == null) return;
        for (Node n : new ArrayList<>(root.lookupAll(".radio-button"))) {
            if (n instanceof RadioButton radio) prepare(radio);
        }
        for (Node n : new ArrayList<>(root.lookupAll(".check-box"))) {
            if (n instanceof CheckBox check) prepare(check);
        }
        for (Node n : new ArrayList<>(root.lookupAll(".slider"))) {
            if (n instanceof Slider slider) prepare(slider);
        }
    }

    /**
     * 从事件目标往上找需要准备的表单控件。
     *
     * <p>注意要一直往上走到根：控件的内部节点本身就是 Pane（滑块的 thumb、复选框的 .box
     * 都是 StackPane），一旦「遇到 Pane 就停」会在第一层就退出，控件永远准备不到 ——
     * 实测点滑块/复选框全部不生效就是这个原因。
     */
    private static void prepareUp(Node node) {
        int depth = 0;
        for (Node n = node; n != null && depth++ < 16; n = n.getParent()) {
            if (n instanceof RadioButton radio) prepare(radio);
            else if (n instanceof CheckBox check) prepare(check);
            else if (n instanceof Slider slider) prepare(slider);
        }
    }

    // ==================== 单选框 ====================

    /** 单选框：蓝点 scale 0 → 1 弹入（幂等） */
    public static void prepare(RadioButton radio) {
        if (radio == null || Boolean.TRUE.equals(radio.getProperties().get(RADIO))) return;
        radio.getProperties().put(RADIO, Boolean.TRUE);
        Node dot = radio.lookup(".dot");
        if (dot == null) return;
        dot.setScaleX(radio.isSelected() ? 1 : 0);
        dot.setScaleY(radio.isSelected() ? 1 : 0);
        radio.selectedProperty().addListener((o, was, on) -> {
            if (on) {
                new Timeline(new KeyFrame(DOT_POP,
                        new KeyValue(dot.scaleXProperty(), 1, POP),
                        new KeyValue(dot.scaleYProperty(), 1, POP))).play();
            } else {
                dot.setScaleX(0);
                dot.setScaleY(0);
            }
        });
    }

    // ==================== 复选框 ====================

    /** 复选框：白色勾描边画出 + 方块回弹 + 按下缩到 0.9（幂等） */
    public static void prepare(CheckBox check) {
        if (check == null || Boolean.TRUE.equals(check.getProperties().get(CHECK))) return;
        // 拨动开关也是 CheckBox，但它不是方块复选：给它叠勾会画出一个巨大的白勾压在开关上（实测）
        if (check.getStyleClass().contains("toggle-switch")) return;
        check.getProperties().put(CHECK, Boolean.TRUE);
        Node box = check.lookup(".box");
        if (box == null) return;

        CheckMark mark = new CheckMark(check, box);
        check.selectedProperty().addListener((o, was, on) -> mark.sync(on, true));
        // 按下缩到 0.9，松开弹回；拖动离开也要还原
        check.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && !check.isDisabled()) {
                animateScale(box, 0.9, EASE);            // 按下缩到 0.9
            }
        });
        check.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> animateScale(box, 1, POP));
        check.addEventHandler(MouseEvent.MOUSE_EXITED, e -> animateScale(box, 1, POP));
        mark.sync(check.isSelected(), false);
    }

    /** 白色勾：描边虚线 20 → 0 画出，叠在复选框所在容器上、对齐方块 */
    private static final class CheckMark {

        private final CheckBox check;
        private final Node box;
        private final Polyline line = new Polyline(0, 5.2, 3.6, 8.8, 10.4, 1.6);
        private Pane host;
        private boolean placed;

        CheckMark(CheckBox check, Node box) {
            this.check = check;
            this.box = box;
            line.setStroke(Color.WHITE);
            line.setStrokeWidth(2);
            line.setStrokeLineCap(StrokeLineCap.ROUND);
            line.setStrokeLineJoin(StrokeLineJoin.ROUND);
            line.setFill(null);
            line.getStrokeDashArray().setAll(DASH);
            line.setStrokeDashOffset(DASH);           // 20 = 隐藏
            line.setManaged(false);
            line.setMouseTransparent(true);
            line.setVisible(false);
        }

        /** 与复选框状态同步；animated=false 时直接到位（用于首次准备） */
        void sync(boolean on, boolean animated) {
            if (on && !ensureHost()) return;
            if (on) {
                align();
                line.setVisible(true);
                if (!animated) {
                    line.setStrokeDashOffset(0);
                    return;
                }
                Timeline draw = new Timeline(new KeyFrame(CHECK_DRAW,
                        new KeyValue(line.strokeDashOffsetProperty(), 0, EASE)));
                draw.setDelay(CHECK_DELAY);           // 设计稿 0.05s
                draw.play();
            } else {
                line.setStrokeDashOffset(DASH);
                line.setVisible(false);
            }
        }

        /** 勾要叠在复选框所在的 Pane 上（Parent.getChildren 是 protected，塞不进复选框自己） */
        private boolean ensureHost() {
            if (placed && host != null) return true;
            if (!(check.getParent() instanceof Pane parent)) return false;
            host = parent;
            if (!host.getChildren().contains(line)) host.getChildren().add(line);
            // 跟着复选框的位置/尺寸走（布局变化后勾不能跑偏）
            check.layoutXProperty().addListener((o, a, b) -> align());
            check.layoutYProperty().addListener((o, a, b) -> align());
            check.widthProperty().addListener((o, a, b) -> align());
            check.heightProperty().addListener((o, a, b) -> align());
            // 选中光晕长出（bounds 20 → 26）发生在样式脉冲中途，当场 align 会量到
            // 过渡几何、之后 bounds 稳定不再触发（实测点选行 scale 卡在 1.19）；
            // 挪到脉冲后按最终几何对齐
            box.layoutBoundsProperty().addListener((o, a, b) ->
                    javafx.application.Platform.runLater(this::align));
            placed = true;
            return true;
        }

        /**
         * 把勾摆到方块正中：勾宽取方块实心区的 62%。
         *
         * <p>几何一律取 {@code layoutBounds}（= 实心方块，与选中态无关），
         * 不能用 {@code getBoundsInLocal}——它含选中光晕（实测 20 → 26），老算法
         * 因此在「预选行」（样式齐全后对齐）和「点选行」（光晕未长出时对齐）算出
         * 不同的勾大小（1.55 / 1.19）。摆位也不能按几何宽 CHECK_W 估——缩放绕勾线
         * layoutBounds 中心（含描边、minX 为负），老算法实测左偏 5.2×(1−scale) px，
         * 预选行偏 2.86px 就是用户看到的「第一次选中偏左」；这里按
         * 「可见中心 = 方块中心」反推位移，任何 scale 下都精确居中。
         */
        private void align() {
            if (host == null) return;
            Bounds boxBounds = host.sceneToLocal(box.localToScene(box.getLayoutBounds()));
            if (boxBounds.getWidth() <= 1 || boxBounds.getHeight() <= 1) return;
            double scale = (boxBounds.getWidth() * 0.62) / CHECK_W;
            line.setScaleX(scale);
            line.setScaleY(scale);
            Bounds lb = line.getLayoutBounds();
            line.setLayoutX(boxBounds.getCenterX() - (lb.getMinX() + lb.getWidth() / 2));
            line.setLayoutY(boxBounds.getCenterY() - (lb.getMinY() + lb.getHeight() / 2));
        }
    }

    private static void animateScale(Node node, double scale, Interpolator ip) {
        animateScale(node, scale, ip, BOX_POP);
    }

    // ==================== 滑块 ====================

    /** 滑块：悬停 1.18、拖动 1.35 + 8px 光晕，并自绘一条正常长度的 5px 滑道（幂等） */
    public static void prepare(Slider slider) {
        if (slider == null || Boolean.TRUE.equals(slider.getProperties().get(SLIDER))) return;
        slider.getProperties().put(SLIDER, Boolean.TRUE);
        // 皮肤那条轨道宽度离谱（实测 2220px，滑块本体才 240）且以自身中心摆放，会横穿整张卡片；
        // 样式表与代码的宽度上限都压不住它（见 SliderTrack 注释），所以它的外观在样式表里置空，
        // 改由 SliderTrack 在滑块所在容器上叠一条长度正常的 5px 滑道。
        new SliderTrack(slider).attach();
        Node thumb = slider.lookup(".thumb");
        if (thumb == null) return;
        boolean[] dragging = {false};
        thumb.addEventHandler(MouseEvent.MOUSE_ENTERED, e ->
                animateScale(thumb, dragging[0] ? THUMB_DRAG : THUMB_HOVER, POP, THUMB_ANIM));
        thumb.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (dragging[0]) return;
            animateScale(thumb, 1, POP, THUMB_ANIM);
        });
        thumb.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            dragging[0] = true;
            setClass(thumb, "dragging", true);
            animateScale(thumb, THUMB_DRAG, POP, THUMB_ANIM);
        });
        thumb.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            dragging[0] = false;
            setClass(thumb, "dragging", false);
            boolean stillHover = thumb.isHover();
            animateScale(thumb, stillHover ? THUMB_HOVER : 1, POP, THUMB_ANIM);
        });
    }

    /**
     * 自绘滑道：一条 5px 圆角轨道，叠在滑块所在容器上、对齐滑块本体。
     *
     * <p>为什么要自己画：Slider 皮肤把轨道 resize 成了两千多像素（实测 2220，滑块本体只有 240），
     * 并以自身中心摆放，于是滑道向两侧各探出近千像素、横穿整张卡片；而样式表的
     * {@code -fx-pref/max-width} 与代码里的 {@code setMaxWidth} 都被皮肤的后续布局绕过（实测均无效）。
     * 所以样式表把皮肤那条轨道置空，这里在父容器上补一条长度正常的（左右各让出半个滑块半径）。
     */
    private static final class SliderTrack {

        private final Slider slider;
        private final Region track = new Region();
        private Pane host;

        SliderTrack(Slider slider) {
            this.slider = slider;
        }

        void attach() {
            if (!(slider.getParent() instanceof Pane parent)) {
                slider.parentProperty().addListener((o, a, b) -> {
                    if (b instanceof Pane && track.getParent() == null) attach();
                });
                return;
            }
            host = parent;
            track.getStyleClass().add("slider-track-custom");
            track.setManaged(false);
            track.setMouseTransparent(true);
            host.getChildren().add(track);
            slider.layoutXProperty().addListener((o, a, b) -> sync());
            slider.layoutYProperty().addListener((o, a, b) -> sync());
            slider.widthProperty().addListener((o, a, b) -> sync());
            slider.heightProperty().addListener((o, a, b) -> sync());
            sync();
        }

        /**
         * 对齐滑块：横向让出半个滑块直径，纵向对齐圆点中心。
         * 不能按滑块总高居中——开着刻度标签时总高含下方刻度区（约 +16px），
         * 滑道会沉到刻度数字上（实测圆点中心 y=11、总高居中 y=18，差 7px）。
         */
        private void sync() {
            if (host == null || slider.getWidth() <= 0) return;
            double inset = THUMB_SIZE / 2;
            Point2D at = host.sceneToLocal(slider.localToScene(inset, 0));
            double w = Math.max(0, slider.getWidth() - THUMB_SIZE);
            double cy;
            Node thumb = slider.lookup(".thumb");
            if (thumb instanceof Region r && r.getWidth() > 0) {
                // 用 Region 矩形中心：圆环画的就是这个矩形；boundsInLocal/layoutBounds
                // 含投影特效（dropshadow offsetY=2 会把中心撑偏 2px），不能用
                cy = host.sceneToLocal(thumb.localToScene(r.getWidth() / 2, r.getHeight() / 2)).getY();
            } else {
                cy = at.getY() + slider.getHeight() / 2;
            }
            track.resizeRelocate(at.getX(), cy - TRACK_H / 2, w, TRACK_H);
        }
    }

    private static void setClass(Node node, String styleClass, boolean on) {
        if (on) {
            if (!node.getStyleClass().contains(styleClass)) node.getStyleClass().add(styleClass);
        } else {
            node.getStyleClass().remove(styleClass);
        }
    }

    /**
     * 缩放动画：开播前先停掉这个节点上一条缩放动画。
     *
     * <p>不停的话两条时间线会同时写 scaleX/Y，先开的那条虽然早开始、却会晚结束，
     * 于是把后开的那条的结果又覆盖回旧值（实测滑块「悬停放大」被上一条「松手还原」压掉，一直是 1.00）。
     */
    private static void animateScale(Node node, double scale, Interpolator ip, Duration duration) {
        Object prev = node.getProperties().get(SCALE_ANIM);
        if (prev instanceof Timeline t) t.stop();
        Timeline anim = new Timeline(new KeyFrame(duration,
                new KeyValue(node.scaleXProperty(), scale, ip),
                new KeyValue(node.scaleYProperty(), scale, ip)));
        node.getProperties().put(SCALE_ANIM, anim);
        anim.play();
    }
}
