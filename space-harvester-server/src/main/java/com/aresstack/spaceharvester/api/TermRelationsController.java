package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.terms.AcronymRelationRule;
import com.aresstack.spaceharvester.application.terms.AcronymResolution;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelationStore;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Begriffsbeziehungen (#120, ADR 0019 Punkt 5): Abkürzung, Synonym, Schreibvariante, fachlich verwandt, mit Herkunft
 * und Prüfstatus. Nur Adapter von {@link TermRelations}. Lesen braucht metadata auf den Geltungsbereich und
 * content auf mindestens einen Beleg automatischer Vorschläge; Ändern braucht sync, global auf jede Quelle.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/term-relations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Begriffe", description = "Begriffsindex: Akronyme, Bezeichner und ihre Fundstellen über alle Quellen")
public class TermRelationsController {

    private static final String REFRESH_JOBS = ApiPaths.V1 + "/term-relations/refresh-jobs";

    private final TermRelations relations;
    private final AcronymRelationRule acronymRule;
    private final AcronymResolution resolution;
    private final Jobs jobs;

    public TermRelationsController(TermRelations relations, AcronymRelationRule acronymRule,
                                   AcronymResolution resolution, Jobs jobs) {
        this.relations = relations;
        this.acronymRule = acronymRule;
        this.resolution = resolution;
        this.jobs = jobs;
    }

    @GetMapping
    @Operation(summary = "Beziehungen, nach Schlüssel sortiert",
            description = "term findet Beziehungen mit diesem Begriff an einem Ende (Suchwert). Ohne status "
                    + "Vorschläge und bestätigte, ohne abgelehnte; status=rejected zeigt die Sperrliste. Mit sourceId "
                    + "globale und für diese Quelle geltende. Verborgene Beziehungen fehlen ohne Hinweis.")
    @ApiResponse(responseCode = "200", description = "RelationListResponse")
    @ApiResponse(responseCode = "400", description = "Unbekannte type/status/origin, limit außerhalb 1 bis "
            + TermRelations.MAX_LIMIT + " oder beschädigter cursor (invalid-request)")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)")
    public RelationListResponse relations(@Parameter(hidden = true) AccessContext caller,
                                          @RequestParam(required = false) String term,
                                          @Parameter(description = "abbreviation, synonym, variant oder related")
                                          @RequestParam(required = false) String type,
                                          @Parameter(description = "proposed, confirmed oder rejected")
                                          @RequestParam(required = false) String status,
                                          @Parameter(description = "manual, rule oder ai")
                                          @RequestParam(required = false) String origin,
                                          @RequestParam(required = false) String sourceId,
                                          @RequestParam(defaultValue = "50") int limit,
                                          @RequestParam(required = false) String cursor) {
        TermRelations.RelationList list = relations.relations(caller, new TermRelations.RelationSearch(
                blankToNull(term), parse(type, RelationType::of, "type"),
                parse(status, value -> ReviewStatus.valueOf(value.toUpperCase(Locale.ROOT)), "status"),
                parse(origin, value -> RelationOrigin.valueOf(value.toUpperCase(Locale.ROOT)), "origin"),
                blankToNull(sourceId), limit, blankToNull(cursor)));
        return new RelationListResponse(list.relations().stream().map(RelationEntry::of).toList(),
                list.nextCursor());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Eine Beziehung")
    @ApiResponse(responseCode = "200", description = "RelationEntry")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verborgen (relation-not-found)")
    public RelationEntry relation(@Parameter(hidden = true) AccessContext caller, @PathVariable String id) {
        return RelationEntry.of(relations.relation(caller, id));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Änderungsprotokoll einer Beziehung, älteste zuerst")
    @ApiResponse(responseCode = "200", description = "Liste von AuditEntryResponse")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verborgen (relation-not-found)")
    public List<AuditEntryResponse> history(@Parameter(hidden = true) AccessContext caller,
                                            @PathVariable String id) {
        return relations.history(caller, id).stream().map(AuditEntryResponse::of).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Beziehung manuell anlegen (manual, confirmed)",
            description = "Gibt es denselben Schlüssel (Endpunkte, Art, Geltungsbereich) schon, wird er bestätigt; "
                    + "seine Herkunft bleibt. Ohne sourceId global.")
    @ApiResponse(responseCode = "201", description = "RelationEntry")
    @ApiResponse(responseCode = "400", description = "Fehlende oder gleiche Begriffe, unbekannte type (invalid-request)")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe auf den Geltungsbereich (access-denied)")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)")
    public ResponseEntity<RelationEntry> add(@Parameter(hidden = true) AccessContext caller,
                                             @RequestBody NewRelationRequest request) {
        List<RelationEvidence> evidence = request.evidence() == null ? List.of() : request.evidence().stream()
                .map(item -> new RelationEvidence(item.sourceId(), item.pageId(), item.start(), item.end(),
                        item.inputSha256())).toList();
        TermRelation created = relations.add(caller, new TermRelations.NewRelation(request.fromTerm(),
                request.toTerm(), parse(request.type(), RelationType::of, "type"),
                new RelationScope(blankToNull(request.sourceId()), blankToNull(request.spaceKey())), request.note(),
                evidence));
        return ResponseEntity.status(HttpStatus.CREATED).body(RelationEntry.of(created));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Bestätigen; Herkunft bleibt")
    @ApiResponse(responseCode = "200", description = "RelationEntry")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe (access-denied)")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verborgen (relation-not-found)")
    @ApiResponse(responseCode = "409", description = "revision ist veraltet (relation-conflict)")
    public RelationEntry confirm(@Parameter(hidden = true) AccessContext caller, @PathVariable String id,
                                 @Parameter(description = "zuletzt gesehene Revision")
                                 @RequestParam(required = false) Long revision) {
        return RelationEntry.of(relations.confirm(caller, id, revision));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Dauerhaft ablehnen (Sperrliste); Vorschlagsläufe bringen sie nicht zurück")
    @ApiResponse(responseCode = "200", description = "RelationEntry")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe (access-denied)")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verborgen (relation-not-found)")
    @ApiResponse(responseCode = "409", description = "revision ist veraltet (relation-conflict)")
    public RelationEntry reject(@Parameter(hidden = true) AccessContext caller, @PathVariable String id,
                                @RequestParam(required = false) Long revision) {
        return RelationEntry.of(relations.reject(caller, id, revision));
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Ablehnung aufheben: automatisch wieder Vorschlag, manuell wieder bestätigt")
    @ApiResponse(responseCode = "200", description = "RelationEntry")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe (access-denied)")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verborgen (relation-not-found)")
    @ApiResponse(responseCode = "409", description = "revision ist veraltet (relation-conflict)")
    public RelationEntry restore(@Parameter(hidden = true) AccessContext caller, @PathVariable String id,
                                 @RequestParam(required = false) Long revision) {
        return RelationEntry.of(relations.restore(caller, id, revision));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Entfernen: manuelle ganz (204), automatische als Ablehnung (200)")
    @ApiResponse(responseCode = "200", description = "RelationEntry der verbleibenden Ablehnung")
    @ApiResponse(responseCode = "204", description = "Entfernt; das Protokoll bleibt")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe (access-denied)")
    @ApiResponse(responseCode = "404", description = "Unbekannt oder verborgen (relation-not-found)")
    @ApiResponse(responseCode = "409", description = "revision ist veraltet (relation-conflict)")
    public ResponseEntity<RelationEntry> remove(@Parameter(hidden = true) AccessContext caller,
                                                @PathVariable String id,
                                                @RequestParam(required = false) Long revision) {
        return relations.remove(caller, id, revision).map(left -> ResponseEntity.ok(RelationEntry.of(left)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/rules/acronyms")
    @Operation(summary = "Regelvorschläge aus Akronym-Ausschreibungen einer Quelle abgleichen",
            description = "Aus jeder Ausschreibung, die der Begriffsindex direkt neben einem Akronym fand, wird eine "
                    + "vorgeschlagene Abkürzung (rule, proposed) für diese Quelle. Ohne KI, nur aus dem Index; "
                    + "abgelehnte bleiben gesperrt, bestätigte unberührt, nicht mehr belegte Vorschläge entfallen.")
    @ApiResponse(responseCode = "200", description = "ProposalRunResponse")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe auf die Quelle (access-denied)")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)")
    public ProposalRunResponse acronymRule(@Parameter(hidden = true) AccessContext caller,
                                           @RequestParam String sourceId) {
        TermRelations.ProposalRun run = acronymRule.run(caller, sourceId.trim());
        return new ProposalRunResponse(run.added(), run.updated(), run.withdrawn(), run.suppressed(), run.stale());
    }

    @PostMapping("/refresh")
    @Operation(summary = "KI-Akronymauflösung einer Quelle starten (#122)",
            description = "Asynchroner Vorschlagslauf: Je Akronym gehen wenige kurze Fundstellen aus dem Cache an das "
                    + "Chat-Modell; jede Ausschreibung muss in einer Fundstelle wörtlich stehen. Ergebnis sind nur "
                    + "Vorschläge (ai, proposed); bestätigte, manuelle und Regelbeziehungen bleiben, abgelehnte kommen "
                    + "nie wieder. Läuft als Auftrag ENRICHMENT der Quelle ohne Space; scheitert er, bleibt der Stand "
                    + "des letzten Laufs. Nur für Quellen in external-processing-sources.")
    @ApiResponse(responseCode = "202", description = "RefreshJobResponse; Location nennt den Auftrag")
    @ApiResponse(responseCode = "400", description = "kind ist nicht acronym (invalid-request)")
    @ApiResponse(responseCode = "403", description = "Keine sync-Freigabe auf die Quelle (access-denied)")
    @ApiResponse(responseCode = "404", description = "Unbekannte Quelle (source-not-found)")
    @ApiResponse(responseCode = "409", description = "Quelle nicht für den KI-Dienst freigegeben "
            + "(external-processing-not-allowed)")
    @ApiResponse(responseCode = "503", description = "Kein Chat-Modell eingestellt (ai-unavailable)")
    public ResponseEntity<RefreshJobResponse> refresh(@Parameter(hidden = true) AccessContext caller,
                                                      @RequestParam String sourceId,
                                                      @RequestBody(required = false) RefreshRequest request) {
        String kind = request == null ? null : blankToNull(request.kind());
        if (kind != null && !"acronym".equals(kind)) {
            throw new InvalidQueryException("kind muss acronym sein");
        }
        JobStatus job = resolution.start(caller, sourceId.trim());
        return ResponseEntity.accepted().location(URI.create(REFRESH_JOBS + "/" + job.id()))
                .body(RefreshJobResponse.of(job));
    }

    @GetMapping("/refresh-jobs/{jobId}")
    @Operation(summary = "Stand eines eigenen Laufs der KI-Akronymauflösung",
            description = "Zustand, Fortschritt (discovered = gefragte Akronyme, processed = beantwortete, written = "
                    + "neue oder geänderte Vorschläge) und Fehler.")
    @ApiResponse(responseCode = "200", description = "RefreshJobResponse")
    @ApiResponse(responseCode = "404", description = "Kein eigener Auftrag mit dieser ID (job-not-found)")
    public RefreshJobResponse refreshJob(@Parameter(hidden = true) AccessContext caller,
                                         @PathVariable String jobId) {
        return RefreshJobResponse.of(ownRefresh(caller, jobId));
    }

    @DeleteMapping("/refresh-jobs/{jobId}")
    @Operation(summary = "Lauf der KI-Akronymauflösung abbrechen",
            description = "Bis dahin wird nichts abgeglichen; der Stand des letzten Laufs bleibt.")
    @ApiResponse(responseCode = "202", description = "RefreshJobResponse")
    @ApiResponse(responseCode = "404", description = "Kein eigener Auftrag mit dieser ID (job-not-found)")
    public ResponseEntity<RefreshJobResponse> cancelRefresh(@Parameter(hidden = true) AccessContext caller,
                                                            @PathVariable String jobId) {
        ownRefresh(caller, jobId);
        return ResponseEntity.accepted().body(RefreshJobResponse.of(jobs.cancel(jobId, caller.jobOwner())));
    }

    /** Nur eigene Aufträge der Anreicherung ohne Space; andere gelten als unbekannt. */
    private JobStatus ownRefresh(AccessContext caller, String jobId) {
        JobStatus job = jobs.status(jobId, caller.jobOwner());
        if (job.type() != JobType.ENRICHMENT || job.partitionKey() != null) {
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

    /** Neue manuelle Beziehung; ohne sourceId global. */
    public record NewRelationRequest(String fromTerm, String toTerm, String type, String sourceId, String spaceKey,
                                     String note, List<EvidenceEntry> evidence) {
    }

    /** Ein Beleg: Fundstelle auf einer Seite, im Text mit diesem Hash. */
    public record EvidenceEntry(String sourceId, String pageId, int start, int end, String inputSha256) {

        static EvidenceEntry of(RelationEvidence evidence) {
            return new EvidenceEntry(evidence.sourceId(), evidence.pageId(), evidence.start(), evidence.end(),
                    evidence.inputSha256());
        }
    }

    /** Eine Beziehung. */
    public record RelationEntry(String id, String fromTerm, String toTerm, String type, boolean directed,
                                String sourceId, String spaceKey, String origin, String status,
                                List<EvidenceEntry> evidence, String note, String generation, boolean evidenceStale,
                                String createdBy, Instant createdAt, String reviewedBy, Instant reviewedAt,
                                long revision) {

        static RelationEntry of(TermRelation relation) {
            return new RelationEntry(relation.id(), relation.fromTerm(), relation.toTerm(), relation.type().id(),
                    relation.type().directed(), relation.scope().sourceId(), relation.scope().spaceKey(),
                    relation.origin().name().toLowerCase(Locale.ROOT),
                    relation.status().name().toLowerCase(Locale.ROOT),
                    relation.evidence().stream().map(EvidenceEntry::of).toList(), relation.note(),
                    relation.generation(), relation.evidenceStale(), relation.createdBy(), relation.createdAt(),
                    relation.reviewedBy(), relation.reviewedAt(), relation.revision());
        }
    }

    /** Ein Stapel Beziehungen; weiter mit nextCursor, bis er null ist. */
    public record RelationListResponse(List<RelationEntry> relations, String nextCursor) {
    }

    /** Art des Vorschlagslaufs; ohne Angabe acronym, heute die einzige. */
    public record RefreshRequest(@Schema(allowableValues = {"acronym"}, nullable = true) String kind) {
    }

    /** Ein Lauf der KI-Akronymauflösung. */
    public record RefreshJobResponse(String id, String sourceId,
                                     @Schema(allowableValues = {"queued", "running", "completed", "failed",
                                             "cancelled"}) String state, JobProgress progress,
                                     @Schema(nullable = true) JobError error, boolean cancelRequested,
                                     Instant createdAt, @Schema(nullable = true) Instant startedAt,
                                     @Schema(nullable = true) Instant finishedAt) {

        static RefreshJobResponse of(JobStatus job) {
            return new RefreshJobResponse(job.id(), job.sourceId(), job.state().name().toLowerCase(Locale.ROOT),
                    job.progress(), job.error(), job.cancelRequested(), job.createdAt(), job.startedAt(),
                    job.finishedAt());
        }
    }

    /** Ausgang eines Vorschlagslaufs. */
    public record ProposalRunResponse(int added, int updated, int withdrawn, int suppressed, int stale) {
    }

    /** Ein Eintrag im Änderungsprotokoll. */
    public record AuditEntryResponse(String action, String actor, Instant at, String status, long revision) {

        static AuditEntryResponse of(TermRelationStore.AuditEntry entry) {
            return new AuditEntryResponse(entry.action(), entry.actor(), entry.at(),
                    entry.status() == null ? null : entry.status().name().toLowerCase(Locale.ROOT),
                    entry.revision());
        }
    }
}
