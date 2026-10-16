package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.api.PageQueryController.CacheResponse;
import com.aresstack.spaceharvester.api.PageQueryController.PageEntry;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchResults;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Titel- und Metadatensuche über den Index des Harvesters (#28, erste Ausbaustufe). Liest nur gespeicherte Kopfdaten,
 * nie live aus der Quelle und nie Seiteninhalte.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/search", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Suche", description = "Titel- und Metadatensuche über den Cache des Harvesters")
public class SearchController {

    private final PageSearch search;

    public SearchController(PageSearch search) {
        this.search = search;
    }

    @GetMapping
    @Operation(summary = "Seiten einer Quelle nach Titel und Metadaten suchen, stapelweise",
            description = "Aus dem Index (origin=cache), nie live. Alle Bedingungen gelten zugleich. title: jedes "
                    + "Wort muss als ganzes Wort im Titel vorkommen, ohne Beachtung der Groß-/Kleinschreibung; "
                    + "Wörter trennt jedes Zeichen, das weder Buchstabe noch Ziffer ist. Keine Abfragesprache, kein "
                    + "Präfix, kein Volltext. Alle Stapel einer Suche stammen aus derselben Indexgeneration "
                    + "(index.generation); ersetzt ein Commit sie, endet die Suche mit 409 und beginnt ohne cursor "
                    + "neu. Ein Stapel kann kürzer als limit oder leer sein, auch wenn noch Treffer folgen: "
                    + "nextCursor mit denselben Parametern übergeben, bis er null ist (complete=true). Jeder Treffer "
                    + "nennt den Cache-Stand seines Space.")
    @ApiResponse(responseCode = "200", description = "Ein Stapel Treffer")
    @ApiResponse(responseCode = "400", description = "Ungültige Parameter, überschrittene Grenzen oder cursor einer "
            + "anderen Suche (invalid-request)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine metadata-Freigabe für einen genannten Space bzw. die "
            + "Quelle, oder keine Quelle mit dieser ID (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Für die Quelle liegt nichts im Index (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Der cursor gehört zu einer ersetzten Indexgeneration "
            + "(cache-generation-changed)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Index nicht eingerichtet oder nicht lesbar (cache-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public SearchResponse search(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence", required = true)
            @RequestParam String sourceId,
            @Parameter(description = "Nur diese Spaces (wiederholbar, höchstens " + PageSearchQuery.MAX_SPACE_KEYS
                    + "); ohne Angabe alle freigegebenen Spaces der Quelle")
            @RequestParam(required = false) List<String> spaceKey,
            @Parameter(description = "Wörter im Titel, höchstens " + PageSearchQuery.MAX_TITLE_TERMS + " Wörter und "
                    + PageSearchQuery.MAX_TITLE_LENGTH + " Zeichen", example = "Architektur Übersicht")
            @RequestParam(required = false) String title,
            @Parameter(description = "Genau diese Seite") @RequestParam(required = false) String pageId,
            @Parameter(description = "Nur direkte Kinder dieser Seite") @RequestParam(required = false)
            String parentPageId,
            @Parameter(description = "Genau diese Version der Quelle") @RequestParam(required = false) String version,
            @Parameter(description = "Geändert ab einschließlich (ISO-8601, UTC)", example = "2026-10-01T00:00:00Z")
            @RequestParam(required = false) String modifiedFrom,
            @Parameter(description = "Geändert vor ausschließlich (ISO-8601, UTC)")
            @RequestParam(required = false) String modifiedBefore,
            @Parameter(description = "Sortierung, jeweils dann nach pageId: title (aufsteigend, ohne Groß-/"
                    + "Kleinschreibung), -modifiedAt (neueste zuerst, ohne Änderungszeit zuletzt), pageId",
                    schema = @Schema(allowableValues = {"title", "-modifiedAt", "pageId"}))
            @RequestParam(defaultValue = "title") String sort,
            @Parameter(description = "nextCursor der vorigen Antwort; leer für den ersten Stapel")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Größe des Stapels, 1 bis " + PageSearch.MAX_LIMIT)
            @RequestParam(defaultValue = "50") int limit) {
        PageSearchQuery query = PageSearchQuery.of(sourceId, spaceKey, title, pageId, parentPageId, version,
                instant("modifiedFrom", modifiedFrom), instant("modifiedBefore", modifiedBefore), sort(sort));
        return SearchResponse.of(search.search(caller, query, cursor, limit));
    }

    private static SearchSort sort(String value) {
        return switch (value) {
            case "title" -> SearchSort.TITLE;
            case "-modifiedAt" -> SearchSort.MODIFIED_DESC;
            case "pageId" -> SearchSort.PAGE_ID;
            default -> throw new InvalidQueryException("sort kennt nur title, -modifiedAt und pageId");
        };
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidQueryException(name + " ist kein ISO-8601-Zeitpunkt in UTC");
        }
    }

    /**
     * Ein Stapel Suchtreffer.
     *
     * @param origin immer {@code cache}: aus dem Index des Harvesters, nicht live aus der Quelle
     */
    public record SearchResponse(String sourceId, String origin, IndexResponse index, String metadataHashSchema,
                                 String attributesHashSchema, List<HitResponse> hits,
                                 @Schema(nullable = true) String nextCursor, boolean complete) {

        static SearchResponse of(SearchResults results) {
            return new SearchResponse(results.sourceId(), "cache",
                    new IndexResponse(results.index().generation(), results.index().committedAt()),
                    MetadataHash.SCHEMA, AttributesHash.SCHEMA, results.hits().stream().map(HitResponse::of).toList(),
                    results.nextCursor(), results.complete());
        }
    }

    /** Stand des Suchindex, aus dem alle Treffer dieser Suche stammen. */
    public record IndexResponse(String generation, Instant committedAt) {
    }

    /**
     * Ein Treffer: dieselben Felder wie in der Seitenliste und der Cache-Stand seines Space.
     */
    public record HitResponse(PageEntry page, CacheResponse cache) {

        static HitResponse of(SearchHit hit) {
            return new HitResponse(PageEntry.of(hit.page()), CacheResponse.of(hit.generation()));
        }
    }
}
