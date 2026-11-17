package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.Objects;

/**
 * Entscheidung eines Menschen zu einer Seite oder einer Aussage (ADR 0032, FL6). Je Ziel gibt es genau einen Eintrag;
 * heute trägt er nur den Stand. Die fachliche Prüfung einer Aussage (bestätigt, abgelehnt; FL4) kommt später als
 * weiteres Feld in denselben Eintrag, keine zweite Ablage daneben.
 *
 * <p>Eine Entscheidung zur Seite hängt am {@link PageKey} und bleibt über neue Fassungen gültig. Eine Entscheidung zur
 * Aussage hängt an deren vollständiger ID ({@code quelle/seite@<Eingabe-Hash>#s<Satz>.v<Verb>}), also an genau dieser
 * Fassung: Ändert sich der Text oder verschwindet die Aussage (etwa Aussagen der Vorstufe nach Entzug der
 * KI-Freigabe), greift sie nicht mehr und erscheint als veraltet, statt still an eine andere Aussage zu wandern.
 *
 * @param page        Seite
 * @param statementId ID der Aussage oder {@code null} für die Seite selbst
 * @param standing    gesetzter Stand
 * @param revision    Änderungszähler ab 1 für optimistisches Sperren
 * @param createdBy   wer zuerst entschieden hat ({@code AccessContext#jobOwner()})
 * @param createdAt   wann zuerst
 * @param decidedBy   wer zuletzt entschieden hat
 * @param decidedAt   wann zuletzt
 */
public record StandingDecision(PageKey page, String statementId, Standing standing, long revision, String createdBy,
                               Instant createdAt, String decidedBy, Instant decidedAt) {

    public StandingDecision {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision beginnt bei 1");
        }
        if (statementId != null && statementId.isBlank()) {
            throw new IllegalArgumentException("statementId darf nicht leer sein");
        }
    }

    /** Ob die Entscheidung die Seite selbst betrifft. */
    public boolean ofPage() {
        return statementId == null;
    }

    /** Schlüssel des Eintrags. */
    public Target target() {
        return new Target(page, statementId);
    }

    /**
     * Ziel einer Entscheidung.
     *
     * @param page        Seite
     * @param statementId ID der Aussage oder {@code null} für die Seite
     */
    public record Target(PageKey page, String statementId) {

        public Target {
            Objects.requireNonNull(page, "page");
            if (statementId != null && statementId.isBlank()) {
                throw new IllegalArgumentException("statementId darf nicht leer sein");
            }
        }
    }
}
