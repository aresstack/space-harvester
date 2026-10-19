/**
 * Satzanalyse über das Chat-Modell (Prozessor {@code statements}), übernommen aus dem Enterprise AI Client (Issue #108,
 * Paket {@code astu.acropolis.textmining.statements}): {@link
 * com.aresstack.spaceharvester.application.enrichment.statements.Mention}s von Nominalgruppen an ihrer Stelle in genau
 * dieser Textprojektion, {@link com.aresstack.spaceharvester.application.enrichment.statements.Relation}s (Eigenschaften,
 * Folge von Handlungen) und {@link com.aresstack.spaceharvester.application.enrichment.statements.Event}s mit
 * Beteiligtenrollen. Ein austauschbarer {@link
 * com.aresstack.spaceharvester.application.enrichment.statements.StatementExtractor} schlägt je Absatz vor; der {@link
 * com.aresstack.spaceharvester.application.enrichment.statements.StatementValidator} behält nur, was im Text steht
 * (Wortbereiche, ein Satz, ein verbales Prädikat); eine optionale Ja/Nein-Prüfung bestätigt oder verwirft jede Aussage.
 * Nichts wird über Seiten hinweg zusammengeführt, offene Pronomen werden nicht erraten, eine vorgeschlagene Grundform
 * bleibt ein Vorschlag.
 *
 * <p>Läuft zusätzlich zum regelbasierten Weg ({@code entities}, {@code activities}, {@code relations}), standardmäßig
 * aus, nur mit Chat-Modell und nur für Quellen in {@code external-processing-sources} (ADR 0011). Das Chat-Modell kennt
 * das Paket nur über den Port {@link com.aresstack.spaceharvester.application.enrichment.statements.StatementChat}.
 */
package com.aresstack.spaceharvester.application.enrichment.statements;
