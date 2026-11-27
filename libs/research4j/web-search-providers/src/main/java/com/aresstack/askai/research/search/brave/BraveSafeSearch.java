package com.aresstack.askai.research.search.brave;

import com.google.gson.annotations.SerializedName;

public enum BraveSafeSearch {
    @SerializedName("off")
    OFF("off"),

    @SerializedName("moderate")
    MODERATE("moderate"),

    @SerializedName("strict")
    STRICT("strict");

    private final String apiValue;

    BraveSafeSearch(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
