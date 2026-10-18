package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.reading.ReadingView;
import com.aresstack.spaceharvester.application.reading.ReadingViews;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lese-Ansicht einer Seite (#176): so, wie die Quelle sie rendert, mit ausgeführten Makros. */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Seiten (Cache)", description = "Seiten, Baum und native Payload aus dem Cache des Harvesters")
public class ReadingViewController {

    private final ReadingViews views;

    public ReadingViewController(ReadingViews views) {
        this.views = views;
    }

    @GetMapping("/{sourceId}/pages/{pageId}/reading-view")
    @Operation(summary = "Lese-Ansicht einer Seite: gerendertes HTML, Lesetext und Bilder",
            description = "Bei Confluence body.export_view mit ausgeführten Makros (include, excerpt-include, "
                    + "children, Jira) und der Lesetext aus body.view, wie im Enterprise AI Client. Rechte und "
                    + "Version wie GET …/pages/{pageId}; die Ansicht gehört immer zur geprüften Version und liegt "
                    + "danach neben dem Raw-Snapshot (stored=true beim nächsten Abruf). Bindet die Seite Inhalte "
                    + "anderer Seiten oder Systeme ein, rendert Confluence sie mit den Zugangsdaten der Quelle: dann "
                    + "nur für Clients mit Freigabe aller Spaces der Quelle, sonst status=restricted ohne Inhalt; "
                    + "solche Ansichten werden jedes Mal neu abgerufen. Quellen ohne Lese-Ansicht: "
                    + "status=unsupported. Bilder nur als Verweise.")
    @ApiResponse(responseCode = "200", description = "ReadingViewResponse")
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Seite nicht vorhanden oder nicht lesbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ReadingViewResponse readingView(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Seiten-ID der Quelle", example = "123456") @PathVariable String pageId,
            @Parameter(description = "true: Seite gegen die Quelle prüfen und Ansicht neu abrufen")
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ReadingViewResponse.of(views.view(caller, sourceId, pageId, refresh));
    }

    /**
     * Antwort der Lese-Ansicht.
     *
     * @param status         available, restricted oder unsupported
     * @param foreignMacros  Makros, mit denen die Quelle Fremdes einbindet
     * @param foreignContent ob die Seite Inhalte anderer Seiten oder Systeme einbindet
     */
    public record ReadingViewResponse(String sourceId, String pageId, String spaceKey, String title, String version,
                                      String status, String html, String baseUri, String text, List<Image> images,
                                      boolean foreignContent, List<String> foreignMacros, boolean stored,
                                      Instant renderedAt) {

        static ReadingViewResponse of(ReadingView view) {
            return new ReadingViewResponse(view.header().key().sourceId(), view.header().key().pageId(),
                    view.header().metadata().partitionKey(), view.header().metadata().title(), view.version(),
                    view.status().name().toLowerCase(Locale.ROOT), view.html(), view.baseUri(), view.text(),
                    view.images().stream().map(Image::of).toList(), view.foreign().present(),
                    view.foreign().macros(), view.stored(), view.renderedAt());
        }
    }

    /** Ein Bild der Ansicht; die Bytes liefert die Bildbeschaffung. */
    public record Image(String src, String title, String attachmentId, String mediaType, String version) {

        static Image of(HarvestSource.ViewImage image) {
            return new Image(image.src(), image.title(), image.attachmentId(), image.mediaType(), image.version());
        }
    }
}
