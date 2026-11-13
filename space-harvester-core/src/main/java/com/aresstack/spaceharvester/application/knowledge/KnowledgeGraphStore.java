package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Optional;

/**
 * Port der Ablage für Aussagenanalysen und damit des globalen Knowledge Graph (#499, ADR 0024). Nutzerdaten aus
 * ausdrücklichen Aktionen, kein Cache: Die Ablage liegt außerhalb des Cache-Verzeichnisses und übersteht dessen
 * Neuaufbau und Neustarts. Jedes Dokument wird einzeln und atomar ersetzt; andere bleiben unberührt.
 *
 * <p>Dokumente (ADR 0026, Punkt 10): Neben der Seite selbst kann jeder Anhang und jede enthaltene Datei eine eigene
 * Analyse haben. Die Methoden mit {@link PageKey} betreffen nur die Seite selbst und liefern nie Analysen von
 * Dokumenten; ausgenommen {@link #remove(PageKey)}, das die Seite samt allen ihren Dokumenten entfernt.
 */
public interface KnowledgeGraphStore {

    /** Alle gespeicherten Analysen von Seiten ohne CoNLL-U, in stabiler Reihenfolge; ohne Anhänge und Teile. */
    List<PageStatementAnalysis> all();

    /** Alle gespeicherten Analysen von Anhängen und enthaltenen Dateien ohne CoNLL-U, in stabiler Reihenfolge. */
    List<PageStatementAnalysis> documents();

    /** Die Analyse einer Seite ohne CoNLL-U. */
    Optional<PageStatementAnalysis> find(PageKey key);

    /** Die Analyse eines Dokuments ohne CoNLL-U; für die Seite selbst wie {@link #find(PageKey)}. */
    Optional<PageStatementAnalysis> find(DocumentKey document);

    /** Die vollständige UD-Analyse (CoNLL-U) einer Seite, für die Syntaxdetails. */
    Optional<String> conllu(PageKey key);

    /** Die vollständige UD-Analyse (CoNLL-U) eines Dokuments; für die Seite selbst wie {@link #conllu(PageKey)}. */
    Optional<String> conllu(DocumentKey document);

    /**
     * Ersetzt die Analyse des Dokuments ({@link PageStatementAnalysis#document()}) in einem Schritt, danach oder gar
     * nicht. Gleiche Analyse noch einmal ergibt denselben Zustand (idempotent).
     */
    void replace(PageStatementAnalysis analysis);

    /** Entfernt die Analyse einer Seite samt den Analysen aller ihrer Dokumente; ohne Analyse geschieht nichts. */
    void remove(PageKey key);

    /**
     * Entfernt die Analyse eines Dokuments, für einen Anhang auch die seiner enthaltenen Dateien. Für die Seite selbst
     * nur ihre eigene Analyse, ihre Dokumente bleiben.
     */
    void removeDocument(DocumentKey document);
}
