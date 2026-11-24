package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.changelog.SummaryUnavailableException;
import com.aresstack.spaceharvester.application.changelog.UnknownUpdateException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fehler des Änderungsprotokolls als Problem Details. */
@RestControllerAdvice(assignableTypes = ChangeLogController.class)
class ChangeLogErrorHandler {

    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(UnknownUpdateException.class)
    ProblemDetail unknownUpdate(UnknownUpdateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "Die Quelle hat kein Update mit dieser Kennung.");
        problem.setType(URI.create(TYPE_BASE + "update-not-found"));
        problem.setTitle("Update nicht gefunden");
        return problem;
    }

    @ExceptionHandler(SummaryUnavailableException.class)
    ProblemDetail summaryUnavailable(SummaryUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                switch (e.reason()) {
                    case NO_MODEL -> "Für Zusammenfassungen ist kein Chat-Modell eingestellt.";
                    case INITIAL_LOAD -> "Die Erstbefüllung wird nicht zusammengefasst.";
                    case EMPTY_AREA -> "Der Bereich hat noch keine Seite, die sich zusammenfassen lässt.";
                });
        problem.setType(URI.create(TYPE_BASE + "summary-unavailable"));
        problem.setTitle("Zusammenfassung nicht möglich");
        problem.setProperty("reason", e.reason().name());
        return problem;
    }
}
