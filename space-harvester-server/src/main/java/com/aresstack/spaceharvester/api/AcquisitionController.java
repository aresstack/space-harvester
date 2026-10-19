package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionStatus;
import com.aresstack.spaceharvester.application.acquisition.BackgroundFill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand der Beschaffung (ADR 0016): Beschaffungsplaner und Hintergrundfüllung, nur Zähler, nie Seiten-IDs oder
 * Suchtexte. Wie die Laufzeitkonfiguration nur für technische Clients mit {@code manage-configuration}.
 */
@RestController
@RequestMapping(path = AcquisitionController.ACQUISITION, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Konfiguration", description = "Wirksame Konfiguration lesen und zur Laufzeit wechseln (ADR 0015)")
public class AcquisitionController {

    public static final String ACQUISITION = ApiPaths.V1 + "/acquisition";

    private final AcquisitionScheduler scheduler;
    private final ObjectProvider<BackgroundFill> fill;

    public AcquisitionController(AcquisitionScheduler scheduler, ObjectProvider<BackgroundFill> fill) {
        this.scheduler = scheduler;
        this.fill = fill;
    }

    @GetMapping
    @Operation(summary = "Stand von Beschaffung und Hintergrundfüllung, nur Zähler",
            description = "Ob die Hintergrundfüllung an ist, warum sie gerade ruht (pause), Warteschlangen von Suche "
                    + "und Hintergrund, geladene und gescheiterte Seiten seit dem Start und dieselben Zähler je "
                    + "Quelle. Ohne Cache-Verzeichnis gibt es keine Füllung (enabled=false). Nur für Clients mit "
                    + "manage-configuration.")
    @ApiResponse(responseCode = "200", description = "Stand der Beschaffung")
    @ApiResponse(responseCode = "403", description = "Client darf die Konfiguration nicht verwalten (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public AcquisitionStatus status() {
        BackgroundFill available = fill.getIfAvailable();
        return available != null ? available.status() : BackgroundFill.withoutFill(scheduler);
    }
}
