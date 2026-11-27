package com.aresstack.askai.research.search.dataforseo;

public enum DataForSeoResultFormat {
    ADVANCED("advanced"),
    REGULAR("regular"),
    HTML("html");

    private final String apiValue;

    DataForSeoResultFormat(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
