package com.aresstack.askai.research.search.brave;

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

public final class BraveSearchResponseMapper {

    public WebSearchResult map(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody)
                .getAsJsonObject();

        List<WebSearchHit> hits =
                new ArrayList<WebSearchHit>();

        JsonObject web = object(root, "web");
        JsonArray results = array(web, "results");

        if (results != null) {
            int rank = 1;
            for (JsonElement element : results) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject item = element.getAsJsonObject();
                String title = text(item, "title");
                String url = text(item, "url");

                if (isBlank(title) || isBlank(url)) {
                    continue;
                }

                hits.add(new WebSearchHit(
                        SearchProviderId.BRAVE,
                        SearchEngine.BRAVE,
                        rank++,
                        title,
                        url,
                        firstText(
                                text(item, "description"),
                                joinExtraSnippets(item))));
            }
        }

        return new WebSearchResult(
                SearchProviderId.BRAVE,
                SearchEngine.BRAVE,
                hits,
                responseBody);
    }

    private String joinExtraSnippets(JsonObject item) {
        JsonArray snippets = array(item, "extra_snippets");
        if (snippets == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (JsonElement element : snippets) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(element.getAsString());
        }
        return result.toString();
    }

    private JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name)
                || !parent.get(name).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(name);
    }

    private JsonArray array(JsonObject parent, String name) {
        if (parent == null || !parent.has(name)
                || !parent.get(name).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(name);
    }

    private String text(JsonObject object, String name) {
        if (object == null || !object.has(name)
                || object.get(name).isJsonNull()) {
            return null;
        }
        return object.get(name).getAsString();
    }

    private String firstText(String first, String second) {
        return isBlank(first) ? (second == null ? "" : second) : first;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
