/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui.ui;

import com.example.starlight.newui.LauncherContext;

/**
 * 自定义主题色（背景颜色）。
 *
 * <p>设置 → 主题与背景 → <b>自定义背景色</b>：用户从调色盘选一个颜色，
 * 立即替换启动器背景（盖掉默认的浅/深渐变），并持久化到配置项 {@code CustomThemeColor}。
 *
 * <h3>与其它背景的关系（优先级从高到低）</h3>
 * <ol>
 *   <li><b>背景图片</b>（{@code BackgroundImage}）：有壁纸时壁纸在最底层，
 *       自定义色不参与，否则会把用户选的图片盖住；</li>
 *   <li><b>自定义背景色</b>（{@code CustomThemeColor}）：没有壁纸时生效；</li>
 *   <li><b>默认渐变</b>：两者都没设置时按当前主题用默认渐变。</li>
 * </ol>
 *
 * <h3>深浅主题下的处理</h3>
 * 自定义色是<b>单一颜色</b>，不随主题变化。但深色主题下背景若很亮，白色系文字会看不清，
 * 所以这里按当前主题给一层轻微遮罩（深色主题压暗、浅色主题提亮），
 * 与壁纸的可读性遮罩同一思路，保证前景文字对比度。
 */
public final class CustomThemeColor {

    /** 配置键：自定义背景色（形如 {@code #RRGGBB}；空串表示未启用） */
    public static final String KEY = "CustomThemeColor";

    /** 色层永远放到最底层（StackPane 里后添加的节点在上层），保证不遮挡任何内容 */
    private static final int LAYER_INDEX = 0;

    /** 记住「当前用于判定是否已挂载」的颜色值，避免重复添加节点 */
    private String appliedColor = "";
    private javafx.scene.layout.Region layer;
    /** 可读性遮罩层（叠在颜色层之上、内容之下） */
    private javafx.scene.layout.Region veil;

    private final LauncherContext host;

    public CustomThemeColor(LauncherContext host) {
        this.host = host;
    }

    /** 读取配置里的自定义颜色；未设置返回空串 */
    public String current() {
        String v = host.config() == null ? null : host.config().get(KEY);
        return v == null ? "" : v.trim();
    }

    /** 是否启用了自定义颜色 */
    public boolean isEnabled() {
        return isValid(current()) && !hasWallpaper();
    }

    /** 校验颜色字符串（只接受 {@code #RGB} / {@code #RRGGBB} 形式） */
    public static boolean isValid(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() != 3 && v.length() != 6) return false;
        for (int i = 0; i < v.length(); i++) {
            if (Character.digit(v.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** 归一化为 {@code #RRGGBB}（3 位简写会展开）；非法返回空串 */
    public static String normalize(String value) {
        if (!isValid(value)) return "";
        String v = value.trim();
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() == 3) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                sb.append(v.charAt(i)).append(v.charAt(i));
            }
            v = sb.toString();
        }
        return "#" + v.toUpperCase(java.util.Locale.ROOT);
    }

    /** 是否设置了背景图片（有壁纸时自定义色让位） */
    private boolean hasWallpaper() {
        String p = host.config() == null ? "" : host.config().getOrDefault("BackgroundImage", "");
        return p != null && !p.isBlank();
    }

    /**
     * 把自定义色应用到根节点（读取配置后调用）。
     *
     * <p>没有任何自定义色 / 有壁纸时，等价于「清除自定义层」，回落到默认渐变。
     */
    public void apply() {
        clearLayer();
        String color = normalize(current());
        if (color.isEmpty() || hasWallpaper()) return;
        if (host.rootPane() == null) return;

        layer = new javafx.scene.layout.Region();
        layer.setMouseTransparent(true);
        layer.prefWidthProperty().bind(host.rootPane().widthProperty());
        layer.prefHeightProperty().bind(host.rootPane().heightProperty());
        layer.setStyle("-fx-background-color: " + color
                + "; -fx-background-radius: 12;");

        // 先把颜色层放到最底层，再挂遮罩 —— 顺序很关键：
        // 若先挂遮罩再放颜色层，遮罩会落到内容之上，把所有界面压暗一层（实测踩中）。
        int index = Math.min(LAYER_INDEX, host.rootPane().getChildren().size());
        host.rootPane().getChildren().add(index, layer);
        // 可读性遮罩紧贴在颜色层之上、内容之下
        applyReadabilityOverlay();
        appliedColor = color;
    }

    /** 深浅主题切换后刷新可读性遮罩（不重建节点） */
    public void refreshReadability() {
        if (layer != null) applyReadabilityOverlay();
    }

    /**
     * 可读性遮罩：颜色层上叠一层半透明膜（深色主题压暗亮色、浅色主题提亮暗色），
     * 保证前景文字与背景的对比度。
     *
     * <p>实现方式：<b>两个 Region 叠起来</b>而不是把两条 {@code -fx-background-color} 用逗号拼接
     * ——实测 JavaFX 的 {@code background-color} <b>不支持</b>逗号分隔的多层填充，
     * 写了也只会取最后一段，遮罩会静默失效。
     */
    private void applyReadabilityOverlay() {
        if (layer == null) return;
        String color = normalize(current());
        if (color.isEmpty()) return;
        layer.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 12;");
        if (veil == null) {
            veil = new javafx.scene.layout.Region();
            veil.setMouseTransparent(true);
            veil.prefWidthProperty().bind(host.rootPane().widthProperty());
            veil.prefHeightProperty().bind(host.rootPane().heightProperty());
            host.rootPane().getChildren().add(1, veil);
        }
        boolean dark = "dark".equals(host.currentTheme());
        veil.setStyle(dark
                ? "-fx-background-color: rgba(0,0,0,0.22);"
                : "-fx-background-color: rgba(255,255,255,0.18);");
    }

    /** 移除自定义色层（含可读性遮罩） */
    private void clearLayer() {
        if (host.rootPane() != null) {
            if (layer != null) host.rootPane().getChildren().remove(layer);
            if (veil != null) host.rootPane().getChildren().remove(veil);
        }
        layer = null;
        veil = null;
        appliedColor = "";
    }

    /**
     * 设置并立即应用自定义色（同时持久化）。
     *
     * @param color {@code #RRGGBB} / {@code #RGB}；传空串表示恢复默认
     * @return 是否成功应用
     */
    public boolean setColor(String color) {
        String normalized = normalize(color);
        if (host.config() == null) return false;
        // 写入空串而非删除：saveClientConfig 是读-改-写合并，删除的键会保留旧值
        host.config().put(KEY, normalized);
        host.saveConfig();
        apply();
        return !normalized.isEmpty();
    }

    /** 恢复默认（清除自定义色，回到主题默认渐变） */
    public void reset() {
        setColor("");
    }
}
