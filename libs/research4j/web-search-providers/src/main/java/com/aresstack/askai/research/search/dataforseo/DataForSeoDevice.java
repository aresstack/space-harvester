package com.aresstack.askai.research.search.dataforseo;

public enum DataForSeoDevice {
    DESKTOP("desktop"),
    MOBILE("mobile");

    private final String apiValue;

    DataForSeoDevice(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
