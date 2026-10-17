package com.aresstack.spaceharvester.api.confluence;

import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.compat.ContentListing;
import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Fehler der Confluence-kompatiblen Routen im Fehlerkörper von Confluence ({@code statusCode}, {@code data},
 * {@code message}, {@code reason}) statt als Problem Details, damit Confluence-Clients sie wie gewohnt auswerten.
 * Wie Confluence unterscheidet ein 404 nicht zwischen fehlender und nicht sichtbarer Seite.
 */
@RestControllerAdvice(assignableTypes = ConfluenceCompatController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ConfluenceCompatErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ConfluenceCompatErrorHandler.class);

    @ExceptionHandler(CacheGenerationChangedException.class)
    ResponseEntity<Map<String, Object>> changed(CacheGenerationChangedException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "The Space Harvester cache changed while reading; retry");
    }

    @ExceptionHandler({CacheMissException.class, UnknownSourceException.class})
    ResponseEntity<Map<String, Object>> notFound(RuntimeException e) {
        return error(HttpStatus.NOT_FOUND, "No content found in the Space Harvester cache");
    }

    @ExceptionHandler(ConfluenceCompatController.NotInConfluenceFormatException.class)
    ResponseEntity<Map<String, Object>> notInFormat(ConfluenceCompatController.NotInConfluenceFormatException e) {
        return error(HttpStatus.NOT_FOUND, "No content found in the Space Harvester cache in Confluence format");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> accessDenied(AccessDeniedException e) {
        if (e.concealExistence()) {
            return error(HttpStatus.NOT_FOUND, "No content found in the Space Harvester cache");
        }
        return error(HttpStatus.FORBIDDEN, "Not permitted to read content from this source");
    }

    @ExceptionHandler({ConfluenceCompatController.UnsupportedRequestException.class, InvalidQueryException.class})
    ResponseEntity<Map<String, Object>> unsupported(RuntimeException e) {
        // Die Meldung stammt aus der eigenen Prüfung und nennt nur Parameter und Erweiterungen.
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ContentListing.IncompleteListingException.class)
    ResponseEntity<Map<String, Object>> incomplete(ContentListing.IncompleteListingException e) {
        // Die Meldung stammt aus der eigenen Prüfung und nennt keine Seite.
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage() + "; die Liste wäre unvollständig");
    }

    @ExceptionHandler(CacheUnavailableException.class)
    ResponseEntity<Map<String, Object>> cacheUnavailable(CacheUnavailableException e) {
        LOG.warn("Seitencache nicht lesbar ({})", e.getClass().getName());
        LOG.debug("Seitencache: ursprüngliche Meldung", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "The Space Harvester cache is not available");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        // Die Fassade liest nur; allowedInReadOnlyMode ist daher immer true.
        Map<String, Object> data = Map.of("authorized", status != HttpStatus.FORBIDDEN, "valid",
                status != HttpStatus.BAD_REQUEST, "allowedInReadOnlyMode", true, "errors", List.of(),
                "successful", false);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("statusCode", status.value(), "data", data, "message", message,
                        "reason", status.getReasonPhrase()));
    }
}
