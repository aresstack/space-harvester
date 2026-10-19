package com.aresstack.spaceharvester.application.enrichment.statements;

/**
 * Ein Zeichenbereich {@code [start, end)} im Text der Projektion (wie {@code TextSpan} im Enterprise AI Client).
 *
 * @param start erstes Zeichen
 * @param end   Position danach
 */
public record TextSpan(int start, int end) implements Comparable<TextSpan> {

    public TextSpan {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Ungültiger Bereich " + start + "-" + end);
        }
    }

    public int length() {
        return end - start;
    }

    /** Ob beide Bereiche mindestens ein Zeichen teilen. */
    public boolean overlaps(TextSpan other) {
        return start < other.end && other.start < end;
    }

    /** Der abgedeckte Text von {@code text}. */
    public String of(String text) {
        return text.substring(start, end);
    }

    @Override
    public int compareTo(TextSpan other) {
        return start != other.start ? Integer.compare(start, other.start) : Integer.compare(end, other.end);
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }
}
