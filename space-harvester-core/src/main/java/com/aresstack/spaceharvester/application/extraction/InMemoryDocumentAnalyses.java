package com.aresstack.spaceharvester.application.extraction;

import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Analyseergebnisse im Arbeitsspeicher, je Dokumentstand ({@link DocumentRef} samt Version). Keine Datenbank; nach
 * einem Neustart ist alles weg. Neue Ergebnisse werden nach {@link FieldMerge} zusammengeführt, nie still
 * überschrieben; jede Änderung geht an die Beobachter (ADR 0027). Thread-sicher.
 */
public final class InMemoryDocumentAnalyses implements DocumentAnalysisProvider {

    private final Map<DocumentRef, DocumentAnalysis> results = new HashMap<>();
    private final Listeners<Consumer<DocumentRef>> listeners = new Listeners<>("Dokumentanalysen");

    @Override
    public synchronized DocumentAnalysis result(DocumentRef document) {
        Objects.requireNonNull(document, "document");
        DocumentAnalysis found = results.get(document);
        return found == null ? DocumentAnalysis.notAnalyzed(document) : found;
    }

    @Override
    public Subscription observe(Consumer<DocumentRef> changed) {
        return listeners.add(changed);
    }

    /** Legt das Ergebnis ab oder führt es mit dem vorhandenen zusammen, meldet es und liefert, was jetzt gilt. */
    public DocumentAnalysis record(DocumentAnalysis incoming) {
        Objects.requireNonNull(incoming, "incoming");
        DocumentAnalysis merged;
        synchronized (this) {
            merged = FieldMerge.merge(results.get(incoming.document()), incoming);
            results.put(incoming.document(), merged);
        }
        listeners.deliver(incoming.document(), listener -> listener.accept(incoming.document()));
        return merged;
    }

    /** Vergisst alle Ergebnisse des Dokumentstands. */
    public void forget(DocumentRef document) {
        boolean removed;
        synchronized (this) {
            removed = results.remove(document) != null;
        }
        if (removed) {
            listeners.deliver(document, listener -> listener.accept(document));
        }
    }
}
