/**
 * Belege fachlicher Aussagen (ADR 0032, Fortschreibung vom 26.11.2026; ADR 0041, Punkt 7). Ein Beleg ist eine
 * gemeinsame Abstraktion ({@link com.aresstack.spaceharvester.application.evidence.Evidence}) mit zwei Arten:
 *
 * <ul>
 *   <li>{@link com.aresstack.spaceharvester.application.evidence.TextEvidence Textbeleg}: Originalsatz einer Seite in
 *       einer Fassung. Geprüft wie bisher durch den Wortabgleich und die Prüfverfahren aus ADR 0032; daran ändert
 *       dieses Paket nichts.</li>
 *   <li>{@link com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence Quellstellenbeleg}: eine oder
 *       mehrere Codestellen eines Programms (auch in Copybooks oder weiteren Programmen) mit Revision, Parserfakten,
 *       Abhängigkeiten und Analyseversion. Geprüft technisch
 *       ({@link com.aresstack.spaceharvester.application.evidence.SourceLocationCheck}) und fachlich durch einen
 *       Menschen (W3).</li>
 * </ul>
 *
 * <p>Erkenntnisebenen bleiben getrennt ({@link com.aresstack.spaceharvester.application.evidence.InsightLevel}):
 * Parserfakt, KI-Hypothese, fachlich bestätigt. Ein Quellstellenbeleg belegt immer nur den Ist-Zustand
 * ({@link com.aresstack.spaceharvester.application.evidence.Bearing#IST}), nie eine geltende Soll-Regel: Ein Programm
 * kann fehlerhaft sein. Eine Bestätigung gilt nur für den Stand, an dem sie gegeben wurde
 * ({@link com.aresstack.spaceharvester.application.evidence.SourceLocationEvidence#fingerprint()}); ändert sich Code,
 * eine Abhängigkeit oder die Analyseversion, ist der Beleg möglicherweise veraltet, und die Bestätigung wandert nicht
 * mit.
 *
 * <p>Es gibt keinen zweiten Fachlogik-Bestand: Aussagen mit Quellstellenbeleg gehören in denselben Bestand wie
 * Aussagen aus Text. Reine Werttypen und Funktionen, ohne Ablage, ohne Parser und ohne KI.
 */
package com.aresstack.spaceharvester.application.evidence;
