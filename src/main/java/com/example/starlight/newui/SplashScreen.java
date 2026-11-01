package com.example.starlight.newui;

import javafx.scene.Node;
import javafx.util.Duration;

/**
 * 启动动画（闪屏）的统一契约 —— 启动器只依赖本接口，不关心具体是哪一套动画。
 *
 * <p>为什么要抽接口：设置 → 主题与背景里的「启动动画」可以在两套动画之间切换
 * （{@link SplashView} 星轨动画 / {@link IconSplashView} 图标式开屏 —— 屏幕正中的一张图片），
 * 启动流程（后台初始化 → 最短播放时长 → 收尾 → 进入主界面）对两套完全一致，
 * 只有「画面长什么样」不同。抽出这三条方法后 {@code JavaFXLauncher} 不再需要
 * 用 {@code if} 区分类型。
 *
 * <p>生命周期（由启动器驱动）：
 * <ol>
 *   <li>{@link #getView()} 取根节点挂到 Scene 上并 show；</li>
 *   <li>启动器在后台初始化完成后调用 {@link #playAnimation(Duration)}，
 *       参数是最短播放时长的剩余量（保证动画不会一闪而过）；</li>
 *   <li>动画自己播完退场后回调 {@link #setOnFinished(Runnable)} 里注册的回调；</li>
 *   <li>启动器切到主界面前调用 {@link #stop()} 释放逐帧定时器。</li>
 * </ol>
 */
public interface SplashScreen {

    /** 闪屏根节点（挂在 Scene 上） */
    Node getView();

    /** 闪屏窗口宽度（用于居中定位，须与根节点首选宽度一致） */
    double width();

    /** 闪屏窗口高度（用于居中定位，须与根节点首选高度一致） */
    double height();

    /**
     * 播放动画，并在 {@code duration} 之后请求收尾（走满进度 → 退场 → 完成回调）。
     *
     * @param duration 收尾前至少再停留的时长（用于保证闪屏最低播放时长）；
     *                 为 0 / 负值时立即请求收尾
     */
    void playAnimation(Duration duration);

    /** 注册退场动画播完后的回调（进入主界面） */
    void setOnFinished(Runnable onFinished);

    /** 停止逐帧定时器（默认空实现：没有定时器的实现不必重写） */
    default void stop() { }
}
