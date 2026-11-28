package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.WindowSizes;
import java.awt.Color;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Das Aussehen der senkrechten Knopfleiste einer Seitenleiste (Angelo 28.11.2026): Sie sitzt als Griff auf dem
 * Trenner und soll sich in mehreren Fassungen vergleichen lassen. Die Wahl gilt für alle Knopfleisten zugleich,
 * liegt in den Benutzer-Einstellungen und wirkt sofort.
 */
public enum RailLook {
    /** Schwarzer Grund, weiße Symbole; gewählt hell. */
    INK("Schwarz", Comic.INK, AppColors.of("app.RailLook.init"), Comic.INK, 1, Comic.SURFACE, Comic.INK),
    /** Gelber Grund wie der kleine Griff der übrigen Trenner, nur größer; gewählt schwarz. */
    YELLOW("Gelb", Comic.YELLOW, Comic.INK, Comic.INK, 1, Comic.INK, AppColors.of("app.RailLook.init")),
    /** Schwarzer Grund mit gelbem Rand; gewählt gelb. */
    INK_YELLOW_EDGE("Schwarz mit gelbem Rand", Comic.INK, AppColors.of("app.RailLook.init"), Comic.YELLOW, 2, Comic.YELLOW, Comic.INK);

    /** Schlüssel der Wahl in den Benutzer-Einstellungen (Platz in {@link #values()}). */
    public static final String KEY = "ui.rail.look";

    private static final Set<JComponent> FOLLOWERS = Collections.newSetFromMap(new WeakHashMap<>());

    private final String label;
    private final Color ground;
    private final Color mark;
    private final Color edge;
    private final int edgeWidth;
    private final Color chosenGround;
    private final Color chosenMark;

    RailLook(String label, Color ground, Color mark, Color edge, int edgeWidth, Color chosenGround,
            Color chosenMark) {
        this.label = label;
        this.ground = ground;
        this.mark = mark;
        this.edge = edge;
        this.edgeWidth = edgeWidth;
        this.chosenGround = chosenGround;
        this.chosenMark = chosenMark;
    }

    /** Die Vorgabe (Angelo 28.11.2026): die schwarze Leiste, wie sie am Fensterrand schon war. */
    public static final RailLook DEFAULT = INK;

    /** Die gewählte Fassung; ohne Wahl {@link #DEFAULT}. */
    public static RailLook current() {
        int index = WindowSizes.recallInt(KEY, DEFAULT.ordinal());
        return index >= 0 && index < values().length ? values()[index] : DEFAULT;
    }

    /** Merkt die Wahl und zeichnet alle Knopfleisten neu. */
    public static void choose(RailLook look) {
        WindowSizes.rememberInt(KEY, look.ordinal());
        for (JComponent follower : Set.copyOf(FOLLOWERS)) {
            follower.repaint();
        }
    }

    /** Diese Fläche zeichnet sich bei einer neuen Wahl neu. */
    static void follow(JComponent component) {
        FOLLOWERS.add(component);
    }

    public String label() {
        return label;
    }

    /** Grund einer Hälfte. */
    public Color ground() {
        return ground;
    }

    /** Farbe des Symbols auf dem Grund. */
    public Color mark() {
        return mark;
    }

    /** Farbe des gemeinsamen Rahmens. */
    public Color edge() {
        return edge;
    }

    /** Strichbreite des Rahmens. */
    public int edgeWidth() {
        return edgeWidth;
    }

    /** Grund der gewählten Hälfte und der Hälfte unter der Maus. */
    public Color chosenGround() {
        return chosenGround;
    }

    /** Farbe des Symbols auf der gewählten Hälfte. */
    public Color chosenMark() {
        return chosenMark;
    }
}
