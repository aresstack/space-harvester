package com.aresstack.spaceharvester.output.export;

import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.export.ExportScope;
import com.aresstack.spaceharvester.application.export.ExportSummary;
import com.aresstack.spaceharvester.application.export.PageFailure;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Legt Exportartefakte im lokalen Dateisystem ab, je Export ein Verzeichnis {@code <root>/<exportId>/}:
 *
 * <ul>
 *   <li>{@code pages.ndjson}: flaches Seiten-Array, eine Zeile je Seite mit {@code header} (aktuelle Kopfdaten),
 *       {@code raw} (Datei, Größe, {@code sha256}, Revision und Abrufzeit der Payload) und {@code content} (die native
 *       JSON-Antwort inhaltlich gleich, nicht bytegleich)</li>
 *   <li>{@code raw/}: die Payload jeder Seite Byte für Byte, Dateiname aus der Seiten-ID</li>
 *   <li>{@code tree.json}: Hierarchie nur aus IDs ({@code roots})</li>
 *   <li>{@code manifest.json}: Vollständigkeit, Generation, Zähler, Fehler, Warnungen, Dateien mit {@code sha256}</li>
 * </ul>
 *
 * <p>Ein Export entsteht unter {@code <root>/.incomplete/<exportId>/} und wird erst nach dem Manifest in einem Schritt
 * nach {@code <root>/<exportId>/} verschoben. Ein vorhandenes Artefakt wird nie überschrieben.
 */
public class FileSystemExportArtifactWriter implements ExportArtifactWriter {

    static final String PAGES = "pages.ndjson";
    static final String TREE = "tree.json";
    static final String MANIFEST = "manifest.json";
    static final String RAW = "raw";

