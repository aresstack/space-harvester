package com.aresstack.enterpriseai.ui.comic.theme;

import java.awt.Color;

/**
 * Kontrast zweier Farben nach WCAG 2 (relative Leuchtdichte, Verhältnis 1:1 bis 21:1). Damit warnt der
 * Einstellungen-Dialog vor schwer lesbaren Blasenfarben, und Markdown hält Akzentfarben auf jeder Blase lesbar.
 */
public final class ColorContrast {

    /** Darunter ist Text kaum noch lesbar (WCAG-Mindestwert für großen Text und Bedienelemente). */
    public static final double MINIMUM_READABLE = 3.0;

    /**
     * Darunter ist farbiger Text auf der Fläche praktisch unlesbar (etwa Blau auf Blau); Akzentfarben weichen erst
     * dann der Schriftfarbe, damit vertraute Akzente auf den Standardblasen bleiben.
     */
    public static final double MINIMUM_LEGIBLE_ACCENT = 2.0;

    private static final double LINEAR_THRESHOLD = 0.03928;
    private static final double LINEAR_DIVISOR = 12.92;
    private static final double GAMMA_OFFSET = 0.055;
    private static final double GAMMA_DIVISOR = 1.055;
    private static final double GAMMA = 2.4;
    private static final double RED_WEIGHT = 0.2126;
    private static final double GREEN_WEIGHT = 0.7152;
    private static final double BLUE_WEIGHT = 0.0722;
    private static final double FLARE = 0.05;
    private static final double CHANNEL_MAX = 255.0;

    private ColorContrast() {
    }

    /** Das Kontrastverhältnis (1 = gleiche Leuchtdichte, 21 = Schwarz auf Weiß); die Reihenfolge ist egal. */
    public static double ratio(Color first, Color second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + FLARE) / (Math.min(a, b) + FLARE);
    }

    /** Ob Text in {@code foreground} auf {@code background} mindestens {@link #MINIMUM_READABLE} erreicht. */
    public static boolean isReadable(Color foreground, Color background) {
        return ratio(foreground, background) >= MINIMUM_READABLE;
    }

    /** Relative Leuchtdichte (0 Schwarz … 1 Weiß). */
    public static double luminance(Color color) {
        return RED_WEIGHT * channel(color.getRed()) + GREEN_WEIGHT * channel(color.getGreen())
                + BLUE_WEIGHT * channel(color.getBlue());
    }

    private static double channel(int value) {
        double c = value / CHANNEL_MAX;
        return c <= LINEAR_THRESHOLD ? c / LINEAR_DIVISOR : Math.pow((c + GAMMA_OFFSET) / GAMMA_DIVISOR, GAMMA);
    }
}
