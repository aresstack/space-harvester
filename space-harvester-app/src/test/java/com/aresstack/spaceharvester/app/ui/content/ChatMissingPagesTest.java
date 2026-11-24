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
}
