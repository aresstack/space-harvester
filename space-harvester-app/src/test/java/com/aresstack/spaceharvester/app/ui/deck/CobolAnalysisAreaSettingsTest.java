package com.aresstack.spaceharvester.app.ui.deck;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CobolAnalysisAreaSettingsTest {
    @Test
    void keepsHomesSeparateAndReusesTheCurrentProfileLibraries() {
        Map<String, String> state = new HashMap<>();
        var settings = new CobolAnalysisAreaSettings(state::get, state::put);
        settings.save("HostA", 21, "USER1", List.of("HLQ.CPY"), "'HLQ.COBOL'");
        var reopened = new CobolAnalysisAreaSettings(state::get, state::put);
        var area = reopened.load("hosta", 21, "USER1", List.of("HLQ.NEWCPY")).orElseThrow();
        assertThat(area.home()).isEqualTo("HLQ.COBOL");
        assertThat(area.syslib()).containsExactly("HLQ.NEWCPY");
        assertThat(reopened.load("hosta", 21, "USER2", List.of())).isEmpty();
        assertThat(reopened.load("hostb", 21, "USER1", List.of())).isEmpty();
        assertThat(reopened.load("hosta", 2121, "USER1", List.of())).isEmpty();
        reopened.save("hosta", 21, "USER2", List.of(), "OTHER.COBOL");
        assertThat(reopened.load("hosta", 21, "USER2", List.of()).orElseThrow().home()).isEqualTo("OTHER.COBOL");
        reopened.save("hosta", 21, "USER1", List.of(), "");
        assertThat(reopened.load("hosta", 21, "USER1", List.of())).isEmpty();
        assertThat(reopened.load("hosta", 21, "USER2", List.of())).isPresent();
    }

    @Test
    void doesNotReplaceValidSettingsWithAnInvalidMember() {
        Map<String, String> state = new HashMap<>();
        var settings = new CobolAnalysisAreaSettings(state::get, state::put);
        settings.save("host", 21, "USER", List.of(), "HLQ.COBOL");
        assertThatThrownBy(() -> settings.save("host", 21, "USER", List.of(), "HLQ.COBOL(PGM)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.load("host", 21, "USER", List.of()).orElseThrow().home()).isEqualTo("HLQ.COBOL");
    }
}
