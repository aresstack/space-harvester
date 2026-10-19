/**
 * Begriffsindex (#119, ADR 0019): Bezeichner (Nomen, Nominalgruppen, Kennungen) und Akronyme aller Seiten mit jeder
 * Fundstelle, quellenübergreifend abfragbar. Erkannt wird einmal je Seite beim Herunterladen oder Aktualisieren durch
 * den Prozessor {@code terms}; dessen Ergebnis legt die Anreicherungsablage zusätzlich als abgeleitete, indexierte
 * Begriffsdokumente ab ({@link com.aresstack.spaceharvester.application.terms.TermIndex}). Die globale Sicht
 * ({@link com.aresstack.spaceharvester.application.terms.TermQueries}) aggregiert dieselben Daten über alle
 * berechtigten Seiten, die Leseansicht ({@link com.aresstack.spaceharvester.application.terms.PageTerms}) zeigt nur
 * die geöffnete Seite. Ein Begriff ist ein Wortlaut, kein Fachkonzept: nichts wird lemmatisiert oder gleichgesetzt.
 */
package com.aresstack.spaceharvester.application.terms;
