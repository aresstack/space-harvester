package com.aresstack.spaceharvester.application.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Höchstens ein Update je Tag (Angelo, 21.11.2026): maßgeblich ist der Tag des Abgleichs in der Zeitzone. */
class DailyUpdateMarksTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static UpdateMark mark(String at, List<String> spaces, int failed, List<String> failedSpaces) {
        return new UpdateMark("wiki", Instant.parse(at), "client:a", spaces, failed, false, failedSpaces);
    }

    @Test
    void roundsOfOneDayBecomeOneMarkAtTheLastRound() {
        List<UpdateMark> days = DailyUpdateMarks.perDay(List.of(
                mark("2026-11-17T16:50:00Z", List.of("A"), 0, List.of()),
                mark("2026-11-17T16:55:00Z", List.of("B"), 0, List.of()),
                mark("2026-11-17T17:29:00Z", List.of("A"), 0, List.of()),
                mark("2026-11-21T02:24:00Z", List.of("A", "B"), 0, List.of())), BERLIN);

        assertThat(days).extracting(UpdateMark::at).containsExactly(Instant.parse("2026-11-17T17:29:00Z"),
                Instant.parse("2026-11-21T02:24:00Z"));
        assertThat(days.getFirst().spaces()).containsExactly("A", "B");
    }

    @Test
    void theDayCountsInTheZoneNotInUtc() {
        // 23:30 UTC am 20.11. ist in Berlin schon der 21.11.
        List<UpdateMark> rounds = List.of(
                mark("2026-11-20T23:30:00Z", List.of("A"), 0, List.of()),
                mark("2026-11-21T08:00:00Z", List.of("A"), 0, List.of()));

        assertThat(DailyUpdateMarks.perDay(rounds, BERLIN)).hasSize(1);
        assertThat(DailyUpdateMarks.perDay(rounds, ZoneId.of("UTC"))).hasSize(2);
    }

    @Test
    void theInitialLoadStaysOnItsOwn() {
        UpdateMark initial = new UpdateMark("wiki", Instant.parse("2026-11-17T10:00:00Z"), "client:a", List.of("A"), 0,
                true);
        List<UpdateMark> days = DailyUpdateMarks.perDay(List.of(initial,
                mark("2026-11-17T11:00:00Z", List.of("A"), 0, List.of()),
                mark("2026-11-17T12:00:00Z", List.of("A"), 0, List.of())), BERLIN);

        assertThat(days).hasSize(2);
        assertThat(days.getFirst()).isEqualTo(initial);
    }

    @Test
    void aRetryOfTheFailedSpacesClearsThem() {
        UpdateMark merged = DailyUpdateMarks.merge(
                mark("2026-11-21T02:24:00Z", List.of("A", "B", "C"), 2, List.of("B", "C")),
                mark("2026-11-21T17:00:00Z", List.of("B"), 0, List.of()));

        assertThat(merged.failedSyncs()).isEqualTo(1);
        assertThat(merged.failedSpaces()).containsExactly("C");
        assertThat(merged.retrySpaces()).containsExactly("C");
    }

    @Test
    void aRetryThatFailsAgainStaysFailed() {
        UpdateMark merged = DailyUpdateMarks.merge(
                mark("2026-11-21T02:24:00Z", List.of("A", "B"), 1, List.of("B")),
                mark("2026-11-21T17:00:00Z", List.of("B"), 1, List.of("B")));

        assertThat(merged.failedSyncs()).isEqualTo(1);
        assertThat(merged.retrySpaces()).containsExactly("B");
    }

    @Test
    void failuresOfOlderMarksWithoutSpacesRetryAllSpacesOfTheRound() {
        UpdateMark legacy = mark("2026-11-21T02:24:00Z", List.of("A", "B"), 2, List.of());

        assertThat(legacy.retrySpaces()).containsExactly("A", "B");
        UpdateMark partly = DailyUpdateMarks.merge(legacy, mark("2026-11-21T17:00:00Z", List.of("A"), 0, List.of()));
        assertThat(partly.failedSyncs()).isEqualTo(2);
        assertThat(partly.retrySpaces()).containsExactly("B");
        UpdateMark all = DailyUpdateMarks.merge(legacy, mark("2026-11-21T17:00:00Z", List.of("A", "B"), 0, List.of()));
        assertThat(all.failedSyncs()).isZero();
        assertThat(all.retrySpaces()).isEmpty();
    }
}
