package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class PillTabsIconsTest {

    /** Reiter als Symbole: der Name wandert in den Tooltip, Auswahl und Reihenfolge bleiben, eine Marke kommt dazu. */
    @Test
    void tabsBecomeIconsInPlaceAndKeepNameSelectionAndMark() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PillTabs tabs = new PillTabs();
            tabs.tab("Aussagen", new JPanel()).tab("Mögliche Widersprüche", new JPanel()).tab("Ohne", new JPanel());
            tabs.select("Mögliche Widersprüche");
            tabs.icons(Map.of("Aussagen", Glyph.GRAPH, "Mögliche Widersprüche", Glyph.FILTER));

            assertThat(tabs.names()).containsExactly("Aussagen", "Mögliche Widersprüche", "Ohne");
            assertThat(tabs.selected()).isEqualTo("Mögliche Widersprüche");
            assertThat(tabs.button("Aussagen").getText()).isNull();
            assertThat(tabs.button("Aussagen").getIcon()).isSameAs(Glyph.GRAPH);
            assertThat(tabs.button("Aussagen").getToolTipText()).isEqualTo("Aussagen");
            assertThat(tabs.button("Aussagen").getAccessibleContext().getAccessibleName()).isEqualTo("Aussagen");
            assertThat(tabs.button("Aussagen").getPreferredSize().width)
                    .isEqualTo(tabs.button("Aussagen").getPreferredSize().height);
            assertThat(tabs.button("Ohne").getText()).as("ohne Symbol bleibt der Text").isEqualTo("Ohne");

            tabs.mark("Mögliche Widersprüche", "3");
            assertThat(tabs.button("Mögliche Widersprüche").getText()).isEqualTo("3");
            assertThat(tabs.button("Mögliche Widersprüche").getToolTipText()).isEqualTo("Mögliche Widersprüche · 3");
            tabs.mark("Mögliche Widersprüche", null);
            assertThat(tabs.button("Mögliche Widersprüche").getText()).isNull();
            assertThat(tabs.button("Mögliche Widersprüche").getToolTipText()).isEqualTo("Mögliche Widersprüche");
        });
    }
}
