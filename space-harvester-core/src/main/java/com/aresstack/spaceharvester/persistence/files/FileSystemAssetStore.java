package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetStoreFullException;
import com.aresstack.spaceharvester.application.assets.AssetText;
import com.aresstack.spaceharvester.application.assets.ImageDescription;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Legt geerntete Anhänge im Dateisystem ab (#19):
 *
 * <ul>
 *   <li>{@code blobs/<2 Zeichen>/<sha256-hex>}: der Inhalt, je Inhalt einmal, unabhängig von Quelle und Anhang</li>
 *   <li>{@code index/<Quelle>/<Anhang>/<Version>.properties}: Metadaten mit Verweis auf den Inhalt</li>
 *   <li>{@code descriptions/<sha256-hex>/<Beschreiber>/<Modell>.properties}: Bildbeschreibungen, je Bildinhalt und
 *       Modell</li>
 *   <li>{@code texts/<sha256-hex>/<Extraktion>/<Version>.properties}: Text von Dokumenten (#183), je Inhalt und
 *       Extraktion</li>
 * </ul>
 *
 * <p>Namen aus Quell-IDs werden nie ungeprüft zu Pfaden: harmlose Namen bekommen das Präfix {@code p}, alle anderen
 * werden hexadezimal kodiert mit Präfix {@code x}. Geschrieben wird über eine temporäre Datei und Umbenennen, damit
 * ein Leser nie eine halbe Datei sieht. Die Inhalte sind so schutzbedürftig wie die Seiten; das Verzeichnis gehört
 * nur dem Dienstkonto.
 *
 * <p>Alle Inhalte zusammen belegen höchstens {@code maxTotalBytes}; ein neuer Inhalt darüber scheitert mit {@link
 * AssetStoreFullException}, nichts wird verdrängt. Gezählt wird beim ersten Schreiben einmal über {@code blobs/},
 * danach je neuem Inhalt; Einträge, Beschreibungen und Texte zählen nicht mit (ein Text ist durch die Zeichengrenze der
 * Extraktion begrenzt).
 */
public class FileSystemAssetStore implements AssetStore {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Pattern HEX_SHA = Pattern.compile("sha256:[0-9a-f]{64}");

    /** Standard für {@code space-harvester.assets.max-total-bytes}: 20 GiB, wie die Exportgrenze. */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024;

    private final Path root;
    private final long maxTotalBytes;
    /** Belegte Bytes in {@code blobs/}; {@code -1}, solange noch nicht gezählt. Geschützt durch {@code this}. */
    private long usedBytes = -1;

    public FileSystemAssetStore(Path root) {
        this(root, DEFAULT_MAX_TOTAL_BYTES);
    }

    public FileSystemAssetStore(Path root, long maxTotalBytes) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (maxTotalBytes <= 0) {
            throw new IllegalArgumentException("maxTotalBytes muss positiv sein");
        }
        this.maxTotalBytes = maxTotalBytes;
    }

    @Override
    public Optional<Asset> find(String sourceId, String attachmentId, String version) {
        if (version == null) {
            return Optional.empty();
        }
        Path entry = entry(sourceId, attachmentId, version);
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(entry, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new AssetStoreException("Asset-Eintrag nicht lesbar", e);
        }
        try {
            Asset asset = new Asset(p.getProperty("sourceId"), p.getProperty("pageId"), p.getProperty("attachmentId"),
                    p.getProperty("version"), p.getProperty("title"), p.getProperty("mediaType"),
                    AssetKind.valueOf(p.getProperty("kind")), Long.parseLong(p.getProperty("size")),
                    p.getProperty("sha256"), Instant.parse(p.getProperty("retrievedAt")));
            if (!asset.sourceId().equals(sourceId) || !asset.attachmentId().equals(attachmentId)
                    || !version.equals(asset.version()) || !HEX_SHA.matcher(asset.sha256()).matches()
                    || !Files.isRegularFile(blob(asset.sha256()))) {
                return Optional.empty();
            }
            return Optional.of(asset);
        } catch (RuntimeException e) {
            throw new AssetStoreException("Asset-Eintrag beschädigt", null);
        }
    }

    @Override
    public void put(Asset asset, byte[] content) {
        if (!HEX_SHA.matcher(asset.sha256()).matches() || !asset.sha256().equals(sha256(content))
                || asset.size() != content.length) {
            throw new IllegalArgumentException("sha256 oder size passen nicht zum Inhalt");
        }
        try {
            Path blob = blob(asset.sha256());
            if (!Files.isRegularFile(blob) || Files.size(blob) != content.length) {
                reserve(content.length);
                try {
                    write(blob, content);
                } catch (IOException | RuntimeException e) {
                    release(content.length);
                    throw e;
                }
            }
            if (asset.version() == null) {
                return;
            }
            Properties p = new Properties();
            p.setProperty("sourceId", asset.sourceId());
            p.setProperty("pageId", asset.pageId());
            p.setProperty("attachmentId", asset.attachmentId());
            p.setProperty("version", asset.version());
            p.setProperty("title", asset.title());
            p.setProperty("mediaType", asset.mediaType());
            p.setProperty("kind", asset.kind().name());
            p.setProperty("size", Long.toString(asset.size()));
            p.setProperty("sha256", asset.sha256());
            p.setProperty("retrievedAt", asset.retrievedAt().toString());
            var text = new java.io.StringWriter();
            p.store(text, null);
            write(entry(asset.sourceId(), asset.attachmentId(), asset.version()),
                    text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | UncheckedIOException e) {
            throw new AssetStoreException("Asset nicht ablegbar", e);
        }
    }

    @Override
    public byte[] read(Asset asset) {
        if (!HEX_SHA.matcher(asset.sha256()).matches()) {
            throw new AssetStoreException("Ungültiger Hash", null);
        }
        byte[] content;
        try {
            content = Files.readAllBytes(blob(asset.sha256()));
        } catch (IOException e) {
            throw new AssetStoreException("Asset-Inhalt nicht lesbar", e);
        }
        if (!asset.sha256().equals(sha256(content))) {
            throw new AssetStoreException("Asset-Inhalt passt nicht zu seinem Hash", null);
        }
        return content;
    }

    @Override
    public InputStream open(Asset asset) {
        if (!HEX_SHA.matcher(asset.sha256()).matches()) {
            throw new AssetStoreException("Ungültiger Hash", null);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        InputStream in;
        try {
            in = Files.newInputStream(blob(asset.sha256()));
        } catch (IOException e) {
            throw new AssetStoreException("Asset-Inhalt nicht lesbar", e);
        }
        return new DigestInputStream(in, digest) {
            private boolean checked;

            @Override
            public int read() throws IOException {
                int b = super.read();
                return b < 0 ? end(b) : b;
            }

            @Override
            public int read(byte[] bytes, int off, int len) throws IOException {
                int n = super.read(bytes, off, len);
                return n < 0 ? end(n) : n;
            }

            private int end(int eof) throws IOException {
                if (!checked) {
                    checked = true;
                    if (!asset.sha256().equals("sha256:" + HexFormat.of().formatHex(digest.digest()))) {
                        throw new IOException("Asset-Inhalt passt nicht zu seinem Hash");
                    }
                }
                return eof;
            }
        };
    }

    @Override
    public Optional<ImageDescription> description(String imageSha256, String describer, String modelVersion) {
        if (!HEX_SHA.matcher(imageSha256).matches()) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(descriptionFile(imageSha256, describer, modelVersion),
                StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new AssetStoreException("Bildbeschreibung nicht lesbar", e);
        }
        try {
            ImageDescription description = new ImageDescription(p.getProperty("imageSha256"),
                    p.getProperty("describer"), p.getProperty("modelVersion"), p.getProperty("text"),
                    Instant.parse(p.getProperty("createdAt")));
            return description.imageSha256().equals(imageSha256) && description.describer().equals(describer)
                    && description.modelVersion().equals(modelVersion) ? Optional.of(description) : Optional.empty();
        } catch (RuntimeException e) {
            throw new AssetStoreException("Bildbeschreibung beschädigt", null);
        }
    }

    @Override
    public void putDescription(ImageDescription description) {
        if (!HEX_SHA.matcher(description.imageSha256()).matches()) {
            throw new IllegalArgumentException("Ungültiger Hash");
        }
        Properties p = new Properties();
        p.setProperty("imageSha256", description.imageSha256());
        p.setProperty("describer", description.describer());
        p.setProperty("modelVersion", description.modelVersion());
        p.setProperty("text", description.text());
        p.setProperty("createdAt", description.createdAt().toString());
        try {
            var text = new java.io.StringWriter();
            p.store(text, null);
            write(descriptionFile(description.imageSha256(), description.describer(), description.modelVersion()),
                    text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | UncheckedIOException e) {
            throw new AssetStoreException("Bildbeschreibung nicht ablegbar", e);
        }
    }

    @Override
    public Optional<AssetText> text(String sha256, String extractor, String version) {
        if (!HEX_SHA.matcher(sha256).matches()) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(textFile(sha256, extractor, version), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new AssetStoreException("Dokumenttext nicht lesbar", e);
        }
        try {
            AssetText text = new AssetText(p.getProperty("sha256"), p.getProperty("extractor"),
                    p.getProperty("version"), p.getProperty("text"), Boolean.parseBoolean(p.getProperty("truncated")),
                    Instant.parse(p.getProperty("createdAt")));
            return text.sha256().equals(sha256) && text.extractor().equals(extractor)
                    && text.version().equals(version) ? Optional.of(text) : Optional.empty();
        } catch (RuntimeException e) {
            throw new AssetStoreException("Dokumenttext beschädigt", null);
        }
    }

    @Override
    public void putText(AssetText text) {
        if (!HEX_SHA.matcher(text.sha256()).matches()) {
            throw new IllegalArgumentException("Ungültiger Hash");
        }
        Properties p = new Properties();
        p.setProperty("sha256", text.sha256());
        p.setProperty("extractor", text.extractor());
        p.setProperty("version", text.version());
        p.setProperty("text", text.text());
        p.setProperty("truncated", Boolean.toString(text.truncated()));
        p.setProperty("createdAt", text.createdAt().toString());
        try {
            var out = new java.io.StringWriter();
            p.store(out, null);
            write(textFile(text.sha256(), text.extractor(), text.version()),
                    out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | UncheckedIOException e) {
            throw new AssetStoreException("Dokumenttext nicht ablegbar", e);
        }
    }

    /**
     * Entfernt die Einträge einer Quelle (#188) und danach jeden Inhalt, auf den kein Eintrag mehr verweist und den
     * {@code keep} nicht nennt (etwa Bilder bestehender Exporte), samt seinen Bildbeschreibungen und Texten. Inhalte,
     * die seit {@code since} geschrieben wurden, bleiben: Sie gehören zu Ablagen, die gerade laufen.
     *
     * @param keep {@code sha256:<hex>} von Inhalten, die bleiben müssen
     * @return Anzahl der entfernten Inhalte
     */
    public synchronized int removeSource(String sourceId, Predicate<String> keep, Instant since) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(keep, "keep");
        Objects.requireNonNull(since, "since");
        try {
            deleteTree(root.resolve("index").resolve(name(sourceId)));
            Set<String> referenced = new HashSet<>();
            Path index = root.resolve("index");
            if (Files.isDirectory(index)) {
                try (Stream<Path> entries = Files.walk(index)) {
                    for (Path entry : (Iterable<Path>) entries.filter(path -> path.getFileName().toString()
                            .endsWith(".properties"))::iterator) {
                        Properties p = new Properties();
                        try (var in = Files.newBufferedReader(entry, StandardCharsets.UTF_8)) {
                            p.load(in);
                        }
                        String sha = p.getProperty("sha256");
                        if (sha != null) {
                            referenced.add(sha);
                        }
                    }
                }
            }
            Path blobs = root.resolve("blobs");
            if (!Files.isDirectory(blobs)) {
                return 0;
            }
            int removed = 0;
            try (Stream<Path> files = Files.walk(blobs)) {
                for (Path blob : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String hex = blob.getFileName().toString();
                    String sha = "sha256:" + hex;
                    if (!HEX_SHA.matcher(sha).matches() || referenced.contains(sha) || keep.test(sha)
                            || !Files.getLastModifiedTime(blob).toInstant().isBefore(since)) {
                        continue;
                    }
                    long size = Files.size(blob);
                    Files.deleteIfExists(blob);
                    if (usedBytes >= 0) {
                        usedBytes = Math.max(0, usedBytes - size);
                    }
                    deleteTree(root.resolve("descriptions").resolve(hex));
                    deleteTree(root.resolve("texts").resolve(hex));
                    removed++;
                }
            }
            return removed;
        } catch (IOException | UncheckedIOException e) {
            throw new AssetStoreException("Assets der Quelle nicht entfernbar", e);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private synchronized void reserve(long bytes) throws IOException {
        if (usedBytes < 0) {
            usedBytes = blobBytes();
        }
        if (usedBytes + bytes > maxTotalBytes) {
            throw new AssetStoreFullException(maxTotalBytes);
        }
        usedBytes += bytes;
    }

    private synchronized void release(long bytes) {
        usedBytes = Math.max(0, usedBytes - bytes);
    }

    private long blobBytes() throws IOException {
        Path blobs = root.resolve("blobs");
        if (!Files.isDirectory(blobs)) {
            return 0;
        }
        long total = 0;
        try (Stream<Path> files = Files.walk(blobs)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                total += Files.size(file);
            }
        }
        return total;
    }

    private Path descriptionFile(String imageSha256, String describer, String modelVersion) {
        return root.resolve("descriptions").resolve(imageSha256.substring("sha256:".length()))
                .resolve(name(describer)).resolve(name(modelVersion) + ".properties");
    }

    private Path textFile(String sha256, String extractor, String version) {
        return root.resolve("texts").resolve(sha256.substring("sha256:".length())).resolve(name(extractor))
                .resolve(name(version) + ".properties");
    }

    private Path blob(String sha256) {
        String hex = sha256.substring("sha256:".length());
        return root.resolve("blobs").resolve(hex.substring(0, 2)).resolve(hex);
    }

    private Path entry(String sourceId, String attachmentId, String version) {
        return root.resolve("index").resolve(name(sourceId)).resolve(name(attachmentId))
                .resolve(name(version) + ".properties");
    }

    /** Ein sicherer, eindeutiger Dateiname für einen beliebigen Wert. */
    static String name(String value) {
        if (PLAIN.matcher(value).matches()) {
            return "p" + value;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 100) {
            return "h" + sha256(bytes).substring("sha256:".length());
        }
        return "x" + HexFormat.of().formatHex(bytes);
    }

    private static void write(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
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

    private static String sha256(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
