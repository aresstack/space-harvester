package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Die editierbare Auswahl für parallele Downloads (Angelo 11.11.2026): Vorschläge samt 0, freie Eingabe nur ganzer
 * Zahlen in den Grenzen; Ungültiges springt mit Meldung auf den alten Wert zurück.
 */
class ParallelChoiceTest {

    @Test
    void onlyWholeNumbersWithinTheLimitsAreAccepted() {
        assertThat(ParallelChoice.parse("0", 0, 64)).isZero();
        assertThat(ParallelChoice.parse(" 12 ", 0, 64)).isEqualTo(12);
        assertThat(ParallelChoice.parse("64", 0, 64)).isEqualTo(64);
        assertThat(ParallelChoice.parse("65", 0, 64)).isNull();
        assertThat(ParallelChoice.parse("0", 1, 16)).isNull();
        assertThat(ParallelChoice.parse("-1", 0, 64)).isNull();
        assertThat(ParallelChoice.parse("2.5", 0, 64)).isNull();
        assertThat(ParallelChoice.parse("", 0, 64)).isNull();
        assertThat(ParallelChoice.parse("99999999999", 0, 64)).isNull();
    }

    @Test
    void aValidEntryIsAppliedAnInvalidOneRevertsWithAMessage() {
        List<Integer> applied = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        ParallelChoice choice = new ParallelChoice("tip", 0, 64, List.of(0, 1, 2, 4), 4, applied::add,
                problems::add);
        assertThat(choice.box().getItemAt(0)).as("0 steht in der Liste").isEqualTo("0");
        choice.box().setSelectedItem("0");
        assertThat(applied).containsExactly(0);
        assertThat(choice.value()).isZero();
        choice.box().setSelectedItem("99");
        assertThat(applied).containsExactly(0);
        assertThat(problems).containsExactly("Bitte eine ganze Zahl von 0 bis 64 eingeben.");
        assertThat(choice.box().getSelectedItem()).isEqualTo("0");
        choice.refresh(8);
        assertThat(choice.value()).isEqualTo(8);
        assertThat(applied).as("Auffrischen übernimmt nichts").containsExactly(0);
    }
}
