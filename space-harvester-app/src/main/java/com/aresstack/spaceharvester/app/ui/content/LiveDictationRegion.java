package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Der Live-Text einer Aufnahme im Eingabefeld („live mitschreiben“, Angelo 26.11.2026): ein Bereich, der mit jeder
 * Erkennung ersetzt wird. Fester Text steht normal, der vorläufige Rest blassgelb hinterlegt. Die Position folgt
 * Eingaben des Benutzers davor (Dokument-{@link Position}); Leerzeichen zum Nachbartext wie {@link DictationInsertion}.
 */
final class LiveDictationRegion {

    private static final Logger LOG = Logger.getLogger(LiveDictationRegion.class.getName());
    private static final DefaultHighlighter.DefaultHighlightPainter PROVISIONAL =
            new DefaultHighlighter.DefaultHighlightPainter(ResearchUiPalette.RECORDING_SURFACE);

    private final JTextComponent editor;
    private Position start;
    private int length;
    private Object highlight;
    private String committed = "";

    private LiveDictationRegion(JTextComponent editor, Position start) {
        this.editor = editor;
        this.start = start;
    }

    /** Beginnt am Cursor bzw. statt der Auswahl {@code [from, to)}. */
    static LiveDictationRegion open(JTextComponent editor, int from, int to) {
        Document document = editor.getDocument();
        int low = clamp(Math.min(from, to), document.getLength());
        int high = clamp(Math.max(from, to), document.getLength());
        try {
            if (high > low) {
                document.remove(low, high - low);
            }
            return new LiveDictationRegion(editor, document.createPosition(low));
        } catch (BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Zeigt den festen und den vorläufigen Text; der Cursor steht dahinter. */
    void show(String fixed, String provisional) {
        committed = fixed == null ? "" : fixed.strip();
        replace(committed, provisional == null ? "" : provisional.strip());
    }

    /**
     * Macht {@code text} zum endgültigen Text des Bereichs (ohne Hinterlegung) und liefert die Cursorposition dahinter,
     * oder -1, wenn kein Text bleibt.
     */
    int done(String text) {
        String value = text == null ? "" : text.strip();
        int caret = replace(value, "");
        return value.isEmpty() ? -1 : caret;
    }

    /** Behält nur den festen Text (Fehlschlag); Ergebnis wie {@link #done}. */
    int keepCommitted() {
        return done(committed);
    }

    /** Entfernt den ganzen Live-Text (Verwerfen). */
    void remove() {
        replace("", "");
    }

    /** Ersetzt den Bereich; liefert die Position hinter dem Text ohne nachfolgendes Leerzeichen. */
    private int replace(String fixed, String provisional) {
        Document document = editor.getDocument();
        clearHighlight();
        try {
            int from = start.getOffset();
            int to = Math.min(document.getLength(), from + length);
            if (to > from) {
                document.remove(from, to - from);
            }
            String words = fixed.isEmpty() ? provisional : provisional.isEmpty() ? fixed : fixed + " " + provisional;
            if (words.isEmpty()) {
                length = 0;
                start = document.createPosition(from);
                return from;
            }
            String text = document.getText(0, document.getLength());
            StringBuilder inserted = new StringBuilder();
            if (from > 0 && !Character.isWhitespace(text.charAt(from - 1))) {
                inserted.append(' ');
            }
            int wordsAt = from + inserted.length();
            inserted.append(words);
            int end = from + inserted.length();
            if (from < text.length() && !Character.isWhitespace(text.charAt(from))) {
                inserted.append(' ');
            }
            document.insertString(from, inserted.toString(), null);
            start = document.createPosition(from);
            length = inserted.length();
            if (!provisional.isEmpty()) {
                highlight = editor.getHighlighter().addHighlight(end - provisional.length(), end, PROVISIONAL);
            }
            editor.setCaretPosition(end);
            return wordsAt + words.length();
        } catch (BadLocationException failed) {
            LOG.log(Level.FINE, "Live-Text der Spracheingabe nicht ersetzbar", failed);
            return editor.getCaretPosition();
        }
    }

    private void clearHighlight() {
        if (highlight != null) {
            editor.getHighlighter().removeHighlight(highlight);
            highlight = null;
        }
    }

    private static int clamp(int index, int length) {
        return Math.max(0, Math.min(index, length));
    }
}
