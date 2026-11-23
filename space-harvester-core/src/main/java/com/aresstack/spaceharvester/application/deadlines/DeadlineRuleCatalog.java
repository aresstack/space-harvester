package com.aresstack.spaceharvester.application.deadlines;

import java.util.List;
import java.util.Optional;

/**
 * Bekannte, versionierte Fristenregeln (ADR 0035, Abschnitt 7). Im Repository stehen nur öffentliche Normen
 * ({@link PublicDeadlineRules}) und erfundene Testregeln; nicht öffentliche Regeln sind Benutzerdaten.
 */
public interface DeadlineRuleCatalog {

    /** Version des Katalogs; ein Abschluss der Ausnahmenprüfung gilt nur für diese. */
    String version();

    List<DeadlineRule> rules();

    default Optional<DeadlineRule> rule(String id) {
        return rules().stream().filter(rule -> rule.id().equals(id)).findFirst();
    }
}
