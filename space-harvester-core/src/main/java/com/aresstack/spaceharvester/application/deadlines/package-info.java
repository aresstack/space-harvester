/**
 * Fristen-Fachlogik nach Grundsatz und Ausnahme (ADR 0035). Drei getrennte Bereiche: Dokumentfakten liefert die
 * Extraktion ({@code application.extraction}, über {@link ExtractionStage}), Rechtswissen der Port
 * {@link LegalKnowledgeRepository}, Prüfergebnisse {@link DeadlineAssessment}. Entscheidungen fallen nur hier; die
 * Oberfläche zeigt an. Keine Swing-, WebDAV-, Lucene- oder Anbietertypen.
 */
package com.aresstack.spaceharvester.application.deadlines;
