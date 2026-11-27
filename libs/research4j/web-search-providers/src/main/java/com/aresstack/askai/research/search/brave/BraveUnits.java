package com.aresstack.askai.research.search.brave;

import com.google.gson.annotations.SerializedName;

public enum BraveUnits {
    @SerializedName("metric")
    METRIC("metric"),

    @SerializedName("imperial")
    IMPERIAL("imperial");

    private final String apiValue;

    BraveUnits(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
