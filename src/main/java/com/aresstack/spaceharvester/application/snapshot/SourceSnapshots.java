package com.aresstack.spaceharvester.application.snapshot;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.PageContentFetcher;
import java.util.Objects;

/**
 * Lädt den nativen Snapshot einer Seite live aus ihrer Quelle: Payload und Kopfdaten aus derselben Antwort, Hashes
 * wie in der Metadatenliste. Speichert nichts; das entscheidet die Synchronisierung, für die dies der
 * {@link PageContentFetcher} ist.
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
        StoredPage page = source.storedPage(pageId);
        if (!page.metadata().pageId().equals(pageId)) {
            throw new SourceAccessException(source.id(), SourceAccessException.Reason.INVALID_RESPONSE,
                    "Antwort der Quelle gehört zu einer anderen Seite", null);
        }
        return PageSnapshot.of(source.id(), page.metadata(),
                new NativePayload(page.payload(), page.mediaType(), page.nativeFormat()), page.retrievedAt());
    }
}
