package com.aresstack.askai.research.search.brave;

public final class BraveFreshnessConfiguration {

    private BraveFreshnessMode mode =
            BraveFreshnessMode.NONE;
    private String startDate;
    private String endDate;

    public BraveFreshnessConfiguration() {
    }

    public BraveFreshnessMode getMode() {
        return mode;
    }

    public void setMode(BraveFreshnessMode mode) {
        this.mode = mode;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public String toApiValue() {
        if (mode == null || mode == BraveFreshnessMode.NONE) {
            return null;
        }

        switch (mode) {
            case PAST_DAY:
                return "pd";
            case PAST_WEEK:
                return "pw";
            case PAST_MONTH:
                return "pm";
            case PAST_YEAR:
                return "py";
            case CUSTOM_RANGE:
                return requireDate(startDate, "startDate")
                        + "to"
                        + requireDate(endDate, "endDate");
            default:
                throw new IllegalStateException(
                        "Unsupported freshness mode: " + mode);
        }
    }

    private String requireDate(
            String value,
            String propertyName) {

        if (value == null
                || !value.matches("\\d{4}-\\d{2}-\\d{2}")) {

            throw new IllegalArgumentException(
                    propertyName
                            + " must use YYYY-MM-DD");
        }

        return value;
    }
}
