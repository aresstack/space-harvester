package com.aresstack.spaceharvester.application.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Messung, Kennzahlen, Ergebnisdatei, Vergleich und Bericht (Q1) mit einer Fake-Suche; nur erfundene Fragen. */
class QualityRunTest {

    private static final PageRef P1 = new PageRef("q", "1");
    private static final PageRef P2 = new PageRef("q", "2");
    private static final PageRef P3 = new PageRef("q", "3");
    private static final String SECRET_QUESTION = "Wer genehmigt den geheimen Mittelabruf?";

    private static QualityCase question(String id, List<PageRef> expected, CaseKind... kinds) {
        return new QualityCase(1, id, id.equals("A") ? SECRET_QUESTION : "Frage " + id,
                List.of(ScopeSpec.source("q")), expected, Set.of(kinds));
    }

    private static QualitySearch.RankedPage hit(String pageId, double score) {
        return new QualitySearch.RankedPage(new PageRef("q", pageId), score, "Titel " + pageId);
    }

    /** Die Fake-Suche antwortet je Frage mit einer festen Liste; die Uhr springt je Aufruf um 100 ms. */
    private static final class Fake implements QualitySearch {
        final Map<String, List<RankedPage>> answers;
        final AtomicLong clock;
        final AtomicInteger calls = new AtomicInteger();

        Fake(Map<String, List<RankedPage>> answers, AtomicLong clock) {
            this.answers = answers;
            this.clock = clock;
        }

        @Override
        public Answer search(QualityCase question, java.util.function.Consumer<List<RankedPage>> interim) {
            calls.incrementAndGet();
            if (question.id().equals("X")) {
                throw new IllegalStateException("Ausfall");
            }
            List<RankedPage> hits = answers.get(question.id());
            clock.addAndGet(100_000_000L);
            interim.accept(hits.subList(0, Math.min(1, hits.size())));
            clock.addAndGet(100_000_000L);
            return new Answer(hits, "QUALITY_REACHED", 2, 0);
        }
    }

    private final AtomicLong clock = new AtomicLong();
    private final Fake search = new Fake(Map.of(
            "A", List.of(hit("9", 0.9), hit("9", 0.8), hit("1", 0.7)),
            "B", List.of(hit("2", 0.9), hit("5", 0.4), hit("6", 0.4), hit("7", 0.4), hit("8", 0.4), hit("10", 0.4),
                    hit("11", 0.4), hit("12", 0.4), hit("13", 0.4), hit("3", 0.4)),
            "C", List.of(hit("7", 0.2), hit("8", 0.6)),
            "D", List.of(hit("7", 0.3))), clock);
    private final QualityRun run = new QualityRun(search, page -> page.equals(P1), new QualityRun.Settings(8, 0.5),
            clock::get);
    private final List<QualityCase> cases = List.of(
            question("A", List.of(P1), CaseKind.EINFACH),
            question("B", List.of(P2, P3), CaseKind.VERTEILT),
            question("C", List.of(), CaseKind.KEINE),
            question("D", List.of(), CaseKind.KEINE),
            question("X", List.of(P1), CaseKind.AKRONYM));

    @Test
    void ranksCountEachPageOnceAndTimesComeFromTheListener() {
        QualityRun.Result result = run.run(cases, Map.of(), () -> false, QualityRun.Listener.NONE);

        assertThat(result.cancelled()).isFalse();
        CaseResult a = result.results().get(0);
        assertThat(a.ranks()).containsExactly(2);
        assertThat(a.stored()).containsExactly(true);
        assertThat(a.durationMillis()).isEqualTo(200);
        assertThat(a.firstExpectedMillis()).isEqualTo(200);
        assertThat(a.found()).containsExactly(new PageRef("q", "9"), P1);
        CaseResult b = result.results().get(1);
        assertThat(b.ranks()).containsExactly(1, 10);
        assertThat(b.firstExpectedMillis()).isEqualTo(100);
        assertThat(b.recallTop10()).isEqualTo(1.0);
        assertThat(b.inTop(8)).isTrue();
        assertThat(result.results().get(2).noAnswerCorrect()).isFalse();
        assertThat(result.results().get(3).noAnswerCorrect()).isTrue();
        assertThat(result.results().get(4).failure()).isEqualTo("IllegalStateException");
    }

    @Test
    void theSummarySeparatesNoAnswerAndFailures() {
        List<CaseResult> results = run.run(cases, Map.of(), () -> false, QualityRun.Listener.NONE).results();

        QualitySummary summary = QualitySummary.of(results, 8);

        assertThat(summary.questions()).isEqualTo(3);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.top5()).isEqualTo(2);
        assertThat(summary.top10()).isEqualTo(2);
        assertThat(summary.mrr()).isCloseTo((0.5 + 1.0) / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(summary.spread()).isEqualTo(1);
        assertThat(summary.noAnswer()).isEqualTo(2);
        assertThat(summary.noAnswerCorrect()).isEqualTo(1);
    }

