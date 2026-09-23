package com.example.starlight.newui;

/**
 * 闪屏初始化进度 —— 后台初始化线程写，闪屏渲染循环每帧读。
 *
 * 刻意用 volatile 字段而不是 JavaFX 属性：写入方在后台线程（闪屏关闭时 FX
 * 工具箱可能尚未启动或已退出），读取方在 FX 线程的 AnimationTimer 里，
 * 轮询 + volatile 的可见性在这里已经足够，不需要跨线程的监听分发。
 */
public final class SplashProgress {

    private static volatile String message = "";
    private static volatile double fraction = 0;

    private SplashProgress() {}

    /** 后台线程调用：汇报当前初始化步骤与进度（fraction 取 0~1，只进不退） */
    public static void update(String msg, double frac) {
        message = msg;
        fraction = Math.max(fraction, Math.min(1.0, frac));
    }

    /** 每次启动开始前清零，避免读到上一次运行的残留状态 */
    public static void reset() {
        message = "";
        fraction = 0;
    }

    public static String getMessage() { return message; }

    public static double getFraction() { return fraction; }
}
