package com.aresstack.askai.research.search.application;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchHit;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;

/** URL de-duplication keeps the query string (WP5 bug: "?id=1" and "?id=2" were merged). Synthetic data. */
public class CompositeWebSearchProviderDedupTest {

    @Test
    public void differentQueryStringsAreDifferentDocuments() throws Exception {
        WebSearchProvider a = provider(SearchProviderId.BRAVE, SearchEngine.BRAVE,
                "https://example.org/item?id=1", "https://example.org/page/#top");
        WebSearchProvider b = provider(SearchProviderId.DATA_FOR_SEO, SearchEngine.BING,
                "https://example.org/item?id=2", "HTTPS://EXAMPLE.org:443/page");

        WebSearchResult result = new CompositeWebSearchProvider(Arrays.asList(a, b), true)
                .search(WebSearchRequest.builder("q").build()).get();

        // id=1, id=2 and one /page (fragment, trailing slash, case and default port are normalised away)
        assertEquals(3, result.getHits().size());
    }

    private static WebSearchProvider provider(final SearchProviderId id, final SearchEngine engine,
                                              final String... urls) {
        return new WebSearchProvider() {
            public SearchProviderId getProviderId() {
                return id;
            }

            public boolean supports(SearchEngine searchEngine) {
                return true;
            }

            public CompletableFuture<WebSearchResult> search(WebSearchRequest request) {
                WebSearchHit[] hits = new WebSearchHit[urls.length];
                for (int i = 0; i < urls.length; i++) {
                    hits[i] = new WebSearchHit(id, engine, i + 1, "t" + i, urls[i], "");
                }
                List<WebSearchHit> list = Arrays.asList(hits);
                return CompletableFuture.completedFuture(new WebSearchResult(id, engine, list, ""));
            }

            public void close() {
            }
        };
    }
}
