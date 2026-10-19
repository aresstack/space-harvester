package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.passages.PassageId;
import com.aresstack.spaceharvester.application.passages.PassageLookup;
import com.aresstack.spaceharvester.application.passages.PassageQueries;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Einen Absatz über seine stabile Passage-ID lesen (EAC-Lücke 5, wie {@code KnowledgeResourceReader} im Enterprise AI
 * Client). Nur Adapter von {@link PassageQueries#resolve}. Die ID steht im Parameter {@code id}, nicht im Pfad: Sie
 * enthält {@code #} und Seiten-IDs mancher Quellen {@code /}.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/passages", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Seiten (Cache)", description = "Seiten, Baum und native Payload aus dem Cache des Harvesters")
public class PassageLookupController {

    private final PassageQueries passages;

    public PassageLookupController(PassageQueries passages) {
        this.passages = passages;
    }

    @GetMapping
    @Operation(summary = "Absatz über seine Passage-ID",
            description = "Löst eine Passage-ID aus GET …/pages/{pageId}/passages auf. match=found: der Absatz mit "
                    + "je einem Nachbarn; older-version: die ID gehört zu einer älteren Fassung der Seite (anderer "
                    + "Text), es folgen alle aktuellen Absätze; changed: gleicher Text, aber Zerlegung oder "
                    + "Embedding-Modell gewechselt, es folgen alle aktuellen Absätze; unavailable: für die Seite "
                    + "liegen gerade keine Absätze vor (state sagt warum). Nachziehen wie "
                    + "GET …/pages/{pageId}/passages; eine nicht sichtbare Seite antwortet wie eine unbekannte (404).")
    @ApiResponse(responseCode = "200", description = "PassageLookupResponse")
    @ApiResponse(responseCode = "400", description = "Keine Passage-ID (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Seite nicht vorhanden, nicht lesbar oder ohne content-Freigabe; "
            + "beides gleich (cache-miss), damit eine ID nicht verrät, ob es die Seite gibt",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PassageLookupResponse lookup(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Passage-ID <sourceId>:<pageId>@<capture>#p<position>@<zerlegung>-<modell>")
            @RequestParam String id) {
        if (!PassageId.looksLike(id)) {
            throw new InvalidQueryException("id ist keine Passage-ID");
        }
        PassageId requested = PassageId.parse(id);
        try {
            return PassageLookupResponse.of(passages.resolve(caller, id));
        } catch (AccessDeniedException e) {
            // Eine ID nennt Seiten, die der Aufrufer vielleicht nicht sehen darf: unsichtbar antwortet wie unbekannt.
            throw new CacheMissException(requested.sourceId(), "Seite nicht vorhanden oder nicht sichtbar");
        }
    }

    /**
     * Ein aufgelöster Absatz.
     *
     * @param requested die angefragte ID
     * @param match     found, older-version, changed oder unavailable
     * @param state     Zustand der Absätze der Seite wie bei …/passages (current, stale, failed, missing)
     * @param passages  bei found der Absatz mit Nachbarn, sonst alle aktuellen Absätze
     */
    public record PassageLookupResponse(String requested, String match, String sourceId, String pageId, String state,
                                        List<PassagesController.PassageEntry> passages) {

        static PassageLookupResponse of(PassageLookup found) {
            return new PassageLookupResponse(found.requested().value(),
                    found.match().name().toLowerCase(Locale.ROOT).replace('_', '-'), found.requested().sourceId(),
                    found.requested().pageId(), found.page().state().name().toLowerCase(Locale.ROOT),
                    found.passages().stream().map(PassagesController.PassageEntry::of).toList());
        }
    }
}
