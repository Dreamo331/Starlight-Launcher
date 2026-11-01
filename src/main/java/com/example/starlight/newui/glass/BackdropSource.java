package com.example.starlight.newui.glass;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * A scene's backdrop: the pixel field the glass samples, and the image that is actually painted.
 *
 * <p>Both are the same data on purpose. If the glass sampled a buffer the viewer could not see, the
 * refraction would be a lie and would drift the moment the window changed size. One buffer means the
 * panes are literally reading the pixels underneath them.</p>
 *
 * <p>Three performance decisions live here:</p>
 * <ul>
 *   <li><b>Device resolution.</b> The sharp buffer is allocated at logical size times the window's
 *       render scale, and the type is rasterised at that resolution, so the headline stays crisp at
 *       150 % scaling and the glass has detail fine enough to bend.</li>
 *   <li><b>Downsampled blur.</b> Blurred fields come from a half or quarter resolution copy. Blurring
 *       at reduced resolution then interpolating costs a quarter of the work for a difference nobody
 *       can see on an already-smeared image.</li>
 *   <li><b>A small ladder of cached radii.</b> Blur is the expensive stage and sampling is cheap, so
 *       the whole scene is blurred once per distinct radius and every pane reads the same field.</li>
 * </ul>
 */
public abstract class BackdropSource {

    /** How many distinct blurred fields stay resident. */
    private static final int CACHE_LIMIT = 6;

    private final Map<Integer, Field> blurCache = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Field> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    protected int width;
    protected int height;
    protected double scale = 1;
    protected int[] pixels;
    private WritableImage image;

    private int[] halfPixels;
    private int halfW;
    private int halfH;
    private int[] quarterPixels;
    private int quarterW;
    private int quarterH;

    /** Called from the FX thread; may rasterise text. Sizes are logical. */
    public final void prepare(double logicalW, double logicalH, double renderScale) {
        int w = (int) Math.round(logicalW * renderScale);
        int h = (int) Math.round(logicalH * renderScale);
        if (w <= 0 || h <= 0) {
            return;
        }
        this.scale = renderScale;
        this.width = w;
        this.height = h;
        this.pixels = new int[w * h];
        build(w, h, pixels);
        // 本次移植的唯一改动：参考实现的场景背景本身就要画到屏幕上，启动器的快照只给玻璃读，
        // 画的那份由启动器自己的场景图负责，所以这里留一个开关省掉一次全屏 setPixels。
        if (needsImage()) {
            this.image = new WritableImage(w, h);
            this.image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        }

        halfW = Math.max(1, w / 2);
        halfH = Math.max(1, h / 2);
        halfPixels = downsample(pixels, w, h, halfW, halfH, 0, 0, 2);
        quarterW = Math.max(1, w / 4);
        quarterH = Math.max(1, h / 4);
        quarterPixels = downsample(pixels, w, h, quarterW, quarterH, 0, 0, 4);

        blurCache.clear();
    }

    protected abstract void build(int w, int h, int[] out);

    /** 是否顺带生成一份可直接画到屏幕上的图像（默认要；启动器快照不要）。 */
    protected boolean needsImage() {
        return true;
    }

    public final int width() {
        return width;
    }

    public final int height() {
        return height;
    }

    public final double scale() {
        return scale;
    }

    public final int[] pixels() {
        return pixels;
    }

    public final WritableImage image() {
        return image;
    }

    /** The sharp field, at full device resolution. */
    public final Field sharp() {
        return Field.of(pixels, width, height, scale);
    }

    /**
     * A lightly blurred field, for effects that need detail but not a hard edge.
     *
     * <p>The dispersion split needs a couple of pixels of real contrast to land on different colours,
     * while the pane's rim must not look like a sharp photograph pasted over a blur. This is the middle
     * ground: about a third of the panel blur, computed at half resolution and cached once.</p>
     */
    public final Field light() {
        int key = -1;
        Field cached = blurCache.get(key);
        if (cached != null) {
            return cached;
        }
        int r = Math.max(1, (int) Math.round(1.0 * scale));
        int[] blurred = Blur.blur(pixels, width, height, r);
        cached = Field.of(blurred, width, height, scale);
        blurCache.put(key, cached);
        return cached;
    }

