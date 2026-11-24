package com.aresstack.spaceharvester.deck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verlauf des Zurück-Knopfs im Command Deck (Angelo 24.11.2026): Namen je Richtung, Sprünge über mehrere Schritte. */
class NavigationHistoryTest {

    @Test
    void listsBothDirectionsNearestFirstAndJumps() {
        NavigationHistory<String> history = new NavigationHistory<>();
        for (String entry : new String[] {"A", "B", "C", "D"}) {
            history.add(entry);
        }
        assertThat(history.backEntries()).containsExactly("C", "B", "A");
        assertThat(history.forwardEntries()).isEmpty();

        assertThat(history.go(-3)).contains("A");
        assertThat(history.backEntries()).isEmpty();
        assertThat(history.forwardEntries()).containsExactly("B", "C", "D");

        assertThat(history.go(2)).contains("C");
        assertThat(history.go(5)).as("zu weit").isEmpty();
        assertThat(history.go(0)).isEmpty();

        history.add("X");
        assertThat(history.forwardEntries()).as("neuer Ort verwirft Vorwärts").isEmpty();
        assertThat(history.backEntries()).containsExactly("C", "B", "A");
    }
}
