package com.aresstack.askai.research.search.api;

public enum SearchEngine {
    MULTI("multi"),
    BRAVE("brave"),
    GOOGLE("google"),
    BING("bing"),
    DUCKDUCKGO("duckduckgo"),
    YAHOO("yahoo"),
    YANDEX("yandex"),
    BAIDU("baidu"),
    NAVER("naver"),
    SEZNAM("seznam");

    private final String apiValue;

    SearchEngine(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
