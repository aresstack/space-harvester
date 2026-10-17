package com.aresstack.spaceharvester.application.jobs;

import java.util.List;

/**
 * Ablage der Diagnoseereignisse ({@link JobEvent}). Ereignisse leben so lange wie ihr Auftrag: {@link Jobs} entfernt
 * sie mit ihm ({@link #delete}). Pflichten jeder Umsetzung prüft {@code JobEventStoreContract} in den Testquellen.
 */
public interface JobEventStore {

    /** Höchstens so viele Ereignisse hält die Ablage je Auftrag; weitere werden verworfen. */
    int MAX_EVENTS_PER_JOB = 500;

    void append(JobEvent event);

    /** Ereignisse eines Auftrags nach {@link JobEvent#sequence()}. */
    List<JobEvent> events(String jobId);

    /** Die jüngsten Ereignisse aller Aufträge, neueste zuerst. */
    List<JobEvent> recent(int limit);

    /** Entfernt die Ereignisse eines Auftrags. */
    void delete(String jobId);
}
