package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Begriffsindex (#119, ADR 0019): Akronyme und Bezeichner aller berechtigten, aktuell indexierten Seiten mit
 * Fundstellen; Kontext nur auf Abruf aus dem gecachten Text. Nur Adapter von {@link TermQueries}.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/terms", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Begriffe", description = "Begriffsindex: Akronyme, Bezeichner und ihre Fundstellen über alle Quellen")
public class TermsController {

    private final TermQueries terms;

    public TermsController(TermQueries terms) {
        this.terms = terms;
    }

    @GetMapping
    @Operation(summary = "Begriffe über alle berechtigten Seiten, alphabetisch nach Suchwert, dann Art",
            description = "Ein Begriff ist ein Wortlaut, kein Fachkonzept: gruppiert wird nach Art und Suchwert "
                    + "(Unicode NFC, Kleinbuchstaben, Leerraum zusammengefasst), nie nach Grundform; Beleg und Belege "
                    + "bleiben zwei Begriffe. q ist ein Präfix des Suchwerts oder eines seiner Wörter, mit exact=true "
                    + "genau der Suchwert. Ohne sourceId alle Quellen mit metadata; danach zählt jede Seite nur mit "
                    + "content-Freigabe, Zähler und Ausschreibungen stammen nur aus sichtbaren Seiten. Seiten, deren "
                    + "Begriffe zu einer älteren Revision gehören, zählen erst nach dem nächsten Lauf. Weiter mit "
                    + "nextCursor, bis er null ist; ein Stapel kann kürzer sein als limit.")
    @ApiResponse(responseCode = "200", description = "TermListResponse")
    @ApiResponse(responseCode = "400", description = "Unbekannte kind, limit außerhalb 1 bis " + TermQueries.MAX_LIMIT
            + " oder cursor einer anderen Abfrage (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Mit sourceId: keine metadata-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public TermListResponse terms(@Parameter(hidden = true) AccessContext caller,
                                  @Parameter(description = "noun, acronym oder identifier; ohne alle")
                                  @RequestParam(required = false) String kind,
                                  @Parameter(description = "Suchtext") @RequestParam(required = false) String q,
                                  @Parameter(description = "q ist der ganze Suchwert")
                                  @RequestParam(defaultValue = "false") boolean exact,
                                  @Parameter(description = "nur diese Quelle") @RequestParam(required = false)
                                  String sourceId,
                                  @Parameter(description = "nur Seiten dieses Space") @RequestParam(required = false)
                                  String spaceKey,
                                  @RequestParam(defaultValue = "50") int limit,
                                  @Parameter(description = "nextCursor der vorigen Antwort")
                                  @RequestParam(required = false) String cursor) {
        TermQueries.TermList list = terms.terms(caller, new TermQueries.TermSearch(blankToNull(sourceId),
                blankToNull(spaceKey), kind(kind), q, exact, limit, blankToNull(cursor)));
        return new TermListResponse(list.terms().stream().map(TermEntry::of).toList(), list.nextCursor());
    }

    @GetMapping("/occurrences")
    @Operation(summary = "Alle berechtigten Fundstellen eines Begriffs",
            description = "Nach Art, Quelle, Seite und Position; jede Fundstelle einzeln, auch mehrere auf derselben "
                    + "Seite. Positionen sind UTF-16-Zeichenpositionen im projizierten Text mit inputSha256. Mit "
                    + "snippetChars > 0 je Fundstelle Kontext aus dem gecachten Text, nur wenn dieser noch denselben "
                    + "Hash hat (sonst snippet null). Rechte wie GET /terms.")
    @ApiResponse(responseCode = "200", description = "OccurrenceListResponse")
    @ApiResponse(responseCode = "400", description = "term fehlt, unbekannte kind, limit außerhalb 1 bis "
            + TermQueries.MAX_LIMIT + ", snippetChars über " + TermQueries.MAX_SNIPPET_CHARS
            + " oder cursor einer anderen Abfrage (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Mit sourceId: keine metadata-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public OccurrenceListResponse occurrences(@Parameter(hidden = true) AccessContext caller,
                                              @Parameter(description = "Begriff oder Suchwert", required = true)
                                              @RequestParam(required = false) String term,
                                              @RequestParam(required = false) String kind,
                                              @RequestParam(required = false) String sourceId,
                                              @RequestParam(required = false) String spaceKey,
                                              @RequestParam(defaultValue = "50") int limit,
                                              @RequestParam(required = false) String cursor,
                                              @Parameter(description = "Zeichen Kontext je Seite, 0 für keinen")
                                              @RequestParam(defaultValue = "0") int snippetChars) {
        TermQueries.OccurrenceList list = terms.occurrences(caller, new TermQueries.OccurrenceSearch(term, kind(kind),
                blankToNull(sourceId), blankToNull(spaceKey), limit, blankToNull(cursor), snippetChars));
        return new OccurrenceListResponse(list.normalized(),
                list.occurrences().stream().map(OccurrenceEntry::of).toList(), list.nextCursor());
    }

