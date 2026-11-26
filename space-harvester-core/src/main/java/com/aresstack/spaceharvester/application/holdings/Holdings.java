package com.aresstack.spaceharvester.application.holdings;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.query.TreeTooLargeException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageLoads;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Der Bestand (ADR 0027, Abschnitt 4): Übersicht je Space und Meldungen bei jeder Änderung.
 *
 * <p><b>Abfrage.</b> {@link #overview} rechnet je Seite des Katalogs den eigenen Zustand ({@link HoldingState}) und
 * die Zähler ihres Teilbaums über denselben Baum wie der Seitenbaum ({@link PageTreeBuilder}: fehlende Elternseite und
 * Zyklen machen eine Seite zur Wurzel). Gesperrte Seiten hängen an der Elternseite aus ihrem zurückgehaltenen Stand.
 * Kein Quellenzugriff, keine Payload.
 *
 * <p><b>Meldungen.</b> Schreibwege melden über {@link HoldingsChanges}, Inhaltsabrufe bei der Quelle über
 * {@link PageLoads} (Beginn als „lädt“, Ende als Änderung, auch ohne Schreiben); hier wird je Space nur vorgemerkt und
 * gebündelt auf dem Zustell-Thread zugestellt ({@link Coalescer}). Ohne Empfänger wird nichts vorgemerkt.
 *
 * <p><b>Rechte.</b> Abfrage und Anmeldung verlangen {@link AccessKind#METADATA} für den Space (bei der Anmeldung für
 * alle Spaces: für die Quelle); Seiten ohne Freigabe fehlen in Übersicht und Meldungen, wie im Seitenbaum.
 */
public final class Holdings implements HoldingsChanges, PageLoads, AutoCloseable {

    /** Höchstens so viele Seiten nennt eine Meldung {@link HoldingsListener#pagesChanged}; sonst kommt der Space. */
    public static final int MAX_PAGES_PER_MESSAGE = 1000;

    private static final Runnable NOTHING = () -> { };

    private final SourceCatalog sources;
    private final HoldingsCatalog catalog;
    private final AccessGuard access;
    private final int maxTreeNodes;
    private volatile com.aresstack.spaceharvester.application.query.PageTextIndex textIndex;

    public void localIndex(com.aresstack.spaceharvester.application.query.PageTextIndex index) {
        textIndex = Objects.requireNonNull(index, "index");
    }

    /** Route primary index changes only to sources using local originals. */
    public HoldingsChanges localIndexChanges() {
        return new HoldingsChanges() {
            @Override
            public void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds) {
                if (localOriginals(sourceId)) {
                    Holdings.this.pagesChanged(sourceId, spaceKey, pageIds);
                }
            }

            @Override
            public void spaceChanged(String sourceId, String spaceKey) {
                if (localOriginals(sourceId)) {
                    Holdings.this.spaceChanged(sourceId, spaceKey);
                }
            }

            @Override
            public void sourceChanged(String sourceId) {
                spaceChanged(sourceId, "");
            }

            @Override
            public void loading(String sourceId, String spaceKey, Collection<String> pageIds) {
                // Cache loads already report their own progress.
            }
        };
    }

    private boolean localOriginals(String sourceId) {
        return sources.find(sourceId).filter(source -> "files".equals(source.type())
                && source.readsOriginalInPlace()).isPresent();
    }
    private final Listeners<Subscriber> listeners = new Listeners<>("Bestand");
    private final Coalescer<Space, Mark> notifier;

    /**
     * @param maxTreeNodes größte Zahl sichtbarer Seiten einer Übersicht, wie beim Seitenbaum ({@code query.max-tree-nodes})
     */
    public Holdings(SourceCatalog sources, HoldingsCatalog catalog, AccessGuard access, int maxTreeNodes) {
        this(sources, catalog, access, maxTreeNodes, Coalescer.INTERVAL);
    }

    /** Mit eigenem Bündelungsintervall, für Tests. */
    Holdings(SourceCatalog sources, HoldingsCatalog catalog, AccessGuard access, int maxTreeNodes,
             Duration interval) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.access = Objects.requireNonNull(access, "access");
        if (maxTreeNodes < 1) {
            throw new IllegalArgumentException("maxTreeNodes muss positiv sein");
        }
        this.maxTreeNodes = maxTreeNodes;
        this.notifier = new Coalescer<>("holdings", interval, this::deliver);
    }

    // --- Abfragen -------------------------------------------------------------------------------------------------

    /**
     * Die Bestandsübersicht eines Space; leer mit Zählern null, wenn der Cache von ihm nichts kennt.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn der Space nicht für
     *                                                                               {@code metadata} freigegeben ist
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws TreeTooLargeException  wenn der Space mehr sichtbare Seiten hat als {@code maxTreeNodes}
     */
    public SpaceHoldings overview(AccessContext caller, String sourceId, String spaceKey) {
        Computed computed = compute(caller, sourceId, spaceKey, true);
        return new SpaceHoldings(computed.summary(), computed.pages());
    }

    /**
     * Nach einer Meldung nur die genannten Seiten samt ihrer Vorfahren, damit nur der Pfad nach oben neu gezeichnet
     * wird; eine genannte Seite, die es in diesem Space nicht mehr gibt, steht dort als {@link HoldingState#REMOVED}.
     * Gerechnet wird trotzdem über den ganzen Space, denn die Zähler der Vorfahren hängen an allen Nachkommen.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wie {@link #overview(AccessContext,
     *                                                                               String, String)}
     */
    public SpaceHoldings overview(AccessContext caller, String sourceId, String spaceKey,
                                  Collection<String> pageIds) {
        Objects.requireNonNull(pageIds, "pageIds");
        Computed computed = compute(caller, sourceId, spaceKey, true);
        Map<String, PageHolding> selected = new HashMap<>();
        for (String pageId : pageIds) {
            PageHolding page = computed.pages().get(pageId);
            if (page == null) {
                selected.put(pageId, PageHolding.removed(pageId));
                continue;
            }
            while (page != null && selected.put(page.pageId(), page) == null) {
                page = page.parentPageId() == null ? null : computed.pages().get(page.parentPageId());
            }
        }
        return new SpaceHoldings(computed.summary(), selected);
    }

    /**
     * Je Space der Quelle, den der Cache kennt und den der Aufrufer sehen darf, Stand und Zähler ohne Seitenliste;
     * nach Space-Key. Ohne Obergrenze, denn ohne Baum.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn die Quelle nicht für
     *                                                                               {@code metadata} freigegeben ist
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     */
    public List<SpaceSummary> spaces(AccessContext caller, String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        requireSource(sourceId);
        List<SpaceSummary> spaces = new ArrayList<>();
        for (String spaceKey : catalog.spaceKeys(sourceId)) {
            if (access.permits(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA)) {
                spaces.add(compute(caller, sourceId, spaceKey, false).summary());
            }
        }
        return spaces;
    }

    private Computed compute(AccessContext caller, String sourceId, String spaceKey, boolean tree) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
        requireSource(sourceId);
        Optional<HoldingsCatalog.Stock> found = catalog.stock(sourceId, spaceKey);
        if (found.isEmpty()) {
            return new Computed(new SpaceSummary(sourceId, spaceKey, null, HoldingCounts.ZERO), Map.of());
        }
        HoldingsCatalog.Stock stock = found.get();
        Map<String, HoldingState> states = new LinkedHashMap<>();
        Map<String, String> parents = new HashMap<>();
        visiblePages(caller, sourceId, spaceKey, stock, states, parents);
        HoldingCounts total = HoldingCounts.ZERO;
        for (HoldingState state : states.values()) {
            total = total.plus(HoldingCounts.of(state));
        }
        Map<String, IndexState> indexStates = indexStates(caller, sourceId, spaceKey, stock, states);
        IndexCoverage coverage = indexStates == null ? null : indexStates.values().stream()
                .map(IndexCoverage::of).reduce(IndexCoverage.ZERO, IndexCoverage::plus);
        SpaceSummary summary = new SpaceSummary(sourceId, spaceKey, stock.generation(), total, coverage);
        if (!tree) {
            return new Computed(summary, Map.of());
        }
        if (states.size() > maxTreeNodes) {
            throw new TreeTooLargeException("Der Bestand hat mehr als " + maxTreeNodes + " Seiten");
        }
        List<TreeEntry> entries = new ArrayList<>(states.size());
        parents.forEach((pageId, parent) -> entries.add(new TreeEntry(pageId, parent)));
        Map<String, PageHolding> pages = new HashMap<>(states.size() * 2);
        for (PageTree.Node root : PageTreeBuilder.build(entries).roots()) {
            accumulate(root, states, indexStates, pages);
        }
        return new Computed(summary, pages);
    }

    /** Resolve local full-text coverage separately from cache/export eligibility. */
    private Map<String, IndexState> indexStates(AccessContext caller, String sourceId, String spaceKey,
                                                HoldingsCatalog.Stock stock, Map<String, HoldingState> states) {
        var index = textIndex;
        if (index == null || !localOriginals(sourceId)) {
            return null;
        }
        Map<String, com.aresstack.spaceharvester.application.query.PageTextIndex.ContentState> indexed =
                index.contentStates(sourceId, spaceKey);
        Map<String, IndexState> result = new HashMap<>();
        for (HoldingsCatalog.CatalogPage page : stock.pages()) {
            HoldingState state = states.get(page.pageId());
            if (state != null) {
                HoldingState readable = access.canRead(caller, sourceId, spaceKey, page.pageId())
                        ? state : HoldingState.LOCKED;
                result.put(page.pageId(), LocalIndexStatus.state(page, spaceKey, readable,
                        indexed.get(page.pageId())));
            }
        }
        states.forEach((id, state) -> {
            if (state == HoldingState.LOCKED) {
                result.putIfAbsent(id, IndexState.LOCKED);
            }
        });
        return result;
    }

    /** Zustand und Elternseite jeder Seite des Space, die der Aufrufer sehen darf. */
    private void visiblePages(AccessContext caller, String sourceId, String spaceKey, HoldingsCatalog.Stock stock,
                              Map<String, HoldingState> states, Map<String, String> parents) {
        for (HoldingsCatalog.CatalogPage page : stock.pages()) {
            if (!states.containsKey(page.pageId()) && visible(caller, sourceId, spaceKey, page.pageId())) {
                states.put(page.pageId(), state(page, stock.stale()));
                parents.put(page.pageId(), page.parentPageId());
            }
        }
        // Eine wieder sichtbare Seite hat ein Seitendokument, bevor die nächste Generation sie entsperrt: es gilt der
        // Katalog.
        for (HoldingsCatalog.LockedPage page : stock.locked()) {
            if (!states.containsKey(page.pageId()) && visible(caller, sourceId, spaceKey, page.pageId())) {
                states.put(page.pageId(), HoldingState.LOCKED);
                parents.put(page.pageId(), page.parentPageId());
            }
        }
    }

    // --- Auswahl (ADR 0029) ---------------------------------------------------------------------------------------

    /**
     * Löst eine Auswahl im Core zu Seiten auf (ADR 0029), über denselben Baum wie {@link #overview}: eine Seite samt
     * allen Nachkommen, ein ganzer Space oder alle Spaces einer Quelle, die der Aufrufer sehen darf. Ohne Quellenzugriff
     * und ohne Obergrenze, denn das Ergebnis ist nur eine Menge von IDs. Seiten ohne Leserecht fehlen.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn ein gewählter Space bzw. eine
     *                                                                               gewählte Quelle nicht für
     *                                                                               {@code metadata} freigegeben ist
     * @throws UnknownSourceException wenn eine Quelle nicht konfiguriert ist
     */
    public PageSelection resolve(AccessContext caller, Selection selection) {
        return resolve(caller, selection, null);
    }

    /**
     * {@link #resolve} nachsichtig (ADR 0034 §4), mit den Rechten des Aufrufers: Wurzeln, die er nicht sehen darf, deren
     * Quelle nicht konfiguriert ist oder deren Seite der Katalog eines inventarisierten Space nicht kennt (etwa ein
     * veralteter Favorit), werden übersprungen und in {@link Lenient#skippedRoots()} gemeldet, statt die ganze
     * Auflösung zu brechen. Eine Wurzel in einem noch nie geladenen Space gilt nicht als übersprungen; sie macht die
     * Auflösung wie bei {@link #resolve} unvollständig.
     */
    public Lenient resolveLenient(AccessContext caller, Selection selection) {
        List<Selection.Root> skipped = new ArrayList<>();
        PageSelection resolved = resolve(caller, selection, skipped);
        return new Lenient(resolved, skipped);
    }

    /**
     * Eine nachsichtig aufgelöste Auswahl.
     *
     * @param selection    die Seiten der übrigen Wurzeln
     * @param skippedRoots die übersprungenen Wurzeln in ihrer Reihenfolge, etwa für „n Favoriten nicht verfügbar“
     */
    public record Lenient(PageSelection selection, List<Selection.Root> skippedRoots) {

        public Lenient {
            Objects.requireNonNull(selection, "selection");
            skippedRoots = List.copyOf(skippedRoots);
        }
    }

    /** Gemeinsame Auflösung; {@code skipped} {@code null} heißt streng, sonst landen übersprungene Wurzeln darin. */
    private PageSelection resolve(AccessContext caller, Selection selection, List<Selection.Root> skipped) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(selection, "selection");
        Map<String, Set<String>> pages = new LinkedHashMap<>();
        Map<String, Set<String>> locked = new LinkedHashMap<>();
        Map<Space, SpaceSummary> spaces = new LinkedHashMap<>();
        HoldingCounts counts = HoldingCounts.ZERO;
        boolean complete = true;
        for (Selection.Root root : selection.roots()) {
            List<Resolved> parts = new ArrayList<>();
            try {
                for (String spaceKey : spaceKeys(caller, root)) {
                    parts.add(resolve(caller, root.sourceId(), spaceKey, root.pageId()));
                }
            } catch (AccessDeniedException | UnknownSourceException e) {
                if (skipped == null) {
                    throw e;
                }
                skipped.add(root);
                continue;
            }
            if (skipped != null && root.pageId() != null && parts.stream()
                    .allMatch(part -> !part.found() && part.summary().inventoried())) {
                skipped.add(root);
                continue;
            }
            for (Resolved resolved : parts) {
                String spaceKey = resolved.summary().spaceKey();
                spaces.putIfAbsent(new Space(root.sourceId(), spaceKey), resolved.summary());
                complete &= resolved.summary().inventoried() && resolved.found();
                Set<String> selected = pages.computeIfAbsent(root.sourceId(), id -> new HashSet<>());
                for (Map.Entry<String, HoldingState> page : resolved.pages().entrySet()) {
                    if (selected.add(page.getKey())) {
                        counts = counts.plus(HoldingCounts.of(page.getValue()));
                        if (page.getValue() == HoldingState.LOCKED) {
                            locked.computeIfAbsent(root.sourceId(), id -> new HashSet<>()).add(page.getKey());
                        }
                    }
                }
            }
        }
        return new PageSelection(PageSet.of(pages), counts, List.copyOf(spaces.values()), complete,
                PageSet.of(locked));
    }

    // --- Fingerabdruck (ADR 0034 §4) ------------------------------------------------------------------------------

    /**
     * Fingerabdruck des Katalogstands einer aufgelösten Auswahl (ADR 0034 §4): je Seite Elternseite, ob und in welcher
     * Version ihr Inhalt gespeichert ist, Version der Kopfdaten, ob sie veraltet oder gesperrt ist. Ändern sich die
     * Seiten der Auswahl oder ihr Stand, ändert er sich; so erkennt eine Zusammenfassung „veraltet“. Berechnet beim
     * Lesen aus dem Katalog, ohne Scan der Quelle und ohne Abfrage; Spaces ohne Leserecht des Aufrufers zählen nicht.
     */
    public String fingerprint(AccessContext caller, PageSelection selection) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(selection, "selection");
        java.util.TreeMap<String, String> entries = new java.util.TreeMap<>();
        for (SpaceSummary space : selection.spaces()) {
            if (!access.permits(caller, ResourceScope.space(space.sourceId(), space.spaceKey()), AccessKind.METADATA)) {
                continue;
            }
            Set<String> scope = selection.pagesOf(space.sourceId());
            catalog.stock(space.sourceId(), space.spaceKey()).ifPresent(stock -> catalogState(stock, scope)
                    .forEach((pageId, entry) -> entries.put(space.sourceId() + '\u0000' + space.spaceKey() + '\u0000'
                            + pageId, entry)));
        }
        return digest(entries);
    }

    /**
     * Der Katalogstand je Seite aus {@code scope} (Seiten-ID → Eintrag), sortiert; Grundlage von {@link #fingerprint}
     * und vom Fingerabdruck einer Ausgabe aus dem Bestand (ADR 0029), die ihn um den gespeicherten Inhalt ergänzt.
     */
    public static java.util.TreeMap<String, String> catalogState(HoldingsCatalog.Stock stock, Set<String> scope) {
        java.util.TreeMap<String, String> entries = new java.util.TreeMap<>();
        for (HoldingsCatalog.CatalogPage page : stock.pages()) {
            if (scope.contains(page.pageId())) {
                entries.put(page.pageId(), page.parentPageId() + "\u0000" + page.loaded() + "\u0000" + page.version()
                        + "\u0000" + page.loadedVersion() + "\u0000" + stock.stale().contains(page.pageId()));
            }
        }
        for (HoldingsCatalog.LockedPage page : stock.locked()) {
            if (scope.contains(page.pageId())) {
                entries.putIfAbsent(page.pageId(), page.parentPageId() + "\u0000locked");
            }
        }
        return entries;
    }

    /** SHA-256 über die Einträge in ihrer Reihenfolge, gekürzt auf 12 Bytes in Hex. */
    public static String digest(Map<String, String> entries) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            entries.forEach((key, entry) -> {
                digest.update(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 1);
                digest.update(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 2);
            });
            return java.util.HexFormat.of().formatHex(digest.digest(), 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    /**
     * Die Nachfahren einer Seite als Seitenmenge (Fahrplan P1, „mit Unterseiten“): derselbe Teilbaum wie
     * {@link #resolve} mit {@link Selection.Root#subtree}, aber ohne die Seite selbst. Die Analyse-Reiter einer Seite
     * fragen damit beim Anzeigen über ihre Unterseiten ab; nichts wird in die Elternseite kopiert, eine Änderung einer
     * Unterseite ist beim nächsten Abfragen von selbst dabei. Leer, wenn die Seite keine sichtbaren Unterseiten hat oder
     * nicht im Katalog steht; {@link PageSelection#complete()} sagt wie bei {@link #resolve}, ob der Teilbaum
     * lückenlos ist. Die Zähler zählen nur die Nachfahren; {@link PageSelection#locked()} nennt die gesperrten unter
     * ihnen.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wie {@link #resolve}
     * @throws UnknownSourceException wie {@link #resolve}
     */
    public PageSelection descendants(AccessContext caller, String sourceId, String spaceKey, String pageId) {
        Objects.requireNonNull(caller, "caller");
        Selection.Root root = Selection.Root.subtree(sourceId, spaceKey, pageId);
        Resolved resolved = resolve(caller, root.sourceId(), root.spaceKey(), root.pageId());
        Set<String> below = new HashSet<>();
        Set<String> locked = new HashSet<>();
        HoldingCounts counts = HoldingCounts.ZERO;
        for (Map.Entry<String, HoldingState> page : resolved.pages().entrySet()) {
            if (!page.getKey().equals(pageId) && below.add(page.getKey())) {
                counts = counts.plus(HoldingCounts.of(page.getValue()));
                if (page.getValue() == HoldingState.LOCKED) {
                    locked.add(page.getKey());
                }
            }
        }
        Map<String, Set<String>> pages = below.isEmpty() ? Map.of() : Map.of(sourceId, below);
        return new PageSelection(PageSet.of(pages), counts, List.of(resolved.summary()),
                resolved.summary().inventoried() && resolved.found(),
                PageSet.of(locked.isEmpty() ? Map.of() : Map.of(sourceId, locked)));
    }

    /**
     * Space und Bestand je Seite einer Menge, nach derselben Regel wie {@link #state} (für den Planer, Fahrplan D1, und
     * {@code StatementAnalysisJobs#missing}). Ohne Quellenzugriff. Seiten ohne gespeicherten Kopf (etwa gelistet, aber
     * beim ersten Abruf gescheitert) findet sie im Bestand der Spaces der Quelle. Es fehlen Seiten, die kein Bestand
     * nennt, in Spaces ohne {@code metadata}-Freigabe und solche, die der Aufrufer nicht sehen darf.
     */
    public Map<PageKey, Located> locate(AccessContext caller, PageSet pages) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(pages, "pages");
        Map<PageKey, Located> located = new LinkedHashMap<>();
        for (String sourceId : pages.sourceIds()) {
            Map<String, List<String>> bySpace = new LinkedHashMap<>();
            Set<String> unplaced = new LinkedHashSet<>();
            for (String pageId : pages.pagesOf(sourceId)) {
                Optional<String> spaceKey = catalog.spaceOf(sourceId, pageId);
                if (spaceKey.isPresent()) {
                    bySpace.computeIfAbsent(spaceKey.get(), ignored -> new ArrayList<>()).add(pageId);
                } else {
                    unplaced.add(pageId);
                }
            }
            Map<String, Optional<HoldingsCatalog.Stock>> stocks = new HashMap<>();
            for (Map.Entry<String, List<String>> space : bySpace.entrySet()) {
                locateIn(caller, sourceId, space.getKey(), space.getValue(), stocks, located);
            }
            if (!unplaced.isEmpty()) {
                // Ohne Kopf: zuerst in den schon gelesenen Spaces suchen, dann in den übrigen der Quelle.
                Set<String> spaceKeys = new LinkedHashSet<>(bySpace.keySet());
                spaceKeys.addAll(catalog.spaceKeys(sourceId));
                for (String spaceKey : spaceKeys) {
                    if (unplaced.isEmpty()) {
                        break;
                    }
                    unplaced.removeAll(locateIn(caller, sourceId, spaceKey, List.copyOf(unplaced), stocks, located));
                }
            }
        }
        return located;
    }

    /**
     * Ordnet {@code pageIds} im Bestand eines Spaces ein ({@link #locate}).
     *
     * @return die Seiten, die dieser Bestand nennt, auch die der Aufrufer nicht sehen darf
     */
    private Set<String> locateIn(AccessContext caller, String sourceId, String spaceKey, List<String> pageIds,
                                 Map<String, Optional<HoldingsCatalog.Stock>> stocks, Map<PageKey, Located> located) {
        if (!access.permits(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA)) {
            return Set.of();
        }
        Optional<HoldingsCatalog.Stock> found = stocks.computeIfAbsent(spaceKey,
                key -> catalog.stock(sourceId, key));
        if (found.isEmpty()) {
            return Set.of();
        }
        HoldingsCatalog.Stock stock = found.get();
        Map<String, HoldingsCatalog.CatalogPage> byId = new HashMap<>();
        stock.pages().forEach(page -> byId.putIfAbsent(page.pageId(), page));
        Set<String> locked = new HashSet<>();
        stock.locked().forEach(page -> locked.add(page.pageId()));
        Set<String> named = new HashSet<>();
        for (String pageId : pageIds) {
            HoldingsCatalog.CatalogPage page = byId.get(pageId);
            HoldingState state = page != null ? state(page, stock.stale())
                    : locked.contains(pageId) ? HoldingState.LOCKED : null;
            if (state == null) {
                continue;
            }
            named.add(pageId);
            if (visible(caller, sourceId, spaceKey, pageId)) {
                located.put(new PageKey(sourceId, pageId), new Located(spaceKey, state));
            }
        }
        return named;
    }

    /**
     * Space und Bestand einer Seite ({@link #locate}).
     *
     * @param spaceKey ihr Space
     * @param state    ihr Zustand im Bestand
     */
    public record Located(String spaceKey, HoldingState state) {

        public Located {
            Objects.requireNonNull(spaceKey, "spaceKey");
            Objects.requireNonNull(state, "state");
        }
    }

    /** Die Spaces einer Wurzel: ihr eigener oder alle der Quelle, die der Aufrufer sehen darf. */
    private List<String> spaceKeys(AccessContext caller, Selection.Root root) {
        if (root.spaceKey() != null) {
            return List.of(root.spaceKey());
        }
        access.require(caller, ResourceScope.source(root.sourceId()), AccessKind.METADATA);
        requireSource(root.sourceId());
        return catalog.spaceKeys(root.sourceId()).stream().filter(spaceKey -> access.permits(caller,
                ResourceScope.space(root.sourceId(), spaceKey), AccessKind.METADATA)).toList();
    }

    /**
     * Eine lokal gespeicherte Seite einer Auswahl.
     *
     * @param key        Quelle und Seite
     * @param spaceKey   ihr Space
     * @param rootPageId die Seite der Wurzel, über die sie zur Auswahl gehört, {@code null} für einen ganzen Space bzw.
     *                   eine ganze Quelle
     */
    public record StoredPage(PageKey key, String spaceKey, String rootPageId) {

        public StoredPage {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(spaceKey, "spaceKey");
        }
    }

    /**
     * Die lokal gespeicherten Seiten einer Auswahl (lokal oder veraltet, nie nur aus der Quelle bekannte oder
     * gesperrte), je mit Space und der Wurzel, über die sie dazugehört; liegt eine Seite unter mehreren Wurzeln, gilt
     * die erste. Für die Liste freigegebener Seiten (ADR 0028: der Chat trägt beim Senden nur Lokales ein). Wie
     * {@link #resolve} ohne Quellenzugriff, Seiten ohne Leserecht fehlen.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wie {@link #resolve}
     * @throws UnknownSourceException wie {@link #resolve}
     */
    public List<StoredPage> storedPages(AccessContext caller, Selection selection) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(selection, "selection");
        Set<PageKey> seen = new HashSet<>();
        List<StoredPage> stored = new ArrayList<>();
        for (Selection.Root root : selection.roots()) {
            for (String spaceKey : spaceKeys(caller, root)) {
                for (Map.Entry<String, HoldingState> page : resolve(caller, root.sourceId(), spaceKey, root.pageId())
                        .pages().entrySet()) {
                    HoldingState state = page.getValue();
                    PageKey key = new PageKey(root.sourceId(), page.getKey());
                    if ((state == HoldingState.LOCAL || state == HoldingState.OUTDATED) && seen.add(key)) {
                        stored.add(new StoredPage(key, spaceKey, root.pageId()));
                    }
                }
            }
        }
        return List.copyOf(stored);
    }

    /**
     * Die Ausgaben einer Auswahl aus dem Bestand (ADR 0029, „Ausgabe aus dem Bestand“): je gewähltem Space eine Wurzel
     * für den ganzen Space, eine Quelle steht für ihre Spaces im Bestand, die der Aufrufer sehen darf; dann je
     * gewähltem Teilbaum eine Wurzel, außer er liegt in einem gewählten Space oder unter einem anderen gewählten
     * Teilbaum, denn dann steckt er schon in dessen Ausgabe. Jede Wurzel nennt ihren Space. Erst die Spaces, dann die
     * Teilbäume, je in der Reihenfolge der Auswahl, ohne Doppelte.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wie {@link #resolve}
     * @throws UnknownSourceException wie {@link #resolve}
     */
    public List<Selection.Root> outputs(AccessContext caller, Selection selection) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(selection, "selection");
        Set<Space> wholeSpaces = new LinkedHashSet<>();
        Map<Space, List<String>> subtrees = new LinkedHashMap<>();
        for (Selection.Root root : selection.roots()) {
            if (root.spaceKey() == null) {
                access.require(caller, ResourceScope.source(root.sourceId()), AccessKind.METADATA);
                requireSource(root.sourceId());
                for (String spaceKey : catalog.spaceKeys(root.sourceId())) {
                    if (access.permits(caller, ResourceScope.space(root.sourceId(), spaceKey), AccessKind.METADATA)) {
                        wholeSpaces.add(new Space(root.sourceId(), spaceKey));
                    }
                }
                continue;
            }
            access.require(caller, ResourceScope.space(root.sourceId(), root.spaceKey()), AccessKind.METADATA);
            requireSource(root.sourceId());
            Space space = new Space(root.sourceId(), root.spaceKey());
            if (root.pageId() == null) {
                wholeSpaces.add(space);
            } else {
                List<String> pages = subtrees.computeIfAbsent(space, key -> new ArrayList<>());
                if (!pages.contains(root.pageId())) {
                    pages.add(root.pageId());
                }
            }
        }
        List<Selection.Root> outputs = new ArrayList<>();
        wholeSpaces.forEach(space -> outputs.add(Selection.Root.space(space.sourceId(), space.spaceKey())));
        for (Map.Entry<Space, List<String>> entry : subtrees.entrySet()) {
            Space space = entry.getKey();
            if (wholeSpaces.contains(space)) {
                continue;
            }
            List<String> roots = entry.getValue();
            // Nur bei mehreren Teilbäumen im Space lohnt das Auflösen: eine Seite unter einem anderen gewählten
            // Teilbaum fällt weg.
            Set<String> nested = new HashSet<>();
            if (roots.size() > 1) {
                for (String pageId : roots) {
                    for (String below : resolve(caller, space.sourceId(), space.spaceKey(), pageId).pages().keySet()) {
                        if (!below.equals(pageId) && roots.contains(below)) {
                            nested.add(below);
                        }
                    }
                }
            }
            for (String pageId : roots) {
                if (!nested.contains(pageId)) {
                    outputs.add(Selection.Root.subtree(space.sourceId(), space.spaceKey(), pageId));
                }
            }
        }
        return List.copyOf(outputs);
    }

    /** Eine Wurzel in einem Space: die Seite samt Nachkommen, ohne Seite der ganze Space. */
    private Resolved resolve(AccessContext caller, String sourceId, String spaceKey, String pageId) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
        requireSource(sourceId);
        Optional<HoldingsCatalog.Stock> found = catalog.stock(sourceId, spaceKey);
        if (found.isEmpty()) {
            return new Resolved(new SpaceSummary(sourceId, spaceKey, null, HoldingCounts.ZERO), Map.of(), false);
        }
        HoldingsCatalog.Stock stock = found.get();
        Map<String, HoldingState> states = new LinkedHashMap<>();
        Map<String, String> parents = new HashMap<>();
        visiblePages(caller, sourceId, spaceKey, stock, states, parents);
        HoldingCounts total = HoldingCounts.ZERO;
        for (HoldingState state : states.values()) {
            total = total.plus(HoldingCounts.of(state));
        }
        SpaceSummary summary = new SpaceSummary(sourceId, spaceKey, stock.generation(), total);
        if (pageId == null) {
            return new Resolved(summary, states, true);
        }
        if (!states.containsKey(pageId)) {
            return new Resolved(summary, Map.of(), false);
        }
        // Derselbe Baum wie der Seitenbaum: Zyklen sind aufgebrochen, fehlende Eltern machen Wurzeln.
        List<TreeEntry> entries = new ArrayList<>(states.size());
        parents.forEach((id, parent) -> entries.add(new TreeEntry(id, parent)));
        PageTree.Node start = find(PageTreeBuilder.build(entries).roots(), pageId);
        Map<String, HoldingState> subtree = new LinkedHashMap<>();
        Deque<PageTree.Node> open = new ArrayDeque<>();
        if (start != null) {
            open.push(start);
        }
        while (!open.isEmpty()) {
            PageTree.Node node = open.pop();
            if (subtree.put(node.pageId(), states.get(node.pageId())) == null) {
                node.children().forEach(open::push);
            }
        }
        return new Resolved(summary, subtree, start != null);
    }

    /** Der Knoten einer Seite im Baum, ohne Rekursion. */
    private static PageTree.Node find(List<PageTree.Node> roots, String pageId) {
        Deque<PageTree.Node> open = new ArrayDeque<>(roots);
        while (!open.isEmpty()) {
            PageTree.Node node = open.pop();
            if (node.pageId().equals(pageId)) {
                return node;
            }
            node.children().forEach(open::push);
        }
        return null;
    }

    private record Resolved(SpaceSummary summary, Map<String, HoldingState> pages, boolean found) {
    }

    /**
     * Zustand einer Seite des Katalogs; dieselbe Regel wie die Lücken der Hintergrundfüllung (ADR 0016) und wie die
     * Entscheidung eines Teilbaum-Exports, ob er die Quelle braucht (ADR 0029).
     *
     * @param stale Seiten, die die bestätigte Generation als veraltet führt ({@link HoldingsCatalog.Stock#stale()})
     */
    public static HoldingState state(HoldingsCatalog.CatalogPage page, Set<String> stale) {
        if (!page.loaded()) {
            return HoldingState.REMOTE;
        }
        boolean newer = page.version() != null && page.loadedVersion() != null
                && !page.version().equals(page.loadedVersion());
        return newer || stale.contains(page.pageId()) ? HoldingState.OUTDATED : HoldingState.LOCAL;
    }

    /** Zähler je Teilbaum, ohne Rekursion: erst von oben nach unten ordnen, dann von unten nach oben zusammenzählen. */
    private static void accumulate(PageTree.Node root, Map<String, HoldingState> states,
                                   Map<String, IndexState> indexStates, Map<String, PageHolding> into) {
        List<PageTree.Node> order = new ArrayList<>();
        Map<String, String> parentOf = new HashMap<>();
        Deque<PageTree.Node> open = new ArrayDeque<>();
        open.push(root);
        while (!open.isEmpty()) {
            PageTree.Node node = open.pop();
            order.add(node);
            for (PageTree.Node child : node.children()) {
                parentOf.put(child.pageId(), node.pageId());
                open.push(child);
            }
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            PageTree.Node node = order.get(i);
            HoldingState state = states.get(node.pageId());
            HoldingCounts subtree = HoldingCounts.of(state);
            IndexState indexState = indexStates == null ? null : indexStates.get(node.pageId());
            IndexCoverage coverage = indexState == null ? null : IndexCoverage.of(indexState);
            for (PageTree.Node child : node.children()) {
                PageHolding found = into.get(child.pageId());
                subtree = subtree.plus(found.subtree());
                if (coverage != null && found.indexCoverage() != null) {
                    coverage = coverage.plus(found.indexCoverage());
                }
            }
            into.put(node.pageId(), new PageHolding(node.pageId(), parentOf.get(node.pageId()), state, subtree,
                    indexState, coverage));
        }
    }

    private boolean visible(AccessContext caller, String sourceId, String spaceKey, String pageId) {
        return access.permits(caller, ResourceScope.page(sourceId, spaceKey, pageId), AccessKind.METADATA);
    }

    private void requireSource(String sourceId) {
        if (sources.find(sourceId).isEmpty()) {
            throw new UnknownSourceException(sourceId);
        }
    }

    private record Computed(SpaceSummary summary, Map<String, PageHolding> pages) {
    }

    // --- Meldungen ------------------------------------------------------------------------------------------------

    /**
     * Meldet einen Empfänger für einen Space oder, mit leerem {@code spaceKey}, für alle Spaces der Quelle an. Erst
     * anmelden, dann {@link #overview} lesen, dann geht nichts verloren.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn Space bzw. Quelle nicht für
     *                                                                               {@code metadata} freigegeben sind
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     */
    public Subscription subscribe(AccessContext caller, String sourceId, String spaceKey,
                                  HoldingsListener listener) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(listener, "listener");
        String space = spaceKey == null ? "" : spaceKey;
        access.require(caller, scope(sourceId, space), AccessKind.METADATA);
        requireSource(sourceId);
        return listeners.add(new Subscriber(caller, sourceId, space, listener));
    }

    @Override
    public void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds) {
        mark(sourceId, spaceKey, Mark.Kind.CHANGED, pageIds);
    }

    @Override
    public void loading(String sourceId, String spaceKey, Collection<String> pageIds) {
        mark(sourceId, spaceKey, Mark.Kind.LOADING, pageIds);
    }

    /**
     * Ein Inhaltsabruf beginnt: „lädt“ für die Seite in ihrem Space laut Katalog, ihr Ende als
     * {@link HoldingsListener#pagesChanged}, auch wenn der Abruf scheitert. Ohne Empfänger und für Seiten, die der
     * Katalog nicht kennt (sie stehen in keiner Übersicht), nichts.
     */
    @Override
    public Runnable started(PageKey key) {
        if (listeners.isEmpty()) {
            return NOTHING;
        }
        Optional<String> space = catalog.spaceOf(key.sourceId(), key.pageId());
        if (space.isEmpty() || space.get().isEmpty()) {
            return NOTHING;
        }
        String spaceKey = space.get();
        Set<String> page = Set.of(key.pageId());
        loading(key.sourceId(), spaceKey, page);
        return () -> pagesChanged(key.sourceId(), spaceKey, page);
    }

    @Override
    public void spaceChanged(String sourceId, String spaceKey) {
        if (sourceId != null && !listeners.isEmpty()) {
            notifier.mark(new Space(sourceId, spaceKey == null ? "" : spaceKey), Set.of(Mark.WHOLE));
        }
    }

    @Override
    public void sourceChanged(String sourceId) {
        spaceChanged(sourceId, "");
    }

    private void mark(String sourceId, String spaceKey, Mark.Kind kind, Collection<String> pageIds) {
        if (sourceId == null || pageIds == null || pageIds.isEmpty() || listeners.isEmpty()) {
            return;
        }
        if (spaceKey == null || spaceKey.isEmpty()) {
            // Ohne Space lässt sich keine Seite zuordnen: lieber die ganze Quelle neu lesen lassen.
            sourceChanged(sourceId);
            return;
        }
        List<Mark> marks = new ArrayList<>(pageIds.size());
        for (String pageId : pageIds) {
            if (pageId != null) {
                marks.add(new Mark(kind, pageId));
            }
        }
        notifier.mark(new Space(sourceId, spaceKey), marks);
    }

    /** Auf dem Zustell-Thread: Vormerkungen eines Space an alle passenden Empfänger. */
    private void deliver(Space key, Set<Mark> marks) {
        boolean whole = false;
        Set<String> changed = new LinkedHashSet<>();
        Set<String> loading = new LinkedHashSet<>();
        for (Mark mark : marks) {
            switch (mark.kind()) {
                case WHOLE -> whole = true;
                case CHANGED -> changed.add(mark.pageId());
                case LOADING -> loading.add(mark.pageId());
            }
        }
        boolean entire = whole;
        listeners.deliver(key, subscriber -> subscriber.deliver(key, entire, changed, loading));
    }

    private static ResourceScope scope(String sourceId, String spaceKey) {
        return spaceKey.isEmpty() ? ResourceScope.source(sourceId) : ResourceScope.space(sourceId, spaceKey);
    }

    /** Beendet die Zustellung; noch vorgemerkte Meldungen entfallen. */
    @Override
    public void close() {
        notifier.close();
    }

    /** Quelle und Space einer Vormerkung; ein leerer Space steht für die ganze Quelle. */
    private record Space(String sourceId, String spaceKey) {
    }

    /** Eine Vormerkung: Seite geändert, Laden begonnen oder der ganze Space. */
    private record Mark(Kind kind, String pageId) {

        static final Mark WHOLE = new Mark(Kind.WHOLE, null);

        enum Kind { CHANGED, LOADING, WHOLE }
    }

    /** Ein angemeldeter Empfänger mit seinem Aufrufer und seinem Space ({@code ""}: alle der Quelle). */
    private final class Subscriber {

        private final AccessContext caller;
        private final String sourceId;
        private final String spaceKey;
        private final HoldingsListener listener;

        Subscriber(AccessContext caller, String sourceId, String spaceKey, HoldingsListener listener) {
            this.caller = caller;
            this.sourceId = sourceId;
            this.spaceKey = spaceKey;
            this.listener = listener;
        }

        void deliver(Space key, boolean whole, Set<String> changed, Set<String> loading) {
            if (!key.sourceId().equals(sourceId)) {
                return;
            }
            if (key.spaceKey().isEmpty()) {
                if (access.permits(caller, scope(sourceId, spaceKey), AccessKind.METADATA)) {
                    listener.spaceChanged(sourceId, spaceKey);
                }
                return;
            }
            if (!spaceKey.isEmpty() && !spaceKey.equals(key.spaceKey())
                    || !access.permits(caller, ResourceScope.space(sourceId, key.spaceKey()), AccessKind.METADATA)) {
                return;
            }
            Set<String> starting = visible(key.spaceKey(), loading);
            if (!starting.isEmpty()) {
                listener.loading(sourceId, key.spaceKey(), starting);
            }
            if (whole || changed.size() > MAX_PAGES_PER_MESSAGE) {
                listener.spaceChanged(sourceId, key.spaceKey());
                return;
            }
            Set<String> done = visible(key.spaceKey(), changed);
            if (!done.isEmpty()) {
                listener.pagesChanged(sourceId, key.spaceKey(), done);
            }
        }

        private Set<String> visible(String space, Set<String> pageIds) {
            Set<String> visible = new HashSet<>();
            for (String pageId : pageIds) {
                if (Holdings.this.visible(caller, sourceId, space, pageId)) {
                    visible.add(pageId);
                }
            }
            return Collections.unmodifiableSet(visible);
        }
    }
}
