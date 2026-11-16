package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.changelog.ChangeEntries;
import com.aresstack.spaceharvester.application.changelog.ChangeFilter;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeLog;
import com.aresstack.spaceharvester.application.changelog.ChangeReport;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Änderungsprotokoll und Updates je Quelle (ADR 0030): Updates zwischen zwei manuellen Synchronisierungen, der
 * Änderungsbericht eines Updates und die rohen Zeilen mit Suche. Jede Zeile hat die Leserechte ihrer Seite; was der
 * Client an der Seite nicht sehen darf, fehlt auch in den Zählern.
 */
@RestController
@Tag(name = "Änderungsprotokoll", description = "Was sich in den Quellen geändert hat, je Update und als Protokoll")
public class ChangeLogController {

    public static final String UPDATES = ApiPaths.V1 + "/sources/{sourceId}/updates";
    public static final String ENTRIES = ApiPaths.V1 + "/change-log";

    private final ChangeLog changeLog;

    public ChangeLogController(ChangeLog changeLog) {
        this.changeLog = changeLog;
    }

    @GetMapping(path = UPDATES, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Updates einer Quelle",
            description = "Neuestes zuerst. Ein Update ist alles nach der vorigen bis einschließlich dieser Update-Marke, "
                    + "gleich welcher Auslöser es geholt hat; das offene Update (id open) nach der letzten Marke steht "
                    + "vorn, wenn es Änderungen hat. Das erste Update ist die Erstbefüllung (initial).")
    @ApiResponse(responseCode = "200", description = "Liste der Updates")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public List<ChangeUpdate> updates(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId) {
        return changeLog.updates(caller, sourceId);
    }

    @GetMapping(path = UPDATES + "/{updateId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Änderungsbericht eines Updates",
            description = "Die geänderten Seiten nach Art und Titel, mehrere Änderungen derselben Seite zu einer "
                    + "zusammengefasst, mit Pfad und Verweis. Bei der Erstbefüllung nur Zähler. Mit diff=true trägt "
                    + "jede Seite mit Inhaltsänderung ihren Textunterschied (Absätze alt gegen neu), sofern der "
                    + "Client ihren Inhalt lesen darf.")
    @ApiResponse(responseCode = "200", description = "Änderungsbericht")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found) oder kein solches Update "
            + "(update-not-found)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ChangeReport report(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                               @PathVariable String updateId,
                               @Parameter(description = "Textunterschied je geänderter Seite mitliefern")
                               @RequestParam(name = "diff", defaultValue = "false") boolean diff) {
        return changeLog.report(caller, sourceId, updateId, diff);
    }

    @GetMapping(path = ENTRIES, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rohe Zeilen des Änderungsprotokolls",
            description = "Neueste zuerst, seitenweise. q sucht Wörter im alten oder neuen Titel (als Wortanfang) oder "
                    + "genau eine Seiten-ID; kind und trigger dürfen mehrfach stehen; from einschließlich, to "
                    + "ausschließlich (ISO-8601).")
    @ApiResponse(responseCode = "200", description = "Zeilen und Gesamtzahl")
    @ApiResponse(responseCode = "400", description = "Ungültige Angabe (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ChangeEntries entries(@Parameter(hidden = true) AccessContext caller,
                                 @RequestParam(required = false) String sourceId,
                                 @RequestParam(required = false) String q,
                                 @RequestParam(required = false) List<String> kind,
                                 @RequestParam(required = false) List<String> trigger,
                                 @RequestParam(required = false) String space,
                                 @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "100") int limit) {
        if (offset < 0 || limit < 1 || limit > ChangeFilter.MAX_LIMIT) {
            throw new InvalidQueryException("offset ab 0, limit zwischen 1 und " + ChangeFilter.MAX_LIMIT);
        }
        return changeLog.entries(caller, new ChangeFilter(sourceId, q, kinds(kind), triggers(trigger), space,
                instant("from", from), instant("to", to), offset, limit));
    }

    private static Set<ChangeKind> kinds(List<String> values) {
        Set<ChangeKind> kinds = EnumSet.noneOf(ChangeKind.class);
        if (values != null) {
            for (String value : values) {
                try {
                    kinds.add(ChangeKind.valueOf(value.strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new InvalidQueryException("Unbekannte Art der Änderung");
                }
            }
        }
        return kinds;
    }

    private static Set<ChangeTrigger> triggers(List<String> values) {
        Set<ChangeTrigger> triggers = EnumSet.noneOf(ChangeTrigger.class);
        if (values != null) {
            for (String value : values) {
                ChangeTrigger trigger = ChangeTrigger.parse(value);
                if (trigger == ChangeTrigger.UNKNOWN && !"unknown".equalsIgnoreCase(value.strip())) {
                    throw new InvalidQueryException("Unbekannter Auslöser");
                }
                triggers.add(trigger);
            }
        }
        return triggers;
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new InvalidQueryException(name + " ist kein Zeitpunkt nach ISO-8601");
        }
    }
}
