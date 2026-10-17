package com.aresstack.spaceharvester.nlp.extraction;

import java.util.List;

/**
 * Das Prädikat einer Aussage: Vollverb mit Hilfsverben und abgetrennter Partikel im Wortlaut
 * ({@code ruft … an}) und eine Grundform ({@code anrufen}), soweit die Vorverarbeitung eine liefert.
 *
 * @param verb   Token des Vollverbs
 * @param tokens alle Prädikatsteile in Textreihenfolge
 * @param text   Teile im Wortlaut, mit {@code " … "} verbunden, wenn sie nicht nebeneinander stehen
 * @param lemma  Grundform des Vollverbs mit vorangestellter Partikel oder {@code null}
 */
public record Predicate(int verb, List<Integer> tokens, String text, String lemma) {

    public Predicate {
        tokens = List.copyOf(tokens);
    }
}
