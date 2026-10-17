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
}
