package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Port der Ablage für Entscheidungen zu Stand und Prüfung (ADR 0032, FL6 und W3), wie die Prüfablage der Fakten:
 * Einträge plus ein nur angehängtes Protokoll. Nutzerdaten, kein Cache: übersteht Neuaufbau des Cache und Neustarts.
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
     * Eine Zeile im Protokoll. Jede Änderung ist eine eigene Zeile; das Zurücknehmen einer Prüfung ist eine eigene
     * Handlung ({@link #UNREVIEW}), kein Entfernen des Eintrags.
     *
     * @param action   {@link #SET} (Stand gesetzt), {@link #REMOVE} (Stand bzw. ganzer Eintrag entfernt),
     *                 {@link #REVIEW} (Prüfung gesetzt) oder {@link #UNREVIEW} (Prüfung zurückgenommen)
     * @param actor    {@code AccessContext#jobOwner()}
     * @param standing Stand danach; beim Entfernen des Stands der zuletzt gültige; {@code null} ohne Stand
     * @param review   Prüfung danach; beim Zurücknehmen die zuletzt gültige; {@code null} ohne Prüfung
     * @param revision Revision danach; wird der Eintrag dabei gelöscht, die zuletzt gültige
     */
    record AuditEntry(StandingDecision.Target target, String action, String actor, Instant at, Standing standing,
                      ParsedStatements.Review review, long revision) {

        public static final String SET = "set";
        public static final String REMOVE = "remove";
        public static final String REVIEW = "review";
        public static final String UNREVIEW = "unreview";

        public AuditEntry {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(at, "at");
        }

        /** Ohne Prüfung (Zeilen vor W3). */
        public AuditEntry(StandingDecision.Target target, String action, String actor, Instant at, Standing standing,
                          long revision) {
            this(target, action, actor, at, standing, null, revision);
        }
    }

    /** Eine Ablage nur im Speicher, etwa im Test oder ohne Datenverzeichnis. */
    static StandingDecisionStore inMemory() {
        return new InMemoryStandingDecisionStore();
    }
}
