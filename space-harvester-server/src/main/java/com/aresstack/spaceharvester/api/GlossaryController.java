package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.glossary.Glossary;
import com.aresstack.spaceharvester.application.glossary.GlossaryClassification;
import com.aresstack.spaceharvester.application.glossary.GlossaryEntry;
import com.aresstack.spaceharvester.application.glossary.GlossaryStore;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fachglossar (#122 Stufe 2, ADR 0019): Fachbegriffe mit Herkunft (manual, ai) und Prüfstatus (proposed, confirmed,
 * rejected), Pflege von Hand und „Fachbegriffe ermitteln“ als KI-Lauf nur auf Knopfdruck. Nur Adapter von
 * {@link Glossary} und {@link GlossaryClassification}. Lesen braucht metadata auf den Geltungsbereich, Ändern und
 * Starten sync, global auf jede Quelle.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/glossary", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Begriffe", description = "Begriffsindex: Akronyme, Bezeichner und ihre Fundstellen über alle Quellen")
public class GlossaryController {

    private final Glossary glossary;
    private final GlossaryClassification classification;
    private final Jobs jobs;

    public GlossaryController(Glossary glossary, GlossaryClassification classification, Jobs jobs) {
        this.glossary = glossary;
        this.classification = classification;
        this.jobs = jobs;
    }

