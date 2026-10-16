package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Anwendungsfälle der cachegestützten Query-API (#27): flache Seitenliste, ID-Baum und native Einzelseite.
 *
 * <p>Liste und Baum lesen dieselben Einträge über {@link PageCacheReader#headers}, daher enthalten sie für dieselbe
 * Generation dieselben Seiten. Jede Seite passiert {@link PageVisibility}, bevor sie in irgendeiner Form ausgegeben
 * wird.
 */
public class PageQueries {

    /** Größte Stapelgröße der Seitenliste je Anfrage; zugleich der Standardwert. */
    public static final int MAX_PAGE_LIMIT = 100;

    /**
     * Größte Baumtiefe einer synchronen Antwort. Jede Ebene kostet im JSON zwei Verschachtelungen (Knoten und
     * {@code children}); darüber würde die übliche Grenze von 500 Ebenen (etwa Jacksons Standard beim Schreiben und
     * Lesen) gesprengt. Solche Bäume liefert der Export.
     */
    public static final int MAX_TREE_DEPTH = 200;

    private static final int TREE_BATCH = 1000;

    private final SourceCatalog catalog;
    private final PageCacheReader cache;
    private final PageVisibility visibility;
    private final int maxTreeNodes;

    /**
     * @param maxTreeNodes größte Zahl sichtbarer Seiten, deren Baum noch synchron ausgeliefert wird
     */
    public PageQueries(SourceCatalog catalog, PageCacheReader cache, PageVisibility visibility, int maxTreeNodes) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        if (maxTreeNodes < 1) {
            throw new IllegalArgumentException("maxTreeNodes muss positiv sein");
        }
        this.maxTreeNodes = maxTreeNodes;
    }

    /**
     * Ein Stapel der flachen Seitenliste eines Space.
     *
     * @param cursor {@code null} oder leer für den ersten Stapel
     * @param limit  1 bis {@value #MAX_PAGE_LIMIT}
     * @throws InvalidQueryException           bei ungültigem Limit oder Cursor
     * @throws UnknownSourceException          wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws CacheMissException              wenn für den Space nichts im Cache liegt
     * @throws CacheGenerationChangedException wenn der Cursor zu einer nicht mehr lesbaren Generation gehört
     */
    public PageListing pages(String sourceId, String spaceKey, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_PAGE_LIMIT + " liegen");
        }
        requireSource(sourceId);
        PageCursor position = cursor == null || cursor.isEmpty() ? null
                : PageCursor.decode(cursor, sourceId, spaceKey);
        CacheGeneration generation = generation(sourceId, spaceKey);
        if (position != null && !position.generationId().equals(generation.id())) {
            throw new CacheGenerationChangedException("Der Cursor gehört zu einer früheren Cache-Generation");
        }

        // So lange lesen, bis das Limit mit sichtbaren Seiten gefüllt oder das Ende erreicht ist. Der nächste Cursor
        // zeigt hinter die letzte ausgelieferte Seite, damit er keine unsichtbare pageId preisgibt.
        List<PageHeader> visible = new ArrayList<>();
        String after = position == null ? null : position.afterPageId();
        boolean exhausted = false;
        while (visible.size() < limit && !exhausted) {
            List<PageHeader> batch = cache.headers(sourceId, spaceKey, generation.id(), after, limit);
            int consumed = 0;
            for (PageHeader header : batch) {
                consumed++;
                after = header.pageId();
                if (visibility.canRead(header)) {
                    visible.add(header);
                    if (visible.size() == limit) {
                        break;
                    }
                }
            }
            exhausted = batch.size() < limit && consumed == batch.size();
        }
        String next = exhausted ? null
                : new PageCursor(sourceId, spaceKey, generation.id(), visible.get(visible.size() - 1).pageId())
                        .encode();
        return new PageListing(sourceId, spaceKey, generation, visible, next, exhausted);
    }

    /**
     * Der ID-Baum eines Space aus sichtbaren Seiten.
     *
     * @throws UnknownSourceException          wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws CacheMissException              wenn für den Space nichts im Cache liegt
     * @throws TreeTooLargeException           wenn der Baum für eine synchrone Antwort zu groß oder zu tief ist
     * @throws CacheGenerationChangedException wenn die Generation während des Lesens ersetzt wurde
     */
    public SpaceTree tree(String sourceId, String spaceKey) {
        requireSource(sourceId);
        CacheGeneration generation = generation(sourceId, spaceKey);
        Map<String, PageHeader> headers = new HashMap<>();
        List<TreeEntry> entries = new ArrayList<>();
        String after = null;
        List<PageHeader> batch;
        do {
            batch = cache.headers(sourceId, spaceKey, generation.id(), after, TREE_BATCH);
            for (PageHeader header : batch) {
                after = header.pageId();
                if (visibility.canRead(header)) {
                    if (entries.size() == maxTreeNodes) {
                        throw new TreeTooLargeException("Der Baum hat mehr als " + maxTreeNodes + " Seiten");
                    }
                    headers.put(header.pageId(), header);
                    entries.add(new TreeEntry(header.pageId(), header.parentPageId()));
                }
            }
        } while (batch.size() == TREE_BATCH);
        PageTree tree = PageTreeBuilder.build(entries);
        if (tree.depth() > MAX_TREE_DEPTH) {
            throw new TreeTooLargeException("Der Baum ist tiefer als " + MAX_TREE_DEPTH + " Ebenen");
        }
        return new SpaceTree(sourceId, spaceKey, generation, tree, headers);
    }

    /**
     * Eine Seite mit ihrer nativen Payload aus dem Cache.
     *
     * @param refresh {@code true} verlangt eine Prüfung gegen die Quelle vor der Auslieferung
     * @throws UnknownSourceException       wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws RefreshUnavailableException  bei {@code refresh}, solange keine Prüfung gegen die Quelle angebunden ist;
     *                                      dann nie stattdessen der Cache-Stand
     * @throws CacheMissException           wenn die Seite nicht im Cache liegt oder nicht sichtbar ist
     */
    public CachedPage page(String sourceId, String pageId, boolean refresh) {
        requireSource(sourceId);
        if (refresh) {
            // Kommt mit der Aktualitätsprüfung aus #16 und dem nativen Abruf aus #8.
            throw new RefreshUnavailableException("Die Prüfung gegen die Quelle ist noch nicht angebunden");
        }
        return cache.page(new PageKey(sourceId, pageId))
                .filter(page -> visibility.canRead(PageHeader.of(page.snapshot())))
                .orElseThrow(() -> new CacheMissException(sourceId, "Seite nicht im Cache"));
    }

    private CacheGeneration generation(String sourceId, String spaceKey) {
        return cache.currentGeneration(sourceId, spaceKey)
                .orElseThrow(() -> new CacheMissException(sourceId, "Space nicht im Cache"));
    }

    private void requireSource(String sourceId) {
        if (catalog.find(sourceId).isEmpty()) {
            throw new UnknownSourceException(sourceId);
        }
    }
}
