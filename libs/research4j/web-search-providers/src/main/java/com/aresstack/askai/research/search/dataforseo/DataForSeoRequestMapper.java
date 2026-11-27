package com.aresstack.askai.research.search.dataforseo;

import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public final class DataForSeoRequestMapper {

    private final Gson gson;

    public DataForSeoRequestMapper(Gson gson) {
        if (gson == null) {
            throw new IllegalArgumentException(
                    "gson must not be null");
        }
        this.gson = gson;
    }

    public String createBody(
            DataForSeoSearchConfiguration configuration,
            WebSearchRequest request) {

        JsonObject task = new JsonObject();
        task.addProperty("keyword", request.getQuery());
        addNumber(task, "location_code",
                configuration.getLocationCode());
        addText(task, "location_name",
                configuration.getLocationName());
        addText(task, "location_coordinate",
                configuration.getLocationCoordinate());
        addText(task, "language_code", firstText(
                request.getLanguageCode(),
                configuration.getLanguageCode()));
        addText(task, "language_name",
                configuration.getLanguageName());
        task.addProperty(
                "depth",
                Math.min(
                        request.getMaximumResults(),
                        configuration.getDepth()));
        task.addProperty(
                "device",
                configuration.getDevice().getApiValue());
        task.addProperty(
                "os",
                configuration.getOperatingSystem().getApiValue());
        task.addProperty(
                "load_async_ai_overview",
                configuration.isLoadAsyncAiOverview());
        addText(task, "tag", configuration.getTag());
        addStopTargets(task, configuration.getStopCrawlOnMatch());
        addNumber(task, "max_crawl_pages",
                configuration.getMaxCrawlPages());
        addText(task, "search_param",
                configuration.getSearchParam());
        addStringArray(task, "remove_from_url",
                configuration.getRemoveFromUrl());
        addNumber(task, "people_also_ask_click_depth",
                configuration.getPeopleAlsoAskClickDepth());
        task.addProperty(
                "group_organic_results",
                configuration.isGroupOrganicResults());
        task.addProperty(
                "calculate_rectangles",
                configuration.isCalculateRectangles());
        addNumber(task, "browser_screen_width",
                configuration.getBrowserScreenWidth());
        addNumber(task, "browser_screen_height",
                configuration.getBrowserScreenHeight());
        addNumber(task, "browser_screen_resolution_ratio",
                configuration.getBrowserScreenResolutionRatio());
        addText(task, "url", configuration.getDirectUrl());
        addText(task, "se_domain", configuration.getSeDomain());
        addText(task, "target", configuration.getTarget());
        if (configuration.getStopCrawlOnMatch() != null
                && !configuration.getStopCrawlOnMatch().isEmpty()) {
            addEnum(task, "target_search_mode",
                    configuration.getTargetSearchMode());
        }
        addElementTypes(task, "find_targets_in",
                configuration.getFindTargetsIn());
        addElementTypes(task, "ignore_targets_in",
                configuration.getIgnoreTargetsIn());

        JsonArray tasks = new JsonArray();
        tasks.add(task);
        return gson.toJson(tasks);
    }

    public String createEndpoint(
            DataForSeoSearchConfiguration configuration) {

        String base = configuration.getEndpointBase();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base
                + "/v3/serp/"
                + configuration.getSearchEngine().getApiValue()
                + "/organic/live/"
                + configuration.getResultFormat().getApiValue();
    }

    private void addStopTargets(
            JsonObject task,
            List<DataForSeoStopCrawlTarget> targets) {

        if (targets == null || targets.isEmpty()) {
            return;
        }

        JsonArray values = new JsonArray();
        for (DataForSeoStopCrawlTarget target : targets) {
            JsonObject value = new JsonObject();
            value.addProperty(
                    "match_type",
                    target.getMatchType().getApiValue());
            value.addProperty(
                    "match_value",
                    target.getMatchValue());
            values.add(value);
        }
        task.add("stop_crawl_on_match", values);
    }

    private void addElementTypes(
            JsonObject task,
            String name,
            List<DataForSeoSerpElementType> values) {

        if (values == null || values.isEmpty()) {
            return;
        }

        JsonArray array = new JsonArray();
        for (DataForSeoSerpElementType value : values) {
            array.add(value.getApiValue());
        }
        task.add(name, array);
    }

    private void addStringArray(
            JsonObject task,
            String name,
            List<String> values) {

        if (values == null || values.isEmpty()) {
            return;
        }

        JsonArray array = new JsonArray();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                array.add(value);
            }
        }
        if (array.size() > 0) {
            task.add(name, array);
        }
    }

    private void addEnum(
            JsonObject object,
            String name,
            DataForSeoTargetSearchMode value) {

        if (value != null) {
            object.addProperty(name, value.getApiValue());
        }
    }

    private void addText(
            JsonObject object,
            String name,
            String value) {

        if (value != null && !value.trim().isEmpty()) {
            object.addProperty(name, value);
        }
    }

    private void addNumber(
            JsonObject object,
            String name,
            Number value) {

        if (value != null) {
            object.addProperty(name, value);
        }
    }

    private String firstText(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        return second;
    }
}
