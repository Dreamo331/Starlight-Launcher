package com.example.starlight.util;

import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * 窗口拖拽工具类，为指定节点添加窗口拖拽功能
 */
public final class WindowDragUtil {

    private WindowDragUtil() {
        // 工具类不可实例化
    }

    /**
     * 为指定节点添加窗口拖拽功能
     *
     * @param node 可作为拖拽区域的节点（如导航栏、标题栏）
     */
    public static void makeDraggable(Node node) {
        final double[] offset = new double[]{0, 0};

        node.setOnMousePressed((MouseEvent event) -> {
            offset[0] = event.getSceneX();
            offset[1] = event.getSceneY();
        });

        node.setOnMouseDragged((MouseEvent event) -> {
            Stage stage = (Stage) node.getScene().getWindow();
            stage.setX(event.getScreenX() - offset[0]);
            stage.setY(event.getScreenY() - offset[1]);
        });
    }
}
