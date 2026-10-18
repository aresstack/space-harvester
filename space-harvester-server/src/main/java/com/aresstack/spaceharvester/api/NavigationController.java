package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Live-Navigation (#177): Kindseiten, Vorfahren und Querverweise direkt aus der Quelle, Rechte je Knoten. */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Navigation (live)", description = "Seitenbaum, Breadcrumbs und Querverweise direkt aus der Quelle")
public class NavigationController {

    private final PageNavigation navigation;

    public NavigationController(PageNavigation navigation) {
        this.navigation = navigation;
    }

    @GetMapping("/{sourceId}/spaces/{spaceKey}/children")
    @Operation(summary = "Wurzelseiten eines Space live, in der Reihenfolge der Quelle",
            description = "Ein Stapel; die Quelle wählt die Größe. Weiter mit nextCursor, bis er null ist. Jede Seite "
                    + "mit hasChildren (true, false oder null für unbekannt). Verlangt metadata auf den Space; Seiten "
                    + "ohne Freigabe fehlen.")
    @ApiResponse(responseCode = "200", description = "ChildrenResponse")
    @ApiResponse(responseCode = "403", description = "Keine metadata-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ChildrenResponse rootPages(@Parameter(hidden = true) AccessContext caller,
                                      @PathVariable String sourceId, @PathVariable String spaceKey,
                                      @Parameter(description = "nextCursor des vorigen Stapels")
                                      @RequestParam(required = false) String cursor) {
        return ChildrenResponse.of(navigation.children(caller, sourceId, spaceKey, null, cursor));
    }

    @GetMapping(path = "/{sourceId}/pages", params = "title")
    @Operation(summary = "Seiten über ihren Titel live bei der Quelle finden, auch nie abgeglichene",
            description = "Seiten, deren Titel den Text enthält (Groß- und Kleinschreibung egal), in der Reihenfolge "
                    + "der Quelle, ohne Laden. Confluence sucht über die CQL-Wortsuche und behält Treffer mit passendem "
                    + "Titel, lokale Dateien vergleichen den Dateinamen. Jede Seite mit spaceKey; Vorfahren über "
                    + "…/pages/{pageId}/ancestors. Verlangt metadata auf die Quelle; Seiten ohne Freigabe fehlen, auch "
                    + "in truncated. Quellen ohne TITLE_SEARCH finden nichts.")
    @ApiResponse(responseCode = "200", description = "TitleMatchesResponse")
    @ApiResponse(responseCode = "400", description = "title leer oder limit außerhalb 1 bis "
            + PageNavigation.MAX_TITLE_LIMIT + " (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine metadata-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public TitleMatchesResponse pagesByTitle(@Parameter(hidden = true) AccessContext caller,
                                             @PathVariable String sourceId,
                                             @Parameter(description = "Teil des Titels", required = true)
                                             @RequestParam String title,
                                             @Parameter(description = "nur diese Spaces, wiederholbar; ohne alle")
                                             @RequestParam(required = false) List<String> spaceKey,
                                             @Parameter(description = "höchstens so viele Seiten")
                                             @RequestParam(defaultValue = "25") int limit) {
        return TitleMatchesResponse.of(navigation.findByTitle(caller, sourceId,
                spaceKey == null ? Set.of() : spaceKey.stream().map(String::trim).filter(key -> !key.isEmpty())
                        .collect(Collectors.toSet()), title, limit));
    }

    @GetMapping("/{sourceId}/pages/{pageId}/children")
    @Operation(summary = "Direkte Kindseiten einer Seite live, in der Reihenfolge der Quelle",
            description = "Wie …/spaces/{spaceKey}/children; verlangt metadata auf die Seite.")
    @ApiResponse(responseCode = "200", description = "ChildrenResponse")
    @ApiResponse(responseCode = "404", description = "Seite unbekannt oder nicht freigegeben",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ChildrenResponse children(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                                     @PathVariable String pageId,
                                     @Parameter(description = "nextCursor des vorigen Stapels")
                                     @RequestParam(required = false) String cursor) {
        return ChildrenResponse.of(navigation.children(caller, sourceId, null, pageId, cursor));
    }

    @GetMapping("/{sourceId}/pages/{pageId}/ancestors")
    @Operation(summary = "Lage einer Seite: Space mit Namen und Vorfahren von oben nach unten (Breadcrumbs)",
            description = "Live aus der Quelle; verlangt metadata auf die Seite. Vorfahren ohne Freigabe fehlen.")
    @ApiResponse(responseCode = "200", description = "PositionResponse")
    @ApiResponse(responseCode = "404", description = "Seite unbekannt oder nicht freigegeben",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PositionResponse ancestors(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                                      @PathVariable String pageId) {
        return PositionResponse.of(navigation.position(caller, sourceId, pageId));
    }

    @GetMapping("/{sourceId}/pages/{pageId}/links")
    @Operation(summary = "Querverweise einer Seite auf andere Seiten der Quelle",
            description = "outgoing: Seiten, auf die die Seite verweist, jede einmal, in Reihenfolge der Seite, ohne "
                    + "Anhänge. incoming: Seiten, die hierher verweisen, nur wenn die Quelle sie vollständig kennt "
                    + "(incomingSupported), bei Confluence nicht. Verlangt content auf die Seite; Ziele ohne Freigabe "
                    + "fehlen.")
    @ApiResponse(responseCode = "200", description = "LinksResponse")
    @ApiResponse(responseCode = "404", description = "Seite unbekannt oder nicht freigegeben",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public LinksResponse links(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                               @PathVariable String pageId) {
        return LinksResponse.of(navigation.references(caller, sourceId, pageId));
    }

    /** Ein Knoten; {@code hasChildren} ist {@code null}, wenn es die Quelle nicht weiß. */
    public record Node(String pageId, String title, String spaceKey, Boolean hasChildren) {

        static Node of(HarvestSource.PageNode node) {
            return new Node(node.pageId(), node.title(), node.partitionKey(), node.hasChildren());
        }

        static List<Node> of(List<HarvestSource.PageNode> nodes) {
            return nodes.stream().map(Node::of).toList();
        }
    }

    /** Ein Stapel Kindseiten; {@code parentPageId} ist {@code null} für die Wurzelseiten. */
    public record ChildrenResponse(String sourceId, String spaceKey, String parentPageId, List<Node> pages,
                                   String nextCursor) {

        static ChildrenResponse of(PageNavigation.Children children) {
            return new ChildrenResponse(children.sourceId(), children.spaceKey(), children.parentPageId(),
                    Node.of(children.pages()), children.nextCursor());
        }
    }

    /** Lage einer Seite. */
    public record PositionResponse(String sourceId, String spaceKey, String spaceName, List<Node> ancestors,
                                   Node page) {

        static PositionResponse of(PageNavigation.Position position) {
            return new PositionResponse(position.sourceId(), position.spaceKey(), position.spaceName(),
                    Node.of(position.ancestors()), Node.of(position.page()));
        }
    }

    /** Treffer einer Titelsuche; {@code truncated}: es gibt mehr sichtbare Seiten. */
    public record TitleMatchesResponse(String sourceId, List<Node> pages, boolean truncated) {

        static TitleMatchesResponse of(PageNavigation.TitleMatches matches) {
            return new TitleMatchesResponse(matches.sourceId(), Node.of(matches.pages()), matches.truncated());
        }
    }

    /** Querverweise; {@code incoming} ist leer, wenn {@code incomingSupported} false ist. */
    public record LinksResponse(String sourceId, String pageId, List<Node> outgoing, boolean incomingSupported,
                                List<Node> incoming) {

        static LinksResponse of(PageNavigation.References references) {
            return new LinksResponse(references.sourceId(), references.pageId(), Node.of(references.outgoing()),
                    references.incoming() != null,
                    references.incoming() == null ? List.of() : Node.of(references.incoming()));
        }
    }
}
