package com.aresstack.spaceharvester.nlp.syntax;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Anwendungsfall: Text → vollständige UD-Analyse. Setzt Vorverarbeitung und Parser zusammen, ohne Spring, HTTP oder
 * Datenbank; threadsicher, wenn beide Ports es sind.
 */
public final class DependencyAnalysis {

    private final GermanPreprocessor preprocessor;
    private final DependencyParser parser;

    public DependencyAnalysis(GermanPreprocessor preprocessor, DependencyParser parser) {
        this.preprocessor = Objects.requireNonNull(preprocessor, "preprocessor");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public ParsedDocument analyze(DocumentRef ref, String text) {
        return analyze(ref, text, () -> false);
    }

    /**
     * Wie {@link #analyze(DocumentRef, String)}, mit Abbruch zwischen den Sätzen (Desktop-App, #499).
     *
     * @param cancelled wird vor jedem Satz gefragt
     * @throws CancellationException nach einem Abbruch
     */
    public ParsedDocument analyze(DocumentRef ref, String text, BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        List<Sentence> sentences = new ArrayList<>();
        for (Sentence sentence : preprocessor.analyze(text)) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Analyse abgebrochen");
            }
            sentences.add(sentence.tokens().isEmpty() ? sentence : parser.parse(sentence));
        }
        List<Component> components = new ArrayList<>(preprocessor.components());
        components.add(parser.component());
        return new ParsedDocument(ref, text, sentences, components);
    }
}
