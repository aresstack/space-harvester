package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.searchrun.SearchRun;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.searchrun.SearchRuns;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lesezugriff auf das Suchlauf-Protokoll (#182). Ein Lauf enthält den Suchtext; lesen dürfen ihn nur sein Aufrufer und
 * technische Clients mit {@code manage-configuration}, für alle anderen gibt es ihn nicht (404). Seiten nennt ein Lauf
 * nie, gezählt sind nur Seiten, die sein Aufrufer sehen durfte.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/search/runs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Suche", description = "Schlagwortsuche über die Suche der Quellen, Titel- und Metadatensuche im Index")
public class SearchRunController {

    private final SearchRuns runs;

    public SearchRunController(SearchRuns runs) {
        this.runs = runs;
    }

    @GetMapping
    @Operation(summary = "Eigene Suchläufe auflisten",
            description = "Die jüngsten Läufe des Aufrufers (Schlagwortsuche, sort=relevance, progressive Suche und "
                    + "ihre Suchanfragen), neueste zuerst; Clients mit manage-configuration sehen alle. Jeder Lauf "
                    + "nennt Suchtext, Quellen mit Ausgang, Trefferzahlen je Stufe und Dauer. Mit "
                    + "space-harvester.search.run-log.enabled=false ist die Liste leer.")
    @ApiResponse(responseCode = "200", description = "Die Läufe")
    @ApiResponse(responseCode = "400", description = "limit außerhalb 1 bis " + SearchRunLog.MAX_LIMIT
            + " (invalid-request)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    public RunsResponse list(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Höchstens so viele Läufe, 1 bis " + SearchRunLog.MAX_LIMIT)
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > SearchRunLog.MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + SearchRunLog.MAX_LIMIT + " liegen");
        }
        return new RunsResponse(runs.enabled(), runs.recent(caller, limit).stream().map(RunResponse::of).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Einen Suchlauf lesen",
            description = "Der Lauf mit seinen Teilläufen (parts): bei der progressiven Suche je Suchanfrage ein Lauf "
                    + "der Schlagwortsuche, bei sort=relevance die Schlagwortsuche der Vorbereitung. Die Kennung "
                    + "steht in der Antwort der Suche (searchRun) bzw. ist die Kennung der progressiven Suche.")
    @ApiResponse(responseCode = "200", description = "Der Lauf")
    @ApiResponse(responseCode = "404", description = "Unbekannt, abgelaufen oder ein Lauf eines anderen Aufrufers")
    public ResponseEntity<RunDetailResponse> find(@Parameter(hidden = true) AccessContext caller,
                                                  @PathVariable String id) {
        return runs.find(caller, id).map(view -> new RunDetailResponse(RunResponse.of(view.run()),
                view.parts().stream().map(RunResponse::of).toList())).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Liste der Läufe.
     *
     * @param enabled ob protokolliert wird
     */
    public record RunsResponse(boolean enabled, List<RunResponse> runs) {
    }

    /** Ein Lauf mit seinen Teilläufen. */
    public record RunDetailResponse(RunResponse run, List<RunResponse> parts) {
    }

    /**
     * Ein Lauf.
     *
     * @param kind       {@code keyword}, {@code relevance} oder {@code progressive}
     * @param partOf     Kennung des übergeordneten Laufs oder {@code null}
     * @param stages     Trefferzahlen je Stufe ({@code queries}, {@code discovered}, {@code load-queued},
     *                   {@code pending}, {@code confirmed}, {@code delivered}); eine nicht erhobene Stufe fehlt
     * @param completion Abschlussgrund der progressiven Suche oder {@code null}
     */
    public record RunResponse(String id, @Schema(nullable = true) String partOf,
                              @Schema(allowableValues = {"keyword", "relevance", "progressive"}) String kind,
                              String query, Instant startedAt, long durationMillis, List<SourceResponse> sources,
                              Map<String, Integer> stages, boolean complete,
                              @Schema(nullable = true) String completion) {

        static RunResponse of(SearchRun run) {
            Map<String, Integer> stages = new LinkedHashMap<>();
            run.stages().forEach(stage -> stages.put(name(stage.stage().name()), stage.count()));
            return new RunResponse(run.id().value(), run.partOf() == null ? null : run.partOf().value(),
                    name(run.kind().name()), run.query(), run.startedAt(), run.duration().toMillis(),
                    run.sources().stream().map(SourceResponse::of).toList(), stages, run.complete(),
                    run.completion() == null ? null : name(run.completion()));
        }
    }

    /**
     * Ausgang einer Quelle.
     *
     * @param reason bei {@code unavailable} der Grund, etwa {@code unavailable} oder {@code rate-limited}
     */
    public record SourceResponse(String sourceId,
                                 @Schema(allowableValues = {"searched", "indexed", "not-supported", "unavailable"})
                                 String status, @Schema(nullable = true) String reason) {

        static SourceResponse of(SearchRun.SourceOutcome source) {
            return new SourceResponse(source.sourceId(), name(source.status().name()),
                    source.reason() == null ? null : name(source.reason()));
        }
    }

    private static String name(String constant) {
        return constant.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
