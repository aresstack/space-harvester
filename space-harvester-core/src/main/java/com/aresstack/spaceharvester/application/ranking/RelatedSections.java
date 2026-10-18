package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.PageSearchIndex;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchScope;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Verwandte Abschnitte (#181, wie {@code KnowledgeRelationsPort} mit {@code IndexKnowledgeRelations} im Enterprise AI
 * Client): die Nachbarschaft eines eingebetteten Stücks, abgeleitet aus dem, was Cache und Vektorablage ohnehin
 * halten. Kein Kantenspeicher, kein neues Embedding, nichts geht an den KI-Dienst; die Kanten entstehen bei der
 * Abfrage:
 * <ul>
 *   <li>{@link Kind#SIMILAR_TO}: Kosinussuche mit dem gespeicherten Vektor des Stücks, ohne das Stück selbst und
 *       seine direkten Nachbarstücke (die teilen sich durch die Überlappung Text), ab der Schwelle.</li>
 *   <li>{@link Kind#PARENT}: die Elternseite, mit ihrem ersten Stück, wenn sie eingebettet ist.</li>
 *   <li>{@link Kind#NEXT}/{@link Kind#PREVIOUS}: das folgende bzw. vorige Stück derselben Seite.</li>
 *   <li>{@link Kind#SAME_SECTION}: weitere Stücke derselben Seite unter derselben Überschriftenkette, die
 *       nächstgelegenen zuerst.</li>
 * </ul>
 * Verweise ({@code LINKS_TO}) kennt der Enterprise AI Client noch nicht; hier fehlen sie ebenso. Jedes Ziel erscheint
 * höchstens einmal (die erste Kante in der Reihenfolge von {@link Kind} gewinnt), je Art höchstens
 * {@link Query#maxPerKind()} Kanten, {@code SIMILAR_TO} absteigend nach Ähnlichkeit.
 *
 * <p>Rechte wie bei der Relevanzsuche: Die Ausgangsseite braucht {@link AccessKind#METADATA} und
 * {@link AccessKind#CONTENT}, sonst gilt sie als nicht im Cache. Jede andere Zielseite braucht beides ebenfalls, denn
 * die Kante stammt aus dem Inhalt; fehlt eines, fehlt die Kante ohne Spur. Mit Live-Prüfung gilt das für den heute bei
 * der Quelle gemeldeten Space; eine nicht prüfbare Zielseite fehlt, und das Ergebnis ist unvollständig.
 */
public final class RelatedSections {

    /** Zusätzliche Kandidaten der Kosinussuche für das Stück selbst und seine beiden Nachbarstücke. */
    private static final int SELF_AND_ADJACENT = 3;

    /** Kandidaten je gewünschter Kante; der Rest deckt gesperrte oder verschwundene Seiten ab. */
    private static final int CANDIDATES_PER_RELATION = 4;

    /** Höchstens so viele Seiten aus der Kosinussuche werden im Suchindex nachgeschlagen. */
    private static final int MAX_LOOKUPS = 60;

    private final SourceCatalog catalog;
    private final PageSearchIndex index;
    private final AccessGuard access;
    private final EnrichmentStore store;
    private final EmbeddingIndex vectors;
    private final EmbeddingProcessor embedding;
    private final LiveCheck live;

    /** Prüft Seiten bei der Quelle; {@code null} heißt weggefallen. */
    interface LiveCheck {

        List<Checked> check(AccessContext caller, List<SearchHit> hits);
    }

    /** Ergebnis der Live-Prüfung einer Seite. */
    record Checked(SearchHit hit, boolean failed) {
    }

    RelatedSections(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, EnrichmentStore store,
                    EmbeddingIndex vectors, EmbeddingProcessor embedding, LiveCheck live) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.access = Objects.requireNonNull(access, "access");
        this.store = Objects.requireNonNull(store, "store");
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.live = Objects.requireNonNull(live, "live");
    }

    /**
     * Die Nachbarschaft eines Stücks.
     *
     * @param chunk Nummer des Stücks in der Seite, ab 0
     * @throws CacheMissException     wenn die Seite nicht im Cache liegt oder nicht sichtbar ist
     * @throws InvalidQueryException  wenn es das Stück nicht gibt
     * @throws SourceAccessException  wenn die Quelle die Ausgangsseite nicht prüfen kann
     */
    Results related(AccessContext caller, String sourceId, String pageId, int chunk, Query query) {
        Objects.requireNonNull(query, "query");
        PageSearchQuery single = PageSearchQuery.of(sourceId, List.of(), null, pageId, null, null, null, null,
                SearchSort.PAGE_ID);
        SearchScope scope = SearchScope.authorize(access, catalog, caller, single);
        scope.requireKnown(catalog);
        SearchIndexState state = scope.state(index).orElseThrow();
        SearchHit seed = index.search(single, scope.sourceIds(), state.generation(), null, 1).stream()
                .filter(hit -> readable(caller, hit)).findFirst()
                .orElseThrow(() -> new CacheMissException(sourceId, "Seite nicht im Cache"));
        Checked checked = live.check(caller, List.of(seed)).getFirst();
        if (checked.failed()) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE,
                    "Kopfdaten der Seite nicht lesbar", null);
        }
        if (checked.hit() == null) {
            throw new CacheMissException(sourceId, "Seite nicht im Cache");
        }
        List<EmbeddingPayload.Chunk> chunks = chunks(seed.page().header().key()).orElse(List.of());
        if (!chunks.isEmpty() && (chunk < 0 || chunk >= chunks.size())) {
            throw new InvalidQueryException("chunk muss zwischen 0 und " + (chunks.size() - 1) + " liegen");
        }
        boolean embedded = !chunks.isEmpty();

        Map<Target, Relation> relations = new LinkedHashMap<>();
        boolean complete = true;
        if (embedded && query.wants(Kind.SIMILAR_TO)) {
            complete &= similar(caller, seed, chunk, chunks.get(chunk), query, relations);
        }
        if (query.wants(Kind.PARENT)) {
            complete &= parent(caller, seed, state, query, relations);
        }
        if (embedded && query.accepts(sourceId)) {
            if (query.wants(Kind.NEXT)) {
                ordinal(seed, chunks, chunk + 1, Kind.NEXT, relations);
            }
            if (query.wants(Kind.PREVIOUS)) {
                ordinal(seed, chunks, chunk - 1, Kind.PREVIOUS, relations);
            }
            if (query.wants(Kind.SAME_SECTION)) {
                sameSection(seed, chunks, chunk, query, relations);
            }
        }
        Section origin = embedded ? section(chunk, chunks.get(chunk)) : null;
        return new Results(seed, origin, new ArrayList<>(relations.values()), complete);
    }

    private boolean similar(AccessContext caller, SearchHit seed, int number, EmbeddingPayload.Chunk origin,
                            Query query, Map<Target, Relation> relations) {
        PageKey key = seed.page().header().key();
        Set<String> sources = similarSources(caller, query);
        if (sources.isEmpty()) {
            return true;
        }
        int k = Math.min(EmbeddingIndex.MAX_K, (query.maxPerKind() + SELF_AND_ADJACENT) * CANDIDATES_PER_RELATION);
        List<EmbeddingIndex.ChunkHit> nearest = vectors.nearest(sources, embedding.version(),
                EmbeddingIndex.normalized(origin.vector()), k);
        Optional<SearchIndexState> state = index.state(sources);
        Map<PageKey, Optional<SearchHit>> pages = new HashMap<>();
        pages.put(key, Optional.of(seed));
        List<Candidate> candidates = new ArrayList<>();
        for (EmbeddingIndex.ChunkHit hit : nearest) {
            if (candidates.size() >= query.maxPerKind() * CANDIDATES_PER_RELATION
                    || hit.similarity() < query.minSimilarity()) {
                break;
            }
            if (hit.page().equals(key) && Math.abs(hit.chunk() - number) <= 1) {
                continue;
            }
            if (!pages.containsKey(hit.page()) && pages.size() > MAX_LOOKUPS) {
                continue;
            }
            Optional<SearchHit> page = pages.computeIfAbsent(hit.page(), target -> state.flatMap(read ->
                    lookup(caller, target, sources, read)));
            page.ifPresent(found -> candidates.add(new Candidate(found, hit)));
        }
        // Zielseiten außer der Ausgangsseite live prüfen, jede einmal.
        Map<PageKey, SearchHit> distinct = new LinkedHashMap<>();
        candidates.forEach(candidate -> {
            if (!candidate.page().page().header().key().equals(key)) {
                distinct.putIfAbsent(candidate.page().page().header().key(), candidate.page());
            }
        });
        List<SearchHit> others = new ArrayList<>(distinct.values());
        List<Checked> checks = others.isEmpty() ? List.of() : live.check(caller, others);
        Set<PageKey> passed = new HashSet<>();
        passed.add(key);
        boolean complete = true;
        for (int i = 0; i < others.size(); i++) {
            complete &= !checks.get(i).failed();
            if (checks.get(i).hit() != null) {
                passed.add(others.get(i).page().header().key());
            }
        }
        int added = 0;
        for (Candidate candidate : candidates) {
            if (added >= query.maxPerKind()) {
                break;
            }
            EmbeddingIndex.ChunkHit hit = candidate.chunk();
            if (!passed.contains(hit.page())) {
                continue;
            }
            String section = sectionOf(hit);
            if (put(relations, key, number, new Relation(Kind.SIMILAR_TO, candidate.page(),
                    new Section(hit.chunk(), hit.charStart(), hit.charEnd(), section), hit.similarity()))) {
                added++;
            }
        }
        return complete;
    }

    /** Die Überschriftenkette eines ähnlichen Stücks aus seinem Ergebnis; die Vektorablage kennt sie nicht. */
    private String sectionOf(EmbeddingIndex.ChunkHit hit) {
        return chunks(hit.page()).filter(found -> hit.chunk() < found.size())
                .map(found -> found.get(hit.chunk()).section()).orElse(null);
    }

    private Set<String> similarSources(AccessContext caller, Query query) {
        SearchScope visible = SearchScope.authorize(access, catalog, caller, PageSearchQuery.of(null,
                List.copyOf(query.sourceIds()), List.of(), null, null, null, null, null, null, SearchSort.TITLE));
        return visible.sourceIds();
    }

    private boolean parent(AccessContext caller, SearchHit seed, SearchIndexState state, Query query,
                           Map<Target, Relation> relations) {
        PageHeader header = seed.page().header();
        String parentId = header.metadata().parentPageId();
        String sourceId = header.key().sourceId();
        if (parentId == null || !query.accepts(sourceId)) {
            return true;
        }
        Optional<SearchHit> parent = lookup(caller, new PageKey(sourceId, parentId), Set.of(sourceId), state);
        if (parent.isEmpty()) {
            return true;
        }
        Checked checked = live.check(caller, List.of(parent.get())).getFirst();
        if (checked.hit() == null) {
            return !checked.failed();
        }
        Section first = chunks(parent.get().page().header().key()).filter(found -> !found.isEmpty())
                .map(found -> section(0, found.getFirst())).orElse(null);
        put(relations, header.key(), -1, new Relation(Kind.PARENT, parent.get(), first, 1.0));
        return true;
    }

    private static void ordinal(SearchHit seed, List<EmbeddingPayload.Chunk> chunks, int number, Kind kind,
                                Map<Target, Relation> relations) {
        if (number >= 0 && number < chunks.size()) {
            put(relations, seed.page().header().key(), -1, new Relation(kind, seed, section(number,
                    chunks.get(number)), 1.0));
        }
    }

    private static void sameSection(SearchHit seed, List<EmbeddingPayload.Chunk> chunks, int origin, Query query,
                                    Map<Target, Relation> relations) {
        String heading = chunks.get(origin).section();
        if (heading == null || heading.isBlank()) {
            return;
        }
        List<Integer> section = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (Math.abs(i - origin) > 1 && heading.equals(chunks.get(i).section())) {
                section.add(i);
            }
        }
        section.sort(Comparator.comparingInt((Integer i) -> Math.abs(i - origin)).thenComparingInt(i -> i));
        int added = 0;
        for (int i : section) {
            if (added >= query.maxPerKind()) {
                return;
            }
            if (put(relations, seed.page().header().key(), origin, new Relation(Kind.SAME_SECTION, seed,
                    section(i, chunks.get(i)), 1.0))) {
                added++;
            }
        }
    }

    /** Erste Kante je Ziel gewinnt; das Ausgangsstück ist nie Ziel. */
    private static boolean put(Map<Target, Relation> relations, PageKey seed, int origin, Relation relation) {
        PageKey page = relation.page().page().header().key();
        Target target = new Target(page, relation.section() == null ? -1 : relation.section().number());
        if (page.equals(seed) && target.chunk() == origin || relations.containsKey(target)) {
            return false;
        }
        relations.put(target, relation);
        return true;
    }

    private Optional<SearchHit> lookup(AccessContext caller, PageKey page, Set<String> sources,
                                       SearchIndexState state) {
        PageSearchQuery single = PageSearchQuery.of(page.sourceId(), List.of(), null, page.pageId(), null, null, null,
                null, SearchSort.PAGE_ID);
        return index.search(single, sources, state.generation(), null, 1).stream()
                .filter(hit -> readable(caller, hit)).findFirst();
    }

    private boolean readable(AccessContext caller, SearchHit hit) {
        PageHeader header = hit.page().header();
        ResourceScope scope = ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId());
        return access.permits(caller, scope, AccessKind.METADATA) && access.permits(caller, scope, AccessKind.CONTENT);
    }

    /** Die Stücke des aktuellen Embedding-Ergebnisses einer Seite. */
    private Optional<List<EmbeddingPayload.Chunk>> chunks(PageKey page) {
        for (Enrichment found : store.find(page)) {
            if ("embedding".equals(found.processorId()) && found.status() == Enrichment.Status.OK
                    && found.processorVersion().equals(embedding.version())) {
                try {
                    return Optional.of(EmbeddingPayload.parse(found.payloadJson()).chunks());
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static Section section(int number, EmbeddingPayload.Chunk chunk) {
        return new Section(number, chunk.charStart(), chunk.charEnd(), chunk.section());
    }

    private record Target(PageKey page, int chunk) {
    }

    private record Candidate(SearchHit page, EmbeddingIndex.ChunkHit chunk) {
    }

    /** Art einer Kante, in der Reihenfolge, in der sie gesucht werden. */
    public enum Kind {
        /** Semantisch ähnlich: Kosinus der gespeicherten Vektoren ab der Schwelle; trägt die Ähnlichkeit. */
        SIMILAR_TO,
        /** Die Elternseite, mit ihrem ersten Stück, wenn sie eingebettet ist. */
        PARENT,
        /** Das folgende Stück derselben Seite. */
        NEXT,
        /** Das vorige Stück derselben Seite. */
        PREVIOUS,
        /** Ein Stück derselben Seite unter derselben Überschriftenkette. */
        SAME_SECTION
    }

    /**
     * Was gesucht wird.
     *
     * @param kinds         gewünschte Arten; leer heißt keine
     * @param maxPerKind    höchstens so viele Kanten je Art, 1 bis 20
     * @param minSimilarity Schwelle für {@link Kind#SIMILAR_TO}, -1 bis 1
     * @param sourceIds     Zielseiten nur aus diesen Quellen; leer heißt alle, die der Aufrufer sehen darf
     */
    public record Query(Set<Kind> kinds, int maxPerKind, double minSimilarity, Set<String> sourceIds) {

        /** Wie im Enterprise AI Client: je Art drei Kanten, Ähnlichkeit ab 0,80. */
        public static final int DEFAULT_MAX_PER_KIND = 3;
        public static final double DEFAULT_MIN_SIMILARITY = 0.80;
        public static final int MAX_PER_KIND = 20;

        public Query {
            kinds = kinds == null || kinds.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(kinds));
            sourceIds = sourceIds == null ? Set.of() : Set.copyOf(sourceIds);
            if (maxPerKind < 1 || maxPerKind > MAX_PER_KIND) {
                throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_PER_KIND + " liegen");
            }
            if (Double.isNaN(minSimilarity) || minSimilarity < -1 || minSimilarity > 1) {
                throw new InvalidQueryException("minSimilarity muss zwischen -1 und 1 liegen");
            }
        }

        /** Alle Arten mit den Standardwerten. */
        public static Query all() {
            return new Query(EnumSet.allOf(Kind.class), DEFAULT_MAX_PER_KIND, DEFAULT_MIN_SIMILARITY, Set.of());
        }

        public Query withKinds(Collection<Kind> value) {
            return new Query(value == null || value.isEmpty() ? Set.of() : EnumSet.copyOf(value), maxPerKind,
                    minSimilarity, sourceIds);
        }

        public boolean wants(Kind kind) {
            return kinds.contains(kind);
        }

        boolean accepts(String sourceId) {
            return sourceIds.isEmpty() || sourceIds.contains(sourceId);
        }
    }

    /**
     * Ein Stück einer Seite.
     *
     * @param number    Nummer in der Seite, ab 0
     * @param charStart erstes Zeichen in der Textprojektion
     * @param charEnd   Ende (exklusiv)
     * @param section   Überschriftenkette oder {@code null}
     */
    public record Section(int number, int charStart, int charEnd, String section) {
    }

    /**
     * Eine Kante.
     *
     * @param page    Zielseite mit den Kopfdaten aus dem Suchindex
     * @param section Zielstück oder {@code null}, wenn die Seite (nur bei {@link Kind#PARENT}) nicht eingebettet ist
     * @param score   Ähnlichkeit bei {@link Kind#SIMILAR_TO}, sonst 1
     */
    public record Relation(Kind kind, SearchHit page, Section section, double score) {

        public Relation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(page, "page");
        }
    }

    /**
     * Die Nachbarschaft.
     *
     * @param seed      Ausgangsseite
     * @param origin    Ausgangsstück oder {@code null}, wenn die Seite (noch) nicht mit dem aktuellen Modell eingebettet
     *                  ist; dann gibt es nur die Elternseite
     * @param complete  {@code false}, wenn eine Zielseite bei der Quelle nicht prüfbar war; sie fehlt dann
     */
    public record Results(SearchHit seed, Section origin, List<Relation> relations, boolean complete) {

        public Results {
            relations = List.copyOf(relations);
        }
    }
}
