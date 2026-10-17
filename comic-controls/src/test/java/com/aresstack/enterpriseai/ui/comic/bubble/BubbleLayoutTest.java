package com.aresstack.enterpriseai.ui.comic.bubble;

import org.junit.Test;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BubbleLayoutTest {

    private static final String LONG_TEXT = "Ein langer Absatz, der in einer schmalen Spalte über viele Zeilen umbrechen "
            + "muss, damit man sieht, ob die Zeilenhöhe nach dem Verkleinern noch zum Text passt. "
            + "Ein langer Absatz, der in einer schmalen Spalte über viele Zeilen umbrechen muss.";

    @Test
    public void narrowingTheTranscriptGrowsTheRowInOneLayoutPass() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                JPanel list = new JPanel();
                list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
                SpeechBubblePanel bubble = new SpeechBubblePanel(BubbleSide.LEFT, Color.BLUE, Color.WHITE,
                        "Assistent", LONG_TEXT);
                BubbleMessageRow row = new BubbleMessageRow(bubble, BubbleSide.LEFT);
                row.setAlignmentX(0f);
                list.add(row);

                list.setSize(900, 2000);
                layOut(list, row);
                int wideHeight = row.getHeight();

                list.setSize(260, 2000); // window narrowed: the row still has its old width
                layOut(list, row);        // one layout pass, as after a real resize

                assertEquals("row takes the new width", 260, row.getWidth());
                assertTrue("row grew for the narrower wrap", row.getHeight() > wideHeight);
                assertTrue("bubble is not clipped by its row",
                        bubble.getY() + bubble.getHeight() <= row.getHeight());
                assertEquals("bubble height matches the wrap at its final width",
                        bubble.preferredHeightForWidth(bubble.getWidth()), bubble.getHeight());
            }
        });
    }

    @Test
    public void preferredWidthNeverExceedsTheLimit() throws Exception {
        onEdt(new Runnable() {
            @Override
            public void run() {
                SpeechBubblePanel bubble = new SpeechBubblePanel(BubbleSide.RIGHT, Color.BLUE, Color.WHITE,
                        "Du", "Hallo");
                assertTrue(bubble.preferredWidthWithin(60) <= 60);
                assertTrue(bubble.preferredWidthWithin(500) <= 500);
            }
        });
    }

    /** What a validation pass does after a resize (headless components have no peer to validate). */
    private static void layOut(JPanel list, BubbleMessageRow row) {
        list.invalidate();
        list.doLayout();
        row.doLayout();
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw (RuntimeException) ex.getCause();
        }
    }
}
