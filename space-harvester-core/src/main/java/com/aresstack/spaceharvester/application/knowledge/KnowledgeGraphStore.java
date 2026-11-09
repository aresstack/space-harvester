package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Optional;

/**
 * Port der Ablage für Aussagenanalysen und damit des globalen Knowledge Graph (#499, ADR 0024). Nutzerdaten aus
 * ausdrücklichen Aktionen, kein Cache: Die Ablage liegt außerhalb des Cache-Verzeichnisses und übersteht dessen
 * Neuaufbau und Neustarts. Jede Seite wird einzeln und atomar ersetzt; andere Seiten bleiben unberührt.
 */
public interface KnowledgeGraphStore {

    /** Alle gespeicherten Analysen ohne CoNLL-U, in stabiler Reihenfolge. */
    List<PageStatementAnalysis> all();

    /** Die Analyse einer Seite ohne CoNLL-U. */
    Optional<PageStatementAnalysis> find(PageKey key);

    /** Die vollständige UD-Analyse (CoNLL-U) einer Seite, für die Syntaxdetails. */
    Optional<String> conllu(PageKey key);

    /**
     * Ersetzt die Analyse der Seite in einem Schritt, danach oder gar nicht. Gleiche Analyse noch einmal ergibt
     * denselben Zustand (idempotent).
     */
    void replace(PageStatementAnalysis analysis);

    /** Entfernt die Analyse einer Seite; ohne Analyse geschieht nichts. */
    void remove(PageKey key);
}
