package com.example.starlight.newui.glass;

/**
 * The knobs of one glass pane. One instance per panel, mutable so the controls can poke it live.
 */
public final class GlassParams {

    /** Blur radius applied to the sampled backdrop, in px. */
    public double blur = 16;

    /**
     * How hard the bevelled rim bends the backdrop, in px of sample offset at the very edge.
     *
     * <p>Kept small on purpose: this decides how far outside the pane the rim reaches, and a large value
     * drags content that belongs elsewhere into the middle of the pane, at which point it stops reading
     * as a bend and starts reading as another image pasted on top.</p>
     */
    public double refraction = 12;

    /** Thickness of the refracting rim band, in px. */
    public double edgeWidth = 26;

    /**
     * Per-corner radii. A liquid-glass card is rarely a symmetric rounded rectangle; unequal corners are
     * most of what makes it look organic rather than like a UI kit component.
     */
    public double radiusTL = 28;
    public double radiusTR = 28;
    public double radiusBR = 28;
    public double radiusBL = 28;

    /**
     * The {@code feDisplacementMap} scale: the whole-pane noise displacement, in px of total travel.
     *
     * <p>Displacement is {@code (channel - 0.5) * scale}, so 25 moves any pixel by at most ±12.5 px —
     * the same number the reference filter uses. This is the entire refraction on the reference card:
     * noise warps the backdrop across the whole surface, with no bevelled edge involved.</p>
     */
    public double turbulence = 0;

    /** Spatial frequency of the turbulence, in cycles per px. CSS used 0.015 / 0.025. */
    public double turbFreqX = 0.015;
    public double turbFreqY = 0.025;

    /** Octaves of fractal noise, i.e. {@code feTurbulence numOctaves}. */
    public int turbOctaves = 3;

    /** Per-channel split of the rim offset. 0 = none, 0.05 = a thin rainbow along the edge. */
    public double chroma = 0.05;

    /** Strength of the specular rim light. */
    public double specular = 1.0;

    /** Body tint, packed 0xRRGGBB. White for the frosted look, dark for the smoked look. */
    public int tint = 0xFFFFFF;

    /** Body tint alpha, 0..1. */
    public double tintAlpha = 0.10;

    /** Saturation multiplier applied to the sampled backdrop. CSS saturate(180%) = 1.8. */
    public double saturation = 1.8;

    /** Brightness offset, -1..1. */
    public double brightness = 0.0;

    /** How much a rim facing away from the light darkens, 0..1. */
    public double edgeShade = 0.3;

    /** Light direction, normalised. Upper left by default; the scene aims it at the cursor. */
    public double lightX = -0.44;
    public double lightY = -0.90;

    /** Cast shadow opacity, 0..1. */
    public double shadowAlpha = 0.55;

    /** Shadow softness, px. */
    public double shadowBlur = 30;

    /** Shadow vertical offset, px. */
    public double shadowDrop = 14;

    /**
     * Whether the refracting rim samples the sharp scene instead of the blurred one.
     *
     * <p>Sharp looks more like thick glass, because a bevel really does bend detail. But it also means
     * the rim shows a crisp image of a place the interior is not showing, and the eye reads that join as
     * a separate image rather than as the same scene bent. Off by default.</p>
     */
    public boolean sharpRim = false;

    public void aimLight(double x, double y) {
        double len = Math.hypot(x, y);
        if (len < 0.0001) {
            return;
        }
        lightX = x / len;
        lightY = y / len;
    }

    /** Uniform corner radius. */
    public GlassParams radius(double v) {
        radiusTL = v;
        radiusTR = v;
        radiusBR = v;
        radiusBL = v;
        return this;
    }

    /** Organic corners, clockwise from top-left. */
    public GlassParams radii(double tl, double tr, double br, double bl) {
        radiusTL = tl;
        radiusTR = tr;
        radiusBR = br;
        radiusBL = bl;
        return this;
    }

    public GlassParams blur(double v) {
        this.blur = v;
        return this;
    }

    public GlassParams refraction(double v) {
        this.refraction = v;
        return this;
    }

    public GlassParams turbulence(double v) {
        this.turbulence = v;
        return this;
    }

    public GlassParams chroma(double v) {
        this.chroma = v;
        return this;
    }

    public GlassParams tint(int rgb, double alpha) {
        this.tint = rgb;
        this.tintAlpha = alpha;
        return this;
    }

    public GlassParams shadow(double alpha, double blurPx) {
        this.shadowAlpha = alpha;
        this.shadowBlur = blurPx;
        return this;
    }
}
