package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Glyph;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

/** Aufklappbare Überschrift: Winkel gezeichnet (kein Unicode), Klick klappt um und meldet, Anzahl rechts. */
class SectionHeaderTest {

    @Test
    void togglesAndReportsAndShowsTheCount() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] toggles = {0};
            SectionHeader header = new SectionHeader("Confluence", true, () -> toggles[0]++);
            header.count(3);
            assertThat(header.isOpen()).isTrue();
            assertThat(((JLabel) header.getComponent(0)).getIcon()).isSameAs(Glyph.COLLAPSE);
            assertThat(((JLabel) header.getComponent(2)).getText()).isEqualTo("3");
            header.toggle();
            assertThat(header.isOpen()).isFalse();
            assertThat(((JLabel) header.getComponent(0)).getIcon()).isSameAs(Glyph.EXPAND);
            assertThat(toggles[0]).isEqualTo(1);
            header.open(true);
            assertThat(header.isOpen()).isTrue();
            assertThat(toggles[0]).as("open() meldet nicht").isEqualTo(1);
            header.count(0);
            assertThat(((JLabel) header.getComponent(2)).getText()).isEmpty();
            assertThat(header.getPreferredSize().height).isGreaterThanOrEqualTo(26);
        });
    }
}
