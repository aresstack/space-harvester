package com.aresstack.askai.research.runtime.rerank;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The OpenAI-compatible {@code POST /rerank} wire, taken over from Space Harvester's {@code KipitzRerankAdapter}
 * (enterprise-ai-client, package {@code com.aresstack.enterpriseai.model.kipitz}): request {@code model},
 * {@code query}, {@code documents}, {@code top_n} = all; answer {@code results[]} (or {@code data[]}) with
 * {@code index} and {@code relevance_score} (or {@code score}). Every document needs exactly one finite score;
 * messages never name the key, the query or a document.
 */
final class OpenAiRerankWire {

    private OpenAiRerankWire() {
    }

    static String request(String modelId, String query, List<String> documents) {
        JsonObject request = new JsonObject();
        request.addProperty("model", modelId);
        request.addProperty("query", query);
        JsonArray array = new JsonArray();
        for (String document : documents) {
            array.add(document == null ? "" : document);
        }
        request.add("documents", array);
        request.addProperty("top_n", documents.size());
        return request.toString();
    }

    /** The rows in document order (index 0..count-1), as AskAI's selection policy reads them. */
    static RerankResponse parse(String modelId, String json, int count) throws RerankerClientException {
        JsonArray rows;
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonObject object = root.isJsonObject() ? root.getAsJsonObject() : null;
            JsonElement results = object == null ? null
                    : object.has("results") ? object.get("results") : object.get("data");
            if (results == null || !results.isJsonArray()) {
                throw invalid("reranker response has no 'results' array");
            }
            rows = results.getAsJsonArray();
        } catch (RerankerClientException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid("reranker response is unreadable (" + e.getClass().getSimpleName() + ")");
        }
        double[] scores = new double[count];
        java.util.Arrays.fill(scores, Double.NaN);
        for (JsonElement row : rows) {
            if (!row.isJsonObject()) {
                throw invalid("reranker result entry is not an object");
            }
            JsonObject item = row.getAsJsonObject();
            JsonElement index = item.get("index");
            JsonElement score = item.has("relevance_score") ? item.get("relevance_score") : item.get("score");
            if (index == null || score == null || !index.isJsonPrimitive() || !score.isJsonPrimitive()) {
                throw invalid("reranker result entry must carry 'index' and 'relevance_score'");
            }
            int at;
            double value;
            try {
                double rawIndex = index.getAsDouble();
                if (rawIndex != Math.rint(rawIndex)) {
                    throw invalid("reranker result index is not an integer");
                }
                at = (int) rawIndex;
                value = score.getAsDouble();
            } catch (RerankerClientException e) {
                throw e;
            } catch (RuntimeException e) {
                throw invalid("reranker result entry holds no number");
            }
            if (at < 0 || at >= count || !Double.isNaN(scores[at])) {
                throw invalid("reranker result index " + at + " is outside 0.." + (count - 1) + " or duplicated");
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw invalid("reranker score for index " + at + " is not finite");
            }
            scores[at] = value;
        }
        List<RerankScore> ordered = new ArrayList<RerankScore>(count);
        for (int i = 0; i < count; i++) {
            if (Double.isNaN(scores[i])) {
                throw invalid("reranker returned " + rows.size() + " of " + count + " requested document scores");
            }
            ordered.add(new RerankScore(i, scores[i]));
        }
        return new RerankResponse(modelId, ordered, 0L, 0L);
    }

    /** " (code)" from {@code {"error":{"code"|"type"}}} or a plain {@code error} token; never free text. */
    static String errorCode(String json) {
        try {
            JsonElement error = JsonParser.parseString(json).getAsJsonObject().get("error");
            JsonElement code = error != null && error.isJsonObject()
                    ? (error.getAsJsonObject().has("code") ? error.getAsJsonObject().get("code")
                    : error.getAsJsonObject().get("type")) : error;
            String value = code == null || !code.isJsonPrimitive() ? "" : code.getAsString();
            return value.matches("[A-Za-z0-9_.:-]{1,64}") ? " (" + value + ")" : "";
        } catch (RuntimeException notJson) {
            return "";
        }
    }

    private static RerankerClientException invalid(String message) {
        return new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE, message);
    }
}
