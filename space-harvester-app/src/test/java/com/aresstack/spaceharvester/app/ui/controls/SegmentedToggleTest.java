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

    @Test
    void aSecondClickGoesToItsReceiverAndTheToggleCanShowNoChoice() throws Exception {
        // Angelo 27.11.2026: Der Umschalter der rechten Leiste klappt sie mit dem zweiten Klick zu; zugeklappt ist keine
        // Hälfte gewählt, und ein Klick wählt wieder.
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<String> events = new java.util.ArrayList<>();
            SegmentedToggle toggle = new SegmentedToggle().segment("Chat", Glyph.STAR).segment("Aufträge", Glyph.STAR);
            toggle.onSelect(name -> events.add("wahl " + name));
            toggle.onAgain(name -> events.add("nochmal " + name));

            toggle.button("Aufträge").doClick();
            toggle.button("Aufträge").doClick();
            toggle.clear();
            assertThat(toggle.selected()).isNull();
            assertThat(toggle.button("Chat").isSelected()).isFalse();
            assertThat(toggle.button("Aufträge").isSelected()).isFalse();
            toggle.button("Aufträge").doClick();
            toggle.show("Chat");

            assertThat(events).containsExactly("wahl Aufträge", "nochmal Aufträge", "wahl Aufträge");
            assertThat(toggle.selected()).isEqualTo("Chat");
        });
    }

    @Test
    void theVerticalToggleStacksItsSymbolsAndIsGlassUntilTheMouseIsOverIt() throws Exception {
        // Angelo 27.11.2026: Am Fensterrand steht der Umschalter einer zugeklappten Leiste senkrecht und leicht
        // durchscheinend; unter der Maus ist er voll deckend.
        SwingUtilities.invokeAndWait(() -> {
            SegmentedToggle toggle = new SegmentedToggle().segment("Chat", Glyph.STAR).segment("Aufträge", Glyph.STAR);
            java.awt.Dimension flat = toggle.getPreferredSize();
            assertThat(toggle.glassy()).isFalse();

            toggle.vertical(true);
            java.awt.Dimension tall = toggle.getPreferredSize();
            assertThat(tall.height).isGreaterThan(tall.width);
            assertThat(tall.width).isLessThan(flat.width / 2);
            assertThat(toggle.glassy()).isTrue();
            javax.swing.JToggleButton chat = toggle.button("Chat");
            chat.dispatchEvent(new java.awt.event.MouseEvent(chat, java.awt.event.MouseEvent.MOUSE_ENTERED, 0, 0, 2, 2,
                    0, false));
            assertThat(toggle.glassy()).isFalse();
            chat.dispatchEvent(new java.awt.event.MouseEvent(chat, java.awt.event.MouseEvent.MOUSE_EXITED, 0, 0, -1, -1,
                    0, false));
            assertThat(toggle.glassy()).isTrue();

            toggle.vertical(false);
            assertThat(toggle.glassy()).isFalse();
            assertThat(toggle.getPreferredSize()).isEqualTo(flat);
        });
    }
}
