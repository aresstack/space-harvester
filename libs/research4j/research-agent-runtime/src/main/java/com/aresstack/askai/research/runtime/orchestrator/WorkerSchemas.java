package com.aresstack.askai.research.runtime.orchestrator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;

/** Small JSON-schema builders for the worker roles' per-request schemas (Gson, ADR 0001, 3.1). */
final class WorkerSchemas {

    private WorkerSchemas() {
    }

    static JsonObject string() {
        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        return type;
    }

    static JsonObject enumOf(Collection<String> values) {
        JsonObject type = string();
        JsonArray allowed = new JsonArray();
        for (String value : values) {
            allowed.add(value);
        }
        type.add("enum", allowed);
        return type;
    }

    static JsonObject arrayOf(JsonObject items) {
        JsonObject type = new JsonObject();
        type.addProperty("type", "array");
        type.add("items", items);
        return type;
    }

    static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
