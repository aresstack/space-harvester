package com.aresstack.enterpriseai.ui.comic.bubble;

import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Knöpfe am rechten Rand der Kopfzeile: direkt links vom Zeitstempel, egal in welcher Reihenfolge gesetzt. */
public class HeaderTrailingAccessoryTest {

    @Test
    public void trailingButtonsSitDirectlyLeftOfTheTimestampAtTheRightEdge() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                for (boolean timestampFirst : new boolean[]{true, false}) {
                    SpeechBubblePanel bubble = new SpeechBubblePanel(BubbleSide.LEFT, Color.WHITE, Color.BLACK,
                            "Assistent", "Ein erfundener Text, lang genug für eine breite Blase mit Platz.");
                    AtomicInteger qr = new AtomicInteger();
                    JPanel actions = new JPanel();
                    actions.setOpaque(false);
                    MessageQrButton qrButton = new MessageQrButton(Color.DARK_GRAY, qr::incrementAndGet);
                    actions.add(qrButton);
                    if (timestampFirst) {
                        bubble.setHeaderTimestamp(0L);
                        bubble.setHeaderTrailingAccessory(actions);
                    } else {
                        bubble.setHeaderTrailingAccessory(actions);
                        bubble.setHeaderTimestamp(0L);
                    }
                    bubble.setSize(bubble.preferredWidthWithin(500) + 200, 200);
                    layout(bubble);
                    Container row = actions.getParent();
                    Component last = row.getComponent(row.getComponentCount() - 1);
                    assertTrue("Zeitstempel ganz rechts", last instanceof javax.swing.JLabel);
                    int gap = last.getX() - (actions.getX() + actions.getWidth());
                    assertTrue("direkt links vom Zeitstempel: " + gap, gap >= 0 && gap <= 6);
                    assertTrue("rechts im Kopf, nicht am Namen", actions.getX() > row.getComponent(0).getWidth() + 20);
                    qrButton.doClick();
                    assertEquals(1, qr.get());
                }
            }
        });
    }

    private static void layout(Component component) {
        component.doLayout();
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                layout(child);
            }
        }
    }
}
