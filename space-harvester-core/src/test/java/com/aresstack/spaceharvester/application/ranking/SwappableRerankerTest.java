package com.aresstack.spaceharvester.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ein austauschbarer Reranker (ADR 0015): jede Suche nimmt den, der gerade eingestellt ist. */
class SwappableRerankerTest {

    private static SearchReranker named(String id) {
        return new SearchReranker() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates) {
                return candidates;
            }
        };
    }

    @Test
    void searchesUseTheRerankerSetWhenTheyStart() {
        SwappableReranker reranker = new SwappableReranker(SearchReranker.NONE);
        assertThat(SwappableReranker.resolve(reranker)).isSameAs(SearchReranker.NONE);

        SearchReranker model = named("cross-encoder@a");
        reranker.swap(model);

        assertThat(SwappableReranker.resolve(reranker)).isSameAs(model);
        assertThat(reranker.id()).isEqualTo("cross-encoder@a");
        assertThat(SwappableReranker.resolve(model)).isSameAs(model);
    }
}
