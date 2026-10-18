/**
 * Progressive Suche (ADR 0017): Die ursprüngliche Anfrage sucht sofort, eine austauschbare
 * {@link com.aresstack.spaceharvester.application.query.progressive.QueryExpansionStrategy} erzeugt gleichzeitig
 * Varianten, die in Wellen laufen. Jeder Fund wird gegen die ursprüngliche Anfrage bewertet und nach Quelle und
 * Seiten-ID zusammengeführt; die Liste wird neu geordnet, nie ersetzt. Die
 * {@link com.aresstack.spaceharvester.application.query.progressive.SearchQualityPolicy} entscheidet nach jeder Welle,
 * ob weitergesucht wird. Der {@link com.aresstack.spaceharvester.application.query.progressive.SearchOrchestrator}
 * liefert das fertige Ergebnis mit Abschlussgrund und meldet unterwegs Ereignisse.
 */
package com.aresstack.spaceharvester.application.query.progressive;
