/**
 * Suchlauf-Protokoll (#182), übernommen aus dem Enterprise AI Client ({@code search-run-api}, #153 dort): Jede Suche
 * hinterlässt einen {@link com.aresstack.spaceharvester.application.searchrun.SearchRun} mit Anfrage, Zeitpunkt,
 * Dauer, den gefragten Quellen samt Ausfällen und den Trefferzahlen je Stufe. Ein Lauf wird einmal geschrieben und nie
 * geändert; das Protokoll lässt keine Suche scheitern.
 *
 * <p>Anders als im Client nennt ein Lauf keine einzelnen Seiten (keine Kandidatenliste, keine Ereignisse je Kandidat)
 * und zählt nur Seiten, die der Aufrufer sehen darf. Kandidatenliste, Vertiefen einzelner Treffer und der Trichter einer
 * Recherche ({@code KnowledgeFunnel}) bleiben Chat-Auswertung im Client. Weil der Lauf den Anfragetext enthält, lesen
 * ihn nur sein Aufrufer und Verwalter ({@link com.aresstack.spaceharvester.application.searchrun.SearchRuns}); ins Log
 * kommt er nie.
 */
package com.aresstack.spaceharvester.application.searchrun;
