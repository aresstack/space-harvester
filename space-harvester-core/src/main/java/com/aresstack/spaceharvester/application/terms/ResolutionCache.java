package com.aresstack.spaceharvester.application.terms;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Was die KI-Akronymauflösung (#122) schon gefragt hat: je Fingerabdruck aus Verfahren, Modell, Akronym und
 * Fundstellen die Kennungen der Beziehungen, die die Antwort ergab, auch keine. Ein unveränderter Stand geht so nicht
 * noch einmal an das Modell. Enthält weder Begriffe noch Text noch Antworten; die Beziehungen selbst liegen in der
 * {@link TermRelationStore}.
 */
public interface ResolutionCache {

    /** @return die Kennungen der Beziehungen zu diesem Fingerabdruck, leer, wenn er unbekannt ist */
    Optional<List<String>> find(String fingerprint);

    /** Ersetzt den Bestand einer Quelle durch die Fingerabdrücke ihres letzten vollständigen Laufs. */
    void replace(String sourceId, Map<String, List<String>> entries);

    /** Im Speicher, etwa ohne Datenverzeichnis und für Tests. */
    static ResolutionCache inMemory() {
        Map<String, Map<String, List<String>>> bySource = new HashMap<>();
        return new ResolutionCache() {
            @Override
            public synchronized Optional<List<String>> find(String fingerprint) {
                for (Map<String, List<String>> entries : bySource.values()) {
                    List<String> ids = entries.get(fingerprint);
                    if (ids != null) {
                        return Optional.of(ids);
                    }
                }
                return Optional.empty();
            }

            @Override
            public synchronized void replace(String sourceId, Map<String, List<String>> entries) {
                bySource.put(sourceId, Map.copyOf(entries));
            }
        };
    }
}
