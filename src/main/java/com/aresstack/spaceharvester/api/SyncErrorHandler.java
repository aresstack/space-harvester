package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.sync.SyncAlreadyRunningException;
import com.aresstack.spaceharvester.application.sync.SyncUnavailableException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Fehler der Sync-API als Problem Details. Gilt nur für {@link SyncController}, damit Auftragsfehler anderer Routen
 * (Export) ihre eigenen Typen behalten. Ein fremder oder abgelaufener Abgleich ist nicht von einem unbekannten
 * unterscheidbar; ein laufender Abgleich eines anderen Clients wird nicht genannt.
 */
@RestControllerAdvice(assignableTypes = SyncController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class SyncErrorHandler {

    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(UnknownJobException.class)
    ProblemDetail unknown(UnknownJobException e) {
        return problem(HttpStatus.NOT_FOUND, "sync-not-found", "Abgleich nicht gefunden",
                "Es gibt keinen eigenen Abgleich mit dieser ID, oder er ist abgelaufen.");
    }

    @ExceptionHandler(SyncAlreadyRunningException.class)
    ProblemDetail alreadyRunning(SyncAlreadyRunningException e) {
        return problem(HttpStatus.CONFLICT, "sync-already-running", "Abgleich läuft bereits",
                "Für diesen Space wartet oder läuft bereits ein Abgleich.");
    }

    @ExceptionHandler(JobCapacityExceededException.class)
    ProblemDetail capacity(JobCapacityExceededException e) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "job-capacity-exceeded", "Keine Aufträge frei",
                "Derzeit werden keine weiteren Aufträge angenommen; später erneut versuchen.");
    }

    @ExceptionHandler(SyncUnavailableException.class)
    ProblemDetail unavailable(SyncUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "sync-unavailable", "Abgleich nicht verfügbar",
                "Cache-Speicher oder Inhaltsabruf sind noch nicht angebunden.");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
