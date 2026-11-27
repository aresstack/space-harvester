package com.aresstack.askai.research.search.dataforseo;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchHit;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.api.WebSearchException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public final class DataForSeoSearchResponseMapper {

    private static final int SUCCESS_STATUS_CODE = 20000;

    public WebSearchResult map(
            String responseBody,
            SearchEngine searchEngine) {

        JsonObject root = JsonParser.parseString(responseBody)
                .getAsJsonObject();
        validateStatus(root, "DataForSEO response");

        JsonObject task = extractTask(root);
        validateStatus(task, "DataForSEO task");

        JsonObject result = extractFirstObject(task, "result");
        JsonArray items = result == null
                ? null
                : array(result, "items");

        List<WebSearchHit> hits =
                new ArrayList<WebSearchHit>();
        if (items != null) {
            int fallbackRank = 1;
            for (JsonElement element : items) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                if (!"organic".equals(text(item, "type"))) {
                    continue;
                }
                String title = text(item, "title");
                String url = text(item, "url");
                if (isBlank(title) || isBlank(url)) {
                    continue;
                }
                int rank = integer(
                        item,
                        "rank_group",
                        integer(item, "rank_absolute", fallbackRank));
                fallbackRank++;
                hits.add(new WebSearchHit(
                        SearchProviderId.DATA_FOR_SEO,
                        searchEngine,
                        rank,
                        title,
                        url,
                        firstText(
                                text(item, "description"),
                                text(item, "extended_snippet"))));
            }
        }

        return new WebSearchResult(
                SearchProviderId.DATA_FOR_SEO,
                searchEngine,
                hits,
                responseBody);
    }

    private JsonObject extractTask(JsonObject root) {
        JsonObject task = extractFirstObject(root, "tasks");
        return task == null ? root : task;
    }

    private JsonObject extractFirstObject(
            JsonObject object,
            String name) {

        JsonArray values = array(object, name);
        if (values == null || values.size() == 0
                || !values.get(0).isJsonObject()) {
            return null;
        }
        return values.get(0).getAsJsonObject();
    }

    private void validateStatus(
            JsonObject object,
            String objectName) {

        if (object == null || !object.has("status_code")) {
            return;
        }
        int status = object.get("status_code").getAsInt();
        if (status == SUCCESS_STATUS_CODE) {
            return;
        }
        throw new WebSearchException(
                objectName
                        + " failed with status "
                        + status
                        + ": "
                        + firstText(
                                text(object, "status_message"),
                                "Unknown DataForSEO error"));
    }

    private JsonArray array(JsonObject object, String name) {
        if (object == null || !object.has(name)
                || !object.get(name).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(name);
    }

    private String text(JsonObject object, String name) {
        if (object == null || !object.has(name)
                || object.get(name).isJsonNull()) {
            return null;
        }
        return object.get(name).getAsString();
    }

    private int integer(
            JsonObject object,
            String name,
            int defaultValue) {

        if (object == null || !object.has(name)
                || object.get(name).isJsonNull()) {
            return defaultValue;
        }
        return object.get(name).getAsInt();
    }

    private String firstText(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
