package com.aresstack.spaceharvester.application.standing;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Port der Ablage für Entscheidungen zum Stand (ADR 0032, FL6), wie die Prüfablage der Fakten: Einträge plus ein nur
 * angehängtes Protokoll. Nutzerdaten, kein Cache: übersteht Neuaufbau des Cache und Neustarts.
 */
public interface StandingDecisionStore {

    /** Alle Einträge in stabiler Reihenfolge. */
    List<StandingDecision> all();

    Optional<StandingDecision> find(StandingDecision.Target target);

    /** Ersetzt {@code put}, entfernt {@code removed} und hängt {@code audit} an, alles oder nichts. */
    void write(Collection<StandingDecision> put, Collection<StandingDecision.Target> removed, List<AuditEntry> audit);

    /** Das Protokoll eines Ziels, älteste Änderung zuerst. */
    List<AuditEntry> history(StandingDecision.Target target);

    /**
     * Eine Zeile im Protokoll.
     *
     * @param action   {@link #SET} oder {@link #REMOVE}
     * @param actor    {@code AccessContext#jobOwner()}
     * @param standing Stand danach; beim Entfernen der zuletzt gültige
     * @param revision Revision danach; beim Entfernen die zuletzt gültige
     */
    record AuditEntry(StandingDecision.Target target, String action, String actor, Instant at, Standing standing,
                      long revision) {

        public static final String SET = "set";
        public static final String REMOVE = "remove";

        public AuditEntry {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(at, "at");
        }
    }

    /** Eine Ablage nur im Speicher, etwa im Test oder ohne Datenverzeichnis. */
    static StandingDecisionStore inMemory() {
        return new InMemoryStandingDecisionStore();
    }
}
