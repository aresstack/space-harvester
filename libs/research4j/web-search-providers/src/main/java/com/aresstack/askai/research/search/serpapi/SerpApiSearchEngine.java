package com.aresstack.askai.research.search.serpapi;

import com.aresstack.askai.research.search.api.SearchEngine;

/** The engines Serp4J's SerpApi provider serves ({@code engine=google} / {@code engine=bing}). */
public enum SerpApiSearchEngine {
    GOOGLE(SearchEngine.GOOGLE),
    BING(SearchEngine.BING);

    private final SearchEngine searchEngine;

    SerpApiSearchEngine(SearchEngine searchEngine) {
        this.searchEngine = searchEngine;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }
}
