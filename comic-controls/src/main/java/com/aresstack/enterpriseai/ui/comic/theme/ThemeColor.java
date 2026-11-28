package com.aresstack.enterpriseai.ui.comic.theme;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * A LIVING color: {@link #getRGB()} returns the value of its token in the CURRENTLY active
 * {@link AskAiTheme}, so a component that keeps a {@code ThemeColor} as background, foreground or
 * border color paints the new theme's value on its next repaint after
 * {@link AskAiTheme#setActive(AskAiTheme)} — without any consumer code knowing about theme switches.
 * All other {@code Color} getters derive from {@code getRGB()} (the float component getters are
 * overridden as well).
 *
 * <p>Besides plain tokens ({@link #of(String)}) there are live DERIVED colors — a token with an
 * alpha ({@link #withAlpha(Color, int)}) and a mix of two colors ({@link #mix(Color, Color, float)}) —
 * because a derived color must never be cached as a fixed value (it is derived again on every read).</p>
 *
 * <p>{@code equals} stays {@code Color}'s (compares {@code getRGB()}, i.e. the live value);
 * {@code hashCode} stays {@code Color}'s too and therefore reflects the value at creation time.</p>
 */
public class ThemeColor extends Color {

    private static final long serialVersionUID = 1L;
    private static final ConcurrentMap<String, ThemeColor> TOKENS = new ConcurrentHashMap<String, ThemeColor>();

    private final String description;

    ThemeColor(String description, Color initial) {
        super(initial.getRGB(), true);
        this.description = description;
    }

    /** The living color of a token ({@code group.role} key); unknown keys are rejected immediately. */
    public static ThemeColor of(final String tokenKey) {
        ThemeColor existing = TOKENS.get(tokenKey);
        if (existing != null) {
            return existing;
        }
        ThemeColor created = new ThemeColor(tokenKey, AskAiTheme.active().color(tokenKey)) {
            private static final long serialVersionUID = 1L;

            @Override
            protected Color current() {
                return AskAiTheme.active().color(tokenKey);
            }
        };
        ThemeColor raced = TOKENS.putIfAbsent(tokenKey, created);
        return raced == null ? created : raced;
    }

    /** {@code base} (typically a token) with a fixed alpha, derived live on every read. */
    public static ThemeColor withAlpha(final Color base, final int alpha) {
        return new ThemeColor(describe(base) + "@" + alpha, alphaOf(base, alpha)) {
            private static final long serialVersionUID = 1L;

            @Override
            protected Color current() {
                return alphaOf(base, alpha);
            }
        };
    }

    /** {@code base} mixed towards {@code target} by {@code amount} (0..1), derived live on every read. */
    public static ThemeColor mix(final Color base, final Color target, final float amount) {
        return new ThemeColor("mix(" + describe(base) + "," + describe(target) + "," + amount + ")",
                ResearchUiPainter.mix(base, target, amount)) {
            private static final long serialVersionUID = 1L;

            @Override
            protected Color current() {
                return ResearchUiPainter.mix(base, target, amount);
            }
        };
    }

    /**
     * The channel-wise average of two colors with integer truncation ({@code (a + b) / 2}), derived live —
     * the exact arithmetic of the historic hand-written blends, so classic pixels stay identical.
     */
    public static ThemeColor average(final Color first, final Color second) {
        return derive("avg(" + describe(first) + "," + describe(second) + ")", new Supplier<Color>() {
            @Override
            public Color get() {
                return new Color((first.getRed() + second.getRed()) / 2,
                        (first.getGreen() + second.getGreen()) / 2,
                        (first.getBlue() + second.getBlue()) / 2);
            }
        });
    }

    /**
     * Any color derived from tokens by {@code formula}, evaluated LIVE on every read (e.g.
     * {@code ink.brighter()}), so a derived color can be stored in a component without freezing it.
     */
    public static ThemeColor derive(String description, final Supplier<Color> formula) {
        return new ThemeColor(description, formula.get()) {
            private static final long serialVersionUID = 1L;

            @Override
            protected Color current() {
                return formula.get();
            }
        };
    }

    /**
     * The color as an HTML/CSS hex string ({@code #RRGGBB}) of its CURRENT value — for colors inside HTML
     * text; call it when the HTML is built (render time), never cache the string across theme switches.
     */
    public static String toHtmlHex(Color color) {
        return String.format("#%06X", color.getRGB() & 0xFFFFFF);
    }

    /** The token key or derivation this color stands for. */
    public String description() {
        return description;
    }

    /** The concrete color right now. */
    protected Color current() {
        return new Color(super.getRGB(), true);
    }

    @Override
    public int getRGB() {
        return current().getRGB();
    }

    @Override
    public float[] getRGBComponents(float[] compArray) {
        return current().getRGBComponents(compArray);
    }

    @Override
    public float[] getRGBColorComponents(float[] compArray) {
        return current().getRGBColorComponents(compArray);
    }

    @Override
    public float[] getComponents(float[] compArray) {
        return current().getComponents(compArray);
    }

    @Override
    public float[] getColorComponents(float[] compArray) {
        return current().getColorComponents(compArray);
    }

    @Override
    public float[] getComponents(ColorSpace cspace, float[] compArray) {
        return current().getComponents(cspace, compArray);
    }

    @Override
    public float[] getColorComponents(ColorSpace cspace, float[] compArray) {
        return current().getColorComponents(cspace, compArray);
    }

    @Override
    public String toString() {
        return getClass().getName() + "[" + description + "=" + Integer.toHexString(getRGB()) + "]";
    }

    private static Color alphaOf(Color base, int alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    private static String describe(Color color) {
        return color instanceof ThemeColor ? ((ThemeColor) color).description : Integer.toHexString(color.getRGB());
    }
}
