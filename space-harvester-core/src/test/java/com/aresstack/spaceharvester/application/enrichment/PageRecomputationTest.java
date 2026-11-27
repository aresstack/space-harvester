package com.aresstack.spaceharvester.application.enrichment;

import static com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** „Neu berechnen“ einer Seite als Auftrag (Fundstelle 21). Nur erfundene Quellen und Texte. */
class PageRecomputationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T03:00:00Z"), ZoneOffset.UTC);
    private static final PageTextProjector PROJECTOR = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    @Test
    void recomputingAPageIsAVisibleJobAndReturnsItsRun() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(new Counting(calls)), store, CLOCK);
        Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10, Duration.ofHours(1)), CLOCK);
        PageSnapshot page = text("wiki", "7", "1", "Ein erfundener Text");
        enrichments.refresh(page);

        Enrichments.Run run = new PageRecomputation(jobs, enrichments).recompute("app", page, null);

        assertThat(run.computed()).isEqualTo(1);
        assertThat(calls).as("auch aktuelle Ergebnisse neu").hasValue(2);
        assertThat(jobs.listAll(JobType.ENRICHMENT)).singleElement().satisfies(job -> {
            assertThat(job.owner()).isEqualTo("app");
            assertThat(job.state()).isEqualTo(JobState.COMPLETED);
            assertThat(job.sourceId()).isEqualTo("wiki");
            assertThat(job.parameter(EnrichmentsAfterSync.TASK_PARAMETER)).isEqualTo(PageRecomputation.TASK);
            assertThat(job.parameter(PageRecomputation.ROOT)).isEqualTo("7");
            assertThat(job.progress().written()).isEqualTo(1);
        });
    }

    @Test
    void aFailedProcessorCountsAsFailedInTheJob() {
        InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(new Failing()), store, CLOCK);
        Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10, Duration.ofHours(1)), CLOCK);

        Enrichments.Run run = new PageRecomputation(jobs, enrichments).recompute("app",
                text("wiki", "8", "1", "Noch ein erfundener Text"), null);

        assertThat(run.failed()).isEqualTo(1);
        JobStatus job = jobs.listAll(JobType.ENRICHMENT).getFirst();
        assertThat(job.progress().failed()).isEqualTo(1);
    }

    @Test
    void cancellingDuringAProcessorStopsBeforeTheNextAndReportsTheCancellation() {
        // Codex-Befund #1078: Nach dem Abbruch startet kein weiterer Prozessor, und der Knopf meldet keinen Erfolg.
        AtomicInteger calls = new AtomicInteger();
        InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
        Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10, Duration.ofHours(1)), CLOCK);
        EnrichmentProcessor cancelling = new EnrichmentProcessor() {
            @Override
            public String id() {
                return "erster";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public EnrichmentOutput process(PageText text) {
                jobs.cancel(jobs.listAll(JobType.ENRICHMENT).getFirst().id());
                return EnrichmentOutput.of("{}");
            }
        };
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(cancelling, new Counting(calls)), store, CLOCK);

        assertThatThrownBy(() -> new PageRecomputation(jobs, enrichments).recompute("app",
                text("wiki", "9", "1", "Ein dritter erfundener Text"), null))
                .isInstanceOf(JobCancelledException.class);

        assertThat(calls).as("der zweite Prozessor startet nicht mehr").hasValue(0);
        assertThat(jobs.listAll(JobType.ENRICHMENT).getFirst().state()).isEqualTo(JobState.CANCELLED);
    }

    @Test
    void recomputingStaleAnalysesLocallySkipsNetworkProcessorsAndUnknownIds() {
        // Selbsttätig beim Öffnen: nur ohne Netz, derselbe sichtbare Auftrag wie der Knopf.
        AtomicInteger local = new AtomicInteger();
        AtomicInteger network = new AtomicInteger();
        InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(new Counting(local), new Remote(network)),
                store, CLOCK);
        Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10, Duration.ofHours(1)), CLOCK);
        PageSnapshot page = text("wiki", "10", "1", "Ein vierter erfundener Text");
        PageRecomputation recomputation = new PageRecomputation(jobs, enrichments);

        assertThat(recomputation.localProcessors(page.key(), List.of("netz", "zaehler", "unbekannt")))
                .containsExactly("zaehler");
        Enrichments.Run run = recomputation.recomputeLocal("app", page, List.of("netz", "zaehler", "unbekannt"),
                state -> { });

        assertThat(run.computed()).isEqualTo(1);
        assertThat(local).hasValue(1);
        assertThat(network).as("kein Netzaufruf ohne Klick").hasValue(0);
        assertThat(jobs.listAll(JobType.ENRICHMENT)).singleElement().satisfies(job -> {
            assertThat(job.owner()).isEqualTo("app");
            assertThat(job.state()).isEqualTo(JobState.COMPLETED);
            assertThat(job.parameter(EnrichmentsAfterSync.TASK_PARAMETER)).isEqualTo(PageRecomputation.TASK);
        });
    }

    @Test
    void onlyNetworkProcessorsStartNoJob() {
        AtomicInteger network = new AtomicInteger();
        Enrichments enrichments = new Enrichments(PROJECTOR, List.of(new Remote(network)),
                new InMemoryEnrichmentStore(), CLOCK);
        Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10, Duration.ofHours(1)), CLOCK);

        Enrichments.Run run = new PageRecomputation(jobs, enrichments).recomputeLocal("app",
                text("wiki", "11", "1", "Ein fünfter erfundener Text"), List.of("netz"), state -> { });

        assertThat(run).isNull();
        assertThat(network).hasValue(0);
        assertThat(jobs.listAll(JobType.ENRICHMENT)).isEmpty();
    }

    private record Remote(AtomicInteger calls) implements EnrichmentProcessor {

        @Override
        public String id() {
            return "netz";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public boolean background() {
            return true;
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            calls.incrementAndGet();
            return EnrichmentOutput.of("{}");
        }
    }

    private record Counting(AtomicInteger calls) implements EnrichmentProcessor {

        @Override
        public String id() {
            return "zaehler";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            calls.incrementAndGet();
            return EnrichmentOutput.of("{}");
        }
    }

    private static final class Failing implements EnrichmentProcessor {

        @Override
        public String id() {
            return "kaputt";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            throw new IllegalStateException("Dienst nicht erreichbar");
        }
    }
}
