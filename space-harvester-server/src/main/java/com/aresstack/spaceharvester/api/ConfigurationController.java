package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.ConfigurationSnapshot;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Konfiguration zur Laufzeit (ADR 0015). Beide Routen verlangt die Sicherheitskonfiguration von einem technischen
 * Client mit {@code manage-configuration: true}; Endnutzer und andere Clients bekommen 403. Geheimnisse (API-Key,
 * Proxy-Passwort) erscheinen in keiner Antwort.
 */
@RestController
@RequestMapping(path = ConfigurationController.CONFIGURATION, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Konfiguration", description = "Wirksame Konfiguration lesen und zur Laufzeit wechseln (ADR 0015)")
public class ConfigurationController {

    public static final String CONFIGURATION = ApiPaths.V1 + "/configuration";

    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";

    private final ConfigurationManager manager;
    private final NetworkDescription network;

    public ConfigurationController(ConfigurationManager manager, NetworkDescription network) {
        this.manager = manager;
        this.network = network;
    }

    @GetMapping
    @Operation(summary = "Wirksame Konfiguration mit Version, ohne Geheimnisse",
            description = "KI-Dienst, Suche, Synchronisierung, Netzwerk und Speicherort der wirksamen Version. "
                    + "Vom API-Key und vom Proxy-Passwort steht nur, ob sie gesetzt sind. Nur für Clients mit "
                    + "manage-configuration.")
    @ApiResponse(responseCode = "200", description = "Wirksame Konfiguration")
    @ApiResponse(responseCode = "403", description = "Client darf die Konfiguration nicht verwalten (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ConfigurationResponse configuration() {
        return ConfigurationResponse.of(manager.current(), network);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Einzelne Schlüssel über die wirksame Konfiguration legen und aktivieren",
            description = "Ein JSON-Objekt flacher Property-Schlüssel ohne oder mit Präfix space-harvester., etwa "
                    + "ai.embedding-model, ai.embedding-dimensions, search.semantic, sync.interval oder "
                    + "cache.directory. Nicht genannte Schlüssel bleiben, auch der API-Key; ein leerer Wert oder null "
                    + "setzt zurück. Proxy und TLS (network.*) wechseln nur über die Konfigurationsquelle. Laufende "
                    + "Aufträge behalten die Version, mit der sie begonnen haben. cache.directory wirkt erst nach "
                    + "einem Neustart (restartRequired). Mit dryRun=true wird nur geprüft.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject(value = "{\"ai.embedding-model\":\"text-embedding-3-small\","
                            + "\"ai.embedding-dimensions\":\"1536\",\"sync.interval\":\"P1D\"}"))))
    @ApiResponse(responseCode = "200", description = "Aktiviert (ACTIVATED), unverändert (UNCHANGED) oder bei "
            + "dryRun gültig (VALID)")
    @ApiResponse(responseCode = "400", description = "Konfiguration abgelehnt (configuration-rejected); problems "
            + "nennt je Schlüssel, was falsch ist oder fehlt, nie den Wert",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Client darf die Konfiguration nicht verwalten (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<?> apply(@RequestBody Map<String, Object> values,
                                   @Parameter(description = "nur prüfen, nichts aktivieren")
                                   @RequestParam(defaultValue = "false") boolean dryRun) {
        ConfigurationSnapshot current = manager.current();
        HarvesterConfiguration.Parsed parsed = ConfigurationOverlay.overlay(current.configuration(), values);
        if (!parsed.valid()) {
            return rejected(parsed.problems(), current);
        }
        HarvesterConfiguration next = parsed.configuration();
        if (dryRun) {
            List<ConfigurationProblem> problems = manager.validate(next);
            if (!problems.isEmpty()) {
                return rejected(problems, current);
            }
            List<String> restart = manager.restartRequired(next);
            boolean unchanged = next.withStorage(current.configuration().storage()).equals(current.configuration());
            return ResponseEntity.ok(new ActivationResponse(unchanged ? "UNCHANGED" : "VALID", current.version(),
                    current.activatedAt().toString(), !restart.isEmpty(), restart, true));
        }
        ConfigurationManager.Activation activation = manager.apply(next);
        if (activation.outcome() == ConfigurationManager.Outcome.REJECTED) {
            return rejected(activation.problems(), activation.snapshot());
        }
        return ResponseEntity.ok(new ActivationResponse(activation.outcome().name(), activation.snapshot().version(),
                activation.snapshot().activatedAt().toString(), !activation.restartRequired().isEmpty(),
                activation.restartRequired(), false));
    }

    private static ResponseEntity<ProblemDetail> rejected(List<ConfigurationProblem> problems,
                                                          ConfigurationSnapshot current) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Die Konfiguration wurde nicht aktiviert; Version " + current.version() + " bleibt wirksam.");
        problem.setType(URI.create(TYPE_BASE + "configuration-rejected"));
        problem.setTitle("Konfiguration abgelehnt");
        problem.setProperty("version", current.version());
        problem.setProperty("problems", problems.stream()
                .map(p -> Map.of("key", p.key(), "message", p.message())).toList());
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /**
     * Ergebnis eines {@code POST}.
     *
     * @param outcome             {@code ACTIVATED}, {@code UNCHANGED} oder bei {@code dryRun} {@code VALID}
     * @param version             die danach wirksame Version
     * @param activatedAt         ihre Aktivierung (ISO-8601)
     * @param restartRequired     ob geänderte Werte erst nach einem Neustart wirken
     * @param restartRequiredKeys diese Schlüssel, etwa {@code cache.directory}
     * @param dryRun              nur geprüft
     */
    public record ActivationResponse(String outcome, long version, String activatedAt, boolean restartRequired,
                                     List<String> restartRequiredKeys, boolean dryRun) {
    }
}
