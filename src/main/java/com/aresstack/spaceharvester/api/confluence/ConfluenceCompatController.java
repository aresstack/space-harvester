package com.aresstack.spaceharvester.api.confluence;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * Lesende Confluence-REST-Endpunkte aus dem Cache (#31). {@code /api/compat/confluence/{sourceId}} ist die Basis-URL,
 * die ein Confluence-Client statt der echten Instanz konfiguriert. Antworten sind die gespeicherten Original-Payloads
 * Byte für Byte; was der Cache nicht hält, wird abgelehnt statt erfunden, und nichts wird an Confluence
 * weitergereicht. Rechte prüft {@link PageQueries} wie für die eigene API. Unterstützter Umfang:
 * {@code docs/CONFLUENCE_COMPAT.md}.
 */
@RestController
@RequestMapping(path = ConfluenceCompatController.BASE + "/rest/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Confluence-kompatibel (Cache)",
        description = "Ausgewählte lesende Confluence-REST-Endpunkte aus dem Cache, ohne Schreiben und ohne "
                + "Durchgriff auf Confluence")
public class ConfluenceCompatController {

    static final String BASE = "/api/compat/confluence/{sourceId}";
    static final String CONTENT_FORMAT = "confluence-rest-content+json";
    static final String CACHE_GENERATION = "X-Cache-Generation";
    static final String CACHE_STATE = "X-Cache-State";

    private static final String EXPAND_PREFIX = CONTENT_FORMAT + ";expand=";

    private final PageQueries queries;

    public ConfluenceCompatController(PageQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/content/{id}")
    @Operation(summary = "GET /rest/api/content/{id} aus dem Cache",
            description = "Die gespeicherte Antwort von Confluence Byte für Byte. Sie enthält alle beim Ernten "
                    + "abgerufenen Erweiterungen (body.storage, version, space, ancestors, metadata.labels, history, "
                    + "history.lastUpdated), also auch nicht angefragte; _links zeigen auf die Confluence-Instanz. "
                    + "Ein expand, das die gespeicherte Antwort nicht enthält, ergibt 400 statt einer erfundenen "
                    + "Antwort. Nur status=current; version (ältere Revisionen) wird nicht unterstützt.")
    @ApiResponse(responseCode = "200", description = "Gespeicherte Confluence-Antwort")
    @ApiResponse(responseCode = "304", description = "Payload unverändert gegenüber If-None-Match")
    @ApiResponse(responseCode = "400", description = "expand, status oder version, die der Cache nicht bedienen kann")
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe für diese Quelle")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle, Seite nicht im Cache oder nicht sichtbar, "
            + "bewusst nicht unterschieden")
    @ApiResponse(responseCode = "503", description = "Cache nicht eingerichtet oder nicht lesbar")
    public ResponseEntity<byte[]> content(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Confluence-Content-ID", example = "123456") @PathVariable String id,
            @Parameter(description = "Kommagetrennte Erweiterungen wie bei Confluence")
            @RequestParam(required = false) String expand,
            @Parameter(description = "Nur current") @RequestParam(required = false) String status,
            @Parameter(description = "Nicht unterstützt") @RequestParam(required = false) String version,
            WebRequest request) {
        if (status != null && !"current".equals(status)) {
            throw new UnsupportedRequestException("status=" + status + " wird nicht unterstützt; der Cache hält nur "
                    + "die aktuelle Fassung (status=current)");
        }
        if (version != null) {
            throw new UnsupportedRequestException("version wird nicht unterstützt; der Cache hält nur die aktuelle "
                    + "Fassung einer Seite");
        }
        CachedPage page = queries.page(caller, sourceId, id, false);
        NativePayload payload = page.snapshot().payload();
        Set<String> stored = storedExpand(payload)
                .orElseThrow(() -> new NotInConfluenceFormatException(id));
        List<String> missing = requested(expand).stream().filter(path -> !covered(path, stored)).toList();
        if (!missing.isEmpty()) {
            throw new UnsupportedRequestException("expand=" + String.join(",", missing) + " ist im Cache nicht "
                    + "gespeichert; verfügbar: " + String.join(",", stored));
        }
        String etag = "\"" + payload.rawSha256() + "\"";
        if (request.checkNotModified(etag)) {
            return null;
        }
        CacheGeneration generation = page.generation();
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .contentType(MediaType.parseMediaType(payload.mediaType()))
                .header(CACHE_GENERATION, generation.id())
                .header(CACHE_STATE, generation.state().name().toLowerCase(Locale.ROOT))
                .body(payload.bytes());
    }

    /** Die Erweiterungen der gespeicherten Abrufform, oder leer, wenn die Payload keine Content-Antwort ist. */
    static Optional<Set<String>> storedExpand(NativePayload payload) {
        String format = payload.nativeFormat();
        if (format.equals(CONTENT_FORMAT)) {
            return Optional.of(Set.of());
        }
        if (!format.startsWith(EXPAND_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(paths(format.substring(EXPAND_PREFIX.length())));
    }

    static List<String> requested(String expand) {
        return expand == null ? List.of() : List.copyOf(paths(expand));
    }

    /**
     * Wie bei Confluence erweitert {@code a.b} auch {@code a}: Ein angefragter Pfad ist gedeckt, wenn er gespeichert
     * ist oder ein gespeicherter Pfad unter ihm liegt.
     */
    static boolean covered(String path, Set<String> stored) {
        return stored.stream().anyMatch(s -> s.equals(path) || s.startsWith(path + "."));
    }

    private static Set<String> paths(String list) {
        Set<String> paths = new TreeSet<>();
        Arrays.stream(list.split(",")).map(String::trim).filter(p -> !p.isEmpty()).forEach(paths::add);
        return paths;
    }

    /** Eine Anfrage, die der Cache nicht ohne erfundene Daten beantworten kann. */
    static final class UnsupportedRequestException extends RuntimeException {

        UnsupportedRequestException(String message) {
            super(message);
        }
    }

    /** Die gespeicherte Payload stammt nicht aus {@code /rest/api/content/{id}}. */
    static final class NotInConfluenceFormatException extends RuntimeException {

        NotInConfluenceFormatException(String id) {
            super("Content " + id + " liegt nicht als Confluence-Content-Antwort im Cache");
        }
    }
}
