package com.aresstack.spaceharvester.application.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strenges Lesen der Antwort des KI-Analysators (Fahrplan Paket A2): je Eingabezeile ein JSON-Objekt
 * {@code {"satz": n, "aussagen": [...]}}. Rechnet nur, ruft kein Modell.
 *
 * <ul>
 *     <li>Eine Zeile gilt nur, wenn sie ein JSON-Objekt mit einer Satznummer aus dem Aufruf und einer Liste
 *     {@code aussagen} ist; sonst wird sie verworfen und gezählt (Codeblock-Zäune und Leerzeilen zählen nicht).</li>
 *     <li>Eine Aussage gilt nur mit einem Verb; eine ohne Verb oder mit „-“ wird übergangen.</li>
 *     <li>Ein Satz ohne Antwortzeile oder mit leerer Liste hat keine eindeutige Aussage: ein Ergebnis, kein Fehler.</li>
 * </ul>
 */
final class ModelAnswer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Längster Wortlaut, der als Verb oder Beteiligter übernommen wird. */
    static final int MAX_VALUE_CHARS = 200;

    private ModelAnswer() {
    }

    /**
     * Eine Aussage, wie das Modell sie nennt.
     *
     * @param verb        Grundform
     * @param actors      Handelnde im Wortlaut des Modells
     * @param affected    Betroffenes
     * @param recipients  Empfänger
     * @param negation    Verneinung
     * @param modality    Verbindlichkeit
     * @param temporality Zeitbezug
     */
    record Item(String verb, List<String> actors, List<String> affected, List<String> recipients,
                ParsedStatements.Negation negation, ParsedStatements.Modality modality,
                ParsedStatements.Temporality temporality) {
    }

    /**
     * @param items    je Satznummer (ab 1) die gelesenen Aussagen; fehlt eine Nummer, kam keine Zeile
     * @param rejected verworfene Antwortzeilen
     */
    record Parsed(Map<Integer, List<Item>> items, int rejected) {
    }

    static Parsed parse(int lines, String response) {
        Map<Integer, List<Item>> items = new LinkedHashMap<>();
        int rejected = 0;
        for (String raw : (response == null ? "" : response).split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("```")) {
                continue;
            }
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (JacksonException e) {
                rejected++;
                continue;
            }
            JsonNode number = node == null ? null : node.get("satz");
            JsonNode statements = node == null ? null : node.get("aussagen");
            if (number == null || !number.isIntegralNumber() || statements == null || !statements.isArray()
                    || number.asInt() < 1 || number.asInt() > lines) {
                rejected++;
                continue;
            }
            List<Item> found = items.computeIfAbsent(number.asInt(), n -> new ArrayList<>());
            for (JsonNode statement : statements) {
                Item item = item(statement);
                if (item != null) {
                    found.add(item);
                }
            }
        }
        return new Parsed(items, rejected);
    }

    private static Item item(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String verb = text(node.get("verb"));
        if (verb == null) {
            return null;
        }
        return new Item(verb, values(node.get("handelnder")), values(node.get("betroffenes")),
                values(node.get("empfaenger")), negation(node.get("verneint")), modality(text(node.get(
                "verbindlichkeit"))), temporality(text(node.get("zeitbezug"))));
    }

    /** Ein Wert ohne „-“, leer oder zu lang; sonst {@code null}. */
    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() || "-".equals(value) || value.length() > MAX_VALUE_CHARS ? null : value;
    }

    /** Ein Beteiligter oder mehrere, als Liste oder mit Komma getrennt. */
    private static List<String> values(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(element -> {
                String value = text(element);
                if (value != null) {
                    values.add(value);
                }
            });
            return values;
        }
        String value = text(node);
        if (value != null) {
            for (String part : value.split(",")) {
                String strip = part.strip();
                if (!strip.isEmpty() && !"-".equals(strip)) {
                    values.add(strip);
                }
            }
        }
        return values;
    }

    private static ParsedStatements.Negation negation(JsonNode node) {
        if (node == null || node.isNull()) {
            return ParsedStatements.Negation.UNKNOWN;
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? ParsedStatements.Negation.NEGATED : ParsedStatements.Negation.AFFIRMED;
        }
        String value = node.asString().strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "ja" -> ParsedStatements.Negation.NEGATED;
            case "false", "nein" -> ParsedStatements.Negation.AFFIRMED;
            default -> ParsedStatements.Negation.UNKNOWN;
        };
    }

    private static ParsedStatements.Modality modality(String value) {
        if (value == null) {
            return ParsedStatements.Modality.NONE;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "MUSS" -> ParsedStatements.Modality.MUSS;
            case "SOLL" -> ParsedStatements.Modality.SOLL;
            case "KANN" -> ParsedStatements.Modality.KANN;
            case "DARF" -> ParsedStatements.Modality.DARF;
            default -> ParsedStatements.Modality.UNKNOWN;
        };
    }

    private static ParsedStatements.Temporality temporality(String value) {
        if (value == null) {
            return ParsedStatements.Temporality.UNKNOWN;
        }
        return switch (value.toUpperCase(Locale.ROOT).replace("Ü", "UE")) {
            case "GILT" -> ParsedStatements.Temporality.GILT;
            case "GEPLANT" -> ParsedStatements.Temporality.GEPLANT;
            case "FRUEHER" -> ParsedStatements.Temporality.FRUEHER;
            case "VERWORFEN" -> ParsedStatements.Temporality.VERWORFEN;
            default -> ParsedStatements.Temporality.UNKNOWN;
        };
    }
}
