package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.deck.editor.LineDiff;
import java.awt.Color;
import java.util.List;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Markiert die Abweichungen zwischen Editor und Vergleichsstand wie {@code DiffHighlighter} in MainframeMate: grün =
 * nur im Editor, rot = nur im Vergleich, Blau-Grau = geändert (in beiden). Gerechnet wird mit {@link LineDiff}.
 */
final class DiffHighlighter {

    static final Color ADDED = Comic.CHANGE_ADDED;
    static final Color DELETED = Comic.CHANGE_REMOVED;
    static final Color CHANGED = Comic.CHANGE_CHANGED;

    private DiffHighlighter() {
    }

    /** Markiert beide Seiten; gibt die Zahl der Abweichungen zurück, {@code -1}, wenn der Text zu groß ist. */
    static int apply(RSyntaxTextArea editor, RSyntaxTextArea compare) {
        clear(editor);
        clear(compare);
        List<LineDiff.Change> changes;
        try {
            changes = LineDiff.of(compare.getText(), editor.getText());
        } catch (IllegalArgumentException e) {
            return -1;
        }
        for (LineDiff.Change change : changes) {
            switch (change.kind()) {
                case ADDED -> line(editor, change.currentLine(), ADDED);
                case DELETED -> line(compare, change.compareLine(), DELETED);
                case CHANGED -> {
                    line(editor, change.currentLine(), CHANGED);
                    line(compare, change.compareLine(), CHANGED);
                }
            }
        }
        return changes.size();
    }

    static void clear(RSyntaxTextArea area) {
        Highlighter highlighter = area.getHighlighter();
        for (Highlighter.Highlight highlight : highlighter.getHighlights()) {
            if (highlight.getPainter() instanceof DiffPainter) {
                highlighter.removeHighlight(highlight);
            }
        }
    }

    private static void line(RSyntaxTextArea area, int line, Color color) {
        try {
            area.getHighlighter().addHighlight(area.getLineStartOffset(line), area.getLineEndOffset(line),
                    new DiffPainter(color));
        } catch (BadLocationException ignored) {
            // Zeile gibt es nicht (mehr).
        }
    }

    private static final class DiffPainter extends DefaultHighlighter.DefaultHighlightPainter {
        DiffPainter(Color color) {
            super(color);
        }
    }
}
