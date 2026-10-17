package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.export.ExportStorageFullException;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobStillRunningException;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Übersetzt Fehler der Auftrags- und Export-API in Problem Details. Ein fremder Auftrag antwortet wie ein unbekannter,
 * die Antwort nennt keine Job-ID.
 */
@RestControllerAdvice
class JobErrorHandler {

    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(UnknownJobException.class)
    ProblemDetail unknownJob(UnknownJobException e) {
        return problem(HttpStatus.NOT_FOUND, "job-not-found", "Auftrag nicht gefunden",
                "Es gibt keinen eigenen Auftrag mit dieser ID, oder er ist abgelaufen.");
    }

    @ExceptionHandler(ExportsController.ArtifactNotFoundException.class)
    ProblemDetail artifactNotFound(ExportsController.ArtifactNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "artifact-not-found", "Artefakt nicht gefunden",
                "Der Export hat (noch) kein Artefakt, oder es enthält keine Datei dieses Namens.");
    }

    @ExceptionHandler(EventNotFoundException.class)
    ProblemDetail eventNotFound(EventNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "event-not-found", "Ereignis nicht gefunden",
                "Der Auftrag hat kein Diagnoseereignis mit dieser Nummer (mehr).");
    }

    @ExceptionHandler(JobStillRunningException.class)
    ProblemDetail stillRunning(JobStillRunningException e) {
        return problem(HttpStatus.CONFLICT, "job-running", "Auftrag läuft noch",
                "Ein wartender oder laufender Auftrag lässt sich nicht löschen; erst abbrechen und das Ende "
                        + "abwarten.");
    }

    @ExceptionHandler(JobCapacityExceededException.class)
    ProblemDetail tooManyJobs(JobCapacityExceededException e) {
        // Die Meldung stammt aus der eigenen Prüfung und nennt nur die Grenze.
        return problem(HttpStatus.TOO_MANY_REQUESTS, "too-many-jobs", "Zu viele Aufträge", e.getMessage());
    }

    @ExceptionHandler(ExportStorageFullException.class)
    ProblemDetail exportStorageFull(ExportStorageFullException e) {
        return problem(HttpStatus.INSUFFICIENT_STORAGE, "export-storage-full", "Exportablage voll", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
