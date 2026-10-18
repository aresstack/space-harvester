package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.sources.ConnectionStep;
import com.aresstack.spaceharvester.application.sources.SourceDiagnoses;
import com.aresstack.spaceharvester.application.sources.SourceDiagnosis;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
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

/** Verbindungsdiagnose einer Quelle in Stufen (#189). */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/sources", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Quellen", description = "Konfigurierte Wissensquellen und ihre Spaces")
public class DiagnosisController {

    private final SourceDiagnoses diagnostics;
    private final AccessGuard access;

    public DiagnosisController(SourceDiagnoses diagnostics, AccessGuard access) {
        this.diagnostics = diagnostics;
        this.access = access;
    }

    @GetMapping("/{sourceId}/diagnosis")
    @Operation(summary = "Verbindungsdiagnose der Quelle in Stufen",
            description = "Geht live denselben Weg wie die Quelle und meldet je Stufe Ergebnis, Ursache und Hinweis: "
                    + "proxy-route, dns, tcp (bei Proxy samt CONNECT-Tunnel und Proxy-Anmeldung), tls, "
                    + "authentication, api. Nach einer gescheiterten Stufe sind die folgenden skipped. Legt nichts "
                    + "ab; Meldungen nennen nie Zugangsdaten und keine Zahl der Spaces. Die Antwort ist auch bei "
                    + "einer gescheiterten Verbindung 200.")
    @ApiResponse(responseCode = "200", description = "Ergebnis der Diagnose")
    @ApiResponse(responseCode = "403", description = "Keine Freigabe für diese Quelle oder keine Quelle mit dieser ID "
            + "(access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Keine Quelle mit dieser ID konfiguriert (source-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public DiagnosisResponse diagnosis(@Parameter(hidden = true) AccessContext caller,
                                       @Parameter(description = "Quellen-ID aus der Konfiguration",
                                               example = "confluence")
                                       @PathVariable String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        return DiagnosisResponse.of(sourceId, diagnostics.diagnose(sourceId)
                .orElseThrow(() -> new UnknownSourceException(sourceId)));
    }

    /**
     * @param successful ob keine Stufe gescheitert ist
     * @param steps      je Stufe ein Schritt, in fester Reihenfolge
     */
    public record DiagnosisResponse(String sourceId, boolean successful, List<StepResponse> steps) {

        static DiagnosisResponse of(String sourceId, SourceDiagnosis diagnosis) {
            return new DiagnosisResponse(sourceId, diagnosis.successful(),
                    diagnosis.steps().stream().map(StepResponse::of).toList());
        }
    }

    /**
     * @param stage   {@code proxy-route}, {@code dns}, {@code tcp}, {@code tls}, {@code authentication} oder {@code api}
     * @param status  {@code ok}, {@code info}, {@code warning}, {@code failed} oder {@code skipped}
     * @param label   Name der Stufe für Benutzer
     * @param message Ergebnis oder Ursache
     * @param hint    was zu tun ist; nur bei {@code failed} oder {@code warning}, sonst {@code null}
     */
    public record StepResponse(String stage, String status, String label, String message, String hint) {

        static StepResponse of(ConnectionStep step) {
            // Die Zahl der Spaces kennt der Aufrufer nicht unbedingt: Freigaben gelten je Space.
            String message = step.stage() == ConnectionStep.Stage.API && step.status() == ConnectionStep.Status.OK
                    ? "Spaces lesbar" : step.message();
            return new StepResponse(step.stage().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    step.status().name().toLowerCase(Locale.ROOT), step.stage().label(), message, step.hint());
        }
    }
}
