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

    /** Read a page-ID ordered batch of source headers from the local cache only. */
    default List<com.aresstack.spaceharvester.application.snapshot.PageHeader> sourceHeaders(
            String sourceId, String afterPageId, int limit) {
        throw new UnsupportedOperationException("Source header enumeration is not supported");
    }

    /**
     * Eine Seite mit aktuellen Kopfdaten und nativer Payload, unabhängig vom Space (eine verschobene Seite behält
     * ihre {@code pageId}).
     *
     * @return leer, wenn die Seite nicht im Cache liegt oder noch keine Payload hat
     */
    Optional<CachedPage> page(PageKey key);

    /**
     * Read the latest page header without requiring a downloaded body.
     * This lets navigation restore parent paths from previously cataloged metadata.
     */
    default Optional<com.aresstack.spaceharvester.application.snapshot.PageHeader> header(PageKey key) {
        return Optional.empty();
    }

    /**
     * Direkte Kinder nach den aktuellen Kopfdaten im Cache, ohne Payload: mit {@code parentPageId} die Kinder dieser
     * Seite, sonst die Wurzelseiten des Space. Für den Baum, der zuerst den gecachten Stand zeigt und die Quelle
     * danach im Hintergrund fragt.
     *
     * @return höchstens {@code limit} Kopfdaten in beliebiger Reihenfolge; leer, wenn der Cache keine kennt
     */
    default List<com.aresstack.spaceharvester.application.snapshot.PageHeader> children(String sourceId,
            String spaceKey, String parentPageId, int limit) {
        return List.of();
    }

    /** Ob der Cache Kinder dieser Seite kennt. */
    default boolean hasChildren(String sourceId, String pageId) {
        return false;
    }

    /**
     * Seiten-IDs der Kinder in der Reihenfolge, in der die Quelle sie zuletzt lieferte; leer, wenn unbekannt.
     *
     * @param parentPageId Elternseite oder {@code null} für die Wurzelseiten von {@code spaceKey}
     */
    default List<String> childOrder(String sourceId, String spaceKey, String parentPageId) {
        return List.of();
    }

    /**
     * Merkt sich die Reihenfolge der Kinder, wie sie die Quelle gerade geliefert hat, und ersetzt die vorige. Der
     * einzige schreibende Zugriff dieses Ports: nur ein Hinweis für die Anzeige, kein Seitenstand.
     */
    default void rememberChildOrder(String sourceId, String spaceKey, String parentPageId,
                                    List<String> pageIds) {
    }

    /** Read the order and its revision together; return empty if revisioned writes are unsupported. */
    default Optional<ChildOrderState> childOrderState(String sourceId, String spaceKey, String parentPageId) {
        return Optional.empty();
    }

    /** Replace an order only if its revision still matches; return the new revision on success. */
    default Optional<String> compareAndSetChildOrder(String sourceId, String spaceKey, String parentPageId,
                                                     String expectedRevision, List<String> pageIds) {
        return Optional.empty();
    }

    /** Treat a missing revision as a legacy or absent order. */
    final class ChildOrderState {
        private final String revision;
        private final List<String> pageIds;

        public ChildOrderState(String revision, List<String> pageIds) {
            this.revision = revision;
            this.pageIds = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(pageIds));
        }

        public String revision() { return revision; }
        public List<String> pageIds() { return pageIds; }
    }

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
