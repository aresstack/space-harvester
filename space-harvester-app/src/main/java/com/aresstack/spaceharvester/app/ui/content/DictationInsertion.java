package com.aresstack.spaceharvester.app.ui.content;

/**
 * Wo erkannter Text ins Eingabefeld kommt (aus askai-java8 {@code ComposerInserter}): am Cursor bzw. statt der
 * Auswahl, mit Leerzeichen zum Nachbartext, leerer Text ändert nichts, der Cursor steht danach hinter den Wörtern.
 */
final class DictationInsertion {

    final String text;
    final int caret;

    private DictationInsertion(String text, int caret) {
        this.text = text;
        this.caret = caret;
    }

    static DictationInsertion insert(String existing, int selectionStart, int selectionEnd, String transcript) {
        String current = existing == null ? "" : existing;
        int start = clamp(selectionStart, current.length());
        int end = clamp(selectionEnd, current.length());
        if (start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        String words = transcript == null ? "" : transcript.trim();
        if (words.isEmpty()) {
            return new DictationInsertion(current, start);
        }
        String before = current.substring(0, start);
        String after = current.substring(end);
        StringBuilder inserted = new StringBuilder();
        if (!before.isEmpty() && !Character.isWhitespace(before.charAt(before.length() - 1))) {
            inserted.append(' ');
        }
        int caret = before.length() + inserted.length() + words.length();
        inserted.append(words);
        if (!after.isEmpty() && !Character.isWhitespace(after.charAt(0))) {
            inserted.append(' ');
        }
        return new DictationInsertion(before + inserted + after, caret);
    }

    private static int clamp(int index, int length) {
        return Math.max(0, Math.min(index, length));
    }
}
