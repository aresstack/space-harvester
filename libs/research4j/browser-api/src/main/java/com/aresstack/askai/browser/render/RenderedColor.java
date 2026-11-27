package com.aresstack.askai.browser.render;

/**
 * An sRGB color with alpha as captured from computed styles. {@link #distanceTo} is a normalized
 * (0..1) perceptual-ish distance so SIMILAR backgrounds can group together — the analysis compares
 * against a threshold instead of requiring identical RGB values.
 */
public final class RenderedColor {

    public static final RenderedColor TRANSPARENT = new RenderedColor(0, 0, 0, 0.0);

    public final int red;
    public final int green;
    public final int blue;
    /** 0.0 (fully transparent) .. 1.0 (opaque). */
    public final double alpha;

    public RenderedColor(int red, int green, int blue, double alpha) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    public boolean isTransparent() {
        return alpha <= 0.01;
    }

    /** Normalized 0..1 distance (weighted RGB, alpha-aware); 0 = identical, 1 = maximal contrast. */
    public double distanceTo(RenderedColor other) {
        if (other == null) {
            return 1.0;
        }
        if (isTransparent() && other.isTransparent()) {
            return 0.0;
        }
        if (isTransparent() != other.isTransparent()) {
            return 1.0;
        }
        // Perceptual weighting (Rec. 601 luma proportions) over the RGB delta.
        double dr = (red - other.red) / 255.0;
        double dg = (green - other.green) / 255.0;
        double db = (blue - other.blue) / 255.0;
        double distance = Math.sqrt(0.299 * dr * dr + 0.587 * dg * dg + 0.114 * db * db);
        return Math.min(1.0, distance + Math.abs(alpha - other.alpha) * 0.5);
    }

    @Override
    public String toString() {
        return "rgba(" + red + "," + green + "," + blue + "," + alpha + ")";
    }
}
