package com.example.starlight.newui.ui;

import javafx.animation.Interpolator;

/**
 * CSS {@code cubic-bezier(x1, y1, x2, y2)} 的 JavaFX 等价实现。
 *
 * <p>为什么不用 {@link Interpolator#SPLINE(double, double, double, double)}：
 * 它要求四个控制点坐标**全部**落在 [0,1]，而回弹类曲线（如
 * {@code cubic-bezier(0.34, 1.4, 0.64, 1)}）的 y1 必须大于 1，传进去直接抛
 * {@code IllegalArgumentException}。
 *
 * <p>解法与浏览器同源：给定 x 用牛顿迭代求出曲线参数 t（导数过小时退回二分），
 * 再代入求 y。y 允许超出 [0,1]，于是动画会先冲过终点再回弹到终点。
 */
public final class CubicBezierInterpolator extends Interpolator {

    /** 牛顿迭代的收敛阈值（与浏览器的 1e-6 一致） */
    private static final double EPSILON = 1e-6;
    /** 导数小于该值时牛顿迭代不可靠，改用二分 */
    private static final double MIN_SLOPE = 1e-6;

    private final double ax, bx, cx;
    private final double ay, by, cy;

    /**
     * @param x1 第一个控制点的 x，必须在 [0,1]（与 CSS 一致；否则曲线不是一个 x→y 的函数）
     * @param y1 第一个控制点的 y，**可以**大于 1，用于做回弹
     * @param x2 第二个控制点的 x，必须在 [0,1]
     * @param y2 第二个控制点的 y
     */
    public CubicBezierInterpolator(double x1, double y1, double x2, double y2) {
        if (x1 < 0 || x1 > 1 || x2 < 0 || x2 > 1) {
            throw new IllegalArgumentException(
                    "贝塞尔控制点的 x 必须在 [0,1]：x1=" + x1 + ", x2=" + x2);
        }
        // 起点 (0,0) 与终点 (1,1) 是隐含的，只存两个控制点，展开成多项式系数
        cx = 3 * x1;
        bx = 3 * (x2 - x1) - cx;
        ax = 1 - cx - bx;
        cy = 3 * y1;
        by = 3 * (y2 - y1) - cy;
        ay = 1 - cy - by;
    }

    @Override
    protected double curve(double t) {
        return sampleY(solveT(t));
    }

    private double sampleX(double t) {
        return ((ax * t + bx) * t + cx) * t;
    }

    private double sampleY(double t) {
        return ((ay * t + by) * t + cy) * t;
    }

    private double sampleSlope(double t) {
        return (3 * ax * t + 2 * bx) * t + cx;
    }

    /** 已知 x 求曲线参数 t */
    private double solveT(double x) {
        // 牛顿迭代：绝大多数点几次就收敛
        double t = x;
        for (int i = 0; i < 8; i++) {
            double dx = sampleX(t) - x;
            if (Math.abs(dx) < EPSILON) return t;
            double slope = sampleSlope(t);
            if (Math.abs(slope) < MIN_SLOPE) break;
            t -= dx / slope;
        }
        // 兜底：二分法，保证不会因为迭代跑飞而算出离谱的值
        double lo = 0;
        double hi = 1;
        t = Math.min(Math.max(x, lo), hi);
        while (lo < hi) {
            double dx = sampleX(t);
            if (Math.abs(dx - x) < EPSILON) return t;
            if (x > dx) lo = t;
            else hi = t;
            t = (hi - lo) * 0.5 + lo;
        }
        return t;
    }
}
