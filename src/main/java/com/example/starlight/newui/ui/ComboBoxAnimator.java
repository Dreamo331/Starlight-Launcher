package com.example.starlight.newui.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;

/**
 * 下拉单选框的动态效果：箭头旋转 + 候选面板「弹下来」展开 / 干脆收起。
 *
 * <p>对应设计稿：
 * <ul>
 *   <li><b>箭头</b>：展开 rotate(180deg)、收起 rotate(0)，0.28s {@code cubic-bezier(0.34,1.4,0.64,1)}
 *       （第二个参数 1.4 带轻微过冲）；展开时颜色变 #3b82f6（写在 style.css 的
 *       {@code .combo-box:showing .arrow}，颜色同步不需要代码）。</li>
 *   <li><b>面板展开</b>：opacity 0.2s ease，transform 0.26s {@code cubic-bezier(0.34,1.4,0.64,1)} —— 
 *       从「上方 8px、缩到 0.96、透明」弹到位，支点为顶部居中。</li>
 *   <li><b>面板收起</b>：0.15s ease，不回弹（比展开更快更干脆）。</li>
 * </ul>
 *
 * <h3>JavaFX 侧的三点落地差异</h3>
 * <ol>
 *   <li>候选面板是<b>独立弹窗</b>（PopupWindow），不在触发器节点树里，动画只能作用在弹窗里的面板节点上。
 *       面板与触发器之间的 8px 间距由面板自身的 {@code translateY} 给（悬停/展开位 8px、收起位 0），
 *       <b>不要</b>去给弹窗场景根加内边距：{@code PopupWindow.updateWindow} 是按
 *       「场景根的 layoutBounds ∪ boundsInLocal（含投影特效）」来定窗口尺寸与位置、再把根节点反向平移补偿的，
 *       加内边距会让面板整体偏移、甚至压到触发器上。窗口本来就有阴影富余空间，面板在里面上下移 8px 不会被裁。</li>
 *   <li>JavaFX 关弹窗是即时的，要做 0.15s 收起动画必须在 {@code WINDOW_HIDING} 上 consume 拦一次，
 *       动画播完再真正 hide（放行第二次 HIDING）。</li>
 *   <li>CSS 的 {@code backdrop-filter: blur(10px)} 在 JavaFX 没有对应能力；面板底色
 *       {@code rgba(255,255,255,0.97)} 已接近不透明，视觉差异很小，故未实现。</li>
 * </ol>
 */
public final class ComboBoxAnimator {

    /** 箭头旋转（设计稿 0.28s） */
    private static final Duration ARROW = Duration.millis(280);
    /** 面板展开：位移/缩放 0.26s、透明度 0.2s */
    private static final Duration EXPAND_MOVE = Duration.millis(260);
    private static final Duration EXPAND_FADE = Duration.millis(200);
    /** 面板收起：统一 0.15s，不回弹 */
    private static final Duration COLLAPSE = Duration.millis(150);

    /** 展开用：带过冲的果冻曲线 cubic-bezier(0.34, 1.4, 0.64, 1) */
    private static final Interpolator BOUNCE = new CubicBezierInterpolator(0.34, 1.40, 0.64, 1);
    /**
     * 箭头专用：{@code cubic-bezier(0.34, 1.0, 0.64, 1)} —— 快进慢收、**不过冲**。
     * 箭头原来复用 BOUNCE（y1=1.4，过冲约 5%），转到 180° 时会先冲过约 9° 再弹回来，
     * 看起来就是「转过头了」；箭头这种角度动画用不过冲的曲线才是停在 180° 上。
     */
    private static final Interpolator ARROW_EASE = new CubicBezierInterpolator(0.34, 1.0, 0.64, 1);
    /** CSS 的 ease：cubic-bezier(0.25, 0.1, 0.25, 1) */
    private static final Interpolator EASE = new CubicBezierInterpolator(0.25, 0.10, 0.25, 1);

