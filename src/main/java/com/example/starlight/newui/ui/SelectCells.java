package com.example.starlight.newui.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Callback;
import javafx.util.Duration;
import javafx.util.StringConverter;

/**
 * 下拉单选框的定制单元格：候选行的错位入场、勾选图标回弹、悬停指示条，以及值文本的换字动画。
 *
 * <p>对齐设计稿：
 * <ul>
 *   <li><b>候选错位入场</b>：每行从 opacity 0 / translateY(-6px) / scale(0.98) 进场到正常，
 *       延迟 {@code i × 28ms + 40ms}（i 是行序号）。</li>
 *   <li><b>勾选回弹</b>：选中行的勾选图标从 scale(0.4) 弹到 scale(1)，0.3s
 *       {@code cubic-bezier(0.34,1.56,0.64,1)}。</li>
 *   <li><b>悬停指示条</b>：行左侧 3px 竖条，悬停时 scaleY 0 → 1，0.2s
 *       {@code cubic-bezier(0.34,1.4,0.64,1)}。</li>
 *   <li><b>值文本换字</b>：选中新选项时旧文本淡出上移（0.15s），新文本下落淡入（0.2s，
 *       {@code cubic-bezier(0.34,1.4,0.64,1)}）。</li>
 * </ul>
 *
 * <p>只在下拉框<b>还没有自定义 cellFactory / buttonCell</b> 时装（{@link #install(ComboBox)} 内部判断），
 * 免得覆盖页面自己的渲染。文本一律走 ComboBox 的 StringConverter，显示效果与默认一致。
 */
public final class SelectCells {

    /** 错位入场的步进与基础延迟（设计稿 28ms / 40ms） */
    private static final Duration STAGGER_STEP = Duration.millis(28);
    private static final Duration STAGGER_BASE = Duration.millis(40);
    private static final Duration STAGGER = Duration.millis(220);
    /** 候选面板展开用（0.26s）与悬停条（0.2s）共用同一条回弹曲线 */
    private static final Interpolator BOUNCE = new CubicBezierInterpolator(0.34, 1.40, 0.64, 1);
    /** 勾选回弹（0.3s）用语 */
    private static final Interpolator SPRING = new CubicBezierInterpolator(0.34, 1.56, 0.64, 1);
    /** CSS 的 ease（换字淡出用） */
    private static final Interpolator EASE = new CubicBezierInterpolator(0.25, 0.10, 0.25, 1);

    private static final Duration SWAP_OUT = Duration.millis(150);
    private static final Duration SWAP_IN = Duration.millis(200);

    private SelectCells() {
    }

    /** 给下拉框换上下拉专用单元格；已有自定义 cellFactory/buttonCell 时不动它 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void install(ComboBox<?> combo) {
        if (combo == null) return;
        if (combo.getCellFactory() == null) {
            combo.setCellFactory((Callback) (Callback<ListView<Object>, ListCell<Object>>) lv -> new OptionCell(combo));
        }
        if (combo.getButtonCell() == null) {
            ((ComboBox) combo).setButtonCell(new ValueCell(combo));
        }
    }

    /** 候选行按序号错位入场（下拉面板每次展开时调一次） */
    public static void staggerIn(Node listView) {
        if (!(listView instanceof Parent parent)) return;
        for (Node node : parent.lookupAll(".list-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.isEmpty()) continue;
            int index = Math.max(0, cell.getIndex());
            cell.setOpacity(0);
            cell.setTranslateY(-6);
            cell.setScaleX(0.98);
            cell.setScaleY(0.98);
            Timeline in = new Timeline(new KeyFrame(STAGGER,
                    new KeyValue(cell.opacityProperty(), 1, BOUNCE),
                    new KeyValue(cell.translateYProperty(), 0, BOUNCE),
                    new KeyValue(cell.scaleXProperty(), 1, BOUNCE),
                    new KeyValue(cell.scaleYProperty(), 1, BOUNCE)));
            in.setDelay(STAGGER_BASE.add(STAGGER_STEP.multiply(index)));
            in.play();
        }
    }

    /** 取显示文本：优先用 ComboBox 的转换器，与默认单元格保持一致 */
    private static String displayText(ComboBox<?> combo, Object item) {
        if (item == null) return "";
        StringConverter<?> converter = combo.getConverter();
        if (converter != null) {
            try {
                return ((StringConverter<Object>) converter).toString(item);
            } catch (Exception ignored) {
                // 转换器不认这个类型就退回 toString
            }
        }
        return String.valueOf(item);
    }

    // ==================== 候选行 ====================

    /** 候选行：左侧 3px 悬停指示条 + 勾选图标 + 文本 */
    private static final class OptionCell extends ListCell<Object> {

        private final ComboBox<?> combo;
        private final Region bar = new Region();
        private final Region check = AppIcons.icon("check", 12, Color.web("#2563eb"));
        private final Label text = new Label();
        private final HBox row;

        /** 上一次的选中态，用来判断要不要播「勾选回弹」 */
        private boolean wasSelected;

        OptionCell(ComboBox<?> combo) {
            this.combo = combo;
            bar.getStyleClass().add("select-hover-bar");
            check.getStyleClass().add("select-check");
            check.setVisible(false);      // 默认不显示，只有选中行才显示
            text.getStyleClass().add("select-option-text");
            HBox.setHgrow(text, Priority.ALWAYS);
            row = new HBox(8, bar, check, text);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
            // 悬停指示条：scaleY 0 → 1
            addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> animateBar(true));
            addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> animateBar(false));
        }

