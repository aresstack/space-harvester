/**
 * Sprachliche Anreicherungen (#29) für deutschen Text, regelbasiert und ohne KI: Begriffe ({@code entities}) und
 * Eigenschaften ({@code properties}). Das Muster folgt dem Aussagenmodell aus enterprise-ai-client (Vorkommen statt
 * Konzepte, Fundstellen im Text, Suchwerte statt Identitäten, vorgeschlagene Grundformen bleiben Vorschläge, offene
 * Pronomen werden nicht aufgelöst, nichts wird über Seiten hinweg zusammengeführt); die Kandidaten kommen hier aber
 * aus festen Regeln ({@link com.aresstack.spaceharvester.application.enrichment.language.GermanText}) statt aus einem
 * Sprachmodell. Ergebnisse sind maschinell abgeleitete Hinweise, keine geprüften Fakten.
 */
package com.aresstack.spaceharvester.application.enrichment.language;
