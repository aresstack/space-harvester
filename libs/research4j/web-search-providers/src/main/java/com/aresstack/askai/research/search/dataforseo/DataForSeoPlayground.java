package com.aresstack.askai.research.search.dataforseo;

import com.aresstack.askai.research.search.api.WebSearchHit;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.application.SearchProviderFactory;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.List;

/**
 * The DataForSEO Playground BACKEND: one throwaway search against the SAME productive adapter the research
 * agent uses (never a second HTTP implementation). It runs from the current, UNSAVED draft configuration,
 * so the user can try a depth or credential before saving. The auth-free effective request preview is
 * built from the real request mapper — the Authorization header / secret is NEVER exposed.
 */
public final class DataForSeoPlayground {

    /** What the playground shows: the endpoint + auth-free request, response metadata and organic hits. */
    public static final class Result {
        private final String endpoint;
        private final String requestPreview;
        private final String rawResponse;
        private final Integer statusCode;
        private final String statusMessage;
        private final Double timeSeconds;
        private final Double cost;
        private final List<WebSearchHit> organicHits;

        Result(String endpoint, String requestPreview, String rawResponse, Integer statusCode,
               String statusMessage, Double timeSeconds, Double cost, List<WebSearchHit> organicHits) {
            this.endpoint = endpoint;
            this.requestPreview = requestPreview;
            this.rawResponse = rawResponse;
            this.statusCode = statusCode;
            this.statusMessage = statusMessage;
            this.timeSeconds = timeSeconds;
            this.cost = cost;
            this.organicHits = organicHits == null ? Collections.<WebSearchHit>emptyList() : organicHits;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public String getRequestPreview() {
            return requestPreview;
        }

        public String getRawResponse() {
            return rawResponse;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public String getStatusMessage() {
            return statusMessage;
        }

        public Double getTimeSeconds() {
            return timeSeconds;
        }

        public Double getCost() {
            return cost;
        }

        public List<WebSearchHit> getOrganicHits() {
            return organicHits;
        }
    }

    private final SearchProviderFactory providerFactory;
    private final Gson gson;

    public DataForSeoPlayground(SearchProviderFactory providerFactory, Gson gson) {
        this.providerFactory = providerFactory;
        this.gson = gson;
    }

    /** The endpoint the run will hit — shown in the UI before running. */
    public String endpoint(DataForSeoSearchConfiguration configuration) {
        return new DataForSeoRequestMapper(gson).createEndpoint(configuration);
    }

    /** The effective, AUTH-FREE request body for {@code term} — the mapper never includes credentials. */
    public String requestPreview(DataForSeoSearchConfiguration configuration, String term) {
        String body = new DataForSeoRequestMapper(gson).createBody(configuration, requestFor(term));
        // Pretty-print for the read-only preview; malformed body degrades to the raw string.
        try {
            return gson.newBuilder().setPrettyPrinting().create()
                    .toJson(JsonParser.parseString(body));
        } catch (RuntimeException notJson) {
            return body;
        }
    }

    /**
     * Run ONE search from the (unsaved) configuration. BLOCKS the calling thread — the caller runs it off
     * the EDT and marshals the result back. The configuration must already carry the credentials to use
     * (the draft applies a freshly typed password before calling this).
     */
    public Result run(DataForSeoSearchConfiguration configuration, String term) throws Exception {
        String endpoint = endpoint(configuration);
        String preview = requestPreview(configuration, term);
        com.aresstack.askai.research.search.api.WebSearchProvider provider =
                providerFactory.createDataForSeo(configuration);
        try {
            WebSearchResult result = provider.search(requestFor(term)).get();
            String raw = result.getRawResponse();
            return new Result(endpoint, preview, raw, statusOf(raw), messageOf(raw), timeOf(raw),
                    costOf(raw), result.getHits());
        } finally {
            provider.close();
        }
    }

    private static WebSearchRequest requestFor(String term) {
        return WebSearchRequest.builder(term == null ? "" : term.trim()).maximumResults(10).build();
    }

    // ------------------------------------------------------------------ raw-envelope metadata (best effort)

    private Integer statusOf(String raw) {
        JsonElement value = firstTask(raw, "status_code");
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : topInt(raw, "status_code");
    }

    private String messageOf(String raw) {
        JsonElement value = firstTask(raw, "status_message");
        if (value != null && value.isJsonPrimitive()) {
            return value.getAsString();
        }
        JsonElement top = topField(raw, "status_message");
        return top != null && top.isJsonPrimitive() ? top.getAsString() : null;
    }

    private Double timeOf(String raw) {
        JsonElement value = firstTask(raw, "time");
        String text = value != null && value.isJsonPrimitive() ? value.getAsString() : null;
        if (text == null) {
            return null;
        }
        // DataForSEO reports e.g. "3.2883 sec." — take the leading number.
        try {
            return Double.parseDouble(text.trim().split("\\s+")[0]);
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    private Double costOf(String raw) {
        JsonElement value = firstTask(raw, "cost");
        if (value != null && value.isJsonPrimitive()) {
            return value.getAsDouble();
        }
        JsonElement top = topField(raw, "cost");
        return top != null && top.isJsonPrimitive() ? top.getAsDouble() : null;
    }

    private JsonElement firstTask(String raw, String field) {
        JsonObject root = parse(raw);
        if (root == null || !root.has("tasks") || !root.get("tasks").isJsonArray()
                || root.getAsJsonArray("tasks").size() == 0) {
            return null;
        }
        JsonElement first = root.getAsJsonArray("tasks").get(0);
        return first.isJsonObject() && first.getAsJsonObject().has(field)
                ? first.getAsJsonObject().get(field) : null;
    }

    private JsonElement topField(String raw, String field) {
        JsonObject root = parse(raw);
        return root != null && root.has(field) ? root.get(field) : null;
    }

    private Integer topInt(String raw, String field) {
        JsonElement value = topField(raw, field);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : null;
    }

    private JsonObject parse(String raw) {
        try {
            JsonElement root = JsonParser.parseString(raw == null ? "" : raw);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }
}
