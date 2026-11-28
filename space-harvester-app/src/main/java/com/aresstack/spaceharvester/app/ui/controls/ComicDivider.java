package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.JSplitPane;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;

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
    private boolean draggable = true;
    /** Ob der kleine gelbe Griff gezeichnet wird; sonst trägt der Trenner eine Knopfleiste ({@link DividerRail}). */
    private boolean gripShown = true;
    /** Länge der Lücke in der Mitte der Linie, in der eine Knopfleiste sitzt; 0 ohne Lücke. */
    private int gap;
    /** Wie viel der Lücke vor der Mitte der Linie liegt; der Rest liegt dahinter. */
    private int gapBefore;

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

    /**
     * Sperrt oder erlaubt das Ziehen dieses Trenners, ohne die Teilung selbst abzuschalten: Eine abgeschaltete
     * Teilung nähme allem darin den eigenen Mauszeiger (die Zeigersuche von AWT überspringt abgeschaltete Flächen
     * samt Inhalt), auch den Doppelpfeil der Trenner weiter innen. Gesperrt zeigt der Trenner den normalen Zeiger;
     * Klicks (etwa der Doppelklick zum Aufklappen) kommen weiter an, die Tastatur (F8) verschiebt ihn nicht.
     */
    public static void draggable(JSplitPane pane, boolean draggable) {
        if (pane.getUI() instanceof BasicSplitPaneUI basic && basic.getDivider() instanceof ComicDivider divider) {
            divider.draggable = draggable;
            divider.showCursor();
            // Auch die Tastatur verschiebt einen gesperrten Trenner nicht: F8 beginnt das Verschieben mit den
            // Pfeiltasten, Pos1 und Ende; gesperrt ist die Taste hier ohne Wirkung.
            javax.swing.InputMap keys = pane.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
            javax.swing.KeyStroke resize = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F8, 0);
            if (draggable) {
                keys.remove(resize);
            } else {
                keys.put(resize, "none");
                if (pane.isFocusOwner()) {
                    pane.transferFocus();
                }
            }
        }
    }

    /**
     * Zeigt oder verbirgt den kleinen gelben Griff: Verborgen ist er, solange eine Knopfleiste der Griff ist
     * ({@link DividerRail}); Linie und Ziehen bleiben gleich.
     */
    public static void grip(JSplitPane pane, boolean shown) {
        if (pane.getUI() instanceof BasicSplitPaneUI basic && basic.getDivider() instanceof ComicDivider divider
                && divider.gripShown != shown) {
            divider.gripShown = shown;
            divider.repaint();
        }
    }

    /**
     * Unterbricht die Linie in ihrer Mitte auf {@code length} Pixeln (Angelo 28.11.2026): Dort sitzt eine
     * durchscheinende Knopfleiste, hinter der die Linie nicht durchscheinen soll; darüber und darunter läuft sie
     * weiter. 0 schließt die Lücke.
     */
    public static void gap(JSplitPane pane, int length) {
        gap(pane, Math.max(0, length) / 2, Math.max(0, length) - Math.max(0, length) / 2);
    }

    /**
     * Wie {@link #gap(JSplitPane, int)}, aber mit einer Lücke, die nicht mittig liegt: {@code before} Pixel vor der
     * Mitte der Linie, {@code after} dahinter. So stehen unter einer Knopfleiste noch einzelne Kugeln
     * ({@link SideDock}), ohne dass die Leiste ihren Platz an der Stelle des Griffs verliert.
     */
    public static void gap(JSplitPane pane, int before, int after) {
        int first = Math.max(0, before);
        int length = first + Math.max(0, after);
        if (pane.getUI() instanceof BasicSplitPaneUI basic && basic.getDivider() instanceof ComicDivider divider
                && (divider.gap != length || divider.gapBefore != first)) {
            divider.gap = length;
            divider.gapBefore = first;
            divider.repaint();
        }
    }

    /** Die Länge der Lücke in der Linie dieser Teilung (für Tests). */
    public static int gap(JSplitPane pane) {
        return pane.getUI() instanceof BasicSplitPaneUI basic && basic.getDivider() instanceof ComicDivider divider
                ? divider.gap : 0;
    }

    /** Ob der Trenner dieser Teilung seinen gelben Griff zeigt (für Tests). */
    public static boolean grip(JSplitPane pane) {
        return pane.getUI() instanceof BasicSplitPaneUI basic && basic.getDivider() instanceof ComicDivider divider
                && divider.gripShown;
    }

    /** Ob sich der Trenner dieser Teilung ziehen lässt (für Tests). */
    public static boolean draggable(JSplitPane pane) {
        return pane.getUI() instanceof BasicSplitPaneUI basic && basic.getDivider() instanceof ComicDivider divider
                && divider.draggable;
    }

    private void showCursor() {
        boolean lying = splitPane != null && splitPane.getOrientation() == JSplitPane.VERTICAL_SPLIT;
        Cursors.set(this, !draggable ? Cursor.getDefaultCursor()
                : Cursor.getPredefinedCursor(lying ? Cursor.S_RESIZE_CURSOR : Cursor.E_RESIZE_CURSOR));
    }

    @Override
    public void propertyChange(java.beans.PropertyChangeEvent event) {
        super.propertyChange(event);
        // Die Basisklasse setzt bei neuer Lage den Doppelpfeil; gesperrt bleibt der normale Zeiger.
        showCursor();
    }

    @Override
    protected void processMouseEvent(MouseEvent event) {
        // Gesperrt beginnt kein Ziehen: Das Drücken erreicht die Zieh-Logik der Basisklasse nicht.
        if (!draggable && event.getID() == MouseEvent.MOUSE_PRESSED) {
            return;
        }
        super.processMouseEvent(event);
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
            int gapStart = Math.max(8, along / 2 - gapBefore);
            if (lying) {
                g2.setColor(palette.getInk());
                if (gap > 0) {
                    g2.fillRoundRect(8, center - 1, Math.max(2, gapStart - 8), 2, 2, 2);
                    g2.fillRoundRect(gapStart + gap, center - 1, Math.max(2, along - 8 - gapStart - gap), 2, 2, 2);
                } else {
                    g2.fillRoundRect(8, center - 1, Math.max(2, along - 16), 2, 2, 2);
                }
                if (gripShown && grip > 10 && across >= 6) {
                    g2.setColor(palette.getAccentYellow());
                    g2.fillRoundRect(gripStart, center - 2, grip, 5, 5, 5);
                    g2.setColor(palette.getInk());
                    g2.drawRoundRect(gripStart, center - 2, grip, 5, 5, 5);
                }
            } else {
                g2.setColor(palette.getInk());
                if (gap > 0) {
                    g2.fillRoundRect(center - 1, 8, 2, Math.max(2, gapStart - 8), 2, 2);
                    g2.fillRoundRect(center - 1, gapStart + gap, 2, Math.max(2, along - 8 - gapStart - gap), 2, 2);
                } else {
                    g2.fillRoundRect(center - 1, 8, 2, Math.max(2, along - 16), 2, 2);
                }
                if (gripShown && grip > 10 && across >= 6) {
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