    @GetMapping
    @Operation(summary = "Glossareinträge, nach Suchwert sortiert",
            description = "Ohne status Vorschläge und bestätigte, ohne abgelehnte; status=rejected zeigt die "
                    + "Sperrliste. q ist ein Präfix des Begriffs oder eines seiner Wörter, startsWith nur des ganzen "
                    + "Begriffs. Mit sourceId globale und für diese Quelle geltende, mit spaceKey dazu nur, was für "
                    + "den Space gilt. Belege fehlen, wenn der Aufrufer die Seite nicht lesen darf.")
    @ApiResponse(responseCode = "200", description = "GlossaryListResponse")
    @ApiResponse(responseCode = "400", description = "Unbekannte status/origin, limit außerhalb 1 bis "
            + Glossary.MAX_LIMIT + " oder beschädigter cursor (invalid-request)")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)")
    public GlossaryListResponse entries(@Parameter(hidden = true) AccessContext caller,
                                        @RequestParam(required = false) String q,
                                        @Parameter(description = "proposed, confirmed oder rejected")
                                        @RequestParam(required = false) String status,
                                        @Parameter(description = "manual oder ai")
                                        @RequestParam(required = false) String origin,
                                        @RequestParam(required = false) String sourceId,
                                        @RequestParam(required = false) String spaceKey,
                                        @RequestParam(required = false) String startsWith,
                                        @RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(required = false) String cursor) {
        Glossary.Page page = glossary.entries(caller, new Glossary.Search(blankToNull(q),
                parse(status, value -> ReviewStatus.valueOf(value.toUpperCase(Locale.ROOT)), "status"),
                parse(origin, value -> RelationOrigin.valueOf(value.toUpperCase(Locale.ROOT)), "origin"),
                blankToNull(sourceId), blankToNull(spaceKey), blankToNull(startsWith), limit, blankToNull(cursor)));
        return new GlossaryListResponse(page.entries().stream().map(Entry::of).toList(), page.nextCursor(),
                classification.available());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Ein Glossareintrag")
    @ApiResponse(responseCode = "200", description = "Entry")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verborgen (glossary-entry-not-found)")
    public Entry entry(@Parameter(hidden = true) AccessContext caller, @PathVariable String id) {
        return Entry.of(glossary.entry(caller, id));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Änderungsprotokoll eines Eintrags, älteste zuerst")
    @ApiResponse(responseCode = "200", description = "Liste von AuditEntryResponse")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verborgen (glossary-entry-not-found)")
    public List<AuditEntryResponse> history(@Parameter(hidden = true) AccessContext caller,
                                            @PathVariable String id) {
        return glossary.history(caller, id).stream().map(AuditEntryResponse::of).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Fachbegriff manuell anlegen (manual, confirmed)",
            description = "Auch für Begriffe, die der Begriffsindex nicht kennt. Gibt es den Begriff im "
                    + "Geltungsbereich schon, wird er bestätigt; seine Herkunft bleibt. Ohne sourceId global.")
    @ApiResponse(responseCode = "201", description = "Entry")
    @ApiResponse(responseCode = "400", description = "Leerer oder zu langer Begriff oder Erläuterung (invalid-request)")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe auf den Geltungsbereich (access-denied)")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)")
    public ResponseEntity<Entry> add(@Parameter(hidden = true) AccessContext caller,
                                     @RequestBody NewEntryRequest request) {
        List<RelationEvidence> evidence = request.evidence() == null ? List.of() : request.evidence().stream()
                .map(item -> new RelationEvidence(item.sourceId(), item.pageId(), item.start(), item.end(),
                        item.inputSha256())).toList();
        GlossaryEntry created = glossary.add(caller, new Glossary.NewEntry(request.term(),
                new RelationScope(blankToNull(request.sourceId()), blankToNull(request.spaceKey())),
                request.explanation(), evidence));
        return ResponseEntity.status(HttpStatus.CREATED).body(Entry.of(created));
    }

    @PutMapping(path = "/{id}/explanation", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Erläuterung ändern oder entfernen", description = "Begriff und Geltungsbereich bleiben.")
    @ApiResponse(responseCode = "200", description = "Entry")
    @ApiResponse(responseCode = "409", description = "revision ist veraltet (glossary-conflict)")
    public Entry explain(@Parameter(hidden = true) AccessContext caller, @PathVariable String id,
                         @RequestBody ExplanationRequest request) {
        return Entry.of(glossary.explain(caller, id, request.explanation(), request.revision()));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Bestätigen; Herkunft bleibt")
    @ApiResponse(responseCode = "200", description = "Entry")
    @ApiResponse(responseCode = "409", description = "revision ist veraltet (glossary-conflict)")
    public Entry confirm(@Parameter(hidden = true) AccessContext caller, @PathVariable String id,
                         @RequestParam(required = false) Long revision) {
        return Entry.of(glossary.confirm(caller, id, revision));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Dauerhaft ablehnen", description = "Kein Lauf schlägt den Begriff hier wieder vor.")
    @ApiResponse(responseCode = "200", description = "Entry")
    @ApiResponse(responseCode = "409", description = "revision ist veraltet (glossary-conflict)")
    public Entry reject(@Parameter(hidden = true) AccessContext caller, @PathVariable String id,
                        @RequestParam(required = false) Long revision) {
        return Entry.of(glossary.reject(caller, id, revision));
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Ablehnung aufheben", description = "KI-Einträge werden wieder Vorschlag, manuelle bestätigt.")
    @ApiResponse(responseCode = "200", description = "Entry")
    @ApiResponse(responseCode = "409", description = "revision ist veraltet (glossary-conflict)")
    public Entry restore(@Parameter(hidden = true) AccessContext caller, @PathVariable String id,
                         @RequestParam(required = false) Long revision) {
        return Entry.of(glossary.restore(caller, id, revision));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Entfernen", description = "Manuelle Einträge ganz, KI-Einträge als Ablehnung.")
    @ApiResponse(responseCode = "200", description = "Entry, der verbleibende abgelehnte Eintrag")
    @ApiResponse(responseCode = "204", description = "Ganz entfernt")
    public ResponseEntity<Entry> remove(@Parameter(hidden = true) AccessContext caller, @PathVariable String id,
                                        @RequestParam(required = false) Long revision) {
        return glossary.remove(caller, id, revision).map(entry -> ResponseEntity.ok(Entry.of(entry)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/classify")
    @Operation(summary = "Fachbegriffe ermitteln (KI-Lauf auf Knopfdruck)",
            description = "Asynchron und abbrechbar, je Quelle ein Auftrag ENRICHMENT. Ohne sourceId alle für den "
                    + "KI-Dienst freigegebenen Quellen mit sync-Freigabe, mit spaceKey nur dieser Space. Arbeitet auf "
                    + "dem vorhandenen Begriffsindex, lädt keine Seiten nach. Ergebnis sind nur Vorschläge (ai, "
                    + "proposed); manuelle, bestätigte und abgelehnte Einträge bleiben. Scheitert ein Auftrag, bleibt "
                    + "der Stand des letzten Laufs.")
    @ApiResponse(responseCode = "202", description = "Liste von ClassificationJobResponse")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe auf die Quelle (access-denied)")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)")
    @ApiResponse(responseCode = "409", description = "Keine Quelle im Umfang für den KI-Dienst freigegeben "
            + "(external-processing-not-allowed)")
    @ApiResponse(responseCode = "503", description = "Kein Chat-Modell eingestellt (ai-unavailable)")
    public ResponseEntity<List<ClassificationJobResponse>> classify(@Parameter(hidden = true) AccessContext caller,
                                                                    @RequestParam(required = false) String sourceId,
                                                                    @RequestParam(required = false) String spaceKey) {
        return ResponseEntity.accepted().body(classification.start(caller, sourceId, spaceKey).stream()
                .map(ClassificationJobResponse::of).toList());
    }

    @GetMapping("/classify-jobs/{jobId}")
    @Operation(summary = "Stand eines eigenen Laufs „Fachbegriffe ermitteln“",
            description = "discovered = gefragte Kandidaten, processed = beantwortete, written = neue oder geänderte "
                    + "Einträge.")
    @ApiResponse(responseCode = "200", description = "ClassificationJobResponse")
    @ApiResponse(responseCode = "404", description = "Kein eigener Auftrag mit dieser ID (job-not-found)")
    public ClassificationJobResponse job(@Parameter(hidden = true) AccessContext caller, @PathVariable String jobId) {
        return ClassificationJobResponse.of(own(caller, jobId));
    }

    @DeleteMapping("/classify-jobs/{jobId}")
    @Operation(summary = "Lauf abbrechen", description = "Bis dahin wird nichts abgeglichen.")
    @ApiResponse(responseCode = "202", description = "ClassificationJobResponse")
    @ApiResponse(responseCode = "404", description = "Kein eigener Auftrag mit dieser ID (job-not-found)")
    public ResponseEntity<ClassificationJobResponse> cancel(@Parameter(hidden = true) AccessContext caller,
                                                            @PathVariable String jobId) {
        own(caller, jobId);
        return ResponseEntity.accepted().body(ClassificationJobResponse.of(jobs.cancel(jobId, caller.jobOwner())));
    }

    private JobStatus own(AccessContext caller, String jobId) {
        if (!classification.started(jobId)) {
            throw new UnknownJobException(jobId);
        }
        JobStatus job = jobs.status(jobId, caller.jobOwner());
        if (job.type() != JobType.ENRICHMENT) {
            throw new UnknownJobException(jobId);
        }
        return job;
    }

    private static <T> T parse(String value, Function<String, T> parser, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parser.apply(value.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException(name + " ist unbekannt: " + value.trim());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Neuer manueller Fachbegriff; ohne sourceId global. */
    public record NewEntryRequest(String term, String sourceId, String spaceKey, String explanation,
                                  List<TermRelationsController.EvidenceEntry> evidence) {
    }

    /** Neue Erläuterung, leer oder null entfernt sie; revision wie zuletzt gelesen oder null. */
    public record ExplanationRequest(String explanation, Long revision) {
    }

    /** Ein Glossareintrag. */
    public record Entry(String id, String term, String normalized, String sourceId, String spaceKey,
                        @Schema(allowableValues = {"manual", "ai"}) String origin,
                        @Schema(allowableValues = {"proposed", "confirmed", "rejected"}) String status,
                        List<TermRelationsController.EvidenceEntry> evidence, boolean evidenceStale,
                        @Schema(nullable = true) String explanation, @Schema(nullable = true) String generation,
                        String createdBy, Instant createdAt, Instant updatedAt,
                        @Schema(nullable = true) String reviewedBy, @Schema(nullable = true) Instant reviewedAt,
                        long revision) {

        static Entry of(GlossaryEntry entry) {
            return new Entry(entry.id(), entry.term(), entry.normalized(), entry.scope().sourceId(),
                    entry.scope().spaceKey(), entry.origin().name().toLowerCase(Locale.ROOT),
                    entry.status().name().toLowerCase(Locale.ROOT),
                    entry.evidence().stream().map(TermRelationsController.EvidenceEntry::of).toList(),
                    entry.evidenceStale(), entry.explanation(), entry.generation(), entry.createdBy(),
                    entry.createdAt(), entry.updatedAt(), entry.reviewedBy(), entry.reviewedAt(), entry.revision());
        }
    }

    /** Ein Stapel Einträge; weiter mit nextCursor, bis er null ist. aiAvailable: ob ein Chat-Modell eingestellt ist. */
    public record GlossaryListResponse(List<Entry> entries, String nextCursor, boolean aiAvailable) {
    }

    /** Ein Lauf „Fachbegriffe ermitteln“. */
    public record ClassificationJobResponse(String id, String sourceId, @Schema(nullable = true) String spaceKey,
                                            @Schema(allowableValues = {"queued", "running", "completed", "failed",
                                                    "cancelled"}) String state, JobProgress progress,
                                            @Schema(nullable = true) JobError error, boolean cancelRequested,
                                            Instant createdAt, @Schema(nullable = true) Instant startedAt,
                                            @Schema(nullable = true) Instant finishedAt) {

        static ClassificationJobResponse of(JobStatus job) {
            return new ClassificationJobResponse(job.id(), job.sourceId(), job.partitionKey(),
                    job.state().name().toLowerCase(Locale.ROOT), job.progress(), job.error(), job.cancelRequested(),
                    job.createdAt(), job.startedAt(), job.finishedAt());
        }
    }

    /** Ein Eintrag im Änderungsprotokoll. */
    public record AuditEntryResponse(String action, String actor, Instant at, @Schema(nullable = true) String status,
                                     long revision) {

        static AuditEntryResponse of(GlossaryStore.AuditEntry entry) {
            return new AuditEntryResponse(entry.action(), entry.actor(), entry.at(),
                    entry.status() == null ? null : entry.status().name().toLowerCase(Locale.ROOT), entry.revision());
        }
    }
}
