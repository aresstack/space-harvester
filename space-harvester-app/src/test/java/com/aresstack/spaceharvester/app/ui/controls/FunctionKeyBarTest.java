package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Gleich breite Tasten in drei Blöcken: links bündig, mittig, rechts bündig (Angelo, 23.11.2026). */
class FunctionKeyBarTest {

    @Test
    void allKeysAreEquallyWideAndTheBlocksReachBothEdges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FunctionKeyBar bar = new FunctionKeyBar(number -> { });
            bar.show(Map.of(2, "Transfer", 9, "Transfer", 13, "Help"));
            bar.setSize(1200, bar.getPreferredSize().height);
            bar.doLayout();
            for (java.awt.Component row : bar.getComponents()) {
                row.setSize(1200 - 16, row.getPreferredSize().height);
                ((java.awt.Container) row).doLayout();
            }
            int width = bar.key(1).getWidth();
            assertThat(width).isPositive();
            for (int number = 1; number <= 24; number++) {
                assertThat(bar.key(number).getWidth()).as("F" + number).isEqualTo(width);
            }
            assertThat(bar.key(1).getX()).as("linker Block bündig links").isZero();
            assertThat(bar.key(12).getX() + width).as("rechter Block bündig rechts").isEqualTo(1200 - 16);
            assertThat(bar.key(13).getX()).as("F13 genau unter F1").isEqualTo(bar.key(1).getX());
            assertThat(bar.key(22).getX()).as("F22 genau unter F10").isEqualTo(bar.key(10).getX());
            int centerLeft = bar.key(5).getX();
            int centerRight = bar.key(8).getX() + width;
            assertThat(Math.abs(centerLeft - ((1200 - 16) - centerRight))).as("mittlerer Block mittig").isLessThanOrEqualTo(1);
        });
    }
}
