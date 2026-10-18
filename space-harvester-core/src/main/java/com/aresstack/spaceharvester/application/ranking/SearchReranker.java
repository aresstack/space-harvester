package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Ordnet Suchtreffer nach Relevanz neu, z. B. über Embedding-Ähnlichkeit oder ein Cross-Encoder-Modell. Ein Reranker
 * bekommt nur Treffer, die der Aufrufer sehen darf, und gibt eine Umordnung genau dieser Treffer zurück: Er entfernt
 * keine, fügt keine hinzu und ändert keine. {@link Reranking} setzt das durch.
 */
public interface SearchReranker {

    /** Kennung samt Version, z. B. {@code none} oder {@code embedding-cosine@e5-small-v2}. */
    String id();

    /**
     * @param query      die Suchanfrage
     * @param candidates freigegebene Treffer in der Reihenfolge des Index
     * @return dieselben Treffer in neuer Reihenfolge
     */
    List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates);

    /**
     * Mit Anfragetext und Dokumenttexten (#181), etwa für einen Cross-Encoder. Ohne eigene Umsetzung wie
     * {@link #rerank(PageSearchQuery, List)}.
     */
    default List<SearchHit> rerank(Request request) {
        return rerank(request.query(), request.candidates());
    }

    /**
     * Eine Umordnung mit Texten.
     *
     * @param text      Anfragetext der Relevanzsuche
     * @param documents Text, nach dem ein Treffer bewertet wird (Titel, mit {@code content}-Freigabe auch ein Auszug
     *                  des Lesetexts); nie die Adresse der Seite
     */
    record Request(PageSearchQuery query, String text, List<SearchHit> candidates,
                   Function<SearchHit, String> documents) {

        public Request {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(text, "text");
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(documents, "documents");
        }
    }

    /** Lässt die Reihenfolge des Index unverändert; Standard, solange kein Modell konfiguriert ist. */
    SearchReranker NONE = new SearchReranker() {

        @Override
        public String id() {
            return "none";
        }

        @Override
        public List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates) {
            return candidates;
        }
    };
}
