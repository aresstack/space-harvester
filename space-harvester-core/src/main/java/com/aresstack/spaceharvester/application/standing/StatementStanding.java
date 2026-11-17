package com.aresstack.spaceharvester.application.standing;

import java.util.Objects;

/**
 * Stand einer Aussage mit Herkunft und Grund (ADR 0032, FL6).
 *
 * @param standing Stand
 * @param basis    woraus er stammt
 * @param reason   fester Grund
 * @param mark     bei {@link StandingReason#PAGE_OUTDATED}, {@link StandingReason#PAGE_OUTDATED_PLAN} und
 *                 {@link StandingReason#PAGE_DRAFT} aus Labels oder Titeln: die Seite (auch ein Vorfahr) und das Wort,
 *                 das gegriffen hat; sonst {@code null}
 */
public record StatementStanding(Standing standing, StandingBasis basis, StandingReason reason, PageMark mark) {

    public StatementStanding {
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(reason, "reason");
    }

    public StatementStanding(Standing standing, StandingBasis basis, StandingReason reason) {
        this(standing, basis, reason, null);
    }
}
