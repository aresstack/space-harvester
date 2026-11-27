package com.aresstack.askai.research.search.brave;

import com.google.gson.annotations.SerializedName;

public enum BraveResultType {
    @SerializedName("web")
    WEB("web"),

    @SerializedName("news")
    NEWS("news"),

    @SerializedName("videos")
    VIDEOS("videos"),

    @SerializedName("discussions")
    DISCUSSIONS("discussions"),

    @SerializedName("faq")
    FAQ("faq"),

    @SerializedName("infobox")
    INFOBOX("infobox"),

    @SerializedName("locations")
    LOCATIONS("locations"),

    @SerializedName("query")
    QUERY("query");

    private final String apiValue;

    BraveResultType(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
