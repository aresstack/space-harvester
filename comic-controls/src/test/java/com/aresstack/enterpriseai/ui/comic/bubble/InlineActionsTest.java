package com.aresstack.enterpriseai.ui.comic.bubble;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Buttons inside the text of a thought bubble, right behind their anchor on the same line. */
public class InlineActionsTest {

    private static final String ANCHOR = "1 davon nicht geladen";
    private static final String TEXT = "Wissen: Handbuch samt Unterseiten · 12 Seiten · " + ANCHOR + "\nSucht im Bereich";

    @Test
    public void theActionsSitOnTheLineOfTheAnchorRightBehindIt() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                AgentActivityBubblePanel bubble = new AgentActivityBubblePanel(BubbleSide.LEFT,
                        BubblePalette.windowsPhoneInspired(), "Internes Wissen durchsuchen", TEXT);
                JButton retry = square("R");
                JButton open = square("O");
                bubble.setInlineActions(ANCHOR, Arrays.<JComponent>asList(retry, open));
                bubble.setSize(new Dimension(900, bubble.getPreferredSize().height));
                bubble.doLayout();

                assertEquals("the text reads as before", TEXT, bubble.getExplanation());
                assertTrue(retry.isVisible());
                assertTrue(open.isVisible());
                assertTrue("open right of retry", open.getX() > retry.getX() + retry.getWidth() - 1);
                JComponentArea area = new JComponentArea(retry);
                Rectangle anchorEnd = area.endOf(ANCHOR);
                assertTrue("right behind the anchor", retry.getX() >= anchorEnd.x
                        && retry.getX() - anchorEnd.x < 20);
                assertTrue("same line", Math.abs(retry.getY() + retry.getHeight() / 2
                        - (anchorEnd.y + anchorEnd.height / 2)) <= 2);
            }
        });
    }

    @Test
    public void withoutTheAnchorTheActionsStayHiddenAndNullRemovesThem() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                AgentActivityBubblePanel bubble = new AgentActivityBubblePanel(BubbleSide.LEFT,
                        BubblePalette.windowsPhoneInspired(), "Titel", "Alles geladen");
                JButton retry = square("R");
                bubble.setInlineActions(ANCHOR, Collections.<JComponent>singletonList(retry));
                bubble.setSize(new Dimension(600, bubble.getPreferredSize().height));
                bubble.doLayout();
                assertFalse(retry.isVisible());

                bubble.setInlineActions(null, null);
                assertTrue(bubble.inlineActions().isEmpty());
                assertEquals("Alles geladen", bubble.getExplanation());
            }
        });
    }

    private static JButton square(String text) {
        JButton button = new JButton(text);
        button.setPreferredSize(new Dimension(20, 20));
        return button;
    }

    /** The bubble's text area, found from an inline action (its parent). */
    private static final class JComponentArea {
        private final javax.swing.JTextArea area;

        JComponentArea(JComponent action) {
            this.area = (javax.swing.JTextArea) action.getParent();
        }

        Rectangle endOf(String anchor) {
            try {
                return area.modelToView(area.getText().indexOf(anchor) + anchor.length());
            } catch (javax.swing.text.BadLocationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