    @GetMapping("/snippet")
    @Operation(summary = "Kontext einer Fundstelle aus dem gecachten Text",
            description = "Nur wenn die Seite im Cache liegt, der Aufrufer content auf sie hat und ihr Text noch "
                    + "inputSha256 hat; sonst 404, ohne zu verraten, warum.")
    @ApiResponse(responseCode = "200", description = "SnippetResponse")
    @ApiResponse(responseCode = "400", description = "chars außerhalb 1 bis " + TermQueries.MAX_SNIPPET_CHARS
            + " (invalid-request)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Kein Kontext verfügbar")
    public ResponseEntity<SnippetResponse> snippet(@Parameter(hidden = true) AccessContext caller,
                                                   @RequestParam String sourceId, @RequestParam String pageId,
                                                   @RequestParam String inputSha256, @RequestParam int start,
                                                   @RequestParam int end,
                                                   @RequestParam(defaultValue = "80") int chars) {
        return terms.snippet(caller, sourceId, pageId, inputSha256, start, end, chars)
                .map(found -> ResponseEntity.ok(SnippetResponse.of(found)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private static TermKind kind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return TermKind.of(kind.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("kind muss noun, acronym oder identifier sein");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Ein Begriff über alle sichtbaren Seiten. */
    public record TermEntry(String term, String normalized, String kind, int occurrences, int pages, int sources,
                            List<String> spellings, List<String> expansions) {

        static TermEntry of(TermQueries.TermSummary summary) {
            return new TermEntry(summary.term(), summary.normalized(), summary.kind().id(), summary.occurrences(),
                    summary.pages(), summary.sources(), summary.spellings(), summary.expansions());
        }
    }

    /** Ein Stapel der Begriffsliste. */
    public record TermListResponse(List<TermEntry> terms, String nextCursor) {
    }

    /** Eine Fundstelle. */
    public record OccurrenceEntry(String sourceId, String spaceKey, String pageId, String title, String kind,
                                  String term, String expansion, String section, int start, int end, String revision,
                                  String inputSha256, String processorVersion, SnippetResponse snippet) {

        static OccurrenceEntry of(TermQueries.Occurrence occurrence) {
            return new OccurrenceEntry(occurrence.sourceId(), occurrence.spaceKey(), occurrence.pageId(),
                    occurrence.title(), occurrence.kind().id(), occurrence.term(), occurrence.expansion(),
                    occurrence.section(), occurrence.start(), occurrence.end(), occurrence.revision(),
                    occurrence.inputSha256(), occurrence.processorVersion(),
                    occurrence.snippet() == null ? null : SnippetResponse.of(occurrence.snippet()));
        }
    }

    /** Ein Stapel Fundstellen. */
    public record OccurrenceListResponse(String normalized, List<OccurrenceEntry> occurrences, String nextCursor) {
    }

    /** Kontext einer Fundstelle. */
    public record SnippetResponse(String before, String match, String after, boolean truncatedBefore,
                                  boolean truncatedAfter) {

        static SnippetResponse of(TermQueries.Snippet snippet) {
            return new SnippetResponse(snippet.before(), snippet.match(), snippet.after(), snippet.truncatedBefore(),
                    snippet.truncatedAfter());
        }
    }
}
