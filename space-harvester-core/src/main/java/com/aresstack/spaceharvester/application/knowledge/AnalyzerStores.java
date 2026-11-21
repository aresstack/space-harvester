package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Je Aussagenanalysator eine eigene Ablage (Fahrplan Analyse-Reiter, Paket A2): Ergebnisse von MaltParser und
 * KI-Analysator überschreiben sich nie. Geschrieben wird in die Ablage des Analysators, der die Analyse gerechnet hat
 * ({@link PageStatementAnalysis#analyzerId()}); gelesen wird für Graph, Stand, Begriffe und Export immer aus genau einer
 * Ablage, der des gewählten Analysators ({@link #selected()}), nie aus beiden gemischt. Entfernen trifft beide.
 */
public final class AnalyzerStores implements KnowledgeGraphStore {

    private final KnowledgeGraphStore parser;
    private final KnowledgeGraphStore model;
    private final Supplier<AnalyzerChoice> selected;

    /**
     * @param selected aus welcher Ablage gelesen wird, etwa nach den Einstellungen; Vorgabe {@link AnalyzerChoice#PARSER}
     */
    public AnalyzerStores(KnowledgeGraphStore parser, KnowledgeGraphStore model, Supplier<AnalyzerChoice> selected) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.model = Objects.requireNonNull(model, "model");
        this.selected = Objects.requireNonNull(selected, "selected");
    }

    /** Der Analysator, aus dessen Ablage gelesen wird. */
    public AnalyzerChoice selected() {
        AnalyzerChoice choice = selected.get();
        return choice == null ? AnalyzerChoice.PARSER : choice;
    }

    /** Die Ablage eines Analysators. */
    public KnowledgeGraphStore store(AnalyzerChoice choice) {
        return choice == AnalyzerChoice.MODEL ? model : parser;
    }

    private KnowledgeGraphStore reading() {
        return store(selected());
    }

    @Override
    public List<PageStatementAnalysis> all() {
        return reading().all();
    }

    @Override
    public List<PageStatementAnalysis> documents() {
        return reading().documents();
    }

    @Override
    public Optional<PageStatementAnalysis> find(PageKey key) {
        return reading().find(key);
    }

    @Override
    public Optional<PageStatementAnalysis> find(DocumentKey document) {
        return reading().find(document);
    }

    @Override
    public Optional<String> conllu(PageKey key) {
        return reading().conllu(key);
    }

    @Override
    public Optional<String> conllu(DocumentKey document) {
        return reading().conllu(document);
    }

    @Override
    public void replace(PageStatementAnalysis analysis) {
        store(AnalyzerChoice.of(analysis.analyzerId())).replace(analysis);
    }

    @Override
    public void remove(PageKey key) {
        parser.remove(key);
        model.remove(key);
    }

    @Override
    public void removeDocument(DocumentKey document) {
        parser.removeDocument(document);
        model.removeDocument(document);
    }
}
