package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Color;

/**
 * Die Farbwerte farbiger Gruppen ({@link com.aresstack.spaceharvester.app.group.GroupColor}) an EINER Stelle, je hell und dunkel (UX-Strang,
 * 27.11.2026). Kein Rot: Rot steht in der App nur für Fehler. Die Farbe ist nur Marke: nie Text darin einfärben, nie eine
 * Fläche füllen, der Name steht immer daneben. Die Statusfarben aus {@code Comic} werden hier bewusst nicht benutzt.
 * Die Konstanten heißen wie die von {@code GroupColor}; ein Test sorgt dafür, dass keine fehlt.
 */
public enum GroupColors {
    BLAU(0x1976D2, 0x64B5F6),
    TUERKIS(0x00838F, 0x4DD0E1),
    GRUEN(0x2E7D32, 0x81C784),
    ORANGE(0xEF6C00, 0xFFB74D),
    LILA(0x7B1FA2, 0xCE93D8),
    ROSA(0xD81B60, 0xF48FB1),
    GELB(0xF9A825, 0xFFD54F),
    GRAU(0x616161, 0xBDBDBD);

    private final Color light;
    private final Color dark;

    GroupColors(int light, int dark) {
        this.light = new Color(light);
        this.dark = new Color(dark);
    }

    /** Der Wert auf hellem Grund. */
    public Color light() {
        return light;
    }

    /** Der Wert auf dunklem Grund. */
    public Color dark() {
        return dark;
    }

    /** Der passende Wert für diesen Hintergrund; ohne Hintergrund der helle. */
    public Color on(Color background) {
        if (background == null) {
            return light;
        }
        double luminance = (0.299 * background.getRed() + 0.587 * background.getGreen()
                + 0.114 * background.getBlue()) / 255;
        return luminance < 0.5 ? dark : light;
    }

    /** Die Farbwerte einer Gruppenfarbe; ohne Farbe {@link #GRAU}. */
    public static GroupColors of(com.aresstack.spaceharvester.app.group.GroupColor key) {
        return key == null ? GRAU : valueOf(key.name());
    }
}
