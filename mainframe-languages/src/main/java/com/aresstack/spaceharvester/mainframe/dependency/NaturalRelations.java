package com.aresstack.spaceharvester.mainframe.dependency;

import java.util.Optional;

/**
 * Port für die bibliotheksweiten Natural-Beziehungen, die nur der NDV-Server kennt (in MainframeMate
 * {@code NaturalAnalysisService.getCallHierarchy} über den Abhängigkeitsgraphen je Bibliothek und
 * {@code DdmAnalysisService.findDdmUsers}). Der NDV-Bereich liefert den Adapter; ohne ihn bleibt die Hierarchie leer.
 *
 * <p>Aufrufe dürfen blockieren und laufen nie auf dem EDT.
 */
public interface NaturalRelations {

    /**
     * Was {@code object} in {@code library} aufruft, rekursiv bis {@code depth}; die Wurzel steht für das Objekt
     * selbst, ihre Kinder sind die Aufgerufenen. {@link Optional#empty()}, solange der Graph der Bibliothek noch nicht
     * gebaut ist.
     */
    Optional<CallNode> callees(String library, String object, int depth);

    /**
     * Wer {@code object} in {@code library} aufruft (bei einem DDM: welche Programme es benutzen), rekursiv bis
     * {@code depth}; sonst wie {@link #callees}.
     */
    Optional<CallNode> callers(String library, String object, int depth);

    /** Ohne NDV-Verbindung: kein Graph. */
    NaturalRelations NONE = new NaturalRelations() {
        @Override
        public Optional<CallNode> callees(String library, String object, int depth) {
            return Optional.empty();
        }

        @Override
        public Optional<CallNode> callers(String library, String object, int depth) {
            return Optional.empty();
        }
    };
}
