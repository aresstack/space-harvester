package com.aresstack.spaceharvester.application.jobs;

/**
 * Führt eine Aktion aus, die {@link Jobs} von sich aus anstößt (ADR 0025, Punkt 17): den Wiederanlauf eines Auftrags,
 * der sich wegen fehlender Verbindung angehalten hat ({@link JobAction#RESUME}), und das während des Laufs angeforderte
 * „Nur Fehler neu versuchen“ ({@link JobAction#RETRY_FAILED}). {@code Jobs} kennt die Anwendungsfälle nicht; die
 * Anwendung verbindet das mit {@code JobActions#perform}, damit derselbe Weg gilt wie für einen Klick.
 */
@FunctionalInterface
public interface JobRecovery {

    /**
     * @param status der Auftrag, wie er gerade steht
     * @param action {@link JobAction#RESUME} oder {@link JobAction#RETRY_FAILED}
     * @throws RuntimeException wenn es nicht ging; {@code Jobs} versucht einen Wiederanlauf dann später erneut
     */
    void perform(JobStatus status, JobAction action);
}
