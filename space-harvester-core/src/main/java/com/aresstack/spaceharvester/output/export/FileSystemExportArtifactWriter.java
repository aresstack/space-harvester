package com.aresstack.spaceharvester.output.export;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.export.Conversion;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.export.ExportScope;
import com.aresstack.spaceharvester.application.export.ExportStorageFullException;
import com.aresstack.spaceharvester.application.export.ExportSummary;
import com.aresstack.spaceharvester.application.export.PageFailure;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.output.convert.PageJson;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JsonGenerator;
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
 *   <li>{@code tree.json}: Hierarchie nur aus Verweisen ({@code roots}, je Knoten {@code id} und {@code url})</li>
 *   <li>{@code manifest.json}: Vollständigkeit, Generation, Zähler, Fehler, Warnungen, Dateien mit {@code sha256},
 *       Herkunft konvertierter Dateien ({@code conversions})</li>
 *   <li>{@code assets.json}: die Bilder des Datenbestands ({@link ExportAssetManifest}), sobald sie beschafft sind;
 *       die Bytes liegen im Asset-Speicher</li>
 *   <li>{@code views/}: die Lese-Ansicht jeder Seite ({@link ExportReadingView}, #218), sobald beschafft; Dateiname
 *       wie unter {@code raw/}</li>
 *   <li>konvertierte Dateien der angefragten Formate (#30), etwa {@code pages.json} oder {@code pages.md}</li>
 * </ul>
 *
 * <p>Ein Export entsteht unter {@code <root>/.incomplete/<exportId>/} und wird erst nach dem Manifest in einem Schritt
 * nach {@code <root>/<exportId>/} verschoben. Ein vorhandenes Artefakt wird nie überschrieben.
 *
 * <p>Alle Artefakte zusammen, veröffentlichte und entstehende, belegen höchstens {@code maxTotalBytes}. Würde ein
 * Export die Grenze überschreiten, scheitert er mit {@link ExportStorageFullException}; nichts wird abgeschnitten.
 */
public class FileSystemExportArtifactWriter implements ExportArtifactWriter {

    static final String PAGES = "pages.ndjson";
    static final String TREE = "tree.json";
    static final String MANIFEST = "manifest.json";
    static final String ASSETS = "assets.json";
    static final String RAW = "raw";
    static final String VIEWS = "views";
    static final String INCOMPLETE = ".incomplete";

    private static final Pattern SAFE_EXPORT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,99}");
    /** Dateinamen konvertierter Dateien. */
    private static final String CONVERTED_NAME = "[a-z][a-z0-9-]{0,40}\\.(json|md|docx|pdf)";
    private static final Pattern SAFE_CONVERTED = Pattern.compile(CONVERTED_NAME);
    // Nur Kleinbuchstaben: Dateisysteme ohne Groß-/Kleinschreibung dürfen keine zwei Seiten zusammenlegen.
    private static final Pattern SAFE_FILE = Pattern.compile(
            "(pages\\.ndjson|tree\\.json|manifest\\.json|assets\\.json|raw/[0-9a-z][0-9a-z_-]{0,63}\\.(json|bin)"
                    + "|" + CONVERTED_NAME + ")");
    private static final Pattern SAFE_PAGE_ID = Pattern.compile("[0-9a-z][0-9a-z_-]{0,63}");

    /** Standardgrenze aller Artefakte zusammen. */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024;

    private final Path root;
    private final long maxTotalBytes;
    /** Was laufende Sitzungen bisher geschrieben haben. */
    private final AtomicLong inProgress = new AtomicLong();
    private final JsonMapper json = JsonMapper.builder().build();

    public FileSystemExportArtifactWriter(Path root) {
        this(root, DEFAULT_MAX_TOTAL_BYTES);
    }

    public FileSystemExportArtifactWriter(Path root, long maxTotalBytes) {
        if (maxTotalBytes <= 0) {
            throw new IllegalArgumentException("maxTotalBytes muss positiv sein");
        }
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.maxTotalBytes = maxTotalBytes;
    }

    @Override
    public void requireCapacity() {
        if (publishedBytes() + inProgress.get() >= maxTotalBytes) {
            throw new ExportStorageFullException(maxTotalBytes);
        }
    }

    /** Größe aller veröffentlichten Artefakte. */
    private long publishedBytes() {
        long total = 0;
        for (String exportId : published()) {
            try (Stream<Path> files = Files.walk(root.resolve(exportId))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    total += Files.size(file);
                }
            } catch (IOException | UncheckedIOException e) {
                // Gleichzeitig entfernt: zählt nicht mehr.
            }
        }
        return total;
    }

    @Override
    public Session begin(String exportId, ExportScope scope) {
        requireSafe(exportId);
        Path target = root.resolve(exportId);
        Path work = root.resolve(INCOMPLETE).resolve(exportId);
        try {
            if (Files.exists(target)) {
                throw new IllegalStateException("Export " + exportId + " ist bereits veröffentlicht");
            }
            deleteRecursively(work);
            long published = publishedBytes();
            if (published + inProgress.get() >= maxTotalBytes) {
                throw new ExportStorageFullException(maxTotalBytes);
            }
            Files.createDirectories(work.resolve(RAW));
            return new FileSession(exportId, scope, work, target, published);
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

    @Override
    public Optional<List<String>> pageIds(String exportId) {
        if (!SAFE_EXPORT_ID.matcher(exportId).matches()) {
            return Optional.empty();
        }
        Path tree = root.resolve(exportId).resolve(TREE);
        if (!Files.isRegularFile(tree) || !Files.isRegularFile(root.resolve(exportId).resolve(MANIFEST))) {
            return Optional.empty();
        }
        List<String> ids = new ArrayList<>();
        collectIds(json.readTree(tree.toFile()).path("roots"), ids);
        return Optional.of(ids);
    }

    private static void collectIds(JsonNode nodes, List<String> ids) {
        for (JsonNode node : nodes) {
            ids.add(node.path("id").asString());
            collectIds(node.path("children"), ids);
        }
    }

    @Override
    public Optional<InputStream> open(String exportId, String name) {
        if (!SAFE_EXPORT_ID.matcher(exportId).matches() || !SAFE_FILE.matcher(name).matches()) {
            return Optional.empty();
        }
        Path dir = root.resolve(exportId);
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(dir.resolve(MANIFEST)) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.newInputStream(file));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Datei des Exports " + exportId + " nicht lesbar", e);
        }
    }

    @Override
    public Optional<ConversionInput> dataset(String exportId) {
        if (!SAFE_EXPORT_ID.matcher(exportId).matches()) {
            return Optional.empty();
        }
        Path dir = root.resolve(exportId);
        if (!Files.isRegularFile(dir.resolve(MANIFEST))) {
            return Optional.empty();
        }
        return Optional.of(ExportDataset.open(exportId, dir, json));
    }

    @Override
    public void putAssets(String exportId, ExportAssetManifest manifest) {
        requireSafe(exportId);
        Path dir = root.resolve(exportId);
        if (!Files.isRegularFile(dir.resolve(MANIFEST))) {
            throw new IllegalStateException("Export " + exportId + " ist nicht veröffentlicht");
        }
        Path part = dir.resolve(ASSETS + ".part");
        try {
            try (JsonGenerator generator = json.createGenerator(Files.newOutputStream(part))) {
                writeAssets(generator, manifest);
            }
            try {
                Files.move(part, dir.resolve(ASSETS), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, dir.resolve(ASSETS), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Bildmanifest des Exports " + exportId + " nicht schreibbar", e);
        }
    }

    @Override
    public void putReadingView(String exportId, ExportReadingView view) {
        requireSafe(exportId);
        Path dir = root.resolve(exportId);
        if (!Files.isRegularFile(dir.resolve(MANIFEST))) {
            throw new IllegalStateException("Export " + exportId + " ist nicht veröffentlicht");
        }
        Path file = dir.resolve(viewFileName(view.pageId()));
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try {
            Files.createDirectories(file.getParent());
            try (JsonGenerator generator = json.createGenerator(Files.newOutputStream(part))) {
                generator.writeStartObject();
                generator.writeStringProperty("format", ExportReadingView.FORMAT);
                generator.writeStringProperty("pageId", view.pageId());
                generator.writeStringProperty("version", view.version());
                generator.writeStringProperty("status", view.status().name());
                generator.writeStringProperty("reason", view.reason());
                generator.writeStringProperty("html", view.html());
                generator.writeStringProperty("baseUri", view.baseUri());
                generator.writeStringProperty("text", view.text());
                generator.writeEndObject();
            }
            try {
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Lese-Ansicht des Exports " + exportId + " nicht schreibbar", e);
        }
    }

    /** Liest eine abgelegte Lese-Ansicht; leer, wenn keine da ist oder sie nicht zu dieser Seite gehört. */
    static Optional<ExportReadingView> readReadingView(Path dir, String pageId, JsonMapper json) {
        Path file = dir.resolve(viewFileName(pageId));
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = json.readTree(file.toFile());
            if (!ExportReadingView.FORMAT.equals(node.path("format").asString())
                    || !pageId.equals(node.path("pageId").asString())) {
                return Optional.empty();
            }
            return Optional.of(new ExportReadingView(pageId, optional(node, "version"),
                    ExportReadingView.Status.valueOf(node.path("status").asString()), optional(node, "reason"),
                    node.path("html").asString(), node.path("baseUri").asString(), node.path("text").asString()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ExportAssetManifest> assets(String exportId) {
        if (!SAFE_EXPORT_ID.matcher(exportId).matches()) {
            return Optional.empty();
        }
        Path file = root.resolve(exportId).resolve(ASSETS);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        JsonNode node = json.readTree(file.toFile());
        if (!ExportAssetManifest.FORMAT.equals(node.path("format").asString())) {
            return Optional.empty();
        }
        List<ExportAssetManifest.Entry> entries = new ArrayList<>();
        for (JsonNode entry : node.path("assets")) {
            JsonNode ref = entry.path("reference");
            ExportAssetManifest.Reference reference = new ExportAssetManifest.Reference(
                    ExportAssetManifest.Kind.ofJson(ref.path("kind").asString()), optional(ref, "attachmentId"),
                    optional(ref, "filename"), optional(ref, "src"),
                    ref.hasNonNull("index") ? ref.path("index").asInt() : null, optional(ref, "source"));
            entries.add(new ExportAssetManifest.Entry(entry.path("pageId").asString(), reference,
                    optional(entry, "attachmentId"), optional(entry, "version"), optional(entry, "title"),
                    optional(entry, "mediaType"), entry.path("size").asLong(), optional(entry, "sha256"),
                    ExportAssetManifest.Status.valueOf(entry.path("status").asString()), optional(entry, "reason"),
                    rendition(entry.path("rendition"))));
        }
        return Optional.of(new ExportAssetManifest(node.path("complete").asBoolean(false), entries));
    }

    private static ExportAssetManifest.Rendition rendition(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return node.hasNonNull("sha256") ? ExportAssetManifest.Rendition.drawn(node.path("mediaType").asString(),
                node.path("size").asLong(), node.path("sha256").asString())
                : ExportAssetManifest.Rendition.notDrawn(node.path("problem").asString());
    }

    private static String optional(JsonNode node, String name) {
        return node.hasNonNull(name) ? node.path(name).asString() : null;
    }

    private static void writeAssets(JsonGenerator generator, ExportAssetManifest manifest) {
        generator.writeStartObject();
        generator.writeStringProperty("format", ExportAssetManifest.FORMAT);
        generator.writeBooleanProperty("complete", manifest.complete());
        generator.writeName("assets");
        generator.writeStartArray();
        for (ExportAssetManifest.Entry entry : manifest.assets()) {
            ExportAssetManifest.Reference ref = entry.reference();
            generator.writeStartObject();
            generator.writeStringProperty("pageId", entry.pageId());
            generator.writeName("reference");
            generator.writeStartObject();
            generator.writeStringProperty("kind", ref.kind().json());
            if (ref.attachmentId() != null) {
                generator.writeStringProperty("attachmentId", ref.attachmentId());
            }
            if (ref.filename() != null) {
                generator.writeStringProperty("filename", ref.filename());
            }
            if (ref.src() != null) {
                generator.writeStringProperty("src", ref.src());
            }
            if (ref.index() != null) {
                generator.writeNumberProperty("index", ref.index());
            }
            if (ref.source() != null) {
                generator.writeStringProperty("source", ref.source());
            }
            generator.writeEndObject();
            generator.writeStringProperty("attachmentId", entry.attachmentId());
            generator.writeStringProperty("version", entry.version());
            generator.writeStringProperty("title", entry.title());
            generator.writeStringProperty("mediaType", entry.mediaType());
            generator.writeNumberProperty("size", entry.size());
            generator.writeStringProperty("sha256", entry.sha256());
            generator.writeStringProperty("status", entry.status().name());
            generator.writeStringProperty("reason", entry.reason());
            if (entry.rendition() != null) {
                generator.writeName("rendition");
                generator.writeStartObject();
                if (entry.rendition().drawn()) {
                    generator.writeStringProperty("mediaType", entry.rendition().mediaType());
                    generator.writeNumberProperty("size", entry.rendition().size());
                    generator.writeStringProperty("sha256", entry.rendition().sha256());
                } else {
                    generator.writeStringProperty("problem", entry.rendition().problem());
                }
                generator.writeEndObject();
            }
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    @Override
    public Set<String> published() {
        return exportIds(root);
    }

    @Override
    public Set<String> unpublished() {
        return exportIds(root.resolve(INCOMPLETE));
    }

    @Override
    public void delete(String exportId) {
        remove(exportId, root);
    }

    @Override
    public void discard(String exportId) {
        remove(exportId, root.resolve(INCOMPLETE));
    }

    private static void remove(String exportId, Path parent) {
        if (!SAFE_EXPORT_ID.matcher(exportId).matches()) {
            return;
        }
        try {
            deleteRecursively(parent.resolve(exportId));
        } catch (IOException e) {
            throw new UncheckedIOException("Export " + exportId + " nicht entfernbar", e);
        } catch (UncheckedIOException e) {
            throw new UncheckedIOException("Export " + exportId + " nicht entfernbar", e.getCause());
        }
    }

    private static Set<String> exportIds(Path directory) {
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                    .filter(name -> SAFE_EXPORT_ID.matcher(name).matches())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("Exportverzeichnis nicht lesbar", e);
        }
    }

    /** Dateiname der Payload einer Seite innerhalb von {@code raw/}; eindeutig je Seiten-ID. */
    static String rawFileName(String pageId, String mediaType) {
        String base = SAFE_PAGE_ID.matcher(pageId).matches()
                ? pageId
                : "id-" + sha256Hex(pageId.getBytes(StandardCharsets.UTF_8)).substring(0, 40);
        return base + ("application/json".equals(mediaType) ? ".json" : ".bin");
    }

    /** Datei der Lese-Ansicht einer Seite innerhalb von {@code views/}; eindeutig je Seiten-ID. */
    static String viewFileName(String pageId) {
        return VIEWS + "/" + rawFileName(pageId, "application/json");
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
        /** Adresse je geschriebener Seite für die Verweise im Baum; nur Seiten mit bekannter Adresse. */
        private final Map<String, String> urls = new HashMap<>();
        /** Belegung durch veröffentlichte Artefakte zu Beginn dieser Sitzung. */
        private final long publishedAtBegin;
        private long written;
        private final List<ExportArtifact.File> convertedFiles = new ArrayList<>();
        private final List<Conversion> conversions = new ArrayList<>();
        private boolean published;

        FileSession(String exportId, ExportScope scope, Path work, Path target, long publishedAtBegin)
                throws IOException {
            this.exportId = exportId;
            this.scope = scope;
            this.work = work;
            this.target = target;
            this.publishedAtBegin = publishedAtBegin;
            this.pages = new HashingOutputStream(Files.newOutputStream(work.resolve(PAGES)), this::reserve);
        }

        /** Zählt zu schreibende Bytes gegen die Gesamtgrenze, bevor sie geschrieben werden. */
        private void reserve(long bytes) {
            if (publishedAtBegin + inProgress.get() + bytes > maxTotalBytes) {
                throw new ExportStorageFullException(maxTotalBytes);
            }
            inProgress.addAndGet(bytes);
            written += bytes;
        }

        /** Gibt den Anteil dieser Sitzung frei; veröffentlicht zählt er ab dann über das Verzeichnis. */
        private void release() {
            inProgress.addAndGet(-written);
            written = 0;
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
            if (header.metadata().url() != null) {
                urls.put(pageId, header.metadata().url().toString());
            }
            try {
                reserve(bytes.length);
                // CREATE_NEW: zwei Seiten-IDs auf einem Dateinamen wären ein Fehler, nie ein Überschreiben.
                Files.write(work.resolve(rawName), bytes, StandardOpenOption.CREATE_NEW);
                boolean parseable = PageJson.isJson(payload.mediaType(), bytes);
                if (!parseable) {
                    warnings.add("Payload von Seite " + pageId + " ist kein lesbares JSON; nur unter " + rawName
                            + " abgelegt");
                }
                try (JsonGenerator line = json.createGenerator(new NonClosingOutputStream(pages))) {
                    line.writeStartObject();
                    PageJson.writeHeader(line, header);
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
                    PageJson.writeContent(line, payload.mediaType(), bytes);
                    line.writeEndObject();
                }
                pages.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException("Seite " + pageId + " nicht schreibbar", e);
            }
        }

        @Override
        public ExportArtifact.File converted(Conversion conversion, Content content) throws IOException {
            String name = conversion.fileName();
            if (!SAFE_CONVERTED.matcher(name).matches() || List.of(PAGES, TREE, MANIFEST, ASSETS).contains(name)
                    || conversions.stream().anyMatch(done -> done.fileName().equals(name))) {
                throw new IllegalArgumentException("Ungültiger oder doppelter Dateiname " + name);
            }
            HashingOutputStream out = new HashingOutputStream(
                    Files.newOutputStream(work.resolve(name), StandardOpenOption.CREATE_NEW), this::reserve);
            try (out) {
                content.write(new NonClosingOutputStream(out));
            }
            ExportArtifact.File file = new ExportArtifact.File(name, out.count, out.sha256());
            convertedFiles.add(file);
            conversions.add(conversion);
            return file;
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
                files.add(writeJson(TREE, generator -> writeTree(generator, tree, urls)));
                files.addAll(convertedFiles);
                List<String> allWarnings = new ArrayList<>(summary.warnings());
                allWarnings.addAll(warnings);
                writeJson(MANIFEST, generator -> writeManifest(generator, summary, allWarnings, files, conversions));
                Files.createDirectories(root);
                try {
                    Files.move(work, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(work, target);
                }
                published = true;
                release();
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
            release();
            try {
                pages.close();
                deleteRecursively(work);
            } catch (IOException e) {
                throw new UncheckedIOException("Unvollständiger Export " + exportId + " nicht entfernbar", e);
            }
        }

        private ExportArtifact.File writeJson(String name, JsonWriting content) throws IOException {
            HashingOutputStream out = new HashingOutputStream(Files.newOutputStream(work.resolve(name)),
                    this::reserve);
            try (out; JsonGenerator generator = json.createGenerator(new NonClosingOutputStream(out))) {
                content.write(generator);
            }
            return new ExportArtifact.File(name, out.count, out.sha256());
        }
    }

    private static void writeTree(JsonGenerator generator, PageTree tree, Map<String, String> urls) {
        generator.writeStartObject();
        generator.writeName("roots");
        writeNodes(generator, tree.roots(), urls);
        generator.writeEndObject();
    }

    private static void writeNodes(JsonGenerator generator, List<PageTree.Node> nodes, Map<String, String> urls) {
        generator.writeStartArray();
        for (PageTree.Node node : nodes) {
            generator.writeStartObject();
            generator.writeStringProperty("id", node.pageId());
            generator.writeStringProperty("url", urls.get(node.pageId()));
            generator.writeName("children");
            writeNodes(generator, node.children(), urls);
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    private static void writeManifest(JsonGenerator generator, ExportSummary summary, List<String> warnings,
                                      List<ExportArtifact.File> files, List<Conversion> conversions) {
        ExportScope scope = summary.scope();
        generator.writeStartObject();
        generator.writeStringProperty("schemaVersion", ExportSummary.SCHEMA_VERSION);
        generator.writeStringProperty("exportId", summary.exportId());
        generator.writeStringProperty("sourceId", scope.sourceId());
        generator.writeStringProperty("spaceKey", scope.spaceKey());
        if (scope.rootPageId() != null) {
            // Teilbaum oder Einzelseite (#186): die Startseite ist die Wurzel des Baums und das Wurzelkapitel
            generator.writeStringProperty("rootPageId", scope.rootPageId());
        }
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
        generator.writeName("conversions");
        generator.writeStartArray();
        for (Conversion conversion : conversions) {
            generator.writeStartObject();
            generator.writeStringProperty("format", conversion.format());
            generator.writeStringProperty("file", conversion.fileName());
            generator.writeStringProperty("mediaType", conversion.mediaType());
            generator.writeStringProperty("converter", conversion.converter());
            generator.writeName("input");
            generator.writeStartObject();
            generator.writeStringProperty("hashVersion", Conversion.INPUT_HASH_VERSION);
            generator.writeStringProperty("sha256", conversion.inputSha256());
            generator.writeEndObject();
            generator.writeName("parameters");
            generator.writeStartObject();
            for (Map.Entry<String, String> parameter : new TreeMap<>(conversion.parameters()).entrySet()) {
                generator.writeStringProperty(parameter.getKey(), parameter.getValue());
            }
            generator.writeEndObject();
            generator.writeEndObject();
        }
        generator.writeEndArray();
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

    private static String text(Instant instant) {
        return PageJson.text(instant);
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
        private final LongConsumer reserve;
        private String sha256;

        HashingOutputStream(OutputStream out, LongConsumer reserve) {
            super(out, FileSystemExportArtifactWriter.sha256());
            this.reserve = reserve;
        }

        @Override
        public void write(int b) throws IOException {
            reserve.accept(1);
            super.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            reserve.accept(len);
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
