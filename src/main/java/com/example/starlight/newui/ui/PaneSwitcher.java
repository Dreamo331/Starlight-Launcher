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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卡片壳：内部多个面板互斥显示，切换时做「高度过渡 + 目标面板淡入」。
 *
 * <p>对齐 {@code references/html/2.0.html} 的 {@code switchPane()}：
 * 先量出新面板的自然高度，把壳高从旧值过渡到新值（.5s cubic-bezier(.4,0,.2,1)），
 * 目标面板同时淡入（.4s，延迟 .08s），过渡结束后高度还原为自动计算。
 * 过渡期间用矩形裁剪，避免高面板溢出到下方控件上。
 *
 * <p>面板的 {@code managed/visible} 由本类统一管理，注册进来的面板不要自己改这两个属性。
 */
public final class PaneSwitcher extends VBox {

    private static final Duration HEIGHT_DURATION = Duration.millis(500);
    private static final Duration FADE_DURATION = Duration.millis(400);
    private static final Duration FADE_DELAY = Duration.millis(80);
    private static final Interpolator EASE = Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0);

    private final Map<String, Node> panes = new LinkedHashMap<>();
    private final Map<String, Runnable> onShow = new HashMap<>();
    private final Map<String, Runnable> onHide = new HashMap<>();

    private final Rectangle clip = new Rectangle();

    private String current;
    private Timeline heightAnim;
    private Timeline fadeAnim;

    public PaneSwitcher() {
        getStyleClass().add("pane-switcher");
        setMinHeight(0);
        setMaxHeight(USE_PREF_SIZE);
        setFillWidth(true);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        // 裁剪也按卡片圆角走（半径 12 → 弧直径 24）：否则行悬停底色、滚动渐隐这些方块内容
        // 会盖住卡片四角，滚到某些位置看起来就是「圆角消失」
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        setClip(clip);
    }

    /**
     * 注册一个面板。
     *
     * @param key    面板标识（切换时用）
     * @param pane   面板内容；初始 hidden/unmanaged 由本类处理
     * @param shown  切入该面板时回调（可空，例如微软面板据此开始取设备码）
     * @param hidden 切出该面板时回调（可空，例如微软面板据此停掉倒计时）
     */
    public void register(String key, Node pane, Runnable shown, Runnable hidden) {
        if (key == null || pane == null) return;
        pane.setVisible(false);
        pane.setManaged(false);
        pane.setOpacity(1);
        // 面板必须真的挂进壳里：只存 map 不加入子节点的话，壳高恒为 0（切换动画也就没有内容可显示）
        getChildren().add(pane);
        panes.put(key, pane);
        if (shown != null) onShow.put(key, shown);
        if (hidden != null) onHide.put(key, hidden);
    }

    /** 当前面板标识（未切换过时为 null） */
    public String current() {
        return current;
    }

    /** 带动画切换面板 */
    public void show(String key) {
        switchTo(key, true);
    }

    /** 立即切换（无动画），用于页面首次构建 */
    public void showImmediate(String key) {
        switchTo(key, false);
    }

    private void switchTo(String key, boolean animate) {
        Node target = panes.get(key);
        if (target == null || key.equals(current)) return;

        String previous = current;
        if (previous != null) {
            Runnable hide = onHide.get(previous);
            if (hide != null) hide.run();
        }
        if (heightAnim != null) heightAnim.stop();
        if (fadeAnim != null) fadeAnim.stop();

        double oldHeight = getHeight();
        for (Map.Entry<String, Node> entry : panes.entrySet()) {
            boolean isTarget = entry.getKey().equals(key);
            Node pane = entry.getValue();
            pane.setManaged(isTarget);
            pane.setVisible(isTarget);
            pane.setOpacity(isTarget ? 0 : 1);
        }
        current = key;

        // 量新高度：先交回自动高度，强制一次布局拿 prefHeight
        setPrefHeight(USE_COMPUTED_SIZE);
        applyCss();
        layout();
        double newHeight = prefHeight(-1);

        boolean canAnimate = animate && oldHeight > 0 && Math.abs(newHeight - oldHeight) > 0.5;
        if (canAnimate) {
            setPrefHeight(oldHeight);
            heightAnim = new Timeline(new KeyFrame(HEIGHT_DURATION,
                    new KeyValue(prefHeightProperty(), newHeight, EASE)));
            heightAnim.setOnFinished(e -> {
                heightAnim = null;
                setPrefHeight(USE_COMPUTED_SIZE);
            });
            heightAnim.play();

            fadeAnim = new Timeline(new KeyFrame(FADE_DURATION,
                    new KeyValue(target.opacityProperty(), 1, EASE)));
            fadeAnim.setDelay(FADE_DELAY);
            fadeAnim.setOnFinished(e -> fadeAnim = null);
            fadeAnim.play();
        } else {
            setPrefHeight(USE_COMPUTED_SIZE);
            target.setOpacity(1);
        }

        if (onShow.containsKey(key)) onShow.get(key).run();
    }
}