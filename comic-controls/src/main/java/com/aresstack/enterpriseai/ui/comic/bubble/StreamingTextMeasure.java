package com.aresstack.enterpriseai.ui.comic.bubble;

import java.awt.FontMetrics;

/**
 * Fortgeschriebene Messung eines wachsenden Textes für {@link SpeechBubblePanel}: die natürliche Breite
 * (breiteste Zeile) und die geschätzten Umbruchzeilen bei einer Breite.
 *
 * <p>Streaming hängt Deltas an. Die Arbeit je Anhängen hängt von der Länge des Deltas und des letzten,
 * noch unvollständigen Wortes ab, nicht von der Länge des Textes: abgeschlossene Zeilen werden einmal
 * vermessen, abgeschlossene Wörter einmal in den Umbruch gezählt, und die Breite der letzten Zeile wird nur
 * bis zu einem Deckel ({@link #WIDTH_CAP}) verfolgt, der breiter ist als jedes Fenster. Ein Wechsel der
 * Umbruchbreite (Fenstergröße) zählt den Text einmal neu.
 *
 * <p>Ein Text, der am Stück gesetzt wird, liefert dieselben Werte wie derselbe Text in Stücken, weil beide
 * Wege dieselbe Wortfolge durch dieselbe Zählung schicken.
 */
final class StreamingTextMeasure {

    /** Breiter als jedes Fenster: ab hier ist die genaue Breite einer Zeile für die Blase ohne Bedeutung. */
    static final int WIDTH_CAP = 16_384;

    private final FontMetrics metrics;
    private final int spaceWidth;
    private final StringBuilder text = new StringBuilder();
    private int lineStart;               // Beginn der letzten Zeile (hinter dem letzten '\n')
    private int completedLinesWidth;     // breiteste abgeschlossene Zeile, gedeckelt
    private int lastLineWidth;           // Breite der letzten Zeile, gedeckelt, gültig für lastLineWidthLength
    private int lastLineWidthLength = -1;
    private int wrapWidth = -1;          // Breite, für die die Umbruchzählung gilt; -1 = noch nicht gezählt
    private int completedParagraphLines; // Umbruchzeilen aller abgeschlossenen Absätze
    private int paragraphLines;          // Zeilen des laufenden Absatzes nach seinen vollständigen Wörtern
    private int paragraphWidth;          // belegte Breite der laufenden Umbruchzeile nach den vollständigen Wörtern
    private int wordsUpTo;               // Index hinter dem letzten verarbeiteten Leerzeichen

    StreamingTextMeasure(FontMetrics metrics) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.metrics = metrics;
        this.spaceWidth = metrics.charWidth(' ');
    }

    int length() {
        return text.length();
    }

    /** Ersetzt den Text; abgeschlossene Zeilen werden einmal vermessen, der Umbruch bei Bedarf neu gezählt. */
    void set(String value) {
        text.setLength(0);
        text.append(value);
        lineStart = value.lastIndexOf('\n') + 1;
        completedLinesWidth = 0;
        int start = 0;
        while (start < lineStart) {
            int end = value.indexOf('\n', start);
            completedLinesWidth = Math.max(completedLinesWidth, cappedWidth(value.substring(start, end)));
            start = end + 1;
        }
        lastLineWidth = 0;
        lastLineWidthLength = -1;
        wrapWidth = -1;
    }

    /** Hängt ein Delta an; jede darin abgeschlossene Zeile und jedes abgeschlossene Wort wird genau einmal gezählt. */
    void append(String delta) {
        int offset = text.length();
        text.append(delta);
        for (int i = delta.indexOf('\n'); i >= 0; i = delta.indexOf('\n', i + 1)) {
            int lineEnd = offset + i;
            completedLinesWidth = Math.max(completedLinesWidth, cappedWidth(text.substring(lineStart, lineEnd)));
            if (wrapWidth >= 0) {
                feedWordsBefore(lineEnd);
                finishParagraph(lineEnd);
            }
            lineStart = lineEnd + 1;
            lastLineWidth = 0;
        }
        lastLineWidthLength = -1;
        if (wrapWidth >= 0) {
            feedWordsBefore(text.length());
        }
    }

    /** Breite der breitesten Zeile in Pixeln, gedeckelt bei {@link #WIDTH_CAP}. */
    int naturalWidth() {
        return Math.max(completedLinesWidth, lastLineWidth());
    }

    /** Geschätzte Zeilen bei gierigem Wortumbruch auf {@code width}; überlange Wörter brechen mitten im Wort. */
    int wrappedLines(int width) {
        if (width != wrapWidth) {
            rebuildWrap(width);
        }
        return completedParagraphLines + tailLines();
    }

    private int lastLineWidth() {
        if (lastLineWidthLength != text.length()) {
            if (lastLineWidth < WIDTH_CAP) {
                // Jenseits des Deckels bleibt die Zeile "breit genug"; sie wird nicht mehr vermessen.
                lastLineWidth = cappedWidth(text.substring(lineStart));
            }
            lastLineWidthLength = text.length();
        }
        return lastLineWidth;
    }

    private int cappedWidth(String line) {
        return Math.min(WIDTH_CAP, metrics.stringWidth(line));
    }

    private void rebuildWrap(int width) {
        wrapWidth = width;
        completedParagraphLines = 0;
        int start = 0;
        while (start < lineStart) {
            int end = text.indexOf("\n", start);
            startParagraph(start);
            feedWordsBefore(end);
            finishParagraph(end);
            start = end + 1;
        }
        startParagraph(lineStart);
        feedWordsBefore(text.length());
    }

    private void startParagraph(int from) {
        paragraphLines = 1;
        paragraphWidth = 0;
        wordsUpTo = from;
    }

    /** Zählt alle vollständigen, von einem Leerzeichen abgeschlossenen Wörter vor {@code limit}. */
    private void feedWordsBefore(int limit) {
        for (int space = text.indexOf(" ", wordsUpTo); space >= 0 && space < limit;
             space = text.indexOf(" ", wordsUpTo)) {
            count(text.substring(wordsUpTo, space));
            wordsUpTo = space + 1;
        }
    }

    /** Schließt den Absatz ab, der bei {@code end} endet: letztes Wort zählen, Zeilen übernehmen. */
    private void finishParagraph(int end) {
        count(text.substring(wordsUpTo, end));
        completedParagraphLines += paragraphLines;
        startParagraph(end + 1);
    }

    /** Zeilen des laufenden Absatzes inklusive des noch unvollständigen letzten Wortes. */
    private int tailLines() {
        String tail = text.substring(wordsUpTo);
        if (tail.isEmpty()) {
            return paragraphLines;
        }
        int savedLines = paragraphLines;
        int savedWidth = paragraphWidth;
        count(tail);
        int lines = paragraphLines;
        paragraphLines = savedLines;
        paragraphWidth = savedWidth;
        return lines;
    }

    private void count(String word) {
        if (word.isEmpty()) {
            return;
        }
        int width = Math.max(1, wrapWidth);
        int wordWidth = metrics.stringWidth(word);
        if (paragraphWidth > 0 && paragraphWidth + spaceWidth + wordWidth > width) {
            paragraphLines++;
            paragraphWidth = 0;
        }
        if (wordWidth > width) {
            paragraphLines += wordWidth / width;
            paragraphWidth = wordWidth % width;
        } else {
            paragraphWidth += (paragraphWidth > 0 ? spaceWidth : 0) + wordWidth;
        }
    }
}
