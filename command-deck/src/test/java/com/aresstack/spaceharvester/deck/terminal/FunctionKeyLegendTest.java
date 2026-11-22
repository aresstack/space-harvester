package com.aresstack.spaceharvester.deck.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import org.junit.jupiter.api.Test;

/** PF-Belegung aus den letzten Bildzeilen, Formate wie in MainframeMate (erfundene Bildschirme). */
class FunctionKeyLegendTest {

    @Test
    void inlineFormats() {
        assertThat(FunctionKeyLegend.parse("", " F1=Help  F3=Exit  F12=Cancel"))
                .containsExactly(entry(1, "Help"), entry(3, "Exit"), entry(12, "Cancel"));
        assertThat(FunctionKeyLegend.parse(" PF3:End  PF7:Up", " 10-Actions"))
                .containsEntry(3, "End").containsEntry(7, "Up").containsEntry(10, "Actions");
        assertThat(FunctionKeyLegend.parse("", " PF1/PF13:Help  F15=Exit"))
                .containsEntry(1, "Help").containsEntry(13, "Help").containsEntry(15, "Exit");
    }

    @Test
    void naturalLegendTakesTheLabelsByColumn() {
        String keys = "Enter-PF1---PF2---PF3---PF4---PF5---PF6---PF7---PF8---PF9---PF10--PF11--PF12---";
        String labels = "      Help        Exit                    -     +                        Canc";
        assertThat(FunctionKeyLegend.parse(keys, labels))
                .containsEntry(1, "Help").containsEntry(3, "Exit").containsEntry(7, "-").containsEntry(8, "+")
                .containsEntry(12, "Canc").doesNotContainKey(2);
    }

    @Test
    void dashesWithoutLabelsAreNoLegendAndTheScreenIsReadFromTheBottom() {
        assertThat(FunctionKeyLegend.parse("PF1---PF2---PF3---", "")).isEmpty();
        assertThat(FunctionKeyLegend.ofScreen("KOPF\nF9=Oben\n\nF3=Ende F8=Vor\n\n"))
                .containsExactly(entry(3, "Ende"), entry(8, "Vor"));
        assertThat(FunctionKeyLegend.ofScreen("")).isEmpty();
    }

    @Test
    void aidKeys() {
        assertThat(AidKey.pf(1)).isEqualTo(AidKey.PF1);
        assertThat(AidKey.pf(24)).isEqualTo(AidKey.PF24);
        assertThat(AidKey.PF13.label()).isEqualTo("F13");
        assertThat(AidKey.CLEAR.label()).isEqualTo("CLR");
        assertThat(AidKey.SPECIAL).extracting(AidKey::label).containsExactly("ENT", "CLR", "PA1", "PA2", "PA3", "SYS");
    }
}
