package com.aresstack.askai.research.search.dataforseo;

public enum DataForSeoTargetMatchType {
    DOMAIN("domain"),
    WITH_SUBDOMAINS("with_subdomains"),
    WILDCARD("wildcard");

    private final String apiValue;

    DataForSeoTargetMatchType(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
