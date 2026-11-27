package com.aresstack.askai.research.search.brightdata;

public enum BrightDataResponseFormat {
    RAW("raw"),
    JSON("json");

    private final String apiValue;

    BrightDataResponseFormat(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
