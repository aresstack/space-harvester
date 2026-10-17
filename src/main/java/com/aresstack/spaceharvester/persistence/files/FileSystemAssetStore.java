package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetStore;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Legt geerntete Anhänge im Dateisystem ab (#19):
 *
 * <ul>
 *   <li>{@code blobs/<2 Zeichen>/<sha256-hex>}: der Inhalt, je Inhalt einmal, unabhängig von Quelle und Anhang</li>
 *   <li>{@code index/<Quelle>/<Anhang>/<Version>.properties}: Metadaten mit Verweis auf den Inhalt</li>
 * </ul>
 *
 * <p>Namen aus Quell-IDs werden nie ungeprüft zu Pfaden: harmlose Namen bekommen das Präfix {@code p}, alle anderen
 * werden hexadezimal kodiert mit Präfix {@code x}. Geschrieben wird über eine temporäre Datei und Umbenennen, damit
 * ein Leser nie eine halbe Datei sieht. Die Inhalte sind so schutzbedürftig wie die Seiten; das Verzeichnis gehört
 * nur dem Dienstkonto.
 */
public class FileSystemAssetStore implements AssetStore {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Pattern HEX_SHA = Pattern.compile("sha256:[0-9a-f]{64}");

    private final Path root;

    public FileSystemAssetStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
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
                    || !version.equals(asset.version()) || !Files.isRegularFile(blob(asset.sha256()))) {
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
                write(blob, content);
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
