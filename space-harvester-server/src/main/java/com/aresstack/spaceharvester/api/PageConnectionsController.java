package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.structure.Connection;
import com.aresstack.spaceharvester.application.structure.ConnectionKind;
import com.aresstack.spaceharvester.application.structure.PageConnections;
import com.aresstack.spaceharvester.application.structure.PageRef;
import com.aresstack.spaceharvester.application.structure.Provenance;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Strukturgraph (Paket G1): Zusammenhänge einer Seite. Ergänzt den Wissensgraphen der OO-Analyse, ersetzt ihn nicht.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Suche", description = "Schlagwortsuche über die Suche der Quellen, Titel- und Metadatensuche im Index")
public class PageConnectionsController {

    private static final String KINDS = "parent, child, links-to, linked-from, term-relation und similar-section";

    private final PageConnections connections;

    public PageConnectionsController(PageConnections connections) {
        this.connections = connections;
    }

    @GetMapping("/{sourceId}/pages/{pageId}/connections")
    @Operation(summary = "Zusammenhänge einer Seite (Strukturgraph): Hierarchie, Verweise, bestätigte "
            + "Begriffsbeziehungen, ähnliche Abschnitte",
            description = "Für Navigation und Recherche. Ergänzt den Wissensgraphen der OO-Analyse, ersetzt ihn "
                    + "nicht. Abgeleitet bei der Abfrage aus lokal gespeicherten Daten; nichts wird gespeichert, "
                    + "nichts geht an den KI-Dienst, Aussagen werden nicht gelesen. Jede Kante trägt Art (kind), "
                    + "Grundlage (basis: explicit = Hierarchie oder Verweis der Quelle, confirmed = bestätigte "
                    + "Begriffsbeziehung, computed = berechnete Ähnlichkeit) und Herkunft (provenance). coverage "
                    + "sagt je Art complete, partial oder unavailable; truncated nennt die Arten, die eine Grenze "
                    + "gekürzt hat. Die Ausgangsseite braucht metadata und content; Ziele aus Hierarchie und "
                    + "Verweisen metadata, die übrigen auch content. Was fehlt, fehlt ohne Spur, auch in Zählern. "
                    + "Bekannte Lücke: Der Dienst hat kein Verzeichnis der Verweise, links-to und linked-from sind "
                    + "hier immer unavailable.")
    @ApiResponse(responseCode = "200", description = "ConnectionsResponse")
    @ApiResponse(responseCode = "400", description = "Unbekannte Art oder Grenze (invalid-query)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Seite nicht im Cache oder nicht lesbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ConnectionsResponse connections(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Seiten-ID der Quelle", example = "123456") @PathVariable String pageId,
            @Parameter(description = "Arten, mehrfach oder kommagetrennt; ohne Angabe alle",
                    schema = @Schema(allowableValues = {"parent", "child", "links-to", "linked-from",
                            "term-relation", "similar-section"}))
            @RequestParam(required = false) List<String> kind,
            @Parameter(description = "Höchstens so viele Kanten je Art, 1 bis " + PageConnections.Query.MAX_PER_KIND)
            @RequestParam(defaultValue = "" + PageConnections.Query.DEFAULT_MAX_PER_KIND) int limit,
            @Parameter(description = "Höchstens so viele Kanten insgesamt, 1 bis " + PageConnections.Query.MAX_EDGES)
            @RequestParam(defaultValue = "" + PageConnections.Query.DEFAULT_MAX_EDGES) int maxEdges,
            @Parameter(description = "Schwelle der Kosinusähnlichkeit für similar-section, -1 bis 1")
            @RequestParam(defaultValue = "" + PageConnections.Query.DEFAULT_MIN_SIMILARITY) double minSimilarity) {
        PageConnections.Query query = new PageConnections.Query(kinds(kind), limit, maxEdges, minSimilarity);
        return ConnectionsResponse.of(connections.connections(caller, sourceId, pageId, query));
    }

    private static Set<ConnectionKind> kinds(List<String> names) {
        if (names == null || names.isEmpty()) {
            return EnumSet.allOf(ConnectionKind.class);
        }
        Set<ConnectionKind> kinds = EnumSet.noneOf(ConnectionKind.class);
        for (String name : names) {
            try {
                kinds.add(ConnectionKind.valueOf(name.strip().toUpperCase(Locale.ROOT).replace('-', '_')));
            } catch (IllegalArgumentException e) {
                throw new InvalidQueryException("kind kennt nur " + KINDS);
            }
        }
        return kinds;
    }

    static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Die Zusammenhänge.
     *
     * @param coverage        je gewünschter Art: complete, partial oder unavailable
     * @param truncated       Arten, die eine Grenze gekürzt hat
     * @param pagesConsidered Zahl der sichtbaren lokal gespeicherten Seiten, die gelesen wurden, samt Ausgangsseite
     * @param complete        ob jede gewünschte Art complete ist
     */
    public record ConnectionsResponse(PageRefResponse seed, List<EdgeResponse> edges, Map<String, String> coverage,
                                      List<String> truncated, int pagesConsidered, boolean complete) {

        static ConnectionsResponse of(PageConnections.Connections result) {
            Map<String, String> coverage = new LinkedHashMap<>();
            for (ConnectionKind kind : ConnectionKind.values()) {
                if (result.coverage().containsKey(kind)) {
                    coverage.put(name(kind), name(result.coverage().get(kind)));
                }
            }
            List<String> truncated = result.truncated().stream().sorted().map(PageConnectionsController::name)
                    .toList();
            return new ConnectionsResponse(PageRefResponse.of(result.seed()),
                    result.edges().stream().map(EdgeResponse::of).toList(), coverage, truncated,
                    result.pagesConsidered(), result.complete());
        }
    }

    /** Eine Seite. */
    public record PageRefResponse(String sourceId, String pageId, @Schema(nullable = true) String title,
                                  @Schema(nullable = true) String spaceKey) {

        static PageRefResponse of(PageRef page) {
            return new PageRefResponse(page.key().sourceId(), page.key().pageId(), page.title(), page.spaceKey());
        }
    }

    /**
     * Eine Kante.
     *
     * @param fromSourceId Quelle der Ausgangsseite
     * @param fromPageId   Ausgangsseite
     * @param basis        explicit, confirmed oder computed
     */
    public record EdgeResponse(String fromSourceId, String fromPageId,
                               @Schema(allowableValues = {"parent", "child", "links-to", "linked-from",
                                       "term-relation", "similar-section"}) String kind,
                               @Schema(allowableValues = {"explicit", "confirmed", "computed"}) String basis,
                               PageRefResponse target, ProvenanceResponse provenance) {

        static EdgeResponse of(Connection edge) {
            return new EdgeResponse(edge.from().sourceId(), edge.from().pageId(), name(edge.kind()),
                    name(edge.basis()), PageRefResponse.of(edge.target()), ProvenanceResponse.of(edge.provenance()));
        }
    }

    /**
     * Herkunft einer Kante; je {@code type} sind nur die passenden Felder gesetzt.
     *
     * @param type         hierarchy, link, term-relation oder similarity
     * @param relationType bei term-relation: abbreviation, synonym, variant oder related
     * @param origin       bei term-relation: wie die (bestätigte) Beziehung entstand: manual, rule oder ai
     * @param similarity   bei similarity: Kosinusähnlichkeit
     * @param model        bei similarity: Version des Embedding-Prozessors
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProvenanceResponse(
            @Schema(allowableValues = {"hierarchy", "link", "term-relation", "similarity"}) String type,
            String relationId, String relationType, String fromTerm, String toTerm, String scopeSourceId,
            String scopeSpaceKey, String origin, String seedTerm, String targetTerm, Double similarity,
            Integer seedChunk, String seedSection, Integer targetChunk, String targetSection, String model) {

        static ProvenanceResponse of(Provenance provenance) {
            return switch (provenance) {
                case Provenance.Hierarchy ignored -> simple("hierarchy");
                case Provenance.Link ignored -> simple("link");
                case Provenance.TermLink link -> new ProvenanceResponse("term-relation", link.relationId(),
                        link.type().id(), link.fromTerm(), link.toTerm(), link.scopeSourceId(), link.scopeSpaceKey(),
                        link.origin().id(), link.seedTerm(), link.targetTerm(), null, null, null, null, null, null);
                case Provenance.Similarity similar -> new ProvenanceResponse("similarity", null, null, null, null,
                        null, null, null, null, null, similar.similarity(), similar.seedChunk(),
                        similar.seedSection(), similar.targetChunk(), similar.targetSection(), similar.model());
            };
        }

        private static ProvenanceResponse simple(String type) {
            return new ProvenanceResponse(type, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }
    }
}
