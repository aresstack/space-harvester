package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.facts.ArgumentCondition;
import com.aresstack.spaceharvester.application.facts.FactEditStore;
import com.aresstack.spaceharvester.application.facts.FactEdits;
import com.aresstack.spaceharvester.application.facts.FactReview;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Metadaten-Fakten je Seite (#182), abgeleitet aus den Anreicherungen (Akronyme, Begriffe, Eigenschaften), dazu
 * manuelle und geprüfte Fakten. Lesen verlangt wie die Anreicherungen {@code metadata} und {@code content} für jede
 * Seite; Fakten nicht lesbarer Seiten erscheinen nirgends, auch nicht als Zahl. Anlegen, Prüfen und Entfernen verlangt
 * zusätzlich {@code sync} an der Seite.
 */
@RestController
@RequestMapping(path = ApiPaths.V1, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Anreicherungen", description = "Deterministisch abgeleitete Angaben zu Seiten im Cache")
public class FactController {

    private final PageFacts facts;
    private final FactEdits edits;
    private final ObjectMapper json;

    public FactController(PageFacts facts, FactEdits edits, ObjectMapper json) {
        this.facts = facts;
        this.edits = edits;
        this.json = json;
    }

    @GetMapping("/sources/{sourceId}/pages/{pageId}/facts")
    @Operation(summary = "Fakten einer Seite",
            description = "Das Faktenblatt einer Seite: je Akronym ACRONYM_CANDIDATE, mit Ausschreibung zusätzlich "
                    + "ACRONYM_DEFINITION, je Begriff MENTION, je Eigenschaft PROPERTY, jeweils mit suchbaren "
                    + "Argumenten, Herkunft (Prozessor, Version, Eingabe-Hash) und Fundstelle. freshness=stale heißt, "
                    + "die Payload hat sich seit der Ableitung geändert; die Fundstelle gilt dann nicht mehr. Abruf, "
                    + "Rechte und Fehler wie GET /api/v1/sources/{sourceId}/pages/{pageId}. Je Prozessor und Seite "
                    + "gibt es höchstens 2000 Fakten; truncated nennt die Prozessoren, deren Fakten dort enden.")
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
            @RequestParam(required = false) String type,
            @Parameter(description = "Nur Fakten dieses Prozessors, etwa entities, statements oder manual (MENTION "
                    + "entsteht aus entities und, eingeschaltet, aus statements)", example = "statements")
            @RequestParam(required = false) String processor) {
        PageFacts.Sheet sheet = facts.factsOf(caller, sourceId, pageId, type(type), blankToNull(processor));
        return new SheetResponse(sheet.sourceId(), sheet.pageId(), sheet.spaceKey(), sheet.facts().stream()
                .map(entry -> FactResponse.of(entry.fact(), entry.freshness().name().toLowerCase(Locale.ROOT),
                        entry.revision(), json))
                .toList(), sheet.truncated());
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
            @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "Nur Fakten dieses Prozessors, etwa entities, statements oder manual",
                    example = "statements")
            @RequestParam(required = false) String processor) {
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
                Set.copyOf(spaceKey == null ? List.of() : spaceKey)), blankToNull(processor), cursor, limit);
        return new FactsResponse(results.facts().stream().map(fact -> FactResponse.of(fact, null,
                edits.revision(fact.id()), json)).toList(), results.nextCursor());
    }

    @PostMapping(path = "/sources/{sourceId}/pages/{pageId}/facts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Manuellen Fakt anlegen",
            description = "Legt einen Fakt zur Seite an (origin=MANUAL, review=UNREVIEWED), gegen ihren aktuellen Text. "
                    + "Kein Prozessorlauf berührt ihn. Argumentwerte werden normalisiert, Ordnungszahlen je Rolle "
                    + "vergeben. Verlangt metadata, content und sync an der Seite.")
    @ApiResponse(responseCode = "201", description = "Der angelegte Fakt mit revision 1")
    @ApiResponse(responseCode = "400", description = "Unbekannter Typ, Payload kein JSON-Objekt oder ungültige "
            + "Argumente (invalid-request)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle oder Seite, oder nicht lesbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public FactResponse add(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                            @PathVariable String pageId, @RequestBody NewFactRequest request) {
        if (request == null) {
            throw new InvalidQueryException("Anfrage fehlt");
        }
        FactEdits.Edited added = edits.add(caller, sourceId, pageId, new FactEdits.NewFact(type(request.type()),
                request.role(), request.payload() == null ? null : request.payload().toString(),
                request.arguments() == null ? List.of() : request.arguments().stream()
                        .map(argument -> new FactEdits.NewArgument(argument.role(), argument.value())).toList(),
                request.anchor() == null ? null : new MetadataFact.Anchor(request.anchor().section(),
                        request.anchor().charStart(), request.anchor().charEnd())));
        return FactResponse.of(added.fact(), null, added.revision(), json);
    }

    @PutMapping(path = "/sources/{sourceId}/pages/{pageId}/facts/{factId}/review",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Fakt bestätigen oder verwerfen",
            description = "Setzt den Prüfstatus (CONFIRMED, REJECTED oder UNREVIEWED). Ein geprüfter automatischer "
                    + "Fakt bleibt mit alter Herkunft stehen, wenn sein Ergebnis neu berechnet wird und etwas anderes "
                    + "liefert; der neue Fakt erscheint dann ungeprüft unter eigener Kennung. UNREVIEWED nimmt einen "
                    + "automatischen Fakt aus der Prüfablage. revision ist die zuletzt gelesene (0 für ungeprüfte "
                    + "automatische Fakten); ohne sie keine Prüfung. Verlangt metadata, content und sync.")
    @ApiResponse(responseCode = "200", description = "Der Fakt mit neuer revision")
    @ApiResponse(responseCode = "404", description = "Kein solcher Fakt an der Seite (fact-not-found) oder Seite "
            + "nicht lesbar", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "revision veraltet (fact-conflict, mit aktueller revision)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public FactResponse review(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                               @PathVariable String pageId, @PathVariable String factId,
                               @RequestBody ReviewRequest request) {
        // Ungültiger Status erst nach der Rechteprüfung: ohne Freigabe verrät die Antwort nichts.
        FactReview review = null;
        if (request != null && request.review() != null) {
            try {
                review = FactReview.valueOf(request.review().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                review = null;
            }
        }
        FactEdits.Edited reviewed = edits.review(caller, sourceId, pageId, factId, review,
                request == null ? null : request.revision());
        return FactResponse.of(reviewed.fact(), null, reviewed.revision(), json);
    }

    @DeleteMapping("/sources/{sourceId}/pages/{pageId}/facts/{factId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Manuellen oder geprüften Fakt entfernen",
            description = "Entfernt einen manuellen Fakt oder die geprüfte Kopie eines automatischen. Ungeprüfte "
                    + "automatische Fakten lassen sich nur verwerfen (400). Verlangt metadata, content und sync.")
    @ApiResponse(responseCode = "204", description = "Entfernt")
    @ApiResponse(responseCode = "404", description = "Kein solcher Fakt an der Seite (fact-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "revision veraltet (fact-conflict)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public void remove(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                       @PathVariable String pageId, @PathVariable String factId,
                       @Parameter(description = "Zuletzt gelesene revision") @RequestParam(required = false)
                       Long revision) {
        edits.remove(caller, sourceId, pageId, factId, revision);
    }

    @GetMapping("/sources/{sourceId}/pages/{pageId}/facts/{factId}/history")
    @Operation(summary = "Protokoll eines Fakts",
            description = "Anlegen, Prüfen und Entfernen mit Bearbeiter, Zeitpunkt, Prüfstatus und revision, älteste "
                    + "Änderung zuerst; auch nach dem Entfernen. Rechte wie das Faktenblatt.")
    @ApiResponse(responseCode = "200", description = "Das Protokoll")
    @ApiResponse(responseCode = "404", description = "Weder Fakt noch Protokoll an der Seite (fact-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public HistoryResponse history(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                                   @PathVariable String pageId, @PathVariable String factId) {
        return new HistoryResponse(factId, edits.history(caller, sourceId, pageId, factId).stream()
                .map(HistoryEntry::of).toList());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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

    /**
     * Das Faktenblatt einer Seite.
     *
     * @param truncated Prozessoren, deren Fakten an der Grenze je Prozessor enden, sonst leer
     */
    public record SheetResponse(String sourceId, String pageId, String spaceKey, List<FactResponse> facts,
                                List<String> truncated) {
    }

    /** Ein Stapel der Suche. */
    public record FactsResponse(List<FactResponse> facts, @Schema(nullable = true) String nextCursor) {
    }

    /**
     * Ein Fakt; automatisch abgeleitet ein Hinweis, geprüft erst mit {@code review} {@code CONFIRMED}.
     *
     * @param role      {@code DEFINES}, {@code MENTIONS} oder {@code null}
     * @param freshness nur im Faktenblatt: {@code current} oder {@code stale}
     * @param origin    {@code AUTOMATIC} oder {@code MANUAL}
     * @param review    {@code UNREVIEWED}, {@code CONFIRMED} oder {@code REJECTED}
     * @param revision  für Prüfen und Entfernen; {@code 0}, solange niemand den Fakt geprüft hat
     */
    public record FactResponse(String id, String sourceId, String pageId, String type, @Schema(nullable = true)
                               String role, @Schema(type = "object") JsonNode payload, List<ArgumentResponse> arguments,
                               ProvenanceResponse provenance, @Schema(nullable = true) AnchorResponse anchor,
                               @Schema(nullable = true) String freshness, String origin, String review,
                               long revision) {

        static FactResponse of(MetadataFact fact, String freshness, long revision, ObjectMapper json) {
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
                            fact.anchor().charStart(), fact.anchor().charEnd()), freshness, fact.origin().name(),
                    fact.review().name(), revision);
        }
    }

    /** Ein neuer manueller Fakt. */
    public record NewFactRequest(@Schema(description = "Typ", example = "PROPERTY") String type,
                                 @Schema(nullable = true, example = "DEFINES") String role,
                                 @Schema(type = "object", nullable = true) JsonNode payload,
                                 @Schema(nullable = true) List<NewArgumentRequest> arguments,
                                 @Schema(nullable = true) AnchorResponse anchor) {
    }

    /** Ein Argument eines neuen Fakts; der Wert wird normalisiert. */
    public record NewArgumentRequest(@Schema(example = "VALUE") String role, @Schema(example = "Team A") String value) {
    }

    /** Neuer Prüfstatus mit der zuletzt gelesenen Revision. */
    public record ReviewRequest(@Schema(example = "CONFIRMED") String review,
                                @Schema(nullable = true) Long revision) {
    }

    /** Das Protokoll eines Fakts. */
    public record HistoryResponse(String factId, List<HistoryEntry> entries) {
    }

    /** Eine Änderung. */
    public record HistoryEntry(String action, String actor, Instant at, String review, long revision) {

        static HistoryEntry of(FactEditStore.AuditEntry entry) {
            return new HistoryEntry(entry.action(), entry.actor(), entry.at(), entry.review().name(),
                    entry.revision());
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
