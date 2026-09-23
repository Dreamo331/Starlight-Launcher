package com.example.starlight.newui.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.shape.StrokeType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 界面矢量图标注册表：用 SVG 图标替代原先散落在各页面的 emoji。
 *
 * <p>图标取自 <a href="https://lucide.dev/icons/">Lucide</a>（ISC 协议），
 * 24×24 视区、2px 描边、圆头圆角——与原 emoji 的视觉重量接近，且风格统一。
 * 路径数据由 Lucide 官方 SVG 转换而来：{@code <circle>/<rect>/<line>/<polyline>}
 * 已展开成等价的 path 数据（JavaFX 的 {@code SVGPath} 只认 {@code d} 属性）。
 *
 * <p>使用方式：
 * <ul>
 *   <li>{@link #icon(String, double, Paint)} —— 取纯图标节点（原 emoji 独占文案的场合）；</li>
 *   <li>{@link #apply(Labeled, String, double, Paint)} —— 给已有控件挂图标；</li>
 *   <li>{@link #button(String, String)} / {@link #label(String, String)} —— 图标 + 文字；</li>
 *   <li>{@link #setText(Labeled, String, String)} —— 运行期同时更新图标与文案。</li>
 * </ul>
 *
 * <p>要换图标，改 {@link #STROKE} / {@link #FILL} 里的路径即可；调用点无需改动。
 * 名字未登记时退化为「不显示图标」，不会抛异常。
 */
public final class AppIcons {

    private AppIcons() {
    }

    /** 描边路径：名字 → path 数据（Lucide 主体部分） */
    private static final Map<String, String> STROKE = new ConcurrentHashMap<>();

    /** 填充路径：少数图标内部有实心小块（如小圆点），为空表示无 */
    private static final Map<String, String> FILL = new ConcurrentHashMap<>();

    /** 图标视区边长：路径数据统一按 24×24 书写，渲染时按目标尺寸缩放 */
    private static final double VIEW_BOX = 24.0;

    /**
     * 品牌图标（GitHub / Gitee 等实心 logo）：名字 → path 数据。
     *
     * <p>与 {@link #STROKE} 的区别有两点：这些 logo 是**实心填充**的（不能描边），
     * 且各自坐标系大小不一（16 / 24 / 1024 都有）。所以缩放比例由路径自身的
     * 包围盒反推，不写死视区——换任何一份官方 SVG 都不用改代码。
     */
    private static final Map<String, String> BRAND = new ConcurrentHashMap<>();

    /** 描边宽度：Lucide 默认 2，缩放后随尺寸等比变化 */
    private static final double STROKE_WIDTH = 2.0;

    /** 未指定颜色时的默认填充（中性灰，深浅色主题下都可见） */
    private static final Paint DEFAULT_COLOR = Color.web("#64748b");

    private static void put(String name, String stroke) {
        STROKE.put(name, stroke);
    }

    private static void put(String name, String stroke, String fill) {
        STROKE.put(name, stroke);
        FILL.put(name, fill);
    }

    /**
     * 把 {@code src/main/resources/svg} 下的 SVG 文件登记成品牌图标。
     *
     * <p>只取第一个 {@code <path>} 的 {@code d} 属性——JavaFX 的 SVGPath 只认路径数据，
     * 不认 {@code viewBox}/{@code transform}/{@code fill}，颜色统一由调用方（跟随文字色）决定。
     * 文件缺失或解析失败时静默跳过，不影响启动。
     */
    private static void loadBrand(String name, String resource) {
        try (java.io.InputStream in = AppIcons.class.getResourceAsStream(resource)) {
            if (in == null) return;
            String svg = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<path[^>]*\\sd=\"([^\"]+)\"").matcher(svg);
            if (m.find()) BRAND.put(name, m.group(1));
        } catch (Exception ignored) {
            // 图标读不出来就不显示图标，不抛异常
        }
    }

    static {
        put("bug",
                "M12 20v-9M14 7a4 4 0 0 1 4 4v3a6 6 0 0 1-12 0v-3a4 4 0 0 1 4-4zM14.12 3.88 16 2M21 21a4 4 0 0 0-3.81-4M21 5a4 4 0 0 1-3.55 3.97M22 13h-4M3 21a4 4 0 0 1 3.81-4M3 5a4 4 0 0 0 3.55 3.97M6 13H2M8 2l1.88 1.88M9 7.13V6a3 3 0 1 1 6 0v1.13");
        put("close",
                "M18 6 6 18M6 6l12 12");
        put("check",
                "M20 6 9 17l-5-5");
        put("info",
                "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12zM12 16v-4M12 8h.01");
        put("error",
                "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12zM15 9l-6 6M9 9l6 6");
        put("warning",
                "M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3M12 9v4M12 17h.01");
        put("help",
                "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12zM9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3M12 17h.01");
        put("search",
                "M21 21l-4.34-4.34M3 11A8 8 0 1 0 19 11A8 8 0 1 0 3 11z");
        put("refresh",
                "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5");
        put("download",
                "M12 15V3M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5");
        put("upload",
                "M12 3v12M17 8l-5-5-5 5M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4");
        put("copy",
                "M10 8H20A2 2 0 0 1 22 10V20A2 2 0 0 1 20 22H10A2 2 0 0 1 8 20V10A2 2 0 0 1 10 8zM4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2");
        put("back",
                "M9 14 4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11");
        put("plus",
                "M5 12h14M12 5v14");
        put("minus",
                "M5 12h14");
        put("trash",
                "M10 11v6M14 11v6M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2");
        put("save",
                "M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7M7 3v4a1 1 0 0 0 1 1h7");
        put("edit",
                "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z");
        put("external-link",
                "M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6");
        put("log-out",
                "M16 17l5-5-5-5M21 12H9M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4");
        put("list",
                "M3 5h.01M3 12h.01M3 19h.01M8 5h13M8 12h13M8 19h13");
        put("sparkles",
                "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594zM20 2v4M22 4h-4M2 20A2 2 0 1 0 6 20A2 2 0 1 0 2 20z");
        put("arrow-left",
                "M15 18l-6-6 6-6");
        put("arrow-right",
                "M9 18l6-6-6-6");
        put("expand-more",
                "M6 9l6 6 6-6");
        put("expand-less",
                "M18 15l-6-6-6 6");
        put("bullet",
                "M11 12A1 1 0 1 0 13 12A1 1 0 1 0 11 12z");
        put("circle-outline",
                "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12z");
        put("circle-check",
                "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12zM16 9l-5.5 5.5L8 12");
        put("play",
                "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z");
        put("play-outline",
                "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z");
        put("pause",
                "M15 3H18A1 1 0 0 1 19 4V20A1 1 0 0 1 18 21H15A1 1 0 0 1 14 20V4A1 1 0 0 1 15 3zM6 3H9A1 1 0 0 1 10 4V20A1 1 0 0 1 9 21H6A1 1 0 0 1 5 20V4A1 1 0 0 1 6 3z");
        put("stop",
                "M5 3H19A2 2 0 0 1 21 5V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V5A2 2 0 0 1 5 3z");
        put("power",
                "M12 2v10M18.4 6.6a9 9 0 1 1-12.77.04");
        put("clapper",
                "M12.296 3.464l3.02 3.956M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3zM3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM6.18 5.276l3.1 3.899");
        put("bolt",
                "M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z");
        put("camera",
                "M13.997 4a2 2 0 0 1 1.76 1.05l.486.9A2 2 0 0 0 18.003 7H20a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h1.997a2 2 0 0 0 1.759-1.048l.489-.904A2 2 0 0 1 10.004 4zM9 13A3 3 0 1 0 15 13A3 3 0 1 0 9 13z");
        put("image",
                "M5 3H19A2 2 0 0 1 21 5V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V5A2 2 0 0 1 5 3zM7 9A2 2 0 1 0 11 9A2 2 0 1 0 7 9zM21 15l-3.086-3.086a2 2 0 0 0-2.828 0L6 21");
        put("avatar",
                "M7 8A5 5 0 1 0 17 8A5 5 0 1 0 7 8zM20 21a8 8 0 0 0-16 0");
        put("user",
                "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2M8 7A4 4 0 1 0 16 7A4 4 0 1 0 8 7z");
        put("user-cog",
                "M10 15H6a4 4 0 0 0-4 4v2M14.305 16.53l.923-.382M15.228 13.852l-.923-.383M16.852 12.228l-.383-.923M16.852 17.772l-.383.924M19.148 12.228l.383-.923M19.53 18.696l-.382-.924M20.772 13.852l.924-.383M20.772 16.148l.924.383M15 15A3 3 0 1 0 21 15A3 3 0 1 0 15 15zM5 7A4 4 0 1 0 13 7A4 4 0 1 0 5 7z");
        put("view",
                "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12z");
        put("heart",
                "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5");
        put("comment",
                "M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z");
        put("people",
                "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 3.128a4 4 0 0 1 0 7.744M22 21v-2a4 4 0 0 0-3-3.87M5 7A4 4 0 1 0 13 7A4 4 0 1 0 5 7z");
        put("people-online",
                "M18 21a8 8 0 0 0-16 0M5 8A5 5 0 1 0 15 8A5 5 0 1 0 5 8zM22 20c0-3.37-2-6.5-4-8a5 5 0 0 0-.45-8.3");
        put("hand",
                "M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15");
        put("memo",
                "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z");
        put("star",
                "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z");
        put("system-info",
                "M12 17v4M14.305 7.53l.923-.382M15.228 4.852l-.923-.383M16.852 3.228l-.383-.924M16.852 8.772l-.383.923M19.148 3.228l.383-.924M19.53 9.696l-.382-.924M20.772 4.852l.924-.383M20.772 7.148l.924.383M22 13v2a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7M8 21h8M15 6A3 3 0 1 0 21 6A3 3 0 1 0 15 6z");
        put("card",
                "M13 19a4 4 0 00-8 0M16 10h2M16 14h2M6 12A3 3 0 1 0 12 12A3 3 0 1 0 6 12zM4 5H20A2 2 0 0 1 22 7V17A2 2 0 0 1 20 19H4A2 2 0 0 1 2 17V7A2 2 0 0 1 4 5z");
        put("clock",
                "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12zM12 6v6l4 2");
        put("home",
                "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z");
        put("door",
                "M10 21H2M10 3H7a2 2 0 00-2 2v16M14 12h.01M19 21V5a2 2 0 00-1.675-1.974l-6.163-1.013A1 1 0 0010 3v18a1 1 0 001.124.992zM22 21h-3");
        put("link",
                "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71");
        put("plug",
                "M12 22v-5M15 8V2M17 8a1 1 0 0 1 1 1v4a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1zM9 8V2");
        put("globe",
                "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12zM12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20M2 12h20");
        put("antenna",
                "M2 12 7 2M7 12l5-10M12 12l5-10M17 12l5-10M4.5 7h15M12 16v6");
        put("signal",
                "M2 20h.01M7 20v-4M12 20v-8M17 20V8M22 4v16");
        put("rocket",
                "M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09M9 12a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.4 22.4 0 0 1-4 2zM9 12H4s.55-3.03 2-4c1.62-1.08 5 .05 5 .05");
        put("dice",
                "M4 10H12A2 2 0 0 1 14 12V20A2 2 0 0 1 12 22H4A2 2 0 0 1 2 20V12A2 2 0 0 1 4 10zM17.92 14l3.5-3.5a2.24 2.24 0 0 0 0-3l-5-4.92a2.24 2.24 0 0 0-3 0L10 6M6 18h.01M10 14h.01M15 6h.01M18 9h.01");
        put("key",
                "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z",
                "M16 7.5A0.5 0.5 0 1 0 17 7.5A0.5 0.5 0 1 0 16 7.5z");
        put("ticket",
                "M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2ZM13 5v2M13 17v2M13 11v2");
        put("window",
                "M4 4H20A2 2 0 0 1 22 6V18A2 2 0 0 1 20 20H4A2 2 0 0 1 2 18V6A2 2 0 0 1 4 4zM10 4v4M2 8h20M6 4v4");
        put("gamepad",
                "M6 11L10 11M8 9L8 13M15 12L15.01 12M18 10L18.01 10M17.32 5H6.68a4 4 0 0 0-3.978 3.59c-.006.052-.01.101-.017.152C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258-.007-.05-.011-.1-.017-.151A4 4 0 0 0 17.32 5z");
        put("wifi",
                "M12 20h.01M2 8.82a15 15 0 0 1 20 0M5 12.859a10 10 0 0 1 14 0M8.5 16.429a5 5 0 0 1 7 0");
        put("network",
                "M17 16H21A1 1 0 0 1 22 17V21A1 1 0 0 1 21 22H17A1 1 0 0 1 16 21V17A1 1 0 0 1 17 16zM3 16H7A1 1 0 0 1 8 17V21A1 1 0 0 1 7 22H3A1 1 0 0 1 2 21V17A1 1 0 0 1 3 16zM10 2H14A1 1 0 0 1 15 3V7A1 1 0 0 1 14 8H10A1 1 0 0 1 9 7V3A1 1 0 0 1 10 2zM5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3M12 12V8");
        put("version-select",
                "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83zM2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17");
        put("quick-tools",
                "M4 3H9A1 1 0 0 1 10 4V9A1 1 0 0 1 9 10H4A1 1 0 0 1 3 9V4A1 1 0 0 1 4 3zM15 3H20A1 1 0 0 1 21 4V9A1 1 0 0 1 20 10H15A1 1 0 0 1 14 9V4A1 1 0 0 1 15 3zM15 14H20A1 1 0 0 1 21 15V20A1 1 0 0 1 20 21H15A1 1 0 0 1 14 20V15A1 1 0 0 1 15 14zM4 14H9A1 1 0 0 1 10 15V20A1 1 0 0 1 9 21H4A1 1 0 0 1 3 20V15A1 1 0 0 1 4 14z");
        put("desktop",
                "M4 3H20A2 2 0 0 1 22 5V15A2 2 0 0 1 20 17H4A2 2 0 0 1 2 15V5A2 2 0 0 1 4 3zM8 21L16 21M12 17L12 21");
        put("memory",
                "M12 12v-2M12 18v-2M16 12v-2M16 18v-2M2 11h1.5M20 18v-2M20.5 11H22M4 18v-2M8 12v-2M8 18v-2M4 6H20A2 2 0 0 1 22 8V14A2 2 0 0 1 20 16H4A2 2 0 0 1 2 14V8A2 2 0 0 1 4 6z");
        put("cpu",
                "M12 20v2M12 2v2M17 20v2M17 2v2M2 12h2M2 17h2M2 7h2M20 12h2M20 17h2M20 7h2M7 20v2M7 2v2M6 4H18A2 2 0 0 1 20 6V18A2 2 0 0 1 18 20H6A2 2 0 0 1 4 18V6A2 2 0 0 1 6 4zM9 8H15A1 1 0 0 1 16 9V15A1 1 0 0 1 15 16H9A1 1 0 0 1 8 15V9A1 1 0 0 1 9 8z");
        put("hard-drive",
                "M10 16h.01M2.212 11.577a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 0 0-.212-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11zM21.946 12.013H2.054M6 16h.01");
        put("package",
                "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73zM12 22V12M3.29 7L12 12L20.71 7M7.5 4.27l9 5.15");
        put("puzzle",
                "M15.39 4.39a1 1 0 0 0 1.68-.474 2.5 2.5 0 1 1 3.014 3.015 1 1 0 0 0-.474 1.68l1.683 1.682a2.414 2.414 0 0 1 0 3.414L19.61 15.39a1 1 0 0 1-1.68-.474 2.5 2.5 0 1 0-3.014 3.015 1 1 0 0 1 .474 1.68l-1.683 1.682a2.414 2.414 0 0 1-3.414 0L8.61 19.61a1 1 0 0 0-1.68.474 2.5 2.5 0 1 1-3.014-3.015 1 1 0 0 0 .474-1.68l-1.683-1.682a2.414 2.414 0 0 1 0-3.414L4.39 8.61a1 1 0 0 1 1.68.474 2.5 2.5 0 1 0 3.014-3.015 1 1 0 0 1-.474-1.68l1.683-1.682a2.414 2.414 0 0 1 3.414 0z");
        put("folder",
                "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z");
        put("folder-open",
                "M6 14l1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2");
        put("library",
                "M16 6l4 14M12 6v14M8 8v12M4 4v16");
        put("file",
                "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2zM14 2v5a1 1 0 0 0 1 1h5M10 9H8M16 13H8M16 17H8");
        put("attach",
                "M16 6l-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551");
        put("send",
                "M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11zM21.854 2.147l-10.94 10.939");
        put("hourglass",
                "M5 22h14M5 2h14M17 22v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22M7 2v4.172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2");
        put("gear",
                "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12z");
        put("wrench",
                "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z");
        put("sliders",
                "M10 5H3M12 19H3M14 3v4M16 17v4M21 12h-9M21 19h-5M21 5h-7M8 10v4M8 12H3");
        put("spool",
                "M17 13.44 4.442 17.082A2 2 0 0 0 4.982 21H19a2 2 0 0 0 .558-3.921l-1.115-.32A2 2 0 0 1 17 14.837V7.66M7 10.56l12.558-3.642A2 2 0 0 0 19.018 3H5a2 2 0 0 0-.558 3.921l1.115.32A2 2 0 0 1 7 9.163v7.178");
        put("palette",
                "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z",
                "M13 6.5A0.5 0.5 0 1 0 14 6.5A0.5 0.5 0 1 0 13 6.5zM17 10.5A0.5 0.5 0 1 0 18 10.5A0.5 0.5 0 1 0 17 10.5zM6 12.5A0.5 0.5 0 1 0 7 12.5A0.5 0.5 0 1 0 6 12.5zM8 7.5A0.5 0.5 0 1 0 9 7.5A0.5 0.5 0 1 0 8 7.5z");
        put("languages",
                "M5 8l6 6M4 14l6-6 2-3M2 5h12M7 2h1M22 22l-5-10-5 10M14 18h6");
        put("accessibility",
                "M15 4A1 1 0 1 0 17 4A1 1 0 1 0 15 4zM18 19l1-7-6 1M5 8l3-3 5.5 3-2.36 3.5M4.24 14.5a5 5 0 0 0 6.88 6M13.76 17.5a5 5 0 0 0-6.88-6");
        put("code",
                "M16 18l6-6-6-6M8 6l-6 6 6 6");
        put("terminal",
                "M12 19h8M4 17l6-6-6-6");
        put("layout",
                "M4 3H9A1 1 0 0 1 10 4V11A1 1 0 0 1 9 12H4A1 1 0 0 1 3 11V4A1 1 0 0 1 4 3zM15 3H20A1 1 0 0 1 21 4V7A1 1 0 0 1 20 8H15A1 1 0 0 1 14 7V4A1 1 0 0 1 15 3zM15 12H20A1 1 0 0 1 21 13V20A1 1 0 0 1 20 21H15A1 1 0 0 1 14 20V13A1 1 0 0 1 15 12zM4 16H9A1 1 0 0 1 10 17V20A1 1 0 0 1 9 21H4A1 1 0 0 1 3 20V17A1 1 0 0 1 4 16z");
        put("scale",
                "M12 3v18M19 8l3 8a5 5 0 0 1-6 0zV7M3 7h1a17 17 0 0 0 8-2 17 17 0 0 0 8 2h1M5 8l3 8a5 5 0 0 1-6 0zV7M7 21h10");
        put("book",
                "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20");
        put("gauge",
                "M12 14l4-4M3.34 19a10 10 0 1 1 17.32 0");
        put("shield",
                "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z");
        put("loader-fabric",
                "M17 13.44 4.442 17.082A2 2 0 0 0 4.982 21H19a2 2 0 0 0 .558-3.921l-1.115-.32A2 2 0 0 1 17 14.837V7.66M7 10.56l12.558-3.642A2 2 0 0 0 19.018 3H5a2 2 0 0 0-.558 3.921l1.115.32A2 2 0 0 1 7 9.163v7.178");
        put("loader-forge",
                "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z");
        put("loader-neoforge",
                "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12z");
        put("loader-quilt",
                "M8.3 10a.7.7 0 0 1-.626-1.079L11.4 3a.7.7 0 0 1 1.198-.043L16.3 8.9a.7.7 0 0 1-.572 1.1ZM4 14H9A1 1 0 0 1 10 15V20A1 1 0 0 1 9 21H4A1 1 0 0 1 3 20V15A1 1 0 0 1 4 14zM14 17.5A3.5 3.5 0 1 0 21 17.5A3.5 3.5 0 1 0 14 17.5z");
        put("loader-optifine",
                "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0M9 12A3 3 0 1 0 15 12A3 3 0 1 0 9 12z");
        put("loader-qsl",
                "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z");
        put("version-release",
                "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5M9 18h6M10 22h4");
        put("version-snapshot",
                "M14 2v6a2 2 0 0 0 .245.96l5.51 10.08A2 2 0 0 1 18 22H6a2 2 0 0 1-1.755-2.96l5.51-10.08A2 2 0 0 0 10 8V2M6.453 15h11.094M8.5 2h7");
        put("version-old",
                "M10 5V3M14 5V3M15 21v-3a3 3 0 0 0-6 0v3M18 3v8M18 5H6M22 11H2M22 9v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9M6 3v8");
        put("version-april",
                "M15 10V9M7.084 14.302a5.12 5.12 0 009.833 0 .24.24 0 00-.235-.302H7.32a.24.24 0 00-.235.302M9 10V9M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12z");
        put("version-other",
                "M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12zM9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3M12 17h.01");
        put("sun",
                "M8 12A4 4 0 1 0 16 12A4 4 0 1 0 8 12zM12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41");
        put("archive",
                "M3 3H21A1 1 0 0 1 22 4V7A1 1 0 0 1 21 8H3A1 1 0 0 1 2 7V4A1 1 0 0 1 3 3zM4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8M10 12h4");
        put("ellipsis",
                "M11 12A1 1 0 1 0 13 12A1 1 0 1 0 11 12zM18 12A1 1 0 1 0 20 12A1 1 0 1 0 18 12zM4 12A1 1 0 1 0 6 12A1 1 0 1 0 4 12z");
        put("list-todo",
                "M13 5h8M13 12h8M13 19h8M3 17l2 2 4-4M3 5H9V11H3z");
        put("world",
                "M21.54 15H17a2 2 0 0 0-2 2v4.54M7 3.34V5a3 3 0 0 0 3 3a2 2 0 0 1 2 2c0 1.1.9 2 2 2a2 2 0 0 0 2-2c0-1.1.9-2 2-2h3.17M11 21.95V18a2 2 0 0 0-2-2a2 2 0 0 1-2-2v-1a2 2 0 0 0-2-2H2.05M2 12A10 10 0 1 0 22 12A10 10 0 1 0 2 12z");

        // 品牌 logo：直接吃 src/main/resources/svg 下的官方 SVG 文件
        loadBrand("github", "/svg/GitHub_light.svg");
        loadBrand("gitee", "/svg/gitee_logo_black.svg");
    }

    /** 该名字是否已有可用图标 */
    public static boolean has(String name) {
        if (name == null) return false;
        if (BRAND.containsKey(name)) return true;
        String p = STROKE.get(name);
        return p != null && !p.isBlank();
    }

    /**
     * 纯图标节点：原 emoji 独占文案的控件用（关闭、播放、头像占位等）。
     *
     * @param size  目标边长（像素）
     * @param color 颜色；传 null 时用默认灰色
     * @return 固定尺寸的图标节点；名字未登记时返回同尺寸透明占位，
     *         保证按钮仍有可点面积，不会塌缩成零宽
     */
    public static Region icon(String name, double size, Paint color) {
        StackPane holder = new StackPane();
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        holder.setMouseTransparent(true);
        holder.setPickOnBounds(false);
        if (has(name)) {
            Paint paint = color != null ? color : DEFAULT_COLOR;
            double scale = size / VIEW_BOX;

            String brandD = BRAND.get(name);
            if (brandD != null) {
                // 品牌 logo：实心填充（不能描边），缩放任一坐标系都按路径自身包围盒反推
                SVGPath logo = new SVGPath();
                logo.setContent(brandD);
                logo.setFill(paint);
                logo.setStroke(null);
                javafx.geometry.Bounds b = logo.getLayoutBounds();
                double side = Math.max(b.getWidth(), b.getHeight());
                if (side > 0) {
                    logo.setScaleX(size / side);
                    logo.setScaleY(size / side);
                }
                holder.getChildren().add(logo);
                return holder;
            }

            String fillD = FILL.get(name);
            if (fillD != null && !fillD.isBlank()) {
                SVGPath filled = new SVGPath();
                filled.setContent(fillD);
                filled.setFill(paint);
                filled.setStroke(null);
                filled.setScaleX(scale);
                filled.setScaleY(scale);
                holder.getChildren().add(filled);
            }

            SVGPath stroked = new SVGPath();
            stroked.setContent(STROKE.get(name));
            stroked.setFill(null);
            stroked.setStroke(paint);
            stroked.setStrokeWidth(STROKE_WIDTH);
            stroked.setStrokeType(StrokeType.CENTERED);
            stroked.setStrokeLineCap(StrokeLineCap.ROUND);
            stroked.setStrokeLineJoin(StrokeLineJoin.ROUND);
            stroked.setScaleX(scale);
            stroked.setScaleY(scale);
            holder.getChildren().add(stroked);
        }
        return holder;
    }

    /** 纯图标节点（默认灰色） */
    public static Region icon(String name, double size) {
        return icon(name, size, null);
    }

    /**
     * 让图标颜色跟随控件的文字颜色。
     *
     * <p>实心彩色按钮（蓝 / 红 / 绿 / 紫）上的文字是白色的，若图标固定用灰色会看不清。
     * 这里不去为每个按钮硬编码颜色，而是绑定 {@link Labeled#textFillProperty()}：
     * {@code -fx-text-fill} 会落到 textFill 上，所以白字按钮自动得到白图标、
     * 深色文字得到深色图标，深浅主题切换也一并跟随。
     *
     * <p>CSS 尚未解析出 textFill 时先退回默认灰，解析完成后会自动更新。
     */
    private static void bindPaintToTextFill(Labeled target, Region node) {
        for (javafx.scene.Node child : node.getChildrenUnmodifiable()) {
            if (!(child instanceof SVGPath sp)) continue;
            javafx.beans.binding.ObjectBinding<Paint> binding = javafx.beans.binding.Bindings.createObjectBinding(
                    () -> target.getTextFill() != null ? target.getTextFill() : DEFAULT_COLOR,
                    target.textFillProperty());
            if (sp.getStroke() != null) {
                sp.strokeProperty().bind(binding);
            } else if (sp.getFill() != null) {
                sp.fillProperty().bind(binding);
            }
        }
    }

    /** 给已有控件挂上图标（放在文字左侧）；名字未登记时不改动控件 */
    public static void apply(Labeled target, String name, double size, Paint color) {
        if (target == null || !has(name)) return;
        Region node = icon(name, size, color);
        // color 为空时跟随文字颜色，避免在实心彩色按钮上对比度不足
        if (color == null) bindPaintToTextFill(target, node);
        target.setGraphic(node);
        target.setGraphicTextGap(6);
    }

    /** 带图标的按钮：等价于原来的 {@code new Button("图标 文字")} */
    public static Button button(String name, String text) {
        Button b = new Button(text);
        apply(b, name, 15, null);
        return b;
    }

    /** 带图标的标签：等价于原来的 {@code new Label("图标 文字")} */
    public static Label label(String name, String text) {
        Label l = new Label(text);
        apply(l, name, 15, null);
        return l;
    }

    /** 带图标的开关按钮：等价于原来的 {@code new ToggleButton("图标 文字")} */
    public static javafx.scene.control.ToggleButton toggle(String name, String text) {
        javafx.scene.control.ToggleButton t = new javafx.scene.control.ToggleButton(text);
        apply(t, name, 15, null);
        return t;
    }

    /** 运行期同时更新图标与文字（原 {@code btn.setText("▶ 启动")} 的等价写法） */
    public static void setText(Labeled target, String name, String text) {
        if (target == null) return;
        target.setText(text);
        if (has(name)) {
            apply(target, name, 15, null);
        } else {
            target.setGraphic(null);
        }
    }

    /** 让外部注册 / 覆盖图标（便于把自定义 SVG 批量灌进来） */
    public static void register(String name, String svgPathData) {
        STROKE.put(name, svgPathData);
    }

    /** 当前已登记且带数据的图标名 */
    public static java.util.Set<String> available() {
        java.util.Set<String> set = new java.util.TreeSet<>();
        for (Map.Entry<String, String> e : STROKE.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) set.add(e.getKey());
        }
        set.addAll(BRAND.keySet());
        return set;
    }
}
