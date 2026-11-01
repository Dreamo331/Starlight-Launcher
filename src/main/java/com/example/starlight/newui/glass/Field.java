package com.example.starlight.newui.glass;

/**
 * A sampled pixel field: the buffer the glass reads, plus how many device pixels of that buffer
 * correspond to one logical pixel of the scene.
 *
 * <p>The second number is what lets the same renderer sample a full-resolution buffer and a
 * half-resolution blurred one without caring which it got. That matters because blur is the
 * expensive half of the pipeline, and blurring at half resolution then interpolating costs a quarter
 * of the work for a difference nobody can see on an already-smeared image.</p>
 */
public final class Field {

    public final int[] pixels;
    public final int width;
    public final int height;
    public final double pxPerLogical;

    public Field(int[] pixels, int width, int height, double pxPerLogical) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        this.pxPerLogical = pxPerLogical;
    }

    public static Field of(int[] pixels, int width, int height, double scale) {
        return new Field(pixels, width, height, scale);
    }
}
