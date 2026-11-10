package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.glossary.GlossaryEntry;
import com.aresstack.spaceharvester.application.glossary.GlossaryStore;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Glossarablage als Dateien (ADR 0019): {@code glossary.json} mit allen Einträgen, atomar ersetzt, und
 * {@code audit.jsonl}, nur angehängt. Das Verzeichnis liegt außerhalb des Caches, ein Neuaufbau des Caches berührt es
 * nicht. Kein Seitentext, keine Geheimnisse: nur Begriffe, Erläuterungen der Bearbeiter, Belege als Seitenposition und
 * Kennungen.
 *
 * <p>Schema {@code glossary/v1}. Eine unlesbare Datei bricht den Start ab, statt Nutzerdaten still zu verwerfen; eine
 * unlesbare Protokollzeile (abgebrochenes Anhängen) wird übersprungen.
 */
public final class FileGlossaryStore implements GlossaryStore {

    public static final String SCHEMA = "glossary/v1";
    static final String ENTRIES = "glossary.json";
    static final String AUDIT = "audit.jsonl";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path directory;
    private final Map<String, GlossaryEntry> entries = new LinkedHashMap<>();

    /** @throws IllegalStateException wenn {@code glossary.json} unlesbar ist oder ein anderes Schema hat */
    public FileGlossaryStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(ENTRIES);
            if (Files.exists(file)) {
                JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
                if (!SCHEMA.equals(root.path("schema").asString(null))) {
                    throw new IllegalStateException(file + " hat nicht das Schema " + SCHEMA);
                }
                for (JsonNode node : root.path("entries")) {
                    GlossaryEntry entry = entry(node);
                    entries.put(entry.id(), entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JacksonException | IllegalArgumentException | NullPointerException
                 | java.time.format.DateTimeParseException e) {
            throw new IllegalStateException("Glossarablage unter " + directory + " ist unlesbar", e);
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public synchronized List<GlossaryEntry> all() {
        return List.copyOf(entries.values());
    }

    @Override
    public synchronized Optional<GlossaryEntry> find(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public synchronized void write(Collection<GlossaryEntry> put, Collection<String> removed,
                                   List<AuditEntry> audit) {
        Map<String, GlossaryEntry> next = new LinkedHashMap<>(entries);
        removed.forEach(next::remove);
        put.forEach(entry -> next.put(entry.id(), entry));
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", SCHEMA);
        ArrayNode list = root.putArray("entries");
        next.values().forEach(entry -> list.add(json(entry)));
        try {
            replace(directory.resolve(ENTRIES), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Erst der Bestand im Speicher, dann das Protokoll: scheitert das Anhängen, weichen Datei und Speicher nicht ab.
        entries.clear();
        entries.putAll(next);
        try {
            if (!audit.isEmpty()) {
                StringBuilder lines = new StringBuilder();
                audit.forEach(entry -> lines.append(JSON.writeValueAsString(json(entry))).append('\n'));
                Files.writeString(directory.resolve(AUDIT), lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized List<AuditEntry> history(String id) {
        Path file = directory.resolve(AUDIT);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<AuditEntry> found = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || !line.contains(id)) {
                    continue;
                }
                try {
                    JsonNode node = JSON.readTree(line);
                    if (id.equals(node.path("entryId").asString(null))) {
                        found.add(new AuditEntry(id, node.path("action").asString(), node.path("actor").asString(),
                                Instant.parse(node.path("at").asString()),
                                node.hasNonNull("status") ? ReviewStatus.valueOf(node.get("status").asString()) : null,
                                node.path("revision").asLong()));
                    }
                } catch (RuntimeException e) {
                    // Abgebrochenes Anhängen: diese Zeile fehlt im Protokoll, die Einträge sind unberührt.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static ObjectNode json(GlossaryEntry entry) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", entry.id());
        node.put("term", entry.term());
        node.put("normalized", entry.normalized());
        node.put("sourceId", entry.scope().sourceId());
        node.put("spaceKey", entry.scope().spaceKey());
        node.put("origin", entry.origin().name());
        node.put("status", entry.status().name());
        ArrayNode evidence = node.putArray("evidence");
        for (RelationEvidence item : entry.evidence()) {
            ObjectNode line = evidence.addObject();
            line.put("sourceId", item.sourceId());
            line.put("pageId", item.pageId());
            line.put("start", item.start());
            line.put("end", item.end());
            line.put("inputSha256", item.inputSha256());
        }
        node.put("evidenceStale", entry.evidenceStale());
        node.put("explanation", entry.explanation());
        node.put("generation", entry.generation());
        node.put("createdBy", entry.createdBy());
        node.put("createdAt", entry.createdAt().toString());
        node.put("updatedAt", entry.updatedAt().toString());
        node.put("reviewedBy", entry.reviewedBy());
        node.put("reviewedAt", entry.reviewedAt() == null ? null : entry.reviewedAt().toString());
        node.put("revision", entry.revision());
        return node;
    }

    private static GlossaryEntry entry(JsonNode node) {
        List<RelationEvidence> evidence = new ArrayList<>();
        for (JsonNode item : node.path("evidence")) {
            evidence.add(new RelationEvidence(item.path("sourceId").asString(), item.path("pageId").asString(),
                    item.path("start").asInt(), item.path("end").asInt(), item.path("inputSha256").asString(null)));
        }
        return new GlossaryEntry(node.path("id").asString(), node.path("term").asString(),
                node.path("normalized").asString(), new RelationScope(text(node, "sourceId"), text(node, "spaceKey")),
                RelationOrigin.valueOf(node.path("origin").asString()),
                ReviewStatus.valueOf(node.path("status").asString()), evidence,
                node.path("evidenceStale").asBoolean(), text(node, "explanation"), text(node, "generation"),
                node.path("createdBy").asString(), Instant.parse(node.path("createdAt").asString()),
                Instant.parse(node.path("updatedAt").asString()), text(node, "reviewedBy"),
                node.hasNonNull("reviewedAt") ? Instant.parse(node.get("reviewedAt").asString()) : null,
                node.path("revision").asLong());
    }

    private static ObjectNode json(AuditEntry entry) {
        ObjectNode node = JSON.createObjectNode();
        node.put("entryId", entry.entryId());
        node.put("action", entry.action());
        node.put("actor", entry.actor());
        node.put("at", entry.at().toString());
        node.put("status", entry.status() == null ? null : entry.status().name());
        node.put("revision", entry.revision());
        return node;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static void replace(Path target, byte[] content) throws IOException {
        Path temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
