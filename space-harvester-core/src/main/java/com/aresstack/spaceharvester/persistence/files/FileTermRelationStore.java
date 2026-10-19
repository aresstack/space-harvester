package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelationStore;
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
 * Beziehungsablage als Dateien (#120, ADR 0019 Punkt 5): {@code relations.json} mit allen Beziehungen, atomar ersetzt,
 * und {@code audit.jsonl}, nur angehängt. Das Verzeichnis liegt außerhalb des Caches, ein Neuaufbau des Caches berührt
 * es nicht. Kein Inhaltstext, keine Geheimnisse: nur Begriffe, Belege als Seitenposition, Kennungen der Bearbeiter.
 *
 * <p>Schema {@code term-relations/v1}. Eine unlesbare Datei bricht den Start ab, statt Nutzerdaten still zu
 * verwerfen; eine unlesbare Protokollzeile (abgebrochenes Anhängen) wird übersprungen.
 */
public final class FileTermRelationStore implements TermRelationStore {

    public static final String SCHEMA = "term-relations/v1";
    static final String RELATIONS = "relations.json";
    static final String AUDIT = "audit.jsonl";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path directory;
    private final Map<String, TermRelation> relations = new LinkedHashMap<>();

    /**
     * @throws IllegalStateException wenn {@code relations.json} unlesbar ist oder ein anderes Schema hat
     */
    public FileTermRelationStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(RELATIONS);
            if (Files.exists(file)) {
                JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
                if (!SCHEMA.equals(root.path("schema").asString(null))) {
                    throw new IllegalStateException(file + " hat nicht das Schema " + SCHEMA);
                }
                for (JsonNode node : root.path("relations")) {
                    TermRelation relation = relation(node);
                    relations.put(relation.id(), relation);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Beziehungsablage unter " + directory + " ist unlesbar", e);
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public synchronized List<TermRelation> all() {
        return List.copyOf(relations.values());
    }

    @Override
    public synchronized Optional<TermRelation> find(String id) {
        return Optional.ofNullable(relations.get(id));
    }

    @Override
    public synchronized void write(Collection<TermRelation> put, Collection<String> removed,
                                   List<AuditEntry> audit) {
        Map<String, TermRelation> next = new LinkedHashMap<>(relations);
        removed.forEach(next::remove);
        put.forEach(relation -> next.put(relation.id(), relation));
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", SCHEMA);
        ArrayNode list = root.putArray("relations");
        next.values().forEach(relation -> list.add(json(relation)));
        try {
            replace(directory.resolve(RELATIONS), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            if (!audit.isEmpty()) {
                StringBuilder lines = new StringBuilder();
                audit.forEach(entry -> lines.append(JSON.writeValueAsString(json(entry))).append('\n'));
                Files.writeString(directory.resolve(AUDIT), lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        relations.clear();
        relations.putAll(next);
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
                    if (id.equals(node.path("relationId").asString(null))) {
                        found.add(new AuditEntry(id, node.path("action").asString(), node.path("actor").asString(),
                                Instant.parse(node.path("at").asString()),
                                node.hasNonNull("status") ? ReviewStatus.valueOf(node.get("status").asString()) : null,
                                node.path("revision").asLong()));
                    }
                } catch (RuntimeException e) {
                    // Abgebrochenes Anhängen: diese Zeile fehlt im Protokoll, die Beziehungen sind unberührt.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static ObjectNode json(TermRelation relation) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", relation.id());
        node.put("fromTerm", relation.fromTerm());
        node.put("toTerm", relation.toTerm());
        node.put("type", relation.type().name());
        node.put("sourceId", relation.scope().sourceId());
        node.put("spaceKey", relation.scope().spaceKey());
        node.put("origin", relation.origin().name());
        node.put("status", relation.status().name());
        ArrayNode evidence = node.putArray("evidence");
        for (RelationEvidence item : relation.evidence()) {
            ObjectNode entry = evidence.addObject();
            entry.put("sourceId", item.sourceId());
            entry.put("pageId", item.pageId());
            entry.put("start", item.start());
            entry.put("end", item.end());
            entry.put("inputSha256", item.inputSha256());
        }
        node.put("note", relation.note());
        node.put("generation", relation.generation());
        node.put("evidenceStale", relation.evidenceStale());
        node.put("createdBy", relation.createdBy());
        node.put("createdAt", relation.createdAt().toString());
        node.put("reviewedBy", relation.reviewedBy());
        node.put("reviewedAt", relation.reviewedAt() == null ? null : relation.reviewedAt().toString());
        node.put("revision", relation.revision());
        return node;
    }

    private static TermRelation relation(JsonNode node) {
        List<RelationEvidence> evidence = new ArrayList<>();
        for (JsonNode item : node.path("evidence")) {
            evidence.add(new RelationEvidence(item.path("sourceId").asString(), item.path("pageId").asString(),
                    item.path("start").asInt(), item.path("end").asInt(), item.path("inputSha256").asString(null)));
        }
        return new TermRelation(node.path("id").asString(), node.path("fromTerm").asString(),
                node.path("toTerm").asString(), RelationType.valueOf(node.path("type").asString()),
                new RelationScope(text(node, "sourceId"), text(node, "spaceKey")),
                RelationOrigin.valueOf(node.path("origin").asString()),
                ReviewStatus.valueOf(node.path("status").asString()), evidence, text(node, "note"),
                text(node, "generation"), node.path("evidenceStale").asBoolean(),
                node.path("createdBy").asString(), Instant.parse(node.path("createdAt").asString()),
                text(node, "reviewedBy"),
                node.hasNonNull("reviewedAt") ? Instant.parse(node.get("reviewedAt").asString()) : null,
                node.path("revision").asLong());
    }

    private static ObjectNode json(AuditEntry entry) {
        ObjectNode node = JSON.createObjectNode();
        node.put("relationId", entry.relationId());
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
