package com.aresstack.spaceharvester.application.jobs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Die eigentliche Arbeit eines Auftrags. {@link Jobs} ruft {@link #start(JobContext)} auf einem eigenen Thread auf; der
 * Auftrag belegt seinen Ausführungsplatz, bis die zurückgegebene Stufe abgeschlossen ist. So passen blockierende
 * Aufträge über den heutigen synchronen Quellzugriff ({@link #blocking(Body)}) und spätere Aufträge über einen
 * asynchronen Transport in denselben Mechanismus.
 *
 * <p>Ausgang: normal abgeschlossen = {@link JobState#COMPLETED}; {@link JobCancelledException} aus
 * {@link JobContext#checkpoint()} = {@link JobState#CANCELLED}; jede andere Ausnahme = {@link JobState#FAILED}, mit Code
 * und Nachricht nur aus {@link JobFailedException} oder einer {@code SourceAccessException}, sonst neutral.
 */
@FunctionalInterface
public interface JobTask {

    CompletionStage<Void> start(JobContext context);

    /** Ein Auftrag, der seine Arbeit blockierend auf dem Thread erledigt, auf dem er gestartet wird. */
    static JobTask blocking(Body body) {
        return context -> {
            try {
                body.run(context);
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        };
    }

    /** Blockierende Arbeit eines Auftrags. */
    @FunctionalInterface
    interface Body {
        void run(JobContext context) throws Exception;
    }
}
