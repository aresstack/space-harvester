package com.aresstack.spaceharvester.nlp.syntax;

import java.util.List;
import java.util.Objects;

/**
 * Vollständige syntaktische Analyse eines Texts: alle Sätze mit allen Wörtern und Kanten, unverkürzt, und die
 * Herkunft jeder Schicht. Die Rohstruktur bleibt neben jeder abgeleiteten Sicht erhalten.
 *
 * @param ref        Herkunft des Texts
 * @param text       analysierter Text, auf den sich alle Spannen beziehen
 * @param sentences  Sätze in Textreihenfolge
 * @param components Werkzeuge und Modelle je Schicht
 */
public record ParsedDocument(DocumentRef ref, String text, List<Sentence> sentences, List<Component> components) {

    public ParsedDocument {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(text, "text");
        sentences = List.copyOf(sentences);
        components = List.copyOf(components);
    }
}
