package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Kreispfeil und Fadenkreuz stehen direkt hinter „… davon nicht geladen“ (Angelo 24.11.2026). */
class ChatMissingPagesTest {

    @Test
    void theIconsStandBehindTheCountOfMissingPagesOrBehindNotLoaded() {
        assertThat(ChatPanel.notLoadedAnchor("Wissen: CL2 samt Unterseiten · 2515 Seiten · 1 davon nicht geladen"))
                .isEqualTo("1 davon nicht geladen");
        assertThat(ChatPanel.notLoadedAnchor("Wissen: Space Handbuch · " + ChatPanel.NOT_LOADED))
                .isEqualTo(ChatPanel.NOT_LOADED);
        assertThat(ChatPanel.notLoadedAnchor("Wissen: Space Handbuch · 3 Seiten")).isNull();
        assertThat(ChatPanel.notLoadedAnchor(null)).isNull();
    }

    @Test
    void theCrosshairTakesExactlyThePagesTheKnowledgeLineCountsAsNotLoaded() {
        for (com.aresstack.spaceharvester.application.holdings.HoldingState state
                : com.aresstack.spaceharvester.application.holdings.HoldingState.values()) {
            com.aresstack.spaceharvester.application.holdings.HoldingCounts one =
                    com.aresstack.spaceharvester.application.holdings.HoldingCounts.of(state);
            assertThat(ChatPanel.notLoaded(state)).as(state.name()).isEqualTo(one.pages() - one.stored() > 0);
        }
        assertThat(ChatPanel.notLoaded(com.aresstack.spaceharvester.application.holdings.HoldingState.LOCKED))
                .as("eine gesperrte Seite zählt als nicht geladen, also findet das Fadenkreuz sie").isTrue();
    }
}
