package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.assets.AssetHarvest;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.PageAssets;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageListing;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchResults;
import com.aresstack.spaceharvester.application.query.SpaceTree;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SinglePageMetadata;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.core.Harvester;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Die fachlichen Aktionen der Oberfläche, genau einmal auf die Anwendungsfälle abgebildet. Jede Methode prüft die
 * Rechte so wie der entsprechende REST-Controller des Dienstes (dieselben {@link AccessKind}s, dieselbe verdeckte
 * Prüfung bei Seiten ohne bekannten Space), damit die App keine Rechteprüfung umgeht. Alle Methoden blockieren und
 * laufen nie auf dem Event Dispatch Thread.
 */
public final class Workspace {

    private final Harvester harvester;
    private final AccessGuard access;
    private final AccessContext caller;
    private final ActivityLog activities;

    public Workspace(Harvester harvester, AccessGuard access, ActivityLog activities) {
        this.harvester = Objects.requireNonNull(harvester, "harvester");
        this.access = access;
        this.caller = harvester.caller();
        this.activities = Objects.requireNonNull(activities, "activities");
    }

    // ------------------------------------------------------------------ Wissensquellen

    /** Sichtbare Quellen ({@code GET /api/v1/sources}). */
    public List<HarvestSource> sources() {
        return access.filter(caller, harvester.sources().sources(), AccessKind.METADATA,
                source -> ResourceScope.source(source.id()));
    }

    /** Spaces einer Quelle, live ({@code GET /api/v1/sources/{id}/spaces}); zugleich die Verbindungsprüfung. */
    public List<Partition> spaces(String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        return access.filter(caller, harvester.sources().partitions(sourceId), AccessKind.METADATA,
                partition -> ResourceScope.space(sourceId, partition.key()));
    }

    // ------------------------------------------------------------------ Inhalte, live

