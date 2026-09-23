package com.example.starlight.newui.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * 右下角悬浮操作按钮（FAB）：一个 52px 蓝色渐变主按钮 + 展开时向左 / 上 / 右三个方向
 * 飞出的子按钮（上扇形排列：左上 / 正上 / 右上）；展开后主按钮显示叉号、点击叉号收起。
 *
 * <p>动画规格（与 CSS 设计稿一致）：
 * <ul>
 *   <li>主按钮：52px 圆形，蓝色渐变 #60a5fa → #2563eb，阴影 0 6px 20px rgba(59,130,246,.4)；
 *       展开时 list-todo 图标淡出、叉号（close）淡入，自身 rotate(135deg)；再点叉号收起。</li>
 *   <li>子按钮：初始 opacity 0、scale 0.4、缩在主按钮中心（translate 0,0）、不可点；
 *       展开时沿主按钮上方的扇形飞出：左上 translate(-52,-52) / 正上 (0,-72) /
 *       右上 (52,-52)（相邻圆心距 ≥52px 不重叠），带 0.02 / 0.06 / 0.10s 的错位延迟；
 *       收起时反向归位。</li>
 *   <li>全部过渡 0.4s cubic-bezier(0.34,1.56,0.64,1)（回弹，用
 *       {@link CubicBezierInterpolator} 实现，JavaFX 自带 SPLINE 不收 y&gt;1）。</li>
 *   <li>再次点击主按钮、或点击启动器其它任意位置收起。</li>
 * </ul>
 *
 * <h3>子按钮的可用性由宿主控制</h3>
 * {@link #setItemEnabled(int, boolean)}：子按钮对应的功能当前不可用（如没有下载任务、
 * 游戏没在跑）时整个收进主按钮后隐藏；三个都不可用时主按钮自身也隐藏；
 * <b>恰好只有一个可用时进入单按钮直显模式</b>：不显示主按钮、也没有展开动作，
 * 直接把该子按钮放大到主按钮尺寸（52px）摆在角落、点击直达功能。
 * 徽标（进行中的下载数）用 {@link #setBadge(String)}。
 *
 * <h3>右上子按钮与宿主 margin</h3>
 * 右上方位（translate x=+52）会探出窗口右缘约 26px：宿主（LauncherView）的 FAB
 * 右 margin 需 ≥ 54（自身 26 + 外探 28），否则右上子按钮被窗口裁掉。
 *
 * <h3>为什么子按钮坐标全用负 layout 偏移而不是 margin</h3>
 * 子按钮要「以主按钮中心为原点」向上方扇形飞出，StackPane 对齐做不到负向定位；
 * 用 {@code setLayoutX/Y} 手工摆位 + {@code setManaged(false)}，展开动画只改
 * translateX/Y 与 scale/opacity，与布局解耦（动画中布局重算不会打断它）。
 */
public final class FabMenu {

    /** 主按钮边长（设计稿 52px） */
    private static final double MAIN_SIZE = 52;
    /** 子按钮边长（比主按钮小一档，图标 18px） */
    private static final double ITEM_SIZE = 44;
    /** 展开的三个目标位移（左上 / 正上 / 右上，135°/90°/45° 方位、半径约 74） */
    private static final double[][] ITEM_POS = {{-52, -52}, {0, -72}, {52, -52}};
    /** 子按钮错位延迟（设计稿 0.02 / 0.06 / 0.10s） */
    private static final Duration[] ITEM_DELAY = {
            Duration.millis(20), Duration.millis(60), Duration.millis(100)};
    private static final Duration DUR = Duration.millis(400);
    /** 回弹曲线 cubic-bezier(0.34,1.56,0.64,1) */
    private static final CubicBezierInterpolator POP =
            new CubicBezierInterpolator(0.34, 1.56, 0.64, 1);

    private final StackPane box = new StackPane();
    private final Button mainBtn = new Button();
    /** 主按钮上叠放的两个图标：list-todo（收起态）与 close 叉号（展开态） */
    private Region frontIcon;
    private Region closeIcon;
    private final Button[] itemBtns = new Button[3];
    /** 各子按钮当前是否可用（不可用的收起后整体隐藏） */
    private final boolean[] itemEnabled = {false, false, false};
    /** 各子按钮的图标名（直显模式放大按钮时按同尺寸重建图形用） */
    private final String[] itemIcons = new String[3];
    private boolean expanded;
    /** 单按钮直显模式：只有一个可用子按钮时隐藏主按钮、直接显示该子按钮（点击直达，无展开态） */
    private boolean singleMode;
    private int singleIndex = -1;
    /** 进行中的动画：再次触发时先 stop，防止新旧两轮 Timeline 并发写同一属性 */
    private Timeline mainAnim;
    private final Timeline[] itemAnims = new Timeline[3];

    public FabMenu() {
        buildMain();
        for (int i = 0; i < 3; i++) buildItem(i);

        // StackPane 不给非受管节点摆位（交接缺陷 1）：等 box 首次布局定下尺寸，
        // 再把子按钮钉到主按钮中心；runLater 推迟到本轮布局走完、主按钮坐标就绪
        box.layoutBoundsProperty().addListener((o, ov, nv) ->
                Platform.runLater(this::pinItemsToMain));

        box.setPickOnBounds(false);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setVisible(false);
        box.getStyleClass().add("fab-menu");
        // 点击启动器其它任意位置收起：事件过滤器在捕获阶段先于页面控件拿到事件
        box.sceneProperty().addListener((o, ov, nv) -> {
            if (nv != null) nv.addEventFilter(MouseEvent.MOUSE_PRESSED, this::collapseOnOutside);
        });
    }

    /** 挂到 rootPane 右下角的节点 */
    public StackPane node() {
        return box;
    }

    /** 配置一个子按钮（0=左上 1=正上 2=右上）：图标、提示、点击动作（普通白底样式） */
    public void setItem(int index, String icon, String tooltip, Runnable action) {
        setItem(index, icon, tooltip, false, action);
    }

    /**
     * 配置一个子按钮并指定样式。
     *
     * @param danger true=危险操作（如关闭游戏进程）：红底白图标，与普通白底子按钮拉开辨识度；
     *               false=普通样式：白底、图标跟随文字色（浅色主题蓝图标 / 深色主题浅蓝图标）
     */
    public void setItem(int index, String icon, String tooltip, boolean danger, Runnable action) {
        Button btn = itemBtns[index];
        itemIcons[index] = icon;
        if (danger) {
            btn.getStyleClass().add("fab-item-danger");
            btn.setGraphic(AppIcons.icon(icon, 18, Color.WHITE));
        } else {
            // 白底上画白图标会隐形：普通样式让图标跟随 -fx-text-fill（白底→蓝、暗色底→浅蓝）
            AppIcons.apply(btn, icon, 18, null);
        }
        btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(e -> {
            collapse();
            action.run();
        });
    }

    /**
     * 子按钮可用性：不可用的子按钮收起后隐藏（没有对应功能时不出现在 FAB 里）；
     * 三个都不可用时整个 FAB 隐藏。
     */
    public void setItemEnabled(int index, boolean enabled) {
        if (itemEnabled[index] == enabled) return;
        itemEnabled[index] = enabled;
        boolean any = itemEnabled[0] || itemEnabled[1] || itemEnabled[2];
        if (!any && expanded) collapse();
        box.setVisible(any);
        Button btn = itemBtns[index];
        // 不可用的在收起状态下立即隐藏；展开状态下保持可见（点了就收起再消失）
        btn.setVisible(enabled || expanded);
        if (any) btn.toFront();
        updateMode();
    }

    /** 是否处于单按钮直显模式（只挂了一个可用子按钮，主按钮被隐藏） */
    public boolean isSingleMode() {
        return singleMode;
    }

    /** 子按钮当前应取的边长：直显模式的那个 = 主按钮 52px，其余 = 44px */
    private double itemSize(int i) {
        return singleMode && i == singleIndex ? MAIN_SIZE : ITEM_SIZE;
    }

    /** 按当前模式尺寸重建子按钮图形（直显 52px 配 22px 图标，普通 44px 配 18px） */
    private void refreshItemGraphic(int i) {
        Button btn = itemBtns[i];
        String name = itemIcons[i];
        if (name == null) return;
        boolean big = itemSize(i) == MAIN_SIZE;
        if (btn.getStyleClass().contains("fab-item-danger")) {
            btn.setGraphic(AppIcons.icon(name, big ? 22 : 18, Color.WHITE));
        } else {
            AppIcons.apply(btn, name, big ? 22 : 18, null);
        }
    }

    /**
     * 重算单按钮直显模式：恰好一个可用子按钮 → 隐藏主按钮，把该子按钮直接摆到
     * 主按钮位置（已由 {@link #pinItemsToMain()} 钉在主按钮中心），点击直达；
     * 恢复多个可用 → 回到「主按钮 + 展开」形态。切换时停掉进行中的动画并归零，
     * 避免残留展开态。主按钮只 setVisible 不改 managed，box 尺寸与徽标位置稳定。
     */
    private void updateMode() {
        int count = 0, idx = -1;
        for (int i = 0; i < 3; i++) if (itemEnabled[i]) { count++; idx = i; }
        boolean next = count == 1;
        if (next == singleMode && (!next || idx == singleIndex)) return;

        if (mainAnim != null) mainAnim.stop();
        expanded = false;
        mainBtn.setRotate(0);
        frontIcon.setOpacity(1);
        closeIcon.setOpacity(0);
        singleMode = next;
        singleIndex = next ? idx : -1;
        mainBtn.setVisible(!singleMode);
        for (int i = 0; i < 3; i++) {
            Timeline prev = itemAnims[i];
            if (prev != null) prev.stop();
            Button btn = itemBtns[i];
            boolean show = singleMode && i == idx;
            btn.setVisible(show);
            btn.setDisable(!show);
            btn.setOpacity(show ? 1 : 0);
            btn.setScaleX(show ? 1 : 0.4);
            btn.setScaleY(show ? 1 : 0.4);
            btn.setTranslateX(0);
            btn.setTranslateY(0);
            if (show) btn.toFront();
        }
        for (int i = 0; i < 3; i++) refreshItemGraphic(i);
        pinItemsToMain();   // 直显按钮放大到 52px 后按新尺寸重新钉位
    }

    /** 主按钮徽标（进行中下载数等）；null / 空清除 */
    public void setBadge(String text) {
        Label badge = (Label) box.lookup(".fab-badge");
        boolean show = text != null && !text.isBlank();
        if (badge == null) {
            if (!show) return;
            badge = new Label();
            badge.getStyleClass().add("fab-badge");
            StackPane.setAlignment(badge, Pos.TOP_RIGHT);
            StackPane.setMargin(badge, new javafx.geometry.Insets(-3, -3, 0, 0));
            box.getChildren().add(badge);
        }
        badge.setText(text);
        badge.setVisible(show);
        badge.toFront();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void collapse() {
        if (!expanded) return;
        expanded = false;
        animate(false);
    }

    private void buildMain() {
        frontIcon = AppIcons.icon("list-todo", 22, Color.WHITE);
        closeIcon = AppIcons.icon("close", 22, Color.WHITE);
        // 叉号自身预置 -135° 抵消主按钮展开的 +135°：X 转 135° 会变十字，反向补偿后叉号始终正对用户
        closeIcon.setRotate(-135);
        closeIcon.setOpacity(0);
        // 两个图标叠进同一个 mouseTransparent 的 graphic 容器，rotate 仍挂在主按钮上一起转
        StackPane iconHolder = new StackPane(frontIcon, closeIcon);
        mainBtn.setGraphic(iconHolder);
        mainBtn.getStyleClass().add("fab-main");
        mainBtn.setFocusTraversable(false);
        mainBtn.setOnAction(e -> toggle());
        box.getChildren().add(mainBtn);
        StackPane.setAlignment(mainBtn, Pos.BOTTOM_RIGHT);
    }

    private void buildItem(int i) {
        Button btn = new Button();
        btn.getStyleClass().add("fab-item");
        btn.setFocusTraversable(false);
        btn.setManaged(false);
        btn.setPrefSize(ITEM_SIZE, ITEM_SIZE);
        btn.setMinSize(ITEM_SIZE, ITEM_SIZE);
        btn.setMaxSize(ITEM_SIZE, ITEM_SIZE);
        btn.setOpacity(0);
        btn.setScaleX(0.4);
        btn.setScaleY(0.4);
        btn.setVisible(false);
        btn.setDisable(true);          // 初始 pointer-events: none
        itemBtns[i] = btn;
        box.getChildren().add(btn);
        pinItemsToMain();              // 主按钮已布局就立即钉位；还没布局由监听器/展开前补钉
    }

    private void toggle() {
        if (singleMode) return;   // 单按钮直显：没有展开态，点击直达由子按钮自己的 onAction 处理
        expanded = !expanded;
        animate(expanded);
    }

    /** 展开 / 收起动画：主按钮旋转 + 子按钮位移/缩放/透明度错位飞出 */
    private void animate(boolean open) {
        if (open) pinItemsToMain();   // 展开前确保子按钮基点仍在主按钮中心
        // 新一轮动画开始前先停旧一轮（交接缺陷 2）：否则两组 Timeline 并发写同一属性，
        // 上一轮收起的 onFinished 会在展开开始后又把按钮 setVisible(false)
        if (mainAnim != null) mainAnim.stop();
        // 主按钮旋转 135°（收起 0 → 展开 135）；同时 list-todo 淡出、叉号淡入
        mainAnim = new Timeline(new KeyFrame(DUR,
                new KeyValue(mainBtn.rotateProperty(), open ? 135 : 0, POP),
                new KeyValue(frontIcon.opacityProperty(), open ? 0 : 1, javafx.animation.Interpolator.EASE_BOTH),
                new KeyValue(closeIcon.opacityProperty(), open ? 1 : 0, javafx.animation.Interpolator.EASE_BOTH)));
        mainAnim.play();

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button btn = itemBtns[i];
            boolean fly = open && itemEnabled[i];
            if (open && !itemEnabled[i]) continue;   // 不可用的子按钮不参与展开
            if (!open && !btn.isVisible() && btn.getOpacity() == 0) continue;   // 本来就没出场的
            Timeline prev = itemAnims[idx];
            if (prev != null) prev.stop();           // 打断旧动画：从当前值继续，不会互相覆盖

            btn.setDisable(!open);                   // 展开才可点（pointer-events）
            if (open) btn.setVisible(true);
            double tx = fly ? ITEM_POS[i][0] : 0;
            double ty = fly ? ITEM_POS[i][1] : 0;
            Timeline t = new Timeline(new KeyFrame(DUR,
                    new KeyValue(btn.translateXProperty(), tx, POP),
                    new KeyValue(btn.translateYProperty(), ty, POP),
                    new KeyValue(btn.scaleXProperty(), open && fly ? 1 : 0.4, POP),
                    new KeyValue(btn.scaleYProperty(), open && fly ? 1 : 0.4, POP),
                    new KeyValue(btn.opacityProperty(), open ? 1 : 0, javafx.animation.Interpolator.EASE_BOTH)));
            // 错位延迟只在展开时用（收起大家一起回去，不等迟到者）
            if (open) t.setDelay(ITEM_DELAY[i]);
            t.setOnFinished(e -> {
                if (!open) {
                    btn.setVisible(false);
                    if (!itemEnabled[idx]) btn.setDisable(true);
                }
            });
            itemAnims[idx] = t;
            t.play();
        }
    }

    /**
     * 把非受管的子按钮钉到主按钮中心（等价设计稿 .fab-item 的
     * top:50%/left:50% + 半宽负 margin：初始 translate 0,0 即缩在主按钮中心）。
     * 动画只改 translate/scale/opacity，与基点解耦，重复钉位不影响进行中的动画。
     */
    private void pinItemsToMain() {
        if (mainBtn.getWidth() <= 0) return;   // 布局尚未计算：监听器 / 下次展开前会补钉
        for (int i = 0; i < itemBtns.length; i++) {
            double s = itemSize(i);
            Button btn = itemBtns[i];
            double x = mainBtn.getLayoutX() + (mainBtn.getWidth() - s) / 2.0;
            double y = mainBtn.getLayoutY() + (mainBtn.getHeight() - s) / 2.0;
            btn.resizeRelocate(x, y, s, s);
        }
    }

    /** 点击 FAB 之外的位置时收起（事件过滤器，捕获阶段） */
    private void collapseOnOutside(MouseEvent e) {
        if (!expanded) return;
        Node target = e.getTarget() instanceof Node n ? n : null;
        for (Node t = target; t != null; t = t.getParent()) {
            if (t == box) return;      // 点在自己身上：主按钮自己的 onAction 会处理
        }
        collapse();
    }}
