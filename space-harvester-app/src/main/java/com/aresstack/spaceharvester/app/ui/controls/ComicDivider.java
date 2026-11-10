package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.JSplitPane;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Der Trenner aller geteilten Flächen der App, in beiden Lagen gleich: ruhige Fläche, eine Tuschelinie in der Mitte
 * und ein kleiner gelber Griff (wie der Teiler neben der Wissensquellen-Liste, {@code ComicSplitPane} der
 * comic-controls, die hier unverändert bleiben und nur die waagerechte Teilung kennen). Senkrecht geteilt
 * ({@link JSplitPane#HORIZONTAL_SPLIT}) stehen Linie und Griff senkrecht, waagerecht geteilt
 * ({@link JSplitPane#VERTICAL_SPLIT}) liegen sie; {@link #install} ersetzt den Standard-Teiler mit seinen Punkten.
 */
public final class ComicDivider extends BasicSplitPaneDivider {

    /** Dicke des Trenners, wie beim {@code ComicSplitPane}. */
    public static final int THICKNESS = 7;
    /** Länge des Griffs: höchstens so viel, sonst ein Drittel der Trennerlänge. */
    static final int GRIP_MAX = 36;

    private final ComicPalette palette;

    private ComicDivider(BasicSplitPaneUI ui, ComicPalette palette) {
        super(ui);
        this.palette = palette;
    }

    /** Gibt dieser Teilung den Comic-Trenner in ihrer Lage; Rahmen des Look-and-feel entfallen. */
    public static void install(JSplitPane pane) {
        install(pane, ComicPalette.defaultPalette());
    }

    public static void install(JSplitPane pane, ComicPalette palette) {
        BasicSplitPaneUI ui = new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                return new ComicDivider(this, palette);
            }
        };
        pane.setUI(ui);
        // setUI holt den Rahmen des Look-and-feel (Schattenlinie über beiden Seiten) zurück.
        pane.setBorder(null);
        ui.getDivider().setBorder(null);
        pane.setDividerSize(THICKNESS);
    }

    @Override
    public void paint(Graphics g) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = splitPane != null ? splitPane.getBackground() : getBackground();
            g2.setColor(base != null ? base : palette.getSurface());
            g2.fillRect(0, 0, width, height);
            boolean lying = splitPane != null && splitPane.getOrientation() == JSplitPane.VERTICAL_SPLIT;
            int across = lying ? height : width;
            int along = lying ? width : height;
            int center = across / 2;
            int grip = Math.min(GRIP_MAX, along / 3);
            int gripStart = (along - grip) / 2;
            if (lying) {
                g2.setColor(palette.getInk());
                g2.fillRoundRect(8, center - 1, Math.max(2, along - 16), 2, 2, 2);
                if (grip > 10 && across >= 6) {
                    g2.setColor(palette.getAccentYellow());
                    g2.fillRoundRect(gripStart, center - 2, grip, 5, 5, 5);
                    g2.setColor(palette.getInk());
                    g2.drawRoundRect(gripStart, center - 2, grip, 5, 5, 5);
                }
            } else {
                g2.setColor(palette.getInk());
                g2.fillRoundRect(center - 1, 8, 2, Math.max(2, along - 16), 2, 2);
                if (grip > 10 && across >= 6) {
                    g2.setColor(palette.getAccentYellow());
                    g2.fillRoundRect(center - 2, gripStart, 5, grip, 5, 5);
                    g2.setColor(palette.getInk());
                    g2.drawRoundRect(center - 2, gripStart, 5, grip, 5, 5);
                }
            }
        } finally {
            g2.dispose();
        }
    }
}
