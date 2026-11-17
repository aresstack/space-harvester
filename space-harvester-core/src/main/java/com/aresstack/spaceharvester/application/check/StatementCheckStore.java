package com.aresstack.spaceharvester.application.check;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ablage des Prüfschritts je Aussage (ADR 0032, FL4). Sie zählt als Seiteninhalt: Sie liegt unter der vorbehaltenen
 * Prozessor-ID {@link Enrichments#STATEMENT_CHECKS} bei der Seite in der Ablage der Anreicherungen, hat deren Rechte
 * und fällt mit der Seite und mit der Quelle weg; den Entzug der KI-Freigabe nimmt {@link #remove} zurück (ADR 0028).
 *
 * <p>Schlüssel eines Eintrags ist {@link #key}: Hash aus Originalsatz und der Aussage, wie das Modell sie sah
 * ({@link StatementCheck#render}), ohne Seitenfassung und Satznummer; Modell und {@link StatementCheck#PROMPT_VERSION}
 * stehen als Version am Ergebnis der Seite. So läuft nach einer Änderung der Seite oder des Parsers nur neu, was sich
 * am Satz oder an der Aussage geändert hat, nach einem Wechsel von Modell oder Anweisung alles. Inhalte gehen nie in
 * Logs oder Ausnahmen.
 */
public final class StatementCheckStore {

    static final String SCHEMA = "statement-checks-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EnrichmentStore store;
    private final Clock clock;

    public StatementCheckStore(EnrichmentStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Schlüssel einer Aussage: SHA-256 über den Originalsatz und die Aussage, wie das Modell sie sah. */
    public static String key(String sentence, String renderedItem) {
        String basis = sentence.replaceAll("\\s+", " ").strip() + '\u0000' + renderedItem;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Die gespeicherten Einträge der Seite für diese Version (Modell und Anweisung); leer bei anderer Version oder
     * unlesbarem Stand.
     */
    public synchronized Map<String, Line> load(PageKey page, String version) {
        Objects.requireNonNull(version, "version");
        for (Enrichment found : store.find(page)) {
            if (!Enrichments.STATEMENT_CHECKS.equals(found.processorId())) {
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
     * @param inputSha256 Eingabe-Hash des Lesetextes, aus dem die Aussagen stammen
     */
    public synchronized void put(PageKey page, String version, String inputSha256, Map<String, Line> entries) {
        Map<String, Line> merged = new LinkedHashMap<>(load(page, version));
        merged.putAll(entries);
        save(page, version, inputSha256, merged);
    }

    /** Behält von den Einträgen der Seite für diese Version nur die genannten Schlüssel. */
    public synchronized void retain(PageKey page, String version, String inputSha256, Set<String> keys) {
        Map<String, Line> current = load(page, version);
        Map<String, Line> kept = new LinkedHashMap<>();
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
        store.remove(page, Enrichments.STATEMENT_CHECKS);
        store.flush();
    }

    /** Ob für die Seite etwas gespeichert ist. */
    public synchronized boolean contains(PageKey page) {
        return store.find(page).stream().anyMatch(found -> Enrichments.STATEMENT_CHECKS.equals(found.processorId()));
    }

    private void save(PageKey page, String version, String inputSha256, Map<String, Line> entries) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("schema", SCHEMA);
        ObjectNode sentences = payload.putObject("statements");
        entries.forEach((key, line) -> {
            ObjectNode node = sentences.putObject(key);
            node.put("verdict", line.verdict().name());
            if (line.correction() != null) {
                node.put("correction", line.correction());
            }
        });
        store.save(new Enrichment(page, Enrichments.STATEMENT_CHECKS, version, inputSha256, null,
                Enrichment.Status.OK, JSON.writeValueAsString(payload), List.of(), null, clock.instant()));
        store.flush();
    }

    private static Map<String, Line> read(String payloadJson) {
        JsonNode payload = JSON.readTree(payloadJson);
        if (!SCHEMA.equals(payload.path("schema").asString())) {
            throw new IllegalArgumentException("Unbekannter Aufbau");
        }
        Map<String, Line> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : payload.path("statements").properties()) {
            JsonNode node = entry.getValue();
            ParsedStatements.Verdict verdict = ParsedStatements.Verdict.valueOf(node.path("verdict").asString());
            JsonNode correction = node.get("correction");
            entries.put(entry.getKey(), new Line(verdict, correction == null || correction.isNull() ? null
                    : correction.asString()));
        }
        return entries;
    }

    /** Ein Urteil in der Ablage, ohne Satznummer (die gilt nur für eine Fassung der Seite). */
    public record Line(ParsedStatements.Verdict verdict, String correction) {

        public Line {
            Objects.requireNonNull(verdict, "verdict");
            if (correction != null && verdict != ParsedStatements.Verdict.UNVOLLSTAENDIG) {
                throw new IllegalArgumentException("Nur UNVOLLSTAENDIG hat eine Korrektur");
            }
        }
    }
}
