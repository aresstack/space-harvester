package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAvailability;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Google in the search browser may fetch several result pages ("google:on:3"): page n asks for offset n * 10. */
public class SingleProviderPagingTest {

    private static final SearchBudgetGate ALLOW = new SearchBudgetGate() {
        public boolean beforeToolCall() {
            return true;
        }
    };

    private static final class PagedProvider implements SearchProvider {
        final List<Integer> offsets = new ArrayList<Integer>();
        final int pagesWithHits;

        PagedProvider(int pagesWithHits) {
            this.pagesWithHits = pagesWithHits;
        }

        public SearchProviderId getProviderId() {
            return SearchProviderId.GOOGLE_BROWSER;
        }

        public SearchProviderResult search(SearchProviderRequest request) {
            offsets.add(request.getOffset());
            List<SearchHit> hits = new ArrayList<SearchHit>();
            int page = request.getOffset() / 10;
            if (page < pagesWithHits) {
                for (int i = 1; i <= 2; i++) {
                    hits.add(new SearchHit(SearchProviderId.GOOGLE_BROWSER, SearchEngine.GOOGLE, "q", i,
                            "https://example.org/p" + page + "/" + i, "t", "s", "example.org", i, ""));
                }
            }
            return new SearchProviderResult(SearchProviderId.GOOGLE_BROWSER, SearchEngine.GOOGLE, hits);
        }

        public SearchProviderAvailability getAvailability() {
            return SearchProviderAvailability.AVAILABLE;
        }
    }

    @Test
    public void fetchesTheConfiguredPagesWithGoogleOffsets() {
        PagedProvider provider = new PagedProvider(5);
        InitialSearchResult result = new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE, 3, 10, 0L)
                .search(new InitialSearchRequest("q", 20, null, null), CancellationSignal.NONE, ALLOW);
        assertEquals(Arrays.asList(0, 10, 20), provider.offsets);
        assertEquals(6, result.candidates.size());
    }

    @Test
    public void defaultFetchesOnePage() {
        PagedProvider provider = new PagedProvider(5);
        new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE)
                .search(new InitialSearchRequest("q", 20, null, null), CancellationSignal.NONE, ALLOW);
        assertEquals(Arrays.asList(0), provider.offsets);
    }

    @Test
    public void stopsWhenAPageIsEmpty() {
        PagedProvider provider = new PagedProvider(1);
        InitialSearchResult result = new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE, 3, 10, 0L)
                .search(new InitialSearchRequest("q", 20, null, null), CancellationSignal.NONE, ALLOW);
        assertEquals(Arrays.asList(0, 10), provider.offsets);
        assertEquals(2, result.candidates.size());
    }
}
