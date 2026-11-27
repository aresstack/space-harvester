package com.aresstack.askai.research.runtime.orchestrator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A worker role's structured answer is unusable; the message is the reason the worker sends back to the model
 * (ADR 0001, 4.2: code never interprets free text, a reader never repairs an answer silently).
 */
final class WorkerAnswerException extends Exception {

    WorkerAnswerException(String message) {
        super(message);
    }

    /** The answer as one JSON object, or the reason it is not one. */
    static JsonObject object(String answer) throws WorkerAnswerException {
        if (answer == null || answer.trim().isEmpty()) {
            throw new WorkerAnswerException("the answer was empty");
        }
        try {
            JsonElement root = JsonParser.parseString(answer.trim());
            if (!root.isJsonObject()) {
                throw new WorkerAnswerException("the answer must be one JSON object");
            }
            return root.getAsJsonObject();
        } catch (RuntimeException notJson) {
            throw new WorkerAnswerException("the answer is not valid JSON");
        }
    }

    /** A required non-empty string member. */
    static String text(JsonObject object, String name) throws WorkerAnswerException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().trim().isEmpty()) {
            throw new WorkerAnswerException("\"" + name + "\" must be a non-empty string");
        }
        return value.getAsString().trim();
    }

    /** An optional string member ("" when absent). */
    static String optionalText(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().trim() : "";
    }
}
