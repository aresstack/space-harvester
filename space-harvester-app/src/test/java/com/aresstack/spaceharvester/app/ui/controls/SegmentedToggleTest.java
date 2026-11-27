package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Glyph;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Angelo 27.11.2026: Umschalter einheitlich nur mit Symbolen. Eine Hälfte mit Symbol zeigt ihren Namen im Tooltip und
 * für Bildschirmleser, ist so schmal wie ihr Symbol und lässt sich gewählt wie ungewählt zeichnen.
 */
class SegmentedToggleTest {

    @Test
    void aSegmentWithAnIconShowsItsNameAsTooltipAndIsAsNarrowAsTheIcon() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SegmentedToggle named = new SegmentedToggle().segment("Wissensquellen").segment("Favoriten");
            SegmentedToggle icons = new SegmentedToggle().segment("Wissensquellen", Glyph.SELECT_ALL)
                    .segment("Favoriten", Glyph.STAR);

            assertThat(icons.button("Favoriten").getToolTipText()).isEqualTo("Favoriten");
            assertThat(icons.button("Favoriten").getAccessibleContext().getAccessibleName()).isEqualTo("Favoriten");
            assertThat(named.button("Favoriten").getToolTipText()).as("eine Hälfte mit Namen braucht keinen").isNull();
            assertThat(icons.getPreferredSize().width).isLessThan(named.getPreferredSize().width);

            icons.setSize(icons.getPreferredSize());
            icons.doLayout();
            BufferedImage image = new BufferedImage(icons.getWidth(), icons.getHeight(), BufferedImage.TYPE_INT_ARGB);
            icons.paint(image.getGraphics());
            icons.select("Favoriten");
            icons.paint(image.getGraphics());
            assertThat(icons.selected()).isEqualTo("Favoriten");
        });
    }
}
