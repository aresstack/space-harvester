package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.app.ui.AppState;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Shape;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Hinterlegt Natural-Blöcke über die ganze Breite, aus MainframeMate übernommen
 * ({@code de.bund.zrb.ui.filetab.NaturalSubroutineHighlighter}): {@code DEFINE SUBROUTINE … END-SUBROUTINE},
 * {@code DEFINE DATA … END-DEFINE} und {@code ON ERROR … END-ERROR}. Offene Blöcke reichen bis zum Ende. Die Farben
 * sind einstellbar (Einstellungen › NDV), Vorgaben wie {@code Settings.naturalColor*} in MainframeMate.
 */
public final class NaturalBlockHighlighter {

    public static final String SUBROUTINE_KEY = "deck.natural.color.subroutine";
    public static final String DEFINE_DATA_KEY = "deck.natural.color.defineData";
    public static final String ON_ERROR_KEY = "deck.natural.color.onError";
    public static final String SUBROUTINE_DEFAULT = "#FFF8DC";
    public static final String DEFINE_DATA_DEFAULT = "#E6F0FF";
    public static final String ON_ERROR_DEFAULT = "#FFE6E6";

    /** Offene Editoren, die bei geänderten Farben neu färben (Beobachter statt Abfragen). */
    private static final java.util.List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private NaturalBlockHighlighter() {
    }

    public static void observe(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void unobserve(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** Anzahl angemeldeter Beobachter (Tests: geschlossene Editoren dürfen nicht hängen bleiben). */
    static int observers() {
        return LISTENERS.size();
    }

    /** Speichert eine Farbe ({@code null} = Vorgabe) und meldet es den offenen Editoren. */
    public static void store(String key, Color color) {
        AppState.shared().put(key, color == null ? "" : hex(color));
        LISTENERS.forEach(Runnable::run);
    }

    /** Die eingestellte Farbe oder die Vorgabe. */
    public static Color color(String key, String fallback) {
        String hex = AppState.shared().get(key);
        try {
            return Color.decode(hex == null || hex.isBlank() ? fallback : hex.trim());
        } catch (NumberFormatException e) {
            return Color.decode(fallback);
        }
    }

    public static String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** Sucht die Blöcke und hinterlegt sie; vorige Hinterlegungen fallen weg. */
    public static void apply(RSyntaxTextArea area) {
        if (area == null) {
            return;
        }
        clear(area);
        String text = area.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        Color subroutine = color(SUBROUTINE_KEY, SUBROUTINE_DEFAULT);
        Color defineData = color(DEFINE_DATA_KEY, DEFINE_DATA_DEFAULT);
        Color onError = color(ON_ERROR_KEY, ON_ERROR_DEFAULT);
        String[] lines = text.split("\n", -1);
        int subStart = -1;
        int dataStart = -1;
        int errorStart = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim().toUpperCase(java.util.Locale.ROOT);
            if (subStart < 0 && line.startsWith("DEFINE SUBROUTINE")) {
                subStart = i;
            }
            if (subStart >= 0 && line.startsWith("END-SUBROUTINE")) {
                highlight(area, subStart, i, subroutine);
                subStart = -1;
            }
            if (dataStart < 0 && line.startsWith("DEFINE DATA")) {
                dataStart = i;
            }
            if (dataStart >= 0 && line.startsWith("END-DEFINE")) {
                highlight(area, dataStart, i, defineData);
                dataStart = -1;
            }
            if (errorStart < 0 && line.startsWith("ON ERROR")) {
                errorStart = i;
            }
            if (errorStart >= 0 && line.startsWith("END-ERROR")) {
                highlight(area, errorStart, i, onError);
                errorStart = -1;
            }
        }
        if (subStart >= 0) {
            highlight(area, subStart, lines.length - 1, subroutine);
        }
        if (dataStart >= 0) {
            highlight(area, dataStart, lines.length - 1, defineData);
        }
        if (errorStart >= 0) {
            highlight(area, errorStart, lines.length - 1, onError);
        }
    }

    public static void clear(RSyntaxTextArea area) {
        Highlighter highlighter = area.getHighlighter();
        for (Highlighter.Highlight h : highlighter.getHighlights()) {
            if (h.getPainter() instanceof BlockPainter) {
                highlighter.removeHighlight(h);
            }
        }
    }

    /** Die hinterlegten Blöcke als Zeilenbereiche {@code [erste, letzte]} mit Farbe (für Tests). */
    static java.util.List<String> blocks(RSyntaxTextArea area) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (Highlighter.Highlight h : area.getHighlighter().getHighlights()) {
            if (h.getPainter() instanceof BlockPainter painter) {
                try {
                    result.add(area.getLineOfOffset(h.getStartOffset()) + "-"
                            + area.getLineOfOffset(Math.max(h.getStartOffset(), h.getEndOffset() - 1)) + " "
                            + hex(painter.color));
                } catch (BadLocationException e) {
                    result.add("?");
                }
            }
        }
        return result;
    }

    private static void highlight(RSyntaxTextArea area, int first, int last, Color color) {
        try {
            area.getHighlighter().addHighlight(area.getLineStartOffset(first), area.getLineEndOffset(last),
                    new BlockPainter(color));
        } catch (BadLocationException ignored) {
            // Zeile gibt es nicht (mehr): auslassen.
        }
    }

    /** Malt die ganze Breite jeder Zeile, nicht nur bis zum Textende. */
    static final class BlockPainter implements Highlighter.HighlightPainter {

        private final Color color;

        BlockPainter(Color color) {
            this.color = color;
        }

        @Override
        public void paint(Graphics g, int offs0, int offs1, Shape bounds, JTextComponent c) {
            try {
                Rectangle alloc = bounds instanceof Rectangle r ? r : bounds.getBounds();
                javax.swing.text.Element root = c.getDocument().getDefaultRootElement();
                int startLine = root.getElementIndex(offs0);
                int endLine = root.getElementIndex(Math.max(offs0, offs1 - 1));
                int width = Math.max(c.getWidth(), alloc.width);
                int lineHeight = c.getFontMetrics(c.getFont()).getHeight();
                g.setColor(color);
                for (int line = startLine; line <= endLine; line++) {
                    Rectangle r = c.modelToView2D(root.getElement(line).getStartOffset()).getBounds();
                    g.fillRect(0, r.y, width, Math.max(r.height, lineHeight));
                }
            } catch (Exception ignored) {
                // Malen darf nie scheitern.
            }
        }
    }
}
