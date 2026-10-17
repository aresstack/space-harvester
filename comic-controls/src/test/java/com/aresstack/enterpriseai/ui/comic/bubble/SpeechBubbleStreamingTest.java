package com.aresstack.enterpriseai.ui.comic.bubble;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.assertEquals;

/**
 * {@link SpeechBubblePanel#appendText(String)} schreibt die Messungen fort, statt den Text neu zu vermessen.
 * Eine Blase, die ihren Text stückweise bekam, muss in Breite und Höhe exakt einer Blase gleichen, die den
 * ganzen Text auf einmal bekam – auch nach Absätzen, Breitenwechseln und einem späteren {@code setText}.
 */
public class SpeechBubbleStreamingTest {

    private static final String[] DELTAS = {
            "Die ", "Kündigungsfrist ", "beträgt drei Monate zum Quartalsende.", "\n", "Die Frist gilt ",
            "für beide Seiten; eine Kündigung muss schriftlich erfolgen.\nEin sehr langer ", "Absatz, der in einer ",
            "schmalen Spalte über viele Zeilen umbrechen muss, damit man sieht, ob die Zeilenhöhe nach dem ",
            "Anhängen noch zum Text passt.\n\n", "Donaudampfschifffahrtsgesellschaftskapitänsmützenabzeichen ",
            "kurz", "\nSchluss."
    };

    @Test
    public void appendedTextMeasuresLikeTextSetAtOnce() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                SpeechBubblePanel streamed = new SpeechBubblePanel(BubbleSide.LEFT, Color.BLUE, Color.WHITE,
                        "Assistent", "");
                StringBuilder full = new StringBuilder();
                int[] widths = {140, 260, 420, 900};
                for (int i = 0; i < DELTAS.length; i++) {
                    streamed.appendText(DELTAS[i]);
                    full.append(DELTAS[i]);
                    // Zwischendurch messen, damit Caches gefüllt sind und später fortgeschrieben werden müssen.
                    streamed.preferredHeightForWidth(widths[i % widths.length]);
                    streamed.preferredWidthWithin(widths[(i + 1) % widths.length]);
                    assertSameMeasures(full.toString(), streamed, widths);
                }
                streamed.setText("Neu gesetzt\nmit zwei Zeilen");
                assertSameMeasures("Neu gesetzt\nmit zwei Zeilen", streamed, widths);
                streamed.appendText(" und mehr");
                assertSameMeasures("Neu gesetzt\nmit zwei Zeilen und mehr", streamed, widths);
            }
        });
    }

    /** Ein langer Absatz ohne Zeilenumbruch, in Stücken, die Wörter zerschneiden. */
    @Test
    public void appendedSingleParagraphMeasuresLikeTextSetAtOnce() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                StringBuilder paragraph = new StringBuilder();
                for (int i = 0; i < 400; i++) {
                    paragraph.append(i % 7 == 0 ? "Donaudampfschifffahrt" : "Wort").append(i).append(' ');
                }
                String full = paragraph.toString().trim();
                SpeechBubblePanel streamed = new SpeechBubblePanel(BubbleSide.LEFT, Color.BLUE, Color.WHITE,
                        "Assistent", "");
                int[] widths = {140, 420, 900};
                int cut = 7;
                for (int from = 0, piece = 0; from < full.length(); from += cut, piece++) {
                    streamed.appendText(full.substring(from, Math.min(full.length(), from + cut)));
                    streamed.preferredHeightForWidth(widths[piece % widths.length]);
                    streamed.preferredWidthWithin(widths[(piece + 1) % widths.length]);
                    if (piece % 50 == 0) {
                        assertSameMeasures(full.substring(0, Math.min(full.length(), from + cut)), streamed, widths);
                    }
                }
                assertSameMeasures(full, streamed, widths);
            }
        });
    }

    private static void assertSameMeasures(String text, SpeechBubblePanel streamed, int[] widths) {
        SpeechBubblePanel fresh = new SpeechBubblePanel(BubbleSide.LEFT, Color.BLUE, Color.WHITE, "Assistent",
                text);
        assertEquals(text, streamed.getText());
        for (int width : widths) {
            assertEquals("Breite innerhalb " + width + " nach \"" + text + "\"",
                    fresh.preferredWidthWithin(width), streamed.preferredWidthWithin(width));
            assertEquals("Höhe bei " + width + " nach \"" + text + "\"",
                    fresh.preferredHeightForWidth(width), streamed.preferredHeightForWidth(width));
        }
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw ex;
        }
    }
}
