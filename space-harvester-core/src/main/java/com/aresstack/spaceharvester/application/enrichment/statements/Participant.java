package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.Objects;

/**
 * Ein Beteiligter eines {@link Event}: seine semantische Rolle und die {@link Mention}, die sie füllt.
 *
 * @param role    Rolle
 * @param mention Erwähnung
 */
public record Participant(ParticipantRole role, Mention mention) {

    public Participant {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(mention, "mention");
    }

    @Override
    public String toString() {
        return role + "=" + mention.key();
    }
}
