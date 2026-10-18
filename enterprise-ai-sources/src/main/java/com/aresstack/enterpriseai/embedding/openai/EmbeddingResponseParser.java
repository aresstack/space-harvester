package com.aresstack.enterpriseai.embedding.openai;

import com.aresstack.enterpriseai.embedding.api.EmbeddingException;
import com.aresstack.enterpriseai.embedding.api.EmbeddingFailureKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Liest {@code data[].embedding} aus einer {@code /embeddings}-Antwort im OpenAI-Format.
 *
 * <p>Streng: Jeder Eintrag braucht ein Float-Array (base64-Strings werden abgelehnt), die Anzahl muss der
 * Eingabeanzahl entsprechen. Tragen alle Einträge {@code index}, wird danach sortiert und Lücken/Doppelte werden
 * abgelehnt; fehlt {@code index} überall, gilt die Listenreihenfolge (wie MainframeMate es erprobt hat). Ein
 * Gemisch ist ungültig. {@code usage} wird ignoriert (beim Chat-Endpunkt real immer 0).
 * Dimension und Endlichkeit prüft danach {@link com.aresstack.enterpriseai.astu.embedding.EmbeddingVector}.
 */
final class EmbeddingResponseParser {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private EmbeddingResponseParser() {
    }

    static List<float[]> parse(String json, int expectedCount) {
        JsonObject root = parseObject(json);
        JsonElement dataElement = root.get("data");
        if (dataElement == null || !dataElement.isJsonArray()) {
            throw invalid("embedding response has no 'data' array");
        }
        JsonArray data = dataElement.getAsJsonArray();
        if (data.size() != expectedCount) {
            throw invalid("embedding count " + data.size() + " does not match input count " + expectedCount);
        }
        float[][] ordered = new float[expectedCount][];
        Boolean indexed = null;
        for (int position = 0; position < data.size(); position++) {
            JsonElement entryElement = data.get(position);
            if (!entryElement.isJsonObject()) {
                throw invalid("data[" + position + "] is not an object");
            }
            JsonObject entry = entryElement.getAsJsonObject();
            boolean hasIndex = entry.has("index") && !entry.get("index").isJsonNull();
            if (indexed == null) {
                indexed = hasIndex;
            } else if (indexed != hasIndex) {
                throw invalid("data entries mix explicit and missing 'index'");
            }
            int slot = hasIndex ? readIndex(entry, position) : position;
            if (slot < 0 || slot >= expectedCount) {
                throw invalid("data[" + position + "].index " + slot + " is out of range 0.." + (expectedCount - 1));
            }
            if (ordered[slot] != null) {
                throw invalid("data index " + slot + " occurs twice");
            }
            ordered[slot] = readVector(entry, position);
        }
        List<float[]> result = new ArrayList<float[]>(expectedCount);
        for (float[] vector : ordered) {
            result.add(vector);
        }
        return result;
    }

    /**
     * Maschinenlesbarer Fehlercode aus {@code {"error": {"code"|"type": ...}}} bzw. {@code {"error": "..."}}, sonst
     * {@code null}. Bewusst kein Freitext ({@code message}, {@code detail}): Validierungsfehler können Teile der
     * Eingabetexte zurückspiegeln, und die dürfen nicht in Exceptions oder Logs landen.
     */
    static String errorCode(String json) {
        try {
            JsonObject root = parseObject(json);
            JsonElement error = root.get("error");
            if (error != null && error.isJsonObject()) {
                String code = token(error.getAsJsonObject().get("code"));
                return code != null ? code : token(error.getAsJsonObject().get("type"));
            }
            return token(error);
        } catch (RuntimeException ignored) {
            return null; // kein JSON-Fehlerobjekt
        }
    }

    private static String token(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return ERROR_CODE.matcher(value).matches() ? value : null;
    }

    private static JsonObject parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw invalid("empty embedding response");
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw invalid("embedding response is not a JSON object");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE, "malformed embedding response", ex);
        }
    }

    private static int readIndex(JsonObject entry, int position) {
        JsonElement index = entry.get("index");
        if (!index.isJsonPrimitive() || !index.getAsJsonPrimitive().isNumber()) {
            throw invalid("data[" + position + "].index is not a number");
        }
        double value = index.getAsDouble();
        if (value != Math.rint(value)) {
            throw invalid("data[" + position + "].index is not an integer");
        }
        return (int) value;
    }

    private static float[] readVector(JsonObject entry, int position) {
        JsonElement embedding = entry.get("embedding");
        if (embedding == null || embedding.isJsonNull()) {
            throw invalid("data[" + position + "] has no 'embedding'");
        }
        if (!embedding.isJsonArray()) {
            throw invalid("data[" + position + "].embedding is not a float array (base64 is not supported)");
        }
        JsonArray array = embedding.getAsJsonArray();
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            JsonElement component = array.get(i);
            if (!component.isJsonPrimitive() || !component.getAsJsonPrimitive().isNumber()) {
                throw invalid("data[" + position + "].embedding[" + i + "] is not a number");
            }
            values[i] = component.getAsFloat();
        }
        return values;
    }

    private static EmbeddingException invalid(String message) {
        return new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE, message);
    }
}
