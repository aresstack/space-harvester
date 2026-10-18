package com.aresstack.spaceharvester.application.ranking;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.ExcerptPriority;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link ModelReranker}: Cross-Encoder ordnet nur um, nur mit freigegebenen Texten, Ausfall lässt die Reihenfolge. */
class ModelRerankerTest {

    private static final PageSearchQuery QUERY = PageSearchQuery.of(null, List.of(), null, null, null, null, null,
            null, SearchSort.TITLE);

    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final List<List<String>> sent = new ArrayList<>();
    private List<SearchHit> hits;

    @BeforeEach
    void publish() {
        SearchIndexState state = new SearchIndexState("i1", Instant.parse("2026-10-17T08:00:00Z"));
        index.publish("wiki", state, List.of(entry(page("wiki", "1", "Architektur"), confirmed("g1")),
                entry(page("wiki", "2", "Nachtbetrieb"), confirmed("g1"))));
        index.publish("intern", state, List.of(entry(page("intern", "3", "Geheimer Betrieb"), confirmed("g1"))));
        hits = new ArrayList<>();
        hits.addAll(index.search(QUERY.with("wiki", "1", SearchSort.PAGE_ID), Set.of("wiki"), "i1", null, 1));
        hits.addAll(index.search(QUERY.with("intern", "3", SearchSort.PAGE_ID), Set.of("intern"), "i1", null, 1));
        hits.addAll(index.search(QUERY.with("wiki", "2", SearchSort.PAGE_ID), Set.of("wiki"), "i1", null, 1));
    }

    @Test
    void ordersApprovedHitsByScoreAndLeavesOthersInPlace() {
        ModelReranker reranker = reranker((query, documents) -> {
            sent.add(documents);
            return documents.stream().mapToDouble(document -> document.contains("Nacht") ? 0.9 : 0.1).toArray();
        });

        List<SearchHit> reordered = new Reranking(reranker).apply(request("nachts", hits));

        assertThat(ids(reordered)).containsExactly("2", "3", "1");
        // Texte aus „intern“ verlassen das Haus nicht.
        assertThat(sent).containsExactly(List.of("Architektur", "Nachtbetrieb"));
        assertThat(reranker.id()).isEqualTo("cross-encoder@bge-reranker");
    }

    @Test
    void aFailingOrUnusableServiceKeepsTheOrder() {
        assertThat(ids(new Reranking(reranker((query, documents) -> {
            throw new IllegalStateException("Dienst weg");
        })).apply(request("nachts", hits)))).containsExactly("1", "3", "2");
        assertThat(ids(new Reranking(reranker((query, documents) -> new double[] {1}))
                .apply(request("nachts", hits)))).containsExactly("1", "3", "2");
        assertThat(ids(new Reranking(reranker((query, documents) -> new double[] {Double.NaN, 1}))
                .apply(request("nachts", hits)))).containsExactly("1", "3", "2");
    }

    @Test
    void withoutQueryTextNothingIsSent() {
        ModelReranker reranker = reranker((query, documents) -> {
            sent.add(documents);
            return new double[documents.size()];
        });

        assertThat(ids(new Reranking(reranker).apply(QUERY, hits))).containsExactly("1", "3", "2");
        assertThat(ids(new Reranking(reranker).apply(request(" ", hits)))).containsExactly("1", "3", "2");
        assertThat(sent).isEmpty();
    }

    @Test
    void theExcerptPriorityUsesTheSameServiceAndTheSameApproval() {
        ModelReranker reranker = reranker((query, documents) -> {
            sent.add(documents);
            return documents.stream().mapToDouble(document -> document.contains("Nacht") ? 0.9 : 0.1).toArray();
        });
        List<KeywordSearch.Hit> found = List.of(found("wiki", "1", "Architektur"), found("intern", "3", "Geheim"),
                found("wiki", "2", "Nachtbetrieb"));

        ExcerptPriority.Priority priority = reranker.excerptPriority().prioritize("nachts", found);

        assertThat(priority.order()).containsExactly(2, 1, 0);
        assertThat(sent).containsExactly(List.of("Architektur", "Nachtbetrieb"));
    }

    private static KeywordSearch.Hit found(String sourceId, String pageId, String title) {
        return new KeywordSearch.Hit(sourceId, pageId, "DEV", title, "", null, "1", null, null);
    }

    private static ModelReranker reranker(ModelReranker.Scorer scorer) {
        return new ModelReranker("bge-reranker", scorer, "wiki"::equals);
    }

    private static SearchReranker.Request request(String text, List<SearchHit> candidates) {
        return new SearchReranker.Request(QUERY, text, candidates, hit -> hit.page().header().metadata().title());
    }

    private static List<String> ids(List<SearchHit> hits) {
        return hits.stream().map(hit -> hit.page().pageId()).toList();
    }

    private static PageSnapshot page(String sourceId, String pageId, String title) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:" + sourceId + "/page/" + pageId, title, "DEV",
                null, "1", Instant.parse("2026-10-01T00:00:00Z"), null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of(sourceId, metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test"), Instant.parse("2026-10-16T20:00:00Z"));
    }
}
