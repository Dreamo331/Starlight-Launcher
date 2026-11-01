package com.example.starlight.newui.glass;

/**
 * CPU backdrop sampler for one pane.
 *
 * <p>It samples a <em>pre-blurred copy of the whole scene</em> rather than blurring its own crop, and
 * it splits the work in two the way a real renderer would:</p>
 *
 * <ul>
 *   <li><b>Geometry, cached.</b> For a given size and shape the rounded-rect signed distance field is
 *       constant. Every pixel's silhouette coverage, outward normal, refraction offset, rim easing and
 *       cast shadow are computed once in {@link Geometry} and reused until the pane resizes or its
 *       shape parameters change. None of it depends on the backdrop or on where the pane sits, so
 *       dragging never recomputes it.</li>
 *   <li><b>Optics, per frame.</b> Sampling the field, dispersing, grading, tinting and adding the
 *       specular lobe. This is all that runs while a pane moves.</li>
 * </ul>
 *
 * <p>The refraction is the interesting part: sample the field offset <em>inward</em> along the surface
 * normal, strongest at the silhouette and easing to zero across the bevel band. That is why the rim
 * shows content from outside the pane, and it is the one visual fact that separates glass from a
 * translucent rectangle. Near the silhouette, red and blue are sampled at slightly different offsets,
 * so an edge crossing a high-contrast line fringes instead of staying monochrome.</p>
 *
 * <p>Everything is computed in device pixels: the output buffer is one device pixel per device pixel,
 * so the rim light and the silhouette stay crisp on a scaled display instead of being upsampled from
 * logical resolution.</p>
 *
 * <p>Output is {@code int} ARGB ready for a {@link javafx.scene.image.WritableImage}. One instance per
 * pane, FX thread only.</p>
 */
public final class GlassRenderer {

    /** Pane margin, in logical px: room for the shadow and for outside-the-edge samples. */
    public static final int MARGIN = 24;

    private int[] out;
    private int ow;
    private int oh;
    private Geometry geometry;

    /** Nanoseconds spent in the last {@link #render} call. */
    public long lastNanos;
    /** Nanoseconds of that call spent rebuilding geometry; 0 on a cached frame. */
    public long lastGeometryNanos;

    /**
     * @param blurred the blurred field, for the interior: a large pane should see a soft backdrop
     * @param sharp   the unblurred field, for the refracting rim. A bevelled edge bends detail, and the
     *                detail it bends is the real scene, not its blur. Without this the dispersion
     *                control has nothing high-contrast to split and reads as a dead knob: sampling two
     *                nearly identical smears and tinting them red and blue changes nothing visible.
     * @param paneX   pane origin in logical scene px
     * @param paneY   pane origin in logical scene px
     * @param scale   device pixels per logical pixel for the output buffer
     * @return buffer of {@code (w+2M)*scale x (h+2M)*scale} device px, pane origin at {@code M*scale}
     */
    public int[] render(Field blurred, Field sharp, double paneX, double paneY,
                        int w, int h, double scale, GlassParams p) {
        return render(blurred, sharp, null, paneX, paneY, w, h, scale, p);
    }

    /**
     * @param fringe a lightly blurred field used only for the dispersion split, so the colour fringe
     *               survives without the crisp rim that made the edge look pasted on. May be null.
     */
    public int[] render(Field blurred, Field sharp, Field fringe, double paneX, double paneY,
                        int w, int h, double scale, GlassParams p) {
        long started = System.nanoTime();
        if (blurred == null || blurred.pixels == null || w <= 0 || h <= 0) {
            return null;
        }
        double s = Math.max(0.5, Math.min(3.0, scale));
        int iw = (int) Math.round(w * s);
        int ih = (int) Math.round(h * s);
        int margin = (int) Math.round(MARGIN * s);
        int bw = iw + margin * 2;
        int bh = ih + margin * 2;
        if (out == null || ow != bw || oh != bh) {
            ow = bw;
            oh = bh;
            out = new int[bw * bh];
            geometry = null;
        }
        lastGeometryNanos = 0;
        if (geometry == null || !geometry.matches(iw, ih, s, p)) {
            long g0 = System.nanoTime();
            geometry = Geometry.build(iw, ih, margin, s, p);
            lastGeometryNanos = System.nanoTime() - g0;
        }
        optics(blurred, sharp == null ? blurred : sharp, fringe, paneX, paneY, s, p);
        lastNanos = System.nanoTime() - started;
        return out;
    }

