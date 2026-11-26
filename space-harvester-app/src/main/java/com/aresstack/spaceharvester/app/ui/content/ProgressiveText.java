package com.aresstack.spaceharvester.app.ui.content;

import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Füllt ein Textfeld vollständig, aber in Schritten (F40-5, Angelo 26.11.2026: keine Bestandsgrenze). Der Anfang
 * steht sofort da, der Rest wird je Schritt im EDT angehängt, sodass die Oberfläche bedienbar bleibt. Ein neuer
 * Aufruf oder {@link #clear} für dasselbe Feld bricht ein laufendes Füllen ab.
 */
final class ProgressiveText {

    /** Zeichen je Schritt; begrenzt nur die Arbeit je EDT-Durchlauf, nie den gezeigten Inhalt (Invariante). */
    static final int STEP_CHARS = 256 * 1024;

    private static final String GENERATION = ProgressiveText.class.getName() + ".generation";

    private ProgressiveText() {
    }

    /** Zeigt {@code text} vollständig, ab dem Anfang; im EDT aufzurufen. */
    static void show(JTextComponent area, String text) {
        int generation = next(area);
        String value = text == null ? "" : text;
        int first = Math.min(value.length(), STEP_CHARS);
        area.setText(value.substring(0, first));
        area.setCaretPosition(0);
        if (first < value.length()) {
            SwingUtilities.invokeLater(() -> append(area, value, first, generation));
        }
    }

    /** Leert das Feld und beendet ein laufendes Füllen; im EDT aufzurufen. */
    static void clear(JTextComponent area) {
        next(area);
        area.setText("");
    }

    private static void append(JTextComponent area, String text, int from, int generation) {
        if (generation(area) != generation) {
            return;
        }
        int to = Math.min(text.length(), from + STEP_CHARS);
        int caret = area.getCaretPosition();
        try {
            area.getDocument().insertString(area.getDocument().getLength(), text.substring(from, to), null);
        } catch (javax.swing.text.BadLocationException e) {
            throw new IllegalStateException(e);
        }
        area.setCaretPosition(caret);
        if (to < text.length()) {
            SwingUtilities.invokeLater(() -> append(area, text, to, generation));
        }
    }

    private static int next(JTextComponent area) {
        int generation = generation(area) + 1;
        area.putClientProperty(GENERATION, generation);
        return generation;
    }

    private static int generation(JTextComponent area) {
        Object value = area.getClientProperty(GENERATION);
        return value instanceof Integer number ? number : 0;
    }
}
