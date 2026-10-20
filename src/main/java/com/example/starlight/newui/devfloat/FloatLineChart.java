/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui.devfloat;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * 轻量多序列折线图（Canvas 自绘，开发者悬浮窗专用）。
 *
 * <p>项目未引入 javafx.scene.chart（保持零依赖），启动器 UI 也以自绘为主，
 * 故悬浮窗曲线用 Canvas 直接画：每条序列按自身窗口内 min-max 归一化铺满绘图区
 * （各序列量纲不同：CPU 是百分比、磁盘是 MB/s），图例行显示各序列<b>最新实际值</b>。
 */
public final class FloatLineChart extends Canvas {

    /** 每条序列保留的点数（1s 采样 ≈ 3 分钟窗口） */
    public static final int MAX_POINTS = 180;

    private static final Font LEGEND_FONT = Font.font("Consolas", 10);
    private static final Color GRID_COLOR = Color.rgb(255, 255, 255, 0.08);
    private static final Color LEGEND_TEXT = Color.rgb(209, 213, 219);

    /** 单条曲线：名称 / 颜色 / 最新值格式化器 / 环形点缓冲 */
    private static final class Series {
        final String name;
        final Color color;
        final DoubleFunction<String> format;
        final Deque<Double> points = new ArrayDeque<>();
        double last = Double.NaN;

        Series(String name, Color color, DoubleFunction<String> format) {
            this.name = name;
            this.color = color;
            this.format = format;
        }
    }

    private final List<Series> series = new ArrayList<>();

    public FloatLineChart(double width, double height) {
        super(width, height);
        widthProperty().addListener((o, ov, nv) -> render());
        heightProperty().addListener((o, ov, nv) -> render());
    }

    /** 注册一条曲线（按调用顺序对应 {@link #push(double...)} 的第 i 个值） */
    public void addSeries(String name, Color color, DoubleFunction<String> format) {
        series.add(new Series(name, color, format));
    }

    /** 追加一帧采样（值的个数与 addSeries 次数一致；缺失的按 NaN 跳过） */
    public void push(double... values) {
        for (int i = 0; i < series.size(); i++) {
            Series s = series.get(i);
            double v = i < values.length ? values[i] : Double.NaN;
            s.last = v;
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                s.points.addLast(v);
                while (s.points.size() > MAX_POINTS) s.points.removeFirst();
            }
        }
        render();
    }

    /** 清空全部历史点（悬浮窗关闭重开时归零） */
    public void clearPoints() {
        for (Series s : series) {
            s.points.clear();
            s.last = Double.NaN;
        }
        render();
    }

    /** 重绘：网格 + 各序列归一化折线 + 底部图例（最新实际值） */
    public void render() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();
        g.clearRect(0, 0, w, h);
        if (w < 20 || h < 20) return;

        double padX = 8, legendH = 18;
        double plotX = padX, plotY = 8;
        double plotW = w - padX * 2, plotH = h - plotY - legendH - 4;

        // 网格：横 4 纵 6
        g.setStroke(GRID_COLOR);
        g.setLineWidth(1);
        for (int i = 0; i <= 4; i++) {
            double y = plotY + plotH * i / 4.0;
            g.strokeLine(plotX, y, plotX + plotW, y);
        }
        for (int i = 0; i <= 6; i++) {
            double x = plotX + plotW * i / 6.0;
            g.strokeLine(x, plotY, x, plotY + plotH);
        }

        for (Series s : series) {
            if (s.points.size() < 2) continue;
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double v : s.points) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
            double span = max - min;
            boolean flat = span < 1e-9;
            double lo = flat ? min - 1 : min - span * 0.1;
            double hi = flat ? max + 1 : max + span * 0.1;
            double range = hi - lo;

            g.setStroke(s.color);
            g.setLineWidth(1.6);
            double step = plotW / (MAX_POINTS - 1);
            int n = s.points.size();
            double startX = plotX + plotW - (n - 1) * step; // 最新点贴右缘
            int i = 0;
            double prevX = 0, prevY = 0;
            for (double v : s.points) {
                double x = startX + i * step;
                double y = plotY + plotH - (v - lo) / range * plotH;
                if (i == 0) {
                    g.beginPath();
                    g.moveTo(x, y);
                } else {
                    g.lineTo(x, y);
                }
                prevX = x;
                prevY = y;
                i++;
            }
            g.stroke();
            // 最新点小圆点
            g.fillOval(prevX - 2, prevY - 2, 4, 4);
        }

        // 图例：名称 + 最新实际值（等宽字体 10px 按每字符约 6.2px 估宽错行）
        g.setFont(LEGEND_FONT);
        double lx = plotX;
        double ly = h - legendH + 2;
        for (Series s : series) {
            g.setFill(s.color);
            g.fillRoundRect(lx, ly + 3, 8, 3, 2, 2);
            lx += 12;
            String text = s.name + " " + (Double.isNaN(s.last) ? "--" : s.format.apply(s.last));
            g.setFill(LEGEND_TEXT);
            g.fillText(text, lx, ly + 10);
            lx += 12 + text.length() * 6.2;
        }
    }
}
