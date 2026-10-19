package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.passages.DocumentNavigationView;
import com.aresstack.spaceharvester.application.passages.NavigationQueries;
import com.aresstack.spaceharvester.application.passages.NavigationSection;
import com.aresstack.spaceharvester.application.passages.PassagePayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dokumentnavigation einer Seite (#181, ADR 0020). Nur Adapter von {@link NavigationQueries}. */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Seiten (Cache)", description = "Seiten, Baum und native Payload aus dem Cache des Harvesters")
public class DocumentNavigationController {

    private final NavigationQueries navigation;

    public DocumentNavigationController(NavigationQueries navigation) {
        this.navigation = navigation;
    }

    @GetMapping("/{sourceId}/pages/{pageId}/navigation")
    @Operation(summary = "Dokumentnavigation einer Seite: Überschriften und erkannte Themenabschnitte",
            description = "Hauptkapitel in Lesereihenfolge mit Unterabschnitten, wie die Dokumentnavigation im "
                    + "Enterprise AI Client. origin=heading ist eine Überschrift der Seite, origin=topic ein "
                    + "erkannter Themenabschnitt (Embeddings je Absatz, zusammengefasst nach Thema). analysis=semantic "
                    + "mit erkannten Abschnitten, sonst analysis=headings mit reason (no-model, source-not-approved, "
                    + "not-embedded, no-topic-change, model-unavailable). Stellen sind UTF-16-Positionen im Text der "
                    + "Seite. state wie bei …/passages; ohne Ergebnis zeigt die Antwort sofort die Überschriften. "
                    + "Rechte wie GET …/pages/{pageId}.")
    @ApiResponse(responseCode = "200", description = "NavigationResponse")
    @ApiResponse(responseCode = "403", description = "Keine content-Freigabe (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Seite nicht vorhanden oder nicht lesbar (cache-miss)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public NavigationResponse navigation(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Seiten-ID der Quelle", example = "123456") @PathVariable String pageId) {
        return NavigationResponse.of(sourceId, pageId, navigation.page(caller, sourceId, pageId));
    }

    /**
     * Die Navigation einer Seite.
     *
     * @param state    current, stale, failed oder missing
     * @param analysis semantic oder headings
     * @param reason   bei headings warum, sonst {@code null}
     * @param chapters Hauptkapitel in Lesereihenfolge
     */
    public record NavigationResponse(String sourceId, String pageId, String state, String analysis,
                                     @Schema(nullable = true) String reason, List<SectionEntry> chapters) {

        static NavigationResponse of(String sourceId, String pageId, DocumentNavigationView found) {
            return new NavigationResponse(sourceId, pageId, PassagePayload.name(found.state()),
                    PassagePayload.name(found.analysis()), found.reason(),
                    found.chapters().stream().map(SectionEntry::of).toList());
        }
    }

    /**
     * Ein Abschnitt.
     *
     * @param id       in der Seite eindeutig
     * @param origin   heading oder topic
     * @param level    Ebene ab 1
     * @param start    erstes Zeichen (UTF-16)
     * @param end      Ende, exklusiv
     * @param anchor   Textanfang an start, höchstens 60 Zeichen
     * @param parentId übergeordnetes Kapitel oder {@code null}
     */
    public record SectionEntry(String id, String label, String origin, int level, int start, int end, String anchor,
                               @Schema(nullable = true) String parentId, List<SectionEntry> children) {

        static SectionEntry of(NavigationSection section) {
            return new SectionEntry(section.id(), section.label(), PassagePayload.name(section.origin()),
                    section.level(), section.start(), section.end(), section.anchor(), section.parentId(),
                    section.children().stream().map(SectionEntry::of).toList());
        }
    }
}
