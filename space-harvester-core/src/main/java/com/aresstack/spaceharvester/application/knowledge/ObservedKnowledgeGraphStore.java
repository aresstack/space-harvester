package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Eine Ablage für Aussagenanalysen, die nach jedem Schreiben meldet (ADR 0027, kein Polling): neue, ersetzte und
 * entfernte Analysen, gleich aus welchem Auftrag (Aussagenanalyse, Prüfschritt, Vorstufe, Entzug der KI-Freigabe).
 * Liest unverändert aus der eigentlichen Ablage. Die Meldung läuft auf dem schreibenden Thread und soll nur vormerken,
 * etwa {@link KnowledgeGraph#analysesChanged()}.
 */
public final class ObservedKnowledgeGraphStore implements KnowledgeGraphStore {

    private final KnowledgeGraphStore delegate;
    private final Runnable changed;

    /**
     * @param delegate die eigentliche Ablage
     * @param changed  nach jedem erfolgreichen Schreiben; eine Ausnahme darin bricht das Schreiben nicht ab
     */
    public ObservedKnowledgeGraphStore(KnowledgeGraphStore delegate, Runnable changed) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    @Override
    public List<PageStatementAnalysis> all() {
        return delegate.all();
    }

    @Override
    public List<PageStatementAnalysis> documents() {
        return delegate.documents();
    }

    @Override
    public Optional<PageStatementAnalysis> find(PageKey key) {
        return delegate.find(key);
    }

    @Override
    public Optional<PageStatementAnalysis> find(DocumentKey document) {
        return delegate.find(document);
    }

    @Override
    public Optional<String> conllu(PageKey key) {
        return delegate.conllu(key);
    }

    @Override
    public Optional<String> conllu(DocumentKey document) {
        return delegate.conllu(document);
    }

    @Override
    public void replace(PageStatementAnalysis analysis) {
        delegate.replace(analysis);
        notifyChanged();
    }

    @Override
    public void remove(PageKey key) {
        delegate.remove(key);
        notifyChanged();
    }

    @Override
    public void removeDocument(DocumentKey document) {
        delegate.removeDocument(document);
        notifyChanged();
    }

    private void notifyChanged() {
        try {
            changed.run();
        } catch (RuntimeException e) {
            System.getLogger(ObservedKnowledgeGraphStore.class.getName()).log(System.Logger.Level.WARNING,
                    "Meldung nach dem Schreiben einer Aussagenanalyse fehlgeschlagen: {0}", e.getClass().getName());
        }
    }
}
