package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.facts.ArgumentCondition;
import com.aresstack.spaceharvester.application.facts.FactTypes;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.facts.PageFacts;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.ArrayList;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Metadaten-Fakten je Seite (#182), abgeleitet aus den Anreicherungen (Akronyme, Begriffe, Eigenschaften). Beides
 * verlangt wie die Anreicherungen {@code metadata} und {@code content} für jede Seite; Fakten nicht lesbarer Seiten
 * erscheinen nirgends, auch nicht als Zahl.
 */
@RestController
@RequestMapping(path = ApiPaths.V1, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Anreicherungen", description = "Deterministisch abgeleitete Angaben zu Seiten im Cache")
public class FactController {

    private final PageFacts facts;
    private final ObjectMapper json;

    public FactController(PageFacts facts, ObjectMapper json) {
        this.facts = facts;
        this.json = json;
    }

    @GetMapping("/sources/{sourceId}/pages/{pageId}/facts")
    @Operation(summary = "Fakten einer Seite",
            description = "Das Faktenblatt einer Seite: je Akronym ACRONYM_CANDIDATE, mit Ausschreibung zusätzlich "
                    + "ACRONYM_DEFINITION, je Begriff MENTION, je Eigenschaft PROPERTY, jeweils mit suchbaren "
                    + "Argumenten, Herkunft (Prozessor, Version, Eingabe-Hash) und Fundstelle. freshness=stale heißt, "
                    + "die Payload hat sich seit der Ableitung geändert; die Fundstelle gilt dann nicht mehr. Abruf, "
                    + "Rechte und Fehler wie GET /api/v1/sources/{sourceId}/pages/{pageId}.")
    @ApiResponse(responseCode = "200", description = "Die Fakten")
    @ApiResponse(responseCode = "400", description = "Ungültiger Typ (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe für die Quelle (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle oder Seite, oder nicht lesbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public SheetResponse factsOf(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "ID der Seite in der Quelle", example = "123456") @PathVariable String pageId,
            @Parameter(description = "Nur dieser Typ", schema = @Schema(allowableValues = {
                    FactTypes.ACRONYM_CANDIDATE, FactTypes.ACRONYM_DEFINITION, FactTypes.MENTION,
                    FactTypes.PROPERTY, FactTypes.EVENT, FactTypes.RELATION}))
            @RequestParam(required = false) String type) {
        PageFacts.Sheet sheet = facts.factsOf(caller, sourceId, pageId, type(type));
        return new SheetResponse(sheet.sourceId(), sheet.pageId(), sheet.spaceKey(), sheet.facts().stream()
                .map(entry -> FactResponse.of(entry.fact(), entry.freshness().name().toLowerCase(Locale.ROOT), json))
                .toList());
    }

    @GetMapping("/facts")
    @Operation(summary = "Fakten über alle lesbaren Seiten suchen",
            description = "Fakten eines Typs und/oder mit Argumenten (argument=ROLLE:Wert, wiederholbar, alle "
                    + "zugleich; Werte ohne Beachtung der Groß-/Kleinschreibung), etwa type=ACRONYM_DEFINITION&"
                    + "argument=SHORT_FORM:API. Sucht nur im Cache, in allen Quellen mit metadata-Freigabe oder den "
                    + "genannten; jede Seite braucht metadata und content an ihrem Space. Geordnet nach Kennung; ein "
                    + "Stapel kann kürzer als limit sein, auch wenn noch Fakten folgen: nextCursor übergeben, bis er "
                    + "null ist. Es gibt keine Gesamtzahl.")
    @ApiResponse(responseCode = "200", description = "Ein Stapel Fakten")
    @ApiResponse(responseCode = "400", description = "Ohne type und argument, ungültige Bedingung, Cursor oder "
            + "Grenze (invalid-request)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Nur mit sourceId: keine metadata-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public FactsResponse search(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Nur dieser Typ", schema = @Schema(allowableValues = {
                    FactTypes.ACRONYM_CANDIDATE, FactTypes.ACRONYM_DEFINITION, FactTypes.MENTION,
                    FactTypes.PROPERTY, FactTypes.EVENT, FactTypes.RELATION}))
            @RequestParam(required = false) String type,
            @Parameter(description = "Bedingung ROLLE:Wert, höchstens 16-mal; Rollen SHORT_FORM, LONG_FORM, TERM, KIND, "
                    + "SUBJECT, VALUE, STATUS", example = "SHORT_FORM:API")
            @RequestParam(required = false) List<String> argument,
            @Parameter(description = "Nur diese Quelle") @RequestParam(required = false) String sourceId,
            @Parameter(description = "Ohne sourceId: nur diese Quellen (wiederholbar oder durch Komma getrennt)")
            @RequestParam(required = false) List<String> sourceIds,
            @Parameter(description = "Nur Seiten dieser Spaces (wiederholbar)")
            @RequestParam(required = false) List<String> spaceKey,
            @Parameter(description = "nextCursor der vorigen Antwort") @RequestParam(required = false) String cursor,
            @Parameter(description = "Größe des Stapels, 1 bis " + PageFacts.MAX_LIMIT)
            @RequestParam(defaultValue = "50") int limit) {
        List<ArgumentCondition> conditions = new ArrayList<>();
        for (String condition : argument == null ? List.<String>of() : argument) {
            try {
                conditions.add(ArgumentCondition.parse(condition));
            } catch (IllegalArgumentException e) {
                throw new InvalidQueryException("argument hat die Form ROLLE:Wert, etwa SHORT_FORM:API");
            }
        }
        PageFacts.Results results = facts.search(caller, new PageFacts.Search(type(type), conditions, sourceId,
                Set.copyOf(sourceIds == null ? List.of() : sourceIds),
                Set.copyOf(spaceKey == null ? List.of() : spaceKey)), cursor, limit);
        return new FactsResponse(results.facts().stream().map(fact -> FactResponse.of(fact, null, json)).toList(),
                results.nextCursor());
    }

    private static String type(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String upper = type.strip().toUpperCase(Locale.ROOT);
        if (!FactTypes.ALL.contains(upper)) {
            throw new InvalidQueryException("type kennt nur " + String.join(", ", FactTypes.ALL));
        }
        return upper;
    }

    /** Das Faktenblatt einer Seite. */
    public record SheetResponse(String sourceId, String pageId, String spaceKey, List<FactResponse> facts) {
    }

    /** Ein Stapel der Suche. */
    public record FactsResponse(List<FactResponse> facts, @Schema(nullable = true) String nextCursor) {
    }

    /**
     * Ein Fakt; maschinell abgeleiteter Hinweis, keine geprüfte Aussage.
     *
     * @param role      {@code DEFINES}, {@code MENTIONS} oder {@code null}
     * @param freshness nur im Faktenblatt: {@code current} oder {@code stale}
     */
    public record FactResponse(String id, String sourceId, String pageId, String type, @Schema(nullable = true)
                               String role, @Schema(type = "object") JsonNode payload, List<ArgumentResponse> arguments,
                               ProvenanceResponse provenance, @Schema(nullable = true) AnchorResponse anchor,
                               @Schema(nullable = true) String freshness) {

        static FactResponse of(MetadataFact fact, String freshness, ObjectMapper json) {
            JsonNode payload;
            try {
                payload = json.readTree(fact.payloadJson());
            } catch (JacksonException e) {
                payload = json.createObjectNode();
            }
            MetadataFact.Provenance provenance = fact.provenance();
            return new FactResponse(fact.id(), fact.page().sourceId(), fact.page().pageId(), fact.type(),
                    fact.role(), payload, fact.arguments().stream().map(argument -> new ArgumentResponse(
                            argument.role(), argument.ordinal(), argument.value())).toList(),
                    new ProvenanceResponse(provenance.processorId(), provenance.processorVersion(),
                            provenance.inputSha256(), provenance.revision(), provenance.computedAt()),
                    fact.anchor() == null ? null : new AnchorResponse(fact.anchor().section(),
                            fact.anchor().charStart(), fact.anchor().charEnd()), freshness);
        }
    }

    /** Ein suchbares Argument mit normalisiertem Wert. */
    public record ArgumentResponse(String role, int ordinal, String value) {
    }

    /** Herkunft: Prozessor, Version, Eingabe-Hash und Revision der Payload. */
    public record ProvenanceResponse(String processor, String processorVersion, String inputSha256,
                                     @Schema(nullable = true) String payloadRevision, Instant computedAt) {
    }

    /** Fundstelle im Lesetext der Eingabe. */
    public record AnchorResponse(@Schema(nullable = true) String section, @Schema(nullable = true) Integer charStart,
                                 @Schema(nullable = true) Integer charEnd) {
    }
}
