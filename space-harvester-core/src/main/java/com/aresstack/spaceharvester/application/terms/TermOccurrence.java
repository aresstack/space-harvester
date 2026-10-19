package com.aresstack.spaceharvester.application.terms;

/**
 * Eine Fundstelle als Zeichenpositionen (UTF-16 wie {@link String#substring}) im projizierten Text der Seite
 * ({@code PageText#text()}) derselben Eingabe ({@code inputSha256}).
 *
 * @param start erste Position
 * @param end   Position nach dem Begriff, bei Akronymen einschließlich eines Plural-{@code s}
 */
public record TermOccurrence(int start, int end) {

    public TermOccurrence {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Ungültige Fundstelle");
        }
    }
}
