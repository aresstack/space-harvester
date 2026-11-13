package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphStore;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ablage der Aussagenanalysen und damit des globalen Knowledge Graph als Dateien (#499, ADR 0024): je Seite eine Datei
 * {@code pages/<sha256(quelle, seite)>.json} (Schema {@value PageStatementAnalysis#SCHEMA}) mit Analyse und CoNLL-U,
 * atomar ersetzt. Das Verzeichnis liegt außerhalb des Caches, ein Neuaufbau des Caches berührt es nicht.
 *
 * <p>Anhänge und enthaltene Dateien (ADR 0026, Punkt 10, Schlüsselform Punkt 7) liegen unter
 * {@code documents/<Hash der Seite>/<DocumentKey#hash()>.json}, damit alles zu einer Seite in einem Zug gefunden und
 * entfernt werden kann. Seiten behalten Ort und Namen ({@link DocumentKey#hash()} der Seite ist der bisherige Name);
 * in der Datei stehen {@code pageId}, {@code attachmentId} und {@code partId} als getrennte Felder.
 *
 * <p>Gelesen wird beim ersten Zugriff, nicht beim Start. Im Speicher bleiben die Analysen ohne CoNLL-U; die
 * Syntaxdetails liest {@link #conllu} bei Bedarf aus der Datei. Eine unlesbare Datei (abgebrochener Schreibvorgang
 * eines anderen Programms, fremdes Schema) wird übergangen und protokolliert, ohne Inhalt: Die Analyse ist aus einer
 * Benutzeraktion wiederholbar und verdrängt die Datei beim nächsten Klick.
 */
public final class FileKnowledgeGraphStore implements KnowledgeGraphStore {

    static final String PAGES = "pages";
    static final String DOCUMENTS = "documents";

    private static final Logger LOG = LoggerFactory.getLogger(FileKnowledgeGraphStore.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SUFFIX = ".json";

    private final Path directory;
    private Map<DocumentKey, PageStatementAnalysis> loaded;

    public FileKnowledgeGraphStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public Path directory() {
        return directory;
    }

    @Override
    public synchronized List<PageStatementAnalysis> all() {
        return analyses().values().stream().filter(analysis -> !analysis.ofDocument()).toList();
    }

    @Override
    public synchronized List<PageStatementAnalysis> documents() {
        return analyses().values().stream().filter(PageStatementAnalysis::ofDocument).toList();
    }

    @Override
    public synchronized Optional<PageStatementAnalysis> find(PageKey key) {
        return find(DocumentKey.page(key));
    }

    @Override
    public synchronized Optional<PageStatementAnalysis> find(DocumentKey document) {
        return Optional.ofNullable(analyses().get(document));
    }

    @Override
    public synchronized Optional<String> conllu(PageKey key) {
        return conllu(DocumentKey.page(key));
    }

    @Override
    public synchronized Optional<String> conllu(DocumentKey document) {
        if (!analyses().containsKey(document)) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file(document), StandardCharsets.UTF_8));
            return Optional.ofNullable(root.path("result").path("conllu").asString(null));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    @Override
    public synchronized void replace(PageStatementAnalysis analysis) {
        Map<DocumentKey, PageStatementAnalysis> all = analyses();
        Path target = file(analysis.document());
        try {
            Files.createDirectories(target.getParent());
            replace(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(json(analysis)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        all.put(analysis.document(), analysis.withResult(analysis.result().withoutConllu()));
    }

    @Override
    public synchronized void remove(PageKey key) {
        Map<DocumentKey, PageStatementAnalysis> all = analyses();
        try {
            Files.deleteIfExists(file(DocumentKey.page(key)));
            deleteTree(documentsOf(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        all.keySet().removeIf(document -> document.page().equals(key));
    }

    @Override
    public synchronized void removeDocument(DocumentKey document) {
        Map<DocumentKey, PageStatementAnalysis> all = analyses();
        List<DocumentKey> gone = document.isPage() || document.isEmbedded() ? List.of(document)
                : all.keySet().stream().filter(other -> other.page().equals(document.page())
                && other.attachmentId().equals(document.attachmentId())).toList();
        try {
            Files.deleteIfExists(file(document));
            for (DocumentKey other : gone) {
                Files.deleteIfExists(file(other));
            }
            if (!document.isPage()) {
                deleteIfEmpty(documentsOf(document.page()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        gone.forEach(all::remove);
    }

    private Map<DocumentKey, PageStatementAnalysis> analyses() {
        if (loaded == null) {
            loaded = load();
        }
        return loaded;
    }

    private Map<DocumentKey, PageStatementAnalysis> load() {
        Map<DocumentKey, PageStatementAnalysis> found = new LinkedHashMap<>();
        int skipped = read(directory.resolve(PAGES), found);
        Path documents = directory.resolve(DOCUMENTS);
        if (Files.isDirectory(documents)) {
            for (Path page : sorted(documents, null)) {
                if (Files.isDirectory(page)) {
                    skipped += read(page, found);
                }
            }
        }
        if (skipped > 0) {
            LOG.warn("Wissensgraph: {} Analyse(n) übergangen", skipped);
        }
        return found;
    }

    /** Liest alle Analysen eines Ordners; gibt die Zahl der übergangenen Dateien zurück. */
    private int read(Path folder, Map<DocumentKey, PageStatementAnalysis> found) {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        int skipped = 0;
        for (Path file : sorted(folder, "*" + SUFFIX)) {
            try {
                PageStatementAnalysis analysis = analysis(JSON.readTree(Files.readString(file,
                        StandardCharsets.UTF_8)));
                // Nur die Datei unter ihrem eigenen Namen und Ort gilt; eine umbenannte Kopie verdoppelte das Dokument.
                if (file.equals(file(analysis.document()))) {
                    found.put(analysis.document(), analysis.withResult(analysis.result().withoutConllu()));
                } else {
                    skipped++;
                }
            } catch (IOException | RuntimeException e) {
                // Jede Datei für sich: ein kaputtes Feld (Zeitstempel, Enum, fehlender Wert) sperrt nicht die übrigen.
                skipped++;
                LOG.warn("Wissensgraph: eine Analyse ist unlesbar und bleibt unberücksichtigt ({})",
                        e.getClass().getSimpleName());
            }
        }
        return skipped;
    }

    private static List<Path> sorted(Path folder, String glob) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = glob == null ? Files.newDirectoryStream(folder)
                : Files.newDirectoryStream(folder, glob)) {
            stream.forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private Path file(DocumentKey document) {
        if (document.isPage()) {
            return directory.resolve(PAGES).resolve(name(document.page()));
        }
        return documentsOf(document.page()).resolve(document.hash() + SUFFIX);
    }

    private Path documentsOf(PageKey key) {
        return directory.resolve(DOCUMENTS).resolve(DocumentKey.page(key).hash());
    }

    /** Dateiname ohne Kennungen im Klartext: {@code sha256(quelle \n seite)}, also {@link DocumentKey#hash()}. */
    static String name(PageKey key) {
        return DocumentKey.page(key).hash() + SUFFIX;
    }

    private static void deleteIfEmpty(Path folder) throws IOException {
        try {
            Files.deleteIfExists(folder);
        } catch (DirectoryNotEmptyException stillUsed) {
            // Weitere Dokumente der Seite bleiben.
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static ObjectNode json(PageStatementAnalysis analysis) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", PageStatementAnalysis.SCHEMA);
        root.put("sourceId", analysis.key().sourceId());
        root.put("pageId", analysis.key().pageId());
        // Dokumente (ADR 0026): getrennte Felder, nie eine zusammengesetzte Kennung; bei Seiten fehlen beide.
        analysis.document().attachmentId().ifPresent(id -> root.put("attachmentId", id));
        analysis.document().part().ifPresent(part -> root.put("partId", part.value()));
        root.put("spaceKey", analysis.spaceKey());
        root.put("title", analysis.title());
        root.put("revision", analysis.revision());
        root.put("inputSha256", analysis.inputSha256());
        root.put("analyzerId", analysis.analyzerId());
        root.put("analyzerVersion", analysis.analyzerVersion());
        root.put("analyzedAt", analysis.analyzedAt().toString());
        ParsedStatements result = analysis.result();
        ObjectNode node = root.putObject("result");
        ArrayNode sentences = node.putArray("sentences");
        for (ParsedStatements.Sentence sentence : result.sentences()) {
            sentences.addObject().put("index", sentence.index()).put("start", sentence.start())
                    .put("end", sentence.end());
        }
        ArrayNode mentions = node.putArray("mentions");
        for (ParsedStatements.Mention mention : result.mentions()) {
            ObjectNode entry = mentions.addObject();
            entry.put("id", mention.id());
            entry.put("sentence", mention.sentence());
            entry.put("start", mention.start());
            entry.put("end", mention.end());
            entry.put("text", mention.text());
            entry.put("headForm", mention.headForm());
            entry.put("lemma", mention.lemma());
            entry.put("kind", mention.kind().name());
            entry.put("unresolved", mention.unresolved());
            ArrayNode cases = entry.putArray("cases");
            mention.cases().stream().sorted().forEach(cases::add);
        }
        ArrayNode statements = node.putArray("statements");
        for (ParsedStatements.Statement statement : result.statements()) {
            ObjectNode entry = statements.addObject();
            entry.put("id", statement.id());
            entry.put("sentence", statement.sentence());
            entry.put("start", statement.start());
            entry.put("end", statement.end());
            entry.put("predicateText", statement.predicateText());
            entry.put("predicateLemma", statement.predicateLemma());
            entry.put("voice", statement.voice().name());
            entry.put("clause", statement.clause().name());
            entry.put("core", statement.core());
            ArrayNode arguments = entry.putArray("arguments");
            for (ParsedStatements.Argument argument : statement.arguments()) {
                arguments.addObject().put("role", argument.role().name()).put("mention", argument.mention())
                        .put("relation", argument.relation()).put("marker", argument.marker())
                        .put("priority", argument.priority().name()).put("basis", argument.basis().name())
                        .put("certain", argument.certain());
            }
            ArrayNode findings = entry.putArray("findings");
            statement.findings().stream().sorted().forEach(findings::add);
            entry.put("paragraph", statement.paragraph());
            entry.put("quality", statement.quality().name());
            ArrayNode reasons = entry.putArray("qualityReasons");
            statement.qualityReasons().forEach(reasons::add);
            entry.put("review", statement.review().name());
        }
        ArrayNode attachments = node.putArray("attachments");
        for (ParsedStatements.Attachment attachment : result.attachments()) {
            attachments.addObject().put("id", attachment.id()).put("head", attachment.head())
                    .put("dependent", attachment.dependent()).put("relation", attachment.relation())
                    .put("marker", attachment.marker()).put("kind", attachment.kind().name())
                    .put("basis", attachment.basis().name());
        }
        ArrayNode components = node.putArray("components");
        result.components().forEach(components::add);
        node.put("conllu", result.conllu());
        return root;
    }

    static PageStatementAnalysis analysis(JsonNode root) {
        if (!PageStatementAnalysis.SCHEMA.equals(root.path("schema").asString(null))) {
            throw new IllegalArgumentException("Fremdes Schema");
        }
        JsonNode node = root.path("result");
        List<ParsedStatements.Sentence> sentences = new ArrayList<>();
        for (JsonNode entry : node.path("sentences")) {
            sentences.add(new ParsedStatements.Sentence(entry.path("index").asInt(), entry.path("start").asInt(),
                    entry.path("end").asInt()));
        }
        List<ParsedStatements.Mention> mentions = new ArrayList<>();
        for (JsonNode entry : node.path("mentions")) {
            Set<String> cases = new HashSet<>();
            entry.path("cases").forEach(value -> cases.add(value.asString()));
            mentions.add(new ParsedStatements.Mention(entry.path("id").asString(), entry.path("sentence").asInt(),
                    entry.path("start").asInt(), entry.path("end").asInt(), entry.path("text").asString(),
                    text(entry, "headForm"), text(entry, "lemma"),
                    ParsedStatements.MentionKind.valueOf(entry.path("kind").asString()),
                    entry.path("unresolved").asBoolean(), cases));
        }
        List<ParsedStatements.Statement> statements = new ArrayList<>();
        for (JsonNode entry : node.path("statements")) {
            List<ParsedStatements.Argument> arguments = new ArrayList<>();
            for (JsonNode argument : entry.path("arguments")) {
                arguments.add(new ParsedStatements.Argument(
                        ParsedStatements.Role.valueOf(argument.path("role").asString()),
                        argument.path("mention").asString(), argument.path("relation").asString(),
                        text(argument, "marker"),
                        ParsedStatements.Priority.valueOf(argument.path("priority").asString()),
                        ParsedStatements.Basis.valueOf(argument.path("basis").asString()),
                        argument.path("certain").asBoolean()));
            }
            Set<String> findings = new HashSet<>();
            entry.path("findings").forEach(value -> findings.add(value.asString()));
            // Jedes neue Feld einzeln, mit Rückfall: ältere Dateien ohne Bewertung bleiben UNCERTAIN/NOT_ASSESSED
            // (und sind über die Regelversion ohnehin veraltet), unbekannte Werte brechen das Laden nicht ab.
            List<String> reasons = new ArrayList<>();
            entry.path("qualityReasons").forEach(value -> reasons.add(value.asString()));
            ParsedStatements.Quality quality = enumOr(ParsedStatements.Quality.class, entry, "quality", null);
            if (quality == null) {
                quality = ParsedStatements.Quality.UNCERTAIN;
                reasons.clear();
                reasons.add(ParsedStatements.Statement.NOT_ASSESSED);
            }
            JsonNode paragraph = entry.path("paragraph");
            ParsedStatements.Statement statement = new ParsedStatements.Statement(entry.path("id").asString(),
                    entry.path("sentence").asInt(), entry.path("start").asInt(), entry.path("end").asInt(),
                    entry.path("predicateText").asString(), text(entry, "predicateLemma"),
                    ParsedStatements.Voice.valueOf(entry.path("voice").asString()),
                    ParsedStatements.Clause.valueOf(entry.path("clause").asString()), entry.path("core").asBoolean(),
                    arguments, findings,
                    paragraph.isInt() ? paragraph.asInt() : ParsedStatements.Statement.UNKNOWN_PARAGRAPH, quality,
                    reasons, enumOr(ParsedStatements.Review.class, entry, "review",
                    ParsedStatements.Review.UNREVIEWED));
            statements.add(statement);
        }
        List<ParsedStatements.Attachment> attachments = new ArrayList<>();
        for (JsonNode entry : node.path("attachments")) {
            attachments.add(new ParsedStatements.Attachment(entry.path("id").asString(),
                    entry.path("head").asString(), entry.path("dependent").asString(),
                    entry.path("relation").asString(), text(entry, "marker"),
                    ParsedStatements.AttachmentKind.valueOf(entry.path("kind").asString()),
                    ParsedStatements.Basis.valueOf(entry.path("basis").asString())));
        }
        List<String> components = new ArrayList<>();
        node.path("components").forEach(value -> components.add(value.asString()));
        ParsedStatements result = new ParsedStatements(sentences, mentions, statements, attachments, components,
                text(node, "conllu"));
        PageKey key = new PageKey(root.path("sourceId").asString(), root.path("pageId").asString());
        String attachmentId = text(root, "attachmentId");
        String partId = text(root, "partId");
        DocumentKey document = new DocumentKey(key, Optional.ofNullable(attachmentId),
                Optional.ofNullable(partId).map(PartId::parse));
        return new PageStatementAnalysis(key, text(root, "spaceKey"), text(root, "title"),
                text(root, "revision"), root.path("inputSha256").asString(), root.path("analyzerId").asString(),
                root.path("analyzerVersion").asString(), Instant.parse(root.path("analyzedAt").asString()), result,
                document);
    }

    /** Der Aufzählungswert im Feld {@code field} oder {@code fallback}, wenn es fehlt oder unbekannt ist. */
    private static <E extends Enum<E>> E enumOr(Class<E> type, JsonNode entry, String field, E fallback) {
        JsonNode value = entry.path(field);
        if (!value.isString()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.asString());
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
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
