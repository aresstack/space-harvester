package com.aresstack.spaceharvester.application.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** „Suchqualität messen“ als Auftrag (Q1b) mit einer Fake-Suche; nur erfundene Fragen. */
class SearchQualityRunsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("app");
    private static final String SECRET = "Wer genehmigt den geheimen Mittelabruf?";

    @TempDir
    Path folder;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run,
            new JobLimits(2, 5, Duration.ofHours(1)), clock);
    private final AtomicInteger asked = new AtomicInteger();

    private SearchQualityRuns runs(QualitySearch search) {
        return new SearchQualityRuns(List::of, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), (caller, timeout) -> search,
                (caller, key) -> key.equals(new PageKey("q", "1")), SearchProfile::defaults, jobs, clock);
    }

    private Path suite() throws Exception {
        Path suite = folder.resolve("fragen.tsv");
        Files.writeString(suite, String.join("\n",
                "id\tfrage\tbereich\terwartet\tart",
                "F1\t" + SECRET + "\tq\tq:1\t",
                "F2\tFrage zwei\tq\tq:2\t",
                "F3\tFrage drei\tq\t\tkeine",
                "F4\tFrage vier\tq\tunlesbar\t"), StandardCharsets.UTF_8);
        return suite;
    }

    private final QualitySearch search = (question, interim) -> {
        asked.incrementAndGet();
        return new QualitySearch.Answer(List.of(new QualitySearch.RankedPage(new PageRef("q", "1"), 0.9, "Titel")),
                "QUALITY_REACHED", 1, 0);
    };

    @Test
    void theJobWritesResultAndReportNextToTheSuiteAndNamesOnlyTheSuite() throws Exception {
        JobStatus status = runs(search).submit(CALLER, new SearchQualityRuns.Request(suite(), null, null, 8,
                Duration.ofSeconds(90), "1.0"));

        JobStatus done = jobs.status(status.id(), CALLER.jobOwner());
        assertThat(done.type()).isEqualTo(JobType.SEARCH_QUALITY);
        assertThat(done.sourceId()).isEqualTo(Jobs.ALL_SOURCES);
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.incomplete()).isTrue();
        assertThat(done.progress().discovered()).isEqualTo(3);
        assertThat(done.progress().processed()).isEqualTo(3);
        assertThat(done.progress().written()).isEqualTo(1);
        assertThat(SearchQualityRuns.suiteName(done)).isEqualTo("fragen.tsv");
        assertThat(done.parameters().toString()).doesNotContain(SECRET);

        Path result = Path.of(done.parameter(SearchQualityRuns.RESULT_PARAMETER));
        assertThat(result.getParent()).isEqualTo(folder.toAbsolutePath());
        assertThat(result.getFileName().toString()).startsWith("fragen – 2026-11-2").endsWith(".tsv");
        assertThat(result).exists();
        Path report = SearchQualityRuns.report(result);
        assertThat(Files.readString(report)).contains("Gemessen: 3 von 3 Fragen", "Zeile 5")
                .doesNotContain(SECRET, "Titel", "q:1");
        assertThat(Files.readString(result)).contains(SECRET);
        assertThat(asked.get()).isEqualTo(3);
    }

    @Test
    void resumingMeasuresOnlyWhatTheResultFileLacks() throws Exception {
        SearchQualityRuns runs = runs(search);
        JobStatus first = runs.submit(CALLER, new SearchQualityRuns.Request(suite(), null, null, 8,
                Duration.ofSeconds(90), "1.0"));
        Path result = Path.of(jobs.status(first.id(), CALLER.jobOwner()).parameter(
                SearchQualityRuns.RESULT_PARAMETER));
        // Ergebnisdatei ohne F2, wie nach einem Abbruch.
        List<String> lines = Files.readAllLines(result, StandardCharsets.UTF_8);
        Files.write(result, lines.stream().filter(line -> !line.startsWith("F2\t")).toList(),
                StandardCharsets.UTF_8);
        asked.set(0);

        runs.resume(first.id(), CALLER.jobOwner());

        assertThat(asked.get()).isEqualTo(1);
        assertThat(Files.readString(result)).contains("F1\t", "F2\t", "F3\t");
    }

    @Test
    void theProfileShowsInTheResultHead() {
        assertThat(SearchQualityRuns.describe(SearchProfile.defaults()))
                .contains("Varianten KI", "Ranking Schlagwort", "Ziel 5 Treffer ab 0.5");
    }
}
