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
}
