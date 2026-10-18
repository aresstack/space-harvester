package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.sync.Reconciliation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Zustand des regelmäßigen Abgleichs je Space (ADR 0014): welche Spaces eingeplant sind, wann der letzte fällige Start
 * war, mit welchem Ergebnis, und wann der nächste folgt. Nur Spaces, für die der Aufrufer die {@code sync}-Freigabe hat.
 */
@RestController
@RequestMapping(path = ApiPaths.V1, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Abgleich", description = "Abgleich eines Space mit der Quelle als Hintergrundauftrag")
public class ReconciliationController {

    private final Reconciliation reconciliation;
    private final AccessGuard access;

    public ReconciliationController(Reconciliation reconciliation, AccessGuard access) {
        this.reconciliation = reconciliation;
        this.access = access;
    }

    @GetMapping("/reconciliations")
    @Operation(summary = "Regelmäßiger Abgleich je Space",
            description = "Eingeplante Spaces aus space-harvester.sync.spaces mit Abstand, ob unbekannte Seiten "
                    + "geladen werden, letztem fälligen Start samt Ergebnis und nächstem Start. Leer, wenn nichts "
                    + "eingeplant ist; dann behauptet der Dienst keine lückenlose Aktualität. Den Lauf selbst und sein "
                    + "Delta führt der Hintergrundauftrag.")
    @ApiResponse(responseCode = "200", description = "ReconciliationsResponse")
    public ReconciliationsResponse list(@Parameter(hidden = true) AccessContext caller) {
        if (caller == null) {
            return new ReconciliationsResponse(List.of());
        }
        return new ReconciliationsResponse(reconciliation.schedules().stream()
                .filter(schedule -> access.permits(caller,
                        ResourceScope.space(schedule.sourceId(), schedule.spaceKey()), AccessKind.SYNC))
                .map(ScheduleResponse::of)
                .toList());
    }

    /** @param reconciliations eingeplante Spaces, nach Quelle und Space */
    public record ReconciliationsResponse(List<ScheduleResponse> reconciliations) {
    }

    /**
     * @param sourceId    Quellen-ID
     * @param spaceKey    Space-Key
     * @param interval    Abstand zwischen zwei Starts (ISO 8601)
     * @param loadUnknown ob unbekannte Seiten geladen werden (Space wird vollständig gehalten)
     * @param nextAt      nächster fälliger Start
     * @param lastAt      letzter fälliger Start oder {@code null}
     * @param lastOutcome sein Ergebnis oder {@code null}
     */
    public record ScheduleResponse(String sourceId, String spaceKey, String interval, boolean loadUnknown,
                                   Instant nextAt, @Schema(nullable = true) Instant lastAt,
                                   @Schema(nullable = true, allowableValues = {"started", "already-running",
                                           "no-capacity", "not-started"}) String lastOutcome) {

        static ScheduleResponse of(Reconciliation.Schedule schedule) {
            return new ScheduleResponse(schedule.sourceId(), schedule.spaceKey(), schedule.interval().toString(),
                    schedule.loadUnknown(), schedule.nextAt(), schedule.lastAt(),
                    schedule.lastOutcome() == null ? null
                            : schedule.lastOutcome().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
    }
}
