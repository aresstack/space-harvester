package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import java.util.List;
import java.util.Optional;

/**
 * Lesender Port auf den Seitencache, den die Query-API (#27) braucht: Abfragen je Space und Generation, die
 * {@link SnapshotRepository} bewusst nicht hat. Arbeitet mit den Typen des Snapshot-Vertrags; umgesetzt vom
 * Lucene-Cache (#26) neben dem {@link SnapshotRepository}, in Tests von Fakes. Keine Lucene-, Spring- oder
 * Confluence-Typen.
 *
 * <p>Alle Methoden dürfen {@link CacheUnavailableException} werfen, wenn der Cache selbst nicht lesbar ist.
 */
public interface PageCacheReader {

    /**
     * Die Generation, die Abfragen auf diesen Space jetzt lesen sollen.
     *
     * @return leer, wenn für den Space nichts im Cache liegt
     */
    Optional<CacheGeneration> currentGeneration(String sourceId, String spaceKey);

    /**
     * Seiten eines Space aus genau einer Generation, aufsteigend nach {@code pageId} ({@link String#compareTo}), mit
     * ihren aktuellen Kopfdaten ({@link SnapshotRepository#findHeader}), nicht denen des Snapshots.
     *
     * @param afterPageId nur Seiten mit größerer {@code pageId}; {@code null} ab der ersten
     * @param limit       höchstens so viele; weniger nur, wenn keine weiteren folgen
     * @throws CacheGenerationChangedException wenn die Generation nicht mehr lesbar ist
     */
    List<ListedPage> pages(String sourceId, String spaceKey, String generationId, String afterPageId, int limit);

    /**
     * Eine Seite mit aktuellen Kopfdaten und nativer Payload, unabhängig vom Space (eine verschobene Seite behält
     * ihre {@code pageId}).
     *
     * @return leer, wenn die Seite nicht im Cache liegt oder noch keine Payload hat
     */
    Optional<CachedPage> page(PageKey key);

    /** Ein Cache, der nicht eingerichtet ist: jede Abfrage scheitert mit {@link CacheUnavailableException}. */
    static PageCacheReader unavailable() {
        return new PageCacheReader() {
            @Override
            public Optional<CacheGeneration> currentGeneration(String sourceId, String spaceKey) {
                throw new CacheUnavailableException("Kein Seitencache eingerichtet");
            }

            @Override
            public List<ListedPage> pages(String sourceId, String spaceKey, String generationId,
                                             String afterPageId, int limit) {
                throw new CacheUnavailableException("Kein Seitencache eingerichtet");
            }

            @Override
            public Optional<CachedPage> page(PageKey key) {
                throw new CacheUnavailableException("Kein Seitencache eingerichtet");
            }
        };
    }
}
