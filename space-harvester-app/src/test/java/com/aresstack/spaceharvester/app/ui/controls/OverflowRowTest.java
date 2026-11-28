package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Die Reihe, die nimmt, was nicht passt, hinter ein Menü am Ende. */
class OverflowRowTest {

    /**
     * Ein gewählter Umschalter bleibt in der Reihe, auch wenn er breiter ist als die anderen (Reiter mit Text): Dann
     * weichen so viele andere, dass er und das Menü ganz in der Reihe stehen.
     */
    @Test
    void aWideSelectedPartStaysVisibleAndTheMenuStaysInsideTheRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel bar = new JPanel();
            for (int i = 0; i < 5; i++) {
                bar.add(part(30, false));
            }
            JToggleButton wide = part(100, true);
            bar.add(wide);
            OverflowRow row = OverflowRow.around(bar);
            row.setSize(170, 30);
            row.doLayout();

            assertThat(row.hiddenParts()).isNotEmpty().doesNotContain(wide);
            assertThat(wide.getWidth()).isEqualTo(100);
            Component menu = null;
            for (Component child : row.getComponents()) {
                if (child != bar) {
                    menu = child;
                }
            }
            assertThat(menu.isVisible()).isTrue();
            assertThat(menu.getX() + menu.getWidth()).as("Menü in der Reihe").isLessThanOrEqualTo(row.getWidth());
            assertThat(wide.getX() + wide.getWidth()).isLessThanOrEqualTo(menu.getX());
            for (Component child : bar.getComponents()) {
                if (!row.hiddenParts().contains((JComponent) child)) {
                    assertThat(child.getX() + child.getWidth()).isLessThanOrEqualTo(menu.getX());
                }
            }
        });
    }

    private static JToggleButton part(int width, boolean selected) {
        JToggleButton button = new JToggleButton();
        button.setPreferredSize(new Dimension(width, 24));
        button.setSelected(selected);
        return button;
    }
}
