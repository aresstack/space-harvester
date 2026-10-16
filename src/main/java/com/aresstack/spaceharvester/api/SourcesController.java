package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Quellen", description = "Konfigurierte Wissensquellen und ihre Spaces")
public class SourcesController {

    private final SourceQueries queries;

    public SourcesController(SourceQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "Konfigurierte Quellen (ohne Verbindungs- und Zugangsdaten)")
    public List<SourceResponse> sources() {
        return queries.sources().stream().map(SourceResponse::of).toList();
    }

    @GetMapping("/{sourceId}/spaces")
    @Operation(summary = "Alle Spaces einer Quelle, die mit den konfigurierten Zugangsdaten sichtbar sind",
            description = "Vollständige, ungekürzte Liste in der Reihenfolge der Quelle.")
    @ApiResponse(responseCode = "200", description = "Spaces der Quelle")
    @ApiResponse(responseCode = "404", description = "Keine Quelle mit dieser ID konfiguriert (source-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Quelle verweigert den Zugriff (source-access-denied) oder "
            + "antwortet fehlerhaft (source-invalid-response)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Quelle nicht erreichbar (source-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public SpacesResponse spaces(@Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
                                 @PathVariable String sourceId) {
        return new SpacesResponse(sourceId, queries.partitions(sourceId).stream().map(SpaceResponse::of).toList());
    }

    /** Eine konfigurierte Quelle. */
    public record SourceResponse(String id, String type, String title) {

        static SourceResponse of(HarvestSource source) {
            return new SourceResponse(source.id(), source.type(), source.title());
        }
    }

    /** Die Spaces einer Quelle. */
    public record SpacesResponse(String sourceId, List<SpaceResponse> spaces) {
    }

    /**
     * Ein Space.
     *
     * @param key            Space-Key, z. B. {@code DEV}
     * @param name           Anzeigename
     * @param homeResourceId ID der Startseite in der Quelle oder {@code null}, wenn der Space keine hat
     */
    public record SpaceResponse(String key, String name,
                                @Schema(nullable = true) String homeResourceId) {

        static SpaceResponse of(Partition partition) {
            return new SpaceResponse(partition.key(), partition.name(), partition.homeResourceId());
        }
    }
}
