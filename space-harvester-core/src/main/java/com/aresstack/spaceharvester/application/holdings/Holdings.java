package com.aresstack.spaceharvester.application.holdings;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.query.TreeTooLargeException;
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
 * <p><b>Meldungen.</b> Schreibwege melden über {@link HoldingsChanges}; hier wird je Space nur vorgemerkt und
 * gebündelt auf dem Zustell-Thread zugestellt ({@link Coalescer}). Ohne Empfänger wird nichts vorgemerkt.
 *
 * <p><b>Rechte.</b> Abfrage und Anmeldung verlangen {@link AccessKind#METADATA} für den Space (bei der Anmeldung für
 * alle Spaces: für die Quelle); Seiten ohne Freigabe fehlen in Übersicht und Meldungen, wie im Seitenbaum.
 */
public final class Holdings implements HoldingsChanges, AutoCloseable {

    /** Höchstens so viele Seiten nennt eine Meldung {@link HoldingsListener#pagesChanged}; sonst kommt der Space. */
    public static final int MAX_PAGES_PER_MESSAGE = 1000;

    private final SourceCatalog sources;
    private final HoldingsCatalog catalog;
    private final AccessGuard access;
    private final int maxTreeNodes;
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
        HoldingCounts total = HoldingCounts.ZERO;
        for (HoldingState state : states.values()) {
            total = total.plus(HoldingCounts.of(state));
        }
        SpaceSummary summary = new SpaceSummary(sourceId, spaceKey, stock.generation(), total);
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
            accumulate(root, states, pages);
        }
        return new Computed(summary, pages);
    }

    /** Zustand einer Seite des Katalogs; dieselbe Regel wie die Lücken der Hintergrundfüllung (ADR 0016). */
    static HoldingState state(HoldingsCatalog.CatalogPage page, Set<String> stale) {
        if (!page.loaded()) {
            return HoldingState.REMOTE;
        }
        boolean newer = page.version() != null && page.loadedVersion() != null
                && !page.version().equals(page.loadedVersion());
        return newer || stale.contains(page.pageId()) ? HoldingState.OUTDATED : HoldingState.LOCAL;
    }

    /** Zähler je Teilbaum, ohne Rekursion: erst von oben nach unten ordnen, dann von unten nach oben zusammenzählen. */
    private static void accumulate(PageTree.Node root, Map<String, HoldingState> states,
                                   Map<String, PageHolding> into) {
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
            for (PageTree.Node child : node.children()) {
                subtree = subtree.plus(into.get(child.pageId()).subtree());
            }
            into.put(node.pageId(), new PageHolding(node.pageId(), parentOf.get(node.pageId()), state, subtree));
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