    /** Convenience overload for callers with a single field (benchmarks, sensitivity probes). */
    public int[] render(Field field, double paneX, double paneY,
                        int w, int h, double scale, GlassParams p) {
        return render(field, field, paneX, paneY, w, h, scale, p);
    }

    public int bufferWidth() {
        return ow;
    }

    public int bufferHeight() {
        return oh;
    }

    private void optics(Field blurred, Field sharp, Field fringe, double paneX, double paneY, double scale, GlassParams p) {
        final Geometry geo = geometry;
        final int[] cov = geo.coverage;
        final int[] shadow = geo.shadow;
        final float[] ox = geo.dx;
        final float[] oy = geo.dy;
        final float[] eases = geo.ease;
        final float[] nxs = geo.nx;
        final float[] nys = geo.ny;
        final float[] depths = geo.depth;
        final int margin = geo.margin;

        final int[] buf = blurred.pixels;
        final int bw = blurred.width;
        final int bh = blurred.height;
        final float bs = (float) Math.max(0.2, blurred.pxPerLogical);

        // The rim may read the sharp field: a bevel does bend real detail, and dispersion only becomes
        // visible when the two channel samples land on genuinely different pixels. When sharpRim is off
        // the whole pane stays on one continuous image, which avoids the rim looking like a crisp ghost
        // pasted over a blurred interior.
        final boolean sharpRim = p.sharpRim;
        final boolean useSharp = sharpRim;
        final int[] sharpBuf = useSharp ? sharp.pixels : buf;
        final int sharpW = useSharp ? sharp.width : bw;
        final int sharpH = useSharp ? sharp.height : bh;
        final float sharpScale = (float) Math.max(0.2, sharp.pxPerLogical);
        final float baseScale = useSharp ? sharpScale : bs;

        final float tintAlpha = (float) Math.max(0, Math.min(0.92, p.tintAlpha));
        final float tintR = (p.tint >> 16) & 0xFF;
        final float tintG = (p.tint >> 8) & 0xFF;
        final float tintB = p.tint & 0xFF;
        final float sat = (float) p.saturation;
        final float bright = (float) p.brightness * 255f;
        final float chroma = (float) Math.max(0, Math.min(0.2, p.chroma));
        final float specStrength = (float) Math.max(0, p.specular);
        final float shade = (float) Math.max(0, Math.min(1, p.edgeShade));
        final float lightX = (float) p.lightX;
        final float lightY = (float) p.lightY;
        final float band = geo.band;
        final float s0 = (float) scale;

        final float originX = (float) paneX;
        final float originY = (float) paneY;
        final int rows = oh;
        final int cw = ow;

        // the rim band is the only place that needs the sharp field and three samples per pixel, so it
        // is also the only place dispersion can happen; the interior stays one cheap sample
        final float rimDepth = band * 1.15f;

        // feDisplacementMap's scale: the channel value minus 0.5, times this. 25 gives ±12.5 px.
        final float turbScale = (float) Math.max(0, p.turbulence);
        final float turbFx = (float) Math.max(0.0001, p.turbFreqX);
        final float turbFy = (float) Math.max(0.0001, p.turbFreqY);
        final int turbOctaves = Math.max(1, Math.min(6, p.turbOctaves));

        for (int y = 0; y < rows; y++) {
            int row = y * cw;
            // ly is centre-relative (needed by the signed distance field). Sampling must use the
            // pane-local coordinate instead: the pane's origin is its top-left corner, so adding a
            // centre-relative coordinate to it shifts the whole sampled image by half the pane. That
            // produced a genuine misalignment — a 320 px card read its backdrop from 160 px to the left.
            float ly = y - margin - geo.halfH;
            // +0.5 samples the output pixel's centre rather than its top-left corner; without it the
            // whole image sits half a pixel off, which matters once the field is quarter resolution
            float localY = (y - margin + 0.5f) / s0;
            float sceneY = (originY + localY) * bs;
            float sceneYSharp = (originY + localY) * sharpScale;
            for (int x = 0; x < cw; x++) {
                int i = row + x;
                int a8 = cov[i];
                if (a8 == 0) {
                    out[i] = shadow[i];
                    continue;
                }

                float e = eases[i];
                float shiftX = ox[i];
                float shiftY = oy[i];
                float lx = x - margin - geo.halfW;
                float localX = (x - margin + 0.5f) / s0;

                // feTurbulence + feDisplacementMap, evaluated per pixel. This is the reference card's
                // entire refraction: noise displaces the sampled backdrop everywhere across the pane,
                // not just at the rim, which is why the whole surface looks gently warped instead of
                // having a bevelled edge.
                if (turbScale > 0.01f) {
                    float[] t = turbulenceAt(lx, ly, turbScale, turbFx, turbFy, turbOctaves);
                    shiftX += t[0];
                    shiftY += t[1];
                }

                float sx = (originX + localX + shiftX) * bs;
                float sy = (sceneY + shiftY * bs);

                int argb;
                if (shiftX == 0 && shiftY == 0) {
                    argb = samplePoint(buf, bw, bh, sx, sy);
                } else {
                    // When sharpRim is on, the rim shows the real detail the bevel is magnifying. When it
                    // is off, the rim stays on the blurred field so the pane reads as one continuous
                    // image; dispersion still gets its detail below, from the sharp field directly.
                    float hx = (originX + localX + shiftX) * baseScale;
                    float hy = (originY + localY + shiftY) * baseScale;
                    argb = useSharp
                            ? sample(sharpBuf, sharpW, sharpH, hx, hy)
                            : sample(buf, bw, bh, sx, sy);

                    if (chroma > 0.001f && depths[i] < rimDepth) {
                        // Dispersion must sample the *same* field the pixel came from, or the three
                        // channels describe two different images. That mistake has been made twice here:
                        // first red/blue from the sharp field with green from the blurred one, then a
                        // full rebuild from a sharper field inside the rim band. The second one is
                        // visible as a faint second arc tracing the inner edge of the band, because the
                        // rim was rendering a crisper image than the interior. Same field, two offsets,
                        // nothing else.
                        float falloff = 1f - depths[i] / rimDepth;
                        float cf = chroma * 8f * falloff * bs;
                        int red = sample(buf, bw, bh, sx + nxs[i] * cf, sy + nys[i] * cf);
                        int blue = sample(buf, bw, bh, sx - nxs[i] * cf, sy - nys[i] * cf);
                        argb = ((red >> 16) & 0xFF) << 16
                                | (argb & 0x00FF00)
                                | (blue & 0xFF);
                    }
                }
                float r = (argb >> 16) & 0xFF;
                float g = (argb >> 8) & 0xFF;
                float b = argb & 0xFF;

                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                r = lum + (r - lum) * sat + bright;
                g = lum + (g - lum) * sat + bright;
                b = lum + (b - lum) * sat + bright;

                float ta = tintAlpha * (0.60f + 0.40f * e);
                r += (tintR - r) * ta;
                g += (tintG - g) * ta;
                b += (tintB - b) * ta;

                if (e > 0.0008f) {
                    float facing = nxs[i] * lightX + nys[i] * lightY;
                    float lit = facing > 0 ? facing : 0;
                    float depth = depths[i];

                    // The reference CSS edge is two thin strokes, not a glow: a 1 px inset highlight at
                    // rgba(255,255,255,.6) on the lit side, and a 1 px border at .15 all round. So the
                    // bands here are measured in single logical pixels. Widening them turns the rim into
                    // a halo that also washes out the interior.
                    float cut = clamp01(1f - depth / (1.0f * s0));            // faint dark cut line
                    float hair = clamp01(1f - Math.abs(depth - 1.4f * s0) / (1.0f * s0));
                    float specBand = clamp01(1f - depth / (3.2f * s0));       // ~3 px, was 16 px
                    float spec = specBand * specBand * (0.10f + 0.62f * lit * lit) * specStrength;
                    float glow = e * (0.004f + 0.010f * lit) * specStrength;  // was 3x this
                    float dark = (facing < 0 ? -facing : 0) * e * shade * 0.55f;
                    float add = hair * (0.08f + 0.42f * lit) * 255f + (spec + glow) * 255f;
                    float keep = (1f - dark) * (1f - 0.22f * cut);

                    r = r * keep + add;
                    g = g * keep + add;
                    b = b * keep + add * 0.97f;
                }

                if (a8 < 255) {
                    int sh = shadow[i];
                    if (sh != 0) {
                        int sa = (int) (((sh >>> 24) & 0xFF) * (1 - a8 / 255f));
                        float f = sa / 255f;
                        float af = a8 / 255f;
                        r = r * af + 4 * f;
                        g = g * af + 7 * f;
                        b = b * af + 10 * f;
                        a8 = Math.min(255, a8 + sa);
                    }
                }

                out[i] = (a8 << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
            }
        }
    }

    /** Nearest read, for the interior where the sample lands on the pixel grid anyway. */
    private static int samplePoint(int[] buf, int bw, int bh, float x, float y) {
        int xi = (int) (x - 0.5f);
        int yi = (int) (y - 0.5f);
        if (xi < 0) {
            xi = 0;
        } else if (xi >= bw) {
            xi = bw - 1;
        }
        if (yi < 0) {
            yi = 0;
        } else if (yi >= bh) {
            yi = bh - 1;
        }
        return buf[yi * bw + xi];
    }

    /**
     * {@code feTurbulence} plus {@code feDisplacementMap}, evaluated inline.
     *
     * <p>Matches the SVG filter's arithmetic: two independent fractal-noise fields stand in for the R
     * and G channels, and the offset is {@code scale * (channel - 0.5)} — so a {@code scale} of 25
     * displaces by at most ±12.5 px, exactly as the reference does. {@code numOctaves} controls how
     * much fine grain rides on the broad undulation.</p>
     *
     * <p>Coordinates are pane-local, so the pattern is anchored to the card and does not crawl when the
     * card moves — same as a filter applied to an element.</p>
     */
    private static float[] turbulenceAt(float x, float y, float scalePx, float fx, float fy, int octaves) {
        float r = fbm(x * fx, y * fy, octaves);
        float g = fbm(x * fy * 1.13f + 41.3f, y * fx * 0.87f + 17.9f, octaves);
        TEMP[0] = (r - 0.5f) * scalePx;
        TEMP[1] = (g - 0.5f) * scalePx;
        return TEMP;
    }

    /** Fractal Brownian motion over the value-noise lattice, normalised to 0..1. */
    private static float fbm(float x, float y, int octaves) {
        float sum = 0;
        float amp = 1;
        float norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += valueNoise(x, y) * amp;
            norm += amp;
            amp *= 0.5f;
            x *= 2f;
            y *= 2f;
        }
        return norm == 0 ? 0.5f : sum / norm;
    }

