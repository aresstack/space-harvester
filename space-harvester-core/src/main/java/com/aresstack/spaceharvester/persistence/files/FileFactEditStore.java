package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.facts.FactEdit;
import com.aresstack.spaceharvester.application.facts.FactEditStore;
import com.aresstack.spaceharvester.application.facts.FactOrigin;
import com.aresstack.spaceharvester.application.facts.FactReview;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prüfablage der Fakten als Dateien (#182), wie die Begriffsbeziehungen: {@code facts.json} mit allen manuellen und
 * geprüften Fakten, atomar ersetzt, und {@code audit.jsonl}, nur angehängt. Das Verzeichnis liegt außerhalb des
 * Caches; ein Neuaufbau des Caches oder der Anreicherungen berührt es nicht. Die Payloads stammen aus dem Inhalt der
 * Seiten: Das Verzeichnis ist so schutzbedürftig wie der Cache. Keine Geheimnisse, Bearbeiter nur als Kennung.
 *
 * <p>Schema {@code facts/v1}. Eine unlesbare Datei bricht den Start ab, statt Nutzerdaten still zu verwerfen;
 * eine unlesbare Protokollzeile (abgebrochenes Anhängen) wird übersprungen. Das Verzeichnis entsteht erst beim ersten
 * Schreiben.
 */
public final class FileFactEditStore implements FactEditStore {

    public static final String SCHEMA = "facts/v1";
    static final String REVIEWS = "facts.json";
    static final String AUDIT = "audit.jsonl";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path directory;
    private final Map<String, FactEdit> edits = new TreeMap<>();

    /**
     * @throws IllegalStateException wenn {@code facts.json} unlesbar ist oder ein anderes Schema hat
     */
    public FileFactEditStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        Path file = directory.resolve(REVIEWS);
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(root.path("schema").asString(null))) {
                throw new IllegalStateException(file + " hat nicht das Schema " + SCHEMA);
            }
            for (JsonNode node : root.path("facts")) {
                FactEdit edit = edit(node);
                edits.put(edit.id(), edit);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Prüfablage der Fakten unter " + directory + " ist unlesbar", e);
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public synchronized List<FactEdit> all() {
        return List.copyOf(edits.values());
    }

    @Override
    public synchronized List<FactEdit> forPage(PageKey page) {
        return edits.values().stream().filter(edit -> edit.fact().page().equals(page)).toList();
    }

    @Override
    public synchronized Optional<FactEdit> find(String factId) {
        return Optional.ofNullable(edits.get(factId));
    }

    @Override
    public synchronized void write(Collection<FactEdit> put, Collection<String> removed, List<AuditEntry> audit) {
        Map<String, FactEdit> next = new TreeMap<>(edits);
        removed.forEach(next::remove);
        put.forEach(edit -> next.put(edit.id(), edit));
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", SCHEMA);
        ArrayNode list = root.putArray("facts");
        next.values().forEach(edit -> list.add(json(edit)));
        try {
            Files.createDirectories(directory);
            replace(directory.resolve(REVIEWS), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            if (!audit.isEmpty()) {
                StringBuilder lines = new StringBuilder();
                audit.forEach(entry -> lines.append(JSON.writeValueAsString(json(entry))).append('\n'));
                Files.writeString(directory.resolve(AUDIT), lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        edits.clear();
        edits.putAll(next);
    }

    @Override
    public synchronized List<AuditEntry> history(String factId) {
        Path file = directory.resolve(AUDIT);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<AuditEntry> found = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || !line.contains(factId)) {
                    continue;
                }
                try {
                    JsonNode node = JSON.readTree(line);
                    if (factId.equals(node.path("factId").asString(null))) {
                        found.add(new AuditEntry(factId, new PageKey(node.path("sourceId").asString(),
                                node.path("pageId").asString()), node.path("action").asString(),
                                node.path("actor").asString(), Instant.parse(node.path("at").asString()),
                                FactReview.valueOf(node.path("review").asString()), node.path("revision").asLong()));
                    }
                } catch (RuntimeException e) {
                    // Abgebrochenes Anhängen: diese Zeile fehlt im Protokoll, die Fakten sind unberührt.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static ObjectNode json(FactEdit edit) {
        MetadataFact fact = edit.fact();
        ObjectNode node = JSON.createObjectNode();
        node.put("id", fact.id());
        node.put("sourceId", fact.page().sourceId());
        node.put("pageId", fact.page().pageId());
        node.put("type", fact.type());
        node.put("role", fact.role());
        node.put("payload", fact.payloadJson());
        ArrayNode arguments = node.putArray("arguments");
        for (MetadataFact.Argument argument : fact.arguments()) {
            ObjectNode entry = arguments.addObject();
            entry.put("role", argument.role());
            entry.put("ordinal", argument.ordinal());
            entry.put("value", argument.value());
        }
        MetadataFact.Provenance provenance = fact.provenance();
        ObjectNode origin = node.putObject("provenance");
        origin.put("processorId", provenance.processorId());
        origin.put("processorVersion", provenance.processorVersion());
        origin.put("inputSha256", provenance.inputSha256());
        origin.put("revision", provenance.revision());
        origin.put("computedAt", provenance.computedAt().toString());
        if (fact.anchor() == null) {
            node.putNull("anchor");
        } else {
            ObjectNode anchor = node.putObject("anchor");
            anchor.put("section", fact.anchor().section());
            anchor.put("charStart", fact.anchor().charStart());
            anchor.put("charEnd", fact.anchor().charEnd());
        }
        node.put("origin", fact.origin().name());
        node.put("review", fact.review().name());
        node.put("revision", edit.revision());
        node.put("createdBy", edit.createdBy());
        node.put("createdAt", edit.createdAt().toString());
        node.put("reviewedBy", edit.reviewedBy());
        node.put("reviewedAt", edit.reviewedAt() == null ? null : edit.reviewedAt().toString());
        return node;
    }

    private static FactEdit edit(JsonNode node) {
        List<MetadataFact.Argument> arguments = new ArrayList<>();
        for (JsonNode argument : node.path("arguments")) {
            arguments.add(new MetadataFact.Argument(argument.path("role").asString(), argument.path("ordinal").asInt(),
                    argument.path("value").asString()));
        }
        JsonNode provenance = node.path("provenance");
        JsonNode anchor = node.path("anchor");
        MetadataFact fact = new MetadataFact(node.path("id").asString(), new PageKey(node.path("sourceId").asString(),
                node.path("pageId").asString()), node.path("type").asString(), text(node, "role"),
                node.path("payload").asString(), arguments, new MetadataFact.Provenance(
                        provenance.path("processorId").asString(), provenance.path("processorVersion").asString(),
                        provenance.path("inputSha256").asString(), text(provenance, "revision"),
                        Instant.parse(provenance.path("computedAt").asString())),
                anchor.isObject() ? new MetadataFact.Anchor(text(anchor, "section"), integer(anchor, "charStart"),
                        integer(anchor, "charEnd")) : null,
                FactOrigin.valueOf(node.path("origin").asString()), FactReview.valueOf(node.path("review").asString()));
        return new FactEdit(fact, node.path("revision").asLong(), node.path("createdBy").asString(),
                Instant.parse(node.path("createdAt").asString()), text(node, "reviewedBy"),
                node.hasNonNull("reviewedAt") ? Instant.parse(node.get("reviewedAt").asString()) : null);
    }

    private static ObjectNode json(AuditEntry entry) {
        ObjectNode node = JSON.createObjectNode();
        node.put("factId", entry.factId());
        node.put("sourceId", entry.page().sourceId());
        node.put("pageId", entry.page().pageId());
        node.put("action", entry.action());
        node.put("actor", entry.actor());
        node.put("at", entry.at().toString());
        node.put("review", entry.review().name());
        node.put("revision", entry.revision());
        return node;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
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
