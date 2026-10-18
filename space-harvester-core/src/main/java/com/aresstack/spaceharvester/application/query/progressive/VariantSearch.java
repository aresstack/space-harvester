package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Map;
import java.util.Objects;

/**
 * Eine Suchanfrage der progressiven Suche; im Harvester die {@link KeywordSearch} mit ihren Rechteprüfungen,
 * vorläufigen Treffern und dem Laden in den Cache (ADR 0016), mit {@link SearchProfile.Ranking#SEMANTIC} die
 * semantische Suche über die Vektoren im Cache. Treffer, die der Aufrufer nicht sehen darf, liefert sie nie, auch nicht
 * als Zahl.
 */
public interface VariantSearch {

    /**
     * @throws RuntimeException wenn die Suche scheitert; der Orchestrator versucht es nach
     *                          {@link SearchExecutionPolicy#retries()} erneut
     */
    KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit);

    /**
     * Die Suchanfrage {@code variant} einer Suche nach {@code original}. Ohne eigene Bewertung wie
     * {@link #search}; der Orchestrator bewertet dann mit dem {@link HitScorer}.
     *
     * @param original der Text der ursprünglichen Anfrage, gegen den bewertet wird
     */
    default Outcome run(AccessContext caller, KeywordSearch.Query variant, String original, int limit) {
        return new Outcome(search(caller, variant, limit), Map.of());
    }

    /**
     * Prüft vor dem Start einer Suche, ob diese Suche gerade möglich ist; die semantische etwa nur mit Embedding-Modell.
     * Es gibt keinen stillen Rückfall auf eine andere Suche (ADR 0011).
     *
     * @throws RuntimeException wenn nicht, mit einer Meldung ohne Suchtext
     */
    default void requireAvailable() {
    }

    /** Bricht das Laden eines Stapels ab ({@link KeywordSearch#cancel(String)}). */
    default void cancelLoading(String progressId) {
    }

    /**
     * Treffer einer Suchanfrage.
     *
     * @param results die Treffer
     * @param scores  Bewertung gegen die ursprüngliche Anfrage je Seite, soweit die Suche sie selbst kennt; Treffer ohne
     *                Eintrag bewertet der {@link HitScorer}
     */
    record Outcome(KeywordSearch.Results results, Map<PageKey, Double> scores) {

        public Outcome {
            Objects.requireNonNull(results, "results");
            scores = Map.copyOf(scores);
        }
    }

    static VariantSearch of(KeywordSearch keywords) {
        Objects.requireNonNull(keywords, "keywords");
        return new VariantSearch() {
            @Override
            public KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit) {
                return keywords.search(caller, query, null, limit);
            }

            @Override
            public void cancelLoading(String progressId) {
                keywords.cancel(progressId);
            }
        };
    }
}
