/**
 * Stand und Status-quo-Sicht (ADR 0032, FL6): was fachlich gerade gilt, aus den gespeicherten Aussagen und ihren
 * Merkmalen (Verneinung, Verbindlichkeit, Zeitbezug), den Kennzeichen der Vorstufe, Labels und Lage der Seite und den
 * Entscheidungen des Menschen.
 *
 * <p>Rechnet nie neu und ruft weder Parser noch KI: Grundlage sind nur die Analysen im
 * {@link com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph}. Überholtes wird nach hinten sortiert,
 * nie versteckt. Entscheidungen des Menschen liegen außerhalb des Cache ({@link
 * com.aresstack.spaceharvester.application.standing.StandingDecisionStore}) und melden jede Änderung an Beobachter
 * (ADR 0027, kein Polling). Im selben Eintrag liegt die Prüfung einer Aussage durch Menschen (W3); sie wirkt an einer
 * Stelle beim Lesen der Analysen ({@link com.aresstack.spaceharvester.application.standing.DecisionReviews}), so dass
 * Zählung, Herkunftsanzeige und Export sie gleich sehen.
 */
package com.aresstack.spaceharvester.application.standing;
