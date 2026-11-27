package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.api.SearchEngine;

public enum BrightDataSearchEngine {
    GOOGLE(SearchEngine.GOOGLE, "https://www.google.com/search"),
    BING(SearchEngine.BING, "https://www.bing.com/search"),
    DUCKDUCKGO(SearchEngine.DUCKDUCKGO, "https://duckduckgo.com/"),
    YANDEX(SearchEngine.YANDEX, "https://yandex.com/search/");

    private final SearchEngine searchEngine;
    private final String defaultEndpoint;

    BrightDataSearchEngine(
            SearchEngine searchEngine,
            String defaultEndpoint) {

        this.searchEngine = searchEngine;
        this.defaultEndpoint = defaultEndpoint;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }

    public String getDefaultEndpoint() {
        return defaultEndpoint;
    }
}