        private void animateBar(boolean on) {
            new Timeline(new KeyFrame(Duration.millis(200),
                    new KeyValue(bar.scaleYProperty(), on ? 1 : 0, BOUNCE))).play();
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            setVisible(true);
            if (empty || item == null) {
                row.setVisible(false);
                row.setManaged(false);
                return;
            }
            row.setVisible(true);
            row.setManaged(true);
            text.setText(displayText(combo, item));
            updateCheck();
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            updateCheck();
        }

        /**
         * 勾选图标：选中时从 0.4 弹到 1，没选中隐藏。
         *
         * <p>注意别写成「状态没变就 return」——单元格是复用的，回收来的行可能还留着上一次的显示状态，
         * 必须每次 update 都把显隐落实（否则会看到每一行都挂着勾，实测就是这么被截图抓到的）。
         */
        private void updateCheck() {
            boolean selected = isSelected() && !isEmpty();
            boolean turnedOn = selected && !wasSelected;
            wasSelected = selected;
            if (!selected) {
                check.setVisible(false);
                return;
            }
            check.setVisible(true);
            if (turnedOn) {
                check.setScaleX(0.4);
                check.setScaleY(0.4);
                new Timeline(new KeyFrame(Duration.millis(300),
                        new KeyValue(check.scaleXProperty(), 1, SPRING),
                        new KeyValue(check.scaleYProperty(), 1, SPRING))).play();
            } else {
                check.setScaleX(1);
                check.setScaleY(1);
            }
        }
    }

    // ==================== 值单元格（触发器上的文本） ====================

    /** 值单元格：换选项时旧文本淡出上移、新文本下落淡入 */
    private static final class ValueCell extends ListCell<Object> {

        private final ComboBox<?> combo;
        private final Label text = new Label();
        /** 当前正在显示的文本（换字动画要先把旧文本演出去，所以自己记一份） */
        private String shown;

        ValueCell(ComboBox<?> combo) {
            this.combo = combo;
            text.getStyleClass().add("select-value-text");
            setText(null);
            setGraphic(text);
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            String next = empty ? "" : displayText(combo, item);
            if (next.equals(shown)) return;
            String previous = shown;
            shown = next;
            if (previous == null || previous.isEmpty() || next.isEmpty()) {
                text.setText(next);                     // 首次/清空：直接换，不做动画
                text.setOpacity(1);
                text.setTranslateY(0);
                return;
            }
            // 旧文本淡出上移（此刻 model 里已经是新值，所以先把旧文本写回去演完）
            text.setText(previous);
            FadeTransition out = new FadeTransition(SWAP_OUT, text);
            out.setToValue(0);
            out.setInterpolator(EASE);
            Timeline outMove = new Timeline(new KeyFrame(SWAP_OUT,
                    new KeyValue(text.translateYProperty(), -6, EASE)));
            ParallelTransition leave = new ParallelTransition(out, outMove);
            // 再换成新文本、从上方落下来
            PauseTransition swap = new PauseTransition(Duration.ZERO);
            swap.setOnFinished(e -> {
                text.setText(next);
                text.setTranslateY(-6);
            });
            Timeline inMove = new Timeline(new KeyFrame(SWAP_IN,
                    new KeyValue(text.opacityProperty(), 1, BOUNCE),
                    new KeyValue(text.translateYProperty(), 0, BOUNCE)));
            new SequentialTransition(leave, swap, inMove).play();
        }
    }
}
