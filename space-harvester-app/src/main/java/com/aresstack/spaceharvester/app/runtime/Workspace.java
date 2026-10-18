package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.assets.AssetHarvest;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.PageAssets;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobStillRunningException;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageListing;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchResults;
import com.aresstack.spaceharvester.application.query.SpaceTree;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.reading.ReadingView;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SinglePageMetadata;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.core.Harvester;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
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

    /**
     * Lese-Ansicht einer Seite ({@code …/pages/{pageId}/reading-view}, #176): gerendertes HTML der Quelle mit den
     * Bildern, die sie zeigt; Rechte und Version prüft der Core.
     */
    public ReadingView readingView(String sourceId, String pageId, boolean refresh) {
        return harvester.readingViews().view(caller, sourceId, pageId, refresh);
    }

    /**
     * Ein Bild einer Lese-Ansicht aus der Ablage oder einmalig über die Anmeldung der Quelle geladen und abgelegt
     * ({@link Assets#viewImage}). Nur Bilder, die die Ansicht des Aufrufers selbst nennt.
     */
    public Assets.AssetContent viewImage(ReadingView view, HarvestSource.ViewImage image) {
        PageHeader header = view.header();
        if (view.status() != ReadingView.Status.AVAILABLE || !view.images().contains(image)) {
            throw new IllegalArgumentException("Das Bild gehört nicht zu dieser Lese-Ansicht");
        }
        access.require(caller, ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId()), AccessKind.CONTENT);
        return harvester.assets().viewImage(header.key().sourceId(), header.key().pageId(), view.version(), image);
    }

    /**
     * Das gezeichnete Mermaid-Diagramm einer Lese-Ansicht ({@link Assets#rendition}): einmal gezeichnet und abgelegt,
     * dieselben Quelldaten werden nie erneut gezeichnet (#190). Leer, wenn es sich nicht zeichnen lässt.
     */
    public java.util.Optional<byte[]> mermaid(ReadingView view, String source) {
        PageHeader header = view.header();
        if (view.status() != ReadingView.Status.AVAILABLE) {
            throw new IllegalArgumentException("Ohne Lese-Ansicht keine Diagramme");
        }
        access.require(caller, ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId()), AccessKind.CONTENT);
        Assets assets = harvester.assets();
        Assets.Rendered rendered = assets.rendition(header.key().sourceId(), header.key().pageId(),
                Assets.MERMAID_RENDITION, source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (rendered.asset() == null) {
            return java.util.Optional.empty();
        }
        return assets.stored(header.key().sourceId(), rendered.asset().attachmentId(), rendered.asset().version(),
                rendered.asset().sha256());
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

    /** Titel- und Metadatensuche im Cache ({@code GET /api/v1/search}); ohne {@code sourceId} global (#114). */
    public SearchResults search(PageSearchQuery query, String cursor, int limit) {
        return harvester.search().search(caller, query, cursor, limit);
    }

    /**
     * Schlagwortsuche über Titel und Inhalt in den Quellen selbst ({@code GET /api/v1/search?text=…}); jeder Treffer
     * ist live auf Freigabe geprüft. {@code cursor} gilt nur für dieselbe Anfrage.
     */
    public KeywordSearch.Results keywordSearch(KeywordSearch.Query query, String cursor, int limit) {
        return harvester.keywordSearch().search(caller, query, cursor, limit);
    }

    /** Ob {@code sort=relevance} semantisch sucht (Embedding-Modell eingerichtet). */
    public boolean semanticSearch() {
        return harvester.relevance().semantic();
    }

    /** Suche nach Relevanz ({@code sort=relevance}). */
    public RelevanceSearch.RelevanceResults relevance(PageSearchQuery query, String text, int limit) {
        return harvester.relevance().search(caller, query, text, limit);
    }

    /**
     * Die Modelle des KI-Dienstes für „Modelle laden“, auch mit noch nicht gespeicherten Eingaben; über dieselbe
     * Proxy-Route wie die Quellen.
     */
    public List<AiModel> aiModels(AiServiceSettings settings) {
        return harvester.aiModels(settings);
    }

    /** Misst die Vektordimension eines Embedding-Modells für {@code embedding-dimensions}. */
    public int aiEmbeddingDimension(AiServiceSettings settings, String embeddingModel) {
        return harvester.aiEmbeddingDimension(settings, embeddingModel);
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

    /**
     * Datenbestand eines abgeschlossenen Exports ({@code GET /exports/{id}/dataset}); leer, solange keiner vorliegt.
     * Nur dann bietet die Oberfläche „Exportieren als …“ an.
     */
    public Optional<ExportDatasetInfo> exportDataset(String exportId) {
        ownExport(exportId);
        return harvester.conversions().dataset(exportId, Harvester.OWNER);
    }

    /**
     * Erzeugt aus dem Datenbestand eines Exports eine Datei im Ordner {@code directory}, ohne die Quelle erneut zu
     * lesen ({@code GET /exports/{id}/conversions/{format}}); überschreibt nie.
     */
    public ExportConversions.ConvertedFile convertExport(String exportId, String format, Path directory) {
        ownExport(exportId);
        return harvester.conversions().convert(exportId, Harvester.OWNER, format, directory);
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
        // Anreicherungen und Bilder plant der Kern nach Abgleich oder Export selbst ein; sie gehören trotzdem in die Liste.
        List<JobStatus> followUps = new ArrayList<>(harvester.jobs().list(Harvester.OWNER, JobType.ENRICHMENT));
        followUps.addAll(harvester.jobs().list(Harvester.OWNER, JobType.ASSETS));
        for (JobStatus enrichment : followUps) {
            if (result.stream().noneMatch(known -> known.id().equals(enrichment.id()))) {
                try {
                    requireVisible(enrichment, AccessKind.SYNC);
                    result.add(enrichment);
                } catch (RuntimeException e) {
                    // wie oben: ohne Recht auf den Space unsichtbar
                }
            }
        }
        result.sort(Comparator.comparing(JobStatus::createdAt).reversed());
        return result;
    }

    /** Bricht einen eigenen Auftrag beliebiger Art ab, etwa eine Anreicherung. */
    public JobStatus cancelJob(String jobId) {
        job(jobId);
        return harvester.jobs().cancel(jobId, Harvester.OWNER);
    }

    /**
     * Diagnoseereignisse eines Auftrags in ihrer Reihenfolge (docs/DIAGNOSTICS.md); bereits maskiert. Ein fremder
     * oder nicht mehr sichtbarer Auftrag gilt als unbekannt.
     */
    public List<JobEvent> events(String jobId) {
        job(jobId);
        return harvester.events(jobId);
    }

    /** Die jüngsten Diagnoseereignisse aller sichtbaren Aufträge dieser App, neueste zuerst. */
    public List<JobEvent> recentEvents(int limit) {
        return harvester.recentEvents(limit).stream()
                .filter(event -> access.permits(caller, ResourceScope.space(event.sourceId(), event.partitionKey()),
                        event.type() == JobType.EXPORT ? AccessKind.EXPORT : AccessKind.SYNC))
                .toList();
    }

    /**
     * Löscht einen beendeten Auftrag samt Diagnoseereignissen und nimmt ihn aus der Liste. Exportdateien, die der
     * Benutzer gespeichert hat, und der Seitenbestand bleiben.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.JobStillRunningException wenn er noch wartet oder läuft
     */
    public void deleteJob(String jobId) {
        job(jobId);
        harvester.deleteJob(jobId);
        activities.forget(jobId);
    }

    /** Löscht alle sichtbaren beendeten Aufträge; wartende und laufende bleiben. @return Anzahl */
    public int deleteFinishedJobs() {
        int deleted = 0;
        for (JobStatus status : jobs()) {
            if (status.state().terminal()) {
                try {
                    deleteJob(status.id());
                    deleted++;
                } catch (UnknownJobException e) {
                    activities.forget(status.id());
                }
            }
        }
        return deleted;
    }

    /**
     * Löscht genau diese Aufträge, soweit sie sichtbar und beendet sind; schon gelöschte oder wieder laufende bleiben
     * unberührt. @return Anzahl
     */
    public int deleteJobs(List<String> jobIds) {
        int deleted = 0;
        for (String id : jobIds) {
            try {
                if (job(id).state().terminal()) {
                    deleteJob(id);
                    deleted++;
                }
            } catch (UnknownJobException | JobStillRunningException e) {
                // Inzwischen gelöscht oder wieder aktiv: bleibt unberührt.
            }
        }
        return deleted;
    }

    /** Löscht ein Diagnoseereignis eines beendeten, sichtbaren Auftrags; {@code false}, wenn es schon fehlt. */
    public boolean deleteEvent(String jobId, long sequence) {
        job(jobId);
        return harvester.deleteEvent(jobId, sequence);
    }

    /**
     * Löscht die Ereignisse sichtbarer beendeter Aufträge vor {@code before}; die Aufträge bleiben. Aufträge, die die
     * App nicht (mehr) sehen darf, z. B. einer entfernten Quelle, bleiben unberührt. @return Anzahl
     */
    public int deleteEventsBefore(Instant before) {
        int deleted = 0;
        for (JobStatus status : jobs()) {
            if (status.state().terminal()) {
                try {
                    deleted += harvester.jobs().deleteEventsBefore(status.id(), before, Harvester.OWNER);
                } catch (UnknownJobException | JobStillRunningException e) {
                    // Inzwischen gelöscht oder (neu gestartet) wieder aktiv: dann bleibt er unberührt.
                }
            }
        }
        return deleted;
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
