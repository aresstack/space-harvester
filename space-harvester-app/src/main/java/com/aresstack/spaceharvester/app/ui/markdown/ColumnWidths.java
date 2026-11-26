package com.aresstack.spaceharvester.app.ui.markdown;

import java.awt.FontMetrics;
import java.text.BreakIterator;

/**
 * Verteilt die Breite einer Tabelle nach ihrem Inhalt wie das automatische Tabellenlayout von HTML (Angelo 26.11.2026:
 * im Chat keine gleichmäßig geteilten Spalten). Jede Spalte hat eine Mindestbreite (ihr breitestes Wort) und eine
 * Wunschbreite (ihr längster Text ohne Umbruch). Passen alle Wunschbreiten, bekommt jede Spalte ihre; sonst erhält jede
 * Spalte ihre Mindestbreite und den Rest anteilig zu dem, was ihr bis zur Wunschbreite fehlt. Eine kurze
 * Beschriftungsspalte bleibt so schmal und bricht mehrfach um, die textreiche Spalte bekommt fast den ganzen Rest.
 * Zeilen, die ohnehin untereinander stehen (Listenpunkte, Zeilenumbrüche), zählen nur mit ihrer längsten Zeile.
 *
 * <p>Ein einzelnes Wort beansprucht höchstens einen gleichen Anteil der Breite: längere Stücke (URL, Pfad) brechen
 * zwischen zwei Zeichen um wie im übrigen Chat, und die Tabelle passt immer in die Fläche, ohne waagerecht zu
 * scrollen.
 */
final class ColumnWidths {

    private ColumnWidths() {
    }

    /**
     * Spaltenbreiten, deren Summe höchstens {@code available} ist (genau {@code available}, sobald der Inhalt mehr
     * Platz wünscht).
     *
     * @param minimum   Mindestbreite je Spalte (breitestes Wort samt Rand)
     * @param maximum   Wunschbreite je Spalte (längste Zeile ohne Umbruch samt Rand)
     * @param available verfügbare Breite der ganzen Tabelle
     */
    static int[] distribute(int[] minimum, int[] maximum, int available) {
        int columns = minimum.length;
        int[] widths = new int[columns];
        if (columns == 0) {
            return widths;
        }
        int share = Math.max(1, available / columns);
        int[] low = new int[columns];
        int[] high = new int[columns];
        long lowSum = 0;
        long highSum = 0;
        for (int column = 0; column < columns; column++) {
            low[column] = Math.max(1, Math.min(minimum[column], share));
            high[column] = Math.max(low[column], maximum[column]);
            lowSum += low[column];
            highSum += high[column];
        }
        if (highSum <= available) {
            return high;
        }
        if (lowSum >= available) {
            return low;
        }
        long extra = available - lowSum;
        long range = highSum - lowSum;
        int used = 0;
        int widestRange = 0;
        for (int column = 0; column < columns; column++) {
            widths[column] = low[column] + (int) ((high[column] - low[column]) * extra / range);
            used += widths[column];
            if (high[column] - low[column] > high[widestRange] - low[widestRange]) {
                widestRange = column;
            }
        }
        // Rundungsreste gehen an die Spalte mit dem meisten Text, damit die Tabelle die Fläche genau füllt.
        widths[widestRange] += available - used;
        return widths;
    }

    /** Breite des breitesten Worts (Stück zwischen zwei Umbruchstellen ohne folgende Leerzeichen). */
    static int widestWord(String text, FontMetrics metrics) {
        return widestWord(text, (start, end) -> metrics.stringWidth(text.substring(start, end)));
    }

    /** Wie {@link #widestWord(String, FontMetrics)}, aber die Breite eines Stücks misst der Aufrufer (Schriftwechsel). */
    static int widestWord(String text, SegmentWidth width) {
        BreakIterator breaks = BreakIterator.getLineInstance();
        breaks.setText(text);
        int widest = 0;
        int start = breaks.first();
        for (int end = breaks.next(); end != BreakIterator.DONE; start = end, end = breaks.next()) {
            int trimmed = end;
            while (trimmed > start && Character.isWhitespace(text.charAt(trimmed - 1))) {
                trimmed--;
            }
            if (trimmed > start) {
                widest = Math.max(widest, width.of(start, trimmed));
            }
        }
        return widest;
    }

    /** Misst die Breite des Texts von {@code start} (einschließlich) bis {@code end} (ausschließlich). */
    interface SegmentWidth {
        int of(int start, int end);
    }
}