    /** Ein Stapel Seitenmetadaten eines Space, live ({@code …/spaces/{key}/pages/metadata}). */
    public PageMetadataListing liveMetadata(String sourceId, String spaceKey, String cursor, int limit) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
        PageMetadataListing listing = harvester.sources().pageMetadata(sourceId, spaceKey, cursor, limit);
        List<PageMetadataListing.Entry> visible = access.filter(caller, listing.entries(), AccessKind.METADATA,
                entry -> ResourceScope.page(sourceId, entry.page().partitionKey(), entry.page().pageId()));
        return new PageMetadataListing(listing.sourceId(), listing.partitionKey(), visible, listing.nextCursor(),
                listing.retrievedAt());
    }

    /** Metadaten einer Seite samt Hashes, live ({@code …/pages/{pageId}/metadata}). */
    public SinglePageMetadata liveMetadata(String sourceId, String pageId) {
        if (access.permits(caller, ResourceScope.page(sourceId, null, pageId), AccessKind.METADATA)) {
            return harvester.sources().pageMetadata(sourceId, pageId);
        }
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        SinglePageMetadata metadata = harvester.sources().pageMetadata(sourceId, pageId);
        access.requireConcealed(caller, ResourceScope.page(sourceId, metadata.entry().page().partitionKey(), pageId),
                AccessKind.METADATA);
        return metadata;
    }

    /** Anhangsliste einer Seite, live ({@code …/pages/{pageId}/attachments}). */
    public PageAttachmentList attachments(String sourceId, String pageId) {
        return visibleAttachments(sourceId, pageId, AccessKind.METADATA);
    }

    // ------------------------------------------------------------------ Inhalte, Cache

    /** Flache Seitenliste aus dem Cache ({@code …/spaces/{key}/pages}). */
    public PageListing cachedPages(String sourceId, String spaceKey, String cursor, int limit) {
        return harvester.pages().pages(caller, sourceId, spaceKey, cursor, limit);
    }

    /** Seitenbaum aus dem Cache ({@code …/spaces/{key}/tree}). */
    public SpaceTree tree(String sourceId, String spaceKey) {
        return harvester.pages().tree(caller, sourceId, spaceKey);
    }

    /** Seite mit nativer Payload ({@code …/pages/{pageId}}, {@code refresh=true} prüft gegen die Quelle). */
    public DeliveredPage page(String sourceId, String pageId, boolean refresh) {
        return harvester.pages().page(caller, sourceId, pageId, refresh);
    }

    /** Seite zu einer Quell-URL mit Seiten-ID ({@code …/pages/by-url}). */
    public DeliveredPage pageByUrl(String sourceId, String url, boolean refresh) {
        return harvester.pages().pageByUrl(caller, sourceId, url, refresh);
    }

    /** Anreicherungen einer gelieferten Seite ({@code view=extended}); das Lesen der Seite hat CONTENT geprüft. */
    public List<Enrichments.View> enrichments(DeliveredPage page) {
        return harvester.enrichments().view(page.snapshot());
    }

    /** Anreicherungen neu berechnen ({@code POST …/enrichments}); braucht wie im Dienst die sync-Freigabe. */
    public Enrichments.Run recomputeEnrichments(String sourceId, String pageId, String processor) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        access.require(caller, ResourceScope.space(sourceId, page.header().metadata().partitionKey()), AccessKind.SYNC);
        Enrichments.Run run = harvester.enrichments().recompute(page.snapshot(), processor);
        harvester.enrichments().flush();
        return run;
    }

    public List<String> enrichmentProcessors() {
        return harvester.enrichments().processorIds();
    }

    // ------------------------------------------------------------------ Suche

    /** Titel- und Metadatensuche im Cache ({@code GET /api/v1/search}). */
    public SearchResults search(PageSearchQuery query, String cursor, int limit) {
        return harvester.search().search(caller, query, cursor, limit);
    }

    /** Ob {@code sort=relevance} semantisch sucht (Embedding-Modell eingerichtet). */
    public boolean semanticSearch() {
        return harvester.relevance().semantic();
    }

    /** Suche nach Relevanz ({@code sort=relevance}). */
    public RelevanceSearch.RelevanceResults relevance(PageSearchQuery query, String text, int limit) {
        return harvester.relevance().search(caller, query, text, limit);
    }

    // ------------------------------------------------------------------ Abgleich

    /** Abgleich eines Space starten ({@code POST …/spaces/{key}/syncs}). */
    public JobStatus startSync(String sourceId, String spaceKey) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        harvester.spaceKeys().requireExisting(sourceId, spaceKey);
        JobStatus status = harvester.syncs().start(sourceId, spaceKey, Harvester.OWNER);
        activities.remember(status.id());
        return status;
    }

    /** Status und Delta eines Abgleichs ({@code GET /syncs/{id}}). */
    public SpaceSyncJobs.SyncJob syncStatus(String jobId) {
        SpaceSyncJobs.SyncJob job = harvester.syncs().status(jobId, Harvester.OWNER);
        requireVisible(job.status(), AccessKind.SYNC);
        return job;
    }

    /** Abgleich abbrechen ({@code POST /syncs/{id}/cancel}). */
    public SpaceSyncJobs.SyncJob cancelSync(String jobId) {
        requireVisible(harvester.syncs().status(jobId, Harvester.OWNER).status(), AccessKind.SYNC);
        return harvester.syncs().cancel(jobId, Harvester.OWNER);
    }

    // ------------------------------------------------------------------ Export

    /** Angebotene Ausgabeformate zusätzlich zum Originalexport. */
    public List<String> exportFormats() {
        return harvester.exports().formats();
    }

    /** Export eines Space starten ({@code POST …/spaces/{key}/exports}); braucht export und content. */
    public JobStatus startExport(String sourceId, String spaceKey, List<String> formats) {
        requireExport(sourceId, spaceKey);
        JobStatus status = harvester.exports().start(sourceId, spaceKey, Harvester.OWNER, formats);
        activities.remember(status.id());
        return status;
    }

    /** Artefakt eines fertigen Exports ({@code GET /exports/{id}}). */
    public Optional<ExportArtifact> exportArtifact(String exportId) {
        ownExport(exportId);
        return harvester.exports().artifact(exportId, Harvester.OWNER);
    }

    /** Eine Datei des Artefakts ({@code GET /exports/{id}/files/{name}}). */
    public Optional<InputStream> exportFile(String exportId, String name) {
        ownExport(exportId);
        return harvester.exports().artifactFile(exportId, Harvester.OWNER, name);
    }

    /** Export abbrechen ({@code POST /exports/{id}/cancel}). */
    public JobStatus cancelExport(String exportId) {
        ownExport(exportId);
        return harvester.jobs().cancel(exportId, Harvester.OWNER);
    }

    // ------------------------------------------------------------------ Aufträge

    /** Status eines Auftrags dieser App, unabhängig von seiner Art. */
    public JobStatus job(String jobId) {
        JobStatus status = harvester.jobs().status(jobId, Harvester.OWNER);
        requireVisible(status, status.type() == JobType.EXPORT ? AccessKind.EXPORT : AccessKind.SYNC);
        return status;
    }

    /**
     * Alle Aufträge dieser App, die die Auftragsablage noch kennt, neueste zuerst. Abgelaufene oder fremde
     * Aufträge vergisst die Liste.
     */
    public List<JobStatus> jobs() {
        List<JobStatus> result = new ArrayList<>();
        for (String id : activities.ids()) {
            try {
                result.add(job(id));
            } catch (UnknownJobException e) {
                activities.forget(id);
            } catch (RuntimeException e) {
                // Ohne Recht auf den Space (z. B. nach geänderter Konfiguration) bleibt der Auftrag unsichtbar.
            }
        }
        result.sort(Comparator.comparing(JobStatus::createdAt).reversed());
        return result;
    }

    // ------------------------------------------------------------------ Anhänge

    /** Anhänge einer Seite mit Art und Ablagestand ({@code …/pages/{pageId}/assets}). */
    public PageAssets assets(String sourceId, String pageId) {
        return harvester.assets().overview(visibleAttachments(sourceId, pageId, AccessKind.METADATA));
    }

    /** Alle Anhänge einer Seite ernten ({@code POST …/assets/harvest}); braucht sync. */
    public AssetHarvest harvestAssets(String sourceId, String pageId) {
        return harvester.assets().harvest(visibleAttachments(sourceId, pageId, AccessKind.SYNC));
    }

    /** Inhalt eines Anhangs ({@code …/assets/{id}/content}); legt nur mit sync-Freigabe ab. */
    public Assets.AssetContent assetContent(String sourceId, String pageId, String attachmentId) {
        PageAttachmentList list = visibleAttachments(sourceId, pageId, AccessKind.CONTENT);
        boolean mayStore = access.permits(caller, ResourceScope.page(sourceId, list.spaceKey(), pageId), AccessKind.SYNC);
        return harvester.assets().content(list, attachmentId, mayStore);
    }

    // ------------------------------------------------------------------ intern

    private PageAttachmentList visibleAttachments(String sourceId, String pageId, AccessKind kind) {
        if (access.permits(caller, ResourceScope.page(sourceId, null, pageId), kind)) {
            return harvester.sources().attachments(sourceId, pageId);
        }
        access.require(caller, ResourceScope.source(sourceId), kind);
        PageAttachmentList list = harvester.sources().attachments(sourceId, pageId);
        access.requireConcealed(caller, ResourceScope.page(sourceId, list.spaceKey(), pageId), kind);
        return list;
    }

    private void requireVisible(JobStatus status, AccessKind kind) {
        if (!access.permits(caller, ResourceScope.space(status.sourceId(), status.partitionKey()), kind)) {
            throw new UnknownJobException(status.id());
        }
    }

    private void ownExport(String exportId) {
        JobStatus job = harvester.jobs().status(exportId, Harvester.OWNER);
        if (job.type() != JobType.EXPORT) {
            throw new UnknownJobException(exportId);
        }
        requireExport(job.sourceId(), job.partitionKey());
    }

    private void requireExport(String sourceId, String spaceKey) {
        ResourceScope space = ResourceScope.space(sourceId, spaceKey);
        access.require(caller, space, AccessKind.EXPORT);
        access.require(caller, space, AccessKind.CONTENT);
    }
}
