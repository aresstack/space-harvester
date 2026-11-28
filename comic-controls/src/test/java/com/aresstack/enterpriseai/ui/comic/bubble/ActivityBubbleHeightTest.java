package com.aresstack.enterpriseai.ui.comic.bubble;

import org.junit.Test;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;

import static org.junit.Assert.assertTrue;

/** Die gelbe Aktivitätsblase wächst mit ihren Zeilen: die letzte Zeile wird nie abgeschnitten. */
public class ActivityBubbleHeightTest {

    @Test
    public void everyLineOfTheActivityFitsWithAndWithoutProgressBar() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                AgentActivityBubblePanel bubble = new AgentActivityBubblePanel(BubbleSide.LEFT,
                        BubblePalette.windowsPhoneInspired(), "Antwort wird vorbereitet", "Überlegt …");
                bubble.setHeaderTimestamp(0L);
                bubble.setMaximumBubbleWidth(640);
                JPanel transcript = new JPanel();
                transcript.setLayout(new BoxLayout(transcript, BoxLayout.Y_AXIS));
                BubbleMessageRow row = new BubbleMessageRow(bubble, BubbleSide.LEFT);
                transcript.add(row);
                transcript.setSize(800, 2000);
                StringBuilder body = new StringBuilder("Erster Schritt");
                try {
                    for (int line = 2; line <= 8; line++) {
                        body.append("\nSchritt ").append(line);
                        for (String marker : new String[]{"", " [[bar:1/3]]"}) {
                            bubble.updateActivity("Antwort wird vorbereitet", body + marker);
                            layOut(transcript);
                            JTextArea text = textOf(bubble);
                            assertTrue(line + " Zeilen" + marker + ": Text " + text.getHeight() + " px, braucht "
                                            + text.getPreferredSize().height + " px",
                                    text.getHeight() >= text.getPreferredSize().height);
                        }
                    }
                } finally {
                    bubble.stopAnimation();
                }
            }
        });
    }

    private static JTextArea textOf(Container bubble) {
        for (Component child : bubble.getComponents()) {
            if (child instanceof JTextArea) {
                return (JTextArea) child;
            }
        }
        throw new AssertionError("kein Text in der Blase");
    }

    private static void layOut(Component component) {
        component.doLayout();
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                layOut(child);
            }
        }
    }
}
