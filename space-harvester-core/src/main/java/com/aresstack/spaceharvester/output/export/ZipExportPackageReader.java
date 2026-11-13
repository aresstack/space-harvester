package com.aresstack.spaceharvester.output.export;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.Sha256CheckedInputStream;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.export.ExportPackage;
import com.aresstack.spaceharvester.application.export.ExportPackageException;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.output.convert.ExportFileJson;
import com.aresstack.spaceharvester.output.convert.JsonPackageConverter;
import com.aresstack.spaceharvester.output.convert.PageJson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Liest ein JSON-Paket {@code export.zip} ab {@code json-package/3} ({@link JsonPackageConverter}) für den Import.
 *
 * <p>Das Paket bleibt eine ZIP-Datei; gelesen wird je Eintrag über seinen Namen, nie entpackt, daher kann kein Eintrag
 * außerhalb landen. Jede Datei ist begrenzt (Payload wie der Cache, Bilder und Anhänge wie die Asset-Ablage) und wird
 * gegen ihre Prüfsumme aus dem Paket geprüft. {@code pages.json} wird als Strom gelesen, nur die Kopfdaten bleiben im
 * Speicher; Bilder und Anhänge gibt es auch als Strom ({@link ExportAssets#open}).
 */
public final class ZipExportPackageReader implements ExportPackage.Reader {

    /** Grenze für {@code manifest.json}, Lese-Ansichten und Texte. */
    static final long MAX_JSON_BYTES = 256L * 1024 * 1024;
    /** Höchstens so viel Text wird je Anhang eingelesen (eigener Text und Teile zusammen). */
    static final long MAX_TEXT_BYTES = 256L * 1024 * 1024;

    private final JsonMapper json = JsonMapper.builder().build();
    private final long maxPayloadBytes;
    private final long maxAssetBytes;
    private final long maxTextBytes;

    /**
     * @param maxPayloadBytes größte Payload einer Seite, wie der Cache sie annimmt
     * @param maxAssetBytes   größte Datei eines Bildes oder Anhangs, wie die Asset-Ablage sie annimmt
     */
    public ZipExportPackageReader(long maxPayloadBytes, long maxAssetBytes) {
        this(maxPayloadBytes, maxAssetBytes, MAX_TEXT_BYTES);
    }

    /** Wie oben; {@code maxTextBytes}: höchstens so viel Text je Anhang wird eingelesen, der Rest gekürzt. */
    public ZipExportPackageReader(long maxPayloadBytes, long maxAssetBytes, long maxTextBytes) {
        if (maxTextBytes < 1) {
            throw new IllegalArgumentException("maxTextBytes muss positiv sein");
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxAssetBytes = maxAssetBytes;
        this.maxTextBytes = maxTextBytes;
    }

    @Override
    public ExportPackage open(Path file) {
        Objects.requireNonNull(file, "file");
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8);
        } catch (ZipException e) {
            throw new ExportPackageException(ExportPackageException.NOT_A_PACKAGE, "Die Datei ist kein ZIP-Paket", e);
        } catch (IOException e) {
            throw new ExportPackageException(ExportPackageException.NOT_A_PACKAGE, "Die Datei ist nicht lesbar", e);
        }
        try {
            return new Opened(zip);
        } catch (RuntimeException e) {
            try {
                zip.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private final class Opened implements ExportPackage {

        private final ZipFile zip;
        private final JsonNode manifest;
        private final String sourceId;
        private final List<Page> pages = new ArrayList<>();
        private final Map<String, Entry> entries = new HashMap<>();
        private final ExportAssets assets;

        Opened(ZipFile zip) {
            this.zip = zip;
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry == null) {
                throw zip.getEntry("pages.json") != null
                        ? new ExportPackageException(ExportPackageException.UNSUPPORTED,
                        "Das Paket stammt von einer älteren Version ohne Rohdaten; bitte neu exportieren")
                        : new ExportPackageException(ExportPackageException.NOT_A_PACKAGE,
                        "Das ZIP enthält kein JSON-Paket (manifest.json fehlt)");
            }
            this.manifest = tree("manifest.json", MAX_JSON_BYTES);
            if (!JsonPackageConverter.PACKAGE_FORMAT.equals(manifest.path("format").asString())) {
                throw new ExportPackageException(ExportPackageException.UNSUPPORTED,
                        "Unbekanntes Paketformat in manifest.json");
            }
            this.sourceId = required(manifest, "sourceId");
            String spaceKey = required(manifest, "spaceKey");
            Map<String, PageHeader> headers = headers();
            for (JsonNode node : manifest.path("pages")) {
                String pageId = required(node, "pageId");
                PageHeader header = headers.get(pageId);
                if (header == null) {
                    throw damaged("pages.json enthält Seite " + pageId + " nicht");
                }
                PageMetadata read = header.metadata();
                if (!spaceKey.equals(read.partitionKey())) {
                    // Sonst landete die Seite in einem anderen Space, als das Paket und die Rückfrage nennen.
                    throw damaged("Seite " + pageId + " liegt nicht im Space " + spaceKey);
                }
                String resourceId = required(node, "resourceId");
                PageMetadata current = new PageMetadata(pageId, resourceId, read.title(), read.partitionKey(),
                        read.parentPageId(), read.version(), read.modifiedAt(), read.url(), read.attributes());
                // Dieselben Kopfdaten wie beim Export, sonst ist das Paket verändert.
                if (!required(node, "metadataHash").equals(MetadataHash.of(sourceId, current))
                        || !required(node, "attributesHash").equals(AttributesHash.of(sourceId, current))) {
                    throw damaged("Kopfdaten der Seite " + pageId + " passen nicht zu ihrem Hash");
                }
                // Nach einem Abgleich nur der Kopfdaten kann die Payload älter sein als die Kopfdaten. Die Seite kommt
                // dann mit der Version ihrer Payload in den Cache, damit der nächste Abgleich den Inhalt neu lädt,
                // statt alte Bytes als neue Version zu bestätigen.
                String revision = node.has("revision")
                        ? (node.hasNonNull("revision") ? node.path("revision").asString() : null) : read.version();
                PageMetadata metadata = Objects.equals(revision, read.version()) ? current
                        : new PageMetadata(pageId, resourceId, read.title(), read.partitionKey(), read.parentPageId(),
                        revision, read.modifiedAt(), read.url(), read.attributes());
                String metadataHash = MetadataHash.of(sourceId, metadata);
                String attributesHash = AttributesHash.of(sourceId, metadata);
                Instant retrievedAt = instant(node, "retrievedAt");
                if (entries.put(pageId, new Entry(metadata, metadataHash, attributesHash, retrievedAt,
                        required(node, "mediaType"), required(node, "nativeFormat"), required(node, "rawSha256"),
                        node.path("size").asLong(-1), required(node, "raw"),
                        node.hasNonNull("view") ? node.path("view").asString() : null)) != null) {
                    throw damaged("Seite " + pageId + " steht doppelt in manifest.json");
                }
                pages.add(new Page(metadata, metadataHash, attributesHash, node.path("depth").asInt(0),
                        retrievedAt));
            }
            if (manifest.path("pageCount").asInt(pages.size()) != pages.size()) {
                throw damaged("manifest.json nennt eine andere Seitenzahl");
            }
            this.assets = packageAssets();
        }

        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public String spaceKey() {
            return manifest.path("spaceKey").asString();
        }

        @Override
        public String spaceName() {
            return manifest.hasNonNull("spaceName") ? manifest.path("spaceName").asString() : spaceKey();
        }

        @Override
        public String rootPageId() {
            return manifest.hasNonNull("rootPageId") ? manifest.path("rootPageId").asString() : null;
        }

        @Override
        public List<Page> pages() {
            return List.copyOf(pages);
        }

        @Override
        public PageSnapshot snapshot(String pageId) {
            Entry entry = entries.get(pageId);
            if (entry == null) {
                throw damaged("Seite " + pageId + " fehlt im Paket");
            }
            String expected = "raw/" + ExportFileJson.rawFileName(pageId, entry.mediaType());
            if (!expected.equals(entry.raw())) {
                throw damaged("Rohdaten der Seite " + pageId + " liegen nicht unter " + expected);
            }
            byte[] bytes = read(expected, maxPayloadBytes);
            NativePayload payload = new NativePayload(bytes, entry.mediaType(), entry.nativeFormat());
            if (!payload.rawSha256().equals(entry.rawSha256())
                    || (entry.size() >= 0 && entry.size() != bytes.length)) {
                throw damaged(expected + " passt nicht zu rawSha256");
            }
            return new PageSnapshot(new PageKey(sourceId, pageId), entry.metadata(), entry.metadataHash(),
                    entry.attributesHash(), payload, entry.retrievedAt());
        }

        @Override
        public Optional<ExportReadingView> readingView(String pageId) {
            Entry entry = entries.get(pageId);
            if (entry == null || entry.view() == null) {
                return Optional.empty();
            }
            return ExportFileJson.readReadingView(tree(entry.view(), MAX_JSON_BYTES), pageId);
        }

        @Override
        public ExportAssets assets() {
            return assets;
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Paket nicht schließbar", e);
            }
        }

        /** Kopfdaten aus {@code pages.json}, als Strom gelesen; der Inhalt je Seite wird übersprungen. */
        private Map<String, PageHeader> headers() {
            ZipEntry entry = zip.getEntry("pages.json");
            if (entry == null) {
                throw damaged("pages.json fehlt");
            }
            Map<String, PageHeader> headers = new LinkedHashMap<>();
            try (InputStream in = zip.getInputStream(entry); JsonParser parser = json.createParser(in)) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw damaged("pages.json ist kein Array");
                }
                while (parser.nextToken() == JsonToken.START_OBJECT) {
                    PageHeader header = null;
                    while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        String name = parser.currentName();
                        parser.nextToken();
                        if ("header".equals(name)) {
                            header = PageJson.readHeader((JsonNode) parser.readValueAsTree());
                        } else {
                            parser.skipChildren();
                        }
                    }
                    if (header == null) {
                        throw damaged("Eine Seite in pages.json hat keine Kopfdaten");
                    }
                    headers.put(header.key().pageId(), header);
                }
            } catch (IOException | JacksonException | DateTimeParseException | IllegalArgumentException e) {
                throw new ExportPackageException(ExportPackageException.DAMAGED, "pages.json ist nicht lesbar", e);
            }
            return headers;
        }

        /** Bildmanifest aus {@code manifest.json}, Dateien über ihre Prüfsumme aus {@code assets.json}. */
        private ExportAssets packageAssets() {
            JsonNode node = manifest.path("assets");
            if (!node.isObject()) {
                return ExportAssets.NONE;
            }
            Optional<ExportAssetManifest> assetManifest;
            try {
                assetManifest = ExportFileJson.readAssets(node);
            } catch (RuntimeException e) {
                throw new ExportPackageException(ExportPackageException.DAMAGED,
                        "Bildmanifest in manifest.json ist nicht lesbar", e);
            }
            if (assetManifest.isEmpty()) {
                return ExportAssets.NONE;
            }
            Map<String, String> files = new HashMap<>();
            Map<String, String> renditions = new HashMap<>();
            Map<String, List<String>> textFiles = new HashMap<>();
            if (zip.getEntry("assets.json") != null) {
                for (JsonNode asset : tree("assets.json", MAX_JSON_BYTES).path("assets")) {
                    if (asset.hasNonNull("path") && asset.hasNonNull("sha256")) {
                        files.putIfAbsent(asset.path("sha256").asString(), asset.path("path").asString());
                    }
                    JsonNode rendition = asset.path("rendition");
                    if (rendition.hasNonNull("path") && rendition.hasNonNull("sha256")) {
                        renditions.putIfAbsent(rendition.path("sha256").asString(),
                                rendition.path("path").asString());
                    }
                }
            }
            if (zip.getEntry("attachments.json") != null) {
                // attachments/v1 und v2: in v2 folgen einem Behälter seine enthaltenen Dateien (mit partId); sein Text
                // ist dann der eigene und danach der seiner Teile in Verzeichnisreihenfolge (ADR 0026, AP4c). Teile
                // werden nie zu eigenen Assets; eine strukturierte Extraktion entsteht beim Einlesen nicht.
                List<String> container = null;
                for (JsonNode attachment : tree("attachments.json", MAX_JSON_BYTES).path("attachments")) {
                    if (attachment.hasNonNull("partId")) {
                        if (container != null && attachment.hasNonNull("textPath")) {
                            container.add(attachment.path("textPath").asString());
                        }
                        continue;
                    }
                    container = null;
                    if (attachment.hasNonNull("path") && attachment.hasNonNull("sha256")) {
                        String sha = attachment.path("sha256").asString();
                        files.putIfAbsent(sha, attachment.path("path").asString());
                        if (!textFiles.containsKey(sha)) {
                            container = new ArrayList<>();
                            textFiles.put(sha, container);
                            if (attachment.hasNonNull("textPath")) {
                                container.add(attachment.path("textPath").asString());
                            }
                        }
                    }
                }
            }
            ExportAssetManifest known = assetManifest.get();
            return new ExportAssets() {
                @Override
                public Optional<ExportAssetManifest> manifest() {
                    return Optional.of(known);
                }

                @Override
                public Optional<byte[]> content(ExportAssetManifest.Entry entry) {
                    if (!entry.stored()) {
                        return Optional.empty();
                    }
                    if (entry.reference().kind() == ExportAssetManifest.Kind.MERMAID) {
                        return Optional.ofNullable(entry.reference().source())
                                .map(source -> source.getBytes(StandardCharsets.UTF_8));
                    }
                    return checked(files.get(entry.sha256()), entry.sha256());
                }

                @Override
                public Optional<InputStream> open(ExportAssetManifest.Entry entry) {
                    if (!entry.stored() || entry.reference().kind() == ExportAssetManifest.Kind.MERMAID) {
                        return content(entry).map(ByteArrayInputStream::new);
                    }
                    return stream(files.get(entry.sha256()), entry.sha256());
                }

                @Override
                public Optional<byte[]> rendition(ExportAssetManifest.Entry entry) {
                    if (entry.rendition() == null || !entry.rendition().drawn()) {
                        return Optional.empty();
                    }
                    return checked(renditions.get(entry.rendition().sha256()), entry.rendition().sha256());
                }

                @Override
                public Optional<String> text(ExportAssetManifest.Entry entry) {
                    return documentText(entry).map(DocumentText::text);
                }

                @Override
                public Optional<DocumentText> documentText(ExportAssetManifest.Entry entry) {
                    List<String> paths = entry.stored() ? textFiles.get(entry.sha256()) : null;
                    if (paths == null) {
                        return Optional.empty();
                    }
                    // Der Export schreibt Texte ohne Zeichengrenze; eingelesen wird höchstens MAX_TEXT_BYTES je
                    // Anhang (eigener Text und Teile zusammen), der Rest bleibt weg, statt das Paket abzulehnen.
                    // Gekürzt wird nie still: der Text sagt es selbst (truncated, gelesene Bytes); das Paket bleibt.
                    List<String> texts = new ArrayList<>();
                    long budget = maxTextBytes;
                    long read = 0;
                    boolean truncated = false;
                    for (String path : paths) {
                        ZipEntry file = zip.getEntry(path);
                        if (file == null || file.isDirectory()) {
                            continue;
                        }
                        if (budget <= 0) {
                            truncated |= file.getSize() != 0;
                            continue;
                        }
                        byte[] bytes = prefix(file, budget + 1);
                        if (bytes.length > budget) {
                            bytes = java.util.Arrays.copyOf(bytes, (int) budget);
                            truncated = true;
                        }
                        budget -= bytes.length;
                        read += bytes.length;
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        if (!text.isBlank()) {
                            texts.add(text);
                        }
                    }
                    return texts.isEmpty() ? Optional.empty()
                            : Optional.of(new DocumentText(String.join("\n\n", texts), truncated, read));
                }
            };
        }

        /** Die Datei unter {@code path}, nur wenn sie zu {@code sha256} passt. */
        private Optional<byte[]> checked(String path, String sha256) {
            if (path == null || sha256 == null || zip.getEntry(path) == null) {
                return Optional.empty();
            }
            // Als Bytes höchstens so groß wie Dateien im Speicher, auch ohne Grenze der Ablage; größere als Strom.
            byte[] bytes = read(path, Math.min(maxAssetBytes, Assets.MAX_IN_MEMORY_BYTES));
            return sha256(bytes).equals(sha256) ? Optional.of(bytes) : Optional.empty();
        }

        /**
         * Wie {@link #checked}, aber als Strom, ohne die Datei ganz in den Speicher zu lesen (Angelo 11.11.2026): die
         * Prüfsumme prüft der Strom am Ende, die Grenze der Asset-Ablage gilt schon beim Lesen.
         */
        private Optional<InputStream> stream(String path, String sha256) {
            if (path == null || sha256 == null) {
                return Optional.empty();
            }
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            if (entry.getSize() > maxAssetBytes) {
                throw tooLarge(path, maxAssetBytes);
            }
            InputStream in;
            try {
                in = zip.getInputStream(entry);
            } catch (IOException e) {
                throw new ExportPackageException(ExportPackageException.DAMAGED, path + " ist nicht lesbar", e);
            }
            return Optional.of(new Sha256CheckedInputStream(new Limited(in, path, maxAssetBytes), sha256,
                    path + " passt nicht zu seiner Prüfsumme"));
        }

        private JsonNode tree(String path, long limit) {
            try {
                return json.readTree(read(path, limit));
            } catch (JacksonException e) {
                throw new ExportPackageException(ExportPackageException.DAMAGED, path + " ist nicht lesbar", e);
            }
        }

        /** Liest einen Eintrag ganz, höchstens {@code limit} Bytes; mehr ist ein Fehler, nie gekürzt. */
        /** Die ersten höchstens {@code limit} Bytes einer Datei des Pakets. */
        private byte[] prefix(ZipEntry entry, long limit) {
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 8));
            } catch (IOException e) {
                throw new ExportPackageException(ExportPackageException.DAMAGED, entry.getName()
                        + " ist nicht lesbar", e);
            }
        }

        private byte[] read(String path, long limit) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.isDirectory()) {
                throw damaged(path + " fehlt im Paket");
            }
            if (entry.getSize() > limit) {
                throw tooLarge(path, limit);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(
                        (int) Math.max(0, Math.min(entry.getSize(), 1 << 20)));
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                    total += n;
                    if (total > limit) {
                        throw tooLarge(path, limit);
                    }
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            } catch (IOException e) {
                throw new ExportPackageException(ExportPackageException.DAMAGED, path + " ist nicht lesbar", e);
            }
        }
    }

    /** Ein Eintrag als Strom, der über {@code limit} Bytes mit {@code TOO_LARGE} abbricht, nie gekürzt. */
    private static final class Limited extends FilterInputStream {

        private final String path;
        private final long limit;
        private long total;

        Limited(InputStream in, String path, long limit) {
            super(in);
            this.path = path;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            int n = super.read(bytes, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) {
            total += n;
            if (total > limit) {
                throw tooLarge(path, limit);
            }
        }
    }

    /** Angaben einer Seite aus {@code manifest.json}. */
    private record Entry(PageMetadata metadata, String metadataHash, String attributesHash, Instant retrievedAt,
                         String mediaType, String nativeFormat, String rawSha256, long size, String raw,
                         String view) {
    }

    private static String required(JsonNode node, String name) {
        if (!node.hasNonNull(name) || node.path(name).asString().isBlank()) {
            throw damaged("manifest.json: " + name + " fehlt");
        }
        return node.path(name).asString();
    }

    private static Instant instant(JsonNode node, String name) {
        try {
            return Instant.parse(required(node, name));
        } catch (DateTimeParseException e) {
            throw damaged("manifest.json: " + name + " ist kein Zeitpunkt");
        }
    }

    private static ExportPackageException damaged(String message) {
        return new ExportPackageException(ExportPackageException.DAMAGED, message);
    }

    private static ExportPackageException tooLarge(String path, long limit) {
        return new ExportPackageException(ExportPackageException.TOO_LARGE, path + " ist größer als " + limit
                + " Bytes");
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }
}
