package com.aresstack.spaceharvester.app.ui.deck.visual;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Das Ziel aus den Knoten-Beschriftungen, die der Konverter tatsächlich erzeugt. */
class CallTargetsTest {

    @Test
    void nameFromConverterLabels() {
        assertThat(CallTargets.clean("CALLNAT SUBPROG")).isEqualTo("SUBPROG");
        assertThat(CallTargets.clean("SUBPROG\n(CALLNAT)")).isEqualTo("SUBPROG");
        assertThat(CallTargets.clean("SUBPROG\\n(CALLNAT)")).isEqualTo("SUBPROG");
        assertThat(CallTargets.clean("STEP1\nPGM=IEFBR14")).isEqualTo("IEFBR14");
        assertThat(CallTargets.clean("'SUB-PROG'")).isEqualTo("SUB-PROG");
        assertThat(CallTargets.clean("📦 IDCAMS")).isEqualTo("IDCAMS");
    }
}
