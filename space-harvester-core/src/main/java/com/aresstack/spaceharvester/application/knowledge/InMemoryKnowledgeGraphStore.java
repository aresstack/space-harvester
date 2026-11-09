package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ablage im Speicher, für Tests und eingebettete Nutzung ohne Datenverzeichnis. */
public final class InMemoryKnowledgeGraphStore implements KnowledgeGraphStore {

    private final Map<PageKey, PageStatementAnalysis> analyses = new LinkedHashMap<>();

    @Override
    public synchronized List<PageStatementAnalysis> all() {
        List<PageStatementAnalysis> all = new ArrayList<>();
        analyses.values().forEach(analysis -> all.add(analysis.withResult(analysis.result().withoutConllu())));
        return all;
    }

    @Override
    public synchronized Optional<PageStatementAnalysis> find(PageKey key) {
        return Optional.ofNullable(analyses.get(key))
                .map(analysis -> analysis.withResult(analysis.result().withoutConllu()));
    }

    @Override
    public synchronized Optional<String> conllu(PageKey key) {
        return Optional.ofNullable(analyses.get(key)).map(analysis -> analysis.result().conllu());
    }

    @Override
    public synchronized void replace(PageStatementAnalysis analysis) {
        analyses.put(analysis.key(), analysis);
    }

    @Override
    public synchronized void remove(PageKey key) {
        analyses.remove(key);
    }
}
