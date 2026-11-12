package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.export.ExportPackageException;
import com.aresstack.spaceharvester.application.export.ExportPackageImport;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Import eines JSON-Pakets ({@code export.zip} ab {@code json-package/3}) in den Cache einer Quelle, ohne die Quelle
 * zu fragen. Schreibt Daten der Quelle, daher wie „Daten bereinigen“ nur für Clients mit
 * {@code manage-configuration}.
 */
@RestController
@RequestMapping(path = ImportController.IMPORTS, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Quellen", description = "Quellen und ihre lokal gespeicherten Daten")
public class ImportController {

    public static final String IMPORTS = SourceDataController.DATA + "/imports";

    /** Größtes Paket, das der Dienst annimmt. */
    static final long MAX_PACKAGE_BYTES = 4L * 1024 * 1024 * 1024;

    private static final String TYPE_BASE = "https://github.com/aresstack/space-harvester/problems/";
    /** Der Dienst läuft ohne Cache-Verzeichnis. */
    static final String UNAVAILABLE = "import-unavailable";

    private final ObjectProvider<ExportPackageImport> imports;
    private final Jobs jobs;

    public ImportController(ObjectProvider<ExportPackageImport> imports, Jobs jobs) {
        this.imports = imports;
        this.jobs = jobs;
    }

    @PostMapping(consumes = {"application/zip", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Operation(summary = "JSON-Paket in den Cache einer Quelle importieren",
            description = "Nimmt ein export.zip (Format json-package ab Version 3, „JSON mit Medien“) als Body an und "
                    + "startet einen Auftrag (Art import). Er übernimmt die Seiten wie ein Abgleich ohne Quelle als "
                    + "neue Generation des Space, legt Bilder, Anhänge, Diagrammgrafiken, Anhangstexte und "
                    + "Lese-Ansichten ab und plant danach Volltext und Anreicherungen ein. Hat der Space schon Daten, "
                    + "ersetzt nur ein Paket des ganzen Space sie und nur mit replace=true. Nur für Clients mit "
                    + "manage-configuration.")
    @ApiResponse(responseCode = "202", description = "ImportJobResponse; Stand unter Location")
    @ApiResponse(responseCode = "400", description = "Kein importierbares Paket (import-not-a-package, "
            + "import-unsupported, import-damaged)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Client darf die Konfiguration nicht verwalten (access-denied)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Paket größer als 4 GiB (import-too-large)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ImportJobResponse> start(@Parameter(hidden = true) AccessContext caller,
                                                   @PathVariable String sourceId,
                                                   @RequestParam(defaultValue = "false") boolean replace,
                                                   @Parameter(hidden = true) InputStream body) throws IOException {
        ExportPackageImport available = available();
        Path file = Files.createTempFile("space-harvester-import-", ".zip");
        try {
            copy(body, file);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        // Die Datei gehört ab hier dem Import; er löscht sie, wenn er endet oder nicht startet.
        JobStatus job = available.start(file, sourceId, replace, caller.jobOwner(), true);
        return ResponseEntity.accepted()
                .location(URI.create(ApiPaths.V1 + "/sources/" + sourceId + "/data/imports/" + job.id()))
                .body(ImportJobResponse.of(job));
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Stand eines Imports",
            description = "discovered = Seiten im Plan, processed = bearbeitet, written = übernommen, failed = "
                    + "gescheitert.")
    @ApiResponse(responseCode = "200", description = "ImportJobResponse")
    @ApiResponse(responseCode = "404", description = "Kein eigener Import dieser Quelle (job-not-found)")
    public ImportJobResponse status(@Parameter(hidden = true) AccessContext caller, @PathVariable String sourceId,
                                    @PathVariable String jobId) {
        JobStatus job = jobs.status(jobId, caller.jobOwner());
        if (job.type() != JobType.IMPORT || !job.sourceId().equals(sourceId)) {
            throw new UnknownJobException(jobId);
        }
        return ImportJobResponse.of(job);
    }

    @ExceptionHandler(ExportPackageException.class)
    ResponseEntity<ProblemDetail> invalid(ExportPackageException e) {
        HttpStatus status = switch (e.code()) {
            case ExportPackageException.TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + e.code()));
        problem.setTitle("Paket nicht importierbar");
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private ExportPackageImport available() {
        ExportPackageImport available = imports.getIfAvailable();
        if (available == null) {
            throw new ExportPackageException(UNAVAILABLE,
                    "Import braucht einen Cache (space-harvester.cache.directory)");
        }
        return available;
    }

    private static void copy(InputStream body, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int n = body.read(buffer); n >= 0; n = body.read(buffer)) {
                total += n;
                if (total > MAX_PACKAGE_BYTES) {
                    throw new ExportPackageException(ExportPackageException.TOO_LARGE,
                            "Das Paket ist größer als 4 GiB");
                }
                out.write(buffer, 0, n);
            }
        }
    }

    /** Ein Importauftrag. */
    public record ImportJobResponse(String id, String sourceId, String spaceKey,
                                    @Schema(allowableValues = {"queued", "running", "completed", "failed",
                                            "cancelled"}) String state, JobProgress progress,
                                    @Schema(nullable = true) JobError error, Instant createdAt,
                                    @Schema(nullable = true) Instant startedAt,
                                    @Schema(nullable = true) Instant finishedAt) {

        static ImportJobResponse of(JobStatus job) {
            return new ImportJobResponse(job.id(), job.sourceId(), job.partitionKey(),
                    job.state().name().toLowerCase(Locale.ROOT), job.progress(), job.error(), job.createdAt(),
                    job.startedAt(), job.finishedAt());
        }
    }
}
