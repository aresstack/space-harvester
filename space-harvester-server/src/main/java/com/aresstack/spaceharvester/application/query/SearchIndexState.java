package com.aresstack.spaceharvester.application.query;

import java.time.Instant;
import java.util.Objects;

/**
 * Stand des Suchindex einer Quelle: die bestätigte Generation, die Suchen jetzt lesen. Eine neue Generation entsteht
 * mit jedem Commit, der Suchergebnisse ändern kann.
 *
 * @param generation  undurchsichtige Kennung, an die Cursor gebunden sind
 * @param committedAt Zeitpunkt des Commits dieser Generation
 */
public record SearchIndexState(String generation, Instant committedAt) {

    public SearchIndexState {
        if (generation == null || generation.isEmpty() || generation.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("generation muss ein nicht leerer einzeiliger Text sein");
        }
        Objects.requireNonNull(committedAt, "committedAt");
    }
}
