package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Der Ring liegt über dem Inhalt, blendet ihn ab und lässt die Maus durch; mit Abbrechen sitzt in seiner Mitte die
 * Esc-Fläche (Handcursor, Tooltip, Klick, leuchtet rot unter der Maus), und Esc bricht ab. Die Animation läuft
 * nur, solange der Schleier gezeigt wird.
 */
class BusyOverlayTest {

    @Test
    void theVeilCoversTheContentAndDimsItOnlyWhileBusy() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea content = new JTextArea("Treffer 1\nTreffer 2");
            content.setBackground(Color.WHITE);
            BusyOverlay overlay = new BusyOverlay(content);
            overlay.setVeilColor(new Color(240, 240, 240));
            overlay.setHighlight(Color.BLUE);
            layout(overlay, 300, 200);

            assertThat(overlay.getPreferredSize()).isEqualTo(content.getPreferredSize());
            assertThat(content.getBounds()).isEqualTo(new java.awt.Rectangle(0, 0, 300, 200));
            Color idle = pixel(overlay, 5, 5);
            assertThat(idle).as("ohne Schleier der weiße Inhalt").isEqualTo(Color.WHITE);

            overlay.setBusy(true);
            layout(overlay, 300, 200);
            Color dimmed = pixel(overlay, 5, 5);
            assertThat(dimmed).as("abgeblendet, aber nicht deckend").isNotEqualTo(Color.WHITE);
            assertThat(dimmed.getRed()).isBetween(238, 243);
            // Ein Punkt des Rings oben in der Mitte (Radius 30): blau statt grau.
            Color dot = pixel(overlay, 150, 100 - 30);
            assertThat(dot.getBlue()).isGreaterThan(dot.getRed() + 40);

