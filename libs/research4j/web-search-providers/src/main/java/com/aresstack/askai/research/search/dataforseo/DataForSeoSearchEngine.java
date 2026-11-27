package com.aresstack.askai.research.search.dataforseo;

import com.aresstack.askai.research.search.api.SearchEngine;

public enum DataForSeoSearchEngine {
    GOOGLE(SearchEngine.GOOGLE, "google"),
    BING(SearchEngine.BING, "bing"),
    YAHOO(SearchEngine.YAHOO, "yahoo"),
    BAIDU(SearchEngine.BAIDU, "baidu"),
    NAVER(SearchEngine.NAVER, "naver"),
    SEZNAM(SearchEngine.SEZNAM, "seznam");

    private final SearchEngine searchEngine;
    private final String apiValue;

    DataForSeoSearchEngine(
            SearchEngine searchEngine,
            String apiValue) {

        this.searchEngine = searchEngine;
        this.apiValue = apiValue;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }

    public String getApiValue() {
        return apiValue;
    }
}
