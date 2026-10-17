package com.aresstack.enterpriseai.ui.comic.bubble;

import org.junit.Test;

import javax.swing.JLabel;
import java.awt.Font;
import java.awt.FontMetrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Die Messung eines wachsenden Textes darf je Anhängen nicht mit der Textlänge wachsen – auch ohne
 * Zeilenumbrüche (ein einziger Absatz). Gemessen wird in Zeichen, die an die Schriftmetrik gehen.
 */
public class StreamingTextMeasureTest {

    private static final int APPENDS = 4_000;

    @Test
    public void workPerAppendDoesNotGrowWithTheTextWithoutNewlines() {
        CountingMetrics metrics = new CountingMetrics(realMetrics());
        StreamingTextMeasure measure = new StreamingTextMeasure(metrics);
        long firstHalf = 0;
        for (int i = 0; i < APPENDS; i++) {
            measure.append("Wort" + i + " ");
            measure.wrappedLines(400);
            measure.naturalWidth();
            if (i == APPENDS / 2 - 1) {
                firstHalf = metrics.measuredChars;
            }
        }
        long secondHalf = metrics.measuredChars - firstHalf;
        assertTrue("zweite Hälfte misst " + secondHalf + " Zeichen, erste " + firstHalf,
                secondHalf <= firstHalf);
        assertTrue("linear in der Textlänge: " + secondHalf + " für " + measure.length() / 2 + " Zeichen",
                secondHalf <= 4L * measure.length() / 2);
        assertEquals(StreamingTextMeasure.WIDTH_CAP, measure.naturalWidth());
    }

    @Test
    public void streamedAndWholeTextCountTheSameLines() {
        FontMetrics metrics = realMetrics();
        StreamingTextMeasure streamed = new StreamingTextMeasure(metrics);
        StringBuilder full = new StringBuilder();
        String[] deltas = {"Ein ", "Satz,", " der ", "umbricht.\nZweiter Absatz ", "mit   doppelten  ", "Leerzeichen",
                "\n\nLetzter"};
        for (String delta : deltas) {
            streamed.append(delta);
            full.append(delta);
            streamed.wrappedLines(60);
            StreamingTextMeasure whole = new StreamingTextMeasure(metrics);
            whole.set(full.toString());
            assertEquals(full.toString(), whole.wrappedLines(60), streamed.wrappedLines(60));
            assertEquals(full.toString(), whole.wrappedLines(200), streamed.wrappedLines(200));
            assertEquals(full.toString(), whole.naturalWidth(), streamed.naturalWidth());
        }
    }

    private static FontMetrics realMetrics() {
        return new JLabel().getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    }

    /** Zählt, wie viele Zeichen an die Schriftmetrik gehen. */
    private static final class CountingMetrics extends FontMetrics {
        private final FontMetrics delegate;
        long measuredChars;

        CountingMetrics(FontMetrics delegate) {
            super(delegate.getFont());
            this.delegate = delegate;
        }

        @Override
        public int stringWidth(String str) {
            measuredChars += str.length();
            return delegate.stringWidth(str);
        }

        @Override
        public int charWidth(char ch) {
            measuredChars++;
            return delegate.charWidth(ch);
        }

        @Override
        public int charWidth(int codePoint) {
            measuredChars++;
            return delegate.charWidth(codePoint);
        }

        @Override
        public int getHeight() {
            return delegate.getHeight();
        }

        @Override
        public int getAscent() {
            return delegate.getAscent();
        }

        @Override
        public int getDescent() {
            return delegate.getDescent();
        }

        @Override
        public int getLeading() {
            return delegate.getLeading();
        }
    }
}
