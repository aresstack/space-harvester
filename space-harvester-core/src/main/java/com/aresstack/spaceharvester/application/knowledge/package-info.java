/**
 * Aussagenanalyse auf Knopfdruck und globaler Knowledge Graph (#499, ADR 0024).
 *
 * <p>Eine Seite wird nur auf ausdrückliche Anforderung analysiert ({@link
 * com.aresstack.spaceharvester.application.knowledge.StatementAnalysis#analyze}); der Parser steckt hinter dem Port
 * {@link com.aresstack.spaceharvester.application.knowledge.StatementAnalyzer}, den nur die Desktop-App mit
 * {@code :nlp-parser} (MaltParser) belegt. Core und Dienst kennen kein Parsermodell. Jede Analyse liegt je Seite
 * atomar in der {@link com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphStore}; der Graph über alle
 * analysierten Seiten entsteht daraus ({@link com.aresstack.spaceharvester.application.knowledge.PageGraph}) und wird
 * über {@link com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph} mit Rechteprüfung abgefragt.
 *
 * <p>Keine automatische Berechnung: weder Abgleich, Cache-Füllung, Suche, Export noch „Analysen neu berechnen“ rufen
 * den Analysator. Gleicher Wortlaut zweier Erwähnungen ist höchstens eine lexikalische Kandidatenverbindung, nie eine
 * bestätigte Identität.
 */
package com.aresstack.spaceharvester.application.knowledge;
