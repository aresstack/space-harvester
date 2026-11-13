package com.aresstack.spaceharvester.application.jobs;

/**
 * Empfänger der Auftragsmeldungen (ADR 0027), angemeldet mit {@link Jobs#subscribe(String, JobListener)}. Meldungen
 * kommen auf dem Zustell-Thread des Core, nie auf dem Thread des Auftrags und nie auf dem EDT; ein Empfänger blockiert
 * dort nicht lange und wechselt für die Oberfläche selbst auf den EDT.
 */
public interface JobListener {

    /**
     * Der jüngste Stand eines Auftrags nach einer Änderung von Zustand, Zählern, Phase oder Grund einer Pause, je
     * Auftrag höchstens alle {@link com.aresstack.spaceharvester.application.observe.Coalescer#INTERVAL}. Kann auch
     * ohne sichtbare Änderung kommen, etwa wenn sie schon im zuvor gelesenen Stand stand.
     */
    void changed(JobStatus status);

    /** Der Auftrag ist gelöscht (vom Auftraggeber oder nach Ablauf der Aufbewahrung). */
    default void removed(String jobId) {
    }

    /** Ein neues Diagnoseereignis eines Auftrags (docs/DIAGNOSTICS.md), schon maskiert; jedes einzeln, ohne Bündeln. */
    default void event(JobEvent event) {
    }
}
