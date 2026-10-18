package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext.PrincipalKind;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.export.ExportRequest;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asynchrone Space-Exporte (#11, #12). Start, Status, Abbruch und Download verlangen die Freigaben {@code export} und
 * {@code content} für den Space; ein Export ist nur für den Client sichtbar, der ihn gestartet hat. Ein fremder oder
 * unbekannter Export antwortet gleich ({@code job-not-found}).
 */
@RestController
@Tag(name = "Exporte", description = "Asynchrone Exporte eines Space aus dem Cache: Seiten-Array, Rohdaten, ID-Baum "
        + "und Manifest")
public class ExportsController {

    static final String EXPORTS = ApiPaths.V1 + "/exports";

    private final Exports exports;
    private final ExportConversions conversions;
    private final Jobs jobs;
    private final AccessGuard access;

    public ExportsController(Exports exports, ExportConversions conversions, Jobs jobs, AccessGuard access) {
        this.exports = exports;
        this.conversions = conversions;
        this.jobs = jobs;
        this.access = access;
    }

    /** Eigentümer eines Auftrags ({@link AccessContext#jobOwner()}); Clients und Endnutzer fallen nie zusammen. */
    static String owner(AccessContext caller) {
        return caller.jobOwner();
    }

    @PostMapping(path = ApiPaths.V1 + "/sources/{sourceId}/spaces/{spaceKey}/exports",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Export eines Space starten",
            description = "Antwortet sofort mit 202 und dem Auftrag; Fortschritt unter Location. Liegt der Space "
                    + "noch nicht im Cache (oder mit refresh=true immer), liest der Auftrag ihn zuerst selbst von der "
                    + "Quelle ein. Das Artefakt ist nur vollständig (complete=true), wenn die Generation bestätigt "
                    + "ist, keine Seite fehlt und der Aufrufer jede Seite lesen darf. "
                    + "Mit format entstehen zusätzlich abgeleitete Dateien aus denselben Seiten (json: pages.json); "
                    + "ihre Herkunft steht im Manifest unter conversions. Optional nennt der Rumpf eine Startseite "
                    + "({\"rootPageId\": \"…\"}): dann enthält der Export nur sie und ihre Unterseiten; gibt es sie im "
                    + "Space nicht oder ist sie nicht lesbar, scheitert der Auftrag (ROOT_NOT_FOUND). Ohne Rumpf der "
                    + "ganze Space.")
    @ApiResponse(responseCode = "202", description = "Export angenommen")
    @ApiResponse(responseCode = "400", description = "Unbekanntes Format (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Keine Freigabe export und content für diesen Space "
            + "(access-denied)", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "507", description = "Exportablage voll (export-storage-full)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Zu viele offene Aufträge (too-many-jobs)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ExportResponse> start(
            @Parameter(hidden = true) AccessContext caller,
            @Parameter(description = "Quellen-ID aus der Konfiguration", example = "confluence")
            @PathVariable String sourceId,
            @Parameter(description = "Space-Key", example = "DEV") @PathVariable String spaceKey,
            @Parameter(description = "Formate neben dem Raw-First-Artefakt, wiederholbar; ohne Angabe json "
                    + "(Standardformat)", example = "json")
            @RequestParam(name = "format", required = false) List<String> formats,
            @Parameter(description = "true: vor dem Export gegen die Quelle abgleichen; sonst eine bestätigte "
                    + "Generation wiederverwenden und nur einlesen, wenn keine vorliegt")
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Optional: Startseite eines "
                    + "Teilbaum-Exports; ohne Rumpf oder ohne rootPageId der ganze Space", required = false)
            @RequestBody(required = false) ExportStartRequest body) {
        requireExport(caller, sourceId, spaceKey);
        String rootPageId = body == null || body.rootPageId() == null || body.rootPageId().isBlank()
                ? null : body.rootPageId();
        List<String> requested = formats == null || formats.isEmpty() ? List.of(Exports.DEFAULT_FORMAT) : formats;
        JobStatus job = exports.start(ExportRequest.of(sourceId, spaceKey, owner(caller), requested)
                .withRefresh(refresh)
                .withVisible(header -> access.canRead(caller, header.key().sourceId(),
                        header.metadata().partitionKey(), header.key().pageId()))
                .withAuthorized(() -> permitsExport(caller, sourceId, spaceKey))
                .withRoot(rootPageId));
        return ResponseEntity.accepted().location(URI.create(EXPORTS + "/" + job.id()))
                .body(ExportResponse.of(job, null));
    }

    @GetMapping(path = EXPORTS + "/{exportId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Zustand, Fortschritt und Fehler eines Exports; nach Abschluss die Artefaktdateien")
    @ApiResponse(responseCode = "200", description = "Zustand des Exports")
    @ApiResponse(responseCode = "404", description = "Kein eigener Export mit dieser ID (job-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ExportResponse status(@Parameter(hidden = true) AccessContext caller,
                                 @Parameter(description = "Export-ID aus der Antwort des Starts")
                                 @PathVariable String exportId) {
        JobStatus job = ownExport(caller, exportId);
        ExportArtifact artifact = job.state() == JobState.COMPLETED
                ? exports.artifact(exportId, owner(caller)).orElse(null) : null;
        return ExportResponse.of(job, artifact);
    }

    @GetMapping(path = EXPORTS + "/{exportId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Diagnoseereignisse eines Exports",
            description = "Phasen, Meldungen und beim Scheitern Komponente, Fehlercode, HTTP-Status, Proxy-Route, "
                    + "TLS-Befund und die maskierte Ausnahmekette, in ihrer Reihenfolge. Nennt nur, was der "
                    + "Auftraggeber lesen darf.")
    @ApiResponse(responseCode = "200", description = "Ereignisse des Exports")
    @ApiResponse(responseCode = "404", description = "Kein eigener Export mit dieser ID (job-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public List<JobEventResponse> events(@Parameter(hidden = true) AccessContext caller,
                                         @Parameter(description = "Export-ID aus der Antwort des Starts")
                                         @PathVariable String exportId) {
        ownExport(caller, exportId);
        return JobEventResponse.of(jobs.events(exportId, owner(caller)));
    }

    @PostMapping(path = EXPORTS + "/{exportId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Export abbrechen",
            description = "Kooperativ: ein wartender Export endet sofort, ein laufender beim nächsten Prüfpunkt "
                    + "(cancelRequested=true bis dahin). Ein abgebrochener Export veröffentlicht nichts.")
    @ApiResponse(responseCode = "202", description = "Abbruch angenommen; Zustand danach")
    @ApiResponse(responseCode = "404", description = "Kein eigener Export mit dieser ID (job-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ExportResponse> cancel(@Parameter(hidden = true) AccessContext caller,
                                                 @PathVariable String exportId) {
        ownExport(caller, exportId);
        return ResponseEntity.accepted().body(ExportResponse.of(jobs.cancel(exportId, owner(caller)), null));
    }

    @DeleteMapping(path = EXPORTS + "/{exportId}")
    @Operation(summary = "Beendeten Export löschen",
            description = "Entfernt den Export samt Diagnoseereignissen; sein Artefakt im Exportverzeichnis "
                    + "verschwindet beim nächsten Aufräumen. Wartende und laufende Exporte erst abbrechen.")
    @ApiResponse(responseCode = "204", description = "Gelöscht")
    @ApiResponse(responseCode = "404", description = "Kein eigener Export mit dieser ID (job-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Der Export wartet oder läuft noch (job-running)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> delete(@Parameter(hidden = true) AccessContext caller,
                                       @PathVariable String exportId) {
        ownExport(caller, exportId);
        jobs.delete(exportId, owner(caller));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(path = EXPORTS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Alle eigenen beendeten Exporte löschen",
            description = "Abgeschlossene, gescheiterte und abgebrochene Exporte samt Ereignissen; wartende und "
                    + "laufende bleiben.")
    @ApiResponse(responseCode = "200", description = "Anzahl gelöschter Exporte")
    public DeletedResponse deleteFinished(@Parameter(hidden = true) AccessContext caller) {
        if (caller == null) {
            return new DeletedResponse(0);
        }
        return new DeletedResponse(jobs.deleteFinished(owner(caller), JobType.EXPORT));
    }

    @DeleteMapping(path = EXPORTS + "/{exportId}/events/{sequence}")
    @Operation(summary = "Ein Diagnoseereignis eines beendeten Exports löschen",
            description = "Die übrigen Ereignisse behalten ihre Nummern; keine Nummer wird wieder vergeben.")
    @ApiResponse(responseCode = "204", description = "Gelöscht")
    @ApiResponse(responseCode = "404", description = "Kein eigener Export mit dieser ID (job-not-found) oder kein "
            + "Ereignis mit dieser Nummer (event-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Der Export wartet oder läuft noch (job-running)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<Void> deleteEvent(@Parameter(hidden = true) AccessContext caller,
                                            @PathVariable String exportId, @PathVariable String sequence) {
        ownExport(caller, exportId);
        if (!jobs.deleteEvent(exportId, EventNotFoundException.sequence(sequence), owner(caller))) {
            throw new EventNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(path = EXPORTS + "/{exportId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Diagnoseereignisse eines beendeten Exports löschen",
            description = "Ohne before alle Ereignisse des Exports, sonst die vor diesem Zeitpunkt; der Export bleibt.")
    @ApiResponse(responseCode = "200", description = "Anzahl gelöschter Ereignisse")
    @ApiResponse(responseCode = "404", description = "Kein eigener Export mit dieser ID (job-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Der Export wartet oder läuft noch (job-running)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public DeletedResponse deleteEvents(@Parameter(hidden = true) AccessContext caller,
                                        @PathVariable String exportId,
                                        @Parameter(description = "ISO-8601-Zeitpunkt, z. B. 2026-10-17T12:00:00Z")
                                        @RequestParam(name = "before", required = false) Instant before) {
        ownExport(caller, exportId);
        return new DeletedResponse(jobs.deleteEventsBefore(exportId, before == null ? Instant.MAX : before,
                owner(caller)));
    }

    @GetMapping(path = EXPORTS + "/{exportId}/artifact/**")
    @Operation(summary = "Eine Datei des fertigen Artefakts herunterladen",
            description = "Dateinamen aus files im Zustand (pages.ndjson, tree.json, manifest.json, konvertierte "
                    + "Dateien wie pages.json) oder raw/<Datei> aus dem Seiten-Array; Rohdaten Byte für Byte.")
    @ApiResponse(responseCode = "200", description = "Dateiinhalt")
    @ApiResponse(responseCode = "404", description = "Kein eigener Export, noch kein Artefakt oder keine solche "
            + "Datei (job-not-found, artifact-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<InputStreamResource> download(@Parameter(hidden = true) AccessContext caller,
                                                        @PathVariable String exportId, HttpServletRequest request) {
        JobStatus job = ownExport(caller, exportId);
        requirePagesReadable(caller, job);
        String prefix = EXPORTS + "/" + exportId + "/artifact/";
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String name = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
        InputStream content = exports.artifactFile(exportId, owner(caller), name)
                .orElseThrow(() -> new ArtifactNotFoundException(exportId));
        MediaType type = mediaType(name);
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                // Konvertierte Dateien (Markdown, PDF, Word) enthalten Seiteninhalt: im Browser nie ausführbar.
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .body(new InputStreamResource(content));
    }

    @GetMapping(path = EXPORTS + "/{exportId}/dataset", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Datenbestand eines abgeschlossenen Exports",
            description = "Seitenzahl, Space, Vollständigkeit, Stand der Bilder und die Formate, in die sich der "
                    + "Datenbestand ohne neuen Abruf der Quelle konvertieren lässt.")
    @ApiResponse(responseCode = "200", description = "Datenbestand vorhanden")
    @ApiResponse(responseCode = "404", description = "Kein eigener Export oder (noch) kein Datenbestand "
            + "(job-not-found, artifact-not-found)", content = @Content(mediaType =
            MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    public ExportDatasetInfo dataset(@Parameter(hidden = true) AccessContext caller, @PathVariable String exportId) {
        JobStatus job = ownExport(caller, exportId);
        return conversions.dataset(job.id(), owner(caller)).orElseThrow(() -> new ArtifactNotFoundException(exportId));
    }

    @GetMapping(path = EXPORTS + "/{exportId}/conversions/{format}")
    @Operation(summary = "Datenbestand eines Exports in ein Format konvertieren und herunterladen",
            description = "Einmal beschaffen, beliebig oft konvertieren: erzeugt die Datei aus dem gespeicherten "
                    + "Datenbestand (Seiten, Rohdaten, Baum), ohne die Quelle erneut zu lesen und ohne neuen Export. "
                    + "Formate wie beim Export (json, markdown, docx, pdf). Hinweise der Converter, etwa fehlende Bilder "
                    + "oder Diagramme, in X-Conversion-Notices (Zahl) und X-Conversion-Notice-Kinds (je Art).")
    @ApiResponse(responseCode = "200", description = "Die konvertierte Datei")
    @ApiResponse(responseCode = "400", description = "Unbekanntes Format (invalid-request)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Kein eigener Export (job-not-found, artifact-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Export nicht abgeschlossen oder Datenbestand beschädigt "
            + "(export-not-convertible, export-dataset-damaged)", content = @Content(mediaType =
            MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<InputStreamResource> convert(@Parameter(hidden = true) AccessContext caller,
                                                       @PathVariable String exportId,
                                                       @Parameter(description = "Zielformat", example = "docx")
                                                       @PathVariable String format) throws IOException {
        JobStatus job = ownExport(caller, exportId);
        requirePagesReadable(caller, job);
        // Erst ganz erzeugen, dann senden: ein Fehler mitten in der Konvertierung wird so eine Fehlerantwort.
        Path directory = Files.createTempDirectory("space-harvester-conversion-");
        ExportConversions.ConvertedFile file;
        try {
            file = conversions.convert(exportId, owner(caller), format, directory);
        } catch (RuntimeException e) {
            deleteQuietly(directory, null);
            throw e;
        }
        InputStream content = new FilterInputStream(Files.newInputStream(file.path())) {
            @Override
            public void close() throws IOException {
                super.close();
                deleteQuietly(directory, file.path());
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
                .contentLength(file.bytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.path().getFileName() + "\"")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header(NOTICES_HEADER, Integer.toString(file.noticeCount()))
                .header(NOTICE_KINDS_HEADER, noticeKinds(file.notices()))
                .body(new InputStreamResource(content));
    }

    /** Zahl der Hinweise, etwa fehlender Bilder; die Stellen nennt das Dokument selbst. */
    static final String NOTICES_HEADER = "X-Conversion-Notices";
    /** Hinweise je Art, etwa {@code missing-image=2, missing-diagram=1}; leer ohne Hinweise. */
    static final String NOTICE_KINDS_HEADER = "X-Conversion-Notice-Kinds";

    private static String noticeKinds(List<ConversionNotice> notices) {
        Map<ConversionNotice.Kind, Long> kinds = new EnumMap<>(ConversionNotice.Kind.class);
        notices.forEach(notice -> kinds.merge(notice.kind(), 1L, Long::sum));
        return kinds.entrySet().stream().map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT).replace('_', '-')
                + "=" + entry.getValue()).collect(Collectors.joining(", "));
    }

    private static void deleteQuietly(Path directory, Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            // Temporäre Datei; das Betriebssystem räumt den Ordner auf.
        }
    }

    private static MediaType mediaType(String name) {
        String extension = name.substring(name.lastIndexOf('.') + 1);
        return switch (extension) {
            case "ndjson" -> MediaType.parseMediaType("application/x-ndjson");
            case "json" -> MediaType.APPLICATION_JSON;
            case "md" -> MediaType.parseMediaType("text/markdown;charset=UTF-8");
            case "docx" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "pdf" -> MediaType.APPLICATION_PDF;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * Ein delegierter Nutzer bekommt das Artefakt nur, solange er jede enthaltene Seite noch lesen darf; sonst gilt es
     * als nicht vorhanden, ohne zu sagen, welche Seite. Die Rechteentscheidungen hält die Richtlinie {@code decision-ttl}
     * lang. Technische Clients sind durch ihre Korpusfreigabe auf Space-Ebene begrenzt.
     */
    private void requirePagesReadable(AccessContext caller, JobStatus job) {
        if (caller.kind() != PrincipalKind.END_USER) {
            return;
        }
        List<String> pageIds = exports.pageIds(job.id(), owner(caller))
                .orElseThrow(() -> new ArtifactNotFoundException(job.id()));
        for (String pageId : pageIds) {
            if (!access.canRead(caller, job.sourceId(), job.partitionKey(), pageId)) {
                throw new ArtifactNotFoundException(job.id());
            }
        }
    }

    /** Eigener Export, für dessen Space der Aufrufer noch export und content hat. */
    private JobStatus ownExport(AccessContext caller, String exportId) {
        // Erst die Freigabe des Aufrufers überhaupt, dann der Auftrag: ohne Kontext nie eine Job-Abfrage.
        if (caller == null) {
            throw new UnknownJobException(exportId);
        }
        JobStatus job = jobs.status(exportId, owner(caller));
        if (job.type() != JobType.EXPORT) {
            throw new UnknownJobException(exportId);
        }
        requireExport(caller, job.sourceId(), job.partitionKey());
        return job;
    }

    /**
     * Wie {@link #requireExport}, aber ohne Ausnahme, für die Prüfungen im laufenden Auftrag. Der Aufrufer (bei
     * delegierten Benutzern samt Token) lebt nur im Speicher dieses Auftrags und wird nie abgelegt.
     */
    private boolean permitsExport(AccessContext caller, String sourceId, String spaceKey) {
        ResourceScope space = ResourceScope.space(sourceId, spaceKey);
        return access.permits(caller, space, AccessKind.EXPORT) && access.permits(caller, space, AccessKind.CONTENT);
    }

    private void requireExport(AccessContext caller, String sourceId, String spaceKey) {
        ResourceScope space = ResourceScope.space(sourceId, spaceKey);
        access.require(caller, space, AccessKind.EXPORT);
        // Das Artefakt enthält die nativen Seiteninhalte.
        access.require(caller, space, AccessKind.CONTENT);
    }

    /** Für diesen Export gibt es (noch) kein Artefakt oder keine Datei dieses Namens. */
    static class ArtifactNotFoundException extends RuntimeException {

        ArtifactNotFoundException(String exportId) {
            super("Kein Artefakt für Export " + exportId);
        }
    }

    /**
     * Optionaler Rumpf beim Start eines Exports.
     *
     * @param rootPageId Startseite: nur sie und ihre Unterseiten; {@code null} oder fehlend für den ganzen Space
     */
    public record ExportStartRequest(String rootPageId) {
    }

    /**
     * Zustand eines Exports.
     *
     * @param id              Export-ID
     * @param state           {@code queued}, {@code running}, {@code completed}, {@code failed}, {@code cancelled};
     *                        {@code completed} heißt nur „durchgelaufen“, Vollständigkeit steht in
     *                        {@code artifact.complete}
     * @param cancelRequested Abbruch angefordert, aber noch nicht wirksam
     * @param error           nur bei {@code failed}
     * @param artifact        nur bei {@code completed}
     * @param rootPageId      Startseite eines Teilbaum-Exports, {@code null} für den ganzen Space
     * @param rootTitle       Titel der Startseite, sobald der Export sie gelesen hat, sonst {@code null}
     */
    public record ExportResponse(String id, String sourceId, String spaceKey, String state, JobProgress progress,
                                 boolean cancelRequested, ErrorResponse error, Instant createdAt, Instant startedAt,
                                 Instant finishedAt, ArtifactResponse artifact, String rootPageId, String rootTitle) {

        static ExportResponse of(JobStatus job, ExportArtifact artifact) {
            return new ExportResponse(job.id(), job.sourceId(), job.partitionKey(),
                    job.state().name().toLowerCase(Locale.ROOT), job.progress(), job.cancelRequested(),
                    ErrorResponse.of(job.error()), job.createdAt(), job.startedAt(), job.finishedAt(),
                    ArtifactResponse.of(job.id(), artifact), job.rootPageId(), job.rootTitle());
        }
    }

    public record ErrorResponse(String code, String message) {

        static ErrorResponse of(JobError error) {
            return error == null ? null : new ErrorResponse(error.code(), error.message());
        }
    }

    /**
     * @param complete aus bestätigter Cache-Generation ohne fehlende Seiten
     * @param files    Dateien mit Download-Adresse; die Rohdaten je Seite nennt {@code pages.ndjson}
     */
    public record ArtifactResponse(boolean complete, List<FileResponse> files) {

        static ArtifactResponse of(String exportId, ExportArtifact artifact) {
            if (artifact == null) {
                return null;
            }
            return new ArtifactResponse(artifact.complete(), Stream.concat(
                            artifact.files().stream()
                                    .map(file -> new FileResponse(file.name(), file.bytes(), file.sha256(),
                                            href(exportId, file.name()))),
                            Stream.of(new FileResponse("manifest.json", null, null,
                                    href(exportId, "manifest.json"))))
                    .toList());
        }

        private static String href(String exportId, String name) {
            return EXPORTS + "/" + exportId + "/artifact/" + name;
        }
    }

    /** @param bytes und sha256 fehlen beim Manifest, das sie für die übrigen Dateien enthält */
    public record FileResponse(String name, Long bytes, String sha256, String href) {
    }
}
