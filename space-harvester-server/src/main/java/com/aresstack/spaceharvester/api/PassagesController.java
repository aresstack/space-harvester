package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.passages.PagePassages;
import com.aresstack.spaceharvester.application.passages.PassagePayload;
import com.aresstack.spaceharvester.application.passages.PassageQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Semantische Absätze einer Seite (#181, ADR 0020). Nur Adapter von {@link PassageQueries}. */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Seiten (Cache)", description = "Seiten, Baum und native Payload aus dem Cache des Harvesters")
public class PassagesController {

    private final PassageQueries passages;

    public PassagesController(PassageQueries passages) {
        this.passages = passages;
    }

    @GetMapping("/{sourceId}/pages/{pageId}/passages")
    @Operation(summary = "Semantische Absätze einer Seite",
            description = "Passagen der Textprojektion in Lesereihenfolge, deren Grenzen Struktur (Überschriften, "
                    + "Absätze, Listenpunkte, Tabellenzeilen) und gemessene Themenwechsel bestimmen (Satzfenster wie "
                    + "im Enterprise AI Client). Mit Embedding-Modell und für den KI-Dienst freigegebener Quelle "
                    + "mode=semantic, sonst mode=structure mit reason (no-model, source-not-approved, "
                    + "model-unavailable). Stellen sind UTF-16-Positionen im Text mit inputSha256. state=current: "
                    + "aus genau diesem Text mit der aktuellen Version; stale: Passagen nur, wenn sie noch zu diesem "
                    + "Text gehören (sonst leer), die Neuberechnung ist angestoßen; failed; missing. Rechte und "
                    + "Version wie GET …/pages/{pageId}.")
    @ApiResponse(responseCode = "200", description = "PassagesResponse")
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Seite nicht vorhanden oder nicht lesbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PassagesResponse passages(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Seiten-ID der Quelle", example = "123456") @PathVariable String pageId) {
        return PassagesResponse.of(sourceId, pageId, passages.page(caller, sourceId, pageId));
    }

    /**
     * Die Absätze einer Seite.
     *
     * @param state            current, stale, failed oder missing
     * @param mode             semantic oder structure, ohne Ergebnis {@code null}
     * @param reason           bei structure: no-model, source-not-approved oder model-unavailable
     * @param model            bei semantic das Embedding-Modell
     * @param segmentation     Verfahren und Parameter der Zerlegung
     * @param processorVersion Version, mit der das Ergebnis entstand
     * @param inputSha256      Hash des Textes, zu dem die Stellen gehören
     * @param revision         Revision der Payload dieses Textes
     */
    public record PassagesResponse(String sourceId, String pageId, String state,
                                   @Schema(nullable = true) String mode, @Schema(nullable = true) String reason,
                                   @Schema(nullable = true) String model,
                                   @Schema(nullable = true) String segmentation,
                                   @Schema(nullable = true) String processorVersion,
                                   @Schema(nullable = true) String inputSha256,
                                   @Schema(nullable = true) String revision, List<PassageEntry> passages,
                                   List<String> warnings) {

        static PassagesResponse of(String sourceId, String pageId, PagePassages found) {
            Enrichment result = found.result();
            return new PassagesResponse(sourceId, pageId, found.state().name().toLowerCase(Locale.ROOT),
                    found.mode() == null ? null : PassagePayload.name(found.mode()), found.reason(), found.model(),
                    found.segmentation(), result == null ? null : result.processorVersion(),
                    result == null ? null : result.inputSha256(), result == null ? null : result.revision(),
                    found.passages().stream().map(PassageEntry::of).toList(), found.warnings());
        }
    }

    /**
     * Ein Absatz.
     *
     * @param index      Nummer in der Seite, ab 0
     * @param start      erstes Zeichen (UTF-16)
     * @param end        Ende, exklusiv
     * @param title      Themenzeile: der erste Satz, gekürzt
     * @param section    Überschriftenkette oder {@code null}
     * @param kind       paragraph, list-item, table-row, quote oder code
     * @param boundary   structure, topic, length oder tokens: warum der Absatz hier beginnt
     * @param similarity bei topic die gemessene Ähnlichkeit der Satzfenster an der Grenze
     */
    public record PassageEntry(int index, int start, int end, String title, String text,
                               @Schema(nullable = true) String section, int firstSentence, int sentences,
                               String kind, String boundary, @Schema(nullable = true) Double similarity) {

        static PassageEntry of(PagePassages.Passage passage) {
            return new PassageEntry(passage.index(), passage.start(), passage.end(), passage.title(), passage.text(),
                    passage.section(), passage.firstSentence(), passage.sentences(),
                    PassagePayload.name(passage.kind()), PassagePayload.name(passage.boundary()),
                    passage.similarity());
        }
    }
}
