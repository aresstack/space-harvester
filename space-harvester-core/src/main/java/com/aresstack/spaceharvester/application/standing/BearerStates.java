package com.aresstack.spaceharvester.application.standing;

import java.util.List;
import java.util.Objects;

/**
 * Die Zustände eines Trägers über alle gewählten Seiten (ADR 0032, FL6): „Antrag – ist unvollständig (gilt), war
 * genehmigt (überholt)“. Gebündelt wird nach der Grundform des Trägers ohne Groß- und Kleinschreibung; das ist nur
 * gleicher Wortlaut, keine bestätigte Identität.
 *
 * @param bearer Grundform des Trägers (erste Schreibung)
 * @param states Zustandsaussagen, sortiert wie die Sicht
 */
public record BearerStates(String bearer, List<StatusQuoEntry> states) {

    public BearerStates {
        Objects.requireNonNull(bearer, "bearer");
        states = List.copyOf(states);
    }
}
