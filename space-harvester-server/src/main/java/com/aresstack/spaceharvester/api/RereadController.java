package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.assets.DocumentRereads;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * „Neu lesen“ (ADR 0026, Punkt 11): liest die abgelegten Dokument-Anhänge einer Quelle ohne Download neu. Ändert den
 * Datenbestand der Quelle, daher wie „Daten bereinigen“ und der Import nur für Clients mit
 * {@code manage-configuration}.
 */
@RestController
@RequestMapping(path = RereadController.REREADS, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Quellen", description = "Quellen und ihre lokal gespeicherten Daten")
public class RereadController {

    public static final String REREADS = SourceDataController.DATA + "/rereads";

    private final DocumentRereads rereads;
    private final Jobs jobs;

    public RereadController(DocumentRereads rereads, Jobs jobs) {
        this.rereads = rereads;
        this.jobs = jobs;
    }

    @PostMapping
    @Operation(summary = "Abgelegte Dokumente einer Quelle neu lesen",
            description = "Startet einen Auftrag (Art reread), der die abgelegten Dokument-Anhänge der Quelle ohne "
                    + "Download neu liest. Ohne status trifft er jedes Dokument ohne Extraktion der eingestellten "
                    + "Version, etwa nach einem Versionswechsel oder einem Import; mit status nur Dokumente, deren "
                    + "Extraktion einen dieser Status hat, etwa failed oder partial nach geänderten Grenzen. Bis das "
                    + "neue Lesen Text liefert, bleibt der bisherige Text lieferbar und durchsuchbar (stale: true); "
                    + "ein gescheitertes Lesen lässt ihn stehen. Erst nach erfolgreichem Lesen ersetzt der Auftrag "
                    + "den Volltext und entfernt ältere Stände. Nur für Clients mit manage-configuration.")
    @ApiResponse(responseCode = "202", description = "RereadJobResponse; Stand unter Location")
    @ApiResponse(responseCode = "400", description = "Unbekannter Status (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Client darf die Konfiguration nicht verwalten (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<RereadJobResponse> start(@Parameter(hidden = true) AccessContext caller,
                                                   @PathVariable String sourceId,
                                                   @Parameter(description = "Nur Dokumente mit diesem Status der "
                                                           + "Extraktion; mehrfach oder kommagetrennt",
                                                           schema = @Schema(allowableValues = {"ok", "partial",
                                                                   "unsupported", "failed"}))
                                                   @RequestParam(required = false) List<String> status) {
        JobStatus job = rereads.start(sourceId, caller.jobOwner(), statuses(status));
        return ResponseEntity.accepted()
                .location(URI.create(ApiPaths.V1 + "/sources/" + sourceId + "/data/rereads/" + job.id()))
                .body(RereadJobResponse.of(job));
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Stand eines Neulesens",
            description = "discovered = abgelegte Dokumente (je Inhalt einmal), processed = geprüft, written = neu "
                    + "gelesen, failed = gescheitert (der alte Stand bleibt).")
    @ApiResponse(responseCode = "200", description = "RereadJobResponse")
    @ApiResponse(responseCode = "404", description = "Kein eigenes Neulesen dieser Quelle (job-not-found)")
    public RereadJobResponse status(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                                    @PathVariable String jobId) {
        JobStatus job = jobs.status(jobId, caller.jobOwner());
        if (job.type() != JobType.REREAD || !job.sourceId().equals(sourceId)) {
            throw new UnknownJobException(jobId);
        }
        return RereadJobResponse.of(job);
    }

    /** Die Status aus der Anfrage; leer ohne Beschränkung. */
    static Set<ExtractionStatus> statuses(List<String> values) {
        Set<ExtractionStatus> statuses = EnumSet.noneOf(ExtractionStatus.class);
        if (values == null) {
            return statuses;
        }
        for (String value : values) {
            for (String name : value.split(",")) {
                if (name.isBlank()) {
                    continue;
                }
                try {
                    statuses.add(ExtractionStatus.valueOf(name.strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new InvalidQueryException("status ist unbekannt");
                }
            }
        }
        return statuses;
    }

    /** Ein Auftrag „Neu lesen“ mit den Status, auf die er beschränkt ist (leer: alle ohne aktuelle Extraktion). */
    public record RereadJobResponse(String id, String sourceId,
                                    @Schema(allowableValues = {"queued", "running", "paused", "completed", "failed",
                                            "cancelled"}) String state,
                                    List<String> statuses, JobProgress progress,
                                    @Schema(nullable = true) JobError error, Instant createdAt,
                                    @Schema(nullable = true) Instant startedAt,
                                    @Schema(nullable = true) Instant finishedAt) {

        static RereadJobResponse of(JobStatus job) {
            return new RereadJobResponse(job.id(), job.sourceId(), job.state().name().toLowerCase(Locale.ROOT),
                    DocumentRereads.statuses(job).stream().map(status -> status.name().toLowerCase(Locale.ROOT))
                            .sorted().toList(), job.progress(), job.error(), job.createdAt(), job.startedAt(),
                    job.finishedAt());
        }
    }
}
