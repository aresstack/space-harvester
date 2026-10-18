package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.configuration.Swappable;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import java.util.List;

/**
 * Ein Reranker, den die Konfiguration zur Laufzeit austauscht (ADR 0015): jede Suche ordnet mit dem Reranker, der bei
 * ihrem Beginn eingestellt war; {@link SearchReranker#NONE} heißt ohne Umordnen.
 */
public final class SwappableReranker implements SearchReranker {

    private final Swappable<SearchReranker> current;

    public SwappableReranker(SearchReranker initial) {
        this.current = Swappable.of(initial);
    }

    /** Der jetzt eingestellte Reranker. */
    public SearchReranker current() {
        return current.get();
    }

    /** Setzt den Reranker für alle folgenden Suchen. */
    public void swap(SearchReranker next) {
        current.swap(next);
    }

    /** Der eingestellte Reranker hinter {@code reranker}, wenn er austauschbar ist. */
    static SearchReranker resolve(SearchReranker reranker) {
        return reranker instanceof SwappableReranker swappable ? swappable.current() : reranker;
    }

    @Override
    public String id() {
        return current().id();
    }

    @Override
    public List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates) {
        return current().rerank(query, candidates);
    }

    @Override
    public List<SearchHit> rerank(Request request) {
        return current().rerank(request);
    }
}
