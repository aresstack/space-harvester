package com.aresstack.enterpriseai.model.kipitz;

import com.aresstack.enterpriseai.astu.modelcatalog.ModelDescriptor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Liest die Antwort von {@code GET /models}: {@code {"object":"list","data":[...]}} oder ein Array auf oberster
 * Ebene. Je Eintrag zählen {@code id}, {@code name}, {@code context_length}, {@code architecture.input_modalities},
 * {@code architecture.output_modalities} (fehlen sie, die Kurzform {@code architecture.modality} wie
 * {@code text+image->text}), {@code tool_calling}, {@code reasoning} und {@code capabilities}. Einträge ohne
 * Kennung fallen weg.
 */
final class KipitzModelListParser {

    private KipitzModelListParser() {
    }

    static List<ModelDescriptor> parse(String body, String catalogId, String catalogName) {
        JsonElement root = JsonParser.parseString(body == null ? "" : body);
        JsonArray data;
        if (root.isJsonArray()) {
            data = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("data")
                && root.getAsJsonObject().get("data").isJsonArray()) {
            data = root.getAsJsonObject().getAsJsonArray("data");
        } else {
            throw new IllegalStateException("no data array");
        }
        List<ModelDescriptor> models = new ArrayList<ModelDescriptor>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = string(entry, "id");
            if (id == null || id.trim().isEmpty()) {
                continue;
            }
            List<String> input = new ArrayList<String>();
            List<String> output = new ArrayList<String>();
            JsonElement architecture = entry.get("architecture");
            if (architecture != null && architecture.isJsonObject()) {
                JsonObject arch = architecture.getAsJsonObject();
                input = strings(arch, "input_modalities");
                output = strings(arch, "output_modalities");
                String modality = string(arch, "modality");
                if (input.isEmpty() && output.isEmpty() && modality != null && modality.contains("->")) {
                    int arrow = modality.indexOf("->");
                    input = split(modality.substring(0, arrow));
                    output = split(modality.substring(arrow + 2));
                }
            }
            models.add(ModelDescriptor.builder(catalogId, id)
                    .catalogName(catalogName)
                    .displayName(string(entry, "name"))
                    .capabilities(strings(entry, "capabilities"))
                    .inputModalities(input)
                    .outputModalities(output)
                    .toolCalling(bool(entry, "tool_calling"))
                    .reasoning(bool(entry, "reasoning"))
                    .contextLength(integer(entry, "context_length"))
                    .build());
        }
        return models;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    private static int integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        try {
            long number = value.getAsLong();
            return number > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, number);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> strings(JsonObject object, String key) {
        List<String> values = new ArrayList<String>();
        JsonElement value = object.get(key);
        if (value != null && value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    values.add(item.getAsString());
                }
            }
        }
        return values;
    }

    private static List<String> split(String modalities) {
        List<String> values = new ArrayList<String>();
        for (String part : modalities.split("\\+")) {
            if (!part.trim().isEmpty()) {
                values.add(part.trim());
            }
        }
        return values;
    }
}
