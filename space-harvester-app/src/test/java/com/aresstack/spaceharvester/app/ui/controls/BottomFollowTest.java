package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mitrollen nur ganz unten (Angelo 26.11.2026): hochrollen hält es an, ganz nach unten rollen setzt es fort. */
class BottomFollowTest {

    private static final int VIEW = 100;

    @Test
    void growingContentIsFollowedWhileTheViewStandsAtTheEnd() throws Exception {
        JScrollBar bar = bar();
        BottomFollow bottom = BottomFollow.install(bar);
        grow(bar, 300);
        follow(bottom);
        assertEquals(300 - VIEW, bar.getValue());
        grow(bar, 500);
        follow(bottom);
        assertEquals(500 - VIEW, bar.getValue());
        assertTrue(bottom.following());
    }

    @Test
    void scrollingUpStopsFollowingWhileTheAnswerKeepsGrowing() throws Exception {
        JScrollBar bar = bar();
        BottomFollow bottom = BottomFollow.install(bar);
        grow(bar, 300);
        follow(bottom);
        // Der Nutzer rollt hoch (Mausrad, Leiste oder Tastatur – alle setzen den Wert der Leiste).
        SwingUtilities.invokeAndWait(() -> bar.setValue(120));
        assertFalse(bottom.following());
        for (int height = 400; height <= 900; height += 100) {
            grow(bar, height);
            follow(bottom);
            assertEquals(120, bar.getValue(), "der Verlauf bleibt stehen, wo der Nutzer liest");
        }
        // Schon eine Zeile hoch reicht: nur ganz unten wird mitgerollt.
        SwingUtilities.invokeAndWait(() -> bar.setValue(900 - VIEW));
        SwingUtilities.invokeAndWait(() -> bar.setValue(900 - VIEW - bar.getUnitIncrement(-1)));
        assertFalse(bottom.following());
    }

    @Test
    void scrollingBackToTheEndResumesFollowing() throws Exception {
        JScrollBar bar = bar();
        BottomFollow bottom = BottomFollow.install(bar);
        grow(bar, 300);
        follow(bottom);
        SwingUtilities.invokeAndWait(() -> bar.setValue(0));
        grow(bar, 400);
        follow(bottom);
        assertEquals(0, bar.getValue());
        SwingUtilities.invokeAndWait(() -> bar.setValue(bar.getMaximum()));
        assertTrue(bottom.following());
        grow(bar, 600);
        follow(bottom);
        assertEquals(600 - VIEW, bar.getValue());
    }

    @Test
    void shrinkingContentKeepsFollowing() throws Exception {
        JScrollBar bar = bar();
        BottomFollow bottom = BottomFollow.install(bar);
        grow(bar, 600);
        follow(bottom);
        // Die Markdown-Ansicht rendert neu und wird kürzer: das Modell setzt den Wert zurück, das ist kein Hochrollen.
        grow(bar, 400);
        assertTrue(bottom.following());
        grow(bar, 700);
        follow(bottom);
        assertEquals(700 - VIEW, bar.getValue());
    }

    @Test
    void jumpResumesFollowingAfterTheUserScrolledUp() throws Exception {
        JScrollBar bar = bar();
        BottomFollow bottom = BottomFollow.install(bar);
        grow(bar, 300);
        follow(bottom);
        SwingUtilities.invokeAndWait(() -> bar.setValue(10));
        grow(bar, 500);
        bottom.jump();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(500 - VIEW, bar.getValue());
        assertTrue(bottom.following());
    }

    @Test
    void theMouseWheelUpInARealScrollPaneStopsFollowingAndScrollingDownResumesIt() throws Exception {
        javax.swing.JPanel content = new javax.swing.JPanel();
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(content,
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        BottomFollow[] bottom = new BottomFollow[1];
        SwingUtilities.invokeAndWait(() -> {
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            bottom[0] = BottomFollow.install(scroll.getVerticalScrollBar());
            scroll.setSize(300, 200);
        });
        stream(scroll, content, bottom[0], 600);
        JScrollBar bar = scroll.getVerticalScrollBar();
        int end = bar.getMaximum() - bar.getVisibleAmount();
        assertEquals(end, bar.getValue());
        SwingUtilities.invokeAndWait(() -> scroll.dispatchEvent(wheel(scroll, -1)));
        int reading = bar.getValue();
        assertTrue(reading < end);
        assertFalse(bottom[0].following());
        stream(scroll, content, bottom[0], 900);
        assertEquals(reading, bar.getValue(), "die Antwort schreibt weiter, der Verlauf bleibt stehen");
        for (int notch = 0; notch < 100 && bar.getValue() < bar.getMaximum() - bar.getVisibleAmount(); notch++) {
            SwingUtilities.invokeAndWait(() -> scroll.dispatchEvent(wheel(scroll, 1)));
        }
        assertTrue(bottom[0].following());
        stream(scroll, content, bottom[0], 1200);
        assertEquals(bar.getMaximum() - bar.getVisibleAmount(), bar.getValue());
    }

    /** Die Antwort wächst auf {@code height}; wie im Chat rollt danach {@link BottomFollow#follow()}. */
    private static void stream(javax.swing.JScrollPane scroll, javax.swing.JPanel content, BottomFollow bottom,
                               int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            content.setPreferredSize(new java.awt.Dimension(280, height));
            content.revalidate();
            scroll.doLayout();
            scroll.getViewport().doLayout();
        });
        follow(bottom);
    }

    private static java.awt.event.MouseWheelEvent wheel(javax.swing.JScrollPane target, int clicks) {
        return new java.awt.event.MouseWheelEvent(target, java.awt.event.MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 10, 10, 10, 10, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, clicks, clicks);
    }

    private static JScrollBar bar() {
        JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL);
        bar.setValues(0, VIEW, 0, VIEW);
        return bar;
    }

    /** Der Inhalt wächst (oder schrumpft) auf {@code height}; der Wert bleibt, soweit das Modell ihn lässt. */
    private static void grow(JScrollBar bar, int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> bar.setValues(Math.min(bar.getValue(), height - VIEW), VIEW, 0, height));
    }

    private static void follow(BottomFollow bottom) throws Exception {
        bottom.follow();
        SwingUtilities.invokeAndWait(() -> { });
    }
}
