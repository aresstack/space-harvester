package com.aresstack.spaceharvester.deck.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TerminalProfileTest {

    @Test
    void defaultsAndValidation() {
        TerminalProfile profile = TerminalProfile.of(" host.example ", 23);
        assertThat(profile.host()).isEqualTo("host.example");
        assertThat(profile.model()).isEqualTo(TerminalModel.MODEL_2);
        assertThat(profile.codePage()).isEqualTo("Cp273");
        assertThat(profile.label()).isEqualTo("host.example:23");
        assertThatThrownBy(() -> TerminalProfile.of(" ", 23)).hasMessageContaining("Host");
        assertThatThrownBy(() -> TerminalProfile.of("h", 0)).hasMessageContaining("Port");
        assertThatThrownBy(() -> new TerminalProfile("h", 23, TerminalModel.MODEL_2, false, 0, "Cp9999x"))
                .hasMessageContaining("Codepage");
    }

    @Test
    void modelsKnowTheirSizeAndColourVariants() {
        assertThat(TerminalModel.ofType("ibm-3279-4")).contains(TerminalModel.MODEL_4);
        assertThat(TerminalModel.MODEL_5.columns()).isEqualTo(132);
        assertThat(TerminalModel.ofType("VT100")).isEmpty();
    }
}
