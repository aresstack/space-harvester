package com.aresstack.enterpriseai.ui.comic.bubble;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Folded details keep the bubble as narrow as its short main text; unfolding adds height inside the
 * same geometry and never exceeds the row's limit.
 */
public class SpeechBubbleDetailsTest {

    private static final String DETAILS = "Technische Ursache: connection to ki.intern.example failed: "
            + "UnknownHostException\nHinweis: Proxy-Modus AUTO/MANUAL in den Einstellungen prüfen.";

    @Test
    public void detailsStayFoldedAndKeepTheShortBubble() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                SpeechBubblePanel plain = bubble();
                SpeechBubblePanel withDetails = bubble();
                withDetails.setDetailsLabels("Details anzeigen", "Details ausblenden");
                withDetails.setDetails(DETAILS);

                assertFalse(plain.hasDetails());
                assertTrue(withDetails.hasDetails());
                assertFalse("folded by default", withDetails.isDetailsExpanded());
                assertTrue("reachable by keyboard", withDetails.detailsToggle().isFocusable());
                assertFalse("a mouse click leaves the focus where it is",
                        withDetails.detailsToggle().isRequestFocusEnabled());
                assertTrue(withDetails.detailsToggle().getText().startsWith("Details anzeigen"));

                int limit = 600;
                assertTrue("the folded bubble is far narrower than the limit",
                        withDetails.preferredWidthWithin(limit) < limit / 2);
                assertEquals("folded details do not widen beyond the toggle",
                        Math.max(plain.preferredWidthWithin(limit),
                                withDetails.preferredWidthWithin(limit)),
                        withDetails.preferredWidthWithin(limit));
                int foldedHeight = withDetails.preferredHeightForWidth(260);
                assertTrue("the toggle adds a little height", foldedHeight > plain.preferredHeightForWidth(260));
            }
        });
    }

    @Test
    public void unfoldingGrowsInsideTheLimitAndToggleFoldsBack() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                SpeechBubblePanel bubble = bubble();
                bubble.setDetailsLabels("Details anzeigen", "Details ausblenden");
                bubble.setDetails(DETAILS);
                int limit = 600;
                int foldedWidth = bubble.preferredWidthWithin(limit);
                int foldedHeight = bubble.preferredHeightForWidth(foldedWidth);

                bubble.detailsToggle().doClick();
                assertTrue(bubble.isDetailsExpanded());
                assertTrue(bubble.detailsToggle().getText().startsWith("Details ausblenden"));
                int openWidth = bubble.preferredWidthWithin(limit);
                assertTrue("never wider than the row allows", openWidth <= limit);
                assertTrue("unfolded details add height", bubble.preferredHeightForWidth(openWidth) > foldedHeight);
                assertEquals(DETAILS, bubble.getDetails());

                bubble.setDetailsExpanded(false);
                assertEquals(foldedWidth, bubble.preferredWidthWithin(limit));
                assertEquals(foldedHeight, bubble.preferredHeightForWidth(foldedWidth));

                bubble.setDetails(null);
                assertFalse(bubble.hasDetails());
                assertFalse(bubble.isDetailsExpanded());
            }
        });
    }

    private static SpeechBubblePanel bubble() {
        return new SpeechBubblePanel(BubbleSide.LEFT, new Color(0xC94C4C), Color.WHITE, "Fehler",
                "Der KI-Dienst ist nicht erreichbar.");
    }
}
