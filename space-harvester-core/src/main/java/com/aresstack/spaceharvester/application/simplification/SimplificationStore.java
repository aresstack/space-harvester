package com.aresstack.spaceharvester.application.simplification;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ablage der Vorstufe je Satz (ADR 0032, FL3). Sie zählt als Seiteninhalt: Sie liegt unter der vorbehaltenen
 * Prozessor-ID {@link Enrichments#SIMPLIFICATIONS} bei der Seite in der Ablage der Anreicherungen, hat deren Rechte und
 * fällt mit der Seite und mit der Quelle weg; den Entzug der KI-Freigabe nimmt {@link #remove} zurück (ADR 0028).
 *
 * <p>Schlüssel eines Eintrags ist {@link #key}: Hash aus dem Satz und allen übrigen Zeilen seines Aufrufs (das
 * Ergebnis hängt an den Nachbarn), ohne Seitenfassung und Satznummer; Modell und {@link SentenceSimplifier#PROMPT_VERSION}
 * stehen als Version am Ergebnis der Seite. So läuft nach einer Änderung der Seite nur der Abschnitt mit dem geänderten
 * Satz neu, nach einem Wechsel von Modell oder Anweisung alles. Inhalte gehen nie in Logs oder Ausnahmen.
 */
public final class SimplificationStore {

    static final String SCHEMA = "sentence-simplifications-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EnrichmentStore store;
    private final Clock clock;

    public SimplificationStore(EnrichmentStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Schlüssel eines Satzes: SHA-256 über die übrigen Zeilen seines Aufrufs (in Textreihenfolge) und den Satztext,
     * unabhängig von Satznummer und Seitenfassung.
     */
    public static String key(String sentence, Collection<String> context) {
        StringBuilder basis = new StringBuilder();
        context.forEach(line -> basis.append(line.replaceAll("\\s+", " ").strip()).append('\n'));
        basis.append('\u0000').append(sentence.replaceAll("\\s+", " ").strip());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(basis.toString()
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Die gespeicherten Einträge der Seite für diese Version (Modell und Anweisung); leer bei anderer Version oder
     * unlesbarem Stand.
     */
    public synchronized Map<String, List<Line>> load(PageKey page, String version) {
        Objects.requireNonNull(version, "version");
        for (Enrichment found : store.find(page)) {
            if (!Enrichments.SIMPLIFICATIONS.equals(found.processorId())) {
                continue;
            }
            if (!version.equals(found.processorVersion()) || found.status() != Enrichment.Status.OK) {
                return Map.of();
            }
            try {
                return read(found.payloadJson());
            } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
                return Map.of();
            }
        }
        return Map.of();
    }

    /**
     * Ergänzt oder ersetzt Einträge der Seite für diese Version; Einträge einer anderen Version fallen weg.
     *
     * @param inputSha256 Eingabe-Hash des Lesetextes, aus dem die Sätze stammen
     */
    public synchronized void put(PageKey page, String version, String inputSha256, Map<String, List<Line>> entries) {
        Map<String, List<Line>> merged = new LinkedHashMap<>(load(page, version));
        merged.putAll(entries);
        save(page, version, inputSha256, merged);
    }

    /** Behält von den Einträgen der Seite für diese Version nur die genannten Schlüssel. */
    public synchronized void retain(PageKey page, String version, String inputSha256, Set<String> keys) {
        Map<String, List<Line>> current = load(page, version);
        Map<String, List<Line>> kept = new LinkedHashMap<>();
        current.forEach((key, lines) -> {
            if (keys.contains(key)) {
                kept.put(key, lines);
            }
        });
        if (kept.size() != current.size()) {
            save(page, version, inputSha256, kept);
        }
    }

    /** Entfernt alle Einträge der Seite, etwa nach dem Entzug der KI-Freigabe. */
    public synchronized void remove(PageKey page) {
        store.remove(page, Enrichments.SIMPLIFICATIONS);
        store.flush();
    }

    /** Ob für die Seite etwas gespeichert ist. */
    public synchronized boolean contains(PageKey page) {
        return store.find(page).stream().anyMatch(found -> Enrichments.SIMPLIFICATIONS.equals(found.processorId()));
    }

    private void save(PageKey page, String version, String inputSha256, Map<String, List<Line>> entries) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("schema", SCHEMA);
        ObjectNode sentences = payload.putObject("sentences");
        entries.forEach((key, lines) -> {
            ArrayNode array = sentences.putArray(key);
            for (Line line : lines) {
                ObjectNode node = array.addObject();
                node.put("flag", line.flag().code());
                node.put("text", line.text());
            }
        });
        store.save(new Enrichment(page, Enrichments.SIMPLIFICATIONS, version, inputSha256, null,
                Enrichment.Status.OK, JSON.writeValueAsString(payload), List.of(), null, clock.instant()));
        store.flush();
    }

    private static Map<String, List<Line>> read(String payloadJson) {
        JsonNode payload = JSON.readTree(payloadJson);
        if (!SCHEMA.equals(payload.path("schema").asString())) {
            throw new IllegalArgumentException("Unbekannter Aufbau");
        }
        Map<String, List<Line>> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : payload.path("sentences").properties()) {
            List<Line> lines = new ArrayList<>();
            for (JsonNode node : entry.getValue()) {
                SimplifiedSentence.Flag flag = SimplifiedSentence.Flag.of(node.path("flag").asString());
                String text = node.path("text").asString();
                if (flag == null || text == null || text.isBlank()) {
                    throw new IllegalArgumentException("Unlesbarer Eintrag");
                }
                lines.add(new Line(flag, text));
            }
            entries.put(entry.getKey(), List.copyOf(lines));
        }
        return entries;
    }

    /** Ein vereinfachter Satz in der Ablage, ohne Satznummer (die gilt nur für eine Fassung der Seite). */
    public record Line(SimplifiedSentence.Flag flag, String text) {

        public Line {
            Objects.requireNonNull(flag, "flag");
            Objects.requireNonNull(text, "text");
        }
    }
}
