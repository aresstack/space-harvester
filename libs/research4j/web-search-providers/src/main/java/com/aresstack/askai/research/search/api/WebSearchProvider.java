package com.aresstack.askai.research.search.api;

import java.util.concurrent.CompletableFuture;

public interface WebSearchProvider extends AutoCloseable {

    SearchProviderId getProviderId();

    boolean supports(SearchEngine searchEngine);

    CompletableFuture<WebSearchResult> search(
            WebSearchRequest request);

    @Override
    void close();
}
