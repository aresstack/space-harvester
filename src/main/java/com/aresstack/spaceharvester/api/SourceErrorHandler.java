package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Übersetzt Quellenfehler in Problem Details (RFC 9457). Der {@code type} unterscheidet eine unbekannte Quellen-ID
 * von Berechtigungs- und Verbindungsfehlern der Quelle; Zugangsdaten tauchen nie in der Antwort auf.
 */
@RestControllerAdvice
class SourceErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SourceErrorHandler.class);
    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(UnknownSourceException.class)
    ProblemDetail unknownSource(UnknownSourceException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "source-not-found", "Unbekannte Quelle", e.getMessage());
        problem.setProperty("sourceId", e.sourceId());
        return problem;
    }

    @ExceptionHandler(SourceAccessException.class)
    ProblemDetail sourceAccess(SourceAccessException e) {
        LOG.warn("Quelle {}: {} ({})", e.sourceId(), e.getMessage(), e.reason());
        ProblemDetail problem = switch (e.reason()) {
            case ACCESS_DENIED -> problem(HttpStatus.BAD_GATEWAY, "source-access-denied",
                    "Quelle verweigert den Zugriff", e.getMessage());
            case UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "source-unavailable",
                    "Quelle nicht erreichbar", e.getMessage());
            case NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "source-object-not-found",
                    "In der Quelle nicht gefunden", e.getMessage());
            case INVALID_RESPONSE -> problem(HttpStatus.BAD_GATEWAY, "source-invalid-response",
                    "Unerwartete Antwort der Quelle", e.getMessage());
            case UNSUPPORTED -> problem(HttpStatus.BAD_REQUEST, "source-unsupported",
                    "Von der Quelle nicht unterstützt", e.getMessage());
        };
        problem.setProperty("sourceId", e.sourceId());
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
