package com.aresstack.spaceharvester.application.metadata;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceRestResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON-Abfragen an die Quelle für die Exporte dieses Pakets: eine Antwort als Baum, oder alle Seiten einer
 * Confluence-Liste ({@code results}, {@code _links.next}) hintereinander. Jede Antwort kommt unverändert in den
 * Export; dieses Hilfsmittel liest nur, was es zum Blättern braucht.
 */
final class RestJson {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** Höchstens so viele Listenseiten je Abfrage; mehr deutet auf eine Endlosschleife der Quelle hin. */
    static final int MAX_LIST_PAGES = 200;
    /** So viele Einträge fordert eine Listenseite an; Confluence kappt je nach Endpunkt bei 100 bis 200. */
    static final int LIST_LIMIT = 100;

    private RestJson() {
    }

    /** Ergebnis einer Abfrage: der Baum oder der Grund des Scheiterns, nie beides. */
    record Result(JsonNode data, int requests, SourceAccessException.Reason reason, String message) {

        boolean ok() {
            return data != null;
        }

        static Result failed(SourceAccessException e, int requests) {
            return new Result(null, requests, e.reason(), e.getMessage());
        }
    }

    /** Eine Abfrage; eine Antwort, die kein JSON ist, zählt als {@code INVALID_RESPONSE}. */
    static Result get(HarvestSource source, String pathAndQuery) {
        try {
            return new Result(parse(source, pathAndQuery), 1, null, null);
        } catch (SourceAccessException e) {
            return Result.failed(e, 1);
        }
    }

    /**
     * Alle Listenseiten einer Abfrage ab {@code start=0}: die {@code results} aller Seiten als ein Array, dazu die
     * Gesamtzahl der Anfragen. Blättert, solange {@code _links.next} da ist oder die Seite voll war.
     */
    static Result list(HarvestSource source, String pathAndQuery) {
        ArrayNode all = JSON.createArrayNode();
        int requests = 0;
        int start = 0;
        boolean more = true;
        try {
            for (int page = 0; page < MAX_LIST_PAGES && more; page++) {
                String separator = pathAndQuery.contains("?") ? "&" : "?";
                JsonNode node = parse(source, pathAndQuery + separator + "start=" + start + "&limit=" + LIST_LIMIT);
                requests++;
                JsonNode results = node.get("results");
                if (results == null || !results.isArray()) {
                    // Kein Listenformat: die Antwort selbst ist das Ergebnis.
                    return new Result(requests == 1 ? node : all, requests, null, null);
                }
                results.forEach(all::add);
                int size = results.size();
                JsonNode links = node.get("_links");
                more = links != null && links.has("next") ? true : size >= LIST_LIMIT && size > 0;
                start += size;
            }
        } catch (SourceAccessException e) {
            return Result.failed(e, requests + 1);
        }
        if (more) {
            // Lieber ein klarer Fehler als eine stillschweigend gekürzte Liste mit „ok“.
            return new Result(null, requests, SourceAccessException.Reason.INVALID_RESPONSE,
                    "Liste nach " + MAX_LIST_PAGES + " Seiten zu je " + LIST_LIMIT + " Einträgen nicht zu Ende; "
                            + "die Quelle blättert endlos oder die Liste ist zu lang");
        }
        return new Result(all, requests, null, null);
    }

    private static JsonNode parse(HarvestSource source, String pathAndQuery) {
        SourceRestResponse response = source.restGet(pathAndQuery);
        if (!response.json()) {
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.INVALID_RESPONSE,
                    "keine JSON-Antwort (" + response.contentType() + ")", null);
        }
        try {
            return JSON.readTree(response.body());
        } catch (JacksonException e) {
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.INVALID_RESPONSE,
                    "JSON nicht lesbar", e);
        }
    }

    /** Ein Abschnitt des Exports: {@code status ok} mit Daten oder {@code status error} mit Grund. */
    static ObjectNode section(Result result) {
        ObjectNode section = JSON.createObjectNode();
        if (result.ok()) {
            section.put("status", "ok");
            section.put("requests", result.requests());
            section.set("data", result.data());
        } else {
            section.put("status", "error");
            section.put("requests", result.requests());
            section.put("kind", result.reason().name());
            section.put("message", Objects.requireNonNullElse(result.message(), ""));
        }
        return section;
    }

    /** Alle Zahlen unter Feldern dieses Namens, rekursiv; leer ohne ein solches Feld. */
    static List<Long> numbers(JsonNode node, String field) {
        List<Long> found = new ArrayList<>();
        collect(node, field, found);
        return found;
    }

    private static void collect(JsonNode node, String field, List<Long> found) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (entry.getKey().equals(field) && entry.getValue().isNumber()) {
                    found.add(entry.getValue().asLong());
                } else {
                    collect(entry.getValue(), field, found);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, field, found));
        }
    }
}
