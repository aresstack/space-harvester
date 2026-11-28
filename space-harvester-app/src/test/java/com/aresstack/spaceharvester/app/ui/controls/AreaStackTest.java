package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Die offenen Bereiche einer Seitenleiste teilen sich die Höhe gleich (Angelo 28.11.2026). */
class AreaStackTest {

    @Test
    void oneAreaGetsAllTheHeightTwoShareItThreeGetThirds() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AreaStack stack = new AreaStack();
            JPanel first = new JPanel();
            JPanel second = new JPanel();
            JPanel third = new JPanel();
            stack.setSize(300, 600 + 2 * AreaStack.GAP);

            stack.show(List.of(first));
            stack.doLayout();
            assertThat(first.getBounds()).isEqualTo(new java.awt.Rectangle(0, 0, 300, 600 + 2 * AreaStack.GAP));

            stack.show(List.of(first, second, third));
            stack.doLayout();
            assertThat(first.getHeight()).isEqualTo(200);
            assertThat(second.getHeight()).isEqualTo(200);
            assertThat(third.getHeight()).isEqualTo(200);
            assertThat(second.getY()).isEqualTo(200 + AreaStack.GAP);
            assertThat(third.getY() + third.getHeight()).as("unten steht nichts über").isEqualTo(stack.getHeight());

            // Ein geschlossener Bereich bleibt unsichtbar in der Fläche; die übrigen teilen neu, in neuer Reihenfolge.
            stack.show(List.of(third, first));
            stack.doLayout();
            assertThat(second.isVisible()).isFalse();
            assertThat(second.getParent()).isSameAs(stack);
            assertThat(third.getY()).isZero();
            assertThat(first.getY()).isEqualTo(third.getHeight() + AreaStack.GAP);
            assertThat(first.getHeight() + third.getHeight() + AreaStack.GAP).isEqualTo(stack.getHeight());
            assertThat(stack.shown()).containsExactly(third, first);
        });
    }

    /** Am Trenner zwischen zwei Bereichen zieht man ihre Höhen; die übrigen bleiben, und es wird gemeldet. */
    @Test
    void theDividerBetweenTwoAreasPullsTheirHeights() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AreaStack stack = new AreaStack();
            JPanel first = new JPanel();
            JPanel second = new JPanel();
            JPanel third = new JPanel();
            stack.setSize(300, 600 + 2 * AreaStack.GAP);
            stack.show(List.of(first, second, third));
            stack.doLayout();
            int[] reported = {0};
            stack.onShares(() -> reported[0]++);
            int placed = 0;
            int[] layouts = {placed};
            stack.onLayout(() -> layouts[0]++);

            // Mit der Maus am ersten Trenner 50 nach unten.
            int y = second.getY() - AreaStack.GAP / 2;
            mouse(stack, java.awt.event.MouseEvent.MOUSE_PRESSED, y);
            mouse(stack, java.awt.event.MouseEvent.MOUSE_DRAGGED, y + 50);
            mouse(stack, java.awt.event.MouseEvent.MOUSE_RELEASED, y + 50);

            assertThat(first.getHeight()).isEqualTo(250);
            assertThat(second.getHeight()).isEqualTo(150);
            assertThat(third.getHeight()).as("der dritte behält seine Höhe").isEqualTo(200);
            assertThat(reported[0]).isEqualTo(1);
            assertThat(layouts[0]).as("wer auf Höhe der Bereiche sitzt, zieht nach").isGreaterThan(0);

            // Kein Bereich wird kleiner als die Untergrenze, so weit man auch zieht.
            stack.pull(1, -1000);
            assertThat(first.getHeight()).isEqualTo(AreaStack.SMALLEST_AREA);
            assertThat(second.getHeight()).isEqualTo(400 - AreaStack.SMALLEST_AREA);

            // Die Anteile bleiben, wenn die Leiste höher wird oder ein Bereich zwischendurch zu war.
            stack.show(List.of(first, third));
            stack.doLayout();
            stack.show(List.of(first, second, third));
            stack.setSize(300, 1200 + 2 * AreaStack.GAP);
            stack.doLayout();
            assertThat(first.getHeight()).isEqualTo(2 * AreaStack.SMALLEST_AREA);
            assertThat(third.getHeight()).isEqualTo(400);
            assertThat(stack.share(first)).isLessThan(stack.share(second));
        });
    }

    private static void mouse(AreaStack stack, int id, int y) {
        java.awt.event.MouseEvent event = new java.awt.event.MouseEvent(stack, id, System.currentTimeMillis(),
                java.awt.event.MouseEvent.BUTTON1_DOWN_MASK, 20, y, 1, false, java.awt.event.MouseEvent.BUTTON1);
        for (java.awt.event.MouseListener listener : stack.getMouseListeners()) {
            if (id == java.awt.event.MouseEvent.MOUSE_PRESSED) {
                listener.mousePressed(event);
            } else if (id == java.awt.event.MouseEvent.MOUSE_RELEASED) {
                listener.mouseReleased(event);
            }
        }
        if (id == java.awt.event.MouseEvent.MOUSE_DRAGGED) {
            for (java.awt.event.MouseMotionListener listener : stack.getMouseMotionListeners()) {
                listener.mouseDragged(event);
            }
        }
    }
}
