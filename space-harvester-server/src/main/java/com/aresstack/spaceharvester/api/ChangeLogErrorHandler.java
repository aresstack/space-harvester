package com.aresstack.spaceharvester.api;

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
}
