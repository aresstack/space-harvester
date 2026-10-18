package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Wendet einen {@link SearchReranker} auf ein begrenztes Fenster freigegebener Treffer an. Liefert er etwas anderes
 * als eine Umordnung derselben Treffer oder scheitert er, gilt die Reihenfolge des Index: Reranking darf nie Rechte
 * umgehen, Treffer verlieren oder die Suche scheitern lassen.
 */
public class Reranking {

    /** Höchstens so viele Treffer ordnet ein Reranker in einem Aufruf neu. */
    public static final int MAX_WINDOW = 100;

    private static final System.Logger LOG = System.getLogger(Reranking.class.getName());

    private final SearchReranker reranker;

    public Reranking(SearchReranker reranker) {
        this.reranker = Objects.requireNonNull(reranker, "reranker");
    }

    public String rerankerId() {
        return reranker.id();
    }

    /**
     * @param candidates freigegebene Treffer, höchstens {@value #MAX_WINDOW}
     * @return die Umordnung des Rerankers oder {@code candidates} unverändert
     */
    public List<SearchHit> apply(PageSearchQuery query, List<SearchHit> candidates) {
        return apply(candidates, () -> reranker.rerank(query, List.copyOf(candidates)));
    }

    /** Wie {@link #apply(PageSearchQuery, List)}, mit Anfrage- und Dokumenttexten für den Reranker (#181). */
    public List<SearchHit> apply(SearchReranker.Request request) {
        return apply(request.candidates(), () -> reranker.rerank(request));
    }

    private List<SearchHit> apply(List<SearchHit> candidates, Supplier<List<SearchHit>> call) {
        if (candidates.size() > MAX_WINDOW) {
            throw new IllegalArgumentException("Höchstens " + MAX_WINDOW + " Treffer je Reranking");
        }
        if (candidates.size() < 2 || reranker == SearchReranker.NONE) {
            return List.copyOf(candidates);
        }
        List<SearchHit> reordered;
        try {
            reordered = call.get();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Reranker {0} scheiterte ({1}); Reihenfolge des Index bleibt",
                    reranker.id(), e.getClass().getName());
            return List.copyOf(candidates);
        }
        if (!isPermutation(candidates, reordered)) {
            LOG.log(System.Logger.Level.WARNING, "Reranker {0} lieferte keine Umordnung der Treffer; Reihenfolge des "
                    + "Index bleibt", reranker.id());
            return List.copyOf(candidates);
        }
        return List.copyOf(reordered);
    }

    /** Dieselben Trefferobjekte, jedes genau einmal. */
    private static boolean isPermutation(List<SearchHit> candidates, List<SearchHit> reordered) {
        if (reordered == null || reordered.size() != candidates.size()) {
            return false;
        }
        Map<SearchHit, Integer> remaining = new IdentityHashMap<>();
        for (SearchHit hit : candidates) {
            remaining.merge(hit, 1, Integer::sum);
        }
        for (SearchHit hit : new ArrayList<>(reordered)) {
            Integer count = remaining.get(hit);
            if (count == null || count == 0) {
                return false;
            }
            remaining.put(hit, count - 1);
        }
        return true;
    }
}
