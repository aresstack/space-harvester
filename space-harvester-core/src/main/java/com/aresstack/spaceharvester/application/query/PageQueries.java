package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
 * Quelle und liefert nie stattdessen den Cache-Stand; ein neuerer Inhalt wird dabei ebenfalls abgelegt. Ohne
 * {@code refresh} gilt eine Seite im Zeitfenster von {@link CacheFreshness} ohne Rückfrage (ADR 0013); danach prüft der
 * Abruf die Version oder, ohne Versionsprüfung, ruft die Seite neu ab.
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
    private final Function<String, CacheFreshness> cacheFreshness;
    private final Clock clock;
    /** Letzte Versionsprüfung ohne neuen Inhalt; die gespeicherten Kopfdaten tragen nur ihren eigenen Stand. */
    /** So viele letzte Prüfungen merkt sich der Use-Case höchstens. */
    static final int MAX_CHECKED = 10_000;

    private final Map<PageKey, Instant> checked = new ConcurrentHashMap<>();

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
        this(catalog, cache, access, freshness, fill, maxTreeNodes, CacheFreshness.DEFAULT, Clock.systemUTC());
    }

    /** @param cacheFreshness wann eine Seite aus dem Cache ohne Rückfrage bei der Quelle gilt (ADR 0013) */
    public PageQueries(SourceCatalog catalog, PageCacheReader cache, AccessGuard access, PageFreshness freshness,
                       PageCacheFill fill, int maxTreeNodes, CacheFreshness cacheFreshness, Clock clock) {
        this(catalog, cache, access, freshness, fill, maxTreeNodes, fixed(cacheFreshness), clock);
    }

    /**
     * Wie oben, mit der Aktualität je Quelle, die jeder Abruf neu liest (Konfiguration zur Laufzeit, ADR 0015).
     *
     * @param cacheFreshness Aktualität vorhandener Inhalte je Quellen-ID
     */
    public PageQueries(SourceCatalog catalog, PageCacheReader cache, AccessGuard access, PageFreshness freshness,
                       PageCacheFill fill, int maxTreeNodes, Function<String, CacheFreshness> cacheFreshness,
                       Clock clock) {
        this.cacheFreshness = Objects.requireNonNull(cacheFreshness, "cacheFreshness");
        this.clock = Objects.requireNonNull(clock, "clock");
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

    private static Function<String, CacheFreshness> fixed(CacheFreshness cacheFreshness) {
        Objects.requireNonNull(cacheFreshness, "cacheFreshness");
        return sourceId -> cacheFreshness;
    }

    private CacheFreshness freshnessFor(String sourceId) {
        CacheFreshness found = cacheFreshness.apply(sourceId);
        return found == null ? CacheFreshness.DEFAULT : found;
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
     * Eine Seite mit ihrer nativen Payload. Der Cache ist dabei transparent (ADR 0010, Punkt 8): Jeder Abruf liest
     * zuerst die Kopfdaten live bei der Quelle und prüft an ihnen in einem Schritt die Rechte des Aufrufers und die
     * Version. Liegt dieselbe Version im Cache, kommt sie von dort; ist sie älter oder fehlt sie, wird die Seite geholt,
     * im gemeinsamen Cache abgelegt und ausgeliefert. Das gilt für jede Anmeldeart gleich.
     *
     * <p>Ist die Quelle für diese Prüfung nicht erreichbar ({@code UNAVAILABLE}, {@code RATE_LIMITED}), liefert der
     * Abruf ohne {@code refresh} den Cache-Stand, aber nur, wenn die Richtlinie dem Aufrufer die Seite an ihren
     * gespeicherten Kopfdaten freigibt; eine delegierte Prüfung, die die Quelle braucht, schlägt dann fehl und gibt
     * nichts heraus. Ohne angebundenen Inhaltsabruf gilt nur der Cache.
     *
     * @param caller  Aufrufer aus der geprüften Anmeldung
     * @param refresh {@code true} verlangt die Prüfung gegen die Quelle ohne Rückgriff auf den Cache, wenn sie nicht
     *                antwortet
     * @throws AccessDeniedException       wenn die Quelle für keinen Space für {@code content} freigegeben ist
     * @throws UnknownSourceException      wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws RefreshUnavailableException bei {@code refresh}, solange keine Prüfung gegen die Quelle angebunden ist
     * @throws SourceAccessException       wenn die Quelle nicht oder nicht in der Frist antwortet und kein lesbarer
     *                                     Cache-Stand vorliegt (mit {@code refresh} immer)
     * @throws CacheMissException          wenn die Quelle die Seite nicht (mehr) kennt, sie für den Aufrufer nicht
     *                                     lesbar ist oder sie ohne angebundenen Inhaltsabruf nicht im Cache liegt
     */
    public DeliveredPage page(AccessContext caller, String sourceId, String pageId, boolean refresh) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        requireSource(sourceId);
        if (!freshness.available()) {
            if (refresh) {
                throw new RefreshUnavailableException("Die Prüfung gegen die Quelle ist noch nicht angebunden");
            }
            Optional<CachedPage> cached = cache.page(new PageKey(sourceId, pageId));
            if (cached.isEmpty() || !readable(caller, cached.get().header())) {
                throw new CacheMissException(sourceId, "Seite nicht im Cache");
            }
            return DeliveredPage.cached(cached.get());
        }
        // Ohne lesbaren Cache prüft der Abruf trotzdem live; die Antwort ist dann als live gekennzeichnet.
        Optional<CachedPage> cached;
        try {
            cached = cache.page(new PageKey(sourceId, pageId));
        } catch (CacheUnavailableException e) {
            cached = Optional.empty();
        }
        if (!refresh && cached.isPresent() && fresh(cached.get())) {
            // ADR 0013: im Zeitfenster ohne Rückfrage bei der Quelle; Rechte gelten trotzdem.
            if (!readable(caller, cached.get().header())) {
                throw new CacheMissException(sourceId, "Seite nicht vorhanden");
            }
            return DeliveredPage.cached(cached.get());
        }
        try {
            return fromSource(caller, sourceId, pageId, cached);
        } catch (SourceAccessException e) {
            boolean unreachable = e.reason() == SourceAccessException.Reason.UNAVAILABLE
                    || e.reason() == SourceAccessException.Reason.RATE_LIMITED;
            if (refresh || !unreachable || cached.isEmpty()) {
                throw e;
            }
            if (!readable(caller, cached.get().header())) {
                throw new CacheMissException(sourceId, "Seite nicht vorhanden");
            }
            // Ohne Seiten-ID: die Meldung landet auch bei Abrufen nicht lesbarer Seiten im Log.
            LOG.log(System.Logger.Level.INFO, "Quelle {0} für die Versionsprüfung nicht erreichbar ({1}), "
                    + "Cache-Stand ausgeliefert", sourceId, e.reason());
            return DeliveredPage.cached(cached.get());
        }
    }

    /**
     * Der Beschaffungsweg des {@code AcquisitionScheduler} (ADR 0016): wie {@link #page} ohne {@code refresh}. Braucht
     * der Hintergrund die Seite, gilt sie nur als geladen, wenn sie jetzt bei der Quelle geprüft wurde und der Cache
     * diesen Stand hält: kein Rückfall auf den alten Stand, wenn die Quelle nicht antwortet oder drosselt, und keine
     * nur live ausgelieferte Fassung, die sich nicht ablegen ließ.
     *
     * @throws SourceAccessException wenn die Quelle nicht antwortet (im Hintergrund immer)
     * @throws IllegalStateException wenn im Hintergrund nichts Aktuelles abgelegt ist
     */
    public void acquire(AccessContext caller, PageKey key, boolean background) {
        DeliveredPage delivered = page(caller, key.sourceId(), key.pageId(), background);
        if (background && (delivered.refresh() == null || delivered.live())) {
            throw new IllegalStateException("Seite nicht aktuell abgelegt");
        }
    }

    /** Prüft bzw. lädt die Seite bei der Quelle, legt einen neuen Inhalt ab und liefert ihn aus. */
    private DeliveredPage fromSource(AccessContext caller, String sourceId, String pageId,
                                     Optional<CachedPage> cached) {
        // Vor dem Abruf, damit eine inzwischen abgelegte neuere Fassung nicht überschrieben wird.
        String seen = stateQuietly(new PageKey(sourceId, pageId));
        PageFreshness.Refreshed checked;
        CacheFreshness rule = freshnessFor(sourceId);
        // Ohne Versionsprüfung wird eine Seite nach Ablauf neu abgerufen, als läge sie nicht im Cache.
        Optional<PageSnapshot> compareWith = rule.versionCheck() ? cached.map(CachedPage::snapshot)
                : Optional.empty();
        try {
            checked = freshness.check(sourceId, pageId, cached.map(CachedPage::header), compareWith,
                    header -> readable(caller, header));
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.NOT_FOUND) {
                // Etwa beim Inhaltsabruf: wie GONE, damit der Fehlertyp nichts über die Seite verrät.
                throw new CacheMissException(sourceId, "Seite nicht vorhanden");
            }
            throw e;
        }
        if (checked.outcome() != PageFreshness.Outcome.CURRENT) {
            // Nur eine unveränderte Seite öffnet ein neues Zeitfenster: geänderte Kopfdaten (etwa ein anderer Space)
            // liegen nicht im Cache, die Rechteprüfung im Zeitfenster sähe sonst die alten.
            this.checked.remove(new PageKey(sourceId, pageId));
        }
        // Rechte am Ergebnis der Quelle: eine verschobene Seite zählt in ihrem neuen Space. Nicht mehr vorhanden und
        // nicht freigegeben sehen gleich aus.
        if (checked.outcome() == PageFreshness.Outcome.GONE || !readable(caller, checked.header())) {
            throw new CacheMissException(sourceId, "Seite nicht vorhanden");
        }
        CacheGeneration generation = cached.map(CachedPage::generation).orElse(null);
        boolean live = checked.cacheStale();
        if (checked.outcome() == PageFreshness.Outcome.CONTENT_CHANGED && stored(checked.snapshot(), seen)) {
            // Jetzt im Cache: die Auslieferung ist der Cache-Stand, mit der Generation des Space.
            Optional<CachedPage> now = cachedQuietly(checked.header().key());
            if (now.isPresent() && now.get().snapshot().payload().rawSha256()
                    .equals(checked.snapshot().payload().rawSha256())) {
                generation = now.get().generation();
                live = false;
            }
        }
        if (checked.outcome() == PageFreshness.Outcome.CURRENT && !rule.maxAge().isZero()) {
            if (this.checked.size() >= MAX_CHECKED) {
                // Begrenzt: ohne Eintrag zählt das Zeitfenster ab Inhaltsabruf bzw. den Kopfdaten im Cache.
                this.checked.clear();
            }
            this.checked.put(checked.header().key(), checked.checkedAt());
        }
        return new DeliveredPage(checked.header(), checked.snapshot(), generation, live, checked.outcome(),
                checked.checkedAt());
    }

    /** Ob die Seite im Zeitfenster liegt: ab Inhaltsabruf, mit Versionsprüfung ab der letzten Prüfung. */
    private boolean fresh(CachedPage page) {
        Instant lastChecked = checked.get(page.header().key());
        // Kopfdaten derselben Version, etwa aus einem Abgleich, zählen als Prüfung; eine neuere Version nie als frisch.
        String version = page.header().metadata().version();
        if (version == null || !version.equals(page.snapshot().revision())) {
            return false;
        }
        Instant observed = page.header().observedAt();
        if (lastChecked == null || observed.isAfter(lastChecked)) {
            lastChecked = observed;
        }
        return freshnessFor(page.header().key().sourceId()).fresh(page.snapshot().retrievedAt(), lastChecked, clock.instant());
    }

    /**
     * Ablegen darf die Auslieferung nie verhindern; misslingt es, legt der nächste Abruf oder Abgleich ab. Abgelegt
     * wird nur über den vorher gesehenen Stand, damit ein langsamer Abruf keinen inzwischen neueren überschreibt.
     */
    private boolean stored(PageSnapshot snapshot, String seenState) {
        try {
            return fill.store(snapshot, seenState);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Nachgeladene Seite nicht abgelegt ({0})",
                    e.getClass().getName());
            return false;
        }
    }

    private String stateQuietly(PageKey key) {
        try {
            return fill.state(key);
        } catch (RuntimeException e) {
            // Unbekannt: ein Vergleichswert, den kein gespeicherter Stand hat, verhindert das Ablegen.
            return "unbekannt";
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
     * Eine Seite über ihre Adresse, wie {@link #page} (#178). Angenommen werden:
     * <ul>
     *   <li>Lesezeichen {@code space-harvester://<Quelle>/page/<ID>} ({@link PageBookmark}),</li>
     *   <li>alles, was die Quelle selbst auflöst ({@link HarvestSource#resolveAddress}): bei Confluence Adressen unter
     *   der Basis-URL mit Seiten-ID, {@code display/KEY/Titel}, {@code viewpage.action?spaceKey=…&title=…}, die
     *   Startseite {@code display/KEY}, Bookmarks {@code confluence://…} und Ressourcen-IDs; Titel und Startseite
     *   fragt die Quelle live nach,</li>
     *   <li>URLs mit Seiten-ID ({@link PageUrls}) auf demselben Server wie die bekannte URL der Seite, auch wenn
     *   Confluence seine Links unter einem anderen Namen als der Basis-URL schreibt.</li>
     * </ul>
     * Rechte gelten am Ergebnis: eine Seite, die der Aufrufer nicht lesen darf, sieht aus wie eine unbekannte.
     *
     * @throws InvalidQueryException wenn die Eingabe keine Adresse dieser Quelle sein kann
     * @throws CacheMissException    wie bei {@link #page}, oder wenn die Adresse auf keine Seite zeigt, auf einen
     *                               anderen Server zeigt oder zu einer anderen Quelle gehört
     * @throws SourceAccessException wenn die Quelle für die Auflösung eines Titels nicht erreichbar ist
     */
    public DeliveredPage pageByUrl(AccessContext caller, String sourceId, String url, boolean refresh) {
        return pageByAddress(caller, sourceId, url, refresh, true);
    }

    /**
     * Wie {@link #pageByUrl}; ohne {@code byKnownUrl} nur Adressen, die Lesezeichen oder Quelle selbst zuordnen, ohne
     * den Abruf über eine Seiten-ID aus einer fremden URL. So fragt die Auflösung über alle Quellen
     * ({@link PageAddresses}) keine Quelle nach einer Seite, die zu einer anderen gehört.
     */
    DeliveredPage pageByAddress(AccessContext caller, String sourceId, String url, boolean refresh,
                                boolean byKnownUrl) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        HarvestSource source = catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
        if (url == null || url.isBlank() || url.length() > PageBookmark.maxAddressLength(url)) {
            throw new InvalidQueryException("url fehlt oder ist zu lang");
        }
        String address = url.trim();
        Optional<PageBookmark> bookmark;
        try {
            bookmark = PageBookmark.parse(address);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException(e.getMessage());
        }
        if (bookmark.isPresent()) {
            if (!bookmark.get().sourceId().equals(sourceId)) {
                throw new CacheMissException(sourceId, "Seite nicht vorhanden");
            }
            return page(caller, sourceId, bookmark.get().pageId(), refresh);
        }
        boolean addressable = source.capabilities().contains(SourceCapability.ADDRESSES)
                && source.addressable(address);
        if (addressable) {
            Optional<String> resolved = source.resolveAddress(address);
            if (resolved.isPresent()) {
                return page(caller, sourceId, resolved.get(), refresh);
            }
        }
        Optional<String> pageId = byKnownUrl ? PageUrls.pageId(address) : Optional.empty();
        if (pageId.isEmpty()) {
            if (addressable) {
                // Passt zur Quelle, zeigt aber auf keine (sichtbare) Seite: wie eine unbekannte Seite.
                throw new CacheMissException(sourceId, "Seite nicht vorhanden");
            }
            throw new InvalidQueryException("url ist keine Adresse dieser Quelle (Seiten-URL, Titel-URL "
                    + "/display/KEY/Titel, Startseite /display/KEY, Lesezeichen oder Ressourcen-ID)");
        }
        DeliveredPage page = page(caller, sourceId, pageId.get(), refresh);
        URI known = page.header().metadata().url();
        // Ohne bekannte URL lässt sich der Server nicht prüfen; fail closed wie bei einem fremden Server.
        if (known == null || !PageUrls.sameServer(address, known)) {
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
