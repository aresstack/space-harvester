package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.query.ExcerptPriority;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Cross-Encoder des externen KI-Dienstes (#181, wie {@code CrossEncoderRanking} mit {@code KipitzRerankAdapter} im
 * Enterprise AI Client): bewertet jeden Treffer einzeln gegen den Anfragetext und ordnet nach dieser Bewertung. Anders
 * als dort verwirft er nichts: Er ordnet nur um ({@link Reranking}).
 *
 * <p>An den Dienst gehen der Anfragetext und die Dokumenttexte nur von Treffern aus Quellen mit Freigabe für externe
 * Verarbeitung (ADR 0011). Treffer anderer Quellen behalten ihren Platz; die freigegebenen werden unter sich auf ihre
 * bisherigen Plätze umgeordnet. Ohne Anfragetext ({@link #rerank(PageSearchQuery, List)}) bleibt die Reihenfolge.
 * Scheitert der Dienst oder antwortet er unbrauchbar, bleibt die Reihenfolge ebenfalls ({@link Reranking}).
 */
public final class ModelReranker implements SearchReranker {

    /**
     * Bewertet Dokumente gegen eine Anfrage: ein Wert je Dokument in derselben Reihenfolge, höher ist besser.
     */
    @FunctionalInterface
    public interface Scorer {

        double[] score(String query, List<String> documents);
    }

    private final String modelId;
    private final Scorer scorer;
    private final Predicate<String> mayProcessExternally;

    /**
     * @param modelId              Rerank-Modell des Dienstes
     * @param mayProcessExternally ob Inhalte dieser Quelle an den Dienst gehen dürfen
     */
    public ModelReranker(String modelId, Scorer scorer, Predicate<String> mayProcessExternally) {
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.mayProcessExternally = Objects.requireNonNull(mayProcessExternally, "mayProcessExternally");
    }

    /**
     * Die Ladereihenfolge der Schlagwortsuche nach Titel und Auszug (#154) mit demselben Dienst und derselben Freigabe
     * für externe Verarbeitung.
     */
    public ExcerptPriority excerptPriority() {
        return new ExcerptPriority(scorer::score, mayProcessExternally);
    }

    @Override
    public String id() {
        return "cross-encoder@" + modelId;
    }

    @Override
    public List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates) {
        return candidates;
    }

    @Override
    public List<SearchHit> rerank(Request request) {
        List<SearchHit> candidates = request.candidates();
        List<Integer> slots = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            SearchHit hit = candidates.get(i);
            if (mayProcessExternally.test(hit.page().header().key().sourceId())) {
                String document = request.documents().apply(hit);
                if (document != null && !document.isBlank()) {
                    slots.add(i);
                    documents.add(document);
                }
            }
        }
        if (slots.size() < 2 || request.text().isBlank()) {
            return candidates;
        }
        double[] scores = scorer.score(request.text(), documents);
        if (scores == null || scores.length != documents.size()) {
            throw new IllegalStateException("Rerank-Dienst: nicht jedes Dokument bewertet");
        }
        for (double score : scores) {
            if (Double.isNaN(score) || Double.isInfinite(score)) {
                throw new IllegalStateException("Rerank-Dienst: Bewertung nicht endlich");
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            order.add(i);
        }
        // Stabil: Gleichstände behalten die bisherige Reihenfolge.
        order.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
        List<SearchHit> reordered = new ArrayList<>(candidates);
        for (int i = 0; i < slots.size(); i++) {
            reordered.set(slots.get(i), candidates.get(slots.get(order.get(i))));
        }
        return reordered;
    }
}
