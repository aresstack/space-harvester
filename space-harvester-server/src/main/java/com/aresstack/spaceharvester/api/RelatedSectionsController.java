package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.api.PageQueryController.PageEntry;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.ranking.RelatedSections;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Verwandte Abschnitte eines eingebetteten Stücks (#181, wie {@code IndexKnowledgeRelations}). */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Suche", description = "Schlagwortsuche über die Suche der Quellen, Titel- und Metadatensuche im Index")
public class RelatedSectionsController {

    private final RelevanceSearch relevance;

    public RelatedSectionsController(RelevanceSearch relevance) {
        this.relevance = relevance;
    }

    @GetMapping("/{sourceId}/pages/{pageId}/related")
    @Operation(summary = "Verwandte Abschnitte: ähnliche Stücke, Elternseite, Nachbarstücke, gleicher Abschnitt",
            description = "Experimentell, wie sort=relevance. Aus den gespeicherten Vektoren und Kopfdaten, ohne "
                    + "neues Embedding; nichts geht an den KI-Dienst. similar-to: ähnliche Stücke ab minSimilarity, "
                    + "ohne das Stück und seine direkten Nachbarn; parent: Elternseite mit ihrem ersten Stück; next/"
                    + "previous: Nachbarstücke; same-section: Stücke derselben Seite unter derselben "
                    + "Überschriftenkette. Jedes Ziel einmal, je Art höchstens limit. Die Ausgangsseite und jede "
                    + "Zielseite brauchen metadata und content, mit Live-Prüfung im heute gemeldeten Space; sonst "
                    + "fehlt die Kante ohne Spur. Ohne Embedding der Seite gibt es nur parent (origin fehlt).")
    @ApiResponse(responseCode = "200", description = "RelatedResponse")
    @ApiResponse(responseCode = "400", description = "Unbekanntes Stück, Art oder Grenze (invalid-query)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Seite nicht im Cache oder nicht lesbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Kein Embedding-Dienst eingerichtet "
            + "(semantic-search-unavailable) oder Seite bei der Quelle nicht prüfbar",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public RelatedResponse related(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Seiten-ID der Quelle", example = "123456") @PathVariable String pageId,
            @Parameter(description = "Nummer des Stücks in der Seite, ab 0; die Stücke stehen im Ergebnis "
                    + "embedding unter view=extended") @RequestParam(defaultValue = "0") int chunk,
            @Parameter(description = "Arten, mehrfach oder kommagetrennt; ohne Angabe alle",
                    schema = @Schema(allowableValues = {"similar-to", "parent", "next", "previous",
                            "same-section"}))
            @RequestParam(required = false) List<String> kind,
            @Parameter(description = "Höchstens so viele Kanten je Art, 1 bis " + RelatedSections.Query.MAX_PER_KIND)
            @RequestParam(defaultValue = "" + RelatedSections.Query.DEFAULT_MAX_PER_KIND) int limit,
            @Parameter(description = "Schwelle der Kosinusähnlichkeit für similar-to, -1 bis 1")
            @RequestParam(defaultValue = "" + RelatedSections.Query.DEFAULT_MIN_SIMILARITY) double minSimilarity,
            @Parameter(description = "Zielseiten nur aus diesen Quellen; ohne Angabe alle lesbaren")
            @RequestParam(required = false) List<String> targetSourceId) {
        RelatedSections.Query query = new RelatedSections.Query(kinds(kind), limit, minSimilarity,
                targetSourceId == null ? Set.of() : Set.copyOf(targetSourceId));
        return RelatedResponse.of(relevance.related(caller, sourceId, pageId, chunk, query));
    }

    private static Set<RelatedSections.Kind> kinds(List<String> names) {
        if (names == null || names.isEmpty()) {
            return EnumSet.allOf(RelatedSections.Kind.class);
        }
        Set<RelatedSections.Kind> kinds = EnumSet.noneOf(RelatedSections.Kind.class);
        for (String name : names) {
            String normalized = name.strip().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                kinds.add(RelatedSections.Kind.valueOf(normalized));
            } catch (IllegalArgumentException e) {
                throw new InvalidQueryException("kind kennt nur similar-to, parent, next, previous und same-section");
            }
        }
        return kinds;
    }

    static String name(RelatedSections.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Die Nachbarschaft.
     *
     * @param origin   Ausgangsstück oder {@code null}, wenn die Seite nicht mit dem aktuellen Modell eingebettet ist
     * @param complete {@code false}, wenn eine Zielseite bei der Quelle nicht prüfbar war; sie fehlt dann
     */
    public record RelatedResponse(String sourceId, String pageId,
                                  @Schema(nullable = true) SectionResponse origin, List<RelationResponse> relations,
                                  boolean complete) {

        static RelatedResponse of(RelatedSections.Results results) {
            return new RelatedResponse(results.seed().page().header().key().sourceId(),
                    results.seed().page().header().key().pageId(), SectionResponse.of(results.origin()),
                    results.relations().stream().map(RelationResponse::of).toList(), results.complete());
        }
    }

    /**
     * Eine Kante.
     *
     * @param section Zielstück; fehlt nur bei {@code parent}, wenn die Elternseite nicht eingebettet ist
     * @param score   Kosinusähnlichkeit bei {@code similar-to}, sonst 1
     */
    public record RelationResponse(@Schema(allowableValues = {"similar-to", "parent", "next", "previous",
            "same-section"}) String kind, String sourceId, PageEntry page,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) SectionResponse section, double score) {

        static RelationResponse of(RelatedSections.Relation relation) {
            return new RelationResponse(name(relation.kind()), relation.page().page().header().key().sourceId(),
                    PageEntry.of(relation.page().page()), SectionResponse.of(relation.section()), relation.score());
        }
    }

    /**
     * Ein Stück einer Seite.
     *
     * @param chunk   Nummer in der Seite, ab 0
     * @param section Überschriftenkette, z. B. {@code Handbuch > Betrieb}
     */
    public record SectionResponse(int chunk, int charStart, int charEnd,
                                  @Schema(nullable = true) String section) {

        static SectionResponse of(RelatedSections.Section section) {
            return section == null ? null : new SectionResponse(section.number(), section.charStart(),
                    section.charEnd(), section.section());
        }
    }
}
