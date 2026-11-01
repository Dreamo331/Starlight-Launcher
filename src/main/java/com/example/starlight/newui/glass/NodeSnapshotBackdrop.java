package com.example.starlight.newui.glass;

import javafx.scene.Node;
import javafx.geometry.Rectangle2D;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;

/**
 * 启动器窗口的快照背景：把「整个窗口去掉玻璃卡片之后的样子」拍成像素缓冲，供玻璃采样。
 *
 * <p>与参考实现（{@code references/liquid-glass}）里的 {@code SceneBackdrop} 处在同一位置：
 * 玻璃读到的那份像素，就是屏幕上真实画着的那份像素。区别只在来源——参考实现画的是照片 +
 * 排版，这里拍的是启动器自己的窗口（壁纸 / 主题渐变 / 侧边栏 / 标题栏 / 页面里其它内容），
 * 所以「透过卡片看到的背景」永远和窗口里实际显示的一致。</p>
 *
 * <p>采样坐标是场景坐标（逻辑 px）。根节点如果不在场景原点，调用方必须先
 * {@link #setSceneOrigin(double, double)}，渲染时会先减掉这个偏移。</p>
 */
public final class NodeSnapshotBackdrop extends BackdropSource {

    /** 根节点在场景里的原点（逻辑 px） */
    private double originX;
    private double originY;

    /** 视口（逻辑 px，相对根节点）；宽高为 0 表示整窗 */
    private double viewX;
    private double viewY;
    private double viewW;
    private double viewH;

    /** 快照的源节点（启动器里就是场景根） */
    private Node source;

    /**
     * 快照底色的回退值。
     *
     * <p>窗口四角是圆角，根节点的背景在圆角外是透明的；相机拍下来那些像素的 alpha 是 0。
     * 不做处理的话，玻璃边缘折射到「窗外」时会读到纯黑。这里用主题中性色补齐。</p>
     */
    private Color base = Color.web("#F1F5F9");

    /** 绑定快照源（场景根）。每次刷新前重新绑一次，窗口重建后也不会指向旧节点。 */
    public void bind(Node node) {
        this.source = node;
    }

    /** 记录根节点在场景里的原点；卡片场景坐标减去它才是快照坐标。 */
    public void setSceneOrigin(double x, double y) {
        this.originX = x;
        this.originY = y;
    }

    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    /** 透明角落的补齐色（浅色主题传浅色、深色主题传深色）。 */
    public void setBaseColor(Color color) {
        if (color != null) {
            this.base = color;
        }
    }

    /**
     * 只拍根节点里的这一块（逻辑 px，相对根节点左上角）。宽高传 0 表示拍整个根节点。
     *
     * <p>采样坐标跟着视口走：调用方把 {@link #setSceneOrigin(double, double)} 设成
     * 「根节点原点 + 视口原点」，卡片场景坐标减掉它就是在快照里的位置。</p>
     */
    public void setViewport(double x, double y, double w, double h) {
        this.viewX = x;
        this.viewY = y;
        this.viewW = w;
        this.viewH = h;
    }

    @Override
    protected boolean needsImage() {
        // 启动器里玻璃读的快照不需要再画一遍：屏幕上那份背景本来就是场景图自己画出来的
        return false;
    }

    @Override
    protected void build(int w, int h, int[] out) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (source == null) {
            fillBase(out, base);
            return;
        }
        // 只拍「所有玻璃卡片的外接矩形」而不是整个窗口：窗口 1165x722 全拍要 80ms 量级，
        // 而卡片占的面积通常只有六成上下，少拍的部分不会有人看见。
        double logicalW = Math.max(1, viewW > 0 ? viewW : source.getLayoutBounds().getWidth());
        double logicalH = Math.max(1, viewH > 0 ? viewH : source.getLayoutBounds().getHeight());
        double sx = w / logicalW;
        double sy = h / logicalH;

        WritableImage shot = new WritableImage(w, h);
        try {
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            params.setTransform(Transform.scale(sx, sy));
            if (viewW > 0 && viewH > 0) {
                // viewport 是变换之后的输出坐标：视口在原节点里的位置 × 缩放
                params.setViewport(new Rectangle2D(viewX * sx, viewY * sy, w, h));
            }
            source.snapshot(params, shot);
        } catch (RuntimeException ex) {
            // 快照失败（节点还没进场景等）：退化成纯色缓冲，玻璃仍然画得出来，界面不报错
            fillBase(out, base);
            return;
        }
        shot.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), out, 0, w);

        int br = (int) Math.round(base.getRed() * 255);
        int bg = (int) Math.round(base.getGreen() * 255);
        int bb = (int) Math.round(base.getBlue() * 255);
        for (int i = 0; i < out.length; i++) {
            int p = out[i];
            int a = p >>> 24;
            if (a == 255) {
                continue;
            }
            double t = a / 255.0;
            int r = (int) (((p >> 16) & 0xFF) * t + br * (1 - t));
            int g = (int) (((p >> 8) & 0xFF) * t + bg * (1 - t));
            int b = (int) ((p & 0xFF) * t + bb * (1 - t));
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private static void fillBase(int[] out, Color base) {
        int r = (int) Math.round(base.getRed() * 255);
        int g = (int) Math.round(base.getGreen() * 255);
        int b = (int) Math.round(base.getBlue() * 255);
        int v = 0xFF000000 | (r << 16) | (g << 8) | b;
        java.util.Arrays.fill(out, v);
    }
}
