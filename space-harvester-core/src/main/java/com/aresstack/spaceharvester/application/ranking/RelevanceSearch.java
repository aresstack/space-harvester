package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.query.SearchScope;
import com.aresstack.spaceharvester.application.query.PageSearchIndex;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>Experimentell</b> (Angelo, 18.10.): Die Suche ist die Schlagwortsuche über die Quellen
 * ({@link com.aresstack.spaceharvester.application.query.KeywordSearch}). Diese Klasse ist die semantische Suche der
 * Checkbox „Semantische Suche (experimentell)“ und bleibt der einzige Weg dafür ({@code sort=relevance}, kein eigener
 * Endpunkt); ihr Umbau auf einen externen Embedding-Dienst steht in ADR 0011.
 *
 * <p>Suche nach Relevanz ({@code sort=relevance}, #89, ADR 0004): ein Fenster von höchstens {@value Reranking#MAX_WINDOW}
 * freigegebenen Kandidaten aus Vektorsuche und Titelsuche, umgeordnet nach der Kosinusähnlichkeit der Anfrage zu den
 * gespeicherten Embeddings.
 *
 * <p>Umfang (#114): mit {@code sourceId} eine Quelle, ohne alle Quellen, die der Aufrufer sehen darf
 * ({@link SearchScope}), mit derselben Fehlersemantik wie {@code PageSearch}. Global gibt es genau eine Einbettung der
 * Anfrage und eine Vektorsuche über alle Quellen des Umfangs zusammen (höchstens {@value #MAX_CHUNKS} Stücke, dann
 * höchstens {@value #MAX_LOOKUPS} Seiten), dazu die ersten Titeltreffer in globaler Titelreihenfolge. Das Fenster bleibt
 * bei {@value Reranking#MAX_WINDOW} Kandidaten: ein begrenzter, global gerankter Block, kein vollständiges Ergebnis und
 * keine Top-K je Quelle. Kandidaten sind Seiten ({@code sourceId + pageId}); gleiche Seiten-IDs verschiedener Quellen
 * bleiben getrennt. Ähnlichkeiten stammen aus demselben Modell und sind über Quellen vergleichbar.
 *
 * <p>Rechte: wie {@code PageSearch} {@link AccessKind#METADATA} für die genannten Spaces bzw. die Quelle und für jeden
 * Treffer. Ähnlichkeiten sind aus dem Inhalt abgeleitet: Ein Kandidat aus der Vektorsuche braucht zusätzlich
 * {@link AccessKind#CONTENT} für seine Seite, sonst fällt er ohne Spur weg; ein Titeltreffer ohne {@code content}
 * bleibt, bekommt aber keine Ähnlichkeit und steht hinter allen Treffern mit Ähnlichkeit. Der Reranker sieht nur
 * diese freigegebenen Treffer und darf sie nur umordnen ({@link Reranking}).
 *
 * <p>Alle Filter der Anfrage gelten für jeden Kandidaten, auch aus der Vektorsuche; jeder Treffer trägt die aktuellen
 * Kopfdaten aus dem Suchindex. Ein Treffer, dessen Embedding aus einer älteren Revision stammt, heißt
 * {@link Freshness#STALE}.
 */
public class RelevanceSearch {

    /** Höchstens so viele Stücke liefert die Vektorsuche je Anfrage als Kandidaten. */
    static final int MAX_CHUNKS = EmbeddingIndex.MAX_K;

    /** Höchstens so viele Seiten aus der Vektorsuche werden im Suchindex nachgeschlagen. */
    static final int MAX_LOOKUPS = 3 * Reranking.MAX_WINDOW;

    /** Längster Anfragetext. */
    public static final int MAX_TEXT_LENGTH = 500;

    private static final System.Logger LOG = System.getLogger(RelevanceSearch.class.getName());

    private final SourceCatalog catalog;
    private final PageSearchIndex index;
    private final AccessGuard access;
    private final EnrichmentStore store;
    private final EmbeddingIndex vectors;
    private final EmbeddingProcessor embedding;

    /**
     * @param store     Ablage der Embeddings oder {@code null}
     * @param vectors   Vektorsuche über diese Ablage oder {@code null}
     * @param embedding Prozessor des eingerichteten Modells oder {@code null}, wenn keines eingerichtet ist; dann
     *                  bleibt nur die Titelsuche in der Reihenfolge des Index
     */
    public RelevanceSearch(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, EnrichmentStore store,
                           EmbeddingIndex vectors, EmbeddingProcessor embedding) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.access = Objects.requireNonNull(access, "access");
        boolean semantic = store != null && vectors != null && embedding != null;
        this.store = semantic ? store : null;
        this.vectors = semantic ? vectors : null;
        this.embedding = semantic ? embedding : null;
    }

    /** Ob Anfragen ohne Titelwörter beantwortet werden können. */
    public boolean semantic() {
        return embedding != null;
    }

    /**
     * Die relevantesten Treffer.
     *
     * @param query Filter und Titelwörter; {@link PageSearchQuery#sort()} wird ignoriert
     * @param text  Anfragetext für die Ähnlichkeit oder {@code null}, dann die Titelwörter
     * @param limit 1 bis {@value Reranking#MAX_WINDOW}
     * @throws AccessDeniedException                wenn ein genannter Space oder die Quelle nicht für {@code metadata}
     *                                              freigegeben ist
     * @throws InvalidQueryException                ohne Anfragetext und Titelwörter, bei zu langem Text oder Limit
     * @throws SemanticSearchUnavailableException   wenn es keine Titelwörter gibt und kein Modell eingerichtet oder
     *                                              erreichbar ist
     */
    public RelevanceResults search(AccessContext caller, PageSearchQuery query, String text, int limit) {
        SearchScope scope = SearchScope.authorize(access, catalog, caller, query);
        if (limit < 1 || limit > Reranking.MAX_WINDOW) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + Reranking.MAX_WINDOW + " liegen");
        }
        String queryText = text == null || text.isBlank() ? String.join(" ", query.titleTerms()) : text.strip();
        if (queryText.isEmpty()) {
            throw new InvalidQueryException("sort=relevance braucht q oder title");
        }
        if (queryText.length() > MAX_TEXT_LENGTH) {
            throw new InvalidQueryException("q hat höchstens " + MAX_TEXT_LENGTH + " Zeichen");
        }
        scope.requireKnown(catalog);
        SearchIndexState state = scope.state(index).orElse(null);
        if (state == null && query.titleTerms().isEmpty() && embedding == null) {
            throw new SemanticSearchUnavailableException(
                    "Für die Suche ohne title ist kein Embedding-Modell eingerichtet");
        }
        if (state == null) {
            // Nur global: keine lesbare Quelle im Index.
            return new RelevanceResults(null, null, SearchReranker.NONE.id(), List.of());
        }

        float[] vector = queryVector(queryText);
        if (vector == null && query.titleTerms().isEmpty()) {
            throw new SemanticSearchUnavailableException(embedding == null
                    ? "Für die Suche ohne title ist kein Embedding-Modell eingerichtet"
                    : "Das Embedding-Modell ist gerade nicht verfügbar");
        }
        List<SearchHit> semantic = vector == null ? List.of()
                : semanticCandidates(caller, query, scope, state, vector);
        List<SearchHit> lexical = query.titleTerms().isEmpty() ? List.of()
                : lexicalCandidates(caller, query, scope, state);
        List<SearchHit> window = interleave(semantic, lexical);

        Map<SearchHit, Score> scores = new IdentityHashMap<>();
        if (vector != null) {
            for (SearchHit hit : window) {
                Score score = score(caller, hit, vector);
                if (score != null) {
                    scores.put(hit, score);
                }
            }
        }
        SearchReranker reranker = vector == null ? SearchReranker.NONE
                : new CosineReranker("embedding-cosine@" + embedding.version(), hit -> {
                    Score score = scores.get(hit);
                    return score == null ? null : score.similarity();
                });
        Reranking reranking = new Reranking(reranker);
        List<SearchHit> ordered = reranking.apply(query, window);
        List<RankedHit> hits = new ArrayList<>(Math.min(limit, ordered.size()));
        for (SearchHit hit : ordered.subList(0, Math.min(limit, ordered.size()))) {
            Score score = scores.get(hit);
            hits.add(score == null ? new RankedHit(hit, null)
                    : new RankedHit(hit, new Relevance(score.similarity(), score.charStart(), score.charEnd(),
                            score.freshness())));
        }
        return new RelevanceResults(query.sourceId(), state, reranking.rerankerId(), hits);
    }

    private float[] queryVector(String text) {
        if (embedding == null) {
            return null;
        }
        try {
            return EmbeddingIndex.normalized(embedding.embedQuery(text));
        } catch (EmbeddingUnavailableException e) {
            // Kein Anfragetext im Log.
            LOG.log(System.Logger.Level.WARNING, "Anfrage nicht eingebettet ({0}); nur Titelsuche",
                    e.getClass().getName());
            return null;
        }
    }

    /** Seiten der ähnlichsten Stücke, nach bester Ähnlichkeit, gefiltert nach Anfrage und Rechten. */
    private List<SearchHit> semanticCandidates(AccessContext caller, PageSearchQuery query, SearchScope scope,
                                               SearchIndexState state, float[] vector) {
        Set<PageKey> pages = new LinkedHashSet<>();
        for (EmbeddingIndex.ChunkHit chunk : vectors.nearest(scope.sourceIds(), embedding.version(), vector,
                MAX_CHUNKS)) {
            pages.add(chunk.page());
        }
        List<SearchHit> found = new ArrayList<>();
        int lookups = 0;
        for (PageKey page : pages) {
            if (found.size() == Reranking.MAX_WINDOW || lookups++ == MAX_LOOKUPS) {
                break;
            }
            // Die Vektorsuche kennt nur Quellen des Umfangs; jede Bedingung der Anfrage gilt trotzdem.
            if (!scope.sourceIds().contains(page.sourceId())
                    || query.sourceId() != null && !query.sourceId().equals(page.sourceId())
                    || query.pageId() != null && !query.pageId().equals(page.pageId())) {
                continue;
            }
            PageSearchQuery single = query.with(page.sourceId(), page.pageId(), SearchSort.PAGE_ID);
            for (SearchHit hit : index.search(single, scope.sourceIds(), state.generation(), null, 1)) {
                if (permits(caller, hit, AccessKind.METADATA) && permits(caller, hit, AccessKind.CONTENT)) {
                    found.add(hit);
                }
            }
        }
        return found;
    }

    /** Die ersten sichtbaren Titeltreffer in der Reihenfolge des Titels, über alle Quellen des Umfangs. */
    private List<SearchHit> lexicalCandidates(AccessContext caller, PageSearchQuery query, SearchScope scope,
                                              SearchIndexState state) {
        PageSearchQuery byTitle = query.with(query.sourceId(), query.pageId(), SearchSort.TITLE);
        List<SearchHit> visible = new ArrayList<>();
        String after = null;
        boolean exhausted = false;
        while (visible.size() < Reranking.MAX_WINDOW && !exhausted) {
            List<SearchHit> batch = index.search(byTitle, scope.sourceIds(), state.generation(), after,
                    Reranking.MAX_WINDOW);
            for (SearchHit hit : batch) {
                after = hit.position();
                if (permits(caller, hit, AccessKind.METADATA)) {
                    visible.add(hit);
                    if (visible.size() == Reranking.MAX_WINDOW) {
                        break;
                    }
                }
            }
            exhausted = batch.size() < Reranking.MAX_WINDOW;
        }
        return visible;
    }

    /**
     * Abwechselnd aus beiden Listen, jede Seite einmal, höchstens {@value Reranking#MAX_WINDOW}. Eine Seite ist
     * {@code sourceId + pageId}: Gleiche Seiten-IDs verschiedener Quellen bleiben getrennte Kandidaten (#114).
     */
    static List<SearchHit> interleave(List<SearchHit> first, List<SearchHit> second) {
        Map<PageKey, SearchHit> window = new LinkedHashMap<>();
        for (int i = 0; window.size() < Reranking.MAX_WINDOW && (i < first.size() || i < second.size()); i++) {
            if (i < first.size()) {
                window.putIfAbsent(first.get(i).page().header().key(), first.get(i));
            }
            if (i < second.size() && window.size() < Reranking.MAX_WINDOW) {
                window.putIfAbsent(second.get(i).page().header().key(), second.get(i));
            }
        }
        return List.copyOf(window.values());
    }

    /** Ähnlichkeit des besten Stücks der Seite, nur mit {@code content}-Freigabe und Embedding des aktuellen Modells. */
    private Score score(AccessContext caller, SearchHit hit, float[] vector) {
        if (!permits(caller, hit, AccessKind.CONTENT)) {
            return null;
        }
        PageHeader header = hit.page().header();
        Enrichment result = null;
        for (Enrichment found : store.find(header.key())) {
            if ("embedding".equals(found.processorId())) {
                result = found;
            }
        }
        if (result == null || result.status() != Enrichment.Status.OK
                || !result.processorVersion().equals(embedding.version())) {
            return null;
        }
        EmbeddingPayload payload;
        try {
            payload = EmbeddingPayload.parse(result.payloadJson());
        } catch (IllegalArgumentException e) {
            return null;
        }
        Score best = null;
        for (EmbeddingPayload.Chunk chunk : payload.chunks()) {
            float[] normalized = EmbeddingIndex.normalized(chunk.vector());
            if (normalized.length != vector.length) {
                continue;
            }
            double dot = 0;
            double norm = 0;
            for (int d = 0; d < vector.length; d++) {
                dot += (double) normalized[d] * vector[d];
                norm += (double) normalized[d] * normalized[d];
            }
            if (norm == 0) {
                continue;
            }
            double similarity = Math.max(-1, Math.min(1, dot));
            if (best == null || similarity > best.similarity()) {
                best = new Score(similarity, chunk.charStart(), chunk.charEnd(), null);
            }
        }
        if (best == null) {
            return null;
        }
        Freshness freshness = Objects.equals(result.revision(), header.revision()) ? Freshness.CURRENT
                : Freshness.STALE;
        return new Score(best.similarity(), best.charStart(), best.charEnd(), freshness);
    }

    private boolean permits(AccessContext caller, SearchHit hit, AccessKind kind) {
        PageHeader header = hit.page().header();
        PageKey key = header.key();
        return access.permits(caller, ResourceScope.page(key.sourceId(), header.metadata().partitionKey(),
                key.pageId()), kind);
    }

    private record Score(double similarity, int charStart, int charEnd, Freshness freshness) {
    }

    /** Ob das Embedding hinter einer Ähnlichkeit zur aktuellen Revision der Seite gehört. */
    public enum Freshness {
        CURRENT,
        STALE
    }

    /**
     * Wie ähnlich ein Treffer der Anfrage ist.
     *
     * @param similarity Kosinusähnlichkeit des besten Stücks, -1 bis 1
     * @param charStart  Anfang dieses Stücks in der Textprojektion
     * @param charEnd    Ende (exklusiv)
     * @param freshness  ob das Embedding aus der aktuellen Revision stammt
     */
    public record Relevance(double similarity, int charStart, int charEnd, Freshness freshness) {
    }

    /**
     * Ein Treffer der Relevanzsuche.
     *
     * @param relevance {@code null}, wenn es für die Seite keine Ähnlichkeit gibt (kein Embedding des aktuellen
     *                  Modells oder keine {@code content}-Freigabe)
     */
    public record RankedHit(SearchHit hit, Relevance relevance) {

        public RankedHit {
            Objects.requireNonNull(hit, "hit");
        }
    }

    /**
     * Treffer nach Relevanz, immer vollständig: kein Cursor.
     *
     * @param sourceId die angefragte Quelle oder {@code null} bei der globalen Suche (#114)
     * @param index    gelesener Indexstand oder {@code null}, wenn global keine lesbare Quelle im Index liegt
     * @param reranker Kennung des Rerankers, {@code none} ohne Modell
     */
    public record RelevanceResults(String sourceId, SearchIndexState index, String reranker, List<RankedHit> hits) {

        public RelevanceResults {
            hits = List.copyOf(hits);
        }

        /** Ob die Treffer aus allen freigegebenen Quellen stammen. */
        public boolean global() {
            return sourceId == null;
        }
    }
}
