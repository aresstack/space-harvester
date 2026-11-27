package com.aresstack.askai.research.search.dataforseo;

public final class DataForSeoStopCrawlTarget {

    private DataForSeoTargetMatchType matchType =
            DataForSeoTargetMatchType.DOMAIN;
    private String matchValue;

    public DataForSeoStopCrawlTarget() {
    }

    public DataForSeoTargetMatchType getMatchType() {
        return matchType;
    }

    public void setMatchType(
            DataForSeoTargetMatchType matchType) {

        this.matchType = matchType;
    }

    public String getMatchValue() {
        return matchValue;
    }

    public void setMatchValue(String matchValue) {
        this.matchValue = matchValue;
    }
}
