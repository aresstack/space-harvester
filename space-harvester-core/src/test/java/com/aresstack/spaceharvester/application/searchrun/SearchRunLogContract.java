package com.aresstack.spaceharvester.application.searchrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Vertragstest für {@link SearchRunLog}; persistente Umsetzungen überschreiben {@link #reopen}, damit der Neustart
 * geprüft wird.
 */
public abstract class SearchRunLogContract {

    protected static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");

    /** @param maxAge Frist oder {@code null}; {@code clock} bestimmt „jetzt“ */
    protected abstract SearchRunLog newLog(Duration maxAge, Clock clock);

    /** Dasselbe Protokoll nach einem Neustart oder {@code null} für flüchtige Umsetzungen. */
    protected SearchRunLog reopen(SearchRunLog log) {
        return null;
    }

    protected static SearchRun run(String id, String partOf, String owner, int secondsAfter) {
        return new SearchRun(new SearchRunId(id), partOf == null ? null : new SearchRunId(partOf),
                partOf == null ? SearchRun.Kind.PROGRESSIVE : SearchRun.Kind.KEYWORD, owner, "Nachtlauf Betrieb",
                T0.plusSeconds(secondsAfter), Duration.ofMillis(1234), List.of(
                        new SearchRun.SourceOutcome("wiki", SearchRun.SourceOutcome.Status.SEARCHED, null),
                        new SearchRun.SourceOutcome("files", SearchRun.SourceOutcome.Status.UNAVAILABLE,
                                "UNAVAILABLE")),
                List.of(new SearchRun.StageCount(SearchRun.Stage.DELIVERED, 3),
                        new SearchRun.StageCount(SearchRun.Stage.QUERIES, 2)), false,
                partOf == null ? "BUDGET_EXHAUSTED" : null);
    }

    private SearchRunLog log() {
        return newLog(null, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void aRunComesBackWithAllFieldsAndStagesInOrder() {
        SearchRunLog log = log();
        log.record(run("r1", null, "TECHNICAL_CLIENT:app", 0));

        SearchRun found = log.find(new SearchRunId("r1")).orElseThrow();
        assertThat(found).isEqualTo(run("r1", null, "TECHNICAL_CLIENT:app", 0));
        assertThat(found.stages()).extracting(SearchRun.StageCount::stage)
                .containsExactly(SearchRun.Stage.QUERIES, SearchRun.Stage.DELIVERED);
        assertThat(log.find(new SearchRunId("nope"))).isEmpty();
    }

    @Test
    void aRunIsWrittenOnce() {
        SearchRunLog log = log();
        log.record(run("r1", null, "a", 0));

        assertThatThrownBy(() -> log.record(run("r1", null, "b", 1))).isInstanceOf(IllegalStateException.class);
        assertThat(log.find(new SearchRunId("r1")).orElseThrow().owner()).isEqualTo("a");
    }

    @Test
    void partsComeInTheOrderTheyStarted() {
        SearchRunLog log = log();
        log.record(run("p", null, "a", 0));
        log.record(run("k2", "p", "a", 2));
        log.record(run("k1", "p", "a", 1));
        log.record(run("other", null, "a", 3));

        assertThat(log.parts(new SearchRunId("p"))).extracting(run -> run.id().value()).containsExactly("k1", "k2");
        assertThat(log.parts(new SearchRunId("other"))).isEmpty();
    }

    @Test
    void recentRunsAreNewestFirstAndFilteredByOwner() {
        SearchRunLog log = log();
        log.record(run("r1", null, "a", 1));
        log.record(run("r2", null, "b", 2));
        log.record(run("r3", null, "a", 3));

        assertThat(log.recent("a", 10)).extracting(run -> run.id().value()).containsExactly("r3", "r1");
        assertThat(log.recent(null, 2)).extracting(run -> run.id().value()).containsExactly("r3", "r2");
        assertThatThrownBy(() -> log.recent(null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteBeforeRemovesOlderRuns() {
        SearchRunLog log = log();
        log.record(run("r1", null, "a", 1));
        log.record(run("r2", null, "a", 5));

        assertThat(log.deleteBefore(T0.plusSeconds(5))).isEqualTo(1);
        assertThat(log.recent(null, 10)).extracting(run -> run.id().value()).containsExactly("r2");
    }

    @Test
    void runsOlderThanTheMaxAgeDisappear() {
        SearchRunLog log = newLog(Duration.ofDays(30), Clock.fixed(T0.plus(Duration.ofDays(40)), ZoneOffset.UTC));
        log.record(run("old", null, "a", 0));
        log.record(new SearchRun(new SearchRunId("new"), null, SearchRun.Kind.KEYWORD, "a", "x",
                T0.plus(Duration.ofDays(39)), Duration.ZERO, List.of(), List.of(), true, null));
        // Spätestens beim nächsten Öffnen ist der alte Lauf fort; flüchtige Umsetzungen räumen sofort.
        SearchRunLog reopened = reopen(log);
        SearchRunLog current = reopened == null ? log : reopened;

        assertThat(current.find(new SearchRunId("old"))).isEmpty();
        assertThat(current.find(new SearchRunId("new"))).isPresent();
    }

    @Test
    void runsSurviveARestart() {
        SearchRunLog log = log();
        log.record(run("r1", null, "a", 0));
        log.record(run("k1", "r1", "a", 1));

        SearchRunLog reopened = reopen(log);
        if (reopened == null) {
            return;
        }
        assertThat(reopened.find(new SearchRunId("r1"))).contains(run("r1", null, "a", 0));
        assertThat(reopened.parts(new SearchRunId("r1"))).containsExactly(run("k1", "r1", "a", 1));
    }
}
