package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.standing.Standing;
import com.aresstack.spaceharvester.application.standing.StandingDecision;
import com.aresstack.spaceharvester.application.standing.StandingDecisionStore;
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
 * Entscheidungen zu Stand und Prüfung als Dateien (ADR 0032, FL6 und W3), wie die Beziehungsablage:
 * {@code decisions.json} mit allen Einträgen, atomar ersetzt, und {@code audit.jsonl}, nur angehängt. Das Verzeichnis
 * liegt außerhalb des Cache. Kein Inhaltstext: nur Seiten, Aussage-IDs, Analysator der Aussage, Stand, Prüfung,
 * Revision, Kennungen der Bearbeiter und Zeitpunkte.
 *
 * <p>Geschrieben wird Schema {@value #SCHEMA}: {@code analyzer} ({@code parser}/{@code model}, nur bei Aussagen),
 * {@code standing} und {@code review} je nur, wenn gesetzt. Gelesen werden auch Dateien im Schema {@value #SCHEMA_V1}
 * (Stand Pflicht, keine Prüfung, Aussagen gehören zum MaltParser). Ein älterer Stand der Anwendung lehnt eine Datei im
 * neuen Schema mit klarer Meldung ab, statt an einem Eintrag nur mit Prüfung zu scheitern. Eine unlesbare Datei bricht
 * den Start ab, statt Nutzerdaten still zu verwerfen; eine unlesbare Protokollzeile (abgebrochenes Anhängen) wird
 * übersprungen. Protokollzeilen tragen {@code analyzer} und {@code review} zusätzlich; ältere Zeilen ohne sie gelten
 * für den MaltParser und ohne Prüfung.
 */
public final class FileStandingDecisionStore implements StandingDecisionStore {

    public static final String SCHEMA = "standing-decisions/v2";
    /** Das Schema vor W3: nur Stand; wird weiter gelesen. */
    public static final String SCHEMA_V1 = "standing-decisions/v1";
    static final String DECISIONS = "decisions.json";
    static final String AUDIT = "audit.jsonl";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path directory;
    private final Map<StandingDecision.Target, StandingDecision> decisions = new LinkedHashMap<>();

    /** @throws IllegalStateException wenn {@code decisions.json} unlesbar ist oder ein anderes Schema hat */
    public FileStandingDecisionStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(DECISIONS);
            if (Files.exists(file)) {
                JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
                String schema = root.path("schema").asString(null);
                if (!SCHEMA.equals(schema) && !SCHEMA_V1.equals(schema)) {
                    throw new IllegalStateException(file + " hat nicht das Schema " + SCHEMA + " oder " + SCHEMA_V1);
                }
                for (JsonNode node : root.path("decisions")) {
                    String statementId = text(node, "statementId");
                    // v1 kennt weder Analysator noch Prüfung; der Stand ist dort Pflicht (der Datensatz prüft das).
                    StandingDecision decision = new StandingDecision(page(node), statementId,
                            analyzer(node, statementId), standing(node), review(node),
                            node.path("revision").asLong(), node.path("createdBy").asString(),
                            Instant.parse(node.path("createdAt").asString()), node.path("decidedBy").asString(),
                            Instant.parse(node.path("decidedAt").asString()));
                    decisions.put(decision.target(), decision);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Ablage der Entscheidungen zum Stand unter " + directory
                    + " ist unlesbar", e);
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public synchronized List<StandingDecision> all() {
        return List.copyOf(decisions.values());
    }

    @Override
    public synchronized Optional<StandingDecision> find(StandingDecision.Target target) {
        return Optional.ofNullable(decisions.get(target));
    }

    @Override
    public synchronized void write(Collection<StandingDecision> put, Collection<StandingDecision.Target> removed,
                                   List<AuditEntry> audit) {
        Map<StandingDecision.Target, StandingDecision> next = new LinkedHashMap<>(decisions);
        removed.forEach(next::remove);
        put.forEach(decision -> next.put(decision.target(), decision));
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", SCHEMA);
        ArrayNode list = root.putArray("decisions");
        for (StandingDecision decision : next.values()) {
            ObjectNode node = list.addObject();
            node.put("sourceId", decision.page().sourceId());
            node.put("pageId", decision.page().pageId());
            node.put("statementId", decision.statementId());
            if (decision.analyzer() != null) {
                node.put("analyzer", decision.analyzer().value());
            }
            if (decision.standing() != null) {
                node.put("standing", decision.standing().name());
            }
            if (decision.review() != null) {
                node.put("review", decision.review().name());
            }
            node.put("revision", decision.revision());
            node.put("createdBy", decision.createdBy());
            node.put("createdAt", decision.createdAt().toString());
            node.put("decidedBy", decision.decidedBy());
            node.put("decidedAt", decision.decidedAt().toString());
        }
        try {
            replace(directory.resolve(DECISIONS), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            if (!audit.isEmpty()) {
                StringBuilder lines = new StringBuilder();
                audit.forEach(entry -> lines.append(JSON.writeValueAsString(json(entry))).append('\n'));
                Files.writeString(directory.resolve(AUDIT), lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        decisions.clear();
        decisions.putAll(next);
    }

    @Override
    public synchronized List<AuditEntry> history(StandingDecision.Target target) {
        Path file = directory.resolve(AUDIT);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<AuditEntry> found = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = JSON.readTree(line);
                    String statementId = text(node, "statementId");
                    StandingDecision.Target entry = new StandingDecision.Target(page(node), statementId,
                            analyzer(node, statementId));
                    if (entry.equals(target)) {
                        found.add(new AuditEntry(entry, node.path("action").asString(), node.path("actor").asString(),
                                Instant.parse(node.path("at").asString()), standing(node), review(node),
                                node.path("revision").asLong()));
                    }
                } catch (RuntimeException e) {
                    // Abgebrochenes Anhängen: diese Zeile fehlt im Protokoll, die Entscheidungen sind unberührt.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static ObjectNode json(AuditEntry entry) {
        ObjectNode node = JSON.createObjectNode();
        node.put("sourceId", entry.target().page().sourceId());
        node.put("pageId", entry.target().page().pageId());
        node.put("statementId", entry.target().statementId());
        if (entry.target().analyzer() != null) {
            node.put("analyzer", entry.target().analyzer().value());
        }
        node.put("action", entry.action());
        node.put("actor", entry.actor());
        node.put("at", entry.at().toString());
        node.put("standing", entry.standing() == null ? null : entry.standing().name());
        node.put("review", entry.review() == null ? null : entry.review().name());
        node.put("revision", entry.revision());
        return node;
    }

    private static PageKey page(JsonNode node) {
        return new PageKey(node.path("sourceId").asString(), node.path("pageId").asString());
    }

    /** Fehlt der Analysator (v1, ältere Protokollzeilen), gehört eine Aussage zum MaltParser. */
    private static AnalyzerChoice analyzer(JsonNode node, String statementId) {
        return statementId == null ? null : AnalyzerChoice.parse(text(node, "analyzer"));
    }

    private static Standing standing(JsonNode node) {
        String value = text(node, "standing");
        return value == null ? null : Standing.valueOf(value);
    }

    private static ParsedStatements.Review review(JsonNode node) {
        String value = text(node, "review");
        return value == null ? null : ParsedStatements.Review.valueOf(value);
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