    /**
     * A blurred field for a logical radius.
     *
     * <p>Small radii are blurred at half resolution, larger ones at quarter. The radius ladder is
     * quantised to whole buffer pixels, so four panes asking for four slightly different blurs still
     * land on two or three cache entries. Without that, scrubbing a blur slider would rebuild a
     * full-scene blur on every frame instead of once per step.</p>
     */
    public final Field blurred(double logicalRadius) {
        double radius = Math.max(0, Math.min(30, logicalRadius));
        if (radius < 0.75) {
            return sharp();
        }
        int div = radius >= 5 ? 4 : 2;
        int[] src = div == 4 ? quarterPixels : halfPixels;
        int sw = div == 4 ? quarterW : halfW;
        int sh = div == 4 ? quarterH : halfH;

        int bufferRadius = Math.max(1, (int) Math.round(radius * scale / div));
        int key = div * 10_000 + bufferRadius;
        Field cached = blurCache.get(key);
        if (cached == null) {
            int[] blurred = Blur.blur(src, sw, sh, bufferRadius);
            cached = Field.of(blurred, sw, sh, scale / div);
            blurCache.put(key, cached);
        }
        return cached;
    }

    private static int[] downsample(int[] src, int w, int h, int dw, int dh,
                                    int offsetX, int offsetY, int factor) {
        int[] out = new int[dw * dh];
        int taps = factor * factor;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int dy = 0; dy < factor; dy++) {
                    int sy = Math.min(h - 1, y * factor + dy + offsetY);
                    int row = sy * w;
                    for (int dx = 0; dx < factor; dx++) {
                        int sx = Math.min(w - 1, x * factor + dx + offsetX);
                        int p = src[row + sx];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                out[y * dw + x] = 0xFF000000 | ((r / taps) << 16) | ((g / taps) << 8) | (b / taps);
            }
        }
        return out;
    }

    protected static void coverCrop(int[] raw, int srcW, int srcH, int w, int h, int[] out) {
        double s = Math.max(w / (double) srcW, h / (double) srcH);
        double offsetX = (srcW * s - w) / 2;
        double offsetY = (srcH * s - h) / 2;
        for (int y = 0; y < h; y++) {
            double sy = Math.max(0, Math.min(srcH - 1.001, (y + offsetY) / s));
            int y0 = (int) sy;
            int y1 = Math.min(srcH - 1, y0 + 1);
            double ty = sy - y0;
            for (int x = 0; x < w; x++) {
                double sx = Math.max(0, Math.min(srcW - 1.001, (x + offsetX) / s));
                int x0 = (int) sx;
                int x1 = Math.min(srcW - 1, x0 + 1);
                double tx = sx - x0;
                int p00 = raw[y0 * srcW + x0];
                int p10 = raw[y0 * srcW + x1];
                int p01 = raw[y1 * srcW + x0];
                int p11 = raw[y1 * srcW + x1];
                out[y * w + x] = lerp(lerp(p00, p10, tx), lerp(p01, p11, tx), ty);
            }
        }
    }

    protected static int[] readPixels(Image image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int[] px = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        return px;
    }

    protected static int lerp(int p0, int p1, double t) {
        int r = (int) (((p0 >> 16) & 0xFF) * (1 - t) + ((p1 >> 16) & 0xFF) * t);
        int g = (int) (((p0 >> 8) & 0xFF) * (1 - t) + ((p1 >> 8) & 0xFF) * t);
        int b = (int) ((p0 & 0xFF) * (1 - t) + (p1 & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    protected static int clamp8(double v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }

    protected static double smooth(double t) {
        double x = t < 0 ? 0 : (t > 1 ? 1 : t);
        return x * x * (3 - 2 * x);
    }
}
