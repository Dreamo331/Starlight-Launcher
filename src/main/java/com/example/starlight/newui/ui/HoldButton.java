package com.example.starlight.newui.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * 长按按钮：按住满阈值才触发，按住期间红色填充从左往右推进。
 *
 * <p>对齐 {@code references/html/2.0.html} 的 {@code .hold-btn}：普通点击不响应，中途松手/移出立即回退。
 * 填充是独立 Region（由 CSS 的 {@code .hold-fill} 决定颜色），宽度由进度驱动，所以推进过程
 * 天然是从左到右展开，不需要旋转或裁剪技巧。按住期间根节点带 {@code :holding} 伪类，
 * 样式可据此把文字与边框一起变红。
 *
 * <p>做成 Region 而不是 {@link javafx.scene.control.Button}：Button 的背景由皮肤接管，
 * 没法在「背景之上、文字之下」插一层可动画的填充。
 */
public final class HoldButton extends StackPane {

    /** 按住中：样式类可用 {@code .hold-btn:holding} 同步变化（文字变红、边框变浅红） */
    private static final PseudoClass HOLDING = PseudoClass.getPseudoClass("holding");

    /** 填充推进的进度 0→1；宽度 = 按钮宽度 × 进度 */
    private final DoubleProperty progress = new SimpleDoubleProperty(0);

    private final Region fill = new Region();
    private final Label label = new Label();

    private final long holdMillis;
    private final Runnable onHold;

    private Timeline timeline;

    /**
     * @param text       按钮文字
     * @param holdMillis 触发所需的按住时长（毫秒），至少 200ms
     * @param onHold     长按达成后的回调
     */
    public HoldButton(String text, long holdMillis, Runnable onHold) {
        this.holdMillis = Math.max(200, holdMillis);
        this.onHold = onHold;

        getStyleClass().add("hold-btn");
        setFocusTraversable(true);

        fill.getStyleClass().add("hold-fill");
        fill.setMouseTransparent(true);
        // 宽度由进度驱动：min 0、max 锁在自身首选宽，否则 StackPane 会把它拉满整行
        fill.setMinWidth(0);
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        fill.setMaxHeight(Double.MAX_VALUE);
        fill.prefWidthProperty().bind(widthProperty().multiply(progress));
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);

        label.getStyleClass().add("hold-label");
        label.setText(text);
        StackPane.setAlignment(label, Pos.CENTER);

        getChildren().addAll(fill, label);

        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> startHold());
        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> cancelHold());
        addEventHandler(MouseEvent.MOUSE_EXITED, e -> cancelHold());
        addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
                e.consume();
                startHold();
            }
        });
        addEventHandler(javafx.scene.input.KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
                e.consume();
                cancelHold();
            }
        });
        focusedProperty().addListener((o, was, is) -> {
            if (!is) cancelHold();
        });
    }

    /** 运行期改文字（取消按钮的文案会随面板状态变化） */
    public void setText(String text) {
        label.setText(text);
    }

    public String getText() {
        return label.getText();
    }

    private void startHold() {
        if (timeline != null) return;
        progress.set(0);
        pseudoClassStateChanged(HOLDING, true);
        timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(progress, 0)),
                new KeyFrame(Duration.millis(holdMillis), new KeyValue(progress, 1)));
        timeline.setOnFinished(e -> {
            timeline = null;
            pseudoClassStateChanged(HOLDING, false);
            progress.set(0);
            if (onHold != null) onHold.run();
        });
        timeline.play();
    }

    private void cancelHold() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        progress.set(0);
        pseudoClassStateChanged(HOLDING, false);
    }
}