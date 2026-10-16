package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Anwendungsfälle der cachegestützten Query-API (#27): flache Seitenliste, ID-Baum und native Einzelseite.
 *
 * <p>Liste und Baum lesen dieselben Einträge über {@link PageCacheReader#pages}, daher enthalten sie für dieselbe
 * Generation dieselben Seiten. Rechte prüft der {@link AccessGuard} (#14), bevor etwas ausgegeben wird: Liste und Baum
 * verlangen {@link AccessKind#METADATA} für den Space und filtern zusätzlich je Seite an ihren aktuellen Kopfdaten; die
 * native Einzelseite verlangt {@link AccessKind#CONTENT}. Eine Seite ohne Freigabe ist nach außen nicht von einer
 * fehlenden zu unterscheiden.
 *
 * <p>{@code refresh=true} prüft die Einzelseite über {@link PageFreshness} gegen die Quelle und liefert nie
 * stattdessen den Cache-Stand; die Prüfung schreibt nicht in den Cache.
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
    private final AccessGuard access;
    private final PageFreshness freshness;
    private final int maxTreeNodes;

    /**
     * @param freshness    Prüfung gegen die Quelle für {@code refresh=true}
     * @param maxTreeNodes größte Zahl sichtbarer Seiten, deren Baum noch synchron ausgeliefert wird
     */
    public PageQueries(SourceCatalog catalog, PageCacheReader cache, AccessGuard access, PageFreshness freshness,
                       int maxTreeNodes) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.access = Objects.requireNonNull(access, "access");
        this.freshness = Objects.requireNonNull(freshness, "freshness");
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
     * @param caller Aufrufer aus der geprüften Anmeldung
     * @throws AccessDeniedException           wenn der Space nicht für {@code metadata} freigegeben ist
     * @throws InvalidQueryException           bei ungültigem Limit oder Cursor
     * @throws UnknownSourceException          wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws CacheMissException              wenn für den Space nichts im Cache liegt
     * @throws CacheGenerationChangedException wenn der Cursor zu einer nicht mehr lesbaren Generation gehört
     */
    public PageListing pages(AccessContext caller, String sourceId, String spaceKey, String cursor, int limit) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
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
        List<ListedPage> visible = new ArrayList<>();
        String after = position == null ? null : position.afterPageId();
        boolean exhausted = false;
        while (visible.size() < limit && !exhausted) {
            List<ListedPage> batch = cache.pages(sourceId, spaceKey, generation.id(), after, limit);
            int consumed = 0;
            for (ListedPage page : batch) {
                consumed++;
                after = page.pageId();
                if (visible(caller, page)) {
                    visible.add(page);
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
     * @param caller Aufrufer aus der geprüften Anmeldung
     * @throws AccessDeniedException           wenn der Space nicht für {@code metadata} freigegeben ist
     * @throws UnknownSourceException          wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws CacheMissException              wenn für den Space nichts im Cache liegt
     * @throws TreeTooLargeException           wenn der Baum für eine synchrone Antwort zu groß oder zu tief ist
     * @throws CacheGenerationChangedException wenn die Generation während des Lesens ersetzt wurde
     */
    public SpaceTree tree(AccessContext caller, String sourceId, String spaceKey) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
        requireSource(sourceId);
        CacheGeneration generation = generation(sourceId, spaceKey);
        Map<String, ListedPage> pages = new HashMap<>();
        List<TreeEntry> entries = new ArrayList<>();
        String after = null;
        List<ListedPage> batch;
        do {
            batch = cache.pages(sourceId, spaceKey, generation.id(), after, TREE_BATCH);
            for (ListedPage page : batch) {
                after = page.pageId();
                if (visible(caller, page)) {
                    if (entries.size() == maxTreeNodes) {
                        throw new TreeTooLargeException("Der Baum hat mehr als " + maxTreeNodes + " Seiten");
                    }
                    pages.put(page.pageId(), page);
                    entries.add(new TreeEntry(page.pageId(), page.parentPageId()));
                }
            }
        } while (batch.size() == TREE_BATCH);
        PageTree tree = PageTreeBuilder.build(entries);
        if (tree.depth() > MAX_TREE_DEPTH) {
            throw new TreeTooLargeException("Der Baum ist tiefer als " + MAX_TREE_DEPTH + " Ebenen");
        }
        return new SpaceTree(sourceId, spaceKey, generation, tree, pages);
    }

    /**
     * Eine Seite mit ihrer nativen Payload, aus dem Cache oder nach Prüfung gegen die Quelle.
     *
     * @param caller  Aufrufer aus der geprüften Anmeldung
     * @param refresh {@code true} verlangt eine Prüfung gegen die Quelle vor der Auslieferung; nie stattdessen der
     *                Cache-Stand
     * @throws AccessDeniedException       wenn die Quelle für keinen Space für {@code content} freigegeben ist
     * @throws UnknownSourceException      wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws RefreshUnavailableException bei {@code refresh}, solange keine Prüfung gegen die Quelle angebunden ist
     * @throws SourceAccessException       bei {@code refresh}, wenn die Quelle nicht oder nicht in der Frist antwortet
     * @throws CacheMissException          wenn die Seite nicht im Cache liegt bzw. die Quelle sie nicht (mehr) kennt,
     *                                     oder ihr Space nicht für {@code content} freigegeben ist
     */
    public DeliveredPage page(AccessContext caller, String sourceId, String pageId, boolean refresh) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        requireSource(sourceId);
        if (!refresh) {
            return cache.page(new PageKey(sourceId, pageId))
                    .filter(page -> readable(caller, page.header()))
                    .map(DeliveredPage::cached)
                    .orElseThrow(() -> new CacheMissException(sourceId, "Seite nicht im Cache"));
        }
        if (!freshness.available()) {
            throw new RefreshUnavailableException("Die Prüfung gegen die Quelle ist noch nicht angebunden");
        }
        // Ohne lesbaren Cache prüft refresh trotzdem live; die Antwort ist dann als live gekennzeichnet.
        Optional<CachedPage> cached;
        try {
            cached = cache.page(new PageKey(sourceId, pageId));
        } catch (CacheUnavailableException e) {
            cached = Optional.empty();
        }
        PageFreshness.Refreshed checked;
        try {
            checked = freshness.check(sourceId, pageId, cached.map(CachedPage::header),
                    cached.map(CachedPage::snapshot));
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.NOT_FOUND) {
                // Etwa beim Inhaltsabruf: wie GONE, damit der Fehlertyp nichts über die Seite verrät.
                throw new CacheMissException(sourceId, "Seite nicht vorhanden");
            }
            throw e;
        }
        // Rechte am Ergebnis der Quelle: eine verschobene Seite zählt in ihrem neuen Space. Nicht mehr vorhanden und
        // nicht freigegeben sehen gleich aus.
        if (checked.outcome() == PageFreshness.Outcome.GONE || !readable(caller, checked.header())) {
            throw new CacheMissException(sourceId, "Seite nicht vorhanden");
        }
        return new DeliveredPage(checked.header(), checked.snapshot(),
                cached.map(CachedPage::generation).orElse(null), checked.cacheStale(), checked.outcome(),
                checked.checkedAt());
    }

    private boolean readable(AccessContext caller, PageHeader header) {
        return access.canRead(caller, header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId());
    }

    /** Freigabe je Seite an ihren aktuellen Kopfdaten, auch wenn der Space schon geprüft ist (#24: je Seite). */
    private boolean visible(AccessContext caller, ListedPage page) {
        return access.permits(caller, ResourceScope.page(page.header().key().sourceId(),
                page.header().metadata().partitionKey(), page.pageId()), AccessKind.METADATA);
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
