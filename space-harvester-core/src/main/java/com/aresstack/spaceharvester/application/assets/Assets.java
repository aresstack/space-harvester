package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.diagrams.Diagrams;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.signin.SignInPendingException;
import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.AttachmentPreview;
import com.aresstack.spaceharvester.application.sources.AttachmentTooLargeException;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>Mit einem {@link PageTextIndex} kommt jeder abgelegte Anhangstext in den Volltextindex (#183), wie im Enterprise
 * AI Client ({@code SourceAttachmentReader}: jeder gelesene Anhang ein eigenes Dokument mit der Seite als Eltern, in
 * Roh- und Wissensindex): Dokumente mit ihrem Text aus {@link DocumentText}, Bilder mit einer vorhandenen
 * Beschreibung, jeweils mit dem Dateinamen als Titel; auch ohne Text steht der Dateiname im Index. Gelesen wird wie
 * bisher beim Ablegen ({@link #harvest}) und auf Abruf ({@link #text}, {@link #describe}); der Index übernimmt nur, was
 * abgelegt ist, und prüft nie Rechte: die Suche gibt einen Anhangstreffer nur mit seiner lesbaren Seite aus.
 */
public class Assets {

    private static final System.Logger LOG = System.getLogger(Assets.class.getName());

    private final SourceCatalog catalog;
    private final AssetStore store;
    private final long maxBytes;
    private final Clock clock;
    private final ImageDescriber describer;
    private final DocumentText documents;
    private final PageTextIndex texts;
    private final LargeFileGate gate;
    private final SignInRequests signIns;

    /** @param maxBytes größte Datei, die heruntergeladen und abgelegt wird */
    public Assets(SourceCatalog catalog, AssetStore store, long maxBytes, Clock clock) {
        this(catalog, store, maxBytes, clock, null);
    }

    /**
     * @param maxBytes  größte Datei, die heruntergeladen und abgelegt wird
     * @param describer Bildbeschreiber oder {@code null}, wenn keiner konfiguriert ist
     */
    public Assets(SourceCatalog catalog, AssetStore store, long maxBytes, Clock clock, ImageDescriber describer) {
        this(catalog, store, maxBytes, clock, describer, null);
    }

    /**
     * @param maxBytes  größte Datei, die heruntergeladen und abgelegt wird
     * @param describer Bildbeschreiber oder {@code null}, wenn keiner konfiguriert ist
     * @param documents Typerkennung am Inhalt und Textextraktion (#183) oder {@code null}: dann gilt der Medientyp der
     *                  Quelle, und Anhänge haben keinen Text
     */
    public Assets(SourceCatalog catalog, AssetStore store, long maxBytes, Clock clock, ImageDescriber describer,
                  DocumentText documents) {
        this(catalog, store, maxBytes, clock, describer, documents, null);
    }

    /**
     * @param maxBytes  größte Datei, die heruntergeladen und abgelegt wird
     * @param describer Bildbeschreiber oder {@code null}, wenn keiner konfiguriert ist
     * @param documents Typerkennung am Inhalt und Textextraktion (#183) oder {@code null}
     * @param texts     Volltextindex für die Texte der Anhänge (#183) oder {@code null} ohne Cache
     */
    public Assets(SourceCatalog catalog, AssetStore store, long maxBytes, Clock clock, ImageDescriber describer,
                  DocumentText documents, PageTextIndex texts) {
        this(catalog, store, maxBytes, clock, describer, documents, texts, LargeFileGate.defaults());
    }

    /**
     * @param maxBytes  größte Datei, die heruntergeladen und abgelegt wird
     * @param describer Bildbeschreiber oder {@code null}, wenn keiner konfiguriert ist
     * @param documents Typerkennung am Inhalt und Textextraktion (#183) oder {@code null}
     * @param texts     Volltextindex für die Texte der Anhänge (#183) oder {@code null} ohne Cache
     * @param gate      Schranke für große Dateien, gemeinsam für alle speicherintensiven Wege dieser Instanz
     */
    public Assets(SourceCatalog catalog, AssetStore store, long maxBytes, Clock clock, ImageDescriber describer,
                  DocumentText documents, PageTextIndex texts, LargeFileGate gate) {
        this(catalog, store, maxBytes, clock, describer, documents, texts, gate, null);
    }

    /**
     * @param maxBytes  größte Datei, die heruntergeladen und abgelegt wird
     * @param describer Bildbeschreiber oder {@code null}, wenn keiner konfiguriert ist
     * @param documents Typerkennung am Inhalt und Textextraktion (#183) oder {@code null}
     * @param texts     Volltextindex für die Texte der Anhänge (#183) oder {@code null} ohne Cache
     * @param gate      Schranke für große Dateien, gemeinsam für alle speicherintensiven Wege dieser Instanz
     * @param signIns   Anmeldeanforderungen für Lesen auf Abruf (ADR 0018) oder {@code null}: dann bleibt ein
     *                  verlangter Einmalcode „keine Berechtigung“
     */
    public Assets(SourceCatalog catalog, AssetStore store, long maxBytes, Clock clock, ImageDescriber describer,
                  DocumentText documents, PageTextIndex texts, LargeFileGate gate, SignInRequests signIns) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.signIns = signIns;
        this.texts = texts;
        this.describer = describer;
        this.documents = documents;
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
    /** Obergrenze der gemerkten Nachhol-Versuche, danach beginnt die Liste neu. */
    static final int MAX_BACKFILLED = 10_000;
    private static final java.time.Duration DOWNLOAD_WAIT = java.time.Duration.ofSeconds(30);
    private final Semaphore downloads = new Semaphore(MAX_CONCURRENT_DOWNLOADS);
    /** Abgelegte Dokumente, deren Text beim Ernten schon einmal nachgeholt wurde (je Laufzeit, nach SHA-256). */
    private final java.util.Set<String> backfilled = ConcurrentHashMap.newKeySet();

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
        boolean indexed = false;
        for (PageAttachment attachment : list.attachments().attachments()) {
            if (stop != null) {
                entries.add(failed(list, attachment, stop));
                indexed |= forgetStale(list, attachment);
                continue;
            }
            Optional<Asset> stored = store.find(list.sourceId(), attachment.attachmentId(), attachment.version());
            if (stored.isPresent()) {
                backfillText(stored.get());
                indexed |= indexStored(list, stored.get());
                entries.add(new AssetHarvest.Entry(attachment.attachmentId(), AssetHarvest.Status.REUSED,
                        stored.get(), null));
                continue;
            }
            // Download und Textextraktion derselben Datei belegen zusammen einen Platz der Schranke.
            try (LargeFileGate.Permit permit = gate.enter(source.id(), attachment.fileSize())) {
                AssetContent content = download(source, pageId, attachment);
                harvestText(content);
                indexed |= indexStored(list, content.asset());
                entries.add(new AssetHarvest.Entry(attachment.attachmentId(), AssetHarvest.Status.STORED,
                        content.asset(), null));
            } catch (AssetTooLargeException e) {
                entries.add(failed(list, attachment, "asset-too-large"));
                indexed |= forgetStale(list, attachment);
            } catch (AssetStoreFullException e) {
                stop = "asset-store-full";
                entries.add(failed(list, attachment, stop));
                indexed |= forgetStale(list, attachment);
            } catch (SourceAccessException e) {
                if (e.reason() == SourceAccessException.Reason.RATE_LIMITED) {
                    stop = code(e.reason());
                }
                entries.add(failed(list, attachment, code(e.reason())));
                indexed |= forgetStale(list, attachment);
            }
        }
        finishIndexing(list, indexed);
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
            return read(stored.get());
        }
        return download(source(list.sourceId()), list.attachments().pageId(), attachment, mayStore);
    }

    /** Liest einen abgelegten Inhalt, für große Dateien unter der Schranke ({@link LargeFileGate}). */
    private AssetContent read(Asset asset) {
        try (LargeFileGate.Permit permit = gate.enter(asset.sourceId(), asset.size())) {
            return new AssetContent(asset, store.read(asset));
        }
    }

    /**
     * Wie {@link #content(PageAttachmentList, String, boolean)} für Lesen auf Abruf durch {@code owner} (ADR 0018):
     * verlangt die Quelle einen Einmalcode, legt dies die Anforderung an ({@link SignInRequests#open}) und wirft
     * {@link SignInPendingException}, statt den Code still abzufragen. Der Aufrufer hat die Seite vorher auf
     * Leserechte geprüft; ohne Oberfläche oder mit {@code owner == null} gilt das Verhalten ohne Anmeldung.
     *
     * @throws SignInPendingException wenn die Quelle einen Einmalcode verlangt und die Anforderung offen ist
     */
    public AssetContent content(PageAttachmentList list, String attachmentId, boolean mayStore, String owner) {
        return unlocking(list.sourceId(), owner, () -> content(list, attachmentId, mayStore));
    }

    /**
     * Wie {@link #preview(PageAttachmentList, String, AttachmentPreview, boolean)} für Lesen auf Abruf durch
     * {@code owner}; Einmalcode wie bei {@link #content(PageAttachmentList, String, boolean, String)}.
     *
     * @throws SignInPendingException wenn die Quelle einen Einmalcode verlangt und die Anforderung offen ist
     */
    public AssetContent preview(PageAttachmentList list, String attachmentId, AttachmentPreview preview,
                                boolean mayStore, String owner) {
        return unlocking(list.sourceId(), owner, () -> preview(list, attachmentId, preview, mayStore));
    }

    /**
     * Wie {@link #viewImage(String, String, String, HarvestSource.ViewImage)} für Lesen auf Abruf durch
     * {@code owner}; Einmalcode wie bei {@link #content(PageAttachmentList, String, boolean, String)}.
     *
     * @throws SignInPendingException wenn die Quelle einen Einmalcode verlangt und die Anforderung offen ist
     */
    public AssetContent viewImage(String sourceId, String pageId, String pageVersion, HarvestSource.ViewImage image,
                                  String owner) {
        return unlocking(sourceId, owner, () -> viewImage(sourceId, pageId, pageVersion, image));
    }

    /**
     * Liest; verlangt die Quelle einen Einmalcode, wird einmal neu gelesen, falls die Quelle inzwischen freigeschaltet
     * ist, sonst die Anforderung angelegt. Code und Token sieht diese Stelle nie.
     */
    private <T> T unlocking(String sourceId, String owner, java.util.function.Supplier<T> read) {
        java.time.Instant started = clock.instant();
        try {
            return read.get();
        } catch (SignInRequiredException e) {
            if (owner == null || signIns == null || !signIns.interactive()) {
                throw e;
            }
            if (signIns.unlockedSince(sourceId, started)) {
                return read.get();
            }
            SignInRequest request = signIns.open(sourceId, owner, e.challenge());
            throw new SignInPendingException(sourceId, request.id(), e);
        }
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
            return read(stored.get());
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
        // Die Größe eines Bildes der Lese-Ansicht steht erst nach dem Download fest; Typerkennung und Ablegen laufen
        // dann unter der Schranke.
        try (LargeFileGate.Permit permit = gate.enter(sourceId, (long) content.length)) {
            String mediaType = mediaType(sourceId, image.mediaType() != null && !image.mediaType().isBlank()
                    ? image.mediaType() : sniff(content), content, viewFileName(image));
            Asset asset = new Asset(sourceId, pageId, attachmentId, version, image.title(), mediaType,
                    AssetKind.of(mediaType, image.title()), content.length, sha256(content), clock.instant());
            store.put(asset, content);
            return new AssetContent(asset, content);
        }
    }

    /**
     * Das draw.io-Diagramm hinter einem Bild der Lese-Ansicht als PNG (#190): Ist {@code image} die Vorschau, die das
     * Plugin neben einem Diagramm-Anhang der Seite ablegt ({@link Diagrams#isDrawioPreview}), wird das Diagramm aus
     * seinen Quelldaten gezeichnet ({@link #DRAWIO_PNG_RENDITION}, einmal je Quelldaten abgelegt). Die Quelldaten
     * kommen aus der Ablage oder wie bei {@link #viewImage} über die Quelle.
     *
     * @param pageVersion wie bei {@link #viewImage}; die Grafik hängt nur an den Quelldaten
     * @return leer, wenn das Bild keine solche Vorschau ist oder das Diagramm nicht darstellbar; dann gilt das Bild selbst
     * @throws SourceAccessException  beim Laden der Anhangsliste oder der Quelldaten, wie {@link #viewImage}
     * @throws AssetTooLargeException wenn die Quelldaten die Grenze überschreiten
     */
    public Optional<AssetContent> viewDiagram(String sourceId, String pageId, String pageVersion,
                                              HarvestSource.ViewImage image) {
        String filename = viewFileName(image);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return Optional.empty();
        }
        HarvestSource source = source(sourceId);
        PageAttachment diagram = null;
        for (PageAttachment attachment : source.attachments(pageId).attachments()) {
            if (Diagrams.isDrawio(attachment.mediaType(), attachment.title())
                    && Diagrams.isDrawioPreview(filename, Diagrams.drawioName(attachment.title()))) {
                diagram = attachment;
                break;
            }
        }
        if (diagram == null) {
            return Optional.empty();
        }
        PageAttachment found = diagram;
        Rendered rendered;
        try (LargeFileGate.Permit permit = gate.enter(sourceId, found.fileSize())) {
            byte[] mxfile = store.find(sourceId, found.attachmentId(), found.version()).map(store::read)
                    .orElseGet(() -> download(source, pageId, found).content());
            rendered = rendition(sourceId, pageId, DRAWIO_PNG_RENDITION, mxfile);
        }
        if (rendered.asset() == null) {
            return Optional.empty();
        }
        return Optional.of(new AssetContent(rendered.asset(), store.read(rendered.asset())));
    }

    /**
     * Der Dateiname eines Bildes der Lese-Ansicht: der letzte Pfadteil der Adresse (Confluence setzt als Titel die
     * Beschriftung des Diagramms, die Adresse endet auf {@code <Name>.png}), sonst sein Titel.
     */
    public static String viewFileName(HarvestSource.ViewImage image) {
        String src = image.src() == null ? "" : image.src();
        int end = src.length();
        for (char c : new char[] {'?', '#'}) {
            int at = src.indexOf(c);
            if (at >= 0) {
                end = Math.min(end, at);
            }
        }
        String path = src.substring(0, end);
        String name = path.substring(path.lastIndexOf('/') + 1);
        try {
            name = java.net.URLDecoder.decode(name.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // unvollständige Kodierung: der Name bleibt, wie er ist
        }
        if (!name.isBlank()) {
            return name;
        }
        return image.title() == null ? "" : image.title();
    }

    /** Die Anhänge einer Seite, live von der Quelle; lädt keine Bytes. */
    public List<PageAttachment> pageAttachments(String sourceId, String pageId) {
        return source(sourceId).attachments(pageId).attachments();
    }

    /**
     * Ob ein Bild der Lese-Ansicht die Vorschau eines draw.io-Diagramms dieser Anhänge ist, wie bei
     * {@link #viewDiagram}: dann gilt die textuelle Quelle des Diagramms, nicht das Bild (Angelo, 16.10.).
     */
    public static boolean diagramPreview(List<PageAttachment> attachments, HarvestSource.ViewImage image) {
        String filename = viewFileName(image);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return false;
        }
        for (PageAttachment attachment : attachments) {
            if (Diagrams.isDrawio(attachment.mediaType(), attachment.title())
                    && Diagrams.isDrawioPreview(filename, Diagrams.drawioName(attachment.title()))) {
                return true;
            }
        }
        return false;
    }

    /** Die Lese-Ansicht einer Seite mit Lesetext und Bildern ({@link HarvestSource#readingPage}); lädt keine Bytes. */
    public HarvestSource.ReadingPage readingPage(String sourceId, String pageId) {
        return source(sourceId).readingPage(pageId);
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
        Optional<Asset> found = store.find(sourceId, attachmentId, version).filter(a -> a.sha256().equals(sha256));
        Asset asset = found.orElseGet(() -> new Asset(sourceId, "-", attachmentId == null ? "-" : attachmentId,
                version, null, null, AssetKind.OTHER, 0, sha256, clock.instant()));
        byte[] content;
        // Ohne Eintrag ist die Größe unbekannt und zählt als groß.
        try (LargeFileGate.Permit permit = gate.enter(sourceId, found.map(Asset::size).orElse(null))) {
            content = store.read(asset);
        } catch (AssetStoreException e) {
            return Optional.empty();
        }
        return sha256(content).equals(sha256) ? Optional.of(content) : Optional.empty();
    }

    /** Version der draw.io-Grafik im Speicher; ändert sich mit dem Renderer, damit alte Grafiken neu entstehen. */
    public static final String DRAWIO_RENDITION = "drawio-svg-1";
    /** Version der draw.io-Grafik als PNG für die Lese-Ansicht ({@link #viewDiagram}). */
    public static final String DRAWIO_PNG_RENDITION = "drawio-png-1";
    /** Version der Mermaid-Grafik im Speicher. */
    public static final String MERMAID_RENDITION = "mermaid-png-1";

    /**
     * Die gezeichnete Grafik eines Diagramms (#190): einmal mit {@link Diagrams} gezeichnet und unter der Kennung
     * {@link #renditionId} seiner Quelldaten abgelegt; dieselben Quelldaten werden nie erneut gezeichnet. Ruft nie die
     * Quelle.
     *
     * @param renderer     {@link #DRAWIO_RENDITION}, {@link #DRAWIO_PNG_RENDITION} oder {@link #MERMAID_RENDITION}
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
            case DRAWIO_PNG_RENDITION -> Diagrams.drawioPng(source);
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

    /**
     * Der Text eines Dokument-Anhangs in seiner aktuellen Version (#183): aus der Ablage oder, falls es für diese Bytes
     * und diese Extraktion noch keinen gibt, gelesen und abgelegt; lädt den Anhang dafür bei Bedarf über die Quelle
     * und legt ihn ab, wie {@link #describe}. Bilder, Grafiken und Diagrammdaten haben hier keinen Text.
     *
     * @throws DocumentTextException {@code NOT_CONFIGURED}, {@code UNSUPPORTED}, {@code TOO_LARGE},
     *                               {@code TIMEOUT}, {@code BUSY} oder {@code FAILED}
     */
    public AssetText text(PageAttachmentList list, String attachmentId) {
        return text(list, attachmentId, true);
    }

    /**
     * Wie {@link #text(PageAttachmentList, String)}; mit {@code mayStore=false} wird weder der Anhang noch sein Text
     * abgelegt, wie bei {@link #content(PageAttachmentList, String, boolean)}: Ein vorhandener Text kommt aus der
     * Ablage, sonst wird er nur gelesen und ausgeliefert. In den Volltextindex kommt nur ein abgelegter Text.
     */
    public AssetText text(PageAttachmentList list, String attachmentId, boolean mayStore) {
        DocumentText extraction = documents();
        PageAttachment attachment = attachment(list, attachmentId);
        Optional<Asset> stored = store.find(list.sourceId(), attachment.attachmentId(), attachment.version());
        // Ein abgelegter Text braucht die Bytes nicht: weder Lesen der Datei noch ein Platz der Schranke.
        Optional<AssetText> existing = stored.isPresent() ? storedText(extraction, stored.get()) : Optional.empty();
        Asset asset;
        AssetText text;
        if (existing.isPresent()) {
            asset = stored.get();
            text = existing.get();
        } else {
            try (LargeFileGate.Permit permit = gate.enter(list.sourceId(),
                    stored.map(Asset::size).orElse(attachment.fileSize()))) {
                AssetContent content = content(list, attachmentId, mayStore);
                asset = content.asset();
                existing = storedText(extraction, asset);
                text = existing.isPresent() ? existing.get() : extract(extraction, content, mayStore);
            }
        }
        if ((mayStore || existing.isPresent()) && indexText(list, asset, text.text(), extractionKey(extraction))) {
            flushIndex();
        }
        return text;
    }

    private AssetText text(DocumentText extraction, AssetContent content) {
        return storedText(extraction, content.asset()).orElseGet(() -> extract(extraction, content, true));
    }

    /** Der abgelegte Text eines Dokuments; für alles andere {@code UNSUPPORTED}. */
    private Optional<AssetText> storedText(DocumentText extraction, Asset asset) {
        if (asset.kind() != AssetKind.OTHER) {
            throw new DocumentTextException(DocumentTextException.Reason.UNSUPPORTED, "Kein Dokument", null);
        }
        return store.text(asset.sha256(), extraction.id(), extraction.version());
    }

    private AssetText extract(DocumentText extraction, AssetContent content, boolean keep) {
        Asset asset = content.asset();
        DocumentText.Extracted extracted = extraction.extract(asset.title(), asset.mediaType(), content.content());
        AssetText text = new AssetText(asset.sha256(), extraction.id(), extraction.version(), extracted.text(),
                extracted.truncated(), clock.instant());
        if (keep) {
            store.putText(text);
        }
        return text;
    }

    /**
     * Übernimmt den abgelegten Text eines Anhangs in den Volltextindex: bei Dokumenten den Text der Extraktion, bei
     * Bildern eine vorhandene Beschreibung des eingestellten Modells ({@link #storedDescription}); erzeugt nichts und
     * ruft nie ein Modell auf. Ein Dokument ohne Text (nicht
     * lesbar, noch nicht gelesen) steht nur mit seinem Dateinamen darin, wie im Enterprise AI Client.
     *
     * @return ob der Index geändert wurde
     */
    private boolean indexStored(PageAttachmentList list, Asset asset) {
        if (texts == null) {
            return false;
        }
        try {
            if (asset.kind() == AssetKind.OTHER && documents != null) {
                String text = store.text(asset.sha256(), documents.id(), documents.version()).map(AssetText::text)
                        .orElse("");
                return indexText(list, asset, text, text.isEmpty() ? "" : extractionKey(documents));
            }
            if (asset.kind() == AssetKind.IMAGE || asset.kind() == AssetKind.VECTOR) {
                // Wie im EAC (DocumentSourceFileTextExtractor → describe), aber ohne Modellaufruf: nur die vorhandene
                // Beschreibung des eingestellten Modells aus der Ablage (ADR 0021); ohne sie nur der Dateiname. Keine
                // OCR.
                Optional<ImageDescription> description = storedDescription(asset.sha256());
                return description.isPresent() ? indexDescription(list, asset, description.get())
                        : indexText(list, asset, "", "");
            }
        } catch (AssetStoreException e) {
            LOG.log(System.Logger.Level.WARNING, "Anhangstext nicht lesbar: source={0} ({1})", asset.sourceId(),
                    e.getClass().getName());
            return false;
        }
        // Diagrammdaten (draw.io) und Dokumente ohne Extraktion: nur der Dateiname, wie Bilder ohne Beschreibung.
        return indexText(list, asset, "", "");
    }

    /**
     * Nach einem gescheiterten Ablegen: Liegt die aktuelle Version des Anhangs nicht in der Ablage, stammt ein
     * vorhandener Indexeintrag von einer älteren Version (oder fehlt); er fällt heraus, damit die Suche keinen Text alter
     * Bytes liefert. Die nächste erfolgreiche Ernte legt ihn neu an.
     *
     * @return ob der Index geändert worden sein kann
     */
    private boolean forgetStale(PageAttachmentList list, PageAttachment attachment) {
        if (texts == null) {
            return false;
        }
        try {
            if (store.find(list.sourceId(), attachment.attachmentId(), attachment.version()).isPresent()) {
                return false;
            }
            PageKey page = new PageKey(list.sourceId(), list.attachments().pageId());
            if (texts.attachmentInputSha256(page, attachment.attachmentId()).isEmpty()) {
                return false;
            }
            texts.removeAttachment(page, attachment.attachmentId());
            return true;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Veralteter Anhangstext in {0} nicht entfernt ({1})",
                    list.sourceId(), e.getClass().getName());
            return false;
        }
    }

    private boolean indexDescription(PageAttachmentList list, Asset asset, ImageDescription description) {
        return indexText(list, asset, description.text(),
                "describer:" + description.describer() + ":" + description.modelVersion());
    }

    private static String extractionKey(DocumentText extraction) {
        return "text:" + extraction.id() + ":" + extraction.version();
    }

    /**
     * Legt den Text eines Anhangs mit Seite und Space der Anhangsliste im Volltextindex ab, wenn er dort nicht schon in
     * diesem Stand liegt. Fehler bleiben hier; der Index ist neu aufbaubar.
     *
     * @param origin woher der Text stammt (Extraktion oder Modell mit Version), leer ohne Text
     * @return ob der Index geändert wurde
     */
    private boolean indexText(PageAttachmentList list, Asset asset, String text, String origin) {
        if (texts == null) {
            return false;
        }
        PageKey page = new PageKey(list.sourceId(), list.attachments().pageId());
        String title = asset.title() == null ? "" : asset.title();
        String input = sha256((asset.sha256() + "\n" + origin + "\n" + list.spaceKey() + "\n" + title)
                .getBytes(StandardCharsets.UTF_8));
        try {
            if (texts.attachmentInputSha256(page, asset.attachmentId()).filter(input::equals).isPresent()) {
                return false;
            }
            texts.putAttachment(new PageTextIndex.AttachmentEntry(page, list.spaceKey(), asset.attachmentId(), title,
                    text, input));
            return true;
        } catch (RuntimeException e) {
            // Ohne Meldung und ohne Seite: sie könnte Text oder IDs enthalten.
            LOG.log(System.Logger.Level.WARNING, "Volltextindex eines Anhangs in {0} nicht aktualisiert ({1})",
                    list.sourceId(), e.getClass().getName());
            return false;
        }
    }

    /**
     * Nach einer Ernte: Anhänge, die die vollständige Liste nicht mehr nennt, fallen aus dem Index; Änderungen werden
     * dauerhaft.
     */
    private void finishIndexing(PageAttachmentList list, boolean indexed) {
        if (texts == null) {
            return;
        }
        try {
            if (list.attachments().complete()) {
                Set<String> current = new HashSet<>();
                list.attachments().attachments().forEach(a -> current.add(a.attachmentId()));
                texts.retainAttachments(new PageKey(list.sourceId(), list.attachments().pageId()), current);
                indexed = true;
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Volltextindex der Anhänge in {0} nicht bereinigt ({1})",
                    list.sourceId(), e.getClass().getName());
        }
        if (indexed) {
            flushIndex();
        }
    }

    private void flushIndex() {
        try {
            texts.flush();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Volltextindex nicht gesichert ({0})", e.getClass().getName());
        }
    }

    /**
     * Liest beim Ablegen gleich den Text eines Dokuments (#183), damit er als Anreicherung bereitliegt. Ein Fehler hält
     * das Ablegen nicht auf und wird mit festem Code protokolliert; ein nicht lesbares Format wird erst auf Abruf
     * ({@link #text}) erneut versucht, nicht bei jeder Ernte.
     */
    private void harvestText(AssetContent content) {
        if (documents == null || content.asset().kind() != AssetKind.OTHER) {
            return;
        }
        try {
            text(documents, content);
        } catch (DocumentTextException e) {
            if (e.reason() != DocumentTextException.Reason.UNSUPPORTED) {
                LOG.log(System.Logger.Level.INFO, "Kein Dokumenttext beim Ablegen: source={0}, code={1}",
                        content.asset().sourceId(), e.reason().code());
            }
        } catch (AssetStoreException e) {
            LOG.log(System.Logger.Level.WARNING, "Dokumenttext nicht ablegbar: source={0} ({1})",
                    content.asset().sourceId(), e.getClass().getName());
        }
    }

    /**
     * Holt den Text eines schon abgelegten Dokuments aus der Ablage nach, etwa für Ablagen von vor #183, ohne den
     * Anhang neu zu laden. Je Laufzeit nur ein Versuch je Inhalt, damit ein nicht lesbares Dokument nicht bei jeder
     * Ernte erneut extrahiert wird.
     */
    private void backfillText(Asset asset) {
        if (documents == null || asset.kind() != AssetKind.OTHER
                || store.text(asset.sha256(), documents.id(), documents.version()).isPresent()) {
            return;
        }
        if (backfilled.size() > MAX_BACKFILLED) {
            backfilled.clear();
        }
        if (!backfilled.add(asset.sha256())) {
            return;
        }
        try (LargeFileGate.Permit permit = gate.enter(asset.sourceId(), asset.size())) {
            byte[] content;
            try {
                content = store.read(asset);
            } catch (AssetStoreException e) {
                LOG.log(System.Logger.Level.WARNING, "Dokument nicht lesbar: source={0} ({1})", asset.sourceId(),
                        e.getClass().getName());
                return;
            }
            harvestText(new AssetContent(asset, content));
        } catch (SourceAccessException e) {
            // Schranke für große Dateien voll: vorübergehend, die nächste Ernte versucht es erneut.
            backfilled.remove(asset.sha256());
            LOG.log(System.Logger.Level.INFO, "Dokumenttext später nachholen: source={0}, code={1}",
                    asset.sourceId(), code(e.reason()));
        }
    }

    private DocumentText documents() {
        if (documents == null) {
            throw new DocumentTextException(DocumentTextException.Reason.NOT_CONFIGURED,
                    "Keine Textextraktion konfiguriert", null);
        }
        return documents;
    }

    /** Medientypen ohne Aussage: Die Quelle wusste den Typ nicht. */
    private static final java.util.Set<String> UNSPECIFIC = java.util.Set.of("application/octet-stream",
            "binary/octet-stream", "application/x-octet-stream", "application/unknown", "application/download",
            "application/x-download", "application/force-download");
    /** Erkannte Behälter ohne Aussage über das Dokument darin (ZIP, OLE2, Tika-Oberbegriffe). */
    private static final java.util.Set<String> CONTAINERS = java.util.Set.of("application/zip",
            "application/x-zip-compressed", "application/x-ole-storage", "application/x-tika-ooxml",
            "application/x-tika-msoffice", "application/x-tika-ooxml-protected", "application/x-tika-msoffice-embedded");

    /**
     * Der Medientyp beim Ablegen (#183): die Angabe der Quelle, es sei denn, der Inhalt zeigt eindeutig etwas anderes.
     * Ohne {@link DocumentText} bleibt die Angabe der Quelle. Der Dateiname verfeinert nur, was der Inhalt offenlässt
     * ({@link DocumentText#detect(String, byte[])}).
     */
    private String mediaType(String sourceId, String declared, byte[] content, String fileName) {
        if (documents == null) {
            return declared;
        }
        String detected;
        try {
            detected = documents.detect(fileName, content).orElse(null);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Typerkennung am Inhalt gescheitert: source={0} ({1})", sourceId,
                    e.getClass().getName());
            return declared;
        }
        String chosen = chooseMediaType(declared, detected);
        if (chosen != null && !chosen.equals(declared)) {
            LOG.log(System.Logger.Level.DEBUG, "Medientyp am Inhalt erkannt: source={0}, Quelle={1}, Inhalt={2}",
                    sourceId, declared, chosen);
        }
        return chosen;
    }

    /**
     * Wählt zwischen der Angabe der Quelle und dem am Inhalt erkannten Typ: Ohne oder mit nichtssagender Angabe gilt der
     * erkannte, sofern er mehr als ein Behälter ist. Sonst ersetzt er die Angabe nur, wenn er ein Binärformat mit
     * Signatur ist (PDF, Office, Bild …): Textformate (XML, JSON, Klartext) sind am Inhalt nicht sicher zu
     * unterscheiden, etwa draw.io-Daten von beliebigem XML, und bleiben so, wie die Quelle sie nennt.
     *
     * @param detected am Inhalt erkannter Typ oder {@code null}
     */
    static String chooseMediaType(String declared, String detected) {
        String found = base(detected);
        if (found.isEmpty() || UNSPECIFIC.contains(found) || CONTAINERS.contains(found)) {
            return declared;
        }
        String named = base(declared);
        if (named.isEmpty() || UNSPECIFIC.contains(named)) {
            return found;
        }
        if (named.equals(found) || textual(found)) {
            return declared;
        }
        return found;
    }

    private static String base(String mediaType) {
        return mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }

    private static boolean textual(String mediaType) {
        return mediaType.startsWith("text/") || mediaType.equals("application/xml")
                || mediaType.equals("application/json") || mediaType.endsWith("+xml")
                || mediaType.endsWith("+json");
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

    /** Version der Vorschauen, die die Quelle selbst erzeugt ({@link #preview}); ändert sich, damit alte neu entstehen. */
    public static final String PREVIEW_RENDITION = "source-preview-1";

    /**
     * Eine Vorschau eines Anhangs in seiner aktuellen Version, wie die Quelle sie selbst erzeugt (#183; bei Confluence
     * die Dokumentkonvertierung, etwa ein Office-Dokument als PDF): aus der Ablage oder über die Quelle geladen. Ohne
     * Art in der Reihenfolge {@link AttachmentPreview#order()}, wie der Lesebereich des Enterprise AI Client
     * ({@code BrowseKnowledgeSourceUseCase#attachmentPreview}): die erste, die die Quelle liefern kann; eine
     * Verweigerung beendet die Suche, weil die nächste Art genauso scheitert. Abgelegt wird je Anhang, Version und Art
     * ({@link #previewId}, Version {@link #previewVersion}), nie ohne Version der Quelle.
     *
     * @param preview  gewünschte Art oder {@code null} für die erste verfügbare
     * @param mayStore wie bei {@link #content(PageAttachmentList, String, boolean)}
     * @throws SourceAccessException  {@code NOT_FOUND}, wenn der Anhang nicht zur Seite gehört oder es keine Vorschau
     *                                gibt, {@code UNAVAILABLE}, solange die Quelle sie noch erzeugt, {@code UNSUPPORTED}
     *                                für Quellen ohne {@link SourceCapability#ATTACHMENT_PREVIEWS}, sonst wie die Quelle
     * @throws AssetTooLargeException wenn die Vorschau die Grenze überschreitet
     */
    public AssetContent preview(PageAttachmentList list, String attachmentId, AttachmentPreview preview,
                                boolean mayStore) {
        PageAttachment attachment = attachment(list, attachmentId);
        String sourceId = list.sourceId();
        List<AttachmentPreview> kinds = preview == null ? AttachmentPreview.order() : List.of(preview);
        String version = previewVersion(attachment.version());
        for (AttachmentPreview kind : kinds) {
            Optional<Asset> stored = store.find(sourceId, previewId(attachmentId, kind), version);
            if (stored.isPresent()) {
                return read(stored.get());
            }
        }
        HarvestSource source = source(sourceId);
        if (!source.capabilities().contains(SourceCapability.ATTACHMENT_PREVIEWS)) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNSUPPORTED,
                    "Quelle erzeugt keine Vorschauen von Anhängen", null);
        }
        // Die Größe der Vorschau ist vorher unbekannt; es gilt die des Anhangs (unbekannt zählt als groß).
        try (LargeFileGate.Permit permit = gate.enter(sourceId, attachment.fileSize())) {
            return fetchPreview(list, attachment, source, kinds, version, mayStore);
        }
    }

    private AssetContent fetchPreview(PageAttachmentList list, PageAttachment attachment, HarvestSource source,
                                      List<AttachmentPreview> kinds, String version, boolean mayStore) {
        String sourceId = list.sourceId();
        String attachmentId = attachment.attachmentId();
        SourceAccessException last = null;
        for (AttachmentPreview kind : kinds) {
            byte[] content;
            try {
                content = previewBytes(source, attachment, kind);
            } catch (SourceAccessException e) {
                if (!skippable(e)) {
                    throw e;
                }
                last = e;
                continue;
            }
            String mediaType = mediaType(sourceId, previewType(content), content, null);
            Asset asset = new Asset(sourceId, list.attachments().pageId(), previewId(attachmentId, kind), version,
                    previewTitle(attachment.title(), mediaType), mediaType, AssetKind.of(mediaType, null),
                    content.length, sha256(content), clock.instant());
            if (mayStore && version != null) {
                store.put(asset, content);
            }
            return new AssetContent(asset, content);
        }
        throw last;
    }

    /** Lädt eine Vorschau über die Quelle, mit derselben Begrenzung gleichzeitiger Downloads wie Anhänge. */
    private byte[] previewBytes(HarvestSource source, PageAttachment attachment, AttachmentPreview kind) {
        try {
            if (!downloads.tryAcquire(DOWNLOAD_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new SourceAccessException(source.id(), SourceAccessException.Reason.RATE_LIMITED,
                        "Zu viele gleichzeitige Downloads von Vorschauen", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.UNAVAILABLE,
                    "Download abgebrochen", e);
        }
        byte[] content;
        try {
            content = source.attachmentPreview(attachment, kind, maxBytes);
        } catch (AttachmentTooLargeException e) {
            throw new AssetTooLargeException(source.id(), maxBytes);
        } finally {
            downloads.release();
        }
        if (content.length > maxBytes) {
            throw new AssetTooLargeException(source.id(), maxBytes);
        }
        return content;
    }

    /**
     * Ob nach diesem Fehler die nächste Vorschauart versucht wird: nur, wenn es diese Art nicht gibt oder sie noch
     * entsteht; Verweigerung, Anmeldung, Drosselung und fehlende Fähigkeit gelten für jede Art.
     */
    private static boolean skippable(SourceAccessException e) {
        return switch (e.reason()) {
            case NOT_FOUND, UNAVAILABLE, INVALID_RESPONSE -> true;
            default -> false;
        };
    }

    /** Kennung einer Vorschau im Speicher: Art und Anhang. */
    public static String previewId(String attachmentId, AttachmentPreview preview) {
        return "preview-" + preview.name().toLowerCase(Locale.ROOT) + "-" + attachmentId;
    }

    /** Version einer Vorschau im Speicher: {@link #PREVIEW_RENDITION} und die Version des Anhangs, ohne diese keine. */
    public static String previewVersion(String attachmentVersion) {
        return attachmentVersion == null || attachmentVersion.isBlank() ? null
                : PREVIEW_RENDITION + ":" + attachmentVersion;
    }

    /** Medientyp einer Vorschau nach ihren ersten Bytes: PDF oder Bild. */
    static String previewType(byte[] content) {
        if (content.length > 4 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F') {
            return "application/pdf";
        }
        return sniff(content);
    }

    /** Dateiname einer Vorschau: der des Anhangs mit der Endung der Vorschau, etwa {@code Planung.xlsx.pdf}. */
    static String previewTitle(String title, String mediaType) {
        String extension = switch (base(mediaType)) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/svg+xml" -> ".svg";
            default -> "";
        };
        String name = title == null ? "" : title;
        return name.toLowerCase(Locale.ROOT).endsWith(extension) ? name : name + extension;
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
        DescribedImage described = store.find(list.sourceId(), attachment.attachmentId(), attachment.version())
                .flatMap(asset -> store.description(asset.sha256(), model.id(), model.modelVersion())
                        .map(description -> new DescribedImage(asset, description, false)))
                .orElseThrow(() -> new ImageDescriptionException(ImageDescriptionException.Reason.NOT_FOUND,
                        "Noch keine Beschreibung", null));
        return indexed(list, described);
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
        ImageDescriber model = describer(list.sourceId());
        PageAttachment attachment = attachment(list, attachmentId);
        requireImage(attachment.mediaType(), attachment.title());
        // Laden bzw. Lesen und der Modellaufruf belegen die Schranke je für sich (content, generate): Der Modellaufruf
        // kann Minuten dauern und hält einen Platz nur, wenn das Bild nach seiner bekannten Größe groß ist.
        return indexed(list, describe(model, content(list, attachmentId)));
    }

    /**
     * Beschreibt ein Bild der Lese-Ansicht einer Seite (ADR 0021) wie {@link #describe(PageAttachmentList, String)}:
     * lädt es bei Bedarf wie {@link #viewImage}, fragt das Modell je Bildbytes und Modell nur einmal. Nur Rasterbilder;
     * SVG und andere Arten melden {@code NOT_AN_IMAGE}.
     *
     * @throws ImageDescriptionException {@code NOT_CONFIGURED}, {@code NOT_RELEASED}, {@code NOT_AN_IMAGE},
     *                                   {@code BUSY} oder {@code FAILED}
     * @throws SourceAccessException     wie {@link #viewImage}
     * @throws AssetTooLargeException    wie {@link #viewImage}
     */
    public DescribedImage describeViewImage(String sourceId, String pageId, String pageVersion,
                                            HarvestSource.ViewImage image) {
        ImageDescriber model = describer(sourceId);
        AssetContent content = viewImage(sourceId, pageId, pageVersion, image);
        // Nur Rasterbilder: SVG ist Text und geht als Quelle in die Analyse, nicht als Bild an das Modell.
        if (content.asset().kind() != AssetKind.IMAGE) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_AN_IMAGE, "Kein Rasterbild",
                    null);
        }
        return describe(model, content);
    }

    /**
     * Die vorhandene Beschreibung genau dieser Bildbytes durch das eingestellte Modell; erzeugt nichts und fragt die
     * Quelle nicht.
     */
    public Optional<ImageDescription> storedDescription(String imageSha256) {
        if (describer == null || !describer.available() || imageSha256 == null) {
            return Optional.empty();
        }
        return store.description(imageSha256, describer.id(), describer.modelVersion());
    }

    /** Der eingestellte Beschreiber, leer ohne Beschreiber oder ohne Modell (ADR 0021). */
    public Optional<ImageDescriber> describerInUse() {
        return describer == null || !describer.available() ? Optional.empty() : Optional.of(describer);
    }

    private DescribedImage describe(ImageDescriber model, AssetContent content) {
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

    /** Die Beschreibung kommt wie der Text eines Dokuments in den Volltextindex (#183). */
    private DescribedImage indexed(PageAttachmentList list, DescribedImage described) {
        if (indexDescription(list, described.asset(), described.description())) {
            flushIndex();
        }
        return described;
    }

    private ImageDescription generate(ImageDescriber model, AssetContent content) {
        try (LargeFileGate.Permit permit = gate.enter(content.asset().sourceId(), content.asset().size())) {
            return generateGated(model, content);
        }
    }

    private ImageDescription generateGated(ImageDescriber model, AssetContent content) {
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
        if (describer == null || !describer.available()) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_CONFIGURED,
                    "Kein Bildbeschreiber konfiguriert", null);
        }
        return describer;
    }

    /** Der Beschreiber für Bilder dieser Quelle; nicht freigegebene Bilder gehen nie an das Modell. */
    private ImageDescriber describer(String sourceId) {
        ImageDescriber model = describer();
        if (!model.mayDescribe(sourceId)) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_RELEASED,
                    "Quelle nicht für den KI-Dienst freigegeben", null);
        }
        return model;
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
        // Große Dateien zusätzlich unter der gemeinsamen Schranke, bis Typerkennung, Hash und Ablegen fertig sind.
        try (LargeFileGate.Permit permit = gate.enter(source.id(), attachment.fileSize())) {
            return downloadGated(source, pageId, attachment, keep);
        }
    }

    private AssetContent downloadGated(HarvestSource source, String pageId, PageAttachment attachment, boolean keep) {
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
        String declaredType = described.mediaType() != null && !described.mediaType().isBlank()
                ? described.mediaType() : attachment.mediaType();
        String title = described.title().isEmpty() ? attachment.title() : described.title();
        String mediaType = mediaType(source.id(), declaredType, content, title);
        // Hat eine Binärsignatur die Angabe der Quelle ersetzt, zählt die Dateiendung (.drawio, .svg) nicht mehr: Ein PDF
        // namens report.drawio ist ein Dokument, kein Diagramm.
        boolean corrected = !base(mediaType).equals(base(declaredType)) && !textual(base(mediaType));
        AssetKind kind = corrected ? AssetKind.of(mediaType, null) : AssetKind.of(mediaType, title);
        Asset asset = new Asset(source.id(), pageId, attachment.attachmentId(), version, title, mediaType, kind,
                content.length, sha256(content), file.retrievedAt());
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
