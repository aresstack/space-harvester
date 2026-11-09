package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.sources.SourceDataPurge;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * „Daten bereinigen“: löscht alle lokal gespeicherten Daten einer Quelle (Seiten-Cache, Katalog, Volltext,
 * Analysen, Anhänge, Begriffsbeziehungen, geprüfte Fakten), ohne die Quelle selbst zu entfernen. Andere Quellen
 * bleiben unberührt. Wie die Laufzeitkonfiguration nur für technische Clients mit {@code manage-configuration}.
 */
@RestController
@RequestMapping(path = SourceDataController.DATA, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Quellen", description = "Quellen und ihre lokal gespeicherten Daten")
public class SourceDataController {

    public static final String DATA = ApiPaths.V1 + "/sources/{sourceId}/data";

    private final SourceDataPurge purge;
    private final Jobs jobs;

    public SourceDataController(SourceDataPurge purge, Jobs jobs) {
        this.purge = purge;
        this.jobs = jobs;
    }

    @DeleteMapping
    @Operation(summary = "Alle lokal gespeicherten Daten einer Quelle löschen",
            description = "Startet einen Auftrag (Art purge): bricht die übrigen Aufträge der Quelle ab und leert "
                    + "dann je Ablage alles, was an dieser Quelle hängt. Die Quelle bleibt eingerichtet, auch eine "
                    + "abgeschaltete lässt sich bereinigen. Der Bestand baut sich erst mit dem nächsten Abgleich "
                    + "oder Download neu auf, das kann lange dauern. Exporte bleiben. Nur für Clients mit "
                    + "manage-configuration.")
    @ApiResponse(responseCode = "202", description = "PurgeJobResponse; Stand unter Location")
    @ApiResponse(responseCode = "403", description = "Client darf die Konfiguration nicht verwalten (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PurgeJobResponse> purge(@Parameter(hidden = true) AccessContext caller,
                                                  @PathVariable String sourceId) {
        JobStatus job = purge.start(sourceId, caller.jobOwner());
        return ResponseEntity.accepted()
                .location(java.net.URI.create(ApiPaths.V1 + "/sources/" + sourceId + "/data/purges/" + job.id()))
                .body(PurgeJobResponse.of(job, purge.stores()));
    }

    @GetMapping("/purges/{jobId}")
    @Operation(summary = "Stand einer Bereinigung",
            description = "Fortschritt je Ablage: discovered = Anzahl Ablagen, processed = bearbeitet, written = "
                    + "geleert, failed = gescheitert.")
    @ApiResponse(responseCode = "200", description = "PurgeJobResponse")
    @ApiResponse(responseCode = "404", description = "Kein eigener Bereinigungsauftrag dieser Quelle (job-not-found)")
    public PurgeJobResponse status(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                                   @PathVariable String jobId) {
        JobStatus job = jobs.status(jobId, caller.jobOwner());
        if (job.type() != JobType.PURGE || !job.sourceId().equals(sourceId)) {
            throw new UnknownJobException(jobId);
        }
        return PurgeJobResponse.of(job, purge.stores());
    }

    /** Ein Bereinigungsauftrag mit den Ablagen, die er leert. */
    public record PurgeJobResponse(String id, String sourceId,
                                   @Schema(allowableValues = {"queued", "running", "completed", "failed",
                                           "cancelled"}) String state, JobProgress progress,
                                   @Schema(nullable = true) JobError error, List<String> stores,
                                   Instant createdAt, @Schema(nullable = true) Instant startedAt,
                                   @Schema(nullable = true) Instant finishedAt) {

        static PurgeJobResponse of(JobStatus job, List<String> stores) {
            return new PurgeJobResponse(job.id(), job.sourceId(), job.state().name().toLowerCase(Locale.ROOT),
                    job.progress(), job.error(), stores, job.createdAt(), job.startedAt(), job.finishedAt());
        }
    }
}
