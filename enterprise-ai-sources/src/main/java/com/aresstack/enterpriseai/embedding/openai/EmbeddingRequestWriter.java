package com.aresstack.enterpriseai.embedding.openai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Baut den JSON-Body für {@code POST /embeddings}. Gesendet werden nur {@code model} und {@code input}.
 * Nicht gesendet, weil UNVERIFIED (Nachtrag, Abschnitt 17): {@code encoding_format}, {@code dimensions},
 * {@code user}. Float-Embeddings sind im OpenAI-Format der Default.
 */
final class EmbeddingRequestWriter {

    private EmbeddingRequestWriter() {
    }

    /** {@code {"model": "...", "input": "<text>"}} – die von der OpenAPI-Typdefinition gedeckte Form. */
    static String singleInput(String modelId, String text) {
        JsonObject request = new JsonObject();
        request.addProperty("model", modelId);
        request.addProperty("input", text);
        return request.toString();
    }

    /** UNVERIFIED: {@code {"model": "...", "input": ["a", "b"]}}. */
    static String arrayInput(String modelId, List<String> texts) {
        JsonObject request = new JsonObject();
        request.addProperty("model", modelId);
        JsonArray input = new JsonArray();
        for (String text : texts) {
            input.add(text);
        }
        request.add("input", input);
        return request.toString();
    }
}
