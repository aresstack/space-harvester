package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.theme.ColorContrast;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * Visuelle Werte für gerendertes Markdown, ohne Kopplung an ein konkretes Look-and-Feel (aus askai-java8
 * {@code MarkdownTheme}).
 */
public final class MarkdownTheme {

    private static final int MINIMUM_BODY_FONT_SIZE = 13;

    private final Font bodyFont;
    private final Font codeFont;
    private final Color foreground;
    private final Color mutedForeground;
    private final Color linkForeground;
    private final Color codeBackground;
    private final Color quoteBorder;
    private final Color separatorColor;
    private final Color errorForeground;
    private final Color tableHeaderBackground;

    public MarkdownTheme(Font bodyFont,
                         Font codeFont,
                         Color foreground,
                         Color mutedForeground,
                         Color linkForeground,
                         Color codeBackground,
                         Color quoteBorder,
                         Color separatorColor,
                         Color errorForeground) {
        this.bodyFont = bodyFont;
        this.codeFont = codeFont;
        this.foreground = foreground;
        this.mutedForeground = mutedForeground;
        this.linkForeground = linkForeground;
        this.codeBackground = codeBackground;
        this.quoteBorder = quoteBorder;
        this.separatorColor = separatorColor;
        this.errorForeground = errorForeground;
        // Der Tabellenkopf hebt sich eine Stufe vom Zellgrund ab, ohne eine eigene Farbe einzuführen.
        this.tableHeaderBackground = codeBackground == null || foreground == null
                ? codeBackground : blend(codeBackground, foreground, 0.12f);
    }

    /**
     * Ein Thema, das in eine farbige Sprechblase passt: Der Text nutzt die Vordergrundfarbe der Blase, jede
     * schattierte Fläche (Code-Hintergrund, Zitatrand, Trennlinien) wird aus dem Blasenhintergrund abgeleitet statt
     * aus dem hellen Look-and-Feel — so erscheint auf einer farbigen Blase nie schwarzer Text oder ein weißer Kasten.
     * Die Schrift entspricht der der Text-Sprechblase ({@code TextArea.font}, normal, mindestens 13 pt), damit
     * Markdown-Antworten und Fehler-/Hinweisblasen gleich wirken.
     */
    public static MarkdownTheme forBubble(Color background, Color foreground) {
        Color fg = foreground == null ? color("Label.foreground", Color.DARK_GRAY) : foreground;
        Color bg = background == null ? color("TextArea.background", Color.WHITE) : background;
        Color muted = blend(bg, fg, 0.55f);
        Color link = linkColorFor(bg, fg);
        Color codeBackground = blend(bg, fg, 0.10f);
        Color quoteBorder = blend(bg, fg, 0.28f);
        Color separator = blend(bg, fg, 0.20f);
        Color error = ResearchUiPalette.DANGER_RED;
        Font body = bodyFont();
        Font code = new Font(Font.MONOSPACED, Font.PLAIN, body.getSize());
        return new MarkdownTheme(body, code, fg, muted, link, codeBackground,
                quoteBorder, separator, error);
    }

    /** Derive a readable document theme from the actual transcript surface and look and feel. */
    public static MarkdownTheme forSurface(Color background) {
        Color bg = background == null ? color("Panel.background", Color.WHITE) : background;
        Color fg = color("Label.foreground", Color.BLACK);
        if (ColorContrast.ratio(fg, bg) < 4.5) {
            fg = ColorContrast.ratio(Color.BLACK, bg) >= ColorContrast.ratio(Color.WHITE, bg)
                    ? Color.BLACK : Color.WHITE;
        }
        Font body = bodyFont();
        Color muted = blend(bg, fg, 0.72f);
        Color link = linkColorFor(bg, fg);
        return new MarkdownTheme(body, new Font(Font.MONOSPACED, Font.PLAIN, body.getSize()),
                fg, muted, link, blend(bg, fg, 0.055f), blend(bg, fg, 0.28f),
                blend(bg, fg, 0.16f), ResearchUiPalette.DANGER_RED);
    }

    private static Font bodyFont() {
        Font base = UIManager.getFont("TextArea.font");
        if (base == null) {
            base = UIManager.getFont("Label.font");
        }
        if (base == null) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, MINIMUM_BODY_FONT_SIZE);
        }
        return base.deriveFont(Font.PLAIN, Math.max(MINIMUM_BODY_FONT_SIZE, base.getSize()));
    }

    /**
     * Auf dunklen Blasen (heller Text) wird das Akzentblau aufgehellt, sonst bliebe ein Link unleserlich. Wäre der
     * Akzent auf einer selbst gewählten Blasenfarbe trotzdem kaum lesbar (etwa Blau auf Blau), folgt der Link der
     * Schriftfarbe; die Unterstreichung kennzeichnet ihn weiter.
     */
    private static Color linkColorFor(Color background, Color foreground) {
        Color accent = luminance(foreground) > 0.6f
                ? blend(foreground, ResearchUiPalette.ACCENT_BLUE, 0.35f)
                : color("Component.linkColor", ResearchUiPalette.ACCENT_BLUE);
        return ColorContrast.ratio(accent, background) >= ColorContrast.MINIMUM_LEGIBLE_ACCENT ? accent : foreground;
    }

    private static float luminance(Color color) {
        return (0.299f * color.getRed() + 0.587f * color.getGreen() + 0.114f * color.getBlue()) / 255f;
    }

    private static Color color(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }

    private static Color blend(Color first, Color second, float secondWeight) {
        float firstWeight = 1.0f - secondWeight;
        return new Color(
                Math.round(first.getRed() * firstWeight + second.getRed() * secondWeight),
                Math.round(first.getGreen() * firstWeight + second.getGreen() * secondWeight),
                Math.round(first.getBlue() * firstWeight + second.getBlue() * secondWeight));
    }

    public Font getBodyFont() {
        return bodyFont;
    }

    public Font getCodeFont() {
        return codeFont;
    }

    public Color getForeground() {
        return foreground;
    }

    public Color getMutedForeground() {
        return mutedForeground;
    }

    public Color getLinkForeground() {
        return linkForeground;
    }

    public Color getCodeBackground() {
        return codeBackground;
    }

    public Color getQuoteBorder() {
        return quoteBorder;
    }

    public Color getSeparatorColor() {
        return separatorColor;
    }

    /** Grund des Tabellenkopfs: der Zellgrund ({@link #getCodeBackground()}) eine Stufe zur Schrift hin. */
    public Color getTableHeaderBackground() {
        return tableHeaderBackground;
    }

    public Color getErrorForeground() {
        return errorForeground;
    }
}