    @Test
    void cancellingKeepsTheMeasuredAndResumingMeasuresOnlyTheRest() {
        AtomicInteger asked = new AtomicInteger();
        QualityRun.Result first = run.run(cases, Map.of(), () -> asked.incrementAndGet() > 2,
                QualityRun.Listener.NONE);

        assertThat(first.cancelled()).isTrue();
        assertThat(first.results()).extracting(CaseResult::id).containsExactly("A", "B");

        Map<String, CaseResult> done = new java.util.HashMap<>();
        first.results().forEach(r -> done.put(r.id(), r));
        List<Integer> progress = new ArrayList<>();
        QualityRun.Result rest = run.run(cases, done, () -> false, (n, total, r) -> progress.add(n));

        assertThat(rest.results()).extracting(CaseResult::id).containsExactly("A", "B", "C", "D", "X");
        assertThat(search.calls.get()).isEqualTo(5);
        assertThat(progress).containsExactly(3, 4, 5);
    }

    @Test
    void anInterruptedSearchEndsTheRunAndLeavesTheQuestionUnmeasured() {
        QualitySearch interrupted = (question, interim) -> {
            if (question.id().equals("B")) {
                throw new InterruptedException();
            }
            return new QualitySearch.Answer(List.of(hit("1", 0.9)), "QUALITY_REACHED", 1, 0);
        };
        try {
            QualityRun.Result result = new QualityRun(interrupted, page -> false, new QualityRun.Settings(8, 0.5),
                    clock::get).run(cases, Map.of(), () -> false, QualityRun.Listener.NONE);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(result.cancelled()).isTrue();
            assertThat(result.results()).extracting(CaseResult::id).containsExactly("A");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFailedNoAnswerSearchCountsAsFailed() {
        QualitySearch broken = (question, interim) -> {
            throw new IllegalStateException("Ausfall");
        };
        List<CaseResult> results = new QualityRun(broken, page -> false, new QualityRun.Settings(8, 0.5), clock::get)
                .run(List.of(question("C", List.of(), CaseKind.KEINE)), Map.of(), () -> false,
                        QualityRun.Listener.NONE).results();

        QualitySummary summary = QualitySummary.of(results, 8);

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.noAnswerCorrect()).isZero();
    }

    @Test
    void theResultFileRoundTripsAndTheComparisonFindsChanges() {
        List<CaseResult> before = run.run(cases, Map.of(), () -> false, QualityRun.Listener.NONE).results();
        RunInfo info = new RunInfo("1.0", "Varianten KI, Schlagwort", Instant.parse("2026-11-21T10:00:00Z"),
                "fragen.tsv", 8, 0.5, 5, false);

        QualityResults.Stored stored = QualityResults.read(QualityResults.write(info, before));

        assertThat(stored.errors()).isEmpty();
        assertThat(stored.info()).isEqualTo(info);
        assertThat(stored.results()).isEqualTo(before);

        Fake better = new Fake(Map.of(
                "A", List.of(hit("1", 0.9)),
                "B", List.of(hit("5", 0.9)),
                "C", List.of(hit("7", 0.2)),
                "D", List.of(hit("7", 0.3))), clock);
        List<CaseResult> after = new QualityRun(better, page -> true, new QualityRun.Settings(8, 0.5), clock::get)
                .run(cases.subList(0, 4), Map.of(), () -> false, QualityRun.Listener.NONE).results();

        QualityComparison comparison = QualityComparison.of(stored, after, 8);

        assertThat(comparison.changes()).extracting(QualityComparison.Change::id, QualityComparison.Change::way)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A", QualityComparison.Direction.BETTER),
                        org.assertj.core.groups.Tuple.tuple("B", QualityComparison.Direction.WORSE),
                        org.assertj.core.groups.Tuple.tuple("C", QualityComparison.Direction.BETTER),
                        org.assertj.core.groups.Tuple.tuple("X", QualityComparison.Direction.REMOVED));
    }

    @Test
    void theReportHasIdsAndNumbersButNoQuestionsTitlesOrPages() {
        List<CaseResult> results = run.run(cases, Map.of(), () -> false, QualityRun.Listener.NONE).results();
        RunInfo info = new RunInfo("1.0", "Varianten KI, Schlagwort", Instant.parse("2026-11-21T10:00:00Z"),
                "fragen.tsv", 8, 0.5, 5, false);
        QualityComparison comparison = QualityComparison.of(new QualityResults.Stored(info, results, List.of()),
                results, 8);

        String report = QualityReport.markdown(info, results,
                List.of(new QualitySuite.LineError(7, "Fundstelle nicht lesbar (Z9)")), comparison);

        assertThat(report).contains("| A |", "Treffer unter den ersten 5 | 2 von 3", "Zeile 7", "zu Recht leer",
                "Keine Frage hat sich verändert");
        assertThat(report).doesNotContain(SECRET_QUESTION, "Titel ", "q:1", "q:9");
        assertThat(QualityResults.write(info, results)).contains(SECRET_QUESTION, "Titel 9", "q:9");
    }
}
