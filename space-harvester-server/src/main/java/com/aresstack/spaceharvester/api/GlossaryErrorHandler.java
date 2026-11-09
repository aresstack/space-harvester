package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.glossary.GlossaryConflictException;
import com.aresstack.spaceharvester.application.glossary.GlossaryUnavailableException;
import com.aresstack.spaceharvester.application.glossary.UnknownGlossaryEntryException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fehler des Fachglossars als Problem Details: unbekannt 404, veraltete Revision 409, ohne Modell 503. */
@RestControllerAdvice
class GlossaryErrorHandler {

    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(UnknownGlossaryEntryException.class)
    ProblemDetail unknown(UnknownGlossaryEntryException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "glossary-entry-not-found", "Unbekannter Glossareintrag",
                "Zu dieser ID gibt es keinen sichtbaren Glossareintrag.");
        problem.setProperty("entryId", e.entryId());
        return problem;
    }

    @ExceptionHandler(GlossaryConflictException.class)
    ProblemDetail conflict(GlossaryConflictException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "glossary-conflict", "Glossareintrag wurde geändert",
                "Der Eintrag hat inzwischen eine andere Revision; bitte neu laden.");
        problem.setProperty("entryId", e.entryId());
        problem.setProperty("revision", e.revision());
        return problem;
    }

    @ExceptionHandler(GlossaryUnavailableException.class)
    ProblemDetail unavailable(GlossaryUnavailableException e) {
        if (e.reason() == GlossaryUnavailableException.Reason.NO_MODEL) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "ai-unavailable", "Kein KI-Modell",
                    "Für „Fachbegriffe ermitteln“ ist kein Chat-Modell eingestellt; die Pflege von Hand geht weiter.");
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
