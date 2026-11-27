package com.aresstack.askai.research.search.api;

public final class WebSearchRequest {

    private final String query;
    private final String countryCode;
    private final String languageCode;
    private final int maximumResults;
    private final int offset;

    private WebSearchRequest(Builder builder) {
        this.query = requireText(builder.query, "query");
        this.countryCode = normalizeOptional(builder.countryCode);
        this.languageCode = normalizeOptional(builder.languageCode);
        this.maximumResults = requireRange(
                builder.maximumResults,
                1,
                200,
                "maximumResults");
        this.offset = requireNonNegative(builder.offset, "offset");
    }

    public static Builder builder(String query) {
        return new Builder(query);
    }

    public String getQuery() {
        return query;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public int getMaximumResults() {
        return maximumResults;
    }

    public int getOffset() {
        return offset;
    }

    private static String requireText(
            String value,
            String propertyName) {

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    propertyName + " must not be empty");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static int requireRange(
            int value,
            int minimum,
            int maximum,
            String propertyName) {

        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    propertyName + " must be between "
                            + minimum + " and " + maximum);
        }
        return value;
    }

    private static int requireNonNegative(
            int value,
            String propertyName) {

        if (value < 0) {
            throw new IllegalArgumentException(
                    propertyName + " must not be negative");
        }
        return value;
    }

    public static final class Builder {

        private final String query;
        private String countryCode;
        private String languageCode;
        private int maximumResults = 10;
        private int offset;

        private Builder(String query) {
            this.query = query;
        }

        public Builder countryCode(String countryCode) {
            this.countryCode = countryCode;
            return this;
        }

        public Builder languageCode(String languageCode) {
            this.languageCode = languageCode;
            return this;
        }

        public Builder maximumResults(int maximumResults) {
            this.maximumResults = maximumResults;
            return this;
        }

        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        public WebSearchRequest build() {
            return new WebSearchRequest(this);
        }
    }
}
