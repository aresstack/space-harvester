package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Anwendungsfälle der Asset-Ernte (#19). Arbeitet immer auf einer frisch gelesenen Anhangsliste ({@link
 * com.aresstack.spaceharvester.application.sources.SourceQueries#attachments}), deren Space der Aufrufer vorher gegen
 * die Freigaben geprüft hat. Ein Anhang derselben Version wird nie zweimal übertragen.
 */
public class Assets {

    private static final System.Logger LOG = System.getLogger(Assets.class.getName());

    private final SourceCatalog catalog;
    private final AssetStore store;
    private final long maxBytes;
    private final Clock clock;

    /** @param maxBytes größte Datei, die heruntergeladen und abgelegt wird */
    public Assets(SourceCatalog catalog, AssetStore store, long maxBytes, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes muss positiv sein");
        }
        this.maxBytes = maxBytes;
    }

    /** Alle Anhänge der Seite mit Art, Diagrammbezug und dem abgelegten Stand; lädt nichts herunter. */
    public PageAssets overview(PageAttachmentList list) {
        Map<String, String> previews = previews(list.attachments().attachments());
        List<PageAssets.Entry> entries = list.attachments().attachments().stream()
                .map(a -> new PageAssets.Entry(a, AssetKind.of(a.mediaType(), a.title()),
                        previews.get(a.attachmentId()), store.find(list.sourceId(), a.attachmentId(), a.version())
                                .orElse(null)))
                .toList();
        return new PageAssets(list.sourceId(), list.attachments().pageId(), list.spaceKey(),
                list.attachments().complete(), entries, list.retrievedAt());
    }

    /**
     * Legt alle Anhänge der Seite ab, die noch nicht in ihrer aktuellen Version abgelegt sind. Ein Fehler bei einem
     * Anhang hält die anderen nicht auf und wird mit festem Code protokolliert; drosselt die Quelle, werden die übrigen
     * Anhänge nicht mehr angefragt.
     */
    public AssetHarvest harvest(PageAttachmentList list) {
        HarvestSource source = source(list.sourceId());
        String pageId = list.attachments().pageId();
        List<AssetHarvest.Entry> entries = new ArrayList<>();
        boolean throttled = false;
        for (PageAttachment attachment : list.attachments().attachments()) {
            if (throttled) {
                entries.add(failed(list, attachment, "source-rate-limited"));
                continue;
            }
            Optional<Asset> stored = store.find(list.sourceId(), attachment.attachmentId(), attachment.version());
            if (stored.isPresent()) {
                entries.add(new AssetHarvest.Entry(attachment.attachmentId(), AssetHarvest.Status.REUSED,
                        stored.get(), null));
                continue;
            }
            try {
                Asset asset = download(source, pageId, attachment).asset();
                entries.add(new AssetHarvest.Entry(attachment.attachmentId(), AssetHarvest.Status.STORED, asset,
                        null));
            } catch (AssetTooLargeException e) {
                entries.add(failed(list, attachment, "asset-too-large"));
            } catch (SourceAccessException e) {
                throttled = e.reason() == SourceAccessException.Reason.RATE_LIMITED;
                entries.add(failed(list, attachment, code(e.reason())));
            }
        }
        return new AssetHarvest(list.sourceId(), pageId, list.spaceKey(), list.attachments().complete(), entries,
                clock.instant());
    }

    /**
     * Ein Anhang der Seite in seiner aktuellen Version: aus der Ablage oder, falls dort nicht vorhanden, frisch
     * heruntergeladen und abgelegt.
     *
     * @throws SourceAccessException  {@code NOT_FOUND}, wenn der Anhang nicht zur Seite gehört, sonst wie die Quelle
     * @throws AssetTooLargeException wenn die Datei die Grenze überschreitet
     */
    public AssetContent content(PageAttachmentList list, String attachmentId) {
        PageAttachment attachment = list.attachments().attachments().stream()
                .filter(a -> a.attachmentId().equals(attachmentId))
                .findFirst()
                .orElseThrow(() -> new SourceAccessException(list.sourceId(), SourceAccessException.Reason.NOT_FOUND,
                        "Anhang gehört nicht zur Seite", null));
        Optional<Asset> stored = store.find(list.sourceId(), attachment.attachmentId(), attachment.version());
        if (stored.isPresent()) {
            return new AssetContent(stored.get(), store.read(stored.get()));
        }
        return download(source(list.sourceId()), list.attachments().pageId(), attachment);
    }

    private AssetContent download(HarvestSource source, String pageId, PageAttachment attachment) {
        if (attachment.fileSize() != null && attachment.fileSize() > maxBytes) {
            throw new AssetTooLargeException(source.id(), maxBytes);
        }
        AttachmentFile file = source.attachmentFile(attachment);
        byte[] content = file.content();
        if (content.length > maxBytes) {
            throw new AssetTooLargeException(source.id(), maxBytes);
        }
        PageAttachment described = file.attachment();
        String version = described.version() != null ? described.version() : attachment.version();
        String mediaType = described.mediaType() != null && !described.mediaType().isBlank()
                ? described.mediaType() : attachment.mediaType();
        String title = described.title().isEmpty() ? attachment.title() : described.title();
        Asset asset = new Asset(source.id(), pageId, attachment.attachmentId(), version, title, mediaType,
                AssetKind.of(mediaType, title), content.length, sha256(content), file.retrievedAt());
        store.put(asset, content);
        return new AssetContent(asset, content);
    }

    /** Ordnet Vorschaubilder ihren draw.io-Diagrammen zu: {@code name.png} bzw. {@code name.drawio.png}. */
    static Map<String, String> previews(List<PageAttachment> attachments) {
        Map<String, String> diagrams = new HashMap<>();
        for (PageAttachment a : attachments) {
            if (AssetKind.of(a.mediaType(), a.title()) == AssetKind.DIAGRAM) {
                diagrams.put(a.title().toLowerCase(Locale.ROOT), a.attachmentId());
            }
        }
        Map<String, String> previews = new HashMap<>();
        for (PageAttachment a : attachments) {
            String name = a.title().toLowerCase(Locale.ROOT);
            if (AssetKind.of(a.mediaType(), a.title()) == AssetKind.IMAGE && name.endsWith(".png")) {
                String diagram = diagrams.get(name.substring(0, name.length() - ".png".length()));
                if (diagram != null) {
                    previews.put(a.attachmentId(), diagram);
                }
            }
        }
        return previews;
    }

    private static AssetHarvest.Entry failed(PageAttachmentList list, PageAttachment attachment, String code) {
        LOG.log(System.Logger.Level.WARNING, "Asset nicht abgelegt: source={0}, page={1}, attachment={2}, code={3}",
                list.sourceId(), list.attachments().pageId(), attachment.attachmentId(), code);
        return new AssetHarvest.Entry(attachment.attachmentId(), AssetHarvest.Status.FAILED, null, code);
    }

    private static String code(SourceAccessException.Reason reason) {
        return switch (reason) {
            case NOT_FOUND -> "source-object-not-found";
            case RATE_LIMITED -> "source-rate-limited";
            case UNAVAILABLE -> "source-unavailable";
            case ACCESS_DENIED -> "source-access-denied";
            case UNSUPPORTED -> "source-unsupported";
            default -> "source-invalid-response";
        };
    }

    static String sha256(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private HarvestSource source(String sourceId) {
        return catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
    }

    /** Ein Anhang mit seinen Bytes; die Bytes erscheinen nie in {@link #toString()}. */
    public record AssetContent(Asset asset, byte[] content) {

        @Override
        public String toString() {
            return "AssetContent[asset=" + asset + ", bytes=" + content.length + "]";
        }
    }
}