            overlay.setBusy(false);
            assertThat(pixel(overlay, 5, 5)).isEqualTo(Color.WHITE);
        });
    }

    @Test
    void withoutCancelTheMouseGoesThroughEverywhereAndTheMiddleStaysEmpty() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea content = new JTextArea("Inhalt");
            BusyOverlay overlay = new BusyOverlay(content);
            overlay.setVeilColor(new Color(240, 240, 240));
            layout(overlay, 300, 200);
            overlay.setBusy(true);
            layout(overlay, 300, 200);

            assertThat(deepest(overlay, 10, 10)).isSameAs(content);
            assertThat(deepest(overlay, 150, 100)).as("auch mitten im Ring geht die Maus durch").isSameAs(content);
            assertThat(overlay.cancelShown()).isFalse();
            Color middle = pixel(overlay, 150, 100);
            assertThat(middle.getRed()).as("Mitte leer: nur der Schleier").isBetween(238, 243);
        });
    }

    @Test
    void withCancelOnlyTheEscDiscCatchesTheMouseAndGlowsRedUnderIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea content = new JTextArea("Inhalt");
            BusyOverlay overlay = new BusyOverlay(content);
            overlay.setHighlight(Color.BLUE);
            overlay.setVeilColor(new Color(240, 240, 240));
            AtomicInteger cancelled = new AtomicInteger();
            overlay.onCancel(cancelled::incrementAndGet);
            overlay.setBusy(true);
            layout(overlay, 300, 200);

            Component stop = overlay.cancelControl();
            Point middle = new Point(150, 100);
            assertThat(stop.getBounds().contains(middle)).as("die Fläche sitzt in der Mitte").isTrue();
            assertThat(deepest(overlay, middle.x, middle.y)).isSameAs(stop);
            assertThat(stop.getCursor().getType()).isEqualTo(Cursor.HAND_CURSOR);
            assertThat(overlay.cancelControl().getToolTipText()).isEqualTo(BusyOverlay.CANCEL);
            // Rund: in der Ecke des Rechtecks geht die Maus schon durch, der Ring selbst fängt nie.
            assertThat(deepest(overlay, stop.getX() + 1, stop.getY() + 1)).isSameAs(content);
            assertThat(deepest(overlay, 150, 100 - 30)).as("auf einem Punkt des Rings").isSameAs(content);
            assertThat(deepest(overlay, 10, 10)).isSameAs(content);

            // Neutral gedämpft in Ruhe (kein Rot), leuchtend rot erst unter der Maus; der Text ist weiß.
            Color muted = pixel(overlay, 150, 100 - 12);
            assertThat(Math.abs(muted.getRed() - muted.getGreen())).as("ein Grauton").isLessThan(12);
            stop.dispatchEvent(new MouseEvent(stop, MouseEvent.MOUSE_ENTERED, 0, 0, 10, 10, 0, false));
            assertThat(overlay.hovering()).isTrue();
            Color lit = pixel(overlay, 150, 100 - 12);
            assertThat(lit.getRed()).isGreaterThan(lit.getGreen() + 100);
            stop.dispatchEvent(new MouseEvent(stop, MouseEvent.MOUSE_EXITED, 0, 0, 10, 10, 0, false));
            assertThat(overlay.hovering()).isFalse();

            overlay.cancel();
            assertThat(cancelled.get()).isEqualTo(1);
            overlay.setBusy(false);
            overlay.cancel();
            assertThat(cancelled.get()).as("ohne Schleier kein Abbrechen").isEqualTo(1);

            overlay.setBusy(true);
            overlay.onCancel(null);
            layout(overlay, 300, 200);
            assertThat(overlay.cancelShown()).isFalse();
            assertThat(deepest(overlay, middle.x, middle.y)).isSameAs(content);
            overlay.cancel();
            assertThat(cancelled.get()).isEqualTo(1);
        });
    }

    /** Eine Größe überall: Abbrechen macht den Ring nicht größer. */
    @Test
    void theRingHasTheSameSizeWithAndWithoutCancel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BusyOverlay overlay = new BusyOverlay(new JPanel());
            overlay.setVeilColor(new Color(240, 240, 240));
            overlay.setHighlight(Color.BLUE);
            overlay.setBusy(true);
            layout(overlay, 300, 200);
            Color plain = pixel(overlay, 150, 100 - 30);
            java.awt.Rectangle before = overlay.ringControl().getBounds();
            overlay.onCancel(() -> { });
            layout(overlay, 300, 200);
            assertThat(overlay.ringControl().getBounds()).isEqualTo(before);
            Color withCancel = pixel(overlay, 150, 100 - 30);
            assertThat(withCancel).as("oberster Punkt an derselben Stelle").isEqualTo(plain);
            assertThat(withCancel.getBlue()).isGreaterThan(withCancel.getRed() + 40);
        });
    }

    @Test
    void escapeIsBoundOnlyWhileTheVeilShows() throws Exception {
        AtomicInteger cancelled = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            BusyOverlay overlay = new BusyOverlay(new JPanel());
            overlay.onCancel(cancelled::incrementAndGet);
            javax.swing.JComponent veil = (javax.swing.JComponent) overlay.getComponent(0);
            Object key = veil.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(javax.swing.KeyStroke.getKeyStroke("ESCAPE"));
            assertThat(key).isNotNull();
            assertThat(veil.isVisible()).as("nicht beschäftigt: Schleier und Esc-Bindung inaktiv").isFalse();
            overlay.setBusy(true);
            assertThat(veil.isVisible()).isTrue();
            veil.getActionMap().get(key).actionPerformed(null);
            // Erst leuchtet die Fläche kurz rot auf, dann läuft der Abbruch.
            assertThat(overlay.flashing()).isTrue();
            assertThat(cancelled.get()).isZero();
        });
        for (int i = 0; i < 50 && cancelled.get() == 0; i++) {
            Thread.sleep(20);
        }
        assertThat(cancelled.get()).isEqualTo(1);
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** Ein Esc, das noch aufleuchtet, bricht keine Arbeit ab, die danach begonnen hat (Codex-Befund #561). */
    @Test
    void aPendingEscapeDoesNotCancelTheNextBusyWork() throws Exception {
        AtomicInteger cancelled = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            BusyOverlay overlay = new BusyOverlay(new JPanel());
            overlay.onCancel(cancelled::incrementAndGet);
            overlay.setBusy(true);
            overlay.flashAndCancel();
            assertThat(overlay.flashing()).isTrue();
            // Neue Suche: Schleier weg und wieder da, noch während des Aufleuchtens.
            overlay.setBusy(false);
            assertThat(overlay.flashing()).isFalse();
            overlay.setBusy(true);
        });
        Thread.sleep(BusyOverlay.FLASH_MILLIS * 3);
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(cancelled.get()).as("das alte Esc trifft die neue Arbeit nicht").isZero();
    }

    @Test
    void theAnimationRunsOnlyWhileShownAndBusy() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BusyOverlay overlay = new BusyOverlay(new JPanel());
            overlay.setBusy(true);
            assertThat(overlay.animating()).as("nicht auf dem Bildschirm: kein Takt").isFalse();
            assertThat(overlay.phase()).isZero();
            overlay.setText("Suche läuft");
            assertThat(overlay.text()).isEqualTo("Suche läuft");
            overlay.setBusy(false);
            assertThat(overlay.animating()).isFalse();
        });
    }

    private static void layout(Component component, int width, int height) {
        component.setSize(new Dimension(width, height));
        component.doLayout();
        for (Component child : ((java.awt.Container) component).getComponents()) {
            child.setSize(width, height);
            child.doLayout();
        }
    }

    private static Component deepest(BusyOverlay overlay, int x, int y) {
        return SwingUtilities.getDeepestComponentAt(overlay, x, y);
    }

    private static Color pixel(BusyOverlay overlay, int x, int y) {
        BufferedImage image = new BufferedImage(overlay.getWidth(), overlay.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        overlay.printAll(g);
        g.dispose();
        return new Color(image.getRGB(x, y));
    }
}
