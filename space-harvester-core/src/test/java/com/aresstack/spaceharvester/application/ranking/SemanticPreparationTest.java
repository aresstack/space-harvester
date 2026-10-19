package com.aresstack.spaceharvester.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

    @Test
    void aFailedEmbeddingRunLeavesThePreparationIncomplete() {
        found(new KeywordSearch.Results(null, List.of(HIT), List.of(searched()), null, true, 0));
        when(enrichments.view(snapshot)).thenReturn(List.of());
        when(enrichments.recompute(snapshot, "embedding")).thenReturn(new Enrichments.Run(true, 1, 0, 1));

        assertThat(preparation().prepare(APP, query, "Nachtlauf")).isEqualTo(1);
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
