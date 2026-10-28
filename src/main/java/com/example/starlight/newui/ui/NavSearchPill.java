package com.example.starlight.newui.ui;


import com.example.starlight.util.I18N;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * 顶部导航栏左侧的搜索胶囊：收起时只有一颗放大镜图标，点一下展开成输入框，失焦自动收回。
 *
 * <p>本类只管胶囊自己的展开 / 收起动画，<b>不参与标签栏布局</b>：页签的位置由外层
 * （{@code LauncherView}）负责保持不动，胶囊只是贴在标签栏左侧的一层浮动控件。
 *
 * <p>展开宽度有两种：默认 {@link #setMaxExpandedWidth} 的上限，以及外层按
 * 「第一个页签左边界 - 留白」实时给出的上限——窗口缩到很窄、或者「模组」页签被打开时，
 * 胶囊也不会盖住导航按钮。
 *
 * <p>展开后右侧出现 Bing 官方标识，点击它等同于回车：关键词交给 {@link #setOnSubmit}
 * 注入的回调（目前是拉起系统浏览器搜索，见 {@code search.BingSearchUtil}）。
 *
 * <p>交互对齐设置页「游戏目录」的搜索胶囊（.gamedir-search）：失焦不立刻收起（留 150ms
 * 缓冲，避免点胶囊内部的按钮时闪收）、ESC 清空并收起。
 */
public final class NavSearchPill extends HBox {

    /** 收起宽度：左右各 7 留白 + 16 图标 */
    private static final double COLLAPSED_WIDTH = 30;
    /** 展开宽度默认上限：外层给的上限比它小时按上限走 */
    private static final double DEFAULT_EXPANDED_WIDTH = 220;
    /** 胶囊高度（也是圆角半径 / 裁剪弧长，保证是标准胶囊形） */
    private static final double HEIGHT = 30;
    /** 展开 / 收起时长与曲线，与 NavEffects 的均匀曲线同一档手感 */
    private static final Duration EXPAND = Duration.millis(280);
    private static final Duration COLLAPSE = Duration.millis(200);
    private static final Interpolator EASE = new CubicBezierInterpolator(0.40, 0.0, 0.20, 1.0);
    /** 失焦缓冲：点胶囊内部的控件会先丢一次焦点，立刻收起会闪 */
    private static final Duration BLUR_GRACE = Duration.millis(150);

    private final Label icon = new Label();
    private final TextField field = new TextField();
    /** 展开态右侧的 Bing 标识：点它等同于回车 */
    private final Button bingBtn = new Button();

    private boolean expanded;
    private PauseTransition collapseTimer;
    /** 展开宽度上限，由外层按页签左边界实时改写 */
    private double maxExpandedWidth = DEFAULT_EXPANDED_WIDTH;
    /** 回车提交回调（默认空实现，搜什么由外层决定） */
    private Consumer<String> onSubmit = keyword -> { };

    public NavSearchPill() {
        getStyleClass().add("nav-search");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);
        setPadding(new Insets(0, 7, 0, 7));
        // min/pref 一起变，实际宽度就等于 pref：max 设成 USE_PREF_SIZE，
        // 这样放进 StackPane 也不会被拉伸到整行宽（StackPane 会把子节点撑满可用区域）
        setMinWidth(COLLAPSED_WIDTH);
        setPrefWidth(COLLAPSED_WIDTH);
        setMaxWidth(Region.USE_PREF_SIZE);
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        setPickOnBounds(true);

        // 展开 / 收起时裁掉溢出的输入框文字，避免文字压出胶囊外（圆角 = 胶囊形）
        Rectangle clip = new Rectangle();
        clip.setArcWidth(HEIGHT);
        clip.setArcHeight(HEIGHT);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        icon.getStyleClass().add("nav-search-icon");
        icon.setMinSize(16, 16);
        icon.setPrefSize(16, 16);
        icon.setMaxSize(16, 16);
        icon.setMouseTransparent(true);
        AppIcons.apply(icon, "search", 16, null);

        field.getStyleClass().add("nav-search-input");
        field.setPromptText(I18N.get("ui.t0010"));
        field.setMinWidth(0);
        field.setPrefWidth(0);
        field.setMaxWidth(Double.MAX_VALUE);
        field.setOpacity(0);
        field.setMouseTransparent(true);
        // 收起时整颗不参与布局：HBox 也就不用为它留 spacing
        field.setManaged(false);
        HBox.setHgrow(field, Priority.ALWAYS);

        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                field.clear();
                collapse();
            }
        });
        field.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (focused) return;
            if (collapseTimer != null) collapseTimer.stop();
            collapseTimer = new PauseTransition(BLUR_GRACE);
            collapseTimer.setOnFinished(e -> {
                if (!field.isFocused()) collapse();
            });
            collapseTimer.play();
        });
        // TextField 的 onAction 就是回车
        field.setOnAction(e -> submit());

        // Bing 标识：展开后才出现，点它等同于回车。不抢焦点（否则会先触发失焦收起）
        bingBtn.getStyleClass().add("nav-search-bing");
        bingBtn.setFocusTraversable(false);
        bingBtn.setMinSize(18, 18);
        bingBtn.setPrefSize(18, 18);
        bingBtn.setMaxSize(18, 18);
        bingBtn.setGraphic(AppIcons.icon("bing", 16));
        bingBtn.setVisible(false);
        bingBtn.setManaged(false);
        // 直接提交，不要先 requestFocus：Button 的 fire 在 MOUSE_RELEASED，抢焦点会先触发一次失焦收起
        bingBtn.setOnAction(e -> submit());

        getChildren().addAll(icon, field, bingBtn);
        setCursor(Cursor.HAND);
        setOnMouseClicked(e -> {
            // Bing 标识自己处理点击：这一步必须放在展开判断之前。它的 onAction 先于 MOUSE_CLICKED 跑，
            // 等事件冒泡到这里时胶囊已经收起了，不提前 return 就会被下面这行重新展开。
            if (isInside(bingBtn, e.getTarget())) return;
            // 点在输入框 / 图标上时交给它们自己处理，否则一律展开
            if (expanded && isInside(field, e.getTarget())) return;
            if (expanded && isInside(icon, e.getTarget())) {
                getField().requestFocus();
                return;
            }
            expand();
        });
    }

    /** 提交当前关键词：非空才回调，然后收起胶囊 */
    private void submit() {
        String keyword = field.getText() == null ? "" : field.getText().trim();
        if (!keyword.isEmpty()) onSubmit.accept(keyword);
        collapse();
    }

    /** 回车提交回调：关键词已 trim，空串不会回调 */
    public void setOnSubmit(Consumer<String> handler) {
        this.onSubmit = handler == null ? keyword -> { } : handler;
    }

    /** 输入框（外层一般用不到，留给后续接真实搜索用） */
    public TextField getField() {
        return field;
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** 展开宽度上限：外层按「第一个页签左边界 - 留白」给，已展开时立即按新上限收回 */
    public void setMaxExpandedWidth(double width) {
        double clamped = Math.max(COLLAPSED_WIDTH, Math.min(DEFAULT_EXPANDED_WIDTH, width));
        if (Math.abs(clamped - maxExpandedWidth) < 1) return;
        maxExpandedWidth = clamped;
        if (expanded) animateWidth(maxExpandedWidth, EXPAND);
    }

    /** 展开（已展开时只把焦点交还输入框） */
    public void expand() {
        if (expanded) {
            field.requestFocus();
            return;
        }
        expanded = true;
        getStyleClass().add("expanded");
        setCursor(Cursor.TEXT);
        field.setManaged(true);
        field.setMouseTransparent(false);
        setBingVisible(true);
        animateWidth(maxExpandedWidth, EXPAND);

        FadeTransition fade = new FadeTransition(Duration.millis(180), field);
        fade.setToValue(1);
        fade.setDelay(Duration.millis(80));
        fade.play();

        // 等宽度动画先跑起来再抢焦点：立刻抢会让光标出现在还没展开的窄盒子里
        PauseTransition focus = new PauseTransition(Duration.millis(60));
        focus.setOnFinished(e -> {
            if (expanded && !field.isFocused()) field.requestFocus();
        });
        focus.play();
    }

    /** 收起（失焦 / ESC / 回车后）；关键词一并清空，不留隐藏状态 */
    public void collapse() {
        if (collapseTimer != null) collapseTimer.stop();
        if (!expanded) {
            if (!field.getText().isEmpty()) field.clear();
            return;
        }
        expanded = false;
        getStyleClass().remove("expanded");
        setCursor(Cursor.HAND);
        field.setMouseTransparent(true);
        field.clear();
        setBingVisible(false);

        FadeTransition fade = new FadeTransition(Duration.millis(120), field);
        fade.setToValue(0);
        fade.play();

        Timeline width = animateWidth(COLLAPSED_WIDTH, COLLAPSE);
        // 收起动画期间又展开了就别取消托管，否则输入框会消失
        width.setOnFinished(e -> {
            if (!expanded) field.setManaged(false);
        });
    }

    // 内部 //

    /** Bing 标识的显隐：不托管 = 收起时不占位，胶囊才能收回 30px */
    private void setBingVisible(boolean visible) {
        bingBtn.setVisible(visible);
        bingBtn.setManaged(visible);
    }

    private Timeline animateWidth(double target, Duration duration) {
        Timeline t = new Timeline(new KeyFrame(duration,
                new KeyValue(prefWidthProperty(), target, EASE)));
        t.play();
        return t;
    }

    /** 事件目标是某节点的后代（含自身）时返回 true */
    private static boolean isInside(Node root, Object target) {
        if (!(target instanceof Node node)) return false;
        for (Node cur = node; cur != null; cur = cur.getParent()) {
            if (cur == root) return true;
        }
        return false;
    }
}