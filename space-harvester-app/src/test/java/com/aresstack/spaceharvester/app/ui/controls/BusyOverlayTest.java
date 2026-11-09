package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Der Ring liegt über dem Inhalt, blendet ihn ab, lässt die Maus durch und fängt sie nur im Knopf „Abbrechen“; die
 * Animation läuft nur, solange der Schleier gezeigt wird.
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
    void theMouseGoesThroughTheVeilExceptOnTheCancelButton() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea content = new JTextArea("Inhalt");
            BusyOverlay overlay = new BusyOverlay(content);
            layout(overlay, 300, 200);
            overlay.setBusy(true);
            layout(overlay, 300, 200);

            assertThat(deepest(overlay, 10, 10)).as("ohne Knopf: alles geht an den Inhalt").isSameAs(content);
            assertThat(overlay.cancelShown()).isFalse();

            AtomicInteger cancelled = new AtomicInteger();
            overlay.onCancel(cancelled::incrementAndGet);
            layout(overlay, 300, 200);
            assertThat(overlay.cancelShown()).isTrue();
            Point middle = new Point(150, 100);
            assertThat(overlay.cancelButton().getBounds().contains(middle)).as("der Knopf sitzt in der Mitte")
                    .isTrue();
            assertThat(deepest(overlay, middle.x, middle.y)).isSameAs(overlay.cancelButton());
            assertThat(deepest(overlay, 10, 10)).as("neben dem Knopf weiter der Inhalt").isSameAs(content);
            overlay.cancelButton().doClick(0);
            assertThat(cancelled.get()).isEqualTo(1);

            overlay.onCancel(null);
            assertThat(overlay.cancelShown()).isFalse();
            assertThat(deepest(overlay, middle.x, middle.y)).isSameAs(content);
        });
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
