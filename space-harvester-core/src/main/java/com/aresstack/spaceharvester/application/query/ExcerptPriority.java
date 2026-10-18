package com.aresstack.spaceharvester.application.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Ladereihenfolge der gefundenen Seiten nach Titel und Auszug (wie {@code ExcerptRanking} im Enterprise AI Client,
 * #154): Bevor ein Volltext geladen wird, bewertet der Cross-Encoder des KI-Dienstes jeden gefundenen Kandidaten gegen
 * den Suchtext; die Bewertung bestimmt nur, was {@link KeywordSearch.Prefetch} zuerst lädt. Sie verwirft nie einen
 * Kandidaten und ändert nie die Reihenfolge der Treffer: Die kommt aus dem Volltextindex über den Cache.
 *
 * <p>An den Dienst gehen Suchtext, Titel und Auszug nur von Kandidaten aus Quellen mit Freigabe für externe
 * Verarbeitung (ADR 0011), genau wie bei
 * {@link com.aresstack.spaceharvester.application.ranking.ModelReranker}: Kandidaten anderer Quellen behalten ihren
 * Platz, die freigegebenen werden unter sich auf ihre bisherigen Plätze umgeordnet.
 *
 * <p>Absichtlich optional: Ohne Bewerter, ohne Suchtext, mit weniger als zwei freigegebenen Kandidaten, wenn der Dienst
 * scheitert oder eine falsche Zahl oder nicht endliche Werte liefert, bleibt die Reihenfolge der Quellen
 * ({@link Priority#ranked()} ist dann {@code false}). Eine Suche scheitert nie daran.
 */
public final class ExcerptPriority {

    /**
     * Bewertet Dokumente gegen eine Anfrage: ein Wert je Dokument in derselben Reihenfolge, höher ist besser; jede
     * Ausnahme heißt „diesmal keine Bewertung“.
     */
    @FunctionalInterface
    public interface Scorer {

        double[] score(String query, List<String> documents);
    }

    private static final System.Logger LOG = System.getLogger(ExcerptPriority.class.getName());
    private static final ExcerptPriority OFF = new ExcerptPriority(null, sourceId -> false);

    private final Scorer scorer;
    private final Predicate<String> mayProcessExternally;

    /**
     * @param scorer               der Cross-Encoder des Dienstes
     * @param mayProcessExternally ob Inhalte dieser Quelle an den Dienst gehen dürfen (Quellen-ID)
     */
    public ExcerptPriority(Scorer scorer, Predicate<String> mayProcessExternally) {
        this.scorer = scorer;
        this.mayProcessExternally = Objects.requireNonNull(mayProcessExternally, "mayProcessExternally");
    }

    /** Ohne Bewertung: die Reihenfolge der Quellen bleibt die Ladereihenfolge. */
    public static ExcerptPriority off() {
        return OFF;
    }

    /** Ob überhaupt bewertet werden kann. */
    public boolean enabled() {
        return scorer != null;
    }

    /**
     * Ladereihenfolge der Kandidaten einer Suche.
     *
     * @param order    Positionen in der Reihenfolge der Quellen, beste zuerst; immer eine Permutation aller Kandidaten
     * @param ranked   ob der Dienst bewertet hat; {@code false}: Reihenfolge der Quellen
     * @param fallback warum die Reihenfolge der Quellen blieb, {@code null}, wenn bewertet wurde oder nichts zu
     *                 bewerten war
     */
    public record Priority(List<Integer> order, boolean ranked, String fallback) {

        public Priority {
            order = List.copyOf(order);
        }

        /** Die Kandidaten in dieser Reihenfolge. */
        public <T> List<T> apply(List<T> items) {
            if (items.size() != order.size()) {
                throw new IllegalArgumentException("Priorität passt nicht zu den Kandidaten");
            }
            List<T> ordered = new ArrayList<>(items.size());
            order.forEach(index -> ordered.add(items.get(index)));
            return ordered;
        }
    }

    /** Bewertet alle freigegebenen Kandidaten zusammen und ordnet sie, beste zuerst; Gleichstände bleiben. */
    public Priority prioritize(String query, List<KeywordSearch.Hit> hits) {
        int size = hits == null ? 0 : hits.size();
        List<Integer> sourceOrder = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            sourceOrder.add(i);
        }
        if (scorer == null) {
            return new Priority(sourceOrder, false, size == 0 ? null : "kein Reranker");
        }
        List<Integer> slots = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            KeywordSearch.Hit hit = hits.get(i);
            if (mayProcessExternally.test(hit.sourceId())) {
                String title = hit.title() == null ? "" : hit.title();
                String snippet = hit.snippet() == null ? "" : hit.snippet();
                String document = snippet.isBlank() ? title : title + "\n" + snippet;
                if (!document.isBlank()) {
                    slots.add(i);
                    documents.add(document);
                }
            }
        }
        if (slots.size() < 2 || query == null || query.isBlank()) {
            return new Priority(sourceOrder, false, null);
        }
        double[] scores;
        try {
            scores = scorer.score(query, List.copyOf(documents));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Bewertung der Auszüge nicht möglich ({0}); Reihenfolge der Quellen",
                    e.getClass().getName());
            return new Priority(sourceOrder, false, "Reranker scheiterte (" + e.getClass().getSimpleName() + ")");
        }
        if (scores == null || scores.length != documents.size()) {
            return fallback(sourceOrder, "Reranker lieferte eine falsche Zahl von Bewertungen");
        }
        for (double score : scores) {
            if (Double.isNaN(score) || Double.isInfinite(score)) {
                return fallback(sourceOrder, "Reranker lieferte eine nicht endliche Bewertung");
            }
        }
        List<Integer> ranking = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            ranking.add(i);
        }
        // Stabil: Gleichstände behalten die Reihenfolge der Quellen.
        ranking.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
        List<Integer> order = new ArrayList<>(sourceOrder);
        for (int i = 0; i < slots.size(); i++) {
            order.set(slots.get(i), slots.get(ranking.get(i)));
        }
        return new Priority(order, true, null);
    }

    private static Priority fallback(List<Integer> sourceOrder, String reason) {
        LOG.log(System.Logger.Level.INFO, "{0}; Reihenfolge der Quellen", reason);
        return new Priority(sourceOrder, false, reason);
    }
}
