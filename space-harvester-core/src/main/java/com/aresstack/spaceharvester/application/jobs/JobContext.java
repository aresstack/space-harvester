package com.aresstack.spaceharvester.application.jobs;

/**
 * Sicht eines laufenden Auftrags auf seinen Job: Zähler melden und an sicheren Stellen
 * {@link #checkpoint()} aufrufen, damit Abbruch und Laufzeitgrenze greifen. Threadsicher.
 */
public interface JobContext {

    String jobId();

    /**
     * Kehrt zurück, solange der Auftrag weiterlaufen darf.
     *
     * @throws JobCancelledException wenn ein Abbruch angefordert wurde
     * @throws JobFailedException    mit Code {@code TIMEOUT}, wenn die Laufzeitgrenze überschritten ist
     */
    void checkpoint();

    boolean cancelRequested();

    void discovered(long pages);

    void processed(long pages);

    void written(long pages);

    void failed(long pages);

    /**
     * Meldet die Phase, in der der Auftrag jetzt arbeitet; ein Wechsel erscheint als Diagnoseereignis, spätere
     * Ereignisse und ein Scheitern tragen sie ({@link JobEvent}). Ohne Diagnose wirkungslos.
     *
     * @param component Komponente, etwa {@code Exports}
     * @param phase     Phase für Menschen, etwa {@code Einlesen}; enthält keine Seiten-IDs oder Titel
     */
    default void phase(String component, String phase) {
    }

    /**
     * Hält ein Diagnoseereignis fest; Auftrag, Zeit, Nummer und Phase ergänzt die Ausführung, Texte werden maskiert.
     * Ohne Diagnose wirkungslos. Ereignisse sieht nur der Auftraggeber; sie nennen also nichts, was er nicht lesen
     * darf.
     */
    default void record(JobEvent.Draft event) {
    }
}
