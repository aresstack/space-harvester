package com.aresstack.askai.research.search.dataforseo;

public enum DataForSeoTargetSearchMode {
    ANY("any"),
    ALL("all");

    private final String apiValue;

    DataForSeoTargetSearchMode(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
