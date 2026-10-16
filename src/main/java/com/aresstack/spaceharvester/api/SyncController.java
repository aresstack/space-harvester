package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.harvest.SpaceKeyCheck;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.SyncSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Abgleich eines Space mit der Quelle (#16): starten, Status samt Delta lesen, abbrechen. Ein Abgleich läuft als
 * Auftrag im Hintergrund; nur der auslösende Client sieht ihn, und nur solange er die {@code sync}-Freigabe für den
 * Space hat.
 */
@RestController
@RequestMapping(path = ApiPaths.V1, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Abgleich", description = "Abgleich eines Space mit der Quelle als Hintergrundauftrag")
public class SyncController {

    private final SpaceSyncJobs syncs;
    private final AccessGuard access;
    private final SpaceKeyCheck spaces;

    public SyncController(SpaceSyncJobs syncs, AccessGuard access, SpaceKeyCheck spaces) {
        this.syncs = syncs;
        this.access = access;
        this.spaces = spaces;
    }

    @PostMapping("/sources/{sourceId}/spaces/{spaceKey}/syncs")
    @Operation(summary = "Abgleich eines Space starten",
            description = "Nimmt den Abgleich als Auftrag an und antwortet sofort; Location nennt den Status. "
                    + "Neue und geänderte Seiten werden geladen, entfernte erst nach Einzelprüfung gelöscht. "
                    + "Leser sehen die Änderungen erst, wenn der Lauf eine neue Generation bestätigt; ein "
                    + "unvollständiger oder abgebrochener Lauf hinterlässt nichts. Je Space läuft höchstens ein "
                    + "Abgleich.")
    @ApiResponse(responseCode = "202", description = "Abgleich angenommen")
    @ApiResponse(responseCode = "400", description = "Kein gültiger Space-Key (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe für diesen Space oder keine Quelle mit "
            + "dieser ID (access-denied)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found) oder Space, den die "
            + "Quelle nicht kennt oder nicht zeigt (source-object-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Für diesen Space läuft bereits ein Abgleich "
            + "(sync-already-running)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Keine weiteren Aufträge möglich (job-capacity-exceeded)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Cache-Speicher oder Inhaltsabruf nicht angebunden "
            + "(sync-unavailable)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<SyncResponse> start(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Space-Key", example = "DEV") @PathVariable String spaceKey) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        // Erst nach der Freigabe: sonst verriete die Antwort Unberechtigten, welche Spaces es gibt.
        spaces.requireExisting(sourceId, spaceKey);
        JobStatus status = syncs.start(sourceId, spaceKey, caller.principal());
        return ResponseEntity.accepted()
                .location(URI.create(ApiPaths.V1 + "/syncs/" + status.id()))
                .body(SyncResponse.of(new SpaceSyncJobs.SyncJob(status, null), false));
    }

    @GetMapping("/syncs/{syncId}")
    @Operation(summary = "Status eines Abgleichs",
            description = "Zustand, Zähler und, sobald der Lauf fertig ist, sein Delta. completed nur mit neu "
                    + "bestätigter Generation; failed mit error.code SYNC_INCOMPLETE (Inventur unvollständig oder "
                    + "widersprüchlich, nichts übernommen) oder SYNC_CONFLICT (ein anderer Lauf hat inzwischen "
                    + "bestätigt). Beendete Abgleiche verschwinden nach der Aufbewahrungsfrist.")
    @ApiResponse(responseCode = "200", description = "Der Abgleich")
    @ApiResponse(responseCode = "404", description = "Kein eigener Abgleich mit dieser ID (sync-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public SyncResponse status(@Parameter(hidden = true) AccessContext caller,
                               @Parameter(description = "ID aus der Location des Starts") @PathVariable String syncId) {
        SpaceSyncJobs.SyncJob job = visible(caller, syncs.status(syncId, caller.principal()));
        return SyncResponse.of(job, pageIdsVisible(caller, job));
    }

    @PostMapping("/syncs/{syncId}/cancel")
    @Operation(summary = "Abgleich abbrechen",
            description = "Fordert den Abbruch an; der Lauf hält vor dem nächsten Seitenabruf an und verwirft alles "
                    + "Vorgemerkte. Ein beendeter Abgleich bleibt unverändert.")
    @ApiResponse(responseCode = "200", description = "Der Abgleich nach der Anforderung")
    @ApiResponse(responseCode = "404", description = "Kein eigener Abgleich mit dieser ID (sync-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public SyncResponse cancel(@Parameter(hidden = true) AccessContext caller,
                               @Parameter(description = "ID aus der Location des Starts") @PathVariable String syncId) {
        visible(caller, syncs.status(syncId, caller.principal()));
        SpaceSyncJobs.SyncJob job = syncs.cancel(syncId, caller.principal());
        return SyncResponse.of(job, pageIdsVisible(caller, job));
    }

    /** Ein Abgleich, dessen Space nicht mehr freigegeben ist, gilt als unbekannt. */
    private SpaceSyncJobs.SyncJob visible(AccessContext caller, SpaceSyncJobs.SyncJob job) {
        JobStatus status = job.status();
        if (!access.permits(caller, ResourceScope.space(status.sourceId(), status.partitionKey()), AccessKind.SYNC)) {
            throw new UnknownJobException(status.id());
        }
        return job;
    }

    /** Seiten-IDs sind Metadaten: {@code sync} allein zeigt nur Zähler. */
    private boolean pageIdsVisible(AccessContext caller, SpaceSyncJobs.SyncJob job) {
        return access.permits(caller, ResourceScope.space(job.status().sourceId(), job.status().partitionKey()),
                AccessKind.METADATA);
    }

    /**
     * Ein Abgleich.
     *
     * @param id              Kennung für Status und Abbruch
     * @param state           {@code queued}, {@code running}, {@code completed}, {@code failed} oder {@code cancelled}
     * @param progress        Zähler: discovered gelesene Seiten, processed abgeglichene, written vorgemerkte
     *                        Änderungen, failed fehlgeschlagene
     * @param error           nur bei {@code failed}
     * @param cancelRequested Abbruch angefordert, aber noch nicht wirksam
     * @param summary         Delta des Laufs, sobald er eines hat, sonst {@code null}
     */
    public record SyncResponse(String id, String sourceId, String spaceKey,
                               @Schema(allowableValues = {"queued", "running", "completed", "failed", "cancelled"})
                               String state, JobProgress progress, @Schema(nullable = true) JobError error,
                               boolean cancelRequested, Instant createdAt, @Schema(nullable = true) Instant startedAt,
                               @Schema(nullable = true) Instant finishedAt,
                               @Schema(nullable = true) SummaryResponse summary) {

        static SyncResponse of(SpaceSyncJobs.SyncJob job, boolean pageIds) {
            JobStatus status = job.status();
            return new SyncResponse(status.id(), status.sourceId(), status.partitionKey(),
                    status.state().name().toLowerCase(Locale.ROOT), status.progress(), status.error(),
                    status.cancelRequested(), status.createdAt(), status.startedAt(), status.finishedAt(),
                    job.summary() == null ? null : SummaryResponse.of(job.summary(), pageIds));
        }
    }

    /**
     * Delta eines Laufs, nur Zähler und Seiten-IDs.
     *
     * @param inventory         {@code complete}, {@code incomplete} oder {@code inconsistent}
     * @param listed            gelesene Seiten
     * @param added             neu erkannte Seiten
     * @param contentChanged    Seiten mit neuer Revision
     * @param metadataChanged   neue Metadaten bei gleicher Revision (umbenannt, verschoben)
     * @param attributesChanged nur neue Attribute (Labels)
     * @param unchanged         unveränderte Seiten
     * @param removed           entfernte Seiten (gelöscht oder nicht mehr sichtbar)
     * @param movedOut          in einen anderen Space verschobene Seiten
     * @param unverified        fehlende Seiten, deren Fehlen nicht bestätigt werden konnte; bleiben als veraltet
     * @param failed            Anzahl der Seiten, deren Abgleich fehlgeschlagen ist; bleiben als veraltet
     * @param failedPageIds     ihre IDs, nur mit {@code metadata}-Freigabe für den Space, sonst {@code null}
     * @param generation        neu bestätigte Generation oder {@code null}
     * @param spaceFingerprint  Fingerabdruck der neuen Generation ({@code space-inventory/v1}) oder {@code null}
     * @param notConfirmed      warum keine Generation bestätigt wurde, sonst {@code null}
     */
    public record SummaryResponse(
            @Schema(allowableValues = {"complete", "incomplete", "inconsistent"}) String inventory, int listed,
            int added, int contentChanged, int metadataChanged, int attributesChanged, int unchanged, int removed,
            int movedOut, int unverified, int failed, @Schema(nullable = true) List<String> failedPageIds, @Schema(nullable = true) Long generation,
            @Schema(nullable = true) String spaceFingerprint, @Schema(nullable = true) String notConfirmed) {

        static SummaryResponse of(SyncSummary summary, boolean pageIds) {
            return new SummaryResponse(summary.inventory().name().toLowerCase(Locale.ROOT), summary.listed(),
                    summary.added(), summary.contentChanged(), summary.metadataChanged(), summary.attributesChanged(),
                    summary.unchanged(), summary.removed(), summary.movedOut(), summary.unverified(),
                    summary.failedPageIds().size(), pageIds ? summary.failedPageIds() : null, summary.generation(), summary.spaceFingerprint(), summary.notConfirmed());
        }
    }
}
