package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetTooLargeException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fehler der Asset-Ernte (#19) als Problem Details, mit festen Texten wie {@link SourceErrorHandler}. */
@RestControllerAdvice
class AssetErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AssetErrorHandler.class);
    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    @ExceptionHandler(AssetTooLargeException.class)
    ProblemDetail tooLarge(AssetTooLargeException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_GATEWAY, "asset-too-large", "Anhang zu groß",
                "Der Anhang ist größer als die konfigurierte Grenze und wird nicht gekürzt ausgeliefert.");
        problem.setProperty("sourceId", e.sourceId());
        problem.setProperty("maxBytes", e.maxBytes());
        return problem;
    }

    @ExceptionHandler(AssetStoreException.class)
    ProblemDetail store(AssetStoreException e) {
        LOG.error("Asset-Ablage: {} ({})", e.getMessage(),
                e.getCause() == null ? "-" : e.getCause().getClass().getName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "asset-store-unavailable", "Asset-Ablage nicht verfügbar",
                "Die Ablage für Anhänge kann gerade nicht lesen oder schreiben.");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
