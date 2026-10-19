package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.terms.AcronymResolutionUnavailableException;
import com.aresstack.spaceharvester.application.terms.RelationConflictException;
import com.aresstack.spaceharvester.application.terms.UnknownRelationException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Fehler der Begriffsbeziehungen (#120) als Problem Details: unbekannt oder verborgen 404, veraltete Revision 409; die
 * KI-Akronymauflösung (#122) ohne Modell 503, ohne Freigabe der Quelle 409.
 */
@RestControllerAdvice
class TermRelationErrorHandler {

    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(UnknownRelationException.class)
    ProblemDetail unknown(UnknownRelationException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "relation-not-found", "Unbekannte Beziehung",
                "Zu dieser ID gibt es keine sichtbare Beziehung.");
        problem.setProperty("relationId", e.relationId());
        return problem;
    }

    @ExceptionHandler(RelationConflictException.class)
    ProblemDetail conflict(RelationConflictException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "relation-conflict", "Beziehung wurde geändert",
                "Die Beziehung hat inzwischen eine andere Revision; bitte neu laden.");
        problem.setProperty("relationId", e.relationId());
        problem.setProperty("revision", e.revision());
        return problem;
    }

    @ExceptionHandler(AcronymResolutionUnavailableException.class)
    ProblemDetail unavailable(AcronymResolutionUnavailableException e) {
        if (e.reason() == AcronymResolutionUnavailableException.Reason.NO_MODEL) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ai-unavailable", "Kein KI-Modell",
                    "Für die KI-Akronymauflösung ist kein Chat-Modell eingestellt.");
        }
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "external-processing-not-allowed",
                "Quelle nicht freigegeben", "Text dieser Quelle geht nicht an den KI-Dienst "
                        + "(external-processing-sources).");
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
