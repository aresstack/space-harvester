package com.aresstack.spaceharvester.application.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Vertragstest für {@link JobEventStore}; persistente Umsetzungen überschreiben {@link #reopen}, damit der Neustart
 * geprüft wird.
 */
public abstract class JobEventStoreContract {

    private static final Instant T0 = Instant.parse("2026-10-17T21:00:00Z");

    protected abstract JobEventStore newStore();

    /** Dieselbe Ablage nach einem Neustart oder {@code null} für flüchtige Umsetzungen. */
    protected JobEventStore reopen(JobEventStore store) {
        return null;
    }

    protected static JobEvent event(String jobId, long sequence, int secondsAfter) {
        return new JobEvent(jobId, sequence, T0.plusSeconds(secondsAfter), JobType.EXPORT, "wiki", "DEV",
                JobEvent.Severity.ERROR, JobEvent.Category.NETWORK, "Exports", "Einlesen", "SOURCE_UNAVAILABLE",
                "nicht erreichbar", 502, "PROXY proxy:8080 (pac:resolved)", "SSLHandshakeException: x",
                "java.lang.IllegalStateException\n\tat x");
    }

    @Test
    void eventsOfAJobComeInSequenceWithAllFields() {
        JobEventStore store = newStore();
        store.append(event("a", 2, 2));
        store.append(event("b", 1, 1));
        store.append(event("a", 1, 3));

        assertThat(store.events("a")).extracting(JobEvent::sequence).containsExactly(1L, 2L);
        assertThat(store.events("a").get(0)).isEqualTo(event("a", 1, 3));
        assertThat(store.events("nope")).isEmpty();
    }

    @Test
    void recentEventsAreNewestFirst() {
        JobEventStore store = newStore();
        store.append(event("a", 1, 1));
        store.append(event("b", 1, 5));
        store.append(event("a", 2, 3));

        assertThat(store.recent(2)).extracting(JobEvent::jobId, JobEvent::sequence)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("b", 1L),
                        org.assertj.core.groups.Tuple.tuple("a", 2L));
        assertThat(store.recent(0)).isEmpty();
    }

    @Test
    void deleteRemovesOnlyThatJob() {
        JobEventStore store = newStore();
        store.append(event("a", 1, 1));
        store.append(event("b", 1, 1));

        store.delete("a");

        assertThat(store.events("a")).isEmpty();
        assertThat(store.events("b")).hasSize(1);
    }

    @Test
    void singleEventsCanBeDeletedAndTheOthersKeepTheirNumbers() {
        JobEventStore store = newStore();
        store.append(event("a", 1, 1));
        store.append(event("a", 2, 2));
        store.append(event("a", 3, 3));
        store.append(event("b", 2, 2));

        store.delete("a", List.of(2L, 9L));
        store.delete("a", List.of());

        assertThat(store.events("a")).extracting(JobEvent::sequence).containsExactly(1L, 3L);
        assertThat(store.events("b")).extracting(JobEvent::sequence).containsExactly(2L);
        JobEventStore reopened = reopen(store);
        if (reopened != null) {
            assertThat(reopened.events("a")).extracting(JobEvent::sequence).containsExactly(1L, 3L);
        }
    }

    @Test
    void aJobKeepsAtMostTheLimit() {
        JobEventStore store = newStore();
        for (int i = 1; i <= JobEventStore.MAX_EVENTS_PER_JOB + 3; i++) {
            store.append(event("a", i, i));
        }

        assertThat(store.events("a")).hasSize(JobEventStore.MAX_EVENTS_PER_JOB);
    }

    @Test
    void eventsSurviveARestart() {
        JobEventStore store = newStore();
        store.append(event("a", 1, 1));

        JobEventStore reopened = reopen(store);
        if (reopened != null) {
            assertThat(reopened.events("a")).containsExactly(event("a", 1, 1));
        }
    }
}
