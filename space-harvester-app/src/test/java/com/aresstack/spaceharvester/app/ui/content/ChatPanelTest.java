package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Statuszeile des Wissens im Chat sagt ehrlich, was links gewählt ist, was fehlt und was die KI sehen darf. */
class ChatPanelTest {

    @Test
    void theKnowledgeLineNamesTheSelectionItsPagesAndWhatIsMissing() {
        assertThat(ChatPanel.knowledgeText(null, null, true, true)).isEqualTo(ChatPanel.NO_SELECTION);
        Selection page = new Selection("wiki", "DEV", "42", "Handbuch");
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(120, 120, 0, 0), true, true))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 120 Seiten");
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(120, 80, 0, 0), true, true))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 120 Seiten · 40 davon nicht geladen");
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(1, 1, 0, 0), true, true))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 1 Seite");
        Selection space = new Selection("wiki", "DEV", null, "Entwicklung");
        assertThat(ChatPanel.knowledgeText(space, HoldingCounts.ZERO, true, true))
                .isEqualTo("Wissen: Space Entwicklung · " + ChatPanel.NOT_LOADED);
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(3, 3, 0, 0), false, true))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 3 Seiten · " + ExportPanel.INCOMPLETE);
        assertThat(ChatPanel.knowledgeText(space, null, true, true))
                .isEqualTo("Wissen: Space Entwicklung · " + ChatPanel.NOT_LOADED);
        Selection source = new Selection("wiki", null, null, "Beta");
        // Der Chat ist die Ausnahme der KI-Freigabe (Angelo, 13.11.2026): genannt, nicht gesperrt.
        assertThat(ChatPanel.knowledgeText(source, new HoldingCounts(2010, 176, 8, 0), true, false))
                .isEqualTo("Wissen: Beta · alle Spaces · 2010 Seiten · 1826 davon nicht geladen · "
                        + ChatPanel.NOT_ALLOWED);
    }
}
