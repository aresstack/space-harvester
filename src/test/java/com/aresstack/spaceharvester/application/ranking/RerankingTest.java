package com.aresstack.spaceharvester.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Reranking ordnet nur um: kein Treffer geht verloren, keiner kommt hinzu, Fehler lassen die Suche unberührt. */
class RerankingTest {

    private static final PageSearchQuery QUERY = PageSearchQuery.of("wiki", List.of("DEV"), "betrieb", null, null,
            null, null, null, SearchSort.TITLE);
    private final List<SearchHit> hits = List.of(hit("101"), hit("102"), hit("103"));

    @Test
    void theDefaultKeepsTheIndexOrder() {
        Reranking reranking = new Reranking(SearchReranker.NONE);

        assertThat(reranking.rerankerId()).isEqualTo("none");
        assertThat(reranking.apply(QUERY, hits)).containsExactlyElementsOf(hits);
    }

    @Test
    void aValidReorderingIsApplied() {
        List<SearchHit> result = new Reranking(reranker(candidates -> {
            List<SearchHit> reversed = new ArrayList<>(candidates);
            Collections.reverse(reversed);
            return reversed;
        })).apply(QUERY, hits);

        assertThat(result).extracting(hit -> hit.page().pageId()).containsExactly("103", "102", "101");
    }

    @Test
    void droppingAddingDuplicatingOrFailingFallsBackToTheIndexOrder() {
        SearchHit foreign = hit("999");
        List<Function<List<SearchHit>, List<SearchHit>>> broken = List.of(
                candidates -> candidates.subList(0, 2),
                candidates -> List.of(candidates.get(0), candidates.get(1), foreign),
                candidates -> List.of(candidates.get(0), candidates.get(0), candidates.get(1)),
                candidates -> List.of(hit("101"), hit("102"), hit("103")),
                candidates -> null,
                candidates -> {
                    throw new IllegalStateException("Modell weg");
                });

        for (Function<List<SearchHit>, List<SearchHit>> rerank : broken) {
            assertThat(new Reranking(reranker(rerank)).apply(QUERY, hits)).containsExactlyElementsOf(hits);
        }
    }

    @Test
    void theWindowIsBounded() {
        List<SearchHit> many = new ArrayList<>();
        for (int i = 0; i <= Reranking.MAX_WINDOW; i++) {
            many.add(hit(String.valueOf(1000 + i)));
        }

        assertThatThrownBy(() -> new Reranking(SearchReranker.NONE).apply(QUERY, many))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SearchReranker reranker(Function<List<SearchHit>, List<SearchHit>> rerank) {
        return new SearchReranker() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates) {
                return rerank.apply(candidates);
            }
        };
    }

    private static SearchHit hit(String pageId) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId,
                "Betrieb " + pageId, "DEV", null, "1", Instant.parse("2026-10-15T10:30:00Z"), null,
                PageMetadata.sorted(Map.of()));
        PageHeader header = new PageHeader(new PageKey("wiki", pageId), metadata, MetadataHash.of("wiki", metadata),
                AttributesHash.of("wiki", metadata), Instant.parse("2026-10-16T20:00:00Z"));
        return new SearchHit(new ListedPage(header, null),
                new CacheGeneration("g1", CacheState.CONFIRMED, null, null), pageId);
    }
}
