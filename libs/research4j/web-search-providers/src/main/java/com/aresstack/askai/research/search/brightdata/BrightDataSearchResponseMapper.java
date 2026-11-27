package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchHit;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public final class BrightDataSearchResponseMapper {

    public WebSearchResult map(
            String responseBody,
            SearchEngine searchEngine) {

        JsonObject root = JsonParser.parseString(responseBody)
                .getAsJsonObject();
        JsonArray organic = root.has("organic")
                && root.get("organic").isJsonArray()
                ? root.getAsJsonArray("organic")
                : null;

        List<WebSearchHit> hits =
                new ArrayList<WebSearchHit>();
        if (organic != null) {
            int fallbackRank = 1;
            for (JsonElement element : organic) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String title = text(item, "title");
                String url = firstText(
                        text(item, "link"),
                        text(item, "url"));
                if (isBlank(title) || isBlank(url)) {
                    continue;
                }
                hits.add(new WebSearchHit(
                        SearchProviderId.BRIGHT_DATA,
                        searchEngine,
                        integer(item, "rank", fallbackRank++),
                        title,
                        url,
                        firstText(
                                text(item, "description"),
                                text(item, "snippet"))));
            }
        }

        return new WebSearchResult(
                SearchProviderId.BRIGHT_DATA,
                searchEngine,
                hits,
                responseBody);
    }

    public String extractResponseId(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody)
                .getAsJsonObject();
        String responseId = text(root, "response_id");
        if (isBlank(responseId)) {
            throw new IllegalStateException(
                    "Bright Data async response contains no response_id");
        }
        return responseId;
    }

    private String text(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        return object.get(name).getAsString();
    }

    private int integer(
            JsonObject object,
            String name,
            int defaultValue) {

        if (!object.has(name) || object.get(name).isJsonNull()) {
            return defaultValue;
        }
        return object.get(name).getAsInt();
    }

    private String firstText(String first, String second) {
        return isBlank(first) ? (second == null ? "" : second) : first;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
