package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Erster Reranker (#89): ordnet nach der Kosinusähnlichkeit, die die {@link RelevanceSearch} für jeden freigegebenen
 * Treffer aus den gespeicherten Embeddings berechnet hat. Treffer ohne Ähnlichkeit folgen in ihrer bisherigen
 * Reihenfolge; Gleichstände behalten sie ebenfalls.
 */
final class CosineReranker implements SearchReranker {

    private final String id;
    private final Function<SearchHit, Double> similarity;

    CosineReranker(String id, Function<SearchHit, Double> similarity) {
        this.id = Objects.requireNonNull(id, "id");
        this.similarity = Objects.requireNonNull(similarity, "similarity");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates) {
        List<SearchHit> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(similarity, Comparator.nullsLast(Comparator.reverseOrder())));
        return ordered;
    }
}
