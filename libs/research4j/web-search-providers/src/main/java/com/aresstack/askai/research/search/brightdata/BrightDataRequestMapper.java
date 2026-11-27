package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public final class BrightDataRequestMapper {

    private final Gson gson;
    private final BrightDataTargetUrlFactory targetUrlFactory;

    public BrightDataRequestMapper(
            Gson gson,
            BrightDataTargetUrlFactory targetUrlFactory) {

        if (gson == null) {
            throw new IllegalArgumentException(
                    "gson must not be null");
        }
        if (targetUrlFactory == null) {
            throw new IllegalArgumentException(
                    "targetUrlFactory must not be null");
        }
        this.gson = gson;
        this.targetUrlFactory = targetUrlFactory;
    }

    public String createSynchronousBody(
            BrightDataSearchConfiguration configuration,
            WebSearchRequest request) {

        JsonObject body = new JsonObject();
        body.addProperty("zone", configuration.getZone());
        body.addProperty(
                "url",
                targetUrlFactory.create(configuration, request));
        body.addProperty(
                "format",
                configuration.getResponseFormat().getApiValue());
        body.addProperty(
                "method",
                configuration.getRequestMethod().name());
        addText(body, "country", configuration.getCountry());

        BrightDataDataFormat dataFormat =
                configuration.getDataFormat();
        if (dataFormat != null && dataFormat.getApiValue() != null) {
            body.addProperty("data_format", dataFormat.getApiValue());
        }

        return gson.toJson(body);
    }

    public String createAsynchronousBody(
            BrightDataSearchConfiguration configuration,
            WebSearchRequest request) {

        JsonObject query = new JsonObject();
        query.addProperty("q", request.getQuery());

        String country = firstText(
                request.getCountryCode(),
                configuration.getCountry());
        String language = firstText(
                request.getLanguageCode(),
                configuration.getLanguage());

        query.addProperty("gl", lower(country));
        query.addProperty("hl", language);
        query.addProperty(
                "start",
                request.getOffset() > 0
                        ? request.getOffset()
                        : configuration.getStartOffset());
        query.addProperty(
                "num",
                Math.min(
                        request.getMaximumResults(),
                        configuration.getResultsPerPage()));

        if (configuration.getAdditionalSearchParameters() != null) {
            for (java.util.Map.Entry<String, String> entry
                    : configuration.getAdditionalSearchParameters()
                    .entrySet()) {
                if (entry.getKey() != null
                        && entry.getValue() != null) {
                    query.addProperty(entry.getKey(), entry.getValue());
                }
            }
        }

        JsonObject body = new JsonObject();
        body.add("query", query);
        body.addProperty("country", country);
        body.addProperty("brd_json", "json");
        return gson.toJson(body);
    }

    private void addText(
            JsonObject object,
            String name,
            String value) {

        if (value != null && !value.trim().isEmpty()) {
            object.addProperty(name, value);
        }
    }

    private String firstText(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        return second;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }
}
