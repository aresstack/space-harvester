package com.aresstack.spaceharvester.application.presentation;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Die Zwischenergebnisse von „In PPTX umwandeln“ je Seite und Fassung des Videos, als ein Eintrag
 * {@value Enrichments#VIDEO_PRESENTATION} in der Anreicherungsablage: das korrigierte Transkript und der Vortragstext
 * je Folie, jeweils mit Modell und dem Hash ihrer Eingabe. Ein neuer Lauf fragt nur, was fehlt oder dessen Eingabe sich
 * geändert hat. Wie alle Modelltexte einer Seite fällt der Eintrag mit der Seite und mit ihrer KI-Freigabe (ADR 0028).
 */
public final class PresentationStore {

    static final String SCHEMA = "video-presentation/v1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EnrichmentStore store;
    private final Clock clock;
    private final Object lock = new Object();

    public PresentationStore(EnrichmentStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Ein abgelegter Modelltext.
     *
     * @param text  Antwort des Modells
     * @param model Verfahren und Modell
     * @param input Hash der Eingabe, aus der er entstand
     */
    public record Result(String text, String model, String input) {

        public Result {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(input, "input");
        }
    }

    /** Das korrigierte Transkript dieser Fassung. */
    public Optional<Result> corrected(PageKey page, String version) {
        return Optional.ofNullable(read(page, version).corrected);
    }

    /** Der Vortragstext je Folie (Position der Marke in Millisekunden) dieser Fassung. */
    public Map<Long, Result> notes(PageKey page, String version) {
        return Map.copyOf(read(page, version).notes);
    }

    /** Legt das korrigierte Transkript ab und ersetzt ein früheres. */
    public void saveCorrected(PageKey page, String version, Result corrected) {
        synchronized (lock) {
            Content content = read(page, version);
            content.corrected = Objects.requireNonNull(corrected, "corrected");
            write(page, version, content);
        }
    }

    /** Legt den Vortragstext einer Folie ab und ersetzt einen früheren. */
    public void saveNote(PageKey page, String version, long millis, Result note) {
        synchronized (lock) {
            Content content = read(page, version);
            content.notes.put(millis, Objects.requireNonNull(note, "note"));
            write(page, version, content);
        }
    }

    /** Entfernt alle Zwischenergebnisse der Seite. */
    public void remove(PageKey page) {
        synchronized (lock) {
            store.remove(page, Enrichments.VIDEO_PRESENTATION);
            store.flush();
        }
    }

    /** Ob die Seite Zwischenergebnisse hat. */
    public boolean has(PageKey page) {
        return store.find(page).stream()
                .anyMatch(found -> Enrichments.VIDEO_PRESENTATION.equals(found.processorId()));
    }

    /** Die Seiten mit Zwischenergebnissen. */
    public java.util.Collection<PageKey> pages() {
        return store.pagesWith(Enrichments.VIDEO_PRESENTATION);
    }

    private static final class Content {
        private Result corrected;
        private final Map<Long, Result> notes = new LinkedHashMap<>();
    }

    private Content read(PageKey page, String version) {
        Content content = new Content();
        for (Enrichment found : store.find(page)) {
            if (!Enrichments.VIDEO_PRESENTATION.equals(found.processorId())
                    || !Objects.equals(found.revision(), version)) {
                continue;
            }
            try {
                JsonNode payload = JSON.readTree(found.payloadJson());
                if (!SCHEMA.equals(payload.path("schema").asString())) {
                    return content;
                }
                content.corrected = result(payload.path("corrected"));
                for (JsonNode note : payload.path("notes")) {
                    Result result = result(note);
                    if (result != null && note.has("millis")) {
                        content.notes.put(note.path("millis").asLong(), result);
                    }
                }
            } catch (JacksonException e) {
                // unlesbar: alles gilt als noch nicht berechnet
            }
        }
        return content;
    }

    private static Result result(JsonNode node) {
        if (node == null || !node.isObject() || !node.path("text").isString()) {
            return null;
        }
        return new Result(node.path("text").asString(), node.path("model").asString(""),
                node.path("input").asString(""));
    }

    private void write(PageKey page, String version, Content content) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("schema", SCHEMA);
        if (content.corrected != null) {
            put(payload.putObject("corrected"), content.corrected);
        }
        ArrayNode notes = payload.putArray("notes");
        content.notes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ObjectNode note = notes.addObject();
            note.put("millis", entry.getKey());
            put(note, entry.getValue());
        });
        String json = JSON.writeValueAsString(payload);
        store.save(new Enrichment(page, Enrichments.VIDEO_PRESENTATION, SCHEMA,
                Assets.sha256(json.getBytes(StandardCharsets.UTF_8)), version, Enrichment.Status.OK, json, List.of(),
                null, clock.instant()));
        store.flush();
    }

    private static void put(ObjectNode node, Result result) {
        node.put("text", result.text());
        node.put("model", result.model());
        node.put("input", result.input());
    }
}
