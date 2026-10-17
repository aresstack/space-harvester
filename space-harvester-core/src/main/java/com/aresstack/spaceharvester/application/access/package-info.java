/**
 * Rechteprüfung für alles, was die API ausliefert: Quellen, Spaces, Seitenmetadaten, Baumknoten, Suchtreffer, native
 * Payloads, Enrichments, Converter-Ergebnisse und Exporte.
 *
 * <p>Zwei Grenzen bleiben getrennt: Das technische Confluence-Konto darf ernten, daraus folgt aber keine Freigabe
 * für irgendeinen Aufrufer. Wer etwas aus dem Harvester lesen darf, entscheidet allein die {@link
 * com.aresstack.spaceharvester.application.access.AccessPolicy} für den {@link
 * com.aresstack.spaceharvester.application.access.AccessContext} des Aufrufers. Anwendungsfälle und Controller
 * fragen ausschließlich den {@link com.aresstack.spaceharvester.application.access.AccessGuard}; er verweigert, wenn die
 * Richtlinie nicht ausdrücklich erlaubt (fail closed).
 *
 * <p>Heute gibt es nur technische Clients mit konfigurierter Korpusfreigabe ({@link
 * com.aresstack.spaceharvester.application.access.CorpusReleasePolicy}). Die delegierte Prüfung im Namen eines
 * Endnutzers (#24, Teil C) wird eine weitere {@code AccessPolicy}, ohne dass sich Aufrufer ändern.
 */
package com.aresstack.spaceharvester.application.access;
