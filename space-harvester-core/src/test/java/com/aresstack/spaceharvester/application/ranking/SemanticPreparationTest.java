package com.aresstack.spaceharvester.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SemanticPreparationTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final KeywordSearch.Hit HIT = new KeywordSearch.Hit("wiki", "1", "DEV", "T", "", null, "1", null,
            null);

    private final KeywordSearch keywords = mock(KeywordSearch.class);
    private final Enrichments enrichments = mock(Enrichments.class);
    private final PageSnapshot snapshot = mock(PageSnapshot.class);
    private final PageSearchQuery query = mock(PageSearchQuery.class);

    @org.junit.jupiter.api.BeforeEach
    void allowPreparationByDefault() {
        when(keywords.allowsPrefetch(any())).thenReturn(true);
    }

    @Test
    void aSourceWithoutPrefetchDoesNotAcquireEmbeddingsDuringSearch() {
        found(new KeywordSearch.Results(null, List.of(HIT), List.of(searched()), null, true, 0));
        when(keywords.allowsPrefetch("wiki")).thenReturn(false);

        assertThat(preparation().prepare(APP, query, "Nachtlauf")).isZero();
        org.mockito.Mockito.verifyNoInteractions(enrichments);
    }

    @Test
    void aFailedEmbeddingRunLeavesThePreparationIncomplete() {
        found(new KeywordSearch.Results(null, List.of(HIT), List.of(searched()), null, true, 0));
        when(enrichments.view(snapshot)).thenReturn(List.of());
        when(enrichments.recompute(snapshot, "embedding")).thenReturn(new Enrichments.Run(true, 1, 0, 1));

        assertThat(preparation().prepare(APP, query, "Nachtlauf")).isEqualTo(1);
    }

    @Test
    void afterTheDeadlineWhatHasNotStartedGoesToTheEnrichmentQueueAndNoJobIsCreated() throws Exception {
        // Angelo 24.11.2026: keine Aufträge „Suchtreffer vorbereiten“ mehr, die Plätze belegen und alles aufhalten;
        // der Rest geht an die KI-Anreicherung (eine Seite einmal, mit Wiederholung und Grund).
        found(new KeywordSearch.Results(null, List.of(HIT, HIT), List.of(searched()), null, true, 0));
        when(enrichments.view(snapshot)).thenReturn(List.of());
        when(enrichments.recomputeInBackground(any(), any())).thenReturn(1);
        java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(2, 10, Duration.ofHours(1)),
                Clock.systemUTC());

        int pending = new SemanticPreparation(keywords, key -> Optional.of(snapshot), enrichments, "embedding",
                Duration.ZERO, jobs).prepare(APP, query, "Nachtlauf");

        assertThat(pending).as("diese Suche ist unvollständig").isEqualTo(1);
        verify(enrichments, never()).recompute(snapshot, "embedding");
        verify(enrichments, times(1)).recomputeInBackground(snapshot, List.of("embedding"));
        assertThat(jobs.listAll(JobType.ENRICHMENT)).as("kein Auftrag je Suche").isEmpty();
        assertThat(held).isEmpty();
    }

    @Test
    void interruptedJobsOfEarlierVersionsAreForgottenAndOthersStay() {
        InMemoryJobStore store = new InMemoryJobStore();
        Jobs before = new Jobs(store, task -> { }, new JobLimits(2, 10, Duration.ofHours(1)), Clock.systemUTC());
        String old = before.submitInteractive(JobType.ENRICHMENT, "wiki", null, null, APP.jobOwner(),
                java.util.Map.of("task", SemanticPreparation.TASK),
                com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> { })).id();
        String other = before.submit(JobType.ENRICHMENT, "wiki", "DEV", null, APP.jobOwner(),
                java.util.Map.of("task", "after-sync"),
                com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> { })).id();

        // Neustart: beide gelten als unterbrochen.
        Jobs restarted = new Jobs(store, task -> { }, new JobLimits(2, 10, Duration.ofHours(1)), Clock.systemUTC());
        assertThat(SemanticPreparation.forgetInterrupted(restarted)).isEqualTo(1);

        assertThat(restarted.listAll(JobType.ENRICHMENT)).extracting(
                com.aresstack.spaceharvester.application.jobs.JobStatus::id).containsExactly(other).doesNotContain(old);
    }

    @Test
    void anIncompleteKeywordSearchLeavesThePreparationIncomplete() {
        found(new KeywordSearch.Results(null, List.of(), List.of(new KeywordSearch.SourceState("wiki",
                KeywordSearch.SourceState.Status.UNAVAILABLE, "UNAVAILABLE")), null, false, 0));

        assertThat(preparation().prepare(APP, query, "Nachtlauf")).isEqualTo(1);
    }

    @Test
    void moreHitsThanPreparedAreNoGap() {
        List<KeywordSearch.Hit> hits = IntStream.range(0, SemanticPreparation.MAX_PAGES)
                .mapToObj(i -> HIT).toList();
        found(new KeywordSearch.Results(null, hits, List.of(searched()), "k2.weiter", false, 0));
        when(enrichments.view(snapshot)).thenReturn(List.of());
        when(enrichments.recompute(snapshot, "embedding")).thenReturn(new Enrichments.Run(true, 1, 0, 0));

        assertThat(preparation().prepare(APP, query, "Nachtlauf")).isZero();
    }

    @Test
    void excludedSpacesAreNeitherFoundNorLoaded() {
        found(new KeywordSearch.Results(null, List.of(), List.of(searched()), null, true, 0));

        preparation().prepare(APP, query, "Nachtlauf", Set.of("OPS"));

        verify(keywords).search(any(), argThat(q -> q.excludedSpaceKeys().equals(Set.of("OPS"))), isNull(),
                anyInt(), isNull());
    }

    @Test
    void theGlossarySynonymsMethodGoesIntoTheKeywordSearchOfThePreparation() {
        found(new KeywordSearch.Results(null, List.of(), List.of(searched()), null, true, 0));

        preparation().prepare(APP, query, "Wolkenpegel", Set.of(), null, true);
        verify(keywords).search(any(), argThat(KeywordSearch.Query::glossarySynonyms), isNull(), anyInt(), isNull());
    }

    @Test
    void withoutTheMethodThePreparationSearchesWithoutSynonyms() {
        found(new KeywordSearch.Results(null, List.of(), List.of(searched()), null, true, 0));

        preparation().prepare(APP, query, "Wolkenpegel");
        verify(keywords).search(any(), argThat(q -> !q.glossarySynonyms()), isNull(), anyInt(), isNull());
    }

    private void found(KeywordSearch.Results results) {
        when(keywords.search(any(), any(), isNull(), anyInt(), any())).thenReturn(results);
    }

    private static KeywordSearch.SourceState searched() {
        return new KeywordSearch.SourceState("wiki", KeywordSearch.SourceState.Status.SEARCHED, null);
    }

    private SemanticPreparation preparation() {
        return new SemanticPreparation(keywords, key -> Optional.of(snapshot), enrichments, "embedding",
                Duration.ofSeconds(5));
    }
}
