package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Neuberechnung der Anreicherungen einer Seite (#29). Gelesen werden sie mit der Seite über {@code view=extended};
 * beides verlangt die {@code content}-Freigabe, weil Anreicherungen aus dem Inhalt abgeleitet sind.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Anreicherungen", description = "Deterministisch abgeleitete Angaben zu Seiten im Cache")
public class EnrichmentController {

    private final PageQueries queries;
    private final Enrichments enrichments;
    private final ObjectMapper json;
    private final AccessGuard access;

    public EnrichmentController(PageQueries queries, Enrichments enrichments, ObjectMapper json, AccessGuard access) {
        this.queries = queries;
        this.enrichments = enrichments;
        this.json = json;
        this.access = access;
    }

    @PostMapping("/{sourceId}/pages/{pageId}/enrichments")
    @Operation(summary = "Anreicherungen einer Seite neu berechnen",
            description = "Berechnet die Ergebnisse aller oder eines Prozessors aus der Payload im Cache neu, auch "
                    + "wenn sie aktuell sind (Neuindexierung), und liefert danach alle Ergebnisse der Seite. Nach "
                    + "jedem bestätigten Abgleich geschieht das für fehlende und veraltete Ergebnisse von selbst. "
                    + "Payload, Kopfdaten und die Ergebnisse anderer Prozessoren bleiben unverändert; scheitert ein "
                    + "Prozessor, steht nur sein Ergebnis auf failed. Kein Abruf bei der Quelle.")
    @ApiResponse(responseCode = "200", description = "Neu berechnet")
    @ApiResponse(responseCode = "400", description = "Unbekannter Prozessor (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe für irgendeinen Space dieser Quelle, "
            + "keine sync-Freigabe für den Space der Seite oder keine Quelle mit dieser ID (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found) oder Seite nicht im "
            + "Cache bzw. ihr Space ohne content-Freigabe, nicht unterscheidbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Cache nicht eingerichtet oder nicht lesbar (cache-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public RecomputeResponse recompute(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "ID der Seite in der Quelle (Confluence-Content-ID)", example = "123456")
            @PathVariable String pageId,
            @Parameter(description = "Nur dieser Prozessor; ohne Angabe alle", example = "acronyms")
            @RequestParam(required = false) String processor) {
        if (processor != null && !enrichments.processorIds().contains(processor)) {
            throw new InvalidQueryException("processor kennt nur " + String.join(", ", enrichments.processorIds()));
        }
        DeliveredPage page = queries.page(caller, sourceId, pageId, false);
        // Neu berechnen schreibt in den Index und kostet Rechenzeit: wie ein Abgleich nur mit sync-Freigabe, also nie
        // für reine Leser oder durchgereichte Endnutzer.
        access.require(caller, ResourceScope.space(sourceId, page.header().metadata().partitionKey()), AccessKind.SYNC);
        Enrichments.Run run = enrichments.recompute(page.snapshot(), processor);
        enrichments.flush();
        return new RecomputeResponse(sourceId, pageId, run.supported(), run.computed(), run.failed(),
                entries(enrichments.view(page.snapshot()), json));
    }

    /**
     * Ausgang einer Neuberechnung.
     *
     * @param supported   ob die Payload ein unterstütztes Format hat; sonst wurde nichts berechnet
     * @param computed    neu berechnete Ergebnisse
     * @param failed      davon fehlgeschlagene
     * @param enrichments alle Ergebnisse der Seite danach
     */
    public record RecomputeResponse(String sourceId, String pageId, boolean supported, int computed, int failed,
                                    List<EnrichmentEntry> enrichments) {
    }

    /**
     * Ein Ergebnis eines Prozessors für eine Seite. Abgeleitete Hinweise, keine geprüften Fakten.
     *
     * @param processor        Kennung des Prozessors, z. B. {@code acronyms}
     * @param processorVersion Version, mit der das Ergebnis berechnet wurde
     * @param status           {@code ok} oder {@code failed}
     * @param freshness        {@code current}, oder {@code stale}, wenn Eingabe oder Prozessorversion sich seither
     *                         geändert haben
     * @param inputSha256      Hash der Textprojektion, aus der das Ergebnis berechnet wurde
     * @param payloadRevision  Revision der Payload dieser Eingabe
     * @param errorCode        nur bei {@code failed}
     * @param result           Ergebnis des Prozessors; bei {@code failed} leer
     */
    public record EnrichmentEntry(String processor, String processorVersion, String status, String freshness,
                                  String inputSha256, @Schema(nullable = true) String payloadRevision,
                                  Instant computedAt, List<String> warnings,
                                  @Schema(nullable = true) String errorCode,
                                  @Schema(type = "object") JsonNode result) {
    }

    static List<EnrichmentEntry> entries(List<Enrichments.View> views, ObjectMapper json) {
        return views.stream().map(view -> entry(view, json)).toList();
    }

    private static EnrichmentEntry entry(Enrichments.View view, ObjectMapper json) {
        Enrichment enrichment = view.enrichment();
        JsonNode result;
        try {
            result = json.readTree(enrichment.payloadJson());
        } catch (JacksonException e) {
            // Prozessoren liefern JSON-Objekte; ein beschädigtes Ergebnis wird leer gezeigt und gilt als veraltet.
            result = json.createObjectNode();
        }
        return new EnrichmentEntry(enrichment.processorId(), enrichment.processorVersion(),
                enrichment.status().name().toLowerCase(Locale.ROOT), view.freshness().name().toLowerCase(Locale.ROOT),
                enrichment.inputSha256(), enrichment.revision(), enrichment.computedAt(), enrichment.warnings(),
                enrichment.errorCode(), result);
    }
}
