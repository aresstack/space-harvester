package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Übersetzt verweigerte Rechteprüfungen in Problem Details. Die Antwort nennt weder Gründe noch angefragte IDs.
 * Verlangt die Prüfung Verdeckung, gleicht die Antwort der für ein in der Quelle unbekanntes Objekt.
 */
@RestControllerAdvice
class AccessErrorHandler {

    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException e) {
        if (e.concealExistence()) {
            ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "source-object-not-found",
                    "In der Quelle nicht gefunden",
                    "Das angefragte Objekt existiert in der Quelle nicht oder ist nicht sichtbar.");
            problem.setProperty("sourceId", e.sourceId());
            return problem;
        }
        return problem(HttpStatus.FORBIDDEN, "access-denied", "Zugriff verweigert",
                "Für diese Anfrage fehlt die Freigabe.");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
