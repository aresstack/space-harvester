package com.aresstack.spaceharvester.application.snapshot;

/**
 * Beobachter der Inhaltsabrufe bei der Quelle (ADR 0027): Der Bestand meldet „lädt“, sobald ein Abruf beginnt, gleich
 * ob für den Beschaffungsplaner, das Lesen, einen Abgleich oder einen Export, und das Ende auch dann, wenn der Abruf
 * scheitert und nichts in den Cache schreibt.
 */
@FunctionalInterface
public interface PageLoads {

    /** Keine Beobachtung. */
    PageLoads NONE = key -> () -> { };

    /**
     * Vor dem Abruf, auf dem abrufenden Thread; hält ihn nicht auf.
     *
     * @return das Ende des Abrufs; wird nach Erfolg wie nach Fehler genau einmal aufgerufen
     */
    Runnable started(PageKey key);
}
