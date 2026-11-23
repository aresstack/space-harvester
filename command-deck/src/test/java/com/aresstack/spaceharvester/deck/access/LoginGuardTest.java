package com.aresstack.spaceharvester.deck.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Schutz vor der Kontosperre: ein Fehlschlag sperrt den Eintrag für alle Bereiche, nur ein Erfolg löst ihn. */
class LoginGuardTest {

    @Test
    void aFailedLoginBlocksEveryFurtherAutomaticAttemptWithThatEntry() {
        LoginGuard guard = new LoginGuard(LoginGuard.Store.memory());
        assertThat(guard.mayTry("Erfundener Host")).isTrue();

        guard.failed("Erfundener Host", "Passwort abgelaufen", Instant.ofEpochMilli(1000));

        // Jeder Bereich fragt denselben Wächter mit demselben Eintrag: keiner versucht es noch einmal.
        assertThat(guard.mayTry("Erfundener Host")).isFalse();
        assertThat(guard.mayTry(" erfundener host ")).as("Schreibweise des Titels zählt nicht").isFalse();
        assertThat(guard.failure("Erfundener Host")).get().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo("Passwort abgelaufen");
            assertThat(failure.at()).isEqualTo(Instant.ofEpochMilli(1000));
        });
        assertThat(guard.mayTry("Anderer Eintrag")).as("andere Einträge bleiben frei").isTrue();
    }

    @Test
    void onlyASuccessfulLoginLiftsTheBlock() {
        LoginGuard guard = new LoginGuard(LoginGuard.Store.memory());
        guard.failed("Erfundener Host", "abgelehnt");
        guard.failed("Erfundener Host", "wieder abgelehnt");
        assertThat(guard.mayTry("Erfundener Host")).isFalse();
        assertThat(guard.failure("Erfundener Host").orElseThrow().reason()).isEqualTo("wieder abgelehnt");

        guard.succeeded("Erfundener Host");
        assertThat(guard.mayTry("Erfundener Host")).isTrue();
        assertThat(guard.failure("Erfundener Host")).isEmpty();
    }

    @Test
    void theBlockSurvivesARestart() {
        LoginGuard.Store store = LoginGuard.Store.memory();
        new LoginGuard(store).failed("Erfundener Host", "Kennung | gesperrt");

        LoginGuard afterRestart = new LoginGuard(store);
        assertThat(afterRestart.mayTry("Erfundener Host")).isFalse();
        assertThat(afterRestart.failure("Erfundener Host").orElseThrow().reason()).isEqualTo("Kennung | gesperrt");
    }

    @Test
    void observersHearEveryChangeAndNothingElse() {
        LoginGuard guard = new LoginGuard(LoginGuard.Store.memory());
        List<String> heard = new ArrayList<>();
        guard.onChange(heard::add);

        guard.succeeded("Erfundener Host");
        assertThat(heard).as("nichts gelöst, nichts gemeldet").isEmpty();
        guard.failed("Erfundener Host", null);
        guard.succeeded("Erfundener Host");
        assertThat(heard).containsExactly("Erfundener Host", "Erfundener Host");
    }

    @Test
    void withoutAnEntryThereIsNothingToBlock() {
        LoginGuard guard = new LoginGuard(LoginGuard.Store.memory());
        guard.failed("", "abgelehnt");
        guard.failed(null, "abgelehnt");
        assertThat(guard.mayTry("")).isTrue();
        assertThat(guard.mayTry(null)).isTrue();
        assertThat(guard.failure(" ")).isEmpty();
    }
}
