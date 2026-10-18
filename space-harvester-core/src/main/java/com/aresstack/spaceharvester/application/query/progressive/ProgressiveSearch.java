package com.aresstack.spaceharvester.application.query.progressive;

import java.util.concurrent.CompletableFuture;

/** Ein laufender Vorgang der progressiven Suche (ADR 0017). */
public interface ProgressiveSearch {

    /** Kennung des Vorgangs. */
    String id();

    /** Das fertige Ergebnis; scheitert nie, eine gescheiterte Suche endet mit {@link ProgressiveResult.Completion#FAILED}. */
    CompletableFuture<ProgressiveResult> result();

    /**
     * Bricht ab, etwa bei einer neuen Eingabe: Was gefunden ist, bleibt im Ergebnis, es endet mit
     * {@link ProgressiveResult.Completion#CANCELLED}; auch das Laden der Funde in den Cache wird abgebrochen.
     *
     * @return ob der Vorgang noch lief
     */
    boolean cancel();
}
