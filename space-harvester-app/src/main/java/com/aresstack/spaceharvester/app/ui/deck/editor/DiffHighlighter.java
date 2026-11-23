package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.deck.editor.LineDiff;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.BadLocationException;
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
        for (Object tag : tags(area)) {
            area.removeLineHighlight(tag);
        }
        tags(area).clear();
    }

    /** Ganze Zeile einfärben (über die volle Breite), damit die Abweichung auch bei kurzen Zeilen auffällt. */
    private static void line(RSyntaxTextArea area, int line, Color color) {
        try {
            tags(area).add(area.addLineHighlight(line, color));
        } catch (BadLocationException ignored) {
            // Zeile gibt es nicht (mehr).
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> tags(RSyntaxTextArea area) {
        Object tags = area.getClientProperty(TAGS);
        if (tags == null) {
            tags = new ArrayList<Object>();
            area.putClientProperty(TAGS, tags);
        }
        return (List<Object>) tags;
    }

    private static final String TAGS = "deck.diff.tags";
}
