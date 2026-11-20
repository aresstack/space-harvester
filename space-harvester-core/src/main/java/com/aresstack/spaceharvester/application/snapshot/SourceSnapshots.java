package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.PageContentFetcher;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Lädt den nativen Snapshot einer Seite live aus ihrer Quelle: Payload und Kopfdaten aus derselben Antwort, Hashes
 * wie in der Metadatenliste. Speichert nichts; das entscheidet die Synchronisierung, für die dies der
 * {@link PageContentFetcher} ist. Hat die Quelle einen asynchronen HTTP-Client
 * ({@link HarvestSource#asyncStoredPages()}, #47), läuft der Abruf darüber; {@link #fetchAsync} belegt dann keinen
 * Thread bis zur Antwort.
 */
public class SourceSnapshots implements PageContentFetcher {

    private static final System.Logger LOG = System.getLogger(SourceSnapshots.class.getName());
    private static final Runnable NOTHING = () -> { };

    private final SourceCatalog catalog;
    private volatile PageLoads loads = PageLoads.NONE;
    /** Größte Datei, die ganz geladen wird ({@link LargeFiles}); {@code <= 0} ohne Grenze. */
    private volatile java.util.function.LongSupplier maxBytes = () -> 0;
    /** Größte Datei, deren Textauszug für eine zu große Datei geladen wird; {@code <= 0} keiner. */
    private volatile java.util.function.LongSupplier maxTextBytes = () -> 0;
    /** Textauszug einer zu großen Datei aus ihrem Original ({@code LargeFileTexts}); ohne Aufruf keiner. */
    private volatile LargeFileText texts = (source, metadata, maxBytes) -> java.util.Optional.empty();

    /** Der Textauszug einer zu großen Datei, ohne ihr Original im Speicher zu halten. */
    @FunctionalInterface
    public interface LargeFileText {

        /** @return der Text; leer ohne lesbaren Text */
        java.util.Optional<String> text(HarvestSource source, PageMetadata metadata, long maxBytes);
    }

    /** Woher der Textauszug zu großer Dateien kommt. */
    public void largeFileText(LargeFileText texts) {
        this.texts = Objects.requireNonNull(texts, "texts");
    }

    public SourceSnapshots(SourceCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Dateien über dieser Größe lädt {@link #fetch(String, PageMetadata)} nicht herunter, sondern liefert nur ihre
     * Kopfdaten ({@link LargeFiles}); gelesen bei jedem Abruf, {@code <= 0} ohne Grenze.
     */
    public void maxBytes(java.util.function.LongSupplier maxBytes) {
        this.maxBytes = Objects.requireNonNull(maxBytes, "maxBytes");
    }

    /**
     * Für Dateien über {@link #maxBytes}, aber nicht über dieser Größe holt {@link #fetch(String, PageMetadata)} den
     * Textauszug der Quelle statt des Originals; gelesen bei jedem Abruf, {@code <= 0} nie.
     */
    public void maxTextBytes(java.util.function.LongSupplier maxTextBytes) {
        this.maxTextBytes = Objects.requireNonNull(maxTextBytes, "maxTextBytes");
    }

    /**
     * Wie {@link #fetch(String, String)}; eine Datei über {@link #maxBytes} lädt es nicht als Original, sondern als
     * Textauszug, wenn sie lesbaren Text hat und nicht größer ist als {@link #maxTextBytes}, sonst gar nicht
     * ({@link LargeFiles}).
     */
    @Override
    public PageSnapshot fetch(String sourceId, PageMetadata expected) {
        if (loadsWhole(expected)) {
            return fetch(sourceId, expected.pageId());
        }
        HarvestSource source = catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
        long textLimit = maxTextBytes.getAsLong();
        if (textLimit > 0 && LargeFiles.size(expected) <= textLimit) {
            Runnable ended = started(new PageKey(sourceId, expected.pageId()));
            try {
                java.util.Optional<String> text = texts.text(source, expected, textLimit);
                if (text.isPresent()) {
                    return LargeFiles.textOnly(sourceId, expected,
                            text.get().getBytes(java.nio.charset.StandardCharsets.UTF_8), java.time.Instant.now());
                }
            } finally {
                quietly(ended);
            }
        }
        return LargeFiles.placeholder(sourceId, expected, java.time.Instant.now());
    }

    @Override
    public boolean loadsWhole(PageMetadata metadata) {
        return !LargeFiles.tooLarge(metadata, maxBytes.getAsLong());
    }

    @Override
    public boolean triesText(PageMetadata metadata) {
        long textLimit = maxTextBytes.getAsLong();
        return !loadsWhole(metadata) && textLimit > 0 && LargeFiles.size(metadata) <= textLimit;
    }

    /** Meldet Beginn und Ende jedes Abrufs (ADR 0027); ohne Aufruf {@link PageLoads#NONE}. */
    public void loads(PageLoads loads) {
        this.loads = Objects.requireNonNull(loads, "loads");
    }

    /**
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SourceAccessException  wie {@link HarvestSource#storedPage(String)}; außerdem {@code INVALID_RESPONSE},
     *                                wenn die Antwort zu einer anderen Seite gehört
     */
    @Override
    public PageSnapshot fetch(String sourceId, String pageId) {
        HarvestSource source = catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
        Runnable ended = started(new PageKey(sourceId, pageId));
        try {
            return fetch(source, pageId);
        } finally {
            quietly(ended);
        }
    }

    private PageSnapshot fetch(HarvestSource source, String pageId) {
        if (!source.asyncStoredPages()) {
            return snapshot(source, pageId, source.storedPage(pageId));
        }
        CompletableFuture<PageSnapshot> pending = fetchAsync(source, pageId).toCompletableFuture();
        try {
            // Wartet der Aufrufer (Sync-Lauf, Job), nicht ein I/O-Thread: der Abruf selbst blockiert nicht. Unterbrechbar
            // warten, damit ein beendeter Auftrag (Harvester.close, shutdownNow) nicht an einer Antwort hängt, die nie
            // kommt.
            return pending.get();
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.UNAVAILABLE,
                    "Abruf der Seite unterbrochen", null);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IllegalStateException("Abruf der Seite fehlgeschlagen", e.getCause());
        }
    }

    /**
     * Wie {@link #fetch}, ohne den Aufrufer-Thread bis zur Antwort zu belegen, wenn die Quelle das kann; sonst endet
     * die Stufe sofort mit {@code UNSUPPORTED} (dann {@link #fetch} benutzen).
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     */
    public CompletionStage<PageSnapshot> fetchAsync(String sourceId, String pageId) {
        HarvestSource source = catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
        return fetchAsync(source, pageId);
    }

    private CompletionStage<PageSnapshot> fetchAsync(HarvestSource source, String pageId) {
        CompletableFuture<StoredPage> request = source.storedPageAsync(pageId).toCompletableFuture();
        CompletableFuture<PageSnapshot> result = request.thenApply(page -> snapshot(source, pageId, page));
        result.whenComplete((snapshot, error) -> {
            if (result.isCancelled()) {
                request.cancel(true);
            }
        });
        return result;
    }

    /** Ein Fehler des Beobachters ändert nichts am Abruf; protokolliert ohne Seiten-ID. */
    private Runnable started(PageKey key) {
        try {
            Runnable ended = loads.started(key);
            return ended == null ? NOTHING : ended;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Beobachter der Abrufe von Quelle {0} fehlgeschlagen ({1})",
                    key.sourceId(), e.getClass().getSimpleName());
            return NOTHING;
        }
    }

    private static void quietly(Runnable ended) {
        try {
            ended.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Beobachter der Abrufe fehlgeschlagen ({0})",
                    e.getClass().getSimpleName());
        }
    }

    private static PageSnapshot snapshot(HarvestSource source, String pageId, StoredPage page) {
        if (!page.metadata().pageId().equals(pageId)) {
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.INVALID_RESPONSE,
                    "Antwort der Quelle gehört zu einer anderen Seite", null);
        }
        return PageSnapshot.of(source.id(), page.metadata(),
                new NativePayload(page.payload(), page.mediaType(), page.nativeFormat()), page.retrievedAt());
    }
}
