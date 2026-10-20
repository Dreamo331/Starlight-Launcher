package com.example.starlight.newui.ui;

import javafx.animation.Animation;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * 节日挂饰：只做 UI 控件层面的点缀（鼠标穿透、不挡点击）。
 *
 * <p>背景仍由用户壁纸 / 主题渐变 / 自定义色负责，本类不接管背景、不叠加遮罩。
 * 测试期为「中秋」一套固定装饰；接入时间检测后由调用方按节日选择。
 */
public final class FestivalDecorations {

    private FestivalDecorations() {
    }

    /** 顶部灯笼层：两盏小灯笼挂在窗口顶沿左右两侧（避开居中标签栏）。加到 rootPane 最上层。 */
    public static Pane topOverlay() {
        return topOverlay("#e35d3a", "#d0482f");
    }

    /** 顶部灯笼层（按节日配色）。加到 rootPane 最上层。 */
    public static Pane topOverlay(String lanternA, String lanternB) {
        Pane layer = new Pane();
        layer.setMouseTransparent(true);
        layer.setPickOnBounds(false);

        List<Animation> animations = new ArrayList<>();

        Node lantern1 = lantern(Color.web(lanternA), 0.82);
        lantern1.layoutXProperty().bind(layer.widthProperty().multiply(0.115).subtract(14));
        lantern1.setLayoutY(-4);
        Node lantern2 = lantern(Color.web(lanternB), 0.70);
        lantern2.layoutXProperty().bind(layer.widthProperty().multiply(0.885).subtract(14));
        lantern2.setLayoutY(-4);
        layer.getChildren().addAll(lantern1, lantern2);

        TranslateTransition sway1 = new TranslateTransition(Duration.seconds(2.8), lantern1);
        sway1.setFromX(-4);
        sway1.setToX(4);
        sway1.setAutoReverse(true);
        sway1.setCycleCount(Animation.INDEFINITE);
        animations.add(sway1);

        TranslateTransition sway2 = new TranslateTransition(Duration.seconds(3.1), lantern2);
        sway2.setFromX(4);
        sway2.setToX(-4);
        sway2.setAutoReverse(true);
        sway2.setCycleCount(Animation.INDEFINITE);
        animations.add(sway2);

        // 装饰层离场（被移出场景）时停掉动画，避免泄漏
        layer.sceneProperty().addListener((o, ov, nv) -> {
            if (nv == null) {
                for (Animation a : animations) a.stop();
            }
        });

        animations.forEach(Animation::play);
        return layer;
    }

    /** 一盏悬挂的灯笼（提绳 + 顶盖 + 灯身 + 底盖 + 流苏 + 坠珠），scale 控制大小 */
    private static Node lantern(Color bodyColor, double scale) {
        VBox lantern = new VBox();
        lantern.setAlignment(Pos.TOP_CENTER);
        lantern.setSpacing(0);

        Rectangle string = new Rectangle(1.5, 16);
        string.setFill(Color.web("#b45309"));

        Rectangle topCap = new Rectangle(13, 4);
        topCap.setArcWidth(3.5);
        topCap.setArcHeight(3.5);
        topCap.setFill(Color.web("#d97706"));

        Ellipse body = new Ellipse(16, 18);
        body.setFill(bodyColor);
        body.setStroke(Color.web("#fcd34d"));
        body.setStrokeWidth(1.2);
        body.setEffect(new DropShadow(16, Color.web("#f59e0b55")));

        Rectangle bottomCap = new Rectangle(9, 3.5);
        bottomCap.setArcWidth(2.5);
        bottomCap.setArcHeight(2.5);
        bottomCap.setFill(Color.web("#d97706"));

        Line tassel = new Line(0, 0, 0, 8);
        tassel.setStroke(Color.web("#b45309"));
        tassel.setStrokeWidth(1.2);

        lantern.getChildren().addAll(string, topCap, body, bottomCap, tassel);
        lantern.setScaleX(scale);
        lantern.setScaleY(scale);
        return lantern;
    }
}
