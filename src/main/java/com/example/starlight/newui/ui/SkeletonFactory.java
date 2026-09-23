package com.example.starlight.newui.ui;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * 骨架屏工厂：加载中的微光占位块、列表骨架、详情骨架。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运）：只表示「内容还在加载」，
 * 不改变组件尺寸、不加边框 / 阴影 / 旋转；样式类 {@code shimmer-block} /
 * {@code shimmer-sweep} / {@code settings-card} 与动画时长、光条比例均未改动。
 */
public final class SkeletonFactory {

    private SkeletonFactory() {
    }


    /** 微光扫过一次的时长（ms）；1~1.5s 最舒服，太快会变成频闪 */
    private static final int SHIMMER_PERIOD_MS = 1300;

    /** 微光条占占位块宽度的比例（20%~30%） */
    private static final double SHIMMER_SWEEP_RATIO = 0.28;

    /**
     * 骨架屏占位块：灰色圆角块 + 一道从左到右匀速扫过的柔光。
     *
     * <p>只表示「内容还在加载」，不改变组件尺寸、不加边框/阴影/旋转，也不做缩放或粒子效果。
     * 光条占块宽的 {@value #SHIMMER_SWEEP_RATIO}，线性循环 {@value #SHIMMER_PERIOD_MS} ms。
     *
     * <p>占位块被移出场景（内容加载完成、容器被清空）时动画会自动停止，不会留下空转的动画。
     */
    public static StackPane shimmerBlock(double width, double height) {
        double w = Math.max(8, width);
        double h = Math.max(6, height);
        StackPane box = new StackPane();
        box.setPrefSize(w, h);
        box.setMinSize(w, h);
        box.setMaxSize(w, h);
        box.getStyleClass().add("shimmer-block");

        // 圆角裁剪：光条扫过端点时不会溢出圆角
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        box.setClip(clip);

        double sweepW = Math.max(20, w * SHIMMER_SWEEP_RATIO);
        Region sweep = new Region();
        sweep.setPrefWidth(sweepW);
        sweep.setMinWidth(sweepW);
        sweep.setMaxWidth(sweepW);
        sweep.getStyleClass().add("shimmer-sweep");
        StackPane.setAlignment(sweep, Pos.CENTER_LEFT);
        box.getChildren().add(sweep);

        TranslateTransition tt = new TranslateTransition(Duration.millis(SHIMMER_PERIOD_MS), sweep);
        tt.setFromX(-sweepW);
        tt.setToX(w);
        tt.setInterpolator(Interpolator.LINEAR);
        tt.setCycleCount(Animation.INDEFINITE);
        tt.play();
        // 离开场景即停：加载完成后占位块会被移除，动画不应继续空转
        box.sceneProperty().addListener((o, ov, nv) -> {
            if (nv == null) {
                tt.stop();
            } else {
                tt.play();
            }
        });
        return box;
    }

    /**
     * 列表骨架屏：若干行「左侧方块 + 右侧两三根长短不一的文字条」，外形贴近真实的内容行。
     *
     * @param rows 行数
     */
    public static Node buildListSkeleton(int rows) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(4, 0, 0, 0));
        for (int i = 0; i < rows; i++) {
            HBox row = new HBox(14);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("settings-card");
            // 长短交替，看起来更像真实内容而不是一排等宽灰条
            double titleW = 150 + (i % 3) * 55;
            double descW = 260 + (i % 2) * 90;
            VBox lines = new VBox(8);
            lines.getChildren().addAll(
                    shimmerBlock(titleW, 14),
                    shimmerBlock(descW, 11),
                    shimmerBlock(120 + (i % 2) * 40, 9));
            HBox.setHgrow(lines, Priority.ALWAYS);
            row.getChildren().addAll(shimmerBlock(48, 48), lines);
            box.getChildren().add(row);
        }
        return box;
    }

    /** 详情页骨架屏：头部（大图标 + 标题 + 简介 + 统计）+ 下方标签与内容行 */
    public static Node buildDetailSkeleton() {
        VBox box = new VBox(16);
        box.setPadding(new Insets(4, 0, 0, 0));

        HBox head = new HBox(14);
        head.setAlignment(Pos.TOP_LEFT);
        VBox lines = new VBox(9);
        lines.getChildren().addAll(
                shimmerBlock(220, 20),
                shimmerBlock(420, 13),
                shimmerBlock(300, 11),
                shimmerBlock(180, 20));
        HBox.setHgrow(lines, Priority.ALWAYS);
        head.getChildren().addAll(shimmerBlock(64, 64), lines);
        box.getChildren().add(head);

        HBox tabs = new HBox(8);
        tabs.getChildren().addAll(shimmerBlock(76, 30), shimmerBlock(76, 30), shimmerBlock(110, 30));
        box.getChildren().add(tabs);

        VBox rows = new VBox(10);
        for (int i = 0; i < 3; i++) {
            rows.getChildren().addAll(buildListSkeleton(1));
        }
        box.getChildren().add(rows);
        return box;
    }
}
