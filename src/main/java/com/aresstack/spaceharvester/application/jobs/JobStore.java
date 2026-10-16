package com.aresstack.spaceharvester.application.jobs;

import java.util.List;
import java.util.Optional;

/**
 * Ablage der Auftragszustände. {@link Jobs} schreibt bei jedem Zustandswechsel; die laufend steigenden Zähler eines
 * aktiven Auftrags liefert {@link Jobs} selbst. Eine dauerhafte Umsetzung (eingebetteter Lucene-Speicher, #26) sorgt
 * dafür, dass Aufträge einen Neustart überdauern; {@link Jobs} markiert dabei unterbrochene Aufträge als gescheitert.
 */
public interface JobStore {

    /** Legt den Zustand an oder ersetzt ihn (Schlüssel: {@link JobStatus#id()}). */
    void save(JobStatus status);

    Optional<JobStatus> find(String jobId);

    List<JobStatus> all();
}
