package com.aresstack.spaceharvester.application.snapshot;

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

    private final SourceCatalog catalog;

    public SourceSnapshots(SourceCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SourceAccessException  wie {@link HarvestSource#storedPage(String)}; außerdem {@code INVALID_RESPONSE},
     *                                wenn die Antwort zu einer anderen Seite gehört
     */
    @Override
    public PageSnapshot fetch(String sourceId, String pageId) {
        HarvestSource source = catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
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

    private static PageSnapshot snapshot(HarvestSource source, String pageId, StoredPage page) {
        if (!page.metadata().pageId().equals(pageId)) {
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.INVALID_RESPONSE,
                    "Antwort der Quelle gehört zu einer anderen Seite", null);
        }
        return PageSnapshot.of(source.id(), page.metadata(),
                new NativePayload(page.payload(), page.mediaType(), page.nativeFormat()), page.retrievedAt());
    }
}
