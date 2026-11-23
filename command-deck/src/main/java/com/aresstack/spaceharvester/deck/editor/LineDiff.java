package com.aresstack.spaceharvester.deck.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Zeilenvergleich zweier Texte über die längste gemeinsame Teilfolge, übernommen aus {@code DiffHighlighter} von
 * MainframeMate: hinzugekommen (nur im aktuellen Text), entfernt (nur im Vergleichstext), geändert (ein Entfernen
 * direkt vor einem Hinzufügen wird zu einer geänderten Zeile zusammengefasst).
 */
public final class LineDiff {

    /** Art einer Abweichung. */
    public enum Kind { ADDED, DELETED, CHANGED }

    /**
     * Eine Abweichung mit Zeilennummern ab 0; {@code -1}, wo es die Zeile auf der Seite nicht gibt.
     *
     * @param compareLine Zeile im Vergleichstext (ältere Version)
     * @param currentLine Zeile im aktuellen Text
     */
    public record Change(Kind kind, int compareLine, int currentLine) {
    }

    /** Ab so vielen Zeilen-Paaren wird nicht mehr verglichen (die Tabelle bräuchte zu viel Speicher). */
    public static final long MAX_CELLS = 25_000_000L;

    private LineDiff() {
    }

    /** Vergleicht {@code compare} (alt) mit {@code current} (neu); Zeilen getrennt an {@code \n}. */
    public static List<Change> of(String compare, String current) {
        String[] oldLines = lines(compare);
        String[] newLines = lines(current);
        int oldLen = oldLines.length;
        int newLen = newLines.length;
        if ((long) (oldLen + 1) * (newLen + 1) > MAX_CELLS) {
            throw new IllegalArgumentException("Zu viele Zeilen für den Vergleich.");
        }
        int[][] lcs = new int[oldLen + 1][newLen + 1];
        for (int i = 1; i <= oldLen; i++) {
            for (int j = 1; j <= newLen; j++) {
                lcs[i][j] = oldLines[i - 1].equals(newLines[j - 1]) ? lcs[i - 1][j - 1] + 1
                        : Math.max(lcs[i - 1][j], lcs[i][j - 1]);
            }
        }
        List<Change> reversed = new ArrayList<>();
        int i = oldLen;
        int j = newLen;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                reversed.add(new Change(Kind.ADDED, -1, j - 1));
                j--;
            } else {
                reversed.add(new Change(Kind.DELETED, i - 1, -1));
                i--;
            }
        }
        Collections.reverse(reversed);
        List<Change> result = new ArrayList<>();
        for (int k = 0; k < reversed.size(); k++) {
            Change change = reversed.get(k);
            if (change.kind() == Kind.DELETED && k + 1 < reversed.size()
                    && reversed.get(k + 1).kind() == Kind.ADDED) {
                result.add(new Change(Kind.CHANGED, change.compareLine(), reversed.get(k + 1).currentLine()));
                k++;
            } else {
                result.add(change);
            }
        }
        return List.copyOf(result);
    }

    private static String[] lines(String text) {
        return text == null || text.isEmpty() ? new String[0] : text.split("\n", -1);
    }
}
