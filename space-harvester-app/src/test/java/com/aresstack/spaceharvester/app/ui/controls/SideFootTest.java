package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.FindBar;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Fuß- und Kopfleiste jeder Seitenleiste wie im Explorer des Space Harvesters (Angelo 24.11.2026). */
class SideFootTest {

    @Test
    void everyFootIsAsHighAsTheBarWithTheMagnifier() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertThat(new SideFoot(null, null, null).getPreferredSize().height).isEqualTo(Comic.FOOT_HEIGHT);
            IconButton star = new IconButton(Glyph.STAR, "Nur Favoriten");
            SideFoot foot = new SideFoot(SideFoot.buttons(new IconButton(Glyph.COLLAPSE_ALL, "Alles zuklappen")),
                    new JLabel("Hinweis"), star);
            assertThat(foot.getPreferredSize().height).isEqualTo(Comic.FOOT_HEIGHT)
                    .isEqualTo(new FindBar(() -> null).getPreferredSize().height);
            foot.setSize(300, Comic.FOOT_HEIGHT);
            foot.doLayout();
            assertThat(star.getX() + star.getWidth()).as("Stern rechts in der Ecke").isEqualTo(300);
            assertThat(star.getY() * 2 + star.getHeight()).as("senkrecht mittig").isBetween(
                    Comic.FOOT_HEIGHT - 1, Comic.FOOT_HEIGHT + 1);
        });
    }

    @Test
    void theHeadShowsTheCaptionInCapitalsAndTheFilterBelow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PlaceholderField filter = new PlaceholderField(14, "Titel filtern …");
            SideHead head = new SideHead("Datasets", filter);
            assertThat(head.heading().getText()).isEqualTo("DATASETS");
            assertThat(SwingUtilities.isDescendingFrom(filter, head)).isTrue();
        });
    }
}
