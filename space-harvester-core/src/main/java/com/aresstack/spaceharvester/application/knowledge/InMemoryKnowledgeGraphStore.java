package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ablage im Speicher, für Tests und eingebettete Nutzung ohne Datenverzeichnis. */
public final class InMemoryKnowledgeGraphStore implements KnowledgeGraphStore {

    private final Map<DocumentKey, PageStatementAnalysis> analyses = new LinkedHashMap<>();

    @Override
    public synchronized List<PageStatementAnalysis> all() {
        return withoutConllu(false);
    }

    @Override
    public synchronized List<PageStatementAnalysis> documents() {
        return withoutConllu(true);
    }

    @Override
    public synchronized Optional<PageStatementAnalysis> find(PageKey key) {
        return find(DocumentKey.page(key));
    }

    @Override
    public synchronized Optional<PageStatementAnalysis> find(DocumentKey document) {
        return Optional.ofNullable(analyses.get(document))
                .map(analysis -> analysis.withResult(analysis.result().withoutConllu()));
    }

    @Override
    public synchronized Optional<String> conllu(PageKey key) {
        return conllu(DocumentKey.page(key));
    }

    @Override
    public synchronized Optional<String> conllu(DocumentKey document) {
        return Optional.ofNullable(analyses.get(document)).map(analysis -> analysis.result().conllu());
    }

    @Override
    public synchronized void replace(PageStatementAnalysis analysis) {
        analyses.put(analysis.document(), analysis);
    }

    @Override
    public synchronized void remove(PageKey key) {
        analyses.keySet().removeIf(document -> document.page().equals(key));
    }

    @Override
    public synchronized void removeDocument(DocumentKey document) {
        if (document.isPage() || document.isEmbedded()) {
            analyses.remove(document);
        } else {
            analyses.keySet().removeIf(other -> other.page().equals(document.page())
                    && other.attachmentId().equals(document.attachmentId()));
        }
    }

    private List<PageStatementAnalysis> withoutConllu(boolean documents) {
        List<PageStatementAnalysis> found = new ArrayList<>();
        analyses.values().stream().filter(analysis -> analysis.ofDocument() == documents)
                .forEach(analysis -> found.add(analysis.withResult(analysis.result().withoutConllu())));
        return found;
    }
}
