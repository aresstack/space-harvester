package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.net.URI;
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
 * <p>Die Einzelseite ist transparent gecacht (ADR 0010): ein Treffer kommt aus dem Cache, eine fehlende Seite wird
 * über {@link PageFreshness} nachgeladen und über {@link PageCacheFill} abgelegt. {@code refresh=true} prüft gegen die
 * Quelle und liefert nie stattdessen den Cache-Stand; ein neuerer Inhalt wird dabei ebenfalls abgelegt.
 */
public class PageQueries {

    private static final System.Logger LOG = System.getLogger(PageQueries.class.getName());

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
    private final PageCacheFill fill;
    private final int maxTreeNodes;

    /**
     * @param freshness    Prüfung gegen die Quelle für {@code refresh=true}
     * @param maxTreeNodes größte Zahl sichtbarer Seiten, deren Baum noch synchron ausgeliefert wird
     */
    public PageQueries(SourceCatalog catalog, PageCacheReader cache, AccessGuard access, PageFreshness freshness,
                       int maxTreeNodes) {
        this(catalog, cache, access, freshness, PageCacheFill.NONE, maxTreeNodes);
    }

    /** @param fill legt nachgeladene Seiten im gemeinsamen Cache ab (ADR 0010) */
    public PageQueries(SourceCatalog catalog, PageCacheReader cache, AccessGuard access, PageFreshness freshness,
                       PageCacheFill fill, int maxTreeNodes) {
        this.fill = Objects.requireNonNull(fill, "fill");
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
     * Eine Seite mit ihrer nativen Payload. Der Cache ist dabei transparent (ADR 0010): Liegt die Seite nicht im Cache,
     * wird sie von der Quelle nachgeladen, sofern der Aufrufer sie lesen darf, im gemeinsamen Cache abgelegt und
     * ausgeliefert. Die Rechte werden an den live gelesenen Kopfdaten geprüft, bevor ein Inhalt geladen wird, und bei
     * jeder Auslieferung erneut, auch bei einem Cache-Treffer.
     *
     * @param caller  Aufrufer aus der geprüften Anmeldung
     * @param refresh {@code true} verlangt eine Prüfung gegen die Quelle vor der Auslieferung; nie stattdessen der
     *                Cache-Stand. Eine neuere Seite wird dabei ebenfalls abgelegt
     * @throws AccessDeniedException       wenn die Quelle für keinen Space für {@code content} freigegeben ist
     * @throws UnknownSourceException      wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws RefreshUnavailableException bei {@code refresh}, solange keine Prüfung gegen die Quelle angebunden ist
     * @throws SourceAccessException       wenn nachgeladen oder geprüft werden muss und die Quelle nicht oder nicht in
     *                                     der Frist antwortet
     * @throws CacheMissException          wenn die Quelle die Seite nicht (mehr) kennt, sie für den Aufrufer nicht
     *                                     lesbar ist oder sie ohne angebundenen Inhaltsabruf nicht im Cache liegt
     */
    public DeliveredPage page(AccessContext caller, String sourceId, String pageId, boolean refresh) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        requireSource(sourceId);
        if (!refresh) {
            Optional<CachedPage> cached = cache.page(new PageKey(sourceId, pageId));
            if (cached.isPresent()) {
                if (!readable(caller, cached.get().header())) {
                    throw new CacheMissException(sourceId, "Seite nicht im Cache");
                }
                return DeliveredPage.cached(cached.get());
            }
            if (!freshness.available()) {
                throw new CacheMissException(sourceId, "Seite nicht im Cache");
            }
            return fromSource(caller, sourceId, pageId, Optional.empty());
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
        return fromSource(caller, sourceId, pageId, cached);
    }

    /** Prüft bzw. lädt die Seite bei der Quelle, legt einen neuen Inhalt ab und liefert ihn aus. */
    private DeliveredPage fromSource(AccessContext caller, String sourceId, String pageId,
                                     Optional<CachedPage> cached) {
        PageFreshness.Refreshed checked;
        try {
            checked = freshness.check(sourceId, pageId, cached.map(CachedPage::header),
                    cached.map(CachedPage::snapshot), header -> readable(caller, header));
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
        CacheGeneration generation = cached.map(CachedPage::generation).orElse(null);
        boolean live = checked.cacheStale();
        String seen = cached.map(page -> page.snapshot().payload().rawSha256()).orElse(null);
        if (checked.outcome() == PageFreshness.Outcome.CONTENT_CHANGED && stored(checked.snapshot(), seen)) {
            // Jetzt im Cache: die Auslieferung ist der Cache-Stand, mit der Generation des Space.
            Optional<CachedPage> now = cachedQuietly(checked.header().key());
            if (now.isPresent() && now.get().snapshot().payload().rawSha256()
                    .equals(checked.snapshot().payload().rawSha256())) {
                generation = now.get().generation();
                live = false;
            }
        }
        return new DeliveredPage(checked.header(), checked.snapshot(), generation, live, checked.outcome(),
                checked.checkedAt());
    }

    /**
     * Ablegen darf die Auslieferung nie verhindern; misslingt es, legt der nächste Abruf oder Abgleich ab. Abgelegt
     * wird nur über den vorher gesehenen Stand, damit ein langsamer Abruf keinen inzwischen neueren überschreibt.
     */
    private boolean stored(PageSnapshot snapshot, String seenRawSha256) {
        try {
            return fill.store(snapshot, seenRawSha256);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Nachgeladene Seite nicht abgelegt ({0})",
                    e.getClass().getName());
            return false;
        }
    }

    private Optional<CachedPage> cachedQuietly(PageKey key) {
        try {
            return cache.page(key);
        } catch (CacheUnavailableException e) {
            return Optional.empty();
        }
    }

    /**
     * Eine Seite über ihre URL, wie {@link #page}. Die URL muss die Seiten-ID enthalten ({@link PageUrls}) und auf
     * denselben Server zeigen wie die bekannte URL der Seite.
     *
     * @throws InvalidQueryException wenn die URL keine Seiten-ID enthält
     * @throws CacheMissException    wie bei {@link #page}, oder wenn die URL auf einen anderen Server zeigt
     *                               oder die Seite keine bekannte URL hat
     */
    public DeliveredPage pageByUrl(AccessContext caller, String sourceId, String url, boolean refresh) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        String pageId = PageUrls.pageId(url).orElseThrow(() -> new InvalidQueryException(
                "url muss eine absolute Seiten-URL mit Seiten-ID sein (pageId=…, /spaces/KEY/pages/ID oder /x/…)"));
        DeliveredPage page = page(caller, sourceId, pageId, refresh);
        URI known = page.header().metadata().url();
        // Ohne bekannte URL lässt sich der Server nicht prüfen; fail closed wie bei einem fremden Server.
        if (known == null || !PageUrls.sameServer(url, known)) {
            throw new CacheMissException(sourceId, "Seite nicht vorhanden");
        }
        return page;
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
