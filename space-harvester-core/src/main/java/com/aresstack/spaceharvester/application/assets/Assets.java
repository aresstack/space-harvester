package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.diagrams.Diagrams;
import com.aresstack.spaceharvester.application.marks.SlideFrames;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.signin.SignInPendingException;
import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.AttachmentPreview;
import com.aresstack.spaceharvester.application.sources.AttachmentTooLargeException;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.StreamedAttachment;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import java.util.function.Consumer;

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
public class Assets implements DocumentInventory {

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

    /**
     * Die Grenze für Downloads aus der Konfiguration ({@code space-harvester.assets.max-size}): {@code 0} heißt ohne
     * Grenze ({@link #UNLIMITED}), der Standard; jede andere Zahl bleibt, wie sie ist.
     */
    public static long maxBytes(long configured) {
        return configured == 0 ? UNLIMITED : configured;
    }

    /** Gleichzeitige Downloads von Anhängen, und wie lange ein weiterer auf einen freien Platz wartet. */
    static final int MAX_CONCURRENT_DOWNLOADS = 4;
    /** Obergrenze der gemerkten Nachhol-Versuche, danach beginnt die Liste neu. */
    static final int MAX_BACKFILLED = 10_000;
    /**
     * Bis zu dieser Größe liest ein Download die Datei nach dem Ablegen auch in den Speicher, für Typerkennung am ganzen
     * Inhalt und Text; so groß wie die Vorgabe der Speicherschranke der Textextraktion ({@link ExtractionLimits#DEFAULT_MEMORY_LIMITS}).
     */
    static final long IN_MEMORY_BYTES = 20L * 1024 * 1024;
    /**
     * Größte Datei, die {@link #content} und {@link #stored} mit ihren Bytes liefern, unabhängig von der Grenze für
     * Downloads: so groß wie bisher die Standardgrenze für Anhänge ({@code READER_MAX_BYTES} im Enterprise AI Client).
     * Größere Dateien gibt es nur als Strom ({@link #stream}, {@link #openStored}), damit keine den Speicher füllt.
     */
    public static final long MAX_IN_MEMORY_BYTES = 100L * 1024 * 1024;
    private volatile long maxInMemoryBytes = MAX_IN_MEMORY_BYTES;
    /** Ohne Grenze für Downloads: Dateien beliebiger Größe fließen in die Ablage (Angelo 11.11.2026). */
    public static final long UNLIMITED = Long.MAX_VALUE;
    private static final java.time.Duration DOWNLOAD_WAIT = java.time.Duration.ofSeconds(30);
    private final Downloads downloads = new Downloads(MAX_CONCURRENT_DOWNLOADS);
    private int downloadLimit = MAX_CONCURRENT_DOWNLOADS;
    /** Strukturierte Extraktion aus der Datei (ADR 0026, AP3b); ohne sie gilt der bisherige Weg über Byte-Arrays. */
    private volatile DocumentExtractions extractions;
    /** Hört auf neu indexierte Dokumente (ADR 0026, AP9a), etwa für ihre Analysen; ohne ihn geschieht nichts. */
    private volatile IndexedDocuments indexedDocuments;
    /** Ob eine Seite auf der Liste freigegebener Seiten steht (ADR 0028); ohne Liste keine. */
    private volatile java.util.function.Predicate<PageKey> pageReleases = key -> false;

    /** Eine Schranke, deren Plätze sich zur Laufzeit ändern lassen ({@link #downloadLimit(int)}). */
    private static final class Downloads extends Semaphore {
        Downloads(int permits) {
            super(permits);
        }

        void shrink(int by) {
            reducePermits(by);
        }
    }

    /**
     * Wie viele Dateien höchstens gleichzeitig von den Quellen geladen werden: mindestens
     * {@value #MAX_CONCURRENT_DOWNLOADS}, und so viele, dass jeder Download-Platz der Aufträge
     * ({@code jobs.parallel-downloads}) und daneben die Lese-Ansicht einen hat; sonst warteten Aufträge auf diese Schranke
     * und zählten das als Drosselung.
     *
     * @param jobDownloads gleichzeitig ladende Aufträge
     */
    private volatile DownloadDurations durations = new DownloadDurations();

    /** Misst ab jetzt in {@code durations}, etwa mit einem über Neustarts gespeicherten Stand. */
    public void durations(DownloadDurations durations) {
        this.durations = java.util.Objects.requireNonNull(durations, "durations");
    }

    /** Durchschnittliche Dauer je tatsächlich von der Quelle geladener Datei, getrennt für Bilder und Anhänge. */
    public DownloadDurations durations() {
        return durations;
    }

    /**
     * Schließt die strukturierte Extraktion an (ADR 0026, AP3b): Dokumente werden aus der abgelegten Datei gelesen,
     * ohne die Grenze von {@link #IN_MEMORY_BYTES}, und ihr Text liegt unter dieser Extraktion. Typerkennung und
     * Dokumente, die nicht abgelegt werden dürfen, bleiben beim bisherigen {@link DocumentText}.
     */
    public void documentExtractions(DocumentExtractions extractions) {
        this.extractions = extractions;
    }

    /** Die angeschlossene strukturierte Extraktion; leer ohne. */
    @Override
    public Optional<DocumentExtractions> documentExtractions() {
        return Optional.ofNullable(extractions);
    }

    /**
     * Die abgelegten Dokument-Anhänge dieser Seiten, die im Volltextindex ihrer Seite stehen (also nicht aus der
     * vollständigen Liste entfernt sind), je Anhang in der zuletzt abgelegten Version (ADR 0026, AP9a: Bestand der
     * Analysen je Dokument). Liest nur die Einträge der Ablage, nichts von der Quelle.
     */
    @Override
    public List<Asset> indexedDocuments(String sourceId, java.util.Collection<String> pageIds) {
        if (texts == null || pageIds.isEmpty()) {
            return List.of();
        }
        Set<String> pages = Set.copyOf(pageIds);
        Map<String, Asset> latest = new java.util.LinkedHashMap<>();
        try (java.util.stream.Stream<Asset> entries = store.entries(sourceId)) {
            entries.filter(asset -> asset.kind() == AssetKind.OTHER && pages.contains(asset.pageId()))
                    .forEach(asset -> latest.merge(asset.attachmentId(), asset,
                            (a, b) -> b.retrievedAt().isAfter(a.retrievedAt()) ? b : a));
        }
        List<Asset> indexed = new java.util.ArrayList<>();
        for (Asset asset : latest.values()) {
            if (texts.attachmentInputSha256(new PageKey(sourceId, asset.pageId()), asset.attachmentId()).isPresent()) {
                indexed.add(asset);
            }
        }
        return List.copyOf(indexed);
    }

    /** Schließt den Hörer auf neu indexierte Dokumente an (ADR 0026, AP9a). */
    public void indexedDocuments(IndexedDocuments listener) {
        this.indexedDocuments = listener;
    }

    private volatile java.util.function.Consumer<PageKey> transcriptChanges = key -> { };

    /**
     * Schließt den Hörer an, der erfährt, dass Transkript oder Korrekturen einer Seite abgelegt oder gelöscht wurden
     * (Angelo 20.11.2026: die Analysen rechnen dann über den neuen Text, ADR 0027: ohne Polling). Er wird im
     * schreibenden Thread gerufen und darf nicht lange arbeiten.
     */
    public void transcriptChanges(java.util.function.Consumer<PageKey> listener) {
        this.transcriptChanges = java.util.Objects.requireNonNull(listener, "listener");
    }

    private void transcriptChanged(String sourceId, String pageId) {
        try {
            transcriptChanges.accept(new PageKey(sourceId, pageId));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Hörer auf Transkripte scheiterte ({0})", e.getClass().getName());
        }
    }

    /**
     * Schließt die Liste freigegebener Seiten an (ADR 0028): Bilder einer Seite darauf gehen auch aus einer nicht
     * freigegebenen Quelle an das Modell. Gefragt wird bei jeder Beschreibung.
     */
    public void pageReleases(java.util.function.Predicate<PageKey> released) {
        this.pageReleases = Objects.requireNonNull(released, "released");
    }

    /** Ob die Seite auf der Liste freigegebener Seiten steht (ADR 0028). */
    public boolean pageReleased(PageKey key) {
        return pageReleases.test(key);
    }

    /** Kennung der Extraktion, unter der Texte liegen. */
    private String textExtractor() {
        DocumentExtractions current = extractions;
        return current != null ? current.id() : documents.id();
    }

    /** Version der Extraktion, unter der Texte liegen. */
    private String textVersion() {
        DocumentExtractions current = extractions;
        return current != null ? current.version() : documents.version();
    }

    public synchronized void downloadLimit(int jobDownloads) {
        int wanted = Math.max(MAX_CONCURRENT_DOWNLOADS, jobDownloads + 2);
        if (wanted > downloadLimit) {
            downloads.release(wanted - downloadLimit);
        } else if (wanted < downloadLimit) {
            downloads.shrink(downloadLimit - wanted);
        }
        downloadLimit = wanted;
    }
    /** Abgelegte Dokumente, deren Text beim Ernten schon einmal nachgeholt wurde (je Laufzeit, nach SHA-256). */
    private final java.util.Set<String> backfilled = ConcurrentHashMap.newKeySet();