    /** 展开后的面板位置：相对触发器下沿再下移 8px（设计稿的面板间距就靠这个位移给） */
    private static final double REST_Y = 8;
    /** 收起态的面板位置：贴上触发器下沿（等价于设计稿的 translateY(-8px)） */
    private static final double HIDDEN_Y = 0;
    private static final double HIDDEN_SCALE = 0.96;
    /** 影子（面板快照）的圆角，与 .combo-box-popup > .list-view 的 12px 一致 */
    private static final double GHOST_RADIUS = 12;
    /** 会被锁住悬停外观的卡片类（与 CSS 里 .settings-card:hover 对应） */
    private static final String CARD_CLASS = "settings-card";
    private static final String LOCK_CLASS = "hover-locked";

    private static final String KEY = "starlight.comboFx.installed";
    private static final String PREPARED = "starlight.comboFx.prepared";

    private ComboBoxAnimator() {
    }

    /**
     * 在某个节点所属的场景上装下拉动效。
     *
     * <p>命中下拉框时按需准备（幂等）：鼠标点开与键盘展开两条路径都覆盖，
     * 所以工程里各处 new 出来的下拉框不用逐个改调用点。
     */
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
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> prepare(findCombo(e.getTarget())));
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> prepare(findCombo(e.getTarget())));
    }

    private static ComboBoxBase<?> findCombo(Object target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof ComboBoxBase<?> combo) return combo;
            node = node.getParent();
        }
        return null;
    }

    /** 给一个下拉框装上箭头旋转与面板进出场（重复调用无副作用） */
    public static void prepare(ComboBoxBase<?> combo) {
        if (combo == null || Boolean.TRUE.equals(combo.getProperties().get(PREPARED))) return;
        combo.getProperties().put(PREPARED, Boolean.TRUE);
        if (combo instanceof ComboBox<?> box) SelectCells.install(box);   // 候选行/值单元格的定制
        new Combo(combo).attach();
    }

    /** 单个下拉框的状态与动画 */
    private static final class Combo {

        private final ComboBoxBase<?> combo;
        private RotateTransition arrowAnim;
        private Timeline panelAnim;
        private Region panel;
        /** 可见面板本体（桥接节点下面的列表）：桥接节点自己没有 layout 尺寸，快照/定位都得用它 */
        private Node listView;
        private Scale panelScale;
        private PopupWindow popup;
        /** 面板宽度的兜底值（桥接节点自己没尺寸，量到一次就记住，避免支点退回 0） */
        private double pivotFallback;
        /** 收起动画用的影子（面板快照）缩放 */
        private Scale ghostScale;
        Combo(ComboBoxBase<?> combo) {
            this.combo = combo;
        }

        void attach() {
            combo.showingProperty().addListener((o, was, showing) -> {
                rotateArrow(showing);
                lockCardHover(showing);
                if (showing) {
                    expand();
                } else {
                    collapse();
                }
            });
        }

        /**
         * 下拉框展开期间，把它所在的卡片标记为「悬停中」。
         *
         * <p>候选面板是独立窗口，指针一移到面板上，主窗口里卡片的 {@code :hover} 就丢了 ——
         * 看起来就是「点开下拉框，卡片的高亮/上浮没了」。这里给祖先卡片挂 {@code hover-locked}，
         * CSS 里把它写成与 {@code :hover} 相同的外观；关闭时摘掉，之后鼠标一动自然恢复。
         */
        private void lockCardHover(boolean on) {
            for (Node n = combo.getParent(); n != null; n = n.getParent()) {
                if (!n.getStyleClass().contains(CARD_CLASS)) continue;
                if (on) {
                    if (!n.getStyleClass().contains(LOCK_CLASS)) n.getStyleClass().add(LOCK_CLASS);
                } else {
                    n.getStyleClass().remove(LOCK_CLASS);
                }
            }
        }

        /** 箭头展开转 180°、收起转回 0°：正好转到角度、不过冲，收起时从当前角度平滑转回 */
        private void rotateArrow(boolean showing) {
            Node arrow = combo.lookup(".arrow");
            if (arrow == null) return;
            if (arrowAnim != null) arrowAnim.stop();
            arrowAnim = new RotateTransition(ARROW, arrow);
            arrowAnim.setToAngle(showing ? 180 : 0);
            arrowAnim.setInterpolator(ARROW_EASE);
            arrowAnim.play();
        }

        /** 展开：面板从「上方 8px、缩到 0.96、透明」弹到位（起点归位，避免首帧闪一下） */
        private void expand() {
            if (!findPanel()) return;
            applyCollapsedState();
            if (panelAnim != null) panelAnim.stop();
            panelAnim = new Timeline(
                    new KeyFrame(EXPAND_MOVE,
                            new KeyValue(panel.translateYProperty(), REST_Y, BOUNCE),
                            new KeyValue(panelScale.xProperty(), 1, BOUNCE),
                            new KeyValue(panelScale.yProperty(), 1, BOUNCE)),
                    new KeyFrame(EXPAND_FADE, new KeyValue(panel.opacityProperty(), 1, EASE)));
            panelAnim.play();
            // 候选行按序号错位入场（等列表把行元件建出来再触发）
            if (listView != null) {
                javafx.application.Platform.runLater(() -> SelectCells.staggerIn(listView));
            }
        }

        /**
         * 收起：0.15s ease 淡出并缩回上方。
         *
         * <p>这里**不碰弹窗**：JavaFX 关弹窗是即时的，而 {@code WINDOW_HIDING} 不可取消
         * （JDK 源码 {@code Window.showingProperty().invalidated()} 里 fireEvent 之后不看 consumed
         * 就继续隐藏），所以「先拦下、播完动画再隐藏」做不到。
         * 之前的做法是把隐藏掉的弹窗按记录的位置重新显示出来演动画再关掉 —— 实测会让下拉框状态错乱：
         * 第一次关闭后第二次根本弹不出来，面板位置还可能整体偏掉。
         * 现在改成：弹窗照常立刻关闭，同时给面板拍一张快照，在主窗口里原位放一个「影子」把收起动画演完。
         */
        private void collapse() {
            if (panel == null) return;
            if (combo.isShowing()) return;              // 收起途中又被打开：交给 expand 收尾
            if (panelAnim != null) panelAnim.stop();
            Node ghost = buildGhost();
            if (ghost == null) return;
            panelAnim = new Timeline(new KeyFrame(COLLAPSE,
                    new KeyValue(ghost.opacityProperty(), 0, EASE),
                    new KeyValue(ghost.translateYProperty(), -8, EASE),
                    new KeyValue(ghostScale.xProperty(), HIDDEN_SCALE, EASE),
                    new KeyValue(ghostScale.yProperty(), HIDDEN_SCALE, EASE)));
            panelAnim.setOnFinished(e -> removeGhost(ghost));
            panelAnim.play();
        }

        /**
         * 面板快照影子：位置与尺寸取自面板当前的屏幕位置，放进主窗口后原位淡出缩回。
         * 圆角用 clip 裁剪，缩放支点放顶部居中，与面板本体一致。
         */
        private Node buildGhost() {
            javafx.scene.Scene mainScene = combo.getScene();
            if (mainScene == null || !(mainScene.getRoot() instanceof Pane host)) return null;
            // 只拍可见面板本身：默认快照会把投影外扩一起拍进来（实测 289x378，而可见框只有约 220x297），
            // 贴回去再按同样的框放大，关的瞬间面板会「变大」一下。
            // 量/拍「可见面板」（列表）：桥接节点自己的 layoutBounds 是 0x0，用它必然量不到东西
            Node visual = listView != null ? listView : panel;
            Bounds visible = visual.getLayoutBounds();
            if (visible.getWidth() <= 1 || visible.getHeight() <= 1) return null;
            Bounds screen = visual.localToScreen(visible);
            if (screen == null) return null;
            javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
            params.setViewport(new javafx.geometry.Rectangle2D(visible.getMinX(), visible.getMinY(),
                    visible.getWidth(), visible.getHeight()));
            javafx.scene.image.WritableImage shot;
            try {
                shot = visual.snapshot(params, null);
            } catch (Exception ex) {
                return null;                            // 快照失败就不演动画，别拖累关闭
            }
            if (shot == null) return null;
            javafx.scene.image.ImageView ghost = new javafx.scene.image.ImageView(shot);
            ghost.getStyleClass().add("combo-collapse-ghost");   // 便于排查/验收时定位这层影子
            Point2D at = host.screenToLocal(screen.getMinX(), screen.getMinY());
            ghost.setManaged(false);
            ghost.setMouseTransparent(true);
            ghost.setPreserveRatio(false);
            // 尺寸用可见框的逻辑尺寸（不是图片像素数），高分屏下也不会被放大
            ghost.resizeRelocate(at.getX(), at.getY(), visible.getWidth(), visible.getHeight());
            Rectangle clip = new Rectangle(visible.getWidth(), visible.getHeight());
            clip.setArcWidth(GHOST_RADIUS * 2);
            clip.setArcHeight(GHOST_RADIUS * 2);
            ghost.setClip(clip);
            ghostScale = new Scale(1, 1, visible.getWidth() / 2, 0);
            ghost.getTransforms().add(ghostScale);
            host.getChildren().add(ghost);
            return ghost;
        }

        private void removeGhost(Node ghost) {
            if (ghost.getParent() instanceof Pane host) host.getChildren().remove(ghost);
        }

        /** 归位到收起态；缩放支点用 transform 实现（节点的 scaleX/Y 是以中心为支点的，这里要顶部居中） */
        private void applyCollapsedState() {
            panel.setTranslateY(HIDDEN_Y);
            panel.setOpacity(0);
            refreshPivot();
            panelScale.setX(HIDDEN_SCALE);
            panelScale.setY(HIDDEN_SCALE);
        }

        /**
         * 刷新缩放的支点：始终取面板当前宽度的一半（顶部居中）。
         *
         * <p>必须每次重算，而且面板本身量不到宽度时要退到里面的列表上量：要动画的桥接节点
         * （CSSBridge）自己**没有尺寸**（宽度常年 0，真正撑开的是它里面的 ListView），
         * 支点算成 0 就变成以左边缘为轴缩放，看起来就是「面板往左边塌」。
         */
        private void refreshPivot() {
            double w = panel.getWidth();
            if (w <= 0) w = panel.prefWidth(-1);
            if (w <= 0) {
                Node list = panel.lookup(".list-view");
                if (list != null) w = list.getBoundsInLocal().getWidth();
            }
            if (w <= 0) {
                panel.applyCss();
                panel.layout();
                w = panel.getWidth() > 0 ? panel.getWidth() : panel.prefWidth(-1);
            }
            if (w > 0) pivotFallback = w;
            if (panelScale == null) {
                panelScale = new Scale(HIDDEN_SCALE, HIDDEN_SCALE, pivotFallback / 2, 0);
                panel.getTransforms().add(panelScale);
            } else {
                panelScale.setPivotX(pivotFallback / 2);
            }
        }

        /**
         * 认领这次弹出的面板与弹窗。
         *
         * <p>PopupControl 的 popup（以及 popupContent）在 JavaFX 里是包级私有，反射进 javafx.controls
         * 又要 --add-opens，所以改从 {@link Window#getWindows()} 里按「宿主窗口 + 面板节点样式类」匹配。
         * 注意弹窗场景根是内层 {@code Pane(root popup)}，真正承载候选列表、带 {@code combo-box-popup}
         * 样式类的是它下面的桥接节点（CSS 里的 {@code .combo-box-popup > .list-view} 就是从那里算起的）。
         */
        private boolean findPanel() {
            if (panel != null && popup != null && popup.isShowing()) return true;
            Window owner = combo.getScene() == null ? null : combo.getScene().getWindow();
            for (Window w : new ArrayList<>(Window.getWindows())) {
                if (!(w instanceof PopupWindow pw)) continue;
                if (owner != null && pw.getOwnerWindow() != owner) continue;
                Scene scene = pw.getScene();
                if (scene == null || scene.getRoot() == null) continue;
                Node bridge = scene.getRoot().lookup(".combo-box-popup");
                if (!(bridge instanceof Region bridgeRegion)) continue;
                popup = pw;
                panel = bridgeRegion;
                listView = bridgeRegion.lookup(".list-view");
                panelScale = null;
                // 注意：这里**不能**在隐藏时把面板归位（applyCollapsedState 会把 opacity 置 0），
                // 否则紧接着拍的收起影子就是一张空白图；归位交给下一次 expand() 开头做。
                return true;
            }
            return false;
        }

    }
}
