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
import com.aresstack.spaceharvester.application.query.PageSearchIndex;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchScope;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * <b>Experimentell</b> (Angelo, 18.10.): Die Suche ist die Schlagwortsuche
 * ({@link com.aresstack.spaceharvester.application.query.KeywordSearch}). Diese Klasse ist die semantische Suche der
 * Checkbox „Semantische Suche (experimentell)“ und bleibt der einzige Weg dafür ({@code sort=relevance}, kein eigener
 * Endpunkt). Die Vektoren kommen aus dem externen Embedding-Dienst (ADR 0011); ohne ihn antwortet sie mit
 * {@link SemanticSearchUnavailableException}, nie still mit anderen Treffern.
 *
 * <p>Erst in den Cache (ADR 0013, Punkt 3, mit {@link Preparation}): Die Quellen finden wie in der Schlagwortsuche,
 * die gefundenen Seiten werden mit den Rechten des Aufrufers geladen und ihre fehlenden oder veralteten Embeddings
 * berechnet; erst dann sucht die Suche einmal semantisch. Was dabei nicht fertig wird, macht das Ergebnis
 * unvollständig.
 *
 * <p>Nur über den Cache und ohne Fusion (ADR 0013): Kandidaten kommen ausschließlich aus der Vektorsuche über die
 * gespeicherten Embeddings, die Reihenfolge ist die Kosinusähnlichkeit. Es gibt keine zweite Ergebnisliste, also weder
 * Volltext- noch Titelkandidaten und keine Rangfusion. Titelwörter der Anfrage filtern die Kandidaten und sind ohne
 * {@code q} der Anfragetext.
 *
 * <p><b>Erweiterungspunkt Schlagwortgenerierung</b> (ADR 0011, bewusst noch nicht gebaut): Vor Schritt 1 der
 * Zielarchitektur kann ein Chat-Modell ({@code space-harvester.ai.chat-model}) aus der Frage gezielt Schlagworte für
 * die Schlagwortsuche der Quellen erzeugen, wie {@code KnowledgeResearch}, {@code LexicalDiscoveryStrategy} und
 * {@code ResearchQueries} im Enterprise AI Client. Die Frage geht dabei an den Dienst; Seiteninhalte nur aus Quellen
 * mit Freigabe für externe Verarbeitung ({@code external-processing-sources}).
 *
 * <p>Suche nach Relevanz ({@code sort=relevance}, #89, ADR 0004): ein Fenster von höchstens {@value Reranking#MAX_WINDOW}
 * freigegebenen Kandidaten aus der Vektorsuche, geordnet nach der Kosinusähnlichkeit der Anfrage zu den gespeicherten
 * Embeddings. Ein Reranker des Programms oder des KI-Dienstes (#181) ordnet die geprüften Treffer danach nur um.
 *
 * <p>Umfang (#114): mit {@code sourceId} eine Quelle, ohne alle Quellen, die der Aufrufer sehen darf
 * ({@link SearchScope}), mit derselben Fehlersemantik wie {@code PageSearch}. Global gibt es genau eine Einbettung der
 * Anfrage und eine Vektorsuche über alle Quellen des Umfangs zusammen (höchstens {@value #MAX_CHUNKS} Stücke, dann
 * höchstens {@value #MAX_LOOKUPS} Seiten). Das Fenster bleibt
 * bei {@value Reranking#MAX_WINDOW} Kandidaten: ein begrenzter, global gerankter Block, kein vollständiges Ergebnis und
 * keine Top-K je Quelle. Kandidaten sind Seiten ({@code sourceId + pageId}); gleiche Seiten-IDs verschiedener Quellen
 * bleiben getrennt. Ähnlichkeiten stammen aus demselben Modell und sind über Quellen vergleichbar.
 *
 * <p>Rechte: wie {@code PageSearch} {@link AccessKind#METADATA} für die genannten Spaces bzw. die Quelle und für jeden
 * Treffer. Ähnlichkeiten sind aus dem Inhalt abgeleitet: Ein Kandidat aus der Vektorsuche braucht zusätzlich
 * {@link AccessKind#CONTENT} für seine Seite, sonst fällt er ohne Spur weg. Der Reranker sieht nur diese freigegebenen
 * Treffer und darf sie nur umordnen ({@link Reranking}).
 *
 * <p>Alle Filter der Anfrage gelten für jeden Kandidaten, auch aus der Vektorsuche; jeder Treffer trägt die aktuellen
 * Kopfdaten aus dem Suchindex. Ein Treffer, dessen Embedding aus einer älteren Revision stammt, heißt
 * {@link Freshness#STALE}.
 *
 * <p>Live-Prüfung (ADR 0011, Punkt 7, mit {@link Renewal}): Vor der Auslieferung prüft die Suche jeden Treffer wie das
 * Lesen einer Seite. Ein Metadatenabruf bei der Quelle liefert Recht und Version; Rechte gelten für den aktuellen
 * Space. Gesperrte oder verschwundene Seiten fallen ohne Spur weg, und weitere Kandidaten rücken nach. Meldet die
 * Quelle eine neuere Version, wird die Seite über {@link Renewal} nachgeladen und abgelegt, ihr Vektor neu berechnet,
 * und der Treffer heißt bis dahin {@link Freshness#STALE}. Ist ein Kandidat nicht prüfbar, fehlt er, und das Ergebnis
 * ist unvollständig. Der Index bleibt gemeinsam für alle Benutzer.
 */
public class RelevanceSearch {

    /** Höchstens so viele Stücke liefert die Vektorsuche je Anfrage als Kandidaten. */
    static final int MAX_CHUNKS = EmbeddingIndex.MAX_K;

    /** Höchstens so viele Seiten aus der Vektorsuche werden im Suchindex nachgeschlagen. */
    static final int MAX_LOOKUPS = 3 * Reranking.MAX_WINDOW;

    /** Gleichzeitige Metadatenabrufe der Live-Prüfung je Suche. */
    static final int PARALLEL_CHECKS = 6;

    /** Längster Auszug des Lesetexts, den ein Reranker je Treffer als Dokument bekommt (#181). */
    static final int MAX_DOCUMENT_CHARS = 2000;

    /** Längster Anfragetext. */
    public static final int MAX_TEXT_LENGTH = 500;

    private static final System.Logger LOG = System.getLogger(RelevanceSearch.class.getName());

    private final SourceCatalog catalog;
    private final PageSearchIndex index;
    private final AccessGuard access;
    private final EnrichmentStore store;
    private final EmbeddingIndex vectors;
    private final EmbeddingProcessor embedding;
    private final Renewal renewal;
    private final SearchReranker reranker;
    private final PageTextIndex texts;
    private final Preparation preparation;

    /**
     * Ohne Live-Prüfung je Treffer: Rechte nur gegen die Zugriffspolicy und die Kopfdaten im Index. Für Tests und
     * Einbettungen ohne Zugriff auf die Quellen.
     *
     * @param store     Ablage der Embeddings oder {@code null}
     * @param vectors   Vektorsuche über diese Ablage oder {@code null}
     * @param embedding Prozessor des eingerichteten Embedding-Dienstes oder {@code null}, wenn keiner eingerichtet ist;
     *                  dann antwortet jede Suche mit {@link SemanticSearchUnavailableException}
     */
    public RelevanceSearch(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, EnrichmentStore store,
                           EmbeddingIndex vectors, EmbeddingProcessor embedding) {
        this(catalog, index, access, store, vectors, embedding, null);
    }

    /**
     * Mit Live-Prüfung je Treffer (ADR 0011, Punkt 7): Vor der Auslieferung liest die Suche die Kopfdaten jedes
     * Treffers bei der Quelle und prüft an ihnen Recht und Version.
     *
     * @param renewal holt neuere Versionen nach und legt sie ab, damit ihr Vektor neu berechnet wird; {@code null}
     *                schaltet die Live-Prüfung ab
     */
    public RelevanceSearch(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, EnrichmentStore store,
                           EmbeddingIndex vectors, EmbeddingProcessor embedding, Renewal renewal) {
        this(catalog, index, access, store, vectors, embedding, renewal, SearchReranker.NONE);
    }

    /**
     * Mit einem weiteren Reranker des einbettenden Programms (#188), etwa einem Cross-Encoder: Er ordnet das nach
     * Ähnlichkeit sortierte Fenster freigegebener Treffer neu, unter denselben Regeln ({@link Reranking}).
     */
    public RelevanceSearch(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, EnrichmentStore store,
                           EmbeddingIndex vectors, EmbeddingProcessor embedding, Renewal renewal,
                           SearchReranker reranker) {
        this(catalog, index, access, store, vectors, embedding, renewal, reranker, null);
    }

    /**
     * Mit dem Volltextindex über den Cache: Aus ihm bekommt ein Reranker den Lesetext der Treffer. Kandidaten liefert
     * er nicht (ADR 0013).
     *
     * @param texts Volltextindex oder {@code null}, dann sieht ein Reranker nur Titel
     */
    public RelevanceSearch(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, EnrichmentStore store,
                           EmbeddingIndex vectors, EmbeddingProcessor embedding, Renewal renewal,
                           SearchReranker reranker, PageTextIndex texts) {
        this(catalog, index, access, store, vectors, embedding, renewal, reranker, texts, null);
    }

    /**
     * Mit Vorbereitung (ADR 0013, Punkt 3): Vor der Vektorsuche finden die Quellen, die gefundenen Seiten werden in den
     * Cache geladen und fehlende oder veraltete Embeddings berechnet.
     *
     * @param preparation die Vorbereitung oder {@code null}, dann sucht die Suche nur über schon gespeicherte Vektoren
     */
    public RelevanceSearch(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, EnrichmentStore store,
                           EmbeddingIndex vectors, EmbeddingProcessor embedding, Renewal renewal,
                           SearchReranker reranker, PageTextIndex texts, Preparation preparation) {
        this.preparation = preparation;
        this.texts = texts;
        this.renewal = renewal;
        this.reranker = Objects.requireNonNull(reranker, "reranker");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.access = Objects.requireNonNull(access, "access");
        boolean semantic = store != null && vectors != null && embedding != null;
        this.store = semantic ? store : null;
        this.vectors = semantic ? vectors : null;
        this.embedding = semantic ? embedding : null;
    }

    /** Ob ein Embedding-Dienst eingerichtet ist; ohne antwortet jede Suche mit einem Fehler. */
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
     * @throws SemanticSearchUnavailableException   wenn kein Embedding-Dienst eingerichtet oder er nicht erreichbar ist;
     *                                              es gibt keinen Rückfall auf die Titelsuche (ADR 0011)
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
        if (embedding == null) {
            // ADR 0011: kein stiller Rückfall auf die Titelsuche.
            throw new SemanticSearchUnavailableException("Kein Embedding-Dienst eingerichtet");
        }
        int preparing = 0;
        if (preparation != null) {
            try {
                preparing = preparation.prepare(caller, query, queryText);
            } catch (RuntimeException e) {
                // Ohne Anfragetext im Log; gesucht wird über das, was schon im Cache liegt.
                LOG.log(System.Logger.Level.INFO, "Vorbereitung der semantischen Suche gescheitert ({0})",
                        e.getClass().getName());
                preparing = 1;
            }
        }
        SearchIndexState state = scope.state(index).orElse(null);
        if (state == null) {
            // Nur global: keine lesbare Quelle im Index.
            return new RelevanceResults(null, null, SearchReranker.NONE.id(), List.of(), preparing == 0);
        }

        float[] vector = queryVector(queryText);
        List<SearchHit> window = semanticCandidates(caller, query, scope, state, vector);

        Map<SearchHit, Score> scores = new IdentityHashMap<>();
        for (SearchHit hit : window) {
            Score score = score(caller, hit, vector);
            if (score != null) {
                scores.put(hit, score);
            }
        }
        SearchReranker reranker = new CosineReranker("embedding-cosine@" + embedding.version(), hit -> {
                    Score score = scores.get(hit);
                    return score == null ? null : score.similarity();
                });
        Reranking reranking = new Reranking(reranker);
        List<SearchHit> ordered = reranking.apply(query, window);
        String rerankerId = reranking.rerankerId();
        // Jeder Kandidat stammt aus den Vektoren, also aus dem Inhalt: live braucht er content.
        Set<PageKey> contentRanked = new LinkedHashSet<>();
        window.forEach(hit -> contentRanked.add(hit.page().header().key()));
        List<RankedHit> hits = new ArrayList<>(Math.min(limit, ordered.size()));
        Map<SearchHit, String> partitions = new IdentityHashMap<>();
        boolean complete = true;
        int next = 0;
        // Gesperrte oder verschwundene Treffer fallen weg; dann rücken weitere Kandidaten nach.
        while (hits.size() < limit && next < ordered.size()) {
            int end = Math.min(ordered.size(), next + limit - hits.size());
            for (Verified verified : verify(caller, query, ordered.subList(next, end), scores, contentRanked)) {
                complete &= !verified.failed();
                if (verified.hit() != null && hits.size() < limit) {
                    hits.add(verified.hit());
                    partitions.put(verified.hit().hit(), verified.partition());
                }
            }
            next = end;
        }
        if (this.reranker != SearchReranker.NONE) {
            // Erst nach der Live-Prüfung: An den Reranker gehen nur Texte von Seiten, die der Aufrufer jetzt lesen
            // darf, geprüft gegen ihren heutigen Space.
            Map<SearchHit, RankedHit> byHit = new IdentityHashMap<>();
            hits.forEach(hit -> byHit.put(hit.hit(), hit));
            Reranking custom = new Reranking(this.reranker);
            List<SearchHit> reranked = custom.apply(new SearchReranker.Request(query, queryText,
                    hits.stream().map(RankedHit::hit).toList(),
                    hit -> document(caller, hit, scores.get(hit), partitions.get(hit))));
            hits = reranked.stream().map(byHit::get).toList();
            rerankerId = rerankerId + "+" + custom.rerankerId();
        }
        return new RelevanceResults(query.sourceId(), state, rerankerId, hits, complete && preparing == 0);
    }

    /**
     * Prüft Treffer parallel, Reihenfolge bleibt.
     *
     * @param contentRanked Seiten, deren Platz aus dem Volltext stammt; sie brauchen live {@code content} wie Treffer
     *                      mit Ähnlichkeit
     */
    private List<Verified> verify(AccessContext caller, PageSearchQuery query, List<SearchHit> batch,
                                  Map<SearchHit, Score> scores, Set<PageKey> contentRanked) {
        if (renewal == null) {
            return batch.stream().map(hit -> new Verified(ranked(hit, scores.get(hit)), false, indexed(hit))).toList();
        }
        Semaphore slots = new Semaphore(PARALLEL_CHECKS);
        List<Future<Verified>> futures = new ArrayList<>(batch.size());
        try (ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor()) {
            for (SearchHit hit : batch) {
                futures.add(checks.submit(() -> {
                    slots.acquireUninterruptibly();
                    try {
                        return verify(caller, query, hit, scores.get(hit),
                                contentRanked.contains(hit.page().header().key()));
                    } finally {
                        slots.release();
                    }
                }));
            }
        }
        List<Verified> verified = new ArrayList<>(futures.size());
        for (Future<Verified> future : futures) {
            try {
                verified.add(future.get());
            } catch (ExecutionException e) {
                // Nie ungeprüft ausliefern; der Stapel ist dann unvollständig.
                verified.add(Verified.FAILED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                verified.add(Verified.FAILED);
            }
        }
        return verified;
    }

    /**
     * Wie das Lesen einer Seite: ein Metadatenabruf bei der Quelle liefert Recht und Version in einem Schritt. Die
     * Rechte gelten für den aktuellen Space der Seite, nicht den im Index.
     */
    private Verified verify(AccessContext caller, PageSearchQuery query, SearchHit hit, Score score,
                            boolean contentRanked) {
        PageKey key = hit.page().header().key();
        HarvestSource source = catalog.find(key.sourceId()).orElse(null);
        if (source == null) {
            return Verified.DROPPED;
        }
        PageMetadata live;
        try {
            live = source.pageMetadata(key.pageId());
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.NOT_FOUND) {
                return Verified.DROPPED;
            }
            if (e.reason() == SourceAccessException.Reason.UNSUPPORTED) {
                // Die Quelle kann keine Einzelseite prüfen (wie beim Lesen ohne Inhaltsabruf): Policy und Index.
                return new Verified(ranked(hit, score), false, indexed(hit));
            }
            // Ohne Seiten-ID im Log.
            LOG.log(System.Logger.Level.INFO, "Kopfdaten eines Relevanztreffers in {0} nicht lesbar ({1})",
                    key.sourceId(), e.reason());
            return Verified.FAILED;
        }
        if (!query.spaceKeys().isEmpty() && !query.spaceKeys().contains(live.partitionKey())) {
            return Verified.DROPPED;
        }
        ResourceScope scope = ResourceScope.page(key.sourceId(), live.partitionKey(), key.pageId());
        if (!access.permits(caller, scope, AccessKind.METADATA)
                || (score != null || contentRanked) && !access.permits(caller, scope, AccessKind.CONTENT)) {
            // Auch die Position eines Treffers mit Ähnlichkeit oder Volltextrang stammt aus dem Inhalt.
            return Verified.DROPPED;
        }
        Score checked = score;
        if (live.version() != null && !live.version().equals(hit.page().header().revision())) {
            renew(caller, key);
            if (checked != null) {
                checked = new Score(checked.similarity(), checked.charStart(), checked.charEnd(), Freshness.STALE);
            }
        }
        return new Verified(ranked(hit, checked), false, live.partitionKey());
    }

    private void renew(AccessContext caller, PageKey key) {
        try {
            renewal.renew(caller, key);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Nachladen eines Relevanztreffers in {0} nicht eingeplant ({1})",
                    key.sourceId(), e.getClass().getName());
        }
    }

    private static RankedHit ranked(SearchHit hit, Score score) {
        return score == null ? new RankedHit(hit, null)
                : new RankedHit(hit, new Relevance(score.similarity(), score.charStart(), score.charEnd(),
                        score.freshness()));
    }

    private static String indexed(SearchHit hit) {
        return hit.page().header().metadata().partitionKey();
    }

    /**
     * Ergebnis der Live-Prüfung: Treffer, weggefallen ({@code hit == null}) oder nicht prüfbar ({@code failed}), mit
     * dem geprüften Space.
     */
    private record Verified(RankedHit hit, boolean failed, String partition) {

        static final Verified DROPPED = new Verified(null, false, null);
        static final Verified FAILED = new Verified(null, true, null);
    }

    /**
     * Holt eine Seite, deren Quelle eine neuere Version meldet, nach und legt sie im gemeinsamen Cache ab; damit wird
     * auch ihr Vektor neu berechnet (ADR 0011, Punkt 7). Darf nicht blockieren.
     */
    /**
     * Vorbereitung der semantischen Suche (ADR 0013, Punkt 3): finden, laden, fehlende Embeddings berechnen, begrenzt
     * im Aufruf.
     */
    @FunctionalInterface
    public interface Preparation {

        /**
         * @param query Umfang und Filter der Suche
         * @param text  Anfragetext
         * @return wie viele gefundene Seiten noch ohne aktuelles Embedding sind; dann ist das Ergebnis unvollständig
         */
        int prepare(AccessContext caller, PageSearchQuery query, String text);
    }

    @FunctionalInterface
    public interface Renewal {

        /** Nur prüfen, nichts nachladen. */
        Renewal NONE = (caller, key) -> { };

        void renew(AccessContext caller, PageKey key);
    }

    private float[] queryVector(String text) {
        try {
            return EmbeddingIndex.normalized(embedding.embedQuery(text));
        } catch (EmbeddingUnavailableException e) {
            // Kein Anfragetext im Log.
            LOG.log(System.Logger.Level.WARNING, "Anfrage nicht eingebettet ({0})", e.getClass().getName());
            throw new SemanticSearchUnavailableException("Der Embedding-Dienst ist gerade nicht verfügbar");
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

    /**
     * Was ein Reranker von einem Treffer sieht (#181): der Titel und, nur mit {@code content}-Freigabe, ein Auszug des
     * Lesetexts aus dem Volltextindex, um das ähnlichste Stück herum oder vom Anfang. Nie die Adresse der Seite.
     */
    private String document(AccessContext caller, SearchHit hit, Score score, String partition) {
        String title = hit.page().header().metadata().title();
        title = title == null ? "" : title.strip();
        PageKey key = hit.page().header().key();
        if (texts == null || !access.permits(caller, ResourceScope.page(key.sourceId(), partition, key.pageId()),
                AccessKind.CONTENT)) {
            return title;
        }
        String text;
        try {
            text = texts.text(hit.page().header().key()).orElse("");
        } catch (RuntimeException e) {
            return title;
        }
        int start = score == null ? 0 : Math.clamp(score.charStart(), 0, text.length());
        int end = Math.min(text.length(), start + MAX_DOCUMENT_CHARS);
        if (score != null && score.charEnd() > start && score.charEnd() - start < MAX_DOCUMENT_CHARS) {
            // Das Stück mittig, soweit der Text reicht.
            int spare = MAX_DOCUMENT_CHARS - (Math.min(score.charEnd(), text.length()) - start);
            start = Math.max(0, start - spare / 2);
            end = Math.min(text.length(), start + MAX_DOCUMENT_CHARS);
        }
        String excerpt = text.substring(start, end).strip();
        return title.isEmpty() ? excerpt : excerpt.isEmpty() ? title : title + "\n" + excerpt;
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
     * @param complete {@code false}, wenn die Kopfdaten eines Kandidaten bei der Quelle nicht lesbar waren; er fehlt
     *                 dann, weil ungeprüft nichts ausgeliefert wird
     */
    public record RelevanceResults(String sourceId, SearchIndexState index, String reranker, List<RankedHit> hits,
                                   boolean complete) {

        public RelevanceResults {
            hits = List.copyOf(hits);
        }

        /** Ob die Treffer aus allen freigegebenen Quellen stammen. */
        public boolean global() {
            return sourceId == null;
        }
    }
}
