package com.aresstack.spaceharvester.output.export;

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
import java.io.ByteArrayOutputStream;
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
 * Speicher.
 */
public final class ZipExportPackageReader implements ExportPackage.Reader {

    /** Grenze für {@code manifest.json}, Lese-Ansichten und Texte. */
    static final long MAX_JSON_BYTES = 256L * 1024 * 1024;

    private final JsonMapper json = JsonMapper.builder().build();
    private final long maxPayloadBytes;
    private final long maxAssetBytes;

    /**
     * @param maxPayloadBytes größte Payload einer Seite, wie der Cache sie annimmt
     * @param maxAssetBytes   größte Datei eines Bildes oder Anhangs, wie die Asset-Ablage sie annimmt
     */
    public ZipExportPackageReader(long maxPayloadBytes, long maxAssetBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxAssetBytes = maxAssetBytes;
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
            Map<String, String> textFiles = new HashMap<>();
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
                for (JsonNode attachment : tree("attachments.json", MAX_JSON_BYTES).path("attachments")) {
                    if (attachment.hasNonNull("path") && attachment.hasNonNull("sha256")) {
                        String sha = attachment.path("sha256").asString();
                        files.putIfAbsent(sha, attachment.path("path").asString());
                        if (attachment.hasNonNull("textPath")) {
                            textFiles.putIfAbsent(sha, attachment.path("textPath").asString());
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
                public Optional<byte[]> rendition(ExportAssetManifest.Entry entry) {
                    if (entry.rendition() == null || !entry.rendition().drawn()) {
                        return Optional.empty();
                    }
                    return checked(renditions.get(entry.rendition().sha256()), entry.rendition().sha256());
                }

                @Override
                public Optional<String> text(ExportAssetManifest.Entry entry) {
                    String path = entry.stored() ? textFiles.get(entry.sha256()) : null;
                    if (path == null || zip.getEntry(path) == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new String(read(path, MAX_JSON_BYTES), StandardCharsets.UTF_8));
                }
            };
        }

        /** Die Datei unter {@code path}, nur wenn sie zu {@code sha256} passt. */
        private Optional<byte[]> checked(String path, String sha256) {
            if (path == null || sha256 == null || zip.getEntry(path) == null) {
                return Optional.empty();
            }
            byte[] bytes = read(path, maxAssetBytes);
            return sha256(bytes).equals(sha256) ? Optional.of(bytes) : Optional.empty();
        }

        private JsonNode tree(String path, long limit) {
            try {
                return json.readTree(read(path, limit));
            } catch (JacksonException e) {
                throw new ExportPackageException(ExportPackageException.DAMAGED, path + " ist nicht lesbar", e);
            }
        }

        /** Liest einen Eintrag ganz, höchstens {@code limit} Bytes; mehr ist ein Fehler, nie gekürzt. */
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
