package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.signin.SignInPendingException;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
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
 * von Berechtigungs- und Verbindungsfehlern der Quelle.
 *
 * <p>Meldungen der Quelle oder des Transports (Hosts, Pfade, Antworttexte, im schlimmsten Fall Zugangsdaten) gehen
 * nie an Clients: {@code detail} ist ein fester Text je Fehlerart. Das Log nennt Quelle, Fehlerart und Ausnahmetyp;
 * die ursprüngliche Meldung steht nur auf DEBUG.
 */
@RestControllerAdvice
class SourceErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SourceErrorHandler.class);
    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(UnknownSourceException.class)
    ProblemDetail unknownSource(UnknownSourceException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "source-not-found", "Unbekannte Quelle",
                "Zu dieser ID ist keine Quelle konfiguriert.");
        problem.setProperty("sourceId", e.sourceId());
        return problem;
    }

    @ExceptionHandler(InvalidQueryException.class)
    ProblemDetail invalidQuery(InvalidQueryException e) {
        // Die Meldung stammt aus der eigenen Validierung, nicht aus der Quelle.
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Ungültige Anfrage", e.getMessage());
    }

    /**
     * Lesen auf Abruf (ADR 0018): die Quelle verlangt einen Einmalcode, die Anforderung ist offen. Erst nach bestandener
     * Prüfung der Seite geworfen; die Antwort nennt die Anforderung für {@code /api/v1/sign-ins}, nie Code oder Token.
     */
    @ExceptionHandler(SignInPendingException.class)
    ProblemDetail signInPending(SignInPendingException e) {
        ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "sign-in-required", "Einmalcode erforderlich",
                "Die Quelle verlangt einen Einmalcode; nach der Eingabe kann die Anfrage wiederholt werden.");
        problem.setProperty("sourceId", e.sourceId());
        problem.setProperty("signInRequestId", e.signInRequestId());
        return problem;
    }

    @ExceptionHandler(SourceAccessException.class)
    ProblemDetail sourceAccess(SourceAccessException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        LOG.warn("Quelle {}: Zugriff fehlgeschlagen ({}, {})", e.sourceId(), e.reason(), cause.getClass().getName());
        LOG.debug("Quelle {}: ursprüngliche Meldung", e.sourceId(), e);
        ProblemDetail problem = switch (e.reason()) {
            case ACCESS_DENIED -> problem(HttpStatus.BAD_GATEWAY, "source-access-denied",
                    "Quelle verweigert den Zugriff",
                    "Die Quelle hat die Anmeldung oder den Zugriff abgelehnt.");
            case UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "source-unavailable",
                    "Quelle nicht erreichbar", "Die Quelle ist derzeit nicht erreichbar.");
            case RATE_LIMITED -> problem(HttpStatus.TOO_MANY_REQUESTS, "source-rate-limited",
                    "Quelle drosselt Anfragen", "Die Quelle hat zu viele Anfragen gemeldet. Bitte später erneut versuchen.");
            case NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "source-object-not-found",
                    "In der Quelle nicht gefunden",
                    "Das angefragte Objekt existiert in der Quelle nicht oder ist nicht sichtbar.");
            case INVALID_RESPONSE -> problem(HttpStatus.BAD_GATEWAY, "source-invalid-response",
                    "Unerwartete Antwort der Quelle", "Die Antwort der Quelle war nicht auswertbar.");
            case UNSUPPORTED -> problem(HttpStatus.BAD_REQUEST, "source-unsupported",
                    "Von der Quelle nicht unterstützt", "Die Quelle unterstützt diese Anfrage nicht, etwa einen Cursor aus einer anderen Abfrage oder eine Seiten-ID in fremder Form.");
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
