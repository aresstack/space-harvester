package com.aresstack.askai.research.search.dataforseo;

public enum DataForSeoSerpElementType {
    ORGANIC("organic"),
    PAID("paid"),
    LOCAL_PACK("local_pack"),
    FEATURED_SNIPPET("featured_snippet"),
    EVENTS("events"),
    GOOGLE_FLIGHTS("google_flights"),
    IMAGES("images"),
    JOBS("jobs"),
    KNOWLEDGE_GRAPH("knowledge_graph"),
    LOCAL_SERVICE("local_service"),
    MAP("map"),
    SCHOLARLY_ARTICLES("scholarly_articles"),
    THIRD_PARTY_REVIEWS("third_party_reviews"),
    TWITTER("twitter");

    private final String apiValue;

    DataForSeoSerpElementType(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
