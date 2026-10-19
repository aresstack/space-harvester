/**
 * Beschaffung von Seiteninhalten (ADR 0016): der {@link com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler}
 * als eine Warteschlange für Vordergrund (Funde der Suche) und Hintergrund, mit reservierter Kapazität für den
 * Vordergrund, Grenzen je Quelle und Hochstufen statt doppeltem Laden, und die dauerhafte
 * {@link com.aresstack.spaceharvester.application.acquisition.BackgroundFill}. Welche Funde einer Suche jetzt laden,
 * entscheidet die {@link com.aresstack.spaceharvester.application.query.CandidateSelectionStrategy} der Suche. Beide
 * Wege laden über denselben Beschaffungs-Use-Case ({@code PageQueries.page}); dieses Paket hängt nicht von der Suche ab.
 */
package com.aresstack.spaceharvester.application.acquisition;
