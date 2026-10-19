package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.images.ImagePayload;
import com.aresstack.spaceharvester.application.images.PageImageAnalysis;
import com.aresstack.spaceharvester.application.images.PageImages;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bildanalyse einer Seite (ADR 0021, #362): die Bilder der Lese-Ansicht mit ihrer Beschreibung durch das Vision-Modell
 * aus {@code space-harvester.ai.vision-model}. Lesen beschreibt nichts; beschrieben wird nur mit {@code POST}.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Anreicherungen", description = "Deterministisch abgeleitete Angaben zu Seiten im Cache")
public class ImagesController {

    private final PageQueries queries;
    private final PageImageAnalysis images;
    private final AccessGuard access;

    public ImagesController(PageQueries queries, PageImageAnalysis images, AccessGuard access) {
        this.queries = queries;
        this.images = images;
        this.access = access;
    }

    @GetMapping("/{sourceId}/pages/{pageId}/images")
    @Operation(summary = "Bilder einer Seite mit ihren Beschreibungen",
            description = "Gespeichertes Ergebnis der Bildanalyse für den Text im Cache: state current, stale "
                    + "(anderer Text oder anderes Modell), failed oder missing mit reason (not-analysed, no-text, "
                    + "vision-model-missing, source-not-released). Je Bild Asset, SHA-256, Status described, pending, "
                    + "failed oder skipped und die maschinell erzeugte Beschreibung. Beschreibt nichts und fragt "
                    + "weder Quelle noch Modell. Freigabe content wie das Bild.")
    @ApiResponse(responseCode = "200", description = "PageImagesResponse")
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Seite nicht vorhanden oder nicht lesbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PageImagesResponse images(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Seiten-ID der Quelle", example = "123456") @PathVariable String pageId) {
        DeliveredPage page = queries.page(caller, sourceId, pageId, false);
        return PageImagesResponse.of(sourceId, pageId, images.read(page.snapshot()));
    }

    @PostMapping("/{sourceId}/pages/{pageId}/images")
    @Operation(summary = "Bilder einer Seite beschreiben lassen",
            description = "Lädt die Bilder der Lese-Ansicht über die Anmeldung der Quelle und beschreibt jedes, für "
                    + "dessen Bytes es mit dem eingestellten Modell noch keine Beschreibung gibt, mit dem Prompt des "
                    + "Enterprise AI Client; dasselbe Bild geht nie zweimal an das Modell. Vorschaubilder von "
                    + "draw.io-Diagrammen und SVG werden übersprungen (die textuelle Quelle gilt). Nur für Quellen in "
                    + "ai.external-processing-sources. Freigaben content und sync.")
    @ApiResponse(responseCode = "200", description = "PageImagesResponse")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe (access-denied) oder Quelle nicht für den "
            + "KI-Dienst freigegeben (image-description-not-released)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Seite nicht vorhanden oder nicht lesbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Kein Vision-Modell eingestellt (image-description-unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PageImagesResponse describe(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Seiten-ID der Quelle", example = "123456") @PathVariable String pageId) {
        DeliveredPage page = queries.page(caller, sourceId, pageId, false);
        // Beschreiben lädt Bilder in die Ablage und schreibt die Anreicherung: wie das Ernten nur mit sync.
        access.require(caller, ResourceScope.space(sourceId, page.header().metadata().partitionKey()), AccessKind.SYNC);
        return PageImagesResponse.of(sourceId, pageId, images.describe(page.snapshot()));
    }

    /**
     * Bilder einer Seite.
     *
     * @param state    current, stale, failed oder missing
     * @param reason   bei missing der Grund, sonst {@code null}
     * @param revision Version der Seite, für die das Ergebnis entstand, oder {@code null}
     */
    public record PageImagesResponse(String sourceId, String pageId, String state, String reason, String revision,
                                     String processorVersion, Instant computedAt, List<Image> images,
                                     List<String> warnings) {

        static PageImagesResponse of(String sourceId, String pageId, PageImages images) {
            return new PageImagesResponse(sourceId, pageId, images.state().name().toLowerCase(Locale.ROOT),
                    images.reason(), images.result() == null ? null : images.result().revision(),
                    images.result() == null ? null : images.result().processorVersion(),
                    images.result() == null ? null : images.result().computedAt(),
                    images.images().stream().map(Image::of).toList(), images.warnings());
        }
    }

    /** Ein Bild; {@code origin} ist immer {@code machine-generated}, die Beschreibung kein Teil der Seite. */
    public record Image(int index, String assetId, String sha256, String filename, String title, String mediaType,
                        String status, String reason, String description, String describer, String model,
                        Instant describedAt, String origin) {

        static Image of(ImagePayload.Entry entry) {
            return new Image(entry.index(), entry.assetId(), entry.sha256(), entry.filename(), entry.title(),
                    entry.mediaType(), entry.status().key(), entry.reason(), entry.description(), entry.describer(),
                    entry.model(), entry.describedAt(), "machine-generated");
        }
    }
}
