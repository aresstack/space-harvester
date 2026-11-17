package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Die Sachlage einer Aussage für den Vergleich (ADR 0032, FL6): Art, Handelnder bzw. Träger, Prädikat und alle übrigen
 * Beteiligten mit Rolle, Präposition und Schlüssel. Schlüssel der Begriffe aus {@code GlossaryDerivation#keyOf}, das
 * Prädikat als Grundform klein. Das Genus verbi gehört nicht dazu: Die Rollen sind nach Sinn vergeben.
 *
 * @param others übrige Beteiligte, geordnet; zwei Sachlagen sind nur gleich, wenn alle übrigen Beteiligten gleich sind
 *               oder bei beiden fehlen
 */
public record Situation(StatementKind kind, String subject, String predicate, List<Participant> others) {

    static final Comparator<Participant> ORDER = Comparator.comparing(Participant::role)
            .thenComparing(participant -> participant.marker() == null ? "" : participant.marker())
            .thenComparing(Participant::key);

    public Situation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(predicate, "predicate");
        others = others.stream().sorted(ORDER).toList();
    }

    /** Schlüssel des ersten Betroffenen oder leer. */
    public String object() {
        return others.stream().filter(participant -> participant.role() == ParsedStatements.Role.AFFECTED_OBJECT)
                .map(Participant::key).findFirst().orElse("");
    }

    /**
     * Ein übriger Beteiligter.
     *
     * @param marker Präposition klein oder {@code null}
     */
    public record Participant(ParsedStatements.Role role, String marker, String key) {

        public Participant {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(key, "key");
        }
    }
}
