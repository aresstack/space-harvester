package com.aresstack.askai.research.search.brightdata;

public enum BrightDataDataFormat {
    NONE(null),
    MARKDOWN("markdown"),
    SCREENSHOT("screenshot");

    private final String apiValue;

    BrightDataDataFormat(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
