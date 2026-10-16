package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.query.RefreshUnavailableException;
import com.aresstack.spaceharvester.application.query.TreeTooLargeException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Übersetzt Fehler der cachegestützten Query-API in Problem Details (RFC 9457), mit festen Texten je Fehlerart.
 * Ein Cache-Miss sagt nie, ob das Objekt fehlt oder nur nicht sichtbar ist.
 */
@RestControllerAdvice
class QueryErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(QueryErrorHandler.class);
    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(CacheMissException.class)
    ProblemDetail cacheMiss(CacheMissException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "cache-miss", "Nicht im Cache",
                "Das angefragte Objekt liegt nicht im Cache oder ist nicht sichtbar.");
        problem.setProperty("sourceId", e.sourceId());
        return problem;
    }

    @ExceptionHandler(CacheUnavailableException.class)
    ProblemDetail cacheUnavailable(CacheUnavailableException e) {
        LOG.warn("Seitencache nicht lesbar ({})", e.getClass().getName());
        LOG.debug("Seitencache: ursprüngliche Meldung", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "cache-unavailable", "Cache nicht verfügbar",
                "Der Seitencache ist nicht eingerichtet oder derzeit nicht lesbar.");
    }

    @ExceptionHandler(CacheGenerationChangedException.class)
    ProblemDetail generationChanged(CacheGenerationChangedException e) {
        return problem(HttpStatus.CONFLICT, "cache-generation-changed", "Cache-Stand geändert",
                "Der Cache-Stand hat sich seit dem ersten Stapel geändert; die Abfrage ohne cursor neu beginnen.");
    }

    @ExceptionHandler(TreeTooLargeException.class)
    ProblemDetail treeTooLarge(TreeTooLargeException e) {
        // Die Meldung stammt aus der eigenen Prüfung und nennt nur die Grenze.
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "tree-too-large", "Baum zu groß", e.getMessage()
                + "; die Seitenliste paginiert abrufen oder den Space exportieren.");
    }

    @ExceptionHandler(RefreshUnavailableException.class)
    ProblemDetail refreshUnavailable(RefreshUnavailableException e) {
        return problem(HttpStatus.NOT_IMPLEMENTED, "refresh-unavailable", "Aktualitätsprüfung nicht verfügbar",
                "Eine Prüfung gegen die Quelle ist noch nicht möglich; ohne refresh=true wird der gekennzeichnete "
                        + "Cache-Stand geliefert.");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
