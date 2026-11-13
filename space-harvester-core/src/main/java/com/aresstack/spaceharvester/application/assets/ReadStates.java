package com.aresstack.spaceharvester.application.assets;

import java.util.Optional;

/**
 * Mit welchem Stand der Extraktion die abgelegten Dokumente einer Quelle zuletzt vollständig neu gelesen sind
 * ({@link DocumentRereads.ReadState}, ADR 0026, Punkt 11, AP5c). Weicht der eingestellte Stand ab, startet „Neu lesen“
 * von selbst.
 */
public interface ReadStates {

    /** Merkt sich nichts; „Neu lesen“ startet dann nie von selbst. */
    ReadStates NONE = new ReadStates() {
        @Override
        public Optional<String> get(String sourceId) {
            return Optional.empty();
        }

        @Override
        public void put(String sourceId, String state) {
        }

        @Override
        public boolean remembers() {
            return false;
        }
    };

    /** Der zuletzt vollständig gelesene Stand einer Quelle; leer, wenn keiner bekannt ist. */
    Optional<String> get(String sourceId);

    /** Merkt sich den Stand einer Quelle, nachdem „Neu lesen“ mit ihm durchgelaufen ist. */
    void put(String sourceId, String state);

    /** Ob überhaupt etwas gemerkt wird; ohne startet „Neu lesen“ nie von selbst. */
    default boolean remembers() {
        return true;
    }
}
