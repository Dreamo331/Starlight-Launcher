package com.example.starlight.Controllers;

import javafx.scene.Node;

/**
 * 页面视图接口 —— 所有 FXML 控制器重构后需实现此接口，
 * 使其成为可独立调用的类，不再依赖 FXML 文件加载。
 */
public interface PageView {

    /**
     * 返回此页面构建好的根节点（Scene graph）
     */
    Node getView();

    /**
     * 页面被显示时回调（可选覆盖）
     */
    default void onPageShown() {}

    /**
     * 页面被隐藏时回调（可选覆盖）
     */
    default void onPageHidden() {}
}
