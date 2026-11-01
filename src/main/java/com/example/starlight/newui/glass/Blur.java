package com.example.starlight.newui.glass;

/**
 * Separable three-box blur: a Gaussian approximation that is O(n) per pass thanks to running sums,
 * so re-blurring a full-window backdrop costs a few tens of milliseconds instead of hundreds.
 *
 * <p>Three passes is the standard trade: fewer shows as visible banding on gradients, more buys
 * nothing a viewer can see, and glass is about to be refracted and tinted anyway.</p>
 */
public final class Blur {

    private Blur() {
    }

    /** Returns a blurred copy of {@code src}. Alpha is forced opaque. */
    public static int[] blur(int[] src, int width, int height, int radiusPx) {
        if (radiusPx <= 0) {
            return src;
        }
        int[] a = src.clone();
        int[] b = new int[a.length];
        for (int pass = 0; pass < 3; pass++) {
            horizontal(a, b, width, height, radiusPx);
            vertical(b, a, width, height, radiusPx);
        }
        return a;
    }

    private static void horizontal(int[] in, int[] out, int w, int h, int r) {
        int norm = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int rr = 0;
            int gg = 0;
            int bb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + clamp(i, 0, w - 1)];
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xFF000000 | ((rr / norm) << 16) | ((gg / norm) << 8) | (bb / norm);
                int add = in[row + clamp(x + r + 1, 0, w - 1)];
                int sub = in[row + clamp(x - r, 0, w - 1)];
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                gg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void vertical(int[] in, int[] out, int w, int h, int r) {
        int norm = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int rr = 0;
            int gg = 0;
            int bb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[clamp(i, 0, h - 1) * w + x];
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xFF000000 | ((rr / norm) << 16) | ((gg / norm) << 8) | (bb / norm);
                int add = in[clamp(y + r + 1, 0, h - 1) * w + x];
                int sub = in[clamp(y - r, 0, h - 1) * w + x];
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                gg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
