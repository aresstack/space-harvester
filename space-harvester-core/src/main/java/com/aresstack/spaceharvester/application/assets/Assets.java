package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.diagrams.Diagrams;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.AttachmentTooLargeException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final ImageDescriber describer;

    /** @param maxBytes größte Datei, die heruntergeladen und abgelegt wird */
    public Assets(SourceCatalog catalog, AssetStore store, long maxBytes, Clock clock) {
        this(catalog, store, maxBytes, clock, null);
    }

    /**
     * @param maxBytes  größte Datei, die heruntergeladen und abgelegt wird
     * @param describer Bildbeschreiber oder {@code null}, wenn keiner konfiguriert ist
     */
    public Assets(SourceCatalog catalog, AssetStore store, long maxBytes, Clock clock, ImageDescriber describer) {
        this.describer = describer;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes muss positiv sein");
        }
        this.maxBytes = maxBytes;
    }

    /** Gleichzeitige Downloads von Anhängen, und wie lange ein weiterer auf einen freien Platz wartet. */
    static final int MAX_CONCURRENT_DOWNLOADS = 4;
    private static final java.time.Duration DOWNLOAD_WAIT = java.time.Duration.ofSeconds(30);
    private final Semaphore downloads = new Semaphore(MAX_CONCURRENT_DOWNLOADS);

    /**
     * Gleichzeitige Läufe des Bildbeschreibers; ein weiterer scheitert sofort mit {@code BUSY}. Dasselbe Bild für
     * dasselbe Modell läuft nie zweimal gleichzeitig: weitere Aufrufer warten höchstens {@link #DESCRIBE_WAIT} auf den
     * laufenden.
     */
    static final int MAX_CONCURRENT_DESCRIPTIONS = 2;
    private static final java.time.Duration DESCRIBE_WAIT = java.time.Duration.ofMinutes(2);
    private final Semaphore descriptions = new Semaphore(MAX_CONCURRENT_DESCRIPTIONS);
    private final Map<String, CompletableFuture<ImageDescription>> describing = new ConcurrentHashMap<>();

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
     * Anhang hält die anderen nicht auf und wird mit festem Code protokolliert; drosselt die Quelle oder ist die Ablage
     * voll, werden die übrigen Anhänge nicht mehr angefragt.
     */
    public AssetHarvest harvest(PageAttachmentList list) {
        HarvestSource source = source(list.sourceId());
        String pageId = list.attachments().pageId();
        List<AssetHarvest.Entry> entries = new ArrayList<>();
        String stop = null;
        for (PageAttachment attachment : list.attachments().attachments()) {
            if (stop != null) {
                entries.add(failed(list, attachment, stop));
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
            } catch (AssetStoreFullException e) {
                stop = "asset-store-full";
                entries.add(failed(list, attachment, stop));
            } catch (SourceAccessException e) {
                if (e.reason() == SourceAccessException.Reason.RATE_LIMITED) {
                    stop = code(e.reason());
                }
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
        return content(list, attachmentId, true);
    }

    /**
     * Wie {@link #content(PageAttachmentList, String)}; mit {@code mayStore=false} wird ein nicht abgelegter Anhang
     * nur ausgeliefert, nicht abgelegt. Ablegen ist ein Schreibvorgang und bleibt Aufrufern mit {@code sync}
     * vorbehalten, sonst füllte jeder Leser die Ablage.
     */
    public AssetContent content(PageAttachmentList list, String attachmentId, boolean mayStore) {
        PageAttachment attachment = attachment(list, attachmentId);
        Optional<Asset> stored = store.find(list.sourceId(), attachment.attachmentId(), attachment.version());
        if (stored.isPresent()) {
            return new AssetContent(stored.get(), store.read(stored.get()));
        }
        return download(source(list.sourceId()), list.attachments().pageId(), attachment, mayStore);
    }

    /**
     * Ein Bild der Lese-Ansicht einer Seite ({@link HarvestSource#viewImages}): aus der Ablage oder über die Anmeldung
     * der Quelle geladen und abgelegt. Ist das Bild ein Anhang, gilt dessen ID und Version; sonst eine ID aus der
     * Adresse und die Version der Seite, damit ein unverändertes Bild nicht erneut übertragen wird.
     *
     * @throws SourceAccessException  {@code UNSUPPORTED} für Adressen außerhalb der Quelle, sonst wie die Quelle
     * @throws AssetTooLargeException wenn die Datei die Grenze überschreitet
     */
    public AssetContent viewImage(String sourceId, String pageId, String pageVersion, HarvestSource.ViewImage image) {
        HarvestSource source = source(sourceId);
        String attachmentId = image.attachmentId() != null ? image.attachmentId()
                : "view-" + sha256(image.src().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .substring("sha256:".length(), "sha256:".length() + 32);
        String version = image.attachmentId() != null ? image.version() : pageVersion;
        Optional<Asset> stored = store.find(sourceId, attachmentId, version);
        if (stored.isPresent()) {
            return new AssetContent(stored.get(), store.read(stored.get()));
        }
        byte[] content;
        try {
            if (!downloads.tryAcquire(DOWNLOAD_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new SourceAccessException(sourceId, SourceAccessException.Reason.RATE_LIMITED,
                        "Zu viele gleichzeitige Downloads von Bildern", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE, "Download abgebrochen",
                    e);
        }
        try {
            content = source.viewImage(image, maxBytes);
        } catch (AttachmentTooLargeException e) {
            throw new AssetTooLargeException(sourceId, maxBytes);
        } finally {
            downloads.release();
        }
        if (content.length > maxBytes) {
            throw new AssetTooLargeException(sourceId, maxBytes);
        }
        String mediaType = image.mediaType() != null && !image.mediaType().isBlank() ? image.mediaType()
                : sniff(content);
        Asset asset = new Asset(sourceId, pageId, attachmentId, version, image.title(), mediaType,
                AssetKind.of(mediaType, image.title()), content.length, sha256(content), clock.instant());
        store.put(asset, content);
        return new AssetContent(asset, content);
    }

    /** Die Bilder der Lese-Ansicht einer Seite ({@link HarvestSource#viewImages}); lädt keine Bytes. */
    public List<HarvestSource.ViewImage> viewImages(String sourceId, String pageId) {
        return source(sourceId).viewImages(pageId);
    }

    /**
     * Die abgelegten Bytes zu {@code sha256}, dagegen geprüft; liest nie von der Quelle. Der Inhalt liegt nach seinem
     * Hash ab, daher auch ohne Version der Quelle und auch dann, wenn inzwischen eine neuere Version abgelegt ist.
     *
     * @return leer, wenn nichts oder etwas anderes abgelegt ist
     */
    public Optional<byte[]> stored(String sourceId, String attachmentId, String version, String sha256) {
        if (sha256 == null) {
            return Optional.empty();
        }
        Asset asset = store.find(sourceId, attachmentId, version).filter(found -> found.sha256().equals(sha256))
                .orElseGet(() -> new Asset(sourceId, "-", attachmentId == null ? "-" : attachmentId, version, null,
                        null, AssetKind.OTHER, 0, sha256, clock.instant()));
        byte[] content;
        try {
            content = store.read(asset);
        } catch (AssetStoreException e) {
            return Optional.empty();
        }
        return sha256(content).equals(sha256) ? Optional.of(content) : Optional.empty();
    }

    /** Version der draw.io-Grafik im Speicher; ändert sich mit dem Renderer, damit alte Grafiken neu entstehen. */
    public static final String DRAWIO_RENDITION = "drawio-svg-1";
    /** Version der Mermaid-Grafik im Speicher. */
    public static final String MERMAID_RENDITION = "mermaid-png-1";

    /**
     * Die gezeichnete Grafik eines Diagramms (#190): einmal mit {@link Diagrams} gezeichnet und unter der Kennung
     * {@link #renditionId} seiner Quelldaten abgelegt; dieselben Quelldaten werden nie erneut gezeichnet. Ruft nie die
     * Quelle.
     *
     * @param renderer     {@link #DRAWIO_RENDITION} oder {@link #MERMAID_RENDITION}
     * @param source       Quelldaten ({@code mxfile} oder Mermaid-Text in UTF-8)
     * @return die abgelegte Grafik oder warum es keine gibt
     */
    public Rendered rendition(String sourceId, String pageId, String renderer, byte[] source) {
        String sourceSha256 = sha256(source);
        String id = renditionId(sourceSha256);
        Optional<Asset> stored = store.find(sourceId, id, renderer);
        if (stored.isPresent()) {
            return new Rendered(stored.get(), null);
        }
        Diagrams.Rendering rendering = switch (renderer) {
            case DRAWIO_RENDITION -> Diagrams.drawio(source);
            case MERMAID_RENDITION -> Diagrams.mermaid(new String(source, java.nio.charset.StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("Unbekannter Renderer " + renderer);
        };
        if (!rendering.drawn()) {
            return new Rendered(null, rendering.problem());
        }
        byte[] content = rendering.content();
        if (content.length > maxBytes) {
            return new Rendered(null, "Grafik größer als die Grenze des Asset-Speichers");
        }
        Asset asset = new Asset(sourceId, pageId, id, renderer, "", rendering.mediaType(),
                AssetKind.of(rendering.mediaType(), ""), content.length, sha256(content), clock.instant());
        try {
            store.put(asset, content);
        } catch (AssetStoreException e) {
            return new Rendered(null, "Grafik nicht ablegbar (" + e.getClass().getSimpleName() + ")");
        }
        return new Rendered(asset, null);
    }

    /** Kennung der Grafik im Speicher: aus dem Hash der Quelldaten, unabhängig von Seite und Anhang. */
    public static String renditionId(String sourceSha256) {
        return "render-" + sourceSha256.substring("sha256:".length(), "sha256:".length() + 32);
    }

    /** Ergebnis von {@link #rendition}: die abgelegte Grafik oder das Problem. */
    public record Rendered(Asset asset, String problem) {
    }

    /** Medientyp aus den ersten Bytes, für Bilder ohne Angabe der Quelle. */
    static String sniff(byte[] content) {
        if (content.length >= 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N'
                && content[3] == 'G') {
            return "image/png";
        }
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (content.length >= 4 && content[0] == 'G' && content[1] == 'I' && content[2] == 'F') {
            return "image/gif";
        }
        String head = new String(content, 0, Math.min(content.length, 256), java.nio.charset.StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("<svg") || head.startsWith("<?xml") && head.contains("<svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    /**
     * Die vorhandene Beschreibung des Bildes in seiner aktuellen, abgelegten Version durch das konfigurierte Modell;
     * erzeugt nichts.
     *
     * @throws ImageDescriptionException {@code NOT_CONFIGURED}, {@code NOT_AN_IMAGE} oder {@code NOT_FOUND}
     */
    public DescribedImage description(PageAttachmentList list, String attachmentId) {
        ImageDescriber model = describer();
        PageAttachment attachment = attachment(list, attachmentId);
        requireImage(attachment.mediaType(), attachment.title());
        return store.find(list.sourceId(), attachment.attachmentId(), attachment.version())
                .flatMap(asset -> store.description(asset.sha256(), model.id(), model.modelVersion())
                        .map(description -> new DescribedImage(asset, description, false)))
                .orElseThrow(() -> new ImageDescriptionException(ImageDescriptionException.Reason.NOT_FOUND,
                        "Noch keine Beschreibung", null));
    }

    /**
     * Beschreibt das Bild in seiner aktuellen Version, falls es für diese Bildbytes und dieses Modell noch keine
     * Beschreibung gibt; lädt das Bild dafür bei Bedarf über die Quelle. Läuft für dasselbe Bild und Modell schon eine
     * Beschreibung, wird auf deren Ergebnis gewartet statt das Modell erneut zu fragen.
     *
     * @throws ImageDescriptionException {@code NOT_CONFIGURED}, {@code NOT_AN_IMAGE}, {@code BUSY} oder
     *                                   {@code FAILED}
     */
    public DescribedImage describe(PageAttachmentList list, String attachmentId) {
        ImageDescriber model = describer();
        PageAttachment attachment = attachment(list, attachmentId);
        requireImage(attachment.mediaType(), attachment.title());
        AssetContent content = content(list, attachmentId);
        Asset asset = content.asset();
        Optional<ImageDescription> existing = store.description(asset.sha256(), model.id(), model.modelVersion());
        if (existing.isPresent()) {
            return new DescribedImage(asset, existing.get(), false);
        }
        String key = asset.sha256() + '\n' + model.id() + '\n' + model.modelVersion();
        CompletableFuture<ImageDescription> mine = new CompletableFuture<>();
        CompletableFuture<ImageDescription> running = describing.putIfAbsent(key, mine);
        if (running != null) {
            return new DescribedImage(asset, await(running), false);
        }
        try {
            // Ein eben beendeter Lauf hat die Beschreibung womöglich zwischen Prüfung und Anmeldung abgelegt.
            existing = store.description(asset.sha256(), model.id(), model.modelVersion());
            if (existing.isPresent()) {
                mine.complete(existing.get());
                return new DescribedImage(asset, existing.get(), false);
            }
            ImageDescription description = generate(model, content);
            mine.complete(description);
            return new DescribedImage(asset, description, true);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            describing.remove(key, mine);
        }
    }

    private ImageDescription generate(ImageDescriber model, AssetContent content) {
        if (!descriptions.tryAcquire()) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.BUSY,
                    "Zu viele gleichzeitige Bildbeschreibungen", null);
        }
        String text;
        try {
            text = model.describe(content.content(), content.asset().mediaType());
        } catch (ImageDescriptionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.FAILED, "Beschreibung fehlgeschlagen",
                    e);
        } finally {
            descriptions.release();
        }
        if (text == null || text.isBlank()) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.FAILED, "Leere Beschreibung", null);
        }
        ImageDescription description = new ImageDescription(content.asset().sha256(), model.id(),
                model.modelVersion(), text.strip(), clock.instant());
        store.putDescription(description);
        return description;
    }

    /** Das Ergebnis eines laufenden Beschreibungslaufs; dessen Fehler gilt auch für den Wartenden. */
    private static ImageDescription await(CompletableFuture<ImageDescription> running) {
        try {
            return running.get(DESCRIBE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new ImageDescriptionException(ImageDescriptionException.Reason.FAILED, "Beschreibung fehlgeschlagen",
                    e.getCause());
        } catch (TimeoutException e) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.BUSY,
                    "Beschreibung desselben Bildes läuft noch", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageDescriptionException(ImageDescriptionException.Reason.BUSY, "Warten abgebrochen", null);
        }
    }

    private ImageDescriber describer() {
        if (describer == null) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_CONFIGURED,
                    "Kein Bildbeschreiber konfiguriert", null);
        }
        return describer;
    }

    private static void requireImage(String mediaType, String title) {
        AssetKind kind = AssetKind.of(mediaType, title);
        if (kind != AssetKind.IMAGE && kind != AssetKind.VECTOR) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_AN_IMAGE, "Kein Bild", null);
        }
    }

    private static PageAttachment attachment(PageAttachmentList list, String attachmentId) {
        return list.attachments().attachments().stream()
                .filter(a -> a.attachmentId().equals(attachmentId))
                .findFirst()
                .orElseThrow(() -> new SourceAccessException(list.sourceId(), SourceAccessException.Reason.NOT_FOUND,
                        "Anhang gehört nicht zur Seite", null));
    }

    private AssetContent download(HarvestSource source, String pageId, PageAttachment attachment) {
        return download(source, pageId, attachment, true);
    }

    private AssetContent download(HarvestSource source, String pageId, PageAttachment attachment, boolean keep) {
        if (attachment.fileSize() != null && attachment.fileSize() > maxBytes) {
            throw new AssetTooLargeException(source.id(), maxBytes);
        }
        AttachmentFile file;
        // Höchstens wenige Downloads gleichzeitig: jeder hält die Datei bis zur Grenze des Transports im Speicher.
        try {
            if (!downloads.tryAcquire(DOWNLOAD_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new SourceAccessException(source.id(), SourceAccessException.Reason.RATE_LIMITED,
                        "Zu viele gleichzeitige Downloads von Anhängen", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.UNAVAILABLE,
                    "Download abgebrochen", e);
        }
        try {
            file = source.attachmentFile(attachment, maxBytes);
        } catch (AttachmentTooLargeException e) {
            throw new AssetTooLargeException(source.id(), maxBytes);
        } finally {
            downloads.release();
        }
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
        if (keep) {
            store.put(asset, content);
        }
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

    public static String sha256(byte[] content) {
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

    /**
     * Ein beschriebenes Bild.
     *
     * @param generated {@code true}, wenn die Beschreibung in diesem Aufruf entstand
     */
    public record DescribedImage(Asset asset, ImageDescription description, boolean generated) {
    }
}