    /**
     * Gleichzeitige Läufe des Bildbeschreibers ohne eigene Einstellung; sonst so viele, wie der Beschreiber nennt
     * ({@link ImageDescriber#parallel()}, {@code ai.parallel}). Ein weiterer scheitert sofort mit {@code BUSY}. Früher
     * fest 2: Damit lief „Folien beschreiben“ mit nur zwei Anfragen, obwohl ein Ollama-Server mit
     * {@code OLLAMA_NUM_PARALLEL=8} sechs Bildanfragen gleichzeitig beantwortet (gemessen 20.11.2026). Dasselbe Bild
     * für dasselbe Modell läuft nie zweimal gleichzeitig: weitere Aufrufer warten höchstens {@link #DESCRIBE_WAIT} auf
     * den laufenden.
     */
    public static final int MAX_CONCURRENT_DESCRIPTIONS = 4;
    private static final java.time.Duration DESCRIBE_WAIT = java.time.Duration.ofMinutes(2);
    /** Laufende Beschreibungen; die Grenze kommt bei jedem Aufruf aus der Einstellung. */
    private final java.util.concurrent.atomic.AtomicInteger describingNow =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Eigene Kontingente (Angelo 21.11.2026: „dann hat jeder Job quasi das gleiche Kontingent“): Ein Auftrag, der seine
     * Beschreibungen in {@link #inLane} laufen lässt, zählt sie in seiner eigenen Spur, nicht in {@link #describingNow};
     * so verdrängen mehrere Hintergrundaufträge weder einander noch die Beschreibungen, die der Benutzer anstößt.
     */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> lanes = new java.util.HashMap<>();
    private static final ThreadLocal<String> LANE = new ThreadLocal<>();

    /**
     * Führt {@code work} in der Spur {@code lane} aus: Beschreibungen in diesem Thread zählen gegen ein eigenes
     * Kontingent von {@link ImageDescriber#parallel()} gleichzeitigen Aufrufen.
     */
    public static <T> T inLane(String lane, java.util.function.Supplier<T> work) {
        String before = LANE.get();
        LANE.set(Objects.requireNonNull(lane, "lane"));
        try {
            return work.get();
        } finally {
            if (before == null) {
                LANE.remove();
            } else {
                LANE.set(before);
            }
        }
    }
    private final Map<String, CompletableFuture<ImageDescription>> describing = new ConcurrentHashMap<>();

    /** Alle Anhänge der Seite mit Art, Diagrammbezug und dem abgelegten Stand; lädt nichts herunter. */
    public PageAssets overview(PageAttachmentList list) {
        Map<String, String> previews = previews(list.attachments().attachments());
        List<PageAssets.Entry> entries = list.attachments().attachments().stream()
                .map(a -> new PageAssets.Entry(a, AssetKind.of(a.mediaType(), a.title()),
                        previews.get(a.attachmentId()), stored(list.sourceId(), a)
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
        return harvest(list, entry -> { });
    }

    /**
     * Wie {@link #harvest(PageAttachmentList)}; {@code each} erfährt jeden Anhang, sobald er erledigt ist, etwa für den
     * Fortschritt eines Auftrags (Fundstelle 24). Wirft {@code each} (Abbrechen), endet der Lauf nach diesem Anhang;
     * der Index bleibt für das bisher Abgelegte stimmig.
     */
    public AssetHarvest harvest(PageAttachmentList list, Consumer<AssetHarvest.Entry> each) {
        HarvestSource source = source(list.sourceId());
        String pageId = list.attachments().pageId();
        List<AssetHarvest.Entry> entries = new ArrayList<>();
        String stop = null;
        boolean indexed = false;
        try {
            for (PageAttachment attachment : list.attachments().attachments()) {
                if (stop != null) {
                    // Erst den veralteten Text austragen: Ein Abbrechen im Rückruf ließe ihn sonst durchsuchbar.
                    indexed |= forgetStale(list, attachment);
                    report(entries, each, failed(list, attachment, stop));
                    continue;
                }
                Optional<Asset> stored = stored(list.sourceId(), attachment);
                if (stored.isPresent()) {
                    backfillText(stored.get());
                    indexed |= indexStored(list, stored.get());
                    report(entries, each, new AssetHarvest.Entry(attachment.attachmentId(), AssetHarvest.Status.REUSED,
                            stored.get(), null));
                    continue;
                }
                // Download und Textextraktion derselben Datei belegen zusammen einen Platz der Schranke.
                try (LargeFileGate.Permit permit = gate.enter(source.id(), attachment.fileSize())) {
                    Downloaded downloaded = download(source, pageId, attachment, true, false);
                    harvestText(downloaded);
                    indexed |= indexStored(list, downloaded.asset());
                    report(entries, each, new AssetHarvest.Entry(attachment.attachmentId(), AssetHarvest.Status.STORED,
                            downloaded.asset(), null));
                } catch (AssetTooLargeException e) {
                    indexed |= forgetStale(list, attachment);
                    report(entries, each, failed(list, attachment, "asset-too-large"));
                } catch (AssetStoreFullException e) {
                    stop = "asset-store-full";
                    indexed |= forgetStale(list, attachment);
                    report(entries, each, failed(list, attachment, stop));
                } catch (SourceAccessException e) {
                    if (e.reason() == SourceAccessException.Reason.RATE_LIMITED) {
                        stop = code(e.reason());
                    }
                    indexed |= forgetStale(list, attachment);
                    report(entries, each, failed(list, attachment, code(e.reason())));
                }
            }
        } finally {
            finishIndexing(list, indexed);
        }
        return new AssetHarvest(list.sourceId(), pageId, list.spaceKey(), list.attachments().complete(), entries,
                clock.instant());
    }

    /**
     * Ein Anhang der Seite in seiner aktuellen Version: aus der Ablage oder, falls dort nicht vorhanden, frisch
     * heruntergeladen und abgelegt.
     *
     * @throws SourceAccessException  {@code NOT_FOUND}, wenn der Anhang nicht zur Seite gehört, sonst wie die Quelle
     * @throws AssetTooLargeException wenn die Datei die Grenze überschreitet oder größer ist als {@link
     *                                #MAX_IN_MEMORY_BYTES}; dann gibt es sie nur als Strom ({@link #stream})
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
        return content(list, attachmentId, mayStore, false);
    }

    /**
     * Wie {@link #content(PageAttachmentList, String, boolean)}; mit {@code fresh} am Speicher vorbei von der Quelle
     * („Neu erzwingen“, ADR 0025). Der abgelegte Stand gilt weiter, bis der neue Download ihn ersetzt; scheitert er,
     * bleibt der alte.
     */
    public AssetContent content(PageAttachmentList list, String attachmentId, boolean mayStore, boolean fresh) {
        PageAttachment attachment = attachment(list, attachmentId);
        if (!fresh) {
            Optional<Asset> stored = stored(list.sourceId(), attachment);
            if (stored.isPresent()) {
                return read(stored.get());
            }
        }
        return download(source(list.sourceId()), list.attachments().pageId(), attachment, mayStore);
    }

    /**
     * Legt einen Anhang der Seite in seiner aktuellen Version ab, ohne seine Bytes in den Speicher zu lesen: für Aufträge
     * wie den Export, die nur den abgelegten Stand brauchen (Dateien beliebiger Größe, Angelo 11.11.2026). Mit {@code
     * fresh} am Speicher vorbei von der Quelle („Neu erzwingen“, ADR 0025); der alte Stand gilt, bis der neue da ist.
     *
     * @throws SourceAccessException  {@code NOT_FOUND}, wenn der Anhang nicht zur Seite gehört, sonst wie die Quelle
     * @throws AssetTooLargeException wenn die Datei die Grenze überschreitet
     */
    public Asset keep(PageAttachmentList list, String attachmentId, boolean fresh) {
        PageAttachment attachment = attachment(list, attachmentId);
        if (!fresh) {
            Optional<Asset> stored = stored(list.sourceId(), attachment);
            if (stored.isPresent()) {
                verify(stored.get());
                return stored.get();
            }
        }
        return download(source(list.sourceId()), list.attachments().pageId(), attachment, true, false).asset();
    }

    /**
     * Prüft einen abgelegten Inhalt gegen seinen Hash wie {@link AssetStore#read}, aber als Strom: Ein beschädigter
     * Inhalt gilt nicht als abgelegt, auch wenn die Datei noch da ist.
     *
     * @throws AssetStoreException wenn der Inhalt nicht lesbar ist oder nicht zum Hash passt
     */
    private void verify(Asset asset) {
        try (InputStream in = store.open(asset)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new AssetStoreException("Asset-Inhalt nicht lesbar oder verändert", e);
        }
    }

    /**
     * Die Grenze für Bilder der Lese-Ansicht und Vorschauen: sie kommen nur als Bytes, also höchstens so groß wie
     * Dateien im Speicher, auch ohne Grenze für Downloads.
     */
    private long inMemoryLimit() {
        return Math.min(maxBytes, maxInMemoryBytes);
    }

    /** Nur für Tests: eine kleinere Grenze für Dateien im Speicher als {@link #MAX_IN_MEMORY_BYTES}. */
    void maxInMemoryBytes(long maxInMemoryBytes) {
        this.maxInMemoryBytes = maxInMemoryBytes;
    }

    /**
     * Liest einen abgelegten Inhalt, für große Dateien unter der Schranke ({@link LargeFileGate}).
     *
     * @throws AssetTooLargeException über {@link #MAX_IN_MEMORY_BYTES}: dann nur als Strom
     */
    private AssetContent read(Asset asset) {
        if (asset.size() > maxInMemoryBytes) {
            throw new AssetTooLargeException(asset.sourceId(), maxInMemoryBytes);
        }
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
     * Wie {@link #content(PageAttachmentList, String, boolean, String)}, aber ein abgelegter Anhang kommt als Strom
     * aus der Ablage, ohne ganz im Speicher zu liegen. Eine große Datei hält ihren Platz an der Schranke
     * ({@link LargeFileGate}), bis der Aufrufer den Strom schließt, auch aus einem anderen Thread. Ein nicht
     * abgelegter Anhang wird geladen, ohne ganz im Speicher zu liegen (Dateien beliebiger Größe, Angelo 11.11.2026):
     * mit {@code mayStore} in die Ablage und von dort geliefert, sonst aus seiner Zwischendatei, die mit dem Strom
     * verschwindet; bis {@link #IN_MEMORY_BYTES} aus dem Speicher wie bisher.
     *
     * @throws SignInPendingException wenn die Quelle einen Einmalcode verlangt und die Anforderung offen ist
     */
    public AssetStream stream(PageAttachmentList list, String attachmentId, boolean mayStore, String owner) {
        return unlocking(list.sourceId(), owner, () -> stream(list, attachmentId, mayStore));
    }

    private AssetStream stream(PageAttachmentList list, String attachmentId, boolean mayStore) {
        PageAttachment attachment = attachment(list, attachmentId);
        Optional<Asset> stored = stored(list.sourceId(), attachment);
        if (stored.isPresent()) {
            return open(stored.get());
        }
        HarvestSource source = source(list.sourceId());
        String pageId = list.attachments().pageId();
        if (mayStore) {
            Downloaded downloaded = download(source, pageId, attachment, true, false);
            return downloaded.content() != null ? downloaded.stream() : open(downloaded.asset());
        }
        Path staging = staging(source.id(), attachment);
        boolean handedOver = false;
        try {
            Downloaded downloaded = download(source, pageId, attachment, false, false, staging);
            if (downloaded.content() != null) {
                return downloaded.stream();
            }
            AssetStream delivered = handOver(downloaded.asset(), staging);
            handedOver = true;
            return delivered;
        } finally {
            if (!handedOver) {
                delete(source.id(), staging);
            }
        }
    }

    /** Ein abgelegter Inhalt als Strom; eine große Datei hält ihren Platz an der Schranke bis zum Schließen. */
    private AssetStream open(Asset asset) {
        LargeFileGate.Permit permit = gate.detached(asset.sourceId(), asset.size());
        try {
            return new AssetStream(asset, store.open(asset), permit);
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    /**
     * Die Zwischendatei eines nicht abgelegten Anhangs als Strom, wie {@link #open(Asset)} mit Platz an der Schranke;
     * die Datei verschwindet beim Schließen. Ihr Hash entstand beim Schreiben, sie ist nur diesem Strom bekannt.
     */
    private AssetStream handOver(Asset asset, Path staging) {
        LargeFileGate.Permit permit = gate.detached(asset.sourceId(), asset.size());
        try {
            return new AssetStream(asset, Files.newInputStream(staging, StandardOpenOption.DELETE_ON_CLOSE), permit);
        } catch (IOException e) {
            permit.close();
            throw new AssetStoreException("Asset nicht lesbar", e);
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
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
     * ist, sonst die Anforderung angelegt. Ist sie schon offen, fragt der Lesezugriff die Quelle nicht noch einmal
     * ({@link #fromSource}), sondern schließt sich ihr an. Code und Token sieht diese Stelle nie.
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
        return viewImage(sourceId, pageId, pageVersion, image, false);
    }

    /**
     * Wie {@link #viewImage(String, String, String, HarvestSource.ViewImage)}; mit {@code fresh} am Speicher vorbei
     * von der Quelle („Neu erzwingen“, ADR 0025); der abgelegte Stand gilt, bis der neue ihn ersetzt.
     */
    public AssetContent viewImage(String sourceId, String pageId, String pageVersion, HarvestSource.ViewImage image,
                                  boolean fresh) {
        HarvestSource source = source(sourceId);
        String attachmentId = image.attachmentId() != null ? image.attachmentId()
                : "view-" + sha256(image.src().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .substring("sha256:".length(), "sha256:".length() + 32);
        String version = image.attachmentId() != null ? image.version() : pageVersion;
        Optional<Asset> stored = fresh ? Optional.empty() : store.find(sourceId, attachmentId, version);
        if (stored.isPresent()) {
            return read(stored.get());
        }
        byte[] content = fromSource(sourceId, () -> {
            try {
                if (!downloads.tryAcquire(DOWNLOAD_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new SourceAccessException(sourceId, SourceAccessException.Reason.RATE_LIMITED,
                            "Zu viele gleichzeitige Downloads von Bildern", null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE,
                        "Download abgebrochen", e);
            }
            try {
                long started = System.nanoTime();
                byte[] loaded = source.viewImage(image, inMemoryLimit());
                durations.record(DownloadDurations.Kind.IMAGE, Duration.ofNanos(System.nanoTime() - started));
                return loaded;
            } catch (AttachmentTooLargeException e) {
                throw new AssetTooLargeException(sourceId, inMemoryLimit());
            } finally {
                downloads.release();
            }
        });
        if (content.length > inMemoryLimit()) {
            throw new AssetTooLargeException(sourceId, inMemoryLimit());
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

    /** So lange gilt ein gescheitertes Lesen der Anhangsliste, bevor die Lese-Ansicht es erneut versucht. */
    static final Duration LISTING_RETRY = Duration.ofMinutes(2);

    private record ListingKey(String sourceId, String pageId, String pageVersion) {
    }

    /** Die Anhangsliste einer Seitenversion, oder wann ihr Lesen scheiterte. */
    private record Listing(List<PageAttachment> attachments, Instant failedAt) {
    }

    /** Je Seitenversion die Anhangsliste für die Diagramme der Lese-Ansicht; der Garbage Collector darf sie einsammeln. */
    private final Map<ListingKey, java.lang.ref.SoftReference<Listing>> listings = new ConcurrentHashMap<>();

    /**
     * Die Anhänge einer Seite für die Diagramme der Lese-Ansicht: je Seitenversion einmal von der Quelle, danach aus dem
     * Speicher, statt für jedes Bild der Seite erneut (Angelo 17.11.2026: die Seite lud Bild für Bild live). Antwortet die
     * Quelle nicht, gilt die Liste für {@link #LISTING_RETRY} als leer: die Bilder kommen dann aus der Ablage, ohne
     * je Bild auf die Quelle zu warten. Eine Einmalcode-Abfrage geht weiter an den Aufrufer.
     */
    private List<PageAttachment> viewAttachments(HarvestSource source, String pageId, String pageVersion) {
        if (pageVersion == null) {
            return source.attachments(pageId).attachments();
        }
        ListingKey key = new ListingKey(source.id(), pageId, pageVersion);
        java.lang.ref.SoftReference<Listing> reference = listings.get(key);
        Listing known = reference == null ? null : reference.get();
        if (known != null && (known.failedAt() == null
                || clock.instant().isBefore(known.failedAt().plus(LISTING_RETRY)))) {
            return known.attachments();
        }
        Listing listing;
        try {
            listing = new Listing(List.copyOf(source.attachments(pageId).attachments()), null);
        } catch (SignInRequiredException e) {
            throw e;
        } catch (SourceAccessException e) {
            LOG.log(System.Logger.Level.DEBUG, "Anhangsliste in {0} für die Lese-Ansicht nicht lesbar ({1})",
                    source.id(), e.getClass().getName());
            listing = new Listing(List.of(), clock.instant());
        }
        if (listings.size() >= 4096) {
            listings.values().removeIf(value -> value.get() == null);
        }
        listings.put(key, new java.lang.ref.SoftReference<>(listing));
        return listing.attachments();
    }

    /**
     * Das draw.io-Diagramm hinter einem Bild der Lese-Ansicht als PNG (#190): Ist {@code image} die Vorschau, die das
     * Plugin neben einem Diagramm-Anhang der Seite ablegt ({@link Diagrams#isDrawioPreview}), wird das Diagramm aus
     * seinen Quelldaten gezeichnet ({@link #DRAWIO_PNG_RENDITION}, einmal je Quelldaten abgelegt). Die Quelldaten
     * kommen aus der Ablage oder wie bei {@link #viewImage} über die Quelle.
     *
     * @param pageVersion wie bei {@link #viewImage}; die Grafik hängt nur an den Quelldaten
     * <p>Die Anhangsliste liest die Quelle je Seitenversion nur einmal ({@link #viewAttachments}).
     *
     * @return leer, wenn das Bild keine solche Vorschau ist oder das Diagramm nicht darstellbar; dann gilt das Bild selbst
     * @throws SourceAccessException  beim Laden der Quelldaten, wie {@link #viewImage}
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
        for (PageAttachment attachment : viewAttachments(source, pageId, pageVersion)) {
            if (Diagrams.isDrawio(attachment.mediaType(), attachment.title())
                    && Diagrams.isDrawioPreview(filename, Diagrams.drawioName(attachment.title()))) {
                diagram = attachment;
                break;
            }
        }
        if (diagram == null) {
            return foreignDrawio(sourceId, pageId, pageVersion, image);
        }
        PageAttachment found = diagram;
        Optional<Asset> storedDiagram = store.find(sourceId, found.attachmentId(), found.version());
        if (storedDiagram.isEmpty() && !deliveredSinceTwoFactorPage(sourceId)) {
            // Wie der Enterprise AI Client (#571): Der erste geschützte Abruf einer Quelle ist das Bild der Lese-Ansicht,
            // nicht der rohe Diagramm-Anhang. Verlangt die Quelle einen Einmalcode, merkt sich das Zwei-Faktor-Plugin
            // diese Adresse und leitet nach dem Code dorthin weiter; im EAC ist das immer ein Bild. Das Bild liegt
            // danach in der Ablage, die Lese-Ansicht lädt es nicht noch einmal.
            try {
                viewImage(sourceId, pageId, pageVersion, image);
            } catch (SignInRequiredException e) {
                throw e;
            } catch (RuntimeException e) {
                // Das Bild selbst lädt nicht (zu groß, nicht unterstützt …): weiter mit den Quelldaten.
            }
        }
        Rendered rendered;
        try (LargeFileGate.Permit permit = gate.enter(sourceId, found.fileSize())) {
            byte[] mxfile = storedDiagram.map(this::storedSource)
                    .orElseGet(() -> download(source, pageId, found).content());
            rendered = rendition(sourceId, pageId, DRAWIO_PNG_RENDITION, mxfile);
        }
        if (rendered.asset() == null) {
            return Optional.empty();
        }
        return Optional.of(new AssetContent(rendered.asset(), store.read(rendered.asset())));
    }

    /**
     * Das draw.io-Diagramm hinter einem Bild der Lese-Ansicht als SVG, damit die Oberfläche es als Vektorzeichnung
     * zeigen und bei jeder Vergrößerung scharf zeichnen kann ({@link com.aresstack.spaceharvester.application.diagrams.VectorDiagram}),
     * statt ein in fester Breite gerastertes PNG zu vergrößern. Hängt das Diagramm an dieser Seite, wird es aus seinen
     * Quelldaten gezeichnet ({@link #DRAWIO_RENDITION}, einmal je Quelldaten abgelegt, wie {@link #viewDiagram}); ist es
     * eingebunden ({@code #drawio=…&page=<andere Seite>}), gilt das SVG, das die Quelle über das Plugin zeichnet.
     *
     * @return leer, wenn das Bild kein draw.io-Diagramm ist oder sich nicht zeichnen lässt; dann gilt das Bild selbst
     * @throws SourceAccessException  wie {@link #viewDiagram}
     * @throws AssetTooLargeException wie {@link #viewDiagram}
     */
    public Optional<AssetContent> viewDiagramSvg(String sourceId, String pageId, String pageVersion,
                                                 HarvestSource.ViewImage image) {
        String filename = viewFileName(image);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return Optional.empty();
        }
        HarvestSource source = source(sourceId);
        PageAttachment diagram = null;
        for (PageAttachment attachment : viewAttachments(source, pageId, pageVersion)) {
            if (Diagrams.isDrawio(attachment.mediaType(), attachment.title())
                    && Diagrams.isDrawioPreview(filename, Diagrams.drawioName(attachment.title()))) {
                diagram = attachment;
                break;
            }
        }
        if (diagram == null) {
            String src = image.src() == null ? "" : image.src();
            if (!src.contains("#drawio=")) {
                return Optional.empty();
            }
            AssetContent loaded = viewImage(sourceId, pageId, pageVersion, image);
            return loaded.asset().kind() == AssetKind.VECTOR || isSvg(loaded.content())
                    ? Optional.of(loaded) : Optional.empty();
        }
        PageAttachment found = diagram;
        Optional<Asset> storedDiagram = store.find(sourceId, found.attachmentId(), found.version());
        if (storedDiagram.isEmpty() && !deliveredSinceTwoFactorPage(sourceId)) {
            // Wie viewDiagram (#571): der erste geschützte Abruf ist das Bild der Lese-Ansicht.
            try {
                viewImage(sourceId, pageId, pageVersion, image);
            } catch (SignInRequiredException e) {
                throw e;
            } catch (RuntimeException e) {
                // Das Bild selbst lädt nicht: weiter mit den Quelldaten.
            }
        }
        Rendered rendered;
        try (LargeFileGate.Permit permit = gate.enter(sourceId, found.fileSize())) {
            byte[] mxfile = storedDiagram.map(this::storedSource)
                    .orElseGet(() -> download(source, pageId, found).content());
            rendered = rendition(sourceId, pageId, DRAWIO_RENDITION, mxfile);
        }
        if (rendered.asset() == null) {
            return Optional.empty();
        }
        return Optional.of(new AssetContent(rendered.asset(), store.read(rendered.asset())));
    }

    /**
     * Die abgelegten Quelldaten eines Diagramms als Bytes, höchstens {@link #MAX_IN_MEMORY_BYTES} wie {@link #read}:
     * Ohne Grenze für Downloads kann ein abgelegtes Diagramm beliebig groß sein. Die Schranke hält schon der Aufrufer.
     *
     * @throws AssetTooLargeException darüber; dann gilt das Bild selbst
     */
    private byte[] storedSource(Asset asset) {
        if (asset.size() > maxInMemoryBytes) {
            throw new AssetTooLargeException(asset.sourceId(), maxInMemoryBytes);
        }
        return store.read(asset);
    }

    /**
     * Ein draw.io-Diagramm, das nicht an dieser Seite hängt (eingebunden über {@code multiexcerpt-include},
     * {@code include} oder {@code inc-drawio}; die Adresse trägt {@code #drawio=…&page=<Eigentümerseite>}): Die Quelle
     * zeichnet es selbst über die REST-Schnittstelle des Plugins und liefert SVG wie im Enterprise AI Client. Swing
     * zeigt kein SVG, also wird es hier gerastert ({@link #SVG_PNG_RENDITION}, einmal je SVG abgelegt), wie der
     * EAC-Leser es mit Batik tut. Liefert die Quelle ein Rasterbild (Vorschau als Rückfall), bleibt es beim Bild selbst.
     */
    private Optional<AssetContent> foreignDrawio(String sourceId, String pageId, String pageVersion,
                                                 HarvestSource.ViewImage image) {
        String src = image.src() == null ? "" : image.src();
        if (!src.contains("#drawio=")) {
            return Optional.empty();
        }
        AssetContent loaded = viewImage(sourceId, pageId, pageVersion, image);
        // Am Inhalt erkannt, nicht am Typ: Confluence nennt am Vorschaubild image/png, die Quelle liefert aber die
        // gezeichnete SVG-Fassung (auch aus der Ablage).
        if (loaded.asset().kind() != AssetKind.VECTOR && !isSvg(loaded.content())) {
            return Optional.empty();
        }
        Rendered rendered = rendition(sourceId, pageId, SVG_PNG_RENDITION, loaded.content());
        if (rendered.asset() == null) {
            return Optional.empty();
        }
        return Optional.of(new AssetContent(rendered.asset(), store.read(rendered.asset())));
    }

    /** SVG-Text am Anfang der Bytes (nach einer XML-Deklaration oder einem Kommentar), gleich welcher Typ genannt ist. */
    static boolean isSvg(byte[] content) {
        if ("image/svg+xml".equals(sniff(content))) {
            return true;
        }
        String head = new String(content, 0, Math.min(content.length, 1024), java.nio.charset.StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(Locale.ROOT);
        return head.startsWith("<") && head.contains("<svg");
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

    /** Präfix der virtuellen Anhänge, die die Marken eines Videos zeigen, und ihrer Standbilder in der Ablage. */
    public static final String VIDEO_MARK = "video-mark-";
    /** Medientyp der virtuellen Anhänge einer Marke ({@link AssetKind#MARK}). */
    public static final String VIDEO_MARK_MEDIA_TYPE = AssetKind.MARK_MEDIA_TYPE;

    /** Das Standbild der Marke an {@code millis} als PNG. */
    public record VideoFrame(long millis, byte[] png) {

        public VideoFrame {
            if (millis < 0 || png == null || png.length == 0) {
                throw new IllegalArgumentException("Standbild ohne Zeit oder Bild");
            }
        }
    }

    /**
     * Legt das Standbild einer Marke ab; es füllt ihren virtuellen Anhang ({@link #withVideoMarks}) und macht sie für
     * die Bildanalyse beschreibbar ({@link #videoFrame}). Das Recht auf die Seite prüft der Aufrufer.
     */
    public void putVideoFrame(String sourceId, String pageId, String version, VideoFrame frame) {
        store.put(new Asset(sourceId, pageId, markId(pageId, frame.millis()), frameVersion(version),
                frameName(frame.millis()), "image/png", AssetKind.IMAGE, frame.png().length, sha256(frame.png()),
                clock.instant()), frame.png());
    }

    /** Das abgelegte Standbild einer Marke dieser Fassung; leer, solange es nicht gezogen ist. */
    public Optional<AssetContent> videoFrame(String sourceId, String pageId, String version, long millis) {
        return store.find(sourceId, markId(pageId, millis), frameVersion(version)).map(this::read);
    }

    /**
     * Die Anhangsliste einer Seite samt einem virtuellen Anhang je Marke ihres Videos ({@link AssetKind#MARK}),
     * benannt nach ihrer Position ({@link #markTitle}); mit gezogenem Standbild samt dessen Größe, sonst ohne.
     *
     * @param marks die Marken des Videos in Millisekunden; leer: keine virtuellen Anhänge
     */
    public PageAttachmentList withVideoMarks(PageAttachmentList list, String version, List<Long> marks) {
        if (marks.isEmpty()) {
            return list;
        }
        String pageId = list.attachments().pageId();
        List<PageAttachment> attachments = new java.util.ArrayList<>(list.attachments().attachments());
        for (long millis : marks) {
            String id = markId(pageId, millis);
            Optional<Asset> frame = store.find(list.sourceId(), id, frameVersion(version));
            attachments.add(new PageAttachment(id, id, markTitle(millis), VIDEO_MARK_MEDIA_TYPE,
                    frame.map(Asset::size).orElse(null), frameVersion(version),
                    frame.map(Asset::retrievedAt).orElse(null), null));
        }
        PageAttachments source = list.attachments();
        return new PageAttachmentList(list.sourceId(), list.spaceKey(), new PageAttachments(source.pageId(),
                attachments, source.responses(), source.mediaType(), source.nativeFormat(), source.warnings(),
                source.retrievedAt()), list.retrievedAt());
    }

    /** Präfix der Folienkommentare an den Marken eines Videos und ihres Verzeichnisses in der Ablage. */
    public static final String VIDEO_NOTE = "video-note-";

    /**
     * Ein Folienkommentar (Vortragstext) einer Marke (Angelo, 20.11.2026): an der virtuellen Marke abgelegt, je Modell
     * einer, wie die Korrekturen am Transkript; die Marke und ihr Standbild bleiben unverändert.
     *
     * @param millis       Position der Marke im Video
     * @param attachmentId Kennung in der Ablage
     * @param label        Anzeigename, etwa {@code Kommentar gemma4:12b}
     * @param model        Verfahren und Modell; je Modell und Marke ersetzt ein neuer den alten
     * @param input        Fingerabdruck der Eingabe (Standbild, Transkriptauszug, Modell)
     */
    public record SlideNote(long millis, String attachmentId, String label, String model, String input) {
    }

    private final Object slideNotes = new Object();

    /** Legt den Folienkommentar einer Marke ab und ersetzt den früheren desselben Modells. */
    public void putVideoSlideNote(String sourceId, String pageId, String version, SlideNote note, String text) {
        String id = noteId(pageId, note.millis(), note.model());
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        synchronized (slideNotes) {
            store.put(new Asset(sourceId, pageId, id, frameVersion(version),
                    frameName(note.millis()).replace(".png", "") + " – " + note.label() + ".txt", "text/plain",
                    AssetKind.TRANSCRIPT, bytes.length, sha256(bytes), clock.instant()), bytes);
            java.util.Properties index = noteIndex(sourceId, pageId, version);
            index.setProperty(id + ".millis", String.valueOf(note.millis()));
            index.setProperty(id + ".label", note.label());
            index.setProperty(id + ".model", note.model());
            index.setProperty(id + ".input", note.input());
            writeNoteIndex(sourceId, pageId, version, index);
        }
    }

    /** Nimmt den Folienkommentar einer Marke dieses Modells aus dem Verzeichnis, etwa wenn er veraltet ist. */
    public void removeVideoSlideNote(String sourceId, String pageId, String version, long millis, String model) {
        String id = noteId(pageId, millis, model);
        synchronized (slideNotes) {
            java.util.Properties index = noteIndex(sourceId, pageId, version);
            if (index.remove(id + ".model") != null) {
                index.remove(id + ".millis");
                index.remove(id + ".label");
                index.remove(id + ".input");
                writeNoteIndex(sourceId, pageId, version, index);
            }
        }
    }

    /** Alle Folienkommentare des Videos dieser Fassung, nach Position und Anzeigename. */
    public List<SlideNote> videoSlideNotes(String sourceId, String pageId, String version) {
        java.util.Properties index = noteIndex(sourceId, pageId, version);
        List<SlideNote> found = new java.util.ArrayList<>();
        for (String key : index.stringPropertyNames()) {
            if (!key.endsWith(".model")) {
                continue;
            }
            String id = key.substring(0, key.length() - ".model".length());
            try {
                found.add(new SlideNote(Long.parseLong(index.getProperty(id + ".millis")), id,
                        index.getProperty(id + ".label", id), index.getProperty(key),
                        index.getProperty(id + ".input", "")));
            } catch (NumberFormatException damaged) {
                // Ein beschädigter Eintrag fehlt; der nächste Lauf schreibt ihn neu.
            }
        }
        found.sort(java.util.Comparator.comparingLong(SlideNote::millis).thenComparing(SlideNote::label));
        return List.copyOf(found);
    }

    /** Der Folienkommentar dieser Marke und dieses Modells; leer ohne. */
    public Optional<SlideNote> videoSlideNote(String sourceId, String pageId, String version, long millis,
                                              String model) {
        String id = noteId(pageId, millis, model);
        return videoSlideNotes(sourceId, pageId, version).stream().filter(note -> note.attachmentId().equals(id))
                .findFirst();
    }

    /** Der Text eines Folienkommentars dieser Fassung. */
    public Optional<String> videoSlideNoteText(String sourceId, String pageId, String version, String attachmentId) {
        if (attachmentId == null || !attachmentId.startsWith(VIDEO_NOTE)
                || attachmentId.equals(noteIndexId(pageId))) {
            return Optional.empty();
        }
        return store.find(sourceId, attachmentId, frameVersion(version)).map(this::read)
                .map(content -> new String(content.content(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private java.util.Properties noteIndex(String sourceId, String pageId, String version) {
        java.util.Properties index = new java.util.Properties();
        store.find(sourceId, noteIndexId(pageId), frameVersion(version)).map(this::read).ifPresent(content -> {
            try {
                index.load(new java.io.StringReader(new String(content.content(),
                        java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return index;
    }

    private void writeNoteIndex(String sourceId, String pageId, String version, java.util.Properties index) {
        java.io.StringWriter written = new java.io.StringWriter();
        try {
            index.store(written, null);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        byte[] bytes = written.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.put(new Asset(sourceId, pageId, noteIndexId(pageId), frameVersion(version), "Folienkommentare.properties",
                "text/plain", AssetKind.TRANSCRIPT, bytes.length, sha256(bytes), clock.instant()), bytes);
    }

    private static String noteIndexId(String pageId) {
        return VIDEO_NOTE + "index-" + framePage(pageId);
    }

    private static String noteId(String pageId, long millis, String model) {
        return VIDEO_NOTE + framePage(pageId) + "-" + millis + "-" + sha256(model.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)).substring("sha256:".length(), "sha256:".length() + 12);
    }

    /**
     * Die Marken ohne Wiederholungen derselben Folie: Zeigt das Standbild einer Marke dieselbe Folie wie das der
     * vorigen ({@link SlideFrames#same}), etwa weil nur der Mauszeiger wanderte, fällt sie weg. Marken ohne gezogenes
     * Standbild bleiben, bis es da ist.
     */
    public List<Long> distinctSlides(String sourceId, String pageId, String version, List<Long> marks) {
        SlideFrames.Thresholds thresholds = slideThresholds.get();
        List<Long> slides = new java.util.ArrayList<>(marks.size());
        SlideFrames.Grid previous = null;
        for (long millis : marks) {
            SlideFrames.Grid grid = store.find(sourceId, markId(pageId, millis), frameVersion(version))
                    .map(frame -> slideGrid(frame, thresholds)).orElse(null);
            if (grid != null && previous != null && SlideFrames.same(previous, grid, thresholds)) {
                continue;
            }
            slides.add(millis);
            previous = grid;
        }
        return List.copyOf(slides);
    }

    /** Das Raster eines Standbilds, je Bild und Blockgröße einmal berechnet; unlesbar {@code null}. */
    private SlideFrames.Grid slideGrid(Asset frame, SlideFrames.Thresholds thresholds) {
        String key = frame.sha256() + "@" + thresholds.blockPixels();
        synchronized (slideGrids) {
            SlideFrames.Grid known = slideGrids.get(key);
            if (known != null) {
                return known;
            }
        }
        SlideFrames.Grid grid;
        try {
            grid = SlideFrames.grid(read(frame).content(), thresholds.blockPixels());
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
        synchronized (slideGrids) {
            slideGrids.put(key, grid);
            // So viele, wie ein Video Marken haben kann.
            while (slideGrids.size() > thresholds.maxFrames()) {
                slideGrids.remove(slideGrids.keySet().iterator().next());
            }
        }
        return grid;
    }

    /** Die Raster der zuletzt verglichenen Standbilder, die ältesten zuerst. */
    private final Map<String, SlideFrames.Grid> slideGrids = new java.util.LinkedHashMap<>(16, 0.75f, true);

    /** Die Werte, mit denen zwei Standbilder verglichen werden; die App stellt sie ein. */
    private volatile java.util.function.Supplier<SlideFrames.Thresholds> slideThresholds =
            () -> SlideFrames.Thresholds.DEFAULT;

    /** Die eingestellten Werte des Vergleichs zweier Standbilder, auch wie viele Marken ein Video haben darf. */
    public SlideFrames.Thresholds slideThresholds() {
        return slideThresholds.get();
    }

    /** Setzt, woher die Werte des Vergleichs zweier Standbilder kommen ({@link #distinctSlides}). */
    public void slideThresholds(java.util.function.Supplier<SlideFrames.Thresholds> thresholds) {
        slideThresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /** Die Marken, deren Standbild in dieser Fassung noch nicht gezogen ist. */
    public List<Long> missingVideoFrames(String sourceId, String pageId, String version, List<Long> marks) {
        return marks.stream().filter(millis -> store.find(sourceId, markId(pageId, millis), frameVersion(version))
                .isEmpty()).toList();
    }

    /**
     * Der Bezug der Bildanalyse für ein Video ohne Text: Fassung, Marken und ihre gezogenen Standbilder. Jede neue
     * Marke und jedes neue Standbild machen das Ergebnis veraltet; leer ohne Standbilder.
     */
    public Optional<String> videoMarksInput(String sourceId, String pageId, String version, List<Long> marks) {
        StringBuilder input = new StringBuilder(frameVersion(version));
        boolean any = false;
        for (long millis : marks) {
            Optional<Asset> frame = store.find(sourceId, markId(pageId, millis), frameVersion(version));
            input.append('\n').append(millis).append(' ').append(frame.map(Asset::sha256).orElse("-"));
            any |= frame.isPresent();
        }
        return any ? Optional.of(sha256(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                : Optional.empty();
    }

    /** Präfix des virtuellen Anhangs, der das Transkript eines Videos zeigt, und seiner Ablage. */
    public static final String VIDEO_TRANSCRIPT = "video-transcript-";

    /**
     * Ein Original-Transkript eines Videos (Angelo 20.11.2026): je Whisper-Modell eines, ausgewählt in der
     * Transkriptansicht. Transkripte von vor dieser Unterscheidung haben kein Modell ({@code whisper} leer).
     *
     * @param whisper      Kennung des Whisper-Modells, etwa {@code tiny}; leer bei einem älteren Transkript
     * @param attachmentId Kennung des virtuellen Anhangs
     */
    public record TranscriptOriginal(String whisper, String attachmentId) {
    }

    /** Schlüssel des gewählten Originals im Verzeichnis der Transkripte. */
    private static final String CHOSEN = "original";
    /** Präfix der Originale je Whisper-Modell im Verzeichnis der Transkripte. */
    private static final String ORIGINAL = "original.";

    /**
     * Legt das Original-Transkript eines Whisper-Modells für das Video dieser Fassung ab (Text in UTF-8) und ersetzt
     * das frühere desselben Modells; die anderen bleiben. Ist es das erste, wird es das gewählte
     * ({@link #chooseVideoTranscript}). Es erscheint als virtueller Anhang ({@link #withVideoTranscript}). Das Recht
     * auf die Seite prüft der Aufrufer.
     *
     * @param whisper  Kennung des Whisper-Modells; leer nur für ein älteres Transkript ohne Modell
     * @param fileName Dateiname des Anhangs, etwa {@code Vortrag – Transkript.txt}; das Modell kommt hinzu
     */
    public void putVideoTranscript(String sourceId, String pageId, String version, String whisper, String fileName,
                                   String text) {
        String model = whisper == null ? "" : whisper.strip();
        String id = originalId(pageId, model);
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        synchronized (corrections) {
            boolean first = chosenVideoTranscript(sourceId, pageId, version).isEmpty();
            store.put(new Asset(sourceId, pageId, id, frameVersion(version), originalTitle(fileName, model),
                    "text/plain", AssetKind.TRANSCRIPT, bytes.length, sha256(bytes), clock.instant()), bytes);
            java.util.Properties index = transcriptIndex(sourceId, pageId, version);
            boolean changed = false;
            if (!model.isEmpty() && !id.equals(index.getProperty(ORIGINAL + model))) {
                index.setProperty(ORIGINAL + model, id);
                changed = true;
            }
            if (first && !model.equals(index.getProperty(CHOSEN))) {
                index.setProperty(CHOSEN, model);
                changed = true;
            }
            if (changed) {
                storeTranscriptIndex(sourceId, pageId, version, index);
            }
        }
        transcriptChanged(sourceId, pageId);
    }

    /** Der Anzeigename eines Originals: der Dateiname mit dem Modell, etwa {@code Vortrag – Transkript (Whisper tiny).txt}. */
    private static String originalTitle(String fileName, String whisper) {
        if (whisper.isEmpty()) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String suffix = " (Whisper " + whisper + ")";
        return dot > 0 ? fileName.substring(0, dot) + suffix + fileName.substring(dot) : fileName + suffix;
    }

    /**
     * Wählt das Original, auf das sich Ansicht, Korrektur und Analysen beziehen; die Analysen rechnen über
     * {@link #transcriptChanges} neu. Nichts, wenn es dieses Original nicht gibt oder es schon gewählt ist.
     *
     * @return ob sich die Wahl geändert hat
     */
    public boolean chooseVideoTranscript(String sourceId, String pageId, String version, String whisper) {
        String model = whisper == null ? "" : whisper.strip();
        synchronized (corrections) {
            Optional<TranscriptOriginal> chosen = chosenVideoTranscript(sourceId, pageId, version);
            if (chosen.map(original -> original.whisper().equals(model)).orElse(true)
                    || store.find(sourceId, originalId(pageId, model), frameVersion(version)).isEmpty()) {
                return false;
            }
            java.util.Properties index = transcriptIndex(sourceId, pageId, version);
            index.setProperty(CHOSEN, model);
            storeTranscriptIndex(sourceId, pageId, version, index);
        }
        transcriptChanged(sourceId, pageId);
        return true;
    }

    /** Die abgelegten Originale des Videos dieser Fassung: zuerst ein älteres ohne Modell, dann nach Modell. */
    public List<TranscriptOriginal> videoTranscriptOriginals(String sourceId, String pageId, String version) {
        List<TranscriptOriginal> found = new java.util.ArrayList<>();
        if (store.find(sourceId, transcriptId(pageId), frameVersion(version)).isPresent()) {
            found.add(new TranscriptOriginal("", transcriptId(pageId)));
        }
        java.util.Properties index = transcriptIndex(sourceId, pageId, version);
        new java.util.TreeSet<>(index.stringPropertyNames()).stream()
                .filter(key -> key.startsWith(ORIGINAL) && key.indexOf('.', ORIGINAL.length()) < 0)
                .map(key -> new TranscriptOriginal(key.substring(ORIGINAL.length()), index.getProperty(key)))
                .filter(original -> !original.whisper().isEmpty()
                        && original.attachmentId().equals(originalId(pageId, original.whisper()))
                        && store.find(sourceId, original.attachmentId(), frameVersion(version)).isPresent())
                .forEach(found::add);
        return List.copyOf(found);
    }

    /**
     * Das gewählte Original des Videos dieser Fassung; ohne ausdrückliche Wahl das ältere ohne Modell, sonst das erste
     * nach Modell. Leer ohne Transkript.
     */
    public Optional<TranscriptOriginal> chosenVideoTranscript(String sourceId, String pageId, String version) {
        List<TranscriptOriginal> originals = videoTranscriptOriginals(sourceId, pageId, version);
        String chosen = transcriptIndex(sourceId, pageId, version).getProperty(CHOSEN);
        return originals.stream().filter(original -> original.whisper().equals(chosen)).findFirst()
                .or(() -> originals.stream().findFirst());
    }

    /** Das gewählte Original-Transkript des Videos dieser Fassung; leer, solange keines erzeugt ist. */
    public Optional<String> videoTranscript(String sourceId, String pageId, String version) {
        return chosenVideoTranscript(sourceId, pageId, version)
                .flatMap(original -> videoTranscript(sourceId, pageId, version, original.whisper()));
    }

    /** Das Original-Transkript dieses Whisper-Modells ({@code ""}: das ältere ohne Modell); leer ohne. */
    public Optional<String> videoTranscript(String sourceId, String pageId, String version, String whisper) {
        return store.find(sourceId, originalId(pageId, whisper == null ? "" : whisper.strip()), frameVersion(version))
                .map(this::read)
                .map(content -> new String(content.content(), java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Die Anhangsliste einer Seite samt den Transkripten ihres Videos als virtuellen Anhängen
     * ({@link AssetKind#TRANSCRIPT}): je Original eines, jeweils mit seinen Korrekturen dahinter; ohne abgelegtes
     * Transkript unverändert.
     */
    public PageAttachmentList withVideoTranscript(PageAttachmentList list, String version) {
        String pageId = list.attachments().pageId();
        List<TranscriptOriginal> originals = videoTranscriptOriginals(list.sourceId(), pageId, version);
        if (originals.isEmpty()) {
            return list;
        }
        List<PageAttachment> attachments = new java.util.ArrayList<>(list.attachments().attachments());
        List<TranscriptCorrection> all = videoTranscriptCorrections(list.sourceId(), pageId, version);
        for (TranscriptOriginal original : originals) {
            List<String> ids = new java.util.ArrayList<>();
            ids.add(original.attachmentId());
            // Die Korrekturen stehen als Ergänzung gleich hinter ihrem Original.
            all.stream().filter(correction -> correction.whisper().equals(original.whisper()))
                    .forEach(correction -> ids.add(correction.attachmentId()));
            for (String id : ids) {
                store.find(list.sourceId(), id, frameVersion(version)).ifPresent(asset ->
                        attachments.add(new PageAttachment(asset.attachmentId(), asset.attachmentId(), asset.title(),
                                AssetKind.TRANSCRIPT_MEDIA_TYPE, asset.size(), frameVersion(version),
                                asset.retrievedAt(), null)));
            }
        }
        PageAttachments source = list.attachments();
        return new PageAttachmentList(list.sourceId(), list.spaceKey(), new PageAttachments(source.pageId(),
                attachments, source.responses(), source.mediaType(), source.nativeFormat(), source.warnings(),
                source.retrievedAt()), list.retrievedAt());
    }

    /**
     * Eine Korrektur eines Original-Transkripts eines Videos (Angelo, 20.11.2026): als Ergänzung neben dem Original
     * abgelegt, je Original und Modell eine, und wie das Original ein virtueller Anhang; das Original selbst bleibt
     * unverändert.
     *
     * @param attachmentId Kennung des Anhangs
     * @param label        Anzeigename, etwa {@code Korrektur gemma4:12b}
     * @param model        Verfahren und Modell, aus dem sie entstand; je Modell ersetzt eine neue die alte
     * @param input        Fingerabdruck der Eingabe (Transkript, Folienbeschreibungen, Modell)
     * @param complete     ob jedes Paket korrigiert ist; sonst stehen gescheiterte unkorrigiert darin
     * @param whisper      das Original, das sie korrigiert ({@link TranscriptOriginal#whisper()})
     */
    public record TranscriptCorrection(String attachmentId, String label, String model, String input,
                                       boolean complete, String whisper) {

        public TranscriptCorrection {
            whisper = whisper == null ? "" : whisper.strip();
        }

        /** Eine Korrektur des älteren Originals ohne Modell. */
        public TranscriptCorrection(String attachmentId, String label, String model, String input, boolean complete) {
            this(attachmentId, label, model, input, complete, "");
        }
    }

    private final Object corrections = new Object();

    /**
     * Legt eine Korrektur des Originals {@link TranscriptCorrection#whisper()} ab und ersetzt die frühere desselben
     * Modells zu diesem Original.
     */
    public void putVideoTranscriptCorrection(String sourceId, String pageId, String version, String fileName,
                                             TranscriptCorrection correction, String text) {
        String id = correctionId(pageId, correction.whisper(), correction.model());
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        synchronized (corrections) {
            store.put(new Asset(sourceId, pageId, id, frameVersion(version), fileName, "text/plain",
                    AssetKind.TRANSCRIPT, bytes.length, sha256(bytes), clock.instant()), bytes);
            java.util.Properties index = transcriptIndex(sourceId, pageId, version);
            index.setProperty(id + ".label", correction.label());
            index.setProperty(id + ".model", correction.model());
            index.setProperty(id + ".input", correction.input());
            index.setProperty(id + ".complete", String.valueOf(correction.complete()));
            index.setProperty(id + ".whisper", correction.whisper());
            storeTranscriptIndex(sourceId, pageId, version, index);
        }
        transcriptChanged(sourceId, pageId);
    }

    /**
     * Der Text, den die Analysen einer Videoseite lesen (Angelo 20.11.2026): die zuletzt abgelegte Korrektur des
     * gewählten Originals, ohne Korrektur das Original; leer ohne Transkript. Mit Zeitmarken wie abgelegt.
     */
    public Optional<String> latestVideoTranscriptText(String sourceId, String pageId, String version) {
        Optional<TranscriptOriginal> chosen = chosenVideoTranscript(sourceId, pageId, version);
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        Asset latest = null;
        for (TranscriptCorrection correction : videoTranscriptCorrections(sourceId, pageId, version,
                chosen.get().whisper())) {
            Optional<Asset> asset = store.find(sourceId, correction.attachmentId(), frameVersion(version));
            if (asset.isPresent() && (latest == null || asset.get().retrievedAt().isAfter(latest.retrievedAt()))) {
                latest = asset.get();
            }
        }
        if (latest == null) {
            return videoTranscript(sourceId, pageId, version, chosen.get().whisper());
        }
        return Optional.of(new String(read(latest).content(), java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Löscht das gewählte Original-Transkript des Videos dieser Fassung samt seinen Korrekturen.
     *
     * @return ob etwas gelöscht wurde
     * @see #deleteVideoTranscript(String, String, String, String)
     */
    public boolean deleteVideoTranscript(String sourceId, String pageId, String version) {
        Optional<TranscriptOriginal> chosen = chosenVideoTranscript(sourceId, pageId, version);
        return chosen.isPresent() && deleteVideoTranscript(sourceId, pageId, version, chosen.get().whisper());
    }

    /**
     * Löscht das Original-Transkript dieses Whisper-Modells samt allen seinen Korrekturen (Angelo 20.11.2026, Knopf in
     * der Transkriptansicht); die anderen Originale bleiben, und war es das gewählte, gilt danach ein verbliebenes. Die
     * Analysen darüber fallen über {@link #transcriptChanges} weg oder rechnen über das verbliebene neu. Das Recht
     * auf die Seite prüft der Aufrufer.
     *
     * @return ob etwas gelöscht wurde
     */
    public boolean deleteVideoTranscript(String sourceId, String pageId, String version, String whisper) {
        String model = whisper == null ? "" : whisper.strip();
        String stored = frameVersion(version);
        boolean deleted = false;
        synchronized (corrections) {
            java.util.Properties index = transcriptIndex(sourceId, pageId, version);
            for (TranscriptCorrection correction : videoTranscriptCorrections(sourceId, pageId, version, model)) {
                deleted |= store.remove(sourceId, correction.attachmentId(), stored);
                for (String field : List.of(".label", ".model", ".input", ".complete", ".whisper")) {
                    index.remove(correction.attachmentId() + field);
                }
            }
            deleted |= store.remove(sourceId, originalId(pageId, model), stored);
            index.remove(ORIGINAL + model);
            if (model.equals(index.getProperty(CHOSEN))) {
                index.remove(CHOSEN);
            }
            if (videoTranscriptOriginals(sourceId, pageId, version).isEmpty()) {
                // Ohne Original bleibt auch kein Verzeichnis, auch keine verwaisten Korrekturen.
                for (TranscriptCorrection correction : videoTranscriptCorrections(sourceId, pageId, version)) {
                    deleted |= store.remove(sourceId, correction.attachmentId(), stored);
                }
                deleted |= store.remove(sourceId, correctionIndexId(pageId), stored);
            } else if (deleted) {
                storeTranscriptIndex(sourceId, pageId, version, index);
            }
        }
        if (deleted) {
            transcriptChanged(sourceId, pageId);
        }
        return deleted;
    }

    /** Die abgelegten Korrekturen aller Originale des Videos dieser Fassung, nach Anzeigename. */
    public List<TranscriptCorrection> videoTranscriptCorrections(String sourceId, String pageId, String version) {
        java.util.Properties index = transcriptIndex(sourceId, pageId, version);
        List<TranscriptCorrection> found = new java.util.ArrayList<>();
        for (String key : index.stringPropertyNames()) {
            if (!key.endsWith(".model") || key.startsWith(ORIGINAL)) {
                continue;
            }
            String id = key.substring(0, key.length() - ".model".length());
            if (store.find(sourceId, id, frameVersion(version)).isPresent()) {
                found.add(new TranscriptCorrection(id, index.getProperty(id + ".label", id),
                        index.getProperty(key), index.getProperty(id + ".input", ""),
                        Boolean.parseBoolean(index.getProperty(id + ".complete")),
                        index.getProperty(id + ".whisper", "")));
            }
        }
        found.sort(java.util.Comparator.comparing(TranscriptCorrection::label));
        return List.copyOf(found);
    }

    /** Die abgelegten Korrekturen des Originals dieses Whisper-Modells, nach Anzeigename. */
    public List<TranscriptCorrection> videoTranscriptCorrections(String sourceId, String pageId, String version,
                                                                 String whisper) {
        String model = whisper == null ? "" : whisper.strip();
        return videoTranscriptCorrections(sourceId, pageId, version).stream()
                .filter(correction -> correction.whisper().equals(model)).toList();
    }

    /** Die Korrektur dieses Modells zum gewählten Original; leer ohne. */
    public Optional<TranscriptCorrection> videoTranscriptCorrection(String sourceId, String pageId, String version,
                                                                    String model) {
        return chosenVideoTranscript(sourceId, pageId, version).flatMap(original ->
                videoTranscriptCorrection(sourceId, pageId, version, original.whisper(), model));
    }

    /** Die Korrektur dieses Modells zum Original dieses Whisper-Modells; leer ohne. */
    public Optional<TranscriptCorrection> videoTranscriptCorrection(String sourceId, String pageId, String version,
                                                                    String whisper, String model) {
        String id = correctionId(pageId, whisper == null ? "" : whisper.strip(), model);
        return videoTranscriptCorrections(sourceId, pageId, version).stream()
                .filter(correction -> correction.attachmentId().equals(id)).findFirst();
    }

    /** Der Text eines Transkript-Anhangs dieser Fassung: ein Original oder eine Korrektur. */
    public Optional<String> videoTranscriptText(String sourceId, String pageId, String version, String attachmentId) {
        if (!transcriptAttachment(attachmentId) || attachmentId.equals(correctionIndexId(pageId))) {
            return Optional.empty();
        }
        return store.find(sourceId, attachmentId, frameVersion(version)).map(this::read)
                .map(content -> new String(content.content(), java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Das Verzeichnis der Transkripte eines Videos: gewähltes Original, Originale je Modell und Korrekturen. */
    private java.util.Properties transcriptIndex(String sourceId, String pageId, String version) {
        java.util.Properties index = new java.util.Properties();
        store.find(sourceId, correctionIndexId(pageId), frameVersion(version)).map(this::read).ifPresent(content -> {
            try {
                index.load(new java.io.StringReader(new String(content.content(),
                        java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return index;
    }

    private void storeTranscriptIndex(String sourceId, String pageId, String version, java.util.Properties index) {
        java.io.StringWriter written = new java.io.StringWriter();
        try {
            index.store(written, null);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        byte[] indexBytes = written.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.put(new Asset(sourceId, pageId, correctionIndexId(pageId), frameVersion(version),
                "Korrekturen.properties", "text/plain", AssetKind.TRANSCRIPT, indexBytes.length,
                sha256(indexBytes), clock.instant()), indexBytes);
    }

    private static String correctionIndexId(String pageId) {
        return VIDEO_TRANSCRIPT + "corrections-" + framePage(pageId);
    }

    /** Die Korrektur eines Originals; die des älteren ohne Modell behält ihre bisherige Kennung. */
    private static String correctionId(String pageId, String whisper, String model) {
        String key = whisper.isEmpty() ? model : whisper + "|" + model;
        return transcriptId(pageId) + "-" + sha256(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .substring("sha256:".length(), "sha256:".length() + 12);
    }

    /** Ob die Seite dieser Fassung ein abgelegtes Transkript hat, gleich welches Original. */
    public boolean hasVideoTranscript(String sourceId, String pageId, String version) {
        return !videoTranscriptOriginals(sourceId, pageId, version).isEmpty();
    }

    /** Ob ein Anhang das Transkript eines Videos ist; es liegt nur in der Ablage, nie bei der Quelle. */
    public static boolean transcriptAttachment(String attachmentId) {
        return attachmentId != null && attachmentId.startsWith(VIDEO_TRANSCRIPT);
    }

    private static String transcriptId(String pageId) {
        return VIDEO_TRANSCRIPT + framePage(pageId);
    }

    /**
     * Das Original eines Whisper-Modells: ohne Modell die bisherige Kennung, sonst mit {@code -w-} und dem Modell
     * (unterscheidbar von den Korrekturen, die zwölf Hexzeichen anhängen).
     */
    private static String originalId(String pageId, String whisper) {
        if (whisper.isEmpty()) {
            return transcriptId(pageId);
        }
        String name = whisper.matches("[A-Za-z0-9_-]{1,40}") ? whisper
                : sha256(whisper.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring("sha256:".length(),
                "sha256:".length() + 16);
        return transcriptId(pageId) + "-w-" + name;
    }

    /** Ob ein Anhang eine Marke eines Videos ist; sie liegt nur in der Ablage, nie bei der Quelle. */
    public static boolean videoMark(String attachmentId) {
        return attachmentId != null && attachmentId.startsWith(VIDEO_MARK);
    }

    private static String markId(String pageId, long millis) {
        return VIDEO_MARK + framePage(pageId) + "-" + millis;
    }

    /** Die Kennung des virtuellen Anhangs einer Marke. */
    public static String markAttachmentId(String pageId, long millis) {
        return markId(pageId, millis);
    }

    /** Der Dateiname des Standbilds einer Marke, etwa {@code 00-03-12.png}. */
    public static String markFileName(long millis) {
        return frameName(millis);
    }

    /** Das abgelegte Standbild einer Marke ohne seine Bytes; leer, solange es nicht gezogen ist. */
    public Optional<Asset> videoFrameAsset(String sourceId, String pageId, String version, long millis) {
        return store.find(sourceId, markId(pageId, millis), frameVersion(version));
    }

    /**
     * Ein abgelegtes Bild der Lese-Ansicht ohne seine Bytes, unter derselben ID und Version wie {@link #viewImage};
     * fragt die Quelle nie.
     */
    public Optional<Asset> storedViewImage(String sourceId, String pageVersion, HarvestSource.ViewImage image) {
        String attachmentId = image.attachmentId() != null ? image.attachmentId()
                : "view-" + sha256(image.src().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .substring("sha256:".length(), "sha256:".length() + 32);
        String version = image.attachmentId() != null ? image.version() : pageVersion;
        return store.find(sourceId, attachmentId, version);
    }

    /** Die Position einer Marke aus der Kennung ihres virtuellen Anhangs. */
    public static long markMillis(String attachmentId) {
        return Long.parseLong(attachmentId.substring(attachmentId.lastIndexOf('-') + 1));
    }

    /** Die Position einer Marke im Video, etwa {@code 00:03:12}. */
    public static String markTitle(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /** Die Fassung des Videos; eine Quelle ohne Fassung legt seine Standbilder unter einer festen ab. */
    private static String frameVersion(String version) {
        return version == null || version.isBlank() ? "unversioned" : version;
    }

    /** Eine kurze, stabile Kennung der Seite für die Pseudo-Anhänge; Anhangs-IDs gelten je Quelle. */
    private static String framePage(String pageId) {
        return sha256(pageId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring("sha256:".length(),
                "sha256:".length() + 16);
    }

    /** Der Dateiname des Standbilds einer Marke nach ihrer Position im Video, etwa {@code 00-03-12.png}. */
    static String frameName(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%02d-%02d-%02d.png", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /**
     * Die abgelegten Bytes zu {@code sha256}, dagegen geprüft; liest nie von der Quelle. Der Inhalt liegt nach seinem
     * Hash ab, daher auch ohne Version der Quelle und auch dann, wenn inzwischen eine neuere Version abgelegt ist.
     *
     * @return leer, wenn nichts oder etwas anderes abgelegt ist, oder über {@link #MAX_IN_MEMORY_BYTES} (dann nur
     *         als Strom, {@link #openStored})
     */
    public Optional<byte[]> stored(String sourceId, String attachmentId, String version, String sha256) {
        if (sha256 == null) {
            return Optional.empty();
        }
        Optional<Asset> found = store.find(sourceId, attachmentId, version).filter(a -> a.sha256().equals(sha256));
        if (found.isPresent() && found.get().size() > maxInMemoryBytes) {
            return Optional.empty();
        }
        Asset asset = found.orElseGet(() -> new Asset(sourceId, "-", attachmentId == null ? "-" : attachmentId,
                version, null, null, AssetKind.OTHER, 0, sha256, clock.instant()));
        byte[] content;
        // Ohne Eintrag ist die Größe unbekannt und zählt als groß; gelesen wird dann höchstens bis zur Grenze.
        try (LargeFileGate.Permit permit = gate.enter(sourceId, found.map(Asset::size).orElse(null));
             InputStream in = store.open(asset)) {
            content = in.readNBytes((int) Math.min(maxInMemoryBytes + 1, Integer.MAX_VALUE - 8));
        } catch (AssetStoreException | IOException e) {
            return Optional.empty();
        }
        if (content.length > maxInMemoryBytes) {
            return Optional.empty();
        }
        return sha256(content).equals(sha256) ? Optional.of(content) : Optional.empty();
    }

    /**
     * Wie {@link #stored}, aber als Strom, ohne die Datei ganz in den Speicher zu lesen (Dateien beliebiger Größe,
     * Angelo 11.11.2026): Eine Abweichung von {@code sha256} meldet der Strom am Ende als {@link IOException}, bevor
     * er das Ende anzeigt. Der Aufrufer schließt ihn.
     *
     * @return leer, wenn nichts abgelegt ist
     */
    public Optional<InputStream> openStored(String sourceId, String attachmentId, String version, String sha256) {
        if (sha256 == null) {
            return Optional.empty();
        }
        Asset asset = store.find(sourceId, attachmentId, version).filter(a -> a.sha256().equals(sha256))
                .orElseGet(() -> new Asset(sourceId, "-", attachmentId == null ? "-" : attachmentId, version, null,
                        null, AssetKind.OTHER, 0, sha256, clock.instant()));
        try {
            return Optional.of(store.open(asset));
        } catch (AssetStoreException e) {
            return Optional.empty();
        }
    }

    /**
     * Das abgelegte Dokument eines Anhangs mit seinen enthaltenen Dateien für den Export (ADR 0026, AP4c); liest nichts
     * neu und fragt die Quelle nicht. Leer ohne strukturierte Extraktion dieser Bytes.
     */
    public Optional<DocumentExport> documentExport(String sourceId, String attachmentId, String version,
                                                   String sha256) {
        DocumentExtractions structured = extractions;
        if (structured == null || sha256 == null) {
            return Optional.empty();
        }
        Asset asset = store.find(sourceId, attachmentId, version).filter(a -> a.sha256().equals(sha256))
                .orElse(null);
        if (asset == null || asset.kind() != AssetKind.OTHER) {
            return Optional.empty();
        }
        try {
            return structured.export(asset, store);
        } catch (AssetStoreException e) {
            return Optional.empty();
        }
    }

    /** Version der draw.io-Grafik im Speicher; ändert sich mit dem Renderer, damit alte Grafiken neu entstehen. */
    public static final String DRAWIO_RENDITION = "drawio-svg-1";
    /** Version der draw.io-Grafik als PNG für die Lese-Ansicht ({@link #viewDiagram}). */
    public static final String DRAWIO_PNG_RENDITION = "drawio-png-1";
    /** Version eines gerasterten SVG der Quelle (draw.io einer anderen Seite) als PNG für die Lese-Ansicht. */
    public static final String SVG_PNG_RENDITION = "svg-png-1";
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
            case SVG_PNG_RENDITION -> Diagrams.svgPng(source);
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
        Optional<Asset> stored = stored(list.sourceId(), attachment);
        // Ein abgelegter Text braucht die Bytes nicht: weder Lesen der Datei noch ein Platz der Schranke.
        Optional<AssetText> existing = stored.isPresent() ? storedText(extraction, stored.get()) : Optional.empty();
        Asset asset;
        AssetText text;
        DocumentExtractions structured = extractions;
        if (existing.isPresent()) {
            asset = stored.get();
            text = existing.get();
        } else if (structured != null && stored.isPresent()) {
            // Aus der abgelegten Datei, ohne Größengrenze (ADR 0026); die Schranke für große Dateien gilt weiter.
            try (LargeFileGate.Permit permit = gate.enter(list.sourceId(), stored.get().size())) {
                asset = stored.get();
                // Ohne Recht zum Ablegen nur gelesen, wie bisher.
                text = mayStore ? structured.text(asset, store, clock.instant())
                        : structured.peek(asset, store, clock.instant());
            }
        } else if (stored.isPresent() && stored.get().size() > IN_MEMORY_BYTES) {
            // Über der Grenze der Textextraktion bleibt die Datei ungelesen (Dateien beliebiger Größe, Angelo
            // 11.11.2026); sie hat wie bisher keinen Text.
            throw tooLarge();
        } else {
            try (LargeFileGate.Permit permit = gate.enter(list.sourceId(),
                    stored.map(Asset::size).orElse(attachment.fileSize()))) {
                // Auch frisch geladen kommt eine Datei über dieser Grenze nicht in den Speicher.
                Downloaded content = stored.isPresent() ? new Downloaded(stored.get(), read(stored.get()).content())
                        : download(source(list.sourceId()), list.attachments().pageId(), attachment, mayStore, false);
                asset = content.asset();
                existing = storedText(extraction, asset);
                if (existing.isPresent()) {
                    text = existing.get();
                } else if (structured != null && mayStore) {
                    text = structured.text(asset, store, clock.instant());
                } else {
                    text = extract(extraction, content, mayStore);
                }
            }
        }
        if ((mayStore || existing.isPresent()) && indexDocument(list, asset, text)) {
            flushIndex();
        }
        return text;
    }

    private AssetText text(DocumentText extraction, AssetContent content) {
        return storedText(extraction, content.asset()).orElseGet(() -> extract(extraction, content, true));
    }

    /**
     * Der abgelegte Anhang dieser Version, mit der Angabe der aktuellen Liste zu ausgelassenen Teilen (AP7c): Sie
     * gehört zur Quelle, und ältere Einträge kennen sie noch nicht.
     */
    private Optional<Asset> stored(String sourceId, PageAttachment attachment) {
        return store.find(sourceId, attachment.attachmentId(), attachment.version())
                .map(asset -> asset.withPartsWithoutFile(attachment.partsWithoutFile()));
    }

    /**
     * Der abgelegte Text eines Dokuments, auch ein veralteter ({@link #storedOrStale}); für alles andere
     * {@code UNSUPPORTED}.
     */
    private Optional<AssetText> storedText(DocumentText extraction, Asset asset) {
        if (asset.kind() != AssetKind.OTHER) {
            throw new DocumentTextException(DocumentTextException.Reason.UNSUPPORTED, "Kein Dokument", null);
        }
        return storedOrStale(asset.sha256()).map(text -> DocumentExtractions.withSource(text, asset));
    }

    /**
     * Der abgelegte Text dieser Bytes (ADR 0026, Punkt 11, Abnahme 8): der der eingestellten Extraktion, sonst der
     * jüngste einer anderen Extraktion oder Version, als veraltet gekennzeichnet; er bleibt lieferbar, bis das Original
     * neu gelesen ist. Ein Text der eingestellten Extraktion ohne abgelegte strukturierte Extraktion (Ablage verworfen)
     * gilt ebenfalls als veraltet. Der Status kommt vom Text selbst (etwa beim Import gekürzt), sonst aus der
     * zugehörigen abgelegten Extraktion (AP4a).
     */
    private Optional<AssetText> storedOrStale(String sha256) {
        DocumentExtractions structured = extractions;
        Optional<AssetText> current = store.text(sha256, textExtractor(), textVersion());
        if (current.isPresent()) {
            if (structured == null) {
                return current;
            }
            AssetText text = current.get();
            Optional<StoredExtraction> extraction = structured.stored(sha256);
            if (extraction.isEmpty()) {
                return Optional.of(text.asStale());
            }
            return Optional.of(text.outcome().isPresent() ? text
                    : text.withOutcome(extraction.get().document().outcome()));
        }
        Optional<AssetText> older = store.latestText(sha256).map(AssetText::asStale);
        if (older.isEmpty() || structured == null || older.get().outcome().isPresent()) {
            return older;
        }
        AssetText text = older.get();
        return Optional.of(structured.readable(sha256)
                .filter(found -> found.key().extractor().equals(text.extractor())
                        && found.key().version().equals(text.version()))
                .map(found -> text.withOutcome(found.document().outcome())).orElse(text));
    }

    /** Wie {@link #extract(DocumentText, AssetContent, boolean)}; ohne Bytes im Speicher {@code TOO_LARGE}. */
    private AssetText extract(DocumentText extraction, Downloaded content, boolean keep) {
        if (content.content() == null) {
            throw tooLarge();
        }
        return extract(extraction, new AssetContent(content.asset(), content.content()), keep);
    }

    /** Eine Datei über {@link #IN_MEMORY_BYTES}: zu groß für die Textextraktion, wie in {@code TikaDocumentText}. */
    private static DocumentTextException tooLarge() {
        return new DocumentTextException(DocumentTextException.Reason.TOO_LARGE,
                "Datei größer als " + IN_MEMORY_BYTES + " Bytes", null);
    }

    private AssetText extract(DocumentText extraction, AssetContent content, boolean keep) {
        Asset asset = content.asset();
        DocumentText.Extracted extracted = extraction.extract(asset.title(), asset.mediaType(), content.content());
        AssetText text = new AssetText(asset.sha256(), extraction.id(), extraction.version(), extracted.text(),
                extracted.truncated(), clock.instant());
        if (keep) {
            store.putText(text);
        }
        return DocumentExtractions.withSource(text, asset);
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
            if (asset.kind() == AssetKind.OTHER && (documents != null || extractions != null)) {
                Optional<Boolean> sections = indexSections(list, asset);
                if (sections.isPresent()) {
                    return sections.get();
                }
                // Auch ein veralteter Text bleibt im Index, bis das Original neu gelesen ist (ADR 0026, Punkt 11).
                Optional<AssetText> found = storedOrStale(asset.sha256());
                String text = found.map(AssetText::text).orElse("");
                return indexText(list, asset, text, text.isEmpty() ? "" : textKey(found.get()));
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
            if (stored(list.sourceId(), attachment).isPresent()) {
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
        return indexText(list, asset, description.text(), describedOrigin(description));
    }

    /**
     * Herkunft einer Bildbeschreibung im Index: Modell mit Version und Prüfsumme des Textes. Ohne den Text bliebe nach
     * „Neu beschreiben“ (gleiches Bild, gleiches Modell, anderer Text) die alte Beschreibung auffindbar.
     */
    private static String describedOrigin(ImageDescription description) {
        String text = description.text() == null ? "" : description.text();
        return "describer:" + description.describer() + ":" + description.modelVersion() + ":"
                + sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Herkunft eines Textes im Index: Extraktion und Version, aus der er stammt. */
    private static String textKey(AssetText text) {
        return textKey(text.extractor(), text.version());
    }

    private static String textKey(String extractor, String version) {
        return "text:" + extractor + ":" + version;
    }

    /**
     * Legt einen abgelegten Dokument-Anhang aus seiner abgelegten Extraktion neu in den Volltextindex, ohne Download
     * und ohne die übrigen Einträge der Seite zu berühren (ADR 0026, AP4b; für „Neu lesen“, Punkt 11). Gibt es keine
     * abgelegte Extraktion mit Text, bleibt der Eintrag, wie er ist.
     *
     * @return ob der Index geändert wurde
     */
    public boolean reindex(PageAttachmentList list, String attachmentId) {
        PageAttachment attachment = attachment(list, attachmentId);
        Optional<Asset> stored = stored(list.sourceId(), attachment);
        if (stored.isEmpty()) {
            return false;
        }
        boolean changed = indexSections(list, stored.get()).orElse(false);
        if (changed) {
            flushIndex();
        }
        return changed;
    }

    /**
     * Ausgang von {@link #reread}.
     *
     * @param read   ob das Original jetzt gelesen wurde
     * @param text   ob die neue Extraktion Text hat und den bisherigen Stand ersetzt hat
     * @param code   fester Code, wenn kein Text entstand ({@link DocumentTextException.Reason#code()} oder
     *               {@code rate-limited}); sonst {@code null}
     */
    public record Reread(boolean read, boolean text, String code) {

        /** Nichts zu tun: schon aktuell oder nicht betroffen. */
        public static final Reread SKIPPED = new Reread(false, false, null);
    }

    /**
     * Liest ein abgelegtes Dokument ohne Download neu („Neu lesen“, ADR 0026, Punkt 11, Abnahme 8), wenn es betroffen
     * ist: ohne {@code statuses} jedes Dokument ohne Extraktion der eingestellten Version (Versionswechsel, Import,
     * vorübergehend gescheitert), mit {@code statuses} nur Dokumente, deren aktuelle Extraktion für das Dokument selbst
     * oder eine enthaltene Datei einen dieser Status hat (etwa nach geänderten Grenzen), sofern sie nicht nach
     * {@code since} entstand. Der bisherige Stand gilt, bis das
     * Lesen Text liefert; erst dann werden Texte und Extraktionen anderer Versionen derselben Bytes entfernt. Ein
     * gescheitertes Lesen lässt ihn stehen. Den Volltextindex ersetzt {@link #reindexStored}.
     *
     * @param since bei {@code statuses}: Extraktionen ab diesem Zeitpunkt gelten als schon neu gelesen; sonst egal
     * @throws DocumentTextException {@code NOT_CONFIGURED} ohne strukturierte Extraktion
     */
    public Reread reread(Asset asset, Set<ExtractionStatus> statuses, Instant since) {
        DocumentExtractions structured = extractions;
        if (structured == null || documents == null) {
            throw new DocumentTextException(DocumentTextException.Reason.NOT_CONFIGURED,
                    "Keine strukturierte Extraktion konfiguriert", null);
        }
        if (asset.kind() != AssetKind.OTHER) {
            return Reread.SKIPPED;
        }
        Optional<StoredExtraction> current = structured.stored(asset.sha256());
        boolean fresh = !statuses.isEmpty();
        if (fresh ? current.isEmpty() || !matches(current.get().document(), statuses)
                || !current.get().createdAt().isBefore(since) : current.isPresent()) {
            return Reread.SKIPPED;
        }
        try (LargeFileGate.Permit permit = gate.enter(asset.sourceId(), asset.size())) {
            AssetText text = fresh ? structured.reread(asset, store, clock.instant())
                    : structured.text(asset, store, clock.instant());
            try {
                store.removeOtherTexts(asset.sha256(), text.extractor(), text.version());
                structured.removeOlderVersions(asset.sha256());
            } catch (AssetStoreException | IllegalArgumentException e) {
                // Der neue Stand gilt; alte Stände räumt das nächste Neulesen weg.
                LOG.log(System.Logger.Level.WARNING, "Alte Stände eines Dokuments in {0} nicht entfernt ({1})",
                        asset.sourceId(), e.getClass().getName());
            }
            return new Reread(true, true, null);
        } catch (DocumentTextException e) {
            return new Reread(true, false, e.reason().code());
        } catch (SourceAccessException e) {
            return new Reread(false, false, code(e.reason()));
        }
    }

    /**
     * Ob das Dokument selbst oder eine enthaltene Datei einen dieser Status hat: Ein Behälter bleibt {@code OK}, wenn
     * nur enthaltene Dateien an einer Grenze enden (zu tief, zu groß, gekürzt).
     */
    private static boolean matches(StructuredDocument document, Set<ExtractionStatus> statuses) {
        return statuses.contains(document.outcome().status())
                || document.directory().stream().anyMatch(entry -> statuses.contains(entry.outcome().status()));
    }

    /**
     * Ersetzt die Einträge eines abgelegten Dokument-Anhangs im Volltextindex aus seiner abgelegten Extraktion, ohne
     * Download und ohne die Quelle zu fragen (für „Neu lesen“): Seite und Anhang kommen aus dem Eintrag der Ablage, der
     * Space vom Aufrufer. Nur ein Anhang, der schon im Index liegt, bekommt neue Einträge; einer, den eine Ernte als
     * nicht mehr zur Seite gehörig entfernt hat, bleibt draußen. Ersetzt auch bei gleicher Extraktion und Version, denn
     * ein Neulesen unter anderen Grenzen ändert die Abschnitte, nicht den Schlüssel. Sichert den Index nicht
     * ({@link #flushTextIndex}).
     *
     * @return ob der Index geändert wurde
     */
    public boolean reindexStored(Asset asset, String spaceKey) {
        if (texts == null || asset.kind() != AssetKind.OTHER) {
            return false;
        }
        if (texts.attachmentInputSha256(new PageKey(asset.sourceId(), asset.pageId()), asset.attachmentId())
                .isEmpty()) {
            return false;
        }
        PageAttachmentList list = new PageAttachmentList(asset.sourceId(), spaceKey, new PageAttachments(
                asset.pageId(), List.of(), List.of(), "application/json", "stored", List.of(), clock.instant()),
                clock.instant());
        return indexSections(list, asset, true).orElse(false);
    }

    /** Sichert Änderungen am Volltextindex dauerhaft, etwa nach {@link #reindexStored}. */
    public void flushTextIndex() {
        if (texts != null) {
            flushIndex();
        }
    }

    /** Ein Dokument mit Text: aus den Abschnitten der abgelegten Extraktion, sonst sein bisheriger Text. */
    private boolean indexDocument(PageAttachmentList list, Asset asset, AssetText text) {
        Optional<Boolean> sections = indexSections(list, asset);
        return sections.isPresent() ? sections.get() : indexText(list, asset, text.text(), textKey(text));
    }

    /**
     * Legt ein Dokument mit seinen enthaltenen Dateien je Abschnitt der abgelegten Extraktion im Volltextindex ab
     * (ADR 0026, AP4b): alle Einträge des Anhangs in einem Schritt ersetzt, abschnittsweise aus der Ablage gelesen.
     * Ohne abgelegte Extraktion mit Text bleibt der Index unberührt; scheitert das Lesen, gilt der alte Stand. Ohne
     * aktuelle Extraktion mit Text gilt die jüngste lesbare anderer Version, bis das Original neu gelesen ist
     * (ADR 0026, Punkt 11).
     *
     * @return leer, wenn es keine abgelegte Extraktion mit Text gibt; sonst ob der Index geändert wurde
     */
    private Optional<Boolean> indexSections(PageAttachmentList list, Asset asset) {
        return indexSections(list, asset, false);
    }

    /** @param replace ersetzt auch, wenn der abgelegte Stand im Index gleich aussieht (nach einem Neulesen) */
    private Optional<Boolean> indexSections(PageAttachmentList list, Asset asset, boolean replace) {
        DocumentExtractions structured = extractions;
        if (texts == null || structured == null || asset.kind() != AssetKind.OTHER) {
            return Optional.empty();
        }
        PageKey page = new PageKey(list.sourceId(), list.attachments().pageId());
        String title = asset.title() == null ? "" : asset.title();
        try {
            Optional<StoredExtraction> extraction = structured.readable(asset.sha256());
            if (extraction.isEmpty()) {
                return Optional.empty();
            }
            ExtractionKey key = extraction.get().key();
            String input = inputSha256(list.spaceKey(), asset, title,
                    textKey(key.extractor(), key.version()) + ":sections");
            try (java.util.stream.Stream<DocumentExtractions.IndexSection> stream =
                         structured.indexSections(extraction.get())) {
                if (!replace && texts.attachmentInputSha256(page, asset.attachmentId()).filter(input::equals)
                        .isPresent()) {
                    return Optional.of(false);
                }
                java.util.Iterator<DocumentExtractions.IndexSection> found = stream.iterator();
                if (!found.hasNext()) {
                    // Kein Abschnitt mit Text (etwa ein Behälter ohne eigenen Text und ohne lesbare Teile): wie
                    // bisher nur der Dateiname.
                    texts.replaceAttachment(page, asset.attachmentId(), List.of(new PageTextIndex.AttachmentEntry(
                            page, list.spaceKey(), asset.attachmentId(), title, "", input, asset.mediaType())));
                    indexed(list, asset, structured);
                    return Optional.of(true);
                }
                Iterable<PageTextIndex.AttachmentEntry> entries = () -> new java.util.Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return found.hasNext();
                    }

                    @Override
                    public PageTextIndex.AttachmentEntry next() {
                        DocumentExtractions.IndexSection section = found.next();
                        return new PageTextIndex.AttachmentEntry(page, list.spaceKey(), asset.attachmentId(), title,
                                section.text(), input, section.partId(), section.partTitle(), section.section(),
                                asset.mediaType());
                    }
                };
                texts.replaceAttachment(page, asset.attachmentId(), entries);
                indexed(list, asset, structured);
                return Optional.of(true);
            }
        } catch (RuntimeException e) {
            // Ohne Meldung und ohne Seite: sie könnte Text oder IDs enthalten.
            LOG.log(System.Logger.Level.WARNING, "Volltextindex eines Dokuments in {0} nicht aktualisiert ({1})",
                    list.sourceId(), e.getClass().getName());
            return Optional.of(false);
        }
    }

    private void indexed(PageAttachmentList list, Asset asset, DocumentExtractions structured) {
        IndexedDocuments listener = indexedDocuments;
        if (listener == null) {
            return;
        }
        try {
            listener.indexed(list, asset, structured);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Analysen eines Dokuments in {0} nicht eingeplant ({1})",
                    list.sourceId(), e.getClass().getName());
        }
    }

    /** Stand eines Indexeintrags: Inhalt, Herkunft des Textes, Space und Dateiname. */
    private static String inputSha256(String spaceKey, Asset asset, String title, String origin) {
        return sha256((asset.sha256() + "\n" + origin + "\n" + spaceKey + "\n" + title)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Legt den Text eines Anhangs mit Seite und Space der Anhangsliste im Volltextindex ab, wenn er dort nicht schon in
     * diesem Stand liegt. Fehler bleiben hier; der Index ist neu aufbaubar.
     *
     * @param origin woher der Text stammt (Extraktion oder Modell mit Version), leer ohne Text
     * @return ob der Index geändert wurde
     */
    private boolean indexText(PageAttachmentList list, Asset asset, String text, String origin) {
        return indexText(new PageKey(list.sourceId(), list.attachments().pageId()), list.spaceKey(), asset, text,
                origin);
    }

    private boolean indexText(PageKey page, String spaceKey, Asset asset, String text, String origin) {
        if (texts == null) {
            return false;
        }
        String title = asset.title() == null ? "" : asset.title();
        String input = inputSha256(spaceKey, asset, title, origin);
        try {
            if (texts.attachmentInputSha256(page, asset.attachmentId()).filter(input::equals).isPresent()) {
                return false;
            }
            texts.putAttachment(new PageTextIndex.AttachmentEntry(page, spaceKey, asset.attachmentId(), title,
                    text, input, asset.mediaType()));
            return true;
        } catch (RuntimeException e) {
            // Ohne Meldung und ohne Seite: sie könnte Text oder IDs enthalten.
            LOG.log(System.Logger.Level.WARNING, "Volltextindex eines Anhangs in {0} nicht aktualisiert ({1})",
                    page.sourceId(), e.getClass().getName());
            return false;
        }
    }

    /**
     * Nimmt die Bildbeschreibungen dieser Seiten zurück (ADR 0028, Abschnitt 4), ohne Quelle und ohne Modell: Ihre
     * Bildanhänge stehen danach nur noch mit dem Dateinamen im Volltextindex, und die gespeicherten Beschreibungen
     * ihrer Bilder und der Bilder {@code images} entfallen, sobald keine freigegebene Seite ein Bild mit denselben
     * Bytes trägt.
     *
     * @param pages    die Seiten mit ihrem Space; nur solche, die nicht mehr freigegeben sind
     * @param images   SHA-256 weiterer Bilder dieser Seiten, etwa aus ihrem Ergebnis {@code images}
     * @param released ob eine Seite jetzt für den KI-Dienst freigegeben ist
     * @return wie viele Bildinhalte ihre Beschreibungen verloren
     */
    public int withdrawDescriptions(Map<PageKey, String> pages, Set<String> images,
                                    java.util.function.Predicate<PageKey> released) {
        Objects.requireNonNull(released, "released");
        if (pages.isEmpty()) {
            return 0;
        }
        Set<String> candidates = new HashSet<>(images);
        // Je Seite und Anhang der jüngste abgelegte Stand eines Bildes: Er steht im Index.
        Map<PageKey, Map<String, Asset>> attachments = new HashMap<>();
        Set<String> sources = new java.util.TreeSet<>();
        pages.keySet().forEach(key -> sources.add(key.sourceId()));
        for (String sourceId : sources) {
            try (java.util.stream.Stream<Asset> entries = store.entries(sourceId)) {
                entries.filter(asset -> asset.kind() == AssetKind.IMAGE || asset.kind() == AssetKind.VECTOR)
                        .filter(asset -> pages.containsKey(new PageKey(sourceId, asset.pageId())))
                        .forEach(asset -> {
                            candidates.add(asset.sha256());
                            attachments.computeIfAbsent(new PageKey(sourceId, asset.pageId()), key -> new HashMap<>())
                                    .merge(asset.attachmentId(), asset,
                                            (a, b) -> b.retrievedAt().isAfter(a.retrievedAt()) ? b : a);
                        });
            }
        }
        candidates.remove(null);
        // Trägt eine freigegebene Seite, gleich welcher Quelle, dieselben Bytes, bleibt deren Beschreibung.
        Set<String> kept = new HashSet<>();
        for (HarvestSource source : catalog.all()) {
            try (java.util.stream.Stream<Asset> entries = store.entries(source.id())) {
                entries.filter(asset -> candidates.contains(asset.sha256()))
                        .filter(asset -> {
                            PageKey key = new PageKey(source.id(), asset.pageId());
                            return !pages.containsKey(key) && released.test(key);
                        })
                        .forEach(asset -> kept.add(asset.sha256()));
            }
        }
        int removed = 0;
        for (String sha : candidates) {
            if (!kept.contains(sha)) {
                store.removeDescriptions(sha);
                removed++;
            }
        }
        boolean indexed = false;
        for (Map.Entry<PageKey, Map<String, Asset>> page : attachments.entrySet()) {
            for (Asset asset : page.getValue().values()) {
                if (texts != null && !kept.contains(asset.sha256())
                        && texts.attachmentInputSha256(page.getKey(), asset.attachmentId()).isPresent()) {
                    indexed |= indexText(page.getKey(), pages.get(page.getKey()), asset, "", "");
                }
            }
        }
        if (indexed) {
            flushIndex();
        }
        return removed;
    }

    /**
     * Nach einer Ernte: Anhänge, die die vollständige Liste nicht mehr nennt, fallen aus dem Index; Änderungen werden
     * dauerhaft.
     */
    private static void report(List<AssetHarvest.Entry> entries, Consumer<AssetHarvest.Entry> each,
                               AssetHarvest.Entry entry) {
        entries.add(entry);
        each.accept(entry);
    }

    private void finishIndexing(PageAttachmentList list, boolean indexed) {
        if (texts == null) {
            return;
        }
        try {
            if (list.attachments().complete()) {
                Set<String> current = new HashSet<>();
                list.attachments().attachments().forEach(a -> current.add(a.attachmentId()));
                PageKey page = new PageKey(list.sourceId(), list.attachments().pageId());
                texts.retainAttachments(page, current);
                indexed = true;
                IndexedDocuments listener = indexedDocuments;
                if (listener != null) {
                    listener.retained(page, Set.copyOf(current));
                }
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
     * Wie {@link #harvestText(AssetContent)} für einen frischen Download; über der Grenze der Textextraktion liegt er
     * nicht im Speicher und hat wie bisher keinen Text.
     */
    private void harvestText(Downloaded downloaded) {
        if (extractions != null && documents != null && downloaded.asset().kind() == AssetKind.OTHER) {
            harvestStructured(downloaded.asset());
        } else if (downloaded.content() != null) {
            harvestText(new AssetContent(downloaded.asset(), downloaded.content()));
        } else if (documents != null && downloaded.asset().kind() == AssetKind.OTHER) {
            LOG.log(System.Logger.Level.INFO, "Kein Dokumenttext beim Ablegen: source={0}, code={1}",
                    downloaded.asset().sourceId(), DocumentTextException.Reason.TOO_LARGE.code());
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

    /** Wie {@link #harvestText(AssetContent)}, aber aus der abgelegten Datei und ohne Größengrenze (ADR 0026). */
    private void harvestStructured(Asset asset) {
        DocumentExtractions structured = extractions;
        try {
            if (store.text(asset.sha256(), structured.id(), structured.version()).isEmpty()) {
                structured.text(asset, store, clock.instant());
            }
        } catch (DocumentTextException e) {
            if (e.reason() != DocumentTextException.Reason.UNSUPPORTED) {
                LOG.log(System.Logger.Level.INFO, "Kein Dokumenttext beim Ablegen: source={0}, code={1}",
                        asset.sourceId(), e.reason().code());
            }
        } catch (AssetStoreException e) {
            LOG.log(System.Logger.Level.WARNING, "Dokumenttext nicht ablegbar: source={0} ({1})",
                    asset.sourceId(), e.getClass().getName());
        }
    }

    /**
     * Holt den Text eines schon abgelegten Dokuments aus der Ablage nach, etwa für Ablagen von vor #183, ohne den
     * Anhang neu zu laden. Je Laufzeit nur ein Versuch je Inhalt, damit ein nicht lesbares Dokument nicht bei jeder
     * Ernte erneut extrahiert wird.
     */
    private void backfillText(Asset asset) {
        if (documents == null || asset.kind() != AssetKind.OTHER
                || store.text(asset.sha256(), textExtractor(), textVersion()).isPresent()) {
            return;
        }
        if (backfilled.size() > MAX_BACKFILLED) {
            backfilled.clear();
        }
        if (!backfilled.add(asset.sha256())) {
            return;
        }
        if (extractions != null) {
            try (LargeFileGate.Permit permit = gate.enter(asset.sourceId(), asset.size())) {
                harvestStructured(asset);
            } catch (SourceAccessException e) {
                backfilled.remove(asset.sha256());
                LOG.log(System.Logger.Level.INFO, "Dokumenttext später nachholen: source={0}, code={1}",
                        asset.sourceId(), code(e.reason()));
            }
            return;
        }
        if (asset.size() > IN_MEMORY_BYTES) {
            // Wie beim Ablegen: Über der Grenze der Textextraktion bleibt die Datei ungelesen und ohne Text.
            harvestText(new Downloaded(asset, null));
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
        return fromSource(source.id(), () -> previewBytesNow(source, attachment, kind));
    }

    private byte[] previewBytesNow(HarvestSource source, PageAttachment attachment, AttachmentPreview kind) {
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
            content = source.attachmentPreview(attachment, kind, inMemoryLimit());
        } catch (AttachmentTooLargeException e) {
            throw new AssetTooLargeException(source.id(), inMemoryLimit());
        } finally {
            downloads.release();
        }
        if (content.length > inMemoryLimit()) {
            throw new AssetTooLargeException(source.id(), inMemoryLimit());
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
        DescribedImage described = stored(list.sourceId(), attachment)
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
        PageKey page = new PageKey(list.sourceId(), list.attachments().pageId());
        ImageDescriber model = describer(page);
        PageAttachment attachment = attachment(list, attachmentId);
        requireImage(attachment.mediaType(), attachment.title());
        // Laden bzw. Lesen und der Modellaufruf belegen die Schranke je für sich (content, generate): Der Modellaufruf
        // kann Minuten dauern und hält einen Platz nur, wenn das Bild nach seiner bekannten Größe groß ist.
        DescribedImage described = indexed(list, describe(model, content(list, attachmentId)));
        // ADR 0028: Verlor die Seite ihre Freigabe, während das Modell beschrieb, ist die Rücknahme womöglich schon
        // gelaufen; was eben entstand, nimmt dieser Aufruf selbst zurück. Erst nach dem Ablegen geprüft.
        java.util.function.Predicate<PageKey> released = key -> model.mayDescribe(key.sourceId())
                || pageReleases.test(key);
        if (!released.test(page)) {
            withdrawDescriptions(Map.of(page, list.spaceKey()), Set.of(described.asset().sha256()), released);
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_RELEASED,
                    "Freigabe während der Beschreibung entzogen", null);
        }
        return described;
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
        return describeViewImage(sourceId, pageId, pageVersion, image, false);
    }

    /**
     * Wie {@link #describeViewImage(String, String, String, HarvestSource.ViewImage)}; mit {@code releasedOnce} geht
     * das Bild auch aus einer nicht freigegebenen Quelle an das Modell, weil der Benutzer es für genau diese Seite
     * ausdrücklich erlaubt hat (Reiter „Bilder“, „Nur diese Seite einmalig beschreiben“). Gespeichert wird keine
     * Freigabe.
     */
    public DescribedImage describeViewImage(String sourceId, String pageId, String pageVersion,
                                            HarvestSource.ViewImage image, boolean releasedOnce) {
        ImageDescriber model = releasedOnce ? describer() : describer(new PageKey(sourceId, pageId));
        AssetContent content = viewImage(sourceId, pageId, pageVersion, image);
        // Nur Rasterbilder: SVG ist Text und geht als Quelle in die Analyse, nicht als Bild an das Modell.
        if (content.asset().kind() != AssetKind.IMAGE) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_AN_IMAGE, "Kein Rasterbild",
                    null);
        }
        return describe(model, content);
    }

    /**
     * Der abgelegte Text genau dieser Dokumentbytes durch die eingestellte Extraktion (ADR 0023); liest nichts und
     * fragt die Quelle nicht. Ohne ihn der jüngste Text einer anderen Extraktion oder Version, als veraltet
     * gekennzeichnet, bis das Original neu gelesen ist (ADR 0026, Punkt 11, Abnahme 8).
     *
     * @return leer ohne Extraktion oder wenn es (noch) keinen Text gibt
     */
    public Optional<AssetText> storedText(String sha256) {
        if (documents == null || sha256 == null) {
            return Optional.empty();
        }
        return storedOrStale(sha256);
    }

    /** Extraktion eines mitgebrachten Textes, wenn das Paket keine nennt ({@code attachments/v1}). */
    public static final String IMPORTED_EXTRACTOR = "import";
    /** Version eines mitgebrachten Textes, wenn das Paket keine nennt. */
    public static final String UNKNOWN_VERSION = "unbekannt";

    /** Wie {@link #adoptText(String, String, boolean, long, String, String)}, ungekürzt und ohne Angabe im Paket. */
    public boolean adoptText(String sha256, String text) {
        return adoptText(sha256, text, false, 0);
    }

    /** Wie {@link #adoptText(String, String, boolean, long, String, String)} ohne Angabe der Extraktion im Paket. */
    public boolean adoptText(String sha256, String text, boolean truncated, long bytesRead) {
        return adoptText(sha256, text, truncated, bytesRead, null, null);
    }

    /**
     * Legt einen mitgebrachten Text eines Dokument-Anhangs ab (Import eines JSON-Pakets): unter Extraktion und Version
     * aus dem Paket ({@code attachments/v2}), sonst unter {@value #IMPORTED_EXTRACTOR}/{@value #UNKNOWN_VERSION}; nie
     * unter der eingestellten Extraktion, denn eine strukturierte Extraktion entsteht beim Import nicht. So liefert
     * {@link #storedText} ihn als veraltet, bis das Original neu gelesen ist (ADR 0026, Punkt 11, Abnahme 8). Ein beim
     * Einlesen gekürzter Text trägt das selbst: {@code truncated} und Status {@code PARTIAL} mit Grund
     * {@code too-large} und gelesenem Umfang (nie stilles Kürzen, ADR 0026).
     *
     * @param extractor Extraktion laut Paket oder {@code null}
     * @param version   Version laut Paket oder {@code null}
     * @return ob er abgelegt wurde; ohne Extraktion oder ohne Text nicht
     */
    public boolean adoptText(String sha256, String text, boolean truncated, long bytesRead, String extractor,
                             String version) {
        if (documents == null || sha256 == null || text == null || text.isBlank()) {
            return false;
        }
        boolean named = extractor != null && !extractor.isBlank() && version != null && !version.isBlank()
                && !(extractor.equals(textExtractor()) && version.equals(textVersion()));
        Optional<Outcome> outcome = truncated
                ? Optional.of(Outcome.truncated(StatusReason.TOO_LARGE, new ReadExtent(bytesRead, text.length())))
                : Optional.empty();
        store.putText(new AssetText(sha256, named ? extractor : IMPORTED_EXTRACTOR,
                named ? version : UNKNOWN_VERSION, text, truncated, clock.instant(), outcome));
        return true;
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

    /**
     * Beschreibt etwas Beschreibbares ({@link Describable}) mit dem eingestellten Modell und seinem eigenen Prompt, je
     * Bildbytes, Modell und Prompt nur einmal. Nur Rasterbilder.
     *
     * @param releasedOnce ob der Benutzer es für genau diese Seite einer nicht freigegebenen Quelle erlaubt hat
     * @throws ImageDescriptionException wie {@link #describeViewImage}
     */
    public DescribedImage describe(PageKey page, Describable item, boolean releasedOnce) {
        return describe(page, item, releasedOnce, false);
    }

    /**
     * Wie {@link #describe(PageKey, Describable, boolean)}; mit {@code fresh} am Speicher der Beschreibungen vorbei neu
     * beschrieben („Neu beschreiben“), die neue ersetzt die alte.
     */
    public DescribedImage describe(PageKey page, Describable item, boolean releasedOnce, boolean fresh) {
        ImageDescriber model = releasedOnce ? describer() : describer(page);
        AssetContent content = item.content();
        if (content.asset().kind() != AssetKind.IMAGE) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.NOT_AN_IMAGE, "Kein Rasterbild",
                    null);
        }
        if (fresh) {
            ImageDescription description = generate(model, content, item.instruction());
            return new DescribedImage(content.asset(), description, true);
        }
        return describe(model, content, item.instruction());
    }

    private DescribedImage describe(ImageDescriber model, AssetContent content) {
        return describe(model, content, null);
    }

    /**
     * Die Kennung des Beschreibers für die Ablage der Beschreibungen: mit eigenem Prompt samt dessen Hash, damit ein
     * Bild unter verschiedenen Prompts verschiedene Beschreibungen hat; mit dem Standard-Prompt wie bisher.
     */
    static String describerKey(ImageDescriber model, String instruction) {
        return instruction == null ? model.id() : model.id() + "#"
                + sha256(instruction.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(7, 19);
    }

    private DescribedImage describe(ImageDescriber model, AssetContent content, String instruction) {
        Asset asset = content.asset();
        String describer = describerKey(model, instruction);
        Optional<ImageDescription> existing = store.description(asset.sha256(), describer, model.modelVersion());
        if (existing.isPresent()) {
            return new DescribedImage(asset, existing.get(), false);
        }
        String key = asset.sha256() + '\n' + describer + '\n' + model.modelVersion();
        CompletableFuture<ImageDescription> mine = new CompletableFuture<>();
        CompletableFuture<ImageDescription> running = describing.putIfAbsent(key, mine);
        if (running != null) {
            return new DescribedImage(asset, await(running), false);
        }
        try {
            // Ein eben beendeter Lauf hat die Beschreibung womöglich zwischen Prüfung und Anmeldung abgelegt.
            existing = store.description(asset.sha256(), describer, model.modelVersion());
            if (existing.isPresent()) {
                mine.complete(existing.get());
                return new DescribedImage(asset, existing.get(), false);
            }
            ImageDescription description = generate(model, content, instruction);
            mine.complete(description);
            return new DescribedImage(asset, description, true);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            describing.remove(key, mine);
        }
    }

    /**
     * Übernimmt die Beschreibung eines Bildes der Lese-Ansicht (Reiter „Bilder“, ADR 0021) in den Volltextindex, wenn
     * das Bild ein Anhang dieser Seite ist, der dort schon steht (B2): wie beim Ablegen der Anhänge
     * ({@link #indexStored}) als Text des Bildanhangs, nur sofort statt beim nächsten Ablegen. Nur Beschreibungen mit
     * dem Standard-Prompt des eingestellten Modells; Bilder, die kein Anhang sind, und eigene Prompts (Marken eines
     * Videos) bleiben draußen, ebenso Seiten ohne KI-Freigabe (ADR 0028). Fragt weder Quelle noch Modell; Fehler
     * bleiben hier.
     *
     * @return ob die Beschreibung jetzt im Index steht und vorher nicht
     */
    public boolean indexDescription(PageKey page, String spaceKey, DescribedImage described) {
        Objects.requireNonNull(page, "page");
        Asset asset = described == null ? null : described.asset();
        if (texts == null || asset == null || spaceKey == null || asset.attachmentId() == null
                || describer == null || !describer.available()
                || !page.sourceId().equals(asset.sourceId()) || !page.pageId().equals(asset.pageId())
                || !describer.id().equals(described.description().describer())
                || !describer.modelVersion().equals(described.description().modelVersion())) {
            return false;
        }
        try {
            if (texts.attachmentInputSha256(page, asset.attachmentId()).isEmpty()) {
                return false;
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Volltextindex eines Anhangs in {0} nicht lesbar ({1})",
                    page.sourceId(), e.getClass().getName());
            return false;
        }
        // ADR 0028: unmittelbar vor dem Schreiben geprüft und danach noch einmal. Fällt die Freigabe dazwischen weg,
        // hat die Rücknahme den Index womöglich schon bereinigt; dann nimmt dieser Aufruf seinen Text selbst zurück.
        if (!mayIndexDescription(page)) {
            return false;
        }
        boolean changed = indexText(page, spaceKey, asset, described.description().text(),
                describedOrigin(described.description()));
        if (changed && !mayIndexDescription(page)) {
            indexText(page, spaceKey, asset, "", "");
            flushIndex();
            return false;
        }
        if (changed) {
            flushIndex();
        }
        return changed;
    }

    private boolean mayIndexDescription(PageKey page) {
        return describer.mayDescribe(page.sourceId()) || pageReleases.test(page);
    }

    /** Die Beschreibung kommt wie der Text eines Dokuments in den Volltextindex (#183). */
    private DescribedImage indexed(PageAttachmentList list, DescribedImage described) {
        if (indexDescription(list, described.asset(), described.description())) {
            flushIndex();
        }
        return described;
    }

    private ImageDescription generate(ImageDescriber model, AssetContent content, String instruction) {
        try (LargeFileGate.Permit permit = gate.enter(content.asset().sourceId(), content.asset().size())) {
            return generateGated(model, content, instruction);
        }
    }

    /**
     * Belegt in der Spur des Threads einen Platz, solange dort weniger als {@code limit} Beschreibungen laufen.
     *
     * @return der belegte Zähler, oder {@code null}, wenn kein Platz frei ist
     */
    private java.util.concurrent.atomic.AtomicInteger enterDescription(int limit) {
        String lane = LANE.get();
        if (lane != null) {
            synchronized (lanes) {
                java.util.concurrent.atomic.AtomicInteger counter =
                        lanes.computeIfAbsent(lane, key -> new java.util.concurrent.atomic.AtomicInteger());
                if (counter.get() >= limit) {
                    return null;
                }
                counter.incrementAndGet();
                return counter;
            }
        }
        while (true) {
            int now = describingNow.get();
            if (now >= limit) {
                return null;
            }
            if (describingNow.compareAndSet(now, now + 1)) {
                return describingNow;
            }
        }
    }

    private void leaveDescription(java.util.concurrent.atomic.AtomicInteger counter) {
        try {
            if (counter == describingNow) {
                describingNow.decrementAndGet();
                return;
            }
            synchronized (lanes) {
                if (counter.decrementAndGet() == 0) {
                    // Leere Spuren fallen weg.
                    lanes.values().removeIf(value -> value == counter);
                }
            }
        } finally {
            synchronized (descriptionFreed) {
                descriptionFreed.notifyAll();
            }
        }
    }

    /** Meldet einen frei gewordenen Platz an Wartende von {@link #describeAttachment}. */
    private final Object descriptionFreed = new Object();

    /**
     * Ein Bild, das der Benutzer selbst mitgibt (Chat-Anhang): keine Quelle, nichts abgelegt. Es zählt zu denselben
     * gleichzeitigen Anfragen wie jede Bildbeschreibung ({@link ImageDescriber#parallel()}) und wartet auf einen freien
     * Platz, statt abzulehnen; der Benutzer hat es eben angehängt.
     *
     * @throws ImageDescriptionException {@code NOT_CONFIGURED} ohne Vision-Modell
     */
    public String describeAttachment(byte[] image, String mediaType, String instruction) {
        ImageDescriber model = describerInUse().orElseThrow(() -> new ImageDescriptionException(
                ImageDescriptionException.Reason.NOT_CONFIGURED, "Kein Vision-Modell eingestellt", null));
        java.util.concurrent.atomic.AtomicInteger slot;
        synchronized (descriptionFreed) {
            while ((slot = enterDescription(Math.max(1, model.parallel()))) == null) {
                try {
                    descriptionFreed.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ImageDescriptionException(ImageDescriptionException.Reason.FAILED,
                            "Warten auf das Vision-Modell abgebrochen", e);
                }
            }
        }
        try {
            return model.describe(image, mediaType, instruction);
        } finally {
            leaveDescription(slot);
        }
    }

    private ImageDescription generateGated(ImageDescriber model, AssetContent content, String instruction) {
        java.util.concurrent.atomic.AtomicInteger slot = enterDescription(Math.max(1, model.parallel()));
        if (slot == null) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.BUSY,
                    "Zu viele gleichzeitige Bildbeschreibungen", null);
        }
        String text;
        try {
            text = model.describe(content.content(), content.asset().mediaType(), instruction);
        } catch (ImageDescriptionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.FAILED, "Beschreibung fehlgeschlagen",
                    e);
        } finally {
            leaveDescription(slot);
        }
        if (text == null || text.isBlank()) {
            throw new ImageDescriptionException(ImageDescriptionException.Reason.FAILED, "Leere Beschreibung", null);
        }
        ImageDescription description = new ImageDescription(content.asset().sha256(),
                describerKey(model, instruction), model.modelVersion(), text.strip(), clock.instant());
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

    /**
     * Der Beschreiber für Bilder dieser Seite; Bilder, deren Quelle nicht freigegeben ist und deren Seite nicht auf der
     * Liste freigegebener Seiten steht (ADR 0028), gehen nie an das Modell.
     */
    private ImageDescriber describer(PageKey key) {
        ImageDescriber model = describer();
        if (!model.mayDescribe(key.sourceId()) && !pageReleases.test(key)) {
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
        Downloaded downloaded = download(source, pageId, attachment, keep, true);
        return new AssetContent(downloaded.asset(), downloaded.content());
    }

    /**
     * @param keep  ablegen, sonst nur liefern
     * @param bytes die Bytes auch über {@link #IN_MEMORY_BYTES} in den Speicher lesen, bis {@link
     *              #MAX_IN_MEMORY_BYTES}; darüber {@link AssetTooLargeException}, nachdem er abgelegt ist
     */
    private Downloaded download(HarvestSource source, String pageId, PageAttachment attachment, boolean keep,
                                boolean bytes) {
        if (bytes && !keep && attachment.fileSize() != null && attachment.fileSize() > maxInMemoryBytes) {
            // Weder abzulegen noch ganz im Speicher zu halten: der Download wäre umsonst.
            throw new AssetTooLargeException(source.id(), maxInMemoryBytes);
        }
        Path staging = staging(source.id(), attachment);
        try {
            return download(source, pageId, attachment, keep, bytes, staging);
        } finally {
            delete(source.id(), staging);
        }
    }

    /**
     * Wie {@link #download(HarvestSource, String, PageAttachment, boolean, boolean)}, aber in die Zwischendatei des
     * Aufrufers, der sie auch löscht.
     */
    private Downloaded download(HarvestSource source, String pageId, PageAttachment attachment, boolean keep,
                                boolean bytes, Path staging) {
        if (videoMark(attachment.attachmentId())) {
            // Eine Marke ohne Standbild: Es entsteht unter „Anhänge“, die Quelle kennt es nicht.
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.NOT_FOUND,
                    "Standbild der Marke noch nicht gezogen", null);
        }
        if (transcriptAttachment(attachment.attachmentId())) {
            // Das Transkript entsteht in der App; die Quelle kennt es nicht.
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.NOT_FOUND,
                    "Transkript nicht abgelegt", null);
        }
        // Große Dateien zusätzlich unter der gemeinsamen Schranke, bis Typerkennung, Hash und Ablegen fertig sind.
        try (LargeFileGate.Permit permit = gate.enter(source.id(), attachment.fileSize())) {
            return downloadGated(source, pageId, attachment, keep, bytes, staging);
        }
    }

    /** Eine leere Zwischendatei der Ablage für den Anhang; ist er laut Quelle zu groß, scheitert schon das. */
    private Path staging(String sourceId, PageAttachment attachment) {
        if (attachment.fileSize() != null && attachment.fileSize() > maxBytes) {
            throw new AssetTooLargeException(sourceId, maxBytes);
        }
        try {
            return store.stagingFile();
        } catch (IOException e) {
            throw new AssetStoreException("Asset nicht ablegbar", e);
        }
    }

    private static void delete(String sourceId, Path staging) {
        try {
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Zwischendatei nicht gelöscht: source={0} ({1})", sourceId,
                    e.getClass().getName());
        }
    }

    /**
     * Der Anhang fließt als Strom in eine Datei der Ablage ({@link AssetStore#stagingFile()}), nie ganz in den
     * Speicher; Hash, Größe und Anfang entstehen dabei. Bis {@link #IN_MEMORY_BYTES} oder auf Wunsch liegen die Bytes
     * danach auch im Speicher, für Typerkennung am ganzen Inhalt und Text wie bisher.
     */
    private Downloaded downloadGated(HarvestSource source, String pageId, PageAttachment attachment, boolean keep,
                                     boolean bytes, Path staging) {
        StreamedAttachment file;
        StagedAttachment sink;
        try {
            sink = new StagedAttachment(staging, maxBytes);
        } catch (IOException e) {
            throw new AssetStoreException("Asset nicht ablegbar", e);
        }
        try (sink) {
            // Höchstens wenige Downloads gleichzeitig, auch wenn keiner mehr die ganze Datei im Speicher hält.
            file = fromSource(source.id(), () -> {
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
                    // Nur der Transfer zählt, nicht das Warten auf einen Platz (Restzeit, Angelo 11.11.2026).
                    long started = System.nanoTime();
                    StreamedAttachment loaded = source.streamAttachment(attachment, sink);
                    durations.record(DownloadDurations.Kind.of(attachment.mediaType(), attachment.title()),
                            Duration.ofNanos(System.nanoTime() - started));
                    return loaded;
                } catch (AttachmentTooLargeException e) {
                    throw new AssetTooLargeException(source.id(), maxBytes);
                } catch (SourceAccessException e) {
                    if (sink.failure() != null) {
                        // Die eigene Platte, nicht die Quelle: kein Grund für Neuversuch oder Pause der Quelle.
                        throw new AssetStoreException("Asset nicht ablegbar", sink.failure());
                    }
                    throw e;
                } finally {
                    downloads.release();
                }
            });
        } catch (IOException e) {
            throw new AssetStoreException("Asset nicht ablegbar", e);
        }
        long size = sink.size();
        if (size > maxBytes) {
            throw new AssetTooLargeException(source.id(), maxBytes);
        }
        if (size == 0 && attachment.fileSize() != null && attachment.fileSize() > 0) {
            // Leere Antwort für eine Datei mit Größe: der Download ging ins Leere (etwa ein Pfad, den der Server anders
            // liest). Nicht als fertig ablegen, sonst gilt der Anhang überall als vorhanden und leer.
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.INVALID_RESPONSE,
                    "Anhang: leere Antwort, erwartet " + attachment.fileSize() + " Bytes", null);
        }
        byte[] content = null;
        if (size <= (bytes ? maxInMemoryBytes : Math.min(IN_MEMORY_BYTES, maxInMemoryBytes))) {
            try {
                content = Files.readAllBytes(staging);
            } catch (IOException e) {
                throw new AssetStoreException("Asset nicht ablegbar", e);
            }
        }
        PageAttachment described = file.attachment();
        String version = described.version() != null ? described.version() : attachment.version();
        String declaredType = described.mediaType() != null && !described.mediaType().isBlank()
                ? described.mediaType() : attachment.mediaType();
        String title = described.title().isEmpty() ? attachment.title() : described.title();
        // Über der Grenze der Textextraktion reicht ihr der Anfang für die Signatur, wie bisher bei großen Dateien.
        String mediaType = mediaType(source.id(), declaredType, size <= IN_MEMORY_BYTES ? content : sink.head(),
                title);
        // Hat eine Binärsignatur die Angabe der Quelle ersetzt, zählt die Dateiendung (.drawio, .svg) nicht mehr: Ein PDF
        // namens report.drawio ist ein Dokument, kein Diagramm.
        boolean corrected = !base(mediaType).equals(base(declaredType)) && !textual(base(mediaType));
        AssetKind kind = corrected ? AssetKind.of(mediaType, null) : AssetKind.of(mediaType, title);
        Asset asset = new Asset(source.id(), pageId, attachment.attachmentId(), version, title, mediaType, kind,
                size, sink.sha256(), file.retrievedAt(), attachment.partsWithoutFile());
        if (keep) {
            store.putFile(asset, staging);
        }
        if (bytes && content == null) {
            // Abgelegt ist er dann schon; ganz in den Speicher kommt er nicht, nur als Strom.
            throw new AssetTooLargeException(source.id(), maxInMemoryBytes);
        }
        return new Downloaded(asset, content);
    }

    /**
     * Ein heruntergeladener Anhang; die Bytes nur, wenn sie im Speicher liegen ({@link #downloadGated}), sonst
     * {@code null}. Bleibt in dieser Klasse.
     */
    private record Downloaded(Asset asset, byte[] content) {

        /** Die Bytes aus dem Speicher als Strom; ohne Platz an der Schranke, wie bisher. */
        AssetStream stream() {
            return new AssetStream(asset, new ByteArrayInputStream(content), null);
        }

        @Override
        public String toString() {
            return "Downloaded[asset=" + asset + ", bytes=" + (content == null ? "-" : content.length) + "]";
        }
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

    /** Zustand der Dateiabrufe einer Quelle (#571), siehe {@link #fromSource}. */
    private static final class SourceGate {
        final java.util.concurrent.locks.ReentrantLock probe = new java.util.concurrent.locks.ReentrantLock(true);
        /** Wann die Quelle zuletzt eine Datei ohne Einmalcode geliefert hat; {@code null} seit einer Code-Seite. */
        volatile java.time.Instant delivered;
        /** Die zuletzt gesehene Zwei-Faktor-Seite, solange keine Datei kam; {@code null} sonst. */
        volatile Seen seen;
    }

    /**
     * Eine Zwei-Faktor-Seite der Quelle: wann sie kam und wie viele Anforderungen der Quelle vor dem Abruf erledigt waren
     * ({@link SignInRequests#settlements}).
     */
    private record Seen(SignInChallenge challenge, java.time.Instant at, long settlements) {
    }

    /** So lange nach einer gelieferten Datei gilt die Sitzung als freigeschaltet: Abrufe laufen dann parallel. */
    private static final java.time.Duration FLOWING = java.time.Duration.ofMinutes(1);
    /**
     * So lange gilt eine gesehene Zwei-Faktor-Seite ohne Anforderung (etwa ohne Oberfläche); danach fragt ein Abruf die
     * Quelle wieder und bringt eine frische Seite mit frischem Code.
     */
    private static final java.time.Duration SEEN = java.time.Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, SourceGate> gates = new ConcurrentHashMap<>();

    /**
     * Der eine Weg aller geschützten Dateiabrufe (Bild, Anhang, Vorschau) an die Quelle (#571), damit es wie im
     * Enterprise AI Client je Quelle nur eine Zwei-Faktor-Sitzung, eine Mail und einen Code gibt:
     * <ul>
     *   <li>Solange die Anmeldung der Quelle offen ist ({@link SignInRequests#pendingChallenge}), geht kein Abruf ans
     *       Netz; geworfen wird, was die Quelle geworfen hätte, mit der Seite der offenen Anforderung.</li>
     *   <li>Hat ein Abruf die Zwei-Faktor-Seite bekommen, geht kein weiterer ans Netz, bis ein Code angenommen oder die
     *       Anforderung abgelehnt ist ({@link SignInRequests#settlements}); jeder bekommt dieselbe Seite. Auch dann, wenn
     *       noch niemand die Anforderung angelegt hat: die Lese-Ansicht versucht für ein draw.io-Bild zuerst die
     *       Quelldaten ohne Anforderung und gleich danach die Vorschau; jede neue Abfrage hätte eine neue Seite mit
     *       neuer Mail gebracht, und der Code der ersten passte nicht mehr (HTTP 500). Ohne Anforderung gilt die Seite
     *       {@code SEEN} lang; ohne {@link SignInRequests} (nichts kann freischalten) gar nicht.</li>
     *   <li>Ohne frisch gelieferte Datei (seit {@code FLOWING}) läuft je Quelle immer nur ein Abruf, auch wenn eine
     *       freigeschaltete Sitzung inzwischen abgelaufen sein kann; erst nach einer gelieferten Datei wieder parallel,
     *       auch die schon wartenden. Länger als {@code DOWNLOAD_WAIT} wartet niemand auf den Abruf vor ihm
     *       ({@code RATE_LIMITED}).</li>
     * </ul>
     * Lesen auf Abruf schließt sich damit derselben Anforderung an ({@link #unlocking}), ein Auftrag wartet auf sie
     * ({@link SignInRequests#await}). Abgelegte Dateien kommen weiter aus der Ablage. Code und Token sieht diese Stelle
     * nie.
     */
    private <T> T fromSource(String sourceId, java.util.function.Supplier<T> call) {
        SourceGate state = gates.computeIfAbsent(sourceId, id -> new SourceGate());
        if (flowing(state)) {
            holdWhileSignInOpen(sourceId);
            return observed(sourceId, state, call);
        }
        try {
            if (!state.probe.tryLock(DOWNLOAD_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new SourceAccessException(sourceId, SourceAccessException.Reason.RATE_LIMITED,
                        "Die Quelle antwortet noch auf einen anderen Download", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE, "Download abgebrochen",
                    e);
        }
        boolean locked = true;
        try {
            holdWhileSignInOpen(sourceId);
            Seen seen = state.seen;
            if (seen != null && signIns != null && clock.instant().isBefore(seen.at().plus(SEEN))
                    && settlements(sourceId) == seen.settlements()) {
                throw new SignInRequiredException(sourceId, "Die Quelle verlangt einen Einmalcode", seen.challenge(),
                        null);
            }
            if (flowing(state)) {
                // Der vorige Abruf hat eine Datei geliefert: wieder parallel, wer noch wartet, kommt sofort dran.
                state.probe.unlock();
                locked = false;
            }
            return observed(sourceId, state, call);
        } finally {
            if (locked) {
                state.probe.unlock();
            }
        }
    }

    /** Ob die Quelle seit dem Start und seit der letzten Zwei-Faktor-Seite eine Datei ohne Einmalcode geliefert hat. */
    private boolean deliveredSinceTwoFactorPage(String sourceId) {
        SourceGate state = gates.get(sourceId);
        return state != null && state.delivered != null;
    }

    private boolean flowing(SourceGate state) {
        java.time.Instant delivered = state.delivered;
        return delivered != null && clock.instant().isBefore(delivered.plus(FLOWING));
    }

    private long settlements(String sourceId) {
        return signIns == null ? 0 : signIns.settlements(sourceId);
    }

    private <T> T observed(String sourceId, SourceGate state, java.util.function.Supplier<T> call) {
        // Vor dem Abruf gezählt: wird währenddessen ein Code angenommen, gilt die Seite, die der Abruf mitbringt, nicht.
        long settledBefore = settlements(sourceId);
        try {
            T result = call.get();
            state.seen = null;
            state.delivered = clock.instant();
            return result;
        } catch (SignInRequiredException e) {
            state.delivered = null;
            state.seen = e.challenge() == null ? null : new Seen(e.challenge(), clock.instant(), settledBefore);
            throw e;
        }
    }

    /** Wirft statt eines Abrufs, solange die Anmeldung der Quelle offen ist ({@link #fromSource}). */
    private void holdWhileSignInOpen(String sourceId) {
        if (signIns == null) {
            return;
        }
        Optional<SignInChallenge> open = signIns.pendingChallenge(sourceId);
        if (open.isPresent()) {
            throw new SignInRequiredException(sourceId, "Die Anmeldung der Quelle ist offen (Einmalcode)", open.get(),
                    null);
        }
    }

    private HarvestSource source(String sourceId) {
        return catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
    }

    /**
     * Ein Anhang als Strom; {@link #close()} schließt den Strom und gibt einen belegten Platz an der Schranke frei,
     * mehrfaches Schließen ist harmlos.
     */
    public static final class AssetStream implements Closeable {

        private final Asset asset;
        private final InputStream content;
        private final LargeFileGate.Permit permit;

        AssetStream(Asset asset, InputStream content, LargeFileGate.Permit permit) {
            this.asset = asset;
            this.content = content;
            this.permit = permit;
        }

        public Asset asset() {
            return asset;
        }

        public InputStream content() {
            return content;
        }

        @Override
        public void close() throws IOException {
            try {
                content.close();
            } finally {
                if (permit != null) {
                    permit.close();
                }
            }
        }
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