    private static final Pattern SAFE_EXPORT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,99}");
    // Nur Kleinbuchstaben: Dateisysteme ohne Groß-/Kleinschreibung dürfen keine zwei Seiten zusammenlegen.
    private static final Pattern SAFE_PAGE_ID = Pattern.compile("[0-9a-z][0-9a-z_-]{0,63}");

    private final Path root;
    private final JsonMapper json = JsonMapper.builder().build();

    public FileSystemExportArtifactWriter(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public Session begin(String exportId, ExportScope scope) {
        requireSafe(exportId);
        Path target = root.resolve(exportId);
        Path work = root.resolve(".incomplete").resolve(exportId);
        try {
            if (Files.exists(target)) {
                throw new IllegalStateException("Export " + exportId + " ist bereits veröffentlicht");
            }
            deleteRecursively(work);
            Files.createDirectories(work.resolve(RAW));
            return new FileSession(exportId, scope, work, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Exportverzeichnis für " + exportId + " nicht anlegbar", e);
        }
    }

    @Override
    public Optional<ExportArtifact> find(String exportId) {
        if (!SAFE_EXPORT_ID.matcher(exportId).matches()) {
            return Optional.empty();
        }
        Path manifest = root.resolve(exportId).resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        JsonNode node = json.readTree(manifest.toFile());
        List<ExportArtifact.File> files = new ArrayList<>();
        for (JsonNode file : node.path("files")) {
            files.add(new ExportArtifact.File(file.path("name").asString(), file.path("bytes").asLong(),
                    file.path("sha256").asString()));
        }
        return Optional.of(new ExportArtifact(exportId, node.path("complete").asBoolean(false), files));
    }

    /** Dateiname der Payload einer Seite innerhalb von {@code raw/}; eindeutig je Seiten-ID. */
    static String rawFileName(String pageId, String mediaType) {
        String base = SAFE_PAGE_ID.matcher(pageId).matches()
                ? pageId
                : "id-" + sha256Hex(pageId.getBytes(StandardCharsets.UTF_8)).substring(0, 40);
        return base + ("application/json".equals(mediaType) ? ".json" : ".bin");
    }

    private static void requireSafe(String exportId) {
        if (!SAFE_EXPORT_ID.matcher(exportId).matches()) {
            throw new IllegalArgumentException("Ungültige Export-ID");
        }
    }

    private final class FileSession implements Session {

        private final String exportId;
        private final ExportScope scope;
        private final Path work;
        private final Path target;
        private final HashingOutputStream pages;
        private final List<String> warnings = new ArrayList<>();
        private boolean published;

        FileSession(String exportId, ExportScope scope, Path work, Path target) throws IOException {
            this.exportId = exportId;
            this.scope = scope;
            this.work = work;
            this.target = target;
            this.pages = new HashingOutputStream(Files.newOutputStream(work.resolve(PAGES)));
        }

        @Override
        public void page(PageHeader header, PageSnapshot snapshot) {
            if (!header.key().equals(snapshot.key())) {
                throw new IllegalArgumentException("Kopfdaten und Snapshot gehören zu verschiedenen Seiten");
            }
            String pageId = header.key().pageId();
            NativePayload payload = snapshot.payload();
            byte[] bytes = payload.bytes();
            String rawName = RAW + "/" + rawFileName(pageId, payload.mediaType());
            try {
                // CREATE_NEW: zwei Seiten-IDs auf einem Dateinamen wären ein Fehler, nie ein Überschreiben.
                Files.write(work.resolve(rawName), bytes, StandardOpenOption.CREATE_NEW);
                boolean parseable = isJson(payload.mediaType(), bytes);
                if (!parseable) {
                    warnings.add("Payload von Seite " + pageId + " ist kein lesbares JSON; nur unter " + rawName
                            + " abgelegt");
                }
                try (JsonGenerator line = json.createGenerator(new NonClosingOutputStream(pages))) {
                    line.writeStartObject();
                    writeHeader(line, header);
                    line.writeName("raw");
                    line.writeStartObject();
                    line.writeStringProperty("file", rawName);
                    line.writeStringProperty("mediaType", payload.mediaType());
                    line.writeStringProperty("nativeFormat", payload.nativeFormat());
                    line.writeNumberProperty("bytes", bytes.length);
                    line.writeStringProperty("sha256", payload.rawSha256());
                    line.writeStringProperty("revision", snapshot.revision());
                    line.writeStringProperty("retrievedAt", text(snapshot.retrievedAt()));
                    line.writeEndObject();
                    line.writeName("content");
                    if (parseable) {
                        try (JsonParser parser = json.createParser(bytes)) {
                            parser.nextToken();
                            line.copyCurrentStructureExact(parser);
                        }
                    } else {
                        line.writeNull();
                    }
                    line.writeEndObject();
                }
                pages.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException("Seite " + pageId + " nicht schreibbar", e);
            }
        }

        @Override
        public ExportArtifact publish(PageTree tree, ExportSummary summary) {
            if (!summary.exportId().equals(exportId) || !summary.scope().equals(scope)) {
                throw new IllegalArgumentException("Zusammenfassung gehört nicht zu diesem Export");
            }
            try {
                pages.close();
                List<ExportArtifact.File> files = new ArrayList<>();
                files.add(new ExportArtifact.File(PAGES, pages.count, pages.sha256()));
                files.add(writeJson(TREE, generator -> writeTree(generator, tree)));
                List<String> allWarnings = new ArrayList<>(summary.warnings());
                allWarnings.addAll(warnings);
                writeJson(MANIFEST, generator -> writeManifest(generator, summary, allWarnings, files));
                Files.createDirectories(root);
                try {
                    Files.move(work, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(work, target);
                }
                published = true;
                return new ExportArtifact(exportId, summary.complete(), files);
            } catch (FileAlreadyExistsException e) {
                throw new IllegalStateException("Export " + exportId + " ist bereits veröffentlicht", e);
            } catch (IOException e) {
                throw new UncheckedIOException("Export " + exportId + " nicht veröffentlichbar", e);
            }
        }

        @Override
        public void close() {
            if (published) {
                return;
            }
            try {
                pages.close();
                deleteRecursively(work);
            } catch (IOException e) {
                throw new UncheckedIOException("Unvollständiger Export " + exportId + " nicht entfernbar", e);
            }
        }

        private ExportArtifact.File writeJson(String name, JsonWriting content) throws IOException {
            HashingOutputStream out = new HashingOutputStream(Files.newOutputStream(work.resolve(name)));
            try (out; JsonGenerator generator = json.createGenerator(new NonClosingOutputStream(out))) {
                content.write(generator);
            }
            return new ExportArtifact.File(name, out.count, out.sha256());
        }
    }

    private static void writeHeader(JsonGenerator line, PageHeader header) {
        PageMetadata metadata = header.metadata();
        line.writeName("header");
        line.writeStartObject();
        line.writeStringProperty("sourceId", header.key().sourceId());
        line.writeStringProperty("pageId", header.key().pageId());
        line.writeStringProperty("spaceKey", metadata.partitionKey());
        line.writeStringProperty("title", metadata.title());
        line.writeStringProperty("parentPageId", metadata.parentPageId());
        line.writeStringProperty("version", metadata.version());
        line.writeStringProperty("modifiedAt", text(metadata.modifiedAt()));
        line.writeName("attributes");
        line.writeStartObject();
        for (Map.Entry<String, String> attribute : metadata.attributes().entrySet()) {
            line.writeStringProperty(attribute.getKey(), attribute.getValue());
        }
        line.writeEndObject();
        line.writeStringProperty("metadataHash", header.metadataHash());
        line.writeStringProperty("attributesHash", header.attributesHash());
        line.writeStringProperty("observedAt", text(header.observedAt()));
        line.writeEndObject();
    }

    private static void writeTree(JsonGenerator generator, PageTree tree) {
        generator.writeStartObject();
        generator.writeName("roots");
        writeNodes(generator, tree.roots());
        generator.writeEndObject();
    }

    private static void writeNodes(JsonGenerator generator, List<PageTree.Node> nodes) {
        generator.writeStartArray();
        for (PageTree.Node node : nodes) {
            generator.writeStartObject();
            generator.writeStringProperty("id", node.pageId());
            generator.writeName("children");
            writeNodes(generator, node.children());
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    private static void writeManifest(JsonGenerator generator, ExportSummary summary, List<String> warnings,
                                      List<ExportArtifact.File> files) {
        ExportScope scope = summary.scope();
        generator.writeStartObject();
        generator.writeStringProperty("schemaVersion", ExportSummary.SCHEMA_VERSION);
        generator.writeStringProperty("exportId", summary.exportId());
        generator.writeStringProperty("sourceId", scope.sourceId());
        generator.writeStringProperty("spaceKey", scope.spaceKey());
        generator.writeBooleanProperty("complete", summary.complete());
        generator.writeName("cache");
        generator.writeStartObject();
        generator.writeStringProperty("generation", scope.generation().id());
        generator.writeStringProperty("state", scope.generation().state().name());
        generator.writeStringProperty("syncedAt", text(scope.generation().syncedAt()));
        generator.writeStringProperty("lastCheckedAt", text(scope.generation().lastCheckedAt()));
        generator.writeEndObject();
        generator.writeStringProperty("startedAt", text(summary.startedAt()));
        generator.writeStringProperty("finishedAt", text(summary.finishedAt()));
        generator.writeName("counts");
        generator.writeStartObject();
        generator.writeNumberProperty("pages", summary.pagesWritten());
        generator.writeNumberProperty("failedPages", summary.failures().size());
        generator.writeEndObject();
        generator.writeName("files");
        generator.writeStartArray();
        for (ExportArtifact.File file : files) {
            generator.writeStartObject();
            generator.writeStringProperty("name", file.name());
            generator.writeNumberProperty("bytes", file.bytes());
            generator.writeStringProperty("sha256", file.sha256());
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeStringProperty("rawDirectory", RAW + "/");
        generator.writeName("failures");
        generator.writeStartArray();
        for (PageFailure failure : summary.failures()) {
            generator.writeStartObject();
            generator.writeStringProperty("pageId", failure.pageId());
            generator.writeStringProperty("code", failure.code());
            generator.writeStringProperty("message", failure.message());
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeName("warnings");
        generator.writeStartArray();
        for (String warning : warnings) {
            generator.writeString(warning);
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    /** Vollständig lesbares JSON mit genau einem Wert; nur dann wird es als {@code content} eingebettet. */
    private boolean isJson(String mediaType, byte[] payload) {
        if (!"application/json".equals(mediaType)) {
            return false;
        }
        try (JsonParser parser = json.createParser(payload)) {
            if (parser.nextToken() == null) {
                return false;
            }
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (JacksonException e) {
            return false;
        }
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path each : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.delete(each);
                } catch (NoSuchFileException ignored) {
                    // bereits entfernt
                }
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    @FunctionalInterface
    private interface JsonWriting {
        void write(JsonGenerator generator);
    }

    /** Zählt und hasht alles, was geschrieben wird. */
    private static final class HashingOutputStream extends DigestOutputStream {

        long count;
        private String sha256;

        HashingOutputStream(OutputStream out) {
            super(out, FileSystemExportArtifactWriter.sha256());
        }

        @Override
        public void write(int b) throws IOException {
            super.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            count += len;
        }

        String sha256() {
            if (sha256 == null) {
                sha256 = "sha256:" + HexFormat.of().formatHex(getMessageDigest().digest());
            }
            return sha256;
        }
    }

    /** Lässt den Generator schließen, ohne die Datei zu schließen. */
    private static final class NonClosingOutputStream extends FilterOutputStream {

        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
