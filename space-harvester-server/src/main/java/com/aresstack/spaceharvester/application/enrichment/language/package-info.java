/**
 * Sprachliche Anreicherungen (#29) für deutschen Text, regelbasiert und ohne KI: Begriffe ({@code entities}),
 * Eigenschaften ({@code properties}), Handlungen mit Beteiligten ({@code activities}) und Relationen
 * ({@code relations}: Subjekt–Prädikat–Objekt, Begriffsrelationen, zeitliche Folge). Das Muster folgt dem
 * Aussagenmodell aus enterprise-ai-client (Vorkommen statt Konzepte, Fundstellen im Text, Suchwerte statt Identitäten,
 * vorgeschlagene Grundformen bleiben Vorschläge, offene Pronomen werden nicht aufgelöst, nichts wird über Seiten
 * hinweg zusammengeführt); die Kandidaten kommen hier aber aus festen Regeln
 * ({@link com.aresstack.spaceharvester.application.enrichment.language.GermanText}) statt aus einem Sprachmodell.
 * Ergebnisse sind maschinell abgeleitete Hinweise, keine geprüften Fakten.
 */
package com.aresstack.spaceharvester.application.enrichment.language;
