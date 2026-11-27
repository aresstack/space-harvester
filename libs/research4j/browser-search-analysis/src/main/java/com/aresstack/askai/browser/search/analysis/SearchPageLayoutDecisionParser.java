package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionDecision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps a raw model response into a {@link SearchPageLayoutResolutionDecision}, enforcing the small
 * stable schema strictly: a JSON object with a string {@code snapshotId}, string arrays for the
 * container-id fields and a numeric {@code confidence}. Anything malformed — bad JSON, wrong field
 * types, a missing required field — throws {@link MiniJson.JsonParseException}, which the resolver
 * turns into a typed, retryable attempt failure. It never guesses a value.
 */
final class SearchPageLayoutDecisionParser {

    SearchPageLayoutResolutionDecision parse(String rawText) {
        Object root = MiniJson.parse(rawText);
        if (!(root instanceof Map)) {
            throw new MiniJson.JsonParseException("response root is not a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) root;

        String analysisId = requireString(object, "analysisId");
        String snapshotId = requireString(object, "snapshotId");
        List<String> organic = requireStringArray(object, "organicResultContainerIds");
        List<String> blocks = optionalStringArray(object, "resultBlockContainerIds");
        List<String> excluded = optionalStringArray(object, "excludedContainerIds");
        double confidence = requireNumber(object, "confidence");
        String explanation = optionalString(object, "explanation");

        return new SearchPageLayoutResolutionDecision(analysisId, snapshotId, organic, blocks, excluded,
                confidence, explanation);
    }

    private static String requireString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String)) {
            throw new MiniJson.JsonParseException(
                    "field '" + field + "' must be a string");
        }
        return (String) value;
    }

    private static String optionalString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new MiniJson.JsonParseException("field '" + field + "' must be a string");
        }
        return (String) value;
    }

    private static double requireNumber(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof Double)) {
            throw new MiniJson.JsonParseException("field '" + field + "' must be a number");
        }
        return (Double) value;
    }

    private static List<String> requireStringArray(Map<String, Object> object, String field) {
        if (!object.containsKey(field)) {
            throw new MiniJson.JsonParseException("required field '" + field + "' is missing");
        }
        return optionalStringArray(object, field);
    }

    private static List<String> optionalStringArray(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return new ArrayList<String>();
        }
        if (!(value instanceof List)) {
            throw new MiniJson.JsonParseException("field '" + field + "' must be an array");
        }
        List<?> raw = (List<?>) value;
        List<String> result = new ArrayList<String>(raw.size());
        for (Object element : raw) {
            if (!(element instanceof String)) {
                throw new MiniJson.JsonParseException(
                        "field '" + field + "' must contain only strings");
            }
            result.add((String) element);
        }
        return result;
    }
}
