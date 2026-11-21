package com.aresstack.spaceharvester.application.structure;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.PageSearchIndex;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchScope;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Anwendungsfall „Zusammenhänge einer Seite“ (Strukturgraph, Paket G1): Elternseite und Unterseiten, Verweise in beide
 * Richtungen und bestätigte Begriffsbeziehungen, jede Kante mit Art, Grundlage und Herkunft. Abgeleitet bei der
 * Abfrage aus dem lokalen Suchindex, dem Verzeichnis der Verweise und dem Begriffsindex; nichts wird gespeichert,
 * nichts geht an einen KI-Dienst, Aussagen werden nicht gelesen. Ergänzt den Wissensgraphen der OO-Analyse
 * ({@code application.knowledge}), ersetzt ihn nicht.
 *
 * <p>Rechte: Die Ausgangsseite braucht {@link AccessKind#METADATA} und {@link AccessKind#CONTENT}, sonst gilt sie als
 * nicht im Cache. Ziele aus Hierarchie und Verweisen brauchen {@code metadata} (nur Titel und Lage), Ziele aus
 * Begriffsbeziehungen den Inhalt, denn die Fundstelle stammt aus ihm. Was fehlt, fehlt ohne Spur: in Kanten, Zählern,
 * {@link Connections#truncated()} und {@link Connections#coverage()}.
 *
 * <p>Grenzen: je Art höchstens {@link Query#maxPerKind()}, insgesamt {@link Query#maxEdges()} Kanten; eine Art, von der
 * etwas weggelassen wurde, steht in {@link Connections#truncated()}. Dazu hat jede Art ein festes Budget an Lesezugriffen
 * (siehe {@link #MAX_SCAN}, {@link #MAX_TERM_RELATIONS}, {@link #MAX_TERM_LOOKUPS}), unabhängig von
 * {@code maxPerKind}; wird es erreicht, gilt die Art ebenfalls als gekürzt. Die Reihenfolge folgt
 * {@link ConnectionKind}, innerhalb einer Art nach Titel, bei gekürzten Arten nur innerhalb des Gelesenen.
 */
public class PageConnections {

    /**
     * Höchstens so viele Einträge liest {@link ConnectionKind#CHILD} aus dem Index (sichtbar oder nicht). Wird die
     * Grenze erreicht, steht die Art in {@link Connections#truncated()} und ist nur {@link Coverage#PARTIAL}.
     */
    static final int MAX_SCAN = 1_000;
    /** Höchstens so viele Begriffe der Ausgangsseite werden betrachtet; darüber gilt die Art als gekürzt. */
    static final int MAX_SEED_TERMS = 1_000;
    /**
     * Budget von {@link ConnectionKind#TERM_RELATION}: höchstens so viele bestätigte Beziehungen werden verfolgt, in
     * der Reihenfolge von {@code TermRelations#forTerms} (nach Schlüssel). Darüber gilt die Art als gekürzt.
     */
    static final int MAX_TERM_RELATIONS = 50;
    /** Budget von {@link ConnectionKind#TERM_RELATION}: höchstens so viele Fundstellen je Gegenbegriff. */
    static final int MAX_OCCURRENCES = TermQueries.MAX_LIMIT;
    /**
     * Budget von {@link ConnectionKind#TERM_RELATION}: höchstens so viele verschiedene Zielseiten werden im Index
     * nachgeschlagen. Darüber gilt die Art als gekürzt. Die Ordnung nach Titel gilt dann nur innerhalb des Gelesenen.
     */
    static final int MAX_TERM_LOOKUPS = 200;

    private static final int BATCH = 100;

    /** Aufwandsgrenzen; in Tests kleiner. */
    record Budget(int scan, int seedTerms, int termRelations, int occurrences, int termLookups) {

        static final Budget DEFAULT = new Budget(MAX_SCAN, MAX_SEED_TERMS, MAX_TERM_RELATIONS, MAX_OCCURRENCES,
                MAX_TERM_LOOKUPS);
    }

    private final SourceCatalog catalog;
    private final PageSearchIndex index;
    private final AccessGuard access;
    private final LinkDirectory links;
    private final TermQueries terms;
    private final TermRelations relations;
    private final Budget budget;

    /**
     * @param links     Verzeichnis der Verweise oder {@link LinkDirectory#NONE}
     * @param terms     Begriffsindex oder {@code null}, wenn keiner eingerichtet ist
     * @param relations Begriffsbeziehungen oder {@code null}, wenn keine eingerichtet sind
     */
    public PageConnections(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, LinkDirectory links,
                           TermQueries terms, TermRelations relations) {
        this(catalog, index, access, links, terms, relations, Budget.DEFAULT);
    }

    PageConnections(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, LinkDirectory links,
                    TermQueries terms, TermRelations relations, Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.access = Objects.requireNonNull(access, "access");
        this.links = Objects.requireNonNull(links, "links");
        this.terms = terms;
        this.relations = relations;
    }

    /**
     * Die Zusammenhänge einer Seite.
     *
     * @throws CacheMissException    wenn die Seite nicht im lokalen Index liegt oder nicht sichtbar ist
     * @throws com.aresstack.spaceharvester.application.sources.UnknownSourceException bei unbekannter Quelle
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne {@code metadata} auf die
     *                               Quelle
     */
    public Connections connections(AccessContext caller, String sourceId, String pageId, Query query) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(query, "query");
        PageSearchQuery single = byId(new PageKey(sourceId, pageId));
        SearchScope scope = SearchScope.authorize(access, catalog, caller, single);
        scope.requireKnown(catalog);
        SearchIndexState state = scope.state(index).orElseThrow();
        Run run = new Run(caller, sourceId, state, query);
        PageHeader seed = run.lookup(new PageKey(sourceId, pageId), true)
                .orElseThrow(() -> new CacheMissException(sourceId, "Seite nicht im Cache"));

        if (query.wants(ConnectionKind.PARENT)) {
            run.parent(seed);
        }
        if (query.wants(ConnectionKind.CHILD)) {
            run.children(seed);
        }
        if (query.wants(ConnectionKind.LINKS_TO) || query.wants(ConnectionKind.LINKED_FROM)) {
            run.links(seed);
        }
        if (query.wants(ConnectionKind.TERM_RELATION)) {
            run.termRelations(seed);
        }
        return run.result(ref(seed));
    }

    /** Eine Abfrage mit ihren Zwischenständen. */
    private final class Run {

        private final AccessContext caller;
        private final String sourceId;
        private final SearchIndexState state;
        private final Query query;
        private final Map<ConnectionKind, List<Connection>> found = new EnumMap<>(ConnectionKind.class);
        private final Map<ConnectionKind, Coverage> coverage = new EnumMap<>(ConnectionKind.class);
        private final Set<ConnectionKind> truncated = EnumSet.noneOf(ConnectionKind.class);
        private final Map<PageKey, Optional<PageHeader>> metadataOnly = new HashMap<>();
        private final Map<PageKey, Optional<PageHeader>> withContent = new HashMap<>();
        private final Set<PageKey> considered = new LinkedHashSet<>();
        private int termLookups;

        Run(AccessContext caller, String sourceId, SearchIndexState state, Query query) {
            this.caller = caller;
            this.sourceId = sourceId;
            this.state = state;
            this.query = query;
        }

        void parent(PageHeader seed) {
            String parentId = seed.metadata().parentPageId();
            if (parentId == null || parentId.isBlank()) {
                coverage.put(ConnectionKind.PARENT, Coverage.COMPLETE);
                return;
            }
            Optional<PageHeader> parent = lookup(new PageKey(seed.key().sourceId(), parentId), false);
            coverage.put(ConnectionKind.PARENT, parent.isPresent() ? Coverage.COMPLETE : Coverage.PARTIAL);
            parent.ifPresent(found -> add(seed, ConnectionKind.PARENT, found, new Provenance.Hierarchy()));
        }

        void children(PageHeader seed) {
            PageSearchQuery byParent = PageSearchQuery.of(seed.key().sourceId(), List.of(), null, null,
                    seed.key().pageId(), null, null, null, SearchSort.TITLE);
            List<PageHeader> visible = new ArrayList<>();
            String after = null;
            int scanned = 0;
            boolean more = true;
            while (more && visible.size() <= query.maxPerKind()) {
                if (scanned >= budget.scan()) {
                    truncated.add(ConnectionKind.CHILD);
                    break;
                }
                int requested = Math.min(BATCH, budget.scan() - scanned);
                List<SearchHit> batch = index.search(byParent, Set.of(seed.key().sourceId()), state.generation(),
                        after, requested);
                for (SearchHit hit : batch) {
                    scanned++;
                    PageHeader header = hit.page().header();
                    if (!header.key().equals(seed.key()) && permits(header, AccessKind.METADATA)) {
                        visible.add(header);
                    }
                }
                more = !batch.isEmpty() && batch.size() == requested;
                if (more) {
                    after = batch.getLast().position();
                }
            }
            coverage.put(ConnectionKind.CHILD, truncated.contains(ConnectionKind.CHILD) ? Coverage.PARTIAL
                    : Coverage.COMPLETE);
            visible.forEach(child -> add(seed, ConnectionKind.CHILD, child, new Provenance.Hierarchy()));
        }

        void links(PageHeader seed) {
            Optional<LinkDirectory.Links> known = links.links(caller, seed.key());
            if (known.isEmpty()) {
                markUnavailable(ConnectionKind.LINKS_TO);
                markUnavailable(ConnectionKind.LINKED_FROM);
                return;
            }
            linkTargets(seed, ConnectionKind.LINKS_TO, known.get().outgoing(), known.get().complete());
            linkTargets(seed, ConnectionKind.LINKED_FROM, known.get().incoming(), known.get().complete());
        }

        private void linkTargets(PageHeader seed, ConnectionKind kind, List<PageKey> targets, boolean complete) {
            if (!query.wants(kind)) {
                return;
            }
            boolean allLocal = true;
            int scanned = 0;
            for (PageKey target : new LinkedHashSet<>(targets)) {
                if (target.equals(seed.key())) {
                    continue;
                }
                if (scanned++ >= budget.scan()) {
                    truncated.add(kind);
                    break;
                }
                Optional<PageHeader> header = lookup(target, false);
                if (header.isEmpty()) {
                    allLocal = false;
                    continue;
                }
                add(seed, kind, header.get(), new Provenance.Link());
            }
            coverage.put(kind, complete && allLocal ? Coverage.COMPLETE : Coverage.PARTIAL);
        }

        void termRelations(PageHeader seed) {
            if (terms == null || relations == null) {
                markUnavailable(ConnectionKind.TERM_RELATION);
                return;
            }
            String sourceId = seed.key().sourceId();
            String spaceKey = seed.metadata().partitionKey();
            Set<String> seedTerms = seedTerms(seed);
            if (seedTerms.isEmpty()) {
                markUnavailable(ConnectionKind.TERM_RELATION);
                return;
            }
            coverage.put(ConnectionKind.TERM_RELATION, Coverage.COMPLETE);
            int followed = 0;
            for (TermRelation relation : relations.forTerms(caller, seedTerms, sourceId, spaceKey)) {
                if (relation.status() != ReviewStatus.CONFIRMED) {
                    continue;
                }
                if (followed++ >= budget.termRelations() || termLookups >= budget.termLookups()) {
                    truncated.add(ConnectionKind.TERM_RELATION);
                    break;
                }
                String from = TermText.normalize(relation.fromTerm());
                String to = TermText.normalize(relation.toTerm());
                if (seedTerms.contains(from)) {
                    counterparts(seed, relation, from, to);
                }
                if (seedTerms.contains(to) && !to.equals(from)) {
                    counterparts(seed, relation, to, from);
                }
            }
        }

        /** Seiten, auf denen das andere Ende vorkommt, außer der Ausgangsseite. */
        private void counterparts(PageHeader seed, TermRelation relation, String seedTerm, String targetTerm) {
            Set<PageKey> pages = new LinkedHashSet<>();
            TermQueries.OccurrenceList list = terms.occurrences(caller, new TermQueries.OccurrenceSearch(
                    targetTerm, null, relation.scope().sourceId(), relation.scope().spaceKey(),
                    Math.min(budget.occurrences(), TermQueries.MAX_LIMIT), null, 0));
            for (TermQueries.Occurrence occurrence : list.occurrences()) {
                pages.add(new PageKey(occurrence.sourceId(), occurrence.pageId()));
            }
            if (list.nextCursor() != null) {
                truncated.add(ConnectionKind.TERM_RELATION);
            }
            Provenance.TermLink provenance = new Provenance.TermLink(relation.id(), relation.type(),
                    relation.fromTerm(), relation.toTerm(), relation.scope().sourceId(),
                    relation.scope().spaceKey(), relation.origin(), seedTerm, targetTerm);
            for (PageKey page : pages) {
                if (page.equals(seed.key())) {
                    continue;
                }
                if (!withContent.containsKey(page)) {
                    if (termLookups >= budget.termLookups()) {
                        truncated.add(ConnectionKind.TERM_RELATION);
                        return;
                    }
                    termLookups++;
                }
                lookup(page, true).ifPresent(header -> add(seed, ConnectionKind.TERM_RELATION, header, provenance));
            }
        }

        /** Suchwerte der aktuellen Begriffe der Ausgangsseite. */
        private Set<String> seedTerms(PageHeader seed) {
            Set<String> found = new LinkedHashSet<>();
            PageSet only = PageSet.ofKeys(List.of(seed.key()));
            String cursor = null;
            do {
                TermQueries.TermList list = terms.terms(caller, new TermQueries.TermSearch(seed.key().sourceId(),
                        null, null, null, false, TermQueries.MAX_LIMIT, cursor).within(only));
                list.terms().forEach(term -> found.add(term.normalized()));
                cursor = list.nextCursor();
            } while (cursor != null && found.size() < budget.seedTerms());
            if (cursor != null) {
                truncated.add(ConnectionKind.TERM_RELATION);
            }
            return found;
        }

        private void markUnavailable(ConnectionKind kind) {
            if (query.wants(kind)) {
                coverage.put(kind, Coverage.UNAVAILABLE);
            }
        }

        private void add(PageHeader seed, ConnectionKind kind, PageHeader target, Provenance provenance) {
            found.computeIfAbsent(kind, ignored -> new ArrayList<>())
                    .add(new Connection(seed.key(), kind, ref(target), provenance));
        }

        /**
         * Kopfdaten einer lokal gespeicherten, sichtbaren Seite.
         *
         * @param content ob auch der Inhalt lesbar sein muss
         */
        Optional<PageHeader> lookup(PageKey key, boolean content) {
            Map<PageKey, Optional<PageHeader>> cache = content ? withContent : metadataOnly;
            return cache.computeIfAbsent(key, page -> {
                if (catalog.find(page.sourceId()).isEmpty()) {
                    return Optional.empty();
                }
                Optional<SearchIndexState> sourceState = page.sourceId().equals(sourceId) ? Optional.of(state)
                        : index.state(page.sourceId());
                return sourceState.flatMap(read -> index.search(byId(page), Set.of(page.sourceId()),
                                read.generation(), null, 1).stream().findFirst())
                        .map(hit -> hit.page().header())
                        .filter(header -> permits(header, AccessKind.METADATA)
                                && (!content || permits(header, AccessKind.CONTENT)));
            });
        }

        private boolean permits(PageHeader header, AccessKind kind) {
            ResourceScope scope = ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                    header.key().pageId());
            boolean permitted = access.permits(caller, scope, kind);
            if (permitted) {
                considered.add(header.key());
            }
            return permitted;
        }

        Connections result(PageRef seed) {
            List<Connection> edges = new ArrayList<>();
            for (ConnectionKind kind : ConnectionKind.values()) {
                List<Connection> ofKind = new ArrayList<>(dedupe(found.getOrDefault(kind, List.of())));
                ofKind.sort(Comparator.comparing((Connection edge) -> edge.target().title(),
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(edge -> edge.target().key().pageId()));
                if (ofKind.size() > query.maxPerKind()) {
                    ofKind = ofKind.subList(0, query.maxPerKind());
                    truncated.add(kind);
                }
                int room = query.maxEdges() - edges.size();
                if (ofKind.size() > room) {
                    ofKind = ofKind.subList(0, Math.max(room, 0));
                    truncated.add(kind);
                }
                edges.addAll(ofKind);
            }
            return new Connections(seed, edges, coverage, truncated, considered.size());
        }

        /** Dieselbe Art, dasselbe Ziel, dieselbe Herkunft nur einmal. */
        private Collection<Connection> dedupe(List<Connection> edges) {
            Map<List<Object>, Connection> distinct = new LinkedHashMap<>();
            edges.forEach(edge -> distinct.putIfAbsent(List.of(edge.target().key(), edge.provenance()), edge));
            return distinct.values();
        }
    }

    private static PageSearchQuery byId(PageKey page) {
        return PageSearchQuery.of(page.sourceId(), List.of(), null, page.pageId(), null, null, null, null,
                SearchSort.PAGE_ID);
    }

    private static PageRef ref(PageHeader header) {
        return new PageRef(header.key(), header.metadata().title(), header.metadata().partitionKey());
    }

    /**
     * Was gesucht wird.
     *
     * @param kinds      gewünschte Arten; leer heißt keine
     * @param maxPerKind höchstens so viele Kanten je Art, 1 bis {@link #MAX_PER_KIND}
     * @param maxEdges   höchstens so viele Kanten insgesamt, 1 bis {@link #MAX_EDGES}
     */
    public record Query(Set<ConnectionKind> kinds, int maxPerKind, int maxEdges) {

        public static final int DEFAULT_MAX_PER_KIND = 10;
        public static final int MAX_PER_KIND = 50;
        public static final int DEFAULT_MAX_EDGES = 200;
        public static final int MAX_EDGES = 500;

        public Query {
            kinds = kinds == null || kinds.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(kinds));
            if (maxPerKind < 1 || maxPerKind > MAX_PER_KIND) {
                throw new InvalidQueryException("maxPerKind muss zwischen 1 und " + MAX_PER_KIND + " liegen");
            }
            if (maxEdges < 1 || maxEdges > MAX_EDGES) {
                throw new InvalidQueryException("maxEdges muss zwischen 1 und " + MAX_EDGES + " liegen");
            }
        }

        /** Alle Arten mit den Standardgrenzen. */
        public static Query all() {
            return new Query(EnumSet.allOf(ConnectionKind.class), DEFAULT_MAX_PER_KIND, DEFAULT_MAX_EDGES);
        }

        public Query withKinds(Collection<ConnectionKind> value) {
            return new Query(value == null || value.isEmpty() ? Set.of() : EnumSet.copyOf(value), maxPerKind,
                    maxEdges);
        }

        public boolean wants(ConnectionKind kind) {
            return kinds.contains(kind);
        }
    }

    /**
     * Die Zusammenhänge.
     *
     * @param seed            Ausgangsseite
     * @param edges           Kanten in der Reihenfolge von {@link ConnectionKind}, je Art nach Titel
     * @param coverage        je gewünschter Art, wie vollständig sie aus dem lokalen Bestand beantwortet ist
     *                        ({@link Coverage})
     * @param truncated       Arten, von denen wegen einer Grenze etwas weggelassen wurde
     * @param pagesConsidered Zahl der verschiedenen lokal gespeicherten, für den Aufrufer sichtbaren Seiten, die für die
     *                        Antwort gelesen wurden, die Ausgangsseite eingeschlossen
     */
    public record Connections(PageRef seed, List<Connection> edges, Map<ConnectionKind, Coverage> coverage,
                              Set<ConnectionKind> truncated, int pagesConsidered) {

        public Connections {
            Objects.requireNonNull(seed, "seed");
            edges = List.copyOf(edges);
            coverage = coverage.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(coverage));
            truncated = truncated.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(truncated));
        }

        /** Die Kanten einer Art. */
        public List<Connection> of(ConnectionKind kind) {
            return edges.stream().filter(edge -> edge.kind() == kind).toList();
        }

        /** Ob jede gewünschte Art vollständig ist. */
        public boolean complete() {
            return coverage.values().stream().allMatch(value -> value == Coverage.COMPLETE);
        }
    }
}
