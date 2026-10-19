package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.facts.FactConflictException;
import com.aresstack.spaceharvester.application.facts.UnknownFactException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fehler der manuellen und geprüften Fakten (#182) als Problem Details. */
@RestControllerAdvice
class FactErrorHandler {

    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(UnknownFactException.class)
    ProblemDetail unknown(UnknownFactException e) {
        return problem(HttpStatus.NOT_FOUND, "fact-not-found", "Fakt nicht gefunden",
                "Die Seite hat keinen Fakt mit dieser Kennung.");
    }

    @ExceptionHandler(FactConflictException.class)
    ProblemDetail conflict(FactConflictException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "fact-conflict", "Fakt geändert",
                "Der Fakt wurde inzwischen geändert; erneut lesen und mit der aktuellen revision wiederholen.");
        problem.setProperty("revision", e.revision());
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
