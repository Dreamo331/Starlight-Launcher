package com.example.starlight.newui.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * 可折叠容器：内容高度在「自动」与 0 之间过渡，并同步淡出。
 *
 * <p>对齐 {@code references/html/2.0.html} 的 {@code .collapsible}
 * （其用 {@code grid-template-rows: 1fr → 0fr} + {@code opacity} 做的同一效果）。
 * JavaFX 没有等价布局属性，这里改为动画 {@code prefHeight} 并裁剪溢出内容：
 * 展开完成时把 prefHeight 还原为自动计算，之后内容增高也能自然撑开。
 *
 * <p>折叠时内容置为 mouseTransparent，对应 HTML 的 {@code pointer-events: none}。
 */
public final class CollapsibleBox extends VBox {

    /** 高度过渡时长（对齐 HTML 的 grid-template-rows .5s） */
    private static final Duration HEIGHT_DURATION = Duration.millis(500);

    /** 透明度过渡时长（对齐 HTML 的 opacity .45s） */
    private static final Duration FADE_DURATION = Duration.millis(450);

    /** 与 HTML 的 cubic-bezier(.4, 0, .2, 1) 等价 */
    private static final Interpolator EASE = Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0);

    private final Node content;
    private final Rectangle clip = new Rectangle();

    private boolean collapsed;
    private Timeline timeline;

    public CollapsibleBox(Node content) {
        this.content = content;
        getStyleClass().add("collapsible-box");
        // 折叠到底必须真的能到 0：VBox 默认最小高度是子节点最小高度，不置 0 动画会被卡住
        setMinHeight(0);
        setMaxHeight(USE_PREF_SIZE);
        setFillWidth(true);
        getChildren().add(content);

        // 动画期间高度小于内容高度，靠裁剪避免内容溢出到相邻节点上
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** 展开 / 折叠（带过渡动画） */
    public void setCollapsed(boolean value) {
        setCollapsed(value, true);
    }

    /**
     * @param value    true = 折叠
     * @param animate  false 时立即到位（页面首次构建用，避免开场播放动画）
     */
    public void setCollapsed(boolean value, boolean animate) {
        if (value == collapsed && !(value && getPrefHeight() == 0)) return;
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        collapsed = value;
        content.setMouseTransparent(value);

        double target = value ? 0 : contentHeight();
        if (!animate) {
            setOpacity(value ? 0 : 1);
            setPrefHeight(value ? 0 : USE_COMPUTED_SIZE);
            return;
        }
        Timeline anim = new Timeline(
                new KeyFrame(HEIGHT_DURATION, new KeyValue(prefHeightProperty(), target, EASE)),
                new KeyFrame(FADE_DURATION, new KeyValue(opacityProperty(), value ? 0 : 1, EASE)));
        anim.setOnFinished(e -> {
            timeline = null;
            // 展开到位后交回自动高度：内容后续变化（换行、增删行）仍能撑开
            if (!collapsed) setPrefHeight(USE_COMPUTED_SIZE);
        });
        timeline = anim;
        anim.play();
    }

    /** 内容在当前宽度下的自然高度 */
    private double contentHeight() {
        double w = getWidth() > 0 ? getWidth() : -1;
        return Math.max(0, content.prefHeight(w));
    }
}