    private static final float[] TEMP = new float[2];

    private static float valueNoise(float x, float y) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        float xf = x - xi;
        float yf = y - yi;
        float u = xf * xf * (3 - 2 * xf);
        float v = yf * yf * (3 - 2 * yf);
        float n00 = hash(xi, yi);
        float n10 = hash(xi + 1, yi);
        float n01 = hash(xi, yi + 1);
        float n11 = hash(xi + 1, yi + 1);
        float a = n00 + (n10 - n00) * u;
        float b = n01 + (n11 - n01) * u;
        return a + (b - a) * v;
    }

    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
    }
    /** Bilinear read from a field buffer, in that buffer's own device coordinates. */
    private static int sample(int[] buf, int bw, int bh, float x, float y) {
        float fx = clampF(x - 0.5f, 0, bw - 1.001f);
        float fy = clampF(y - 0.5f, 0, bh - 1.001f);
        int x0 = (int) fx;
        int y0 = (int) fy;
        float tx = fx - x0;
        float ty = fy - y0;
        int x1 = Math.min(x0 + 1, bw - 1);
        int y1 = Math.min(y0 + 1, bh - 1);
        int p00 = buf[y0 * bw + x0];
        int p10 = buf[y0 * bw + x1];
        int p01 = buf[y1 * bw + x0];
        int p11 = buf[y1 * bw + x1];
        float w00 = (1 - tx) * (1 - ty);
        float w10 = tx * (1 - ty);
        float w01 = (1 - tx) * ty;
        float w11 = tx * ty;
        float r = ((p00 >> 16) & 0xFF) * w00 + ((p10 >> 16) & 0xFF) * w10
                + ((p01 >> 16) & 0xFF) * w01 + ((p11 >> 16) & 0xFF) * w11;
        float g = ((p00 >> 8) & 0xFF) * w00 + ((p10 >> 8) & 0xFF) * w10
                + ((p01 >> 8) & 0xFF) * w01 + ((p11 >> 8) & 0xFF) * w11;
        float b = (p00 & 0xFF) * w00 + (p10 & 0xFF) * w10
                + (p01 & 0xFF) * w01 + (p11 & 0xFF) * w11;
        return ((int) r << 16) | ((int) g << 8) | (int) b;
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int clamp8(float v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }

    /**
     * The position-independent half of the effect: silhouette, normals, refraction offsets, rim
     * easing, shadow. Rebuilt only when the pane's size or shape changes, which never happens during a
     * drag. Values are stored in device px.
     */
    /**
     * The position-independent half of the effect: silhouette, normals, refraction offsets, rim easing
     * and shadow, all in device px. Rebuilt only when the pane's size or shape changes, which never
     * happens during a drag.
     */
    private static final class Geometry {

        final int margin;
        final int w;
        final int h;
        final float halfW;
        final float halfH;
        final float band;
        final double scale;

        final float rtl;
        final float rtr;
        final float rbr;
        final float rbl;
        final double edgeWidth;
        final double refraction;
        final double shadowAlpha;
        final double shadowBlur;
        final double shadowDrop;

        final int[] coverage;
        final int[] shadow;
        final float[] nx;
        final float[] ny;
        final float[] dx;
        final float[] dy;
        final float[] ease;
        final float[] depth;

        private Geometry(int iw, int ih, int margin, double scale, GlassParams p) {
            this.margin = margin;
            this.w = iw + margin * 2;
            this.h = ih + margin * 2;
            this.scale = scale;
            this.halfW = iw * 0.5f;
            this.halfH = ih * 0.5f;

            // Clamp each corner so opposing radii cannot overlap, which is what browsers do too.
            float lim = Math.min(iw, ih) * 0.5f;
            rtl = (float) Math.max(0, Math.min(p.radiusTL * scale, lim));
            rtr = (float) Math.max(0, Math.min(p.radiusTR * scale, lim));
            rbr = (float) Math.max(0, Math.min(p.radiusBR * scale, lim));
            rbl = (float) Math.max(0, Math.min(p.radiusBL * scale, lim));

            this.band = (float) Math.max(2.0, p.edgeWidth * scale);
            this.edgeWidth = p.edgeWidth;
            this.refraction = p.refraction;
            this.shadowAlpha = p.shadowAlpha;
            this.shadowBlur = p.shadowBlur;
            this.shadowDrop = p.shadowDrop;

            int n = this.w * this.h;
            coverage = new int[n];
            shadow = new int[n];
            nx = new float[n];
            ny = new float[n];
            dx = new float[n];
            dy = new float[n];
            ease = new float[n];
            depth = new float[n];
        }

        boolean matches(int iw, int ih, double scale, GlassParams p) {
            if (this.scale != scale || w != iw + margin * 2 || h != ih + margin * 2) {
                return false;
            }
            float lim = Math.min(iw, ih) * 0.5f;
            return rtl == (float) Math.max(0, Math.min(p.radiusTL * scale, lim))
                    && rtr == (float) Math.max(0, Math.min(p.radiusTR * scale, lim))
                    && rbr == (float) Math.max(0, Math.min(p.radiusBR * scale, lim))
                    && rbl == (float) Math.max(0, Math.min(p.radiusBL * scale, lim))
                    && edgeWidth == p.edgeWidth
                    && refraction == p.refraction
                    && shadowAlpha == p.shadowAlpha
                    && shadowBlur == p.shadowBlur
                    && shadowDrop == p.shadowDrop;
        }

        static Geometry build(int iw, int ih, int margin, double scale, GlassParams p) {
            Geometry g = new Geometry(iw, ih, margin, scale, p);
            g.fill();
            return g;
        }

        private void fill() {
            final float strength = (float) (refraction * scale);
            final float blurPx = (float) Math.max(2, shadowBlur * scale);
            final float drop = (float) (shadowDrop * scale);

            for (int y = 0; y < h; y++) {
                float ly = y - margin - halfH;
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int i = row + x;
                    float lx = x - margin - halfW;

                    // The corner that owns this quadrant. Its radius insets the shape on both axes —
                    // horizontally from the sides, vertically from the top/bottom. Using halfW for the
                    // vertical inset is what silently flattened every corner into a right angle on a
                    // non-square pane: the vertical test went deeply negative and the rounding never ran.
                    float r = ly < 0 ? (lx < 0 ? rtl : rtr) : (lx < 0 ? rbl : rbr);
                    float insetX = Math.max(0, halfW - r);
                    float insetY = Math.max(0, halfH - r);

                    float qx = Math.abs(lx) - insetX;
                    float qy = Math.abs(ly) - insetY;
                    float ax = Math.max(qx, 0);
                    float ay = Math.max(qy, 0);
                    float sdf = (float) Math.sqrt(ax * ax + ay * ay)
                            + Math.min(Math.max(qx, qy), 0) - r;

                    shadow[i] = shadowOf(lx, ly, blurPx, drop);

                    if (sdf > 0.9f) {
                        coverage[i] = 0;
                        continue;
                    }

                    depth[i] = -sdf;
                    int a8 = (int) (clamp01(0.5f - sdf) * 255f);
                    coverage[i] = a8;
                    if (a8 == 0) {
                        continue;
                    }

                    float rim = clamp01(1f - depth[i] / band);
                    float e = rim * rim * (3f - 2f * rim);
                    ease[i] = e;
                    if (e <= 0.0008f) {
                        continue;
                    }

                    float nxx;
                    float nyy;
                    if (ax > 0.0001f || ay > 0.0001f) {
                        float len = (float) Math.sqrt(ax * ax + ay * ay);
                        nxx = (ax / len) * Math.signum(lx);
                        nyy = (ay / len) * Math.signum(ly);
                    } else if (qx >= qy) {
                        nxx = Math.signum(lx);
                        nyy = 0;
                    } else {
                        nxx = 0;
                        nyy = Math.signum(ly);
                    }
                    nx[i] = nxx;
                    ny[i] = nyy;
                    float pull = e * strength;
                    dx[i] = nxx * pull / (float) scale;
                    dy[i] = nyy * pull / (float) scale;
                }
            }
        }

        private int shadowOf(float lx, float ly, float blurPx, float drop) {
            if (shadowAlpha <= 0.001) {
                return 0;
            }
            float sy = ly - drop;
            float r = sy < 0 ? (lx < 0 ? rtl : rtr) : (lx < 0 ? rbl : rbr);
            float insetX = Math.max(0, halfW - r);
            float insetY = Math.max(0, halfH - r);

            float sxq = Math.abs(lx) - (insetX + 2);
            float syq = Math.abs(sy) - (insetY + 2);
            if (sxq > blurPx + 4 || syq > blurPx + 4) {
                return 0;
            }
            float sax = Math.max(sxq, 0);
            float say = Math.max(syq, 0);
            float ssdf = (float) Math.sqrt(sax * sax + say * say)
                    + Math.min(Math.max(sxq, syq), 0) - r;
            float t = clamp01(-ssdf / blurPx);
            if (t <= 0.004f) {
                return 0;
            }
            float a = (float) shadowAlpha * t * t * (3 - 2 * t);
            return (((int) (a * 255f)) << 24) | 0x00_03_06_09;
        }
    }
}