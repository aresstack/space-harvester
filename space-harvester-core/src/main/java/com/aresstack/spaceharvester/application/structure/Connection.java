package com.aresstack.spaceharvester.application.structure;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.Objects;

/**
 * Eine Kante des Strukturgraphen.
 *
 * @param from       Ausgangsseite; trägt die Kante selbst, damit eine Abfrage für mehrere Ausgangsseiten (etwa für die
 *                   Treffer einer Suche) ohne Bruch hinzukommen kann
 * @param kind       Art
 * @param target     Zielseite
 * @param provenance Herkunft; ihre {@link Provenance#basis()} ist immer {@code kind.basis()}
 */
public record Connection(PageKey from, ConnectionKind kind, PageRef target, Provenance provenance) {

    public Connection {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(provenance, "provenance");
        if (provenance.basis() != kind.basis()) {
            throw new IllegalArgumentException("Herkunft " + provenance.basis() + " passt nicht zu " + kind);
        }
    }

    /** Worauf die Kante beruht. */
    public Basis basis() {
        return kind.basis();
    }
}
