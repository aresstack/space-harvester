package com.aresstack.spaceharvester.core;

import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.EnrichingCacheFill;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync;
import com.aresstack.spaceharvester.application.enrichment.OutlineProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.language.ActivityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.EntityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.PropertyProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.RelationProcessor;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportRequest;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.harvest.SpaceKeyCheck;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.query.PageCacheFill;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.application.query.SearchHitPrefetch;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.application.ranking.PageRenewal;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.ranking.SearchReranker;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.reading.ReadingViews;
import com.aresstack.spaceharvester.application.snapshot.SourceSnapshots;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.output.convert.DocxConverter;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownConverter;
import com.aresstack.spaceharvester.output.convert.PdfConverter;
import com.aresstack.spaceharvester.output.export.FileSystemExportArtifactWriter;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobEventStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageSearchIndex;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSyncBaselineStore;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.LocalFilesTextProjector;
import com.aresstack.spaceharvester.source.SourceAdapter;
import com.aresstack.spaceharvester.source.SourceSecrets;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.ai.RemoteAi;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Space Harvester als Bibliothek: Quellen, Abgleich in den eingebetteten Lucene-Cache, Seiten, Baum, Suche und Export
 * ohne Spring, ohne REST und ohne ApplicationContext (#100).
 *
 * <p>Ein {@code Harvester} baut dieselben Anwendungsfälle, die der Server in {@code config} verdrahtet, und gibt sie über
 * {@link #sources()}, {@link #pages()}, {@link #search()}, {@link #syncs()}, {@link #exports()} und {@link #jobs()}
 * und den weiteren Zugriffsmethoden heraus; es gibt keine zweite Implementierung. Abgleich und Export laufen als Aufträge im Hintergrund: Fortschritt und
 * Zustand über {@link #status(String)}, kooperativer Abbruch über {@link #cancel(String)}, Warten über
 * {@link #await(String, Duration)}.
 *
 * <pre>{@code
 * try (Harvester harvester = Harvester.builder()
 *         .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", URI.create("https://wiki.example.org"),
 *                 new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, token), Map.of()))
 *         .dataDirectory(Path.of("data"))
 *         .build()) {
 *     JobStatus sync = harvester.await(harvester.sync("wiki", "DOC").id(), Duration.ofMinutes(30));
 *     JobStatus export = harvester.await(harvester.export("wiki", "DOC", List.of("markdown")).id(),
 *             Duration.ofMinutes(30));
 *     ExportArtifact artifact = harvester.exportArtifact(export.id()).orElseThrow();
 * }
 * }</pre>
 *
 * <p>Rechte: Die Bibliothek läuft im Vertrauensbereich des einbettenden Programms. Ohne eigene {@link AccessPolicy}
 * darf der Aufrufer {@link #caller()} alles lesen, was die konfigurierten Zugangsdaten erreichen; wer Inhalte an Dritte
 * weitergibt, setzt mit {@link Builder#accessPolicy} eine Richtlinie (wie der Server mit seinen Client-Freigaben).
 * {@link #sync}, {@link #export} und {@link #exportArtifact} prüfen sie wie die REST-Controller; wer die
 * herausgegebenen Anwendungsfälle {@link #syncs()} oder {@link #exports()} direkt aufruft, prüft selbst.
 */
public final class Harvester implements AutoCloseable {

    /** Eigentümer der Aufträge, die über diesen Einstieg entstehen. */
    public static final String OWNER = "space-harvester-core";

    private static final Duration POLL = Duration.ofMillis(50);

    private final List<AutoCloseable> resources;
    private final AccessContext caller;
    private final AccessGuard access;
    private final SourceQueries sources;
    private final SourceSnapshots snapshots;
    private final SpaceKeyCheck spaceKeys;
    private final PageFreshness freshness;
    private final PageQueries pages;
    private final PageAddresses addresses;
    private final ReadingViews readingViews;
    private final PageNavigation navigation;
    private final PageSearch search;
    private final KeywordSearch keywordSearch;
    private final RelevanceSearch relevance;
    private final Jobs jobs;
    private final SpaceSyncJobs syncs;
    private final Exports exports;
    private final ExportConversions conversions;
    private final Assets assets;
    private final Enrichments enrichments;
    private final ConfiguredSourceCatalog catalog;
    private final SourceManagement sourceManagement;
    private final TrustBundles trustBundles;
    private final String userAgent;

    private Harvester(Builder builder) {
        List<AutoCloseable> opened = new ArrayList<>();
        try {
            TrustBundles trustBundles = TrustBundles.withDefault(builder.trustBundles,
                    TrustPolicy.defaultTrust(builder.trust));
            ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(builder.sources, builder.userAgent,
                    trustBundles, builder.proxy, builder.asyncTransport, builder.adapters(),
                    builder.secretProvider);
            opened.add(catalog);
            this.catalog = catalog;
            this.trustBundles = trustBundles;
            this.userAgent = builder.userAgent;
            Path cacheDirectory = builder.dataDirectory.resolve("cache");
            LucenePageCache cache = LucenePageCache.open(cacheDirectory.resolve("pages"));
            opened.add(cache);
            LuceneJobStore jobStore = LuceneJobStore.open(cacheDirectory.resolve("jobs"));
            opened.add(jobStore);
            LuceneJobEventStore eventStore = LuceneJobEventStore.open(cacheDirectory.resolve("job-events"));
            opened.add(eventStore);
            ExecutorService executor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("harvester-job-", 0).factory());
            // Erst wenn kein Auftrag mehr läuft, dürfen Ablage, Cache und Quellen schließen.
            opened.add(() -> {
                executor.shutdownNow();
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Aufträge laufen nach 30 s noch");
                }
            });
            this.jobs = new Jobs(jobStore, eventStore, catalog::route, executor, builder.jobLimits,
                    Clock.systemUTC());
            opened.add(jobs);

            Clock clock = Clock.systemUTC();
            LuceneEnrichmentStore enrichmentStore = LuceneEnrichmentStore.open(cacheDirectory.resolve("enrichments"));
            opened.add(enrichmentStore);
            EmbeddingProcessor embedding = null;
            ExecutorService background = null;
            if (builder.embeddingProvider != null || builder.ai.embeddings()) {
                // ADR 0011: über den externen Dienst oder das Modell des Programms (#188), im Hintergrund, nur für
                // freigegebene Quellen.
                AiServiceSettings ai = builder.ai;
                embedding = builder.embeddingProvider != null
                        ? new EmbeddingProcessor(builder.embeddingProvider, builder.maxChunkChars,
                                builder.embeddingSources)
                        : new EmbeddingProcessor(RemoteAi.of(ai, catalog, trustBundles, builder.userAgent)
                                .embeddings(), builder.maxChunkChars, ai::mayProcessExternally);
                ExecutorService started = Enrichments.background(1000);
                background = started;
                opened.add(() -> {
                    started.shutdownNow();
                    started.awaitTermination(10, TimeUnit.SECONDS);
                });
            }
            List<EnrichmentProcessor> processors = new ArrayList<>(List.of(new AcronymProcessor(),
                    new OutlineProcessor(), new EntityProcessor(), new PropertyProcessor(), new ActivityProcessor(),
                    new RelationProcessor()));
            if (embedding != null) {
                processors.add(embedding);
            }
            this.enrichments = new Enrichments(new ConfluenceStorageTextProjector(), processors, enrichmentStore,
                    clock, background);
            // #180: Volltext über den Cache, abgeleitet und neu aufbaubar.
            LucenePageTextIndex textIndex = LucenePageTextIndex.open(cacheDirectory.resolve("text"));
            opened.add(textIndex);
            PageTextIndexing texts = new PageTextIndexing(List.of(new ConfluenceStorageTextProjector(),
                    new LocalFilesTextProjector()), textIndex);

            this.access = new AccessGuard(builder.accessPolicy);
            LucenePageCacheReader reader = new LucenePageCacheReader(cache);
            LucenePageSearchIndex index = new LucenePageSearchIndex(cache, builder.searchTimeout);
            this.snapshots = new SourceSnapshots(catalog);
            this.sources = new SourceQueries(catalog);
            this.spaceKeys = new SpaceKeyCheck(sources);
            this.freshness = new PageFreshness(sources, snapshots, clock);
            this.pages = new PageQueries(catalog, reader, access, freshness,
                    new EnrichingCacheFill(PageCacheFill.of(cache::state, cache::trySave), enrichments, texts),
                    builder.maxTreeNodes);
            this.addresses = new PageAddresses(catalog, access, pages);
            this.readingViews = new ReadingViews(catalog, pages, access, new ReadingViewStore(enrichmentStore), clock);
            this.navigation = new PageNavigation(catalog, reader, access);
            this.search = new PageSearch(catalog, index, access);
            SearchHitPrefetch prefetch = null;
            if (builder.prefetchSearchHits) {
                // #180: sichtbare Live-Treffer begrenzt im Hintergrund in den Cache laden.
                ExecutorService prefetches = Enrichments.background(100);
                prefetch = new SearchHitPrefetch(key -> cache.findHeader(key)
                        .map(header -> header.metadata().version()), new PageRenewal(pages, prefetches)::renew,
                        SearchHitPrefetch.stopping(prefetches));
                opened.add(prefetch);
            }
            this.keywordSearch = new KeywordSearch(catalog, access, textIndex, prefetch);
            PageRenewal renewal = null;
            if (embedding != null) {
                // ADR 0011, Punkt 7: Relevanztreffer live prüfen, neuere Versionen im Hintergrund nachladen.
                ExecutorService renewals = Enrichments.background(100);
                opened.add(() -> {
                    renewals.shutdownNow();
                    renewals.awaitTermination(10, TimeUnit.SECONDS);
                });
                renewal = new PageRenewal(pages, renewals);
            }
            SearchReranker reranker = builder.reranker;
            if (reranker == SearchReranker.NONE && builder.ai.reranking()) {
                // #181: Cross-Encoder des Dienstes, Texte nur aus freigegebenen Quellen.
                reranker = new ModelReranker(builder.ai.rerankModel(), RemoteAi.of(builder.ai, catalog, trustBundles,
                        builder.userAgent).reranker(), builder.ai::mayProcessExternally);
            }
            this.relevance = new RelevanceSearch(catalog, index, access, enrichmentStore, enrichmentStore, embedding,
                    renewal, reranker, textIndex);
            this.syncs = new SpaceSyncJobs(jobs, sources, new LuceneSyncBaselineStore(cache), snapshots, clock,
                    List.of(new EnrichmentsAfterSync(enrichments, cache, jobs, texts)));
            FileSystemExportArtifactWriter exportWriter = new FileSystemExportArtifactWriter(builder.exportDirectory());
            ExportConverters exportConverters = new ExportConverters(List.of(new JsonArrayConverter(),
                    new MarkdownConverter(), new DocxConverter(), new PdfConverter(builder.pdfFont)),
                    ExportConverters.DEFAULT_MAX_BYTES);
            this.exports = new Exports(jobs, reader, exportWriter, exportConverters, syncs, clock);
            FileSystemAssetStore assetStore = new FileSystemAssetStore(builder.assetDirectory());
            this.assets = new Assets(catalog, assetStore, builder.maxAssetBytes, clock, builder.imageDescriber);
            // Bilder gehören zum Datenbestand: nach jedem veröffentlichten Export ein eigener Auftrag ASSETS.
            this.caller = AccessContext.technicalClient(OWNER);
            ExportAssetCollection images = new ExportAssetCollection(jobs, exportWriter, sources, assets,
                    (owner, sourceId) -> OWNER.equals(owner)
                            && access.permitsSourceWide(caller, sourceId, AccessKind.CONTENT));
            exports.onPublished(images::start);
            this.conversions = new ExportConversions(jobs, exportWriter, exportConverters, images);
            this.sourceManagement = new SourceManagement(catalog, jobs, cache, enrichmentStore, textIndex, assetStore,
                    exportWriter, clock);
            this.resources = List.copyOf(opened);
        } catch (RuntimeException e) {
            closeAll(opened, e);
            throw e;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Aufrufer für {@link #pages()} und {@link #search()}; die Richtlinie aus dem Builder entscheidet über ihn. */
    public AccessContext caller() {
        return caller;
    }

    /**
     * Quellen zur Laufzeit prüfen, testen, hinzufügen, ändern und entfernen (#188), samt Entfernen ihrer abgeleiteten
     * Daten. Prüft keine {@code AccessPolicy}: Die Verwaltung gehört dem Betreiber des einbettenden Programms.
     */
    public SourceManagement sourceManagement() {
        return sourceManagement;
    }

    /** Quellen, Partitionen (Spaces), Live-Metadaten und Anhangslisten direkt bei der Quelle. */
    public SourceQueries sources() {
        return sources;
    }

    /** Native Seiten-Snapshots direkt von der Quelle, ohne Cache. */
    public SourceSnapshots snapshots() {
        return snapshots;
    }

    /** Prüft, ob ein Space bei der Quelle existiert. */
    public SpaceKeyCheck spaceKeys() {
        return spaceKeys;
    }

    /** Echte Prüfung einer Seite gegen die Quelle ({@code refresh=true}). */
    public PageFreshness freshness() {
        return freshness;
    }

    /** Seiten, Baum und Einzelseiten aus dem Cache, mit {@code refresh} gegen die Quelle geprüft. */
    public PageQueries pages() {
        return pages;
    }

    /**
     * Adressen über alle Quellen auflösen (#178): URLs mit und ohne Seiten-ID, Titel, Startseiten, Bookmarks und
     * Ressourcen-IDs der Quellen sowie Lesezeichen {@code space-harvester://…}; in einer Quelle
     * {@link PageQueries#pageByUrl}.
     */
    public PageAddresses addresses() {
        return addresses;
    }

    /**
     * Die Lese-Ansicht einer Seite, wie die Quelle sie rendert (#176): mit ausgeführten Makros, Lesetext und Bildern
     * der Ansicht; Rechte und Version wie {@link #pages()}.
     */
    public ReadingViews readingViews() {
        return readingViews;
    }

    /**
     * Live-Navigation (#177): Kindseiten in der Reihenfolge der Quelle mit {@code hasChildren}, Lage einer Seite mit
     * Space-Namen und Vorfahren, Querverweise; Rechte je Knoten.
     */
    public PageNavigation navigation() {
        return navigation;
    }

    /** Titel- und Metadatensuche im Index (Bestand); für die Suche über Titel und Inhalt {@link #keywordSearch()}. */
    public PageSearch search() {
        return search;
    }

    /**
     * Die globale Schlagwortsuche über die Suche der Quellen, über Titel und Inhalt, ohne vorherigen Abgleich und ohne
     * Nachladen; Treffer mit Titel, Textauszug, Adresse, Version, Quelle und Space.
     */
    public KeywordSearch keywordSearch() {
        return keywordSearch;
    }

    /**
     * Suche nach Relevanz ({@code sort=relevance}, experimentell): nur mit Embedding-Dienst ({@link Builder#ai}).
     */
    public RelevanceSearch relevance() {
        return relevance;
    }

    /** Anhänge und Bilder einer Seite, abgelegt unter dem Asset-Verzeichnis. */
    public Assets assets() {
        return assets;
    }

    /**
     * Die Modelle des KI-Dienstes für „Modelle laden“, auch mit noch nicht gespeicherten Einstellungen aus der
     * Oberfläche. Fragt {@code GET {base-url}/models} über dieselbe Proxy-Route wie die Quellen ab (PAC, Proxy,
     * fail-closed).
     *
     * @throws com.aresstack.spaceharvester.ai.AiServiceException ohne Dienst oder wenn er nicht wie erwartet antwortet
     * @throws IllegalArgumentException                          wenn {@code trust-bundle} nicht konfiguriert ist
     */
    public List<AiModel> aiModels(AiServiceSettings settings) {
        return RemoteAi.of(settings, catalog, trustBundles, userAgent).models();
    }

    /**
     * Misst die Vektordimension eines Embedding-Modells mit einem kurzen, festen Text, für
     * {@code embedding-dimensions}; dieselbe Route wie {@link #aiModels}.
     *
     * @throws com.aresstack.spaceharvester.ai.AiServiceException ohne Dienst oder wenn er nicht wie erwartet antwortet
     */
    public int aiEmbeddingDimension(AiServiceSettings settings, String embeddingModel) {
        return RemoteAi.of(settings, catalog, trustBundles, userAgent).measureDimension(embeddingModel);
    }

    /** Anreicherungen (Akronyme, Gliederung, Sprache, optional Embeddings); nach jedem Abgleich aktualisiert. */
    public Enrichments enrichments() {
        return enrichments;
    }

    /** Abgleich eines Space in den Cache als Auftrag; Status und Delta über {@link SpaceSyncJobs#status}. */
    public SpaceSyncJobs syncs() {
        return syncs;
    }

    /** Exporte eines Space; fehlt er im Cache, liest der Export ihn selbst ein. */
    public Exports exports() {
        return exports;
    }

    /**
     * Einmal beschaffen, beliebig oft konvertieren: Dateien aus dem Datenbestand abgeschlossener Exporte, ohne die
     * Quelle erneut zu lesen.
     */
    public ExportConversions conversions() {
        return conversions;
    }

    /** Alle Aufträge dieses Harvesters. */
    public Jobs jobs() {
        return jobs;
    }

    /**
     * Startet den Abgleich eines Space, ohne zu warten.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn die Richtlinie {@code SYNC}
     *                                                                               nicht freigibt
     */
    public JobStatus sync(String sourceId, String spaceKey) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        requireActive(sourceId);
        return syncs.start(sourceId, spaceKey, OWNER);
    }

    /** Abgeschaltete oder unbekannte Quellen nehmen an keinem Auftrag teil, auch nicht über den Cache (#188). */
    private void requireActive(String sourceId) {
        if (catalog.find(sourceId).isEmpty()) {
            throw new UnknownSourceException(sourceId);
        }
    }

    /**
     * Startet einen Export, ohne zu warten; die Job-ID ist die Export-ID.
     *
     * @param formats zusätzliche Formate aus {@link Exports#formats()}, leer für nur das Raw-First-Artefakt
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn die Richtlinie
     *                                                                               {@code EXPORT} oder
     *                                                                               {@code CONTENT} nicht freigibt
     */
    public JobStatus export(String sourceId, String spaceKey, List<String> formats) {
        return export(sourceId, spaceKey, formats, false);
    }

    /**
     * Wie {@link #export(String, String, List)}; ein noch nicht eingelesener Space wird im Auftrag eingelesen.
     *
     * @param refresh {@code true} gleicht vor dem Export immer gegen die Quelle ab
     */
    public JobStatus export(String sourceId, String spaceKey, List<String> formats, boolean refresh) {
        requireExport(sourceId, spaceKey);
        requireActive(sourceId);
        return exports.start(ExportRequest.of(sourceId, spaceKey, OWNER, formats).withRefresh(refresh)
                .withVisible(header -> access.canRead(caller, header.key().sourceId(),
                        header.metadata().partitionKey(), header.key().pageId()))
                .withAuthorized(() -> exportPermitted(sourceId, spaceKey)));
    }

    private boolean exportPermitted(String sourceId, String spaceKey) {
        ResourceScope space = ResourceScope.space(sourceId, spaceKey);
        return access.permits(caller, space, AccessKind.EXPORT) && access.permits(caller, space, AccessKind.CONTENT);
    }

    /**
     * Das fertige Artefakt eines Exports oder leer, solange es keines gibt; die Richtlinie wird wie beim Start erneut
     * geprüft, damit ein entzogenes Recht auch fertige Artefakte sperrt.
     */
    public Optional<ExportArtifact> exportArtifact(String exportId) {
        JobStatus job = status(exportId);
        requireExport(job.sourceId(), job.partitionKey());
        return exports.artifact(exportId, OWNER);
    }

    /** Wie der Server: Das Artefakt enthält die nativen Seiteninhalte, also EXPORT und CONTENT. */
    private void requireExport(String sourceId, String spaceKey) {
        ResourceScope space = ResourceScope.space(sourceId, spaceKey);
        access.require(caller, space, AccessKind.EXPORT);
        access.require(caller, space, AccessKind.CONTENT);
    }

    /** Zustand und Fortschritt eines Auftrags. */
    public JobStatus status(String jobId) {
        return jobs.status(jobId, OWNER);
    }

    /**
     * Diagnoseereignisse eines Auftrags in ihrer Reihenfolge: Phasen, Meldungen und beim Scheitern Komponente,
     * Fehlercode, HTTP-Status, Proxy-Route, TLS-Befund und die maskierte Ausnahmekette (docs/DIAGNOSTICS.md).
     *
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException wenn es den Auftrag nicht gibt
     */
    public List<JobEvent> events(String jobId) {
        return jobs.events(jobId, OWNER);
    }

    /** Die jüngsten Diagnoseereignisse aller Aufträge dieses Einstiegs, neueste zuerst. */
    public List<JobEvent> recentEvents(int limit) {
        return jobs.recentEvents(OWNER, limit);
    }

    /**
     * Löscht einen beendeten Auftrag samt seinen Diagnoseereignissen. Das Exportartefakt im Exportverzeichnis
     * verschwindet mit dem Auftrag beim nächsten Aufräumen; anderswo gespeicherte Kopien bleiben.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException      wenn es den Auftrag nicht gibt
     * @throws com.aresstack.spaceharvester.application.jobs.JobStillRunningException wenn er noch wartet oder läuft;
     *                                                                                vorher {@link #cancel} und das
     *                                                                                Ende abwarten
     */
    public void deleteJob(String jobId) {
        jobs.delete(jobId, OWNER);
    }

    /**
     * Löscht alle beendeten Aufträge (abgeschlossen, gescheitert, abgebrochen) samt ihren Ereignissen; wartende und
     * laufende bleiben.
     *
     * @return Anzahl gelöschter Aufträge
     */
    public int deleteFinishedJobs() {
        return jobs.deleteFinished(OWNER, null);
    }

    /**
     * Löscht ein Diagnoseereignis eines beendeten Auftrags ({@link JobEvent#jobId()} und {@link JobEvent#sequence()}).
     *
     * @return {@code false}, wenn es das Ereignis nicht (mehr) gibt
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException      wenn es den Auftrag nicht gibt
     * @throws com.aresstack.spaceharvester.application.jobs.JobStillRunningException wenn er noch wartet oder läuft
     */
    public boolean deleteEvent(String jobId, long sequence) {
        return jobs.deleteEvent(jobId, sequence, OWNER);
    }

    /**
     * Löscht die Diagnoseereignisse aller beendeten Aufträge, die vor {@code before} entstanden sind; die Aufträge
     * bleiben. Ereignisse wartender und laufender Aufträge bleiben.
     *
     * @return Anzahl gelöschter Ereignisse
     */
    public int deleteEventsBefore(Instant before) {
        return jobs.deleteEventsBefore(before, OWNER);
    }

    /** Fordert den Abbruch an; ein laufender Auftrag endet an seinem nächsten Prüfpunkt. */
    public JobStatus cancel(String jobId) {
        return jobs.cancel(jobId, OWNER);
    }

    /**
     * Wartet, bis der Auftrag einen Endzustand erreicht hat.
     *
     * @return der Endzustand ({@link JobState#COMPLETED}, {@link JobState#FAILED} oder {@link JobState#CANCELLED})
     * @throws TimeoutException     wenn der Auftrag nach {@code timeout} noch läuft; er läuft weiter
     * @throws InterruptedException wenn der wartende Thread unterbrochen wird; der Auftrag läuft weiter
     */
    public JobStatus await(String jobId, Duration timeout) throws TimeoutException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            JobStatus status = status(jobId);
            if (status.state().terminal()) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("Auftrag " + jobId + " läuft nach " + timeout + " noch");
            }
            Thread.sleep(POLL);
        }
    }

    /** Beendet Aufträge, schließt die Indizes und die Verbindungen der Quellen. */
    @Override
    public void close() {
        RuntimeException failure = closeAll(resources.reversed(), null);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeAll(List<AutoCloseable> resources, RuntimeException primary) {
        RuntimeException failure = primary;
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new IllegalStateException("Schließen fehlgeschlagen", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return primary == null ? failure : null;
    }

    /** Einstellungen eines {@link Harvester}; dieselben Bedeutungen wie die gleichnamigen Schlüssel des Servers. */
    public static final class Builder {

        private final List<SourceSettingsConfig> sources = new ArrayList<>();
        private Path dataDirectory;
        private String userAgent = "space-harvester-core";
        private ProxySettings proxy = ProxySettings.disabled();
        private TrustBundles trustBundles = TrustBundles.NONE;
        private TrustSettings trust = TrustSettings.jvm();
        private ConfiguredSourceCatalog.AsyncTransport asyncTransport = ConfiguredSourceCatalog.AsyncTransport.WEB_CLIENT;
        private AccessPolicy accessPolicy = new TrustedCaller();
        private JobLimits jobLimits = new JobLimits(2, 20, Duration.ofHours(6));
        private Duration searchTimeout = Duration.ofSeconds(5);
        private int maxTreeNodes = 50_000;
        private boolean prefetchSearchHits = true;
        private Path pdfFont;
        private Path exportDirectory;
        private Path assetDirectory;
        private long maxAssetBytes = 50L * 1024 * 1024;
        private ImageDescriber imageDescriber;
        private AiServiceSettings ai = AiServiceSettings.NONE;
        private int maxChunkChars = 1500;
        private final List<SourceAdapter> sourceAdapters = new ArrayList<>();
        private SourceSecrets secretProvider;
        private EmbeddingProvider embeddingProvider;
        private Predicate<String> embeddingSources;
        private SearchReranker reranker = SearchReranker.NONE;

        private Builder() {
        }

        /**
         * Ein weiterer Quellentyp des einbettenden Programms (#188), z. B. MediaWiki; ein Typ, den der Core schon kennt
         * ({@link ConfiguredSourceCatalog#TYPES}), wird durch ihn ersetzt. Ein Typ zweimal ist ein Fehler beim Bauen.
         */
        public Builder sourceAdapter(SourceAdapter adapter) {
            sourceAdapters.add(Objects.requireNonNull(adapter, "adapter"));
            return this;
        }

        /**
         * Zugangsdaten des einbettenden Programms (#188), z. B. KeePassRPC, ein Tresor oder eine Abfrage beim
         * Benutzer. Gefragt wird erst beim ersten Zugriff einer Quelle und nur, was ihre Konfiguration nicht nennt:
         * {@code source:<id>} (Benutzer und Passwort, ein Token ohne Benutzer) und
         * {@code source:<id>:client-certificate} (Passwort der PKCS#12-Datei). Mit Anbieter darf eine Quelle mit
         * Anmeldung Passwort und Token weglassen. Der Core speichert und protokolliert nichts davon.
         */
        public Builder secretProvider(SourceSecrets secretProvider) {
            this.secretProvider = Objects.requireNonNull(secretProvider, "secretProvider");
            return this;
        }

        /**
         * Ein eigenes Embedding-Modell des einbettenden Programms (#188) statt des externen Dienstes aus {@link #ai};
         * damit bleibt etwa ein lokales Modell im Programm möglich, ohne dass der Core eines mitbringt (ADR 0011).
         * Eingebettet werden alle Quellen; welche das Modell sehen dürfen, steuert {@link #embeddings(EmbeddingProvider,
         * Predicate)}.
         */
        public Builder embeddings(EmbeddingProvider provider) {
            return embeddings(provider, sourceId -> true);
        }

        /**
         * @param sources ob Inhalte der Quelle an das Modell gehen dürfen (Quellen-ID)
         */
        public Builder embeddings(EmbeddingProvider provider, Predicate<String> sources) {
            this.embeddingProvider = Objects.requireNonNull(provider, "provider");
            this.embeddingSources = Objects.requireNonNull(sources, "sources");
            return this;
        }

        /**
         * Ein Reranker des einbettenden Programms (#188), etwa ein Cross-Encoder: ordnet bei der semantischen Suche das
         * nach Ähnlichkeit sortierte Fenster freigegebener Treffer neu; er darf nur umordnen, nie Treffer entfernen oder
         * hinzufügen. Standard ohne; dann ordnet mit {@code space-harvester.ai.rerank-model} der Cross-Encoder des
         * KI-Dienstes um (#181).
         */
        public Builder reranker(SearchReranker reranker) {
            this.reranker = Objects.requireNonNull(reranker, "reranker");
            return this;
        }

        /** Die Quellentypen: die des Core, ersetzt oder ergänzt um die eigenen. */
        private List<SourceAdapter> adapters() {
            List<SourceAdapter> adapters = new ArrayList<>();
            for (SourceAdapter adapter : ConfiguredSourceCatalog.DEFAULT_ADAPTERS) {
                if (sourceAdapters.stream().noneMatch(own -> own.type().equals(adapter.type()))) {
                    adapters.add(adapter);
                }
            }
            adapters.addAll(sourceAdapters);
            return adapters;
        }

        /** Exportartefakte; Standard {@code <dataDirectory>/exports}. */
        public Builder exportDirectory(Path exportDirectory) {
            this.exportDirectory = exportDirectory;
            return this;
        }

        /** Abgelegte Anhänge und Bilder; Standard {@code <dataDirectory>/assets}. */
        public Builder assetDirectory(Path assetDirectory) {
            this.assetDirectory = assetDirectory;
            return this;
        }

        /** Größte einzelne Datei, die als Asset abgelegt wird; Standard 50 MiB wie im Server. */
        public Builder maxAssetBytes(long maxAssetBytes) {
            this.maxAssetBytes = maxAssetBytes;
            return this;
        }

        /** Optionale Bildbeschreibung durch ein Modell; ohne bleibt es bei Referenzen. */
        public Builder imageDescriber(ImageDescriber imageDescriber) {
            this.imageDescriber = imageDescriber;
            return this;
        }

        /**
         * Der externe KI-Dienst (ADR 0011); ohne Embedding-Modell gibt es keine semantische Suche
         * ({@code sort=relevance} antwortet mit {@code semantic-search-unavailable}).
         */
        public Builder ai(AiServiceSettings ai) {
            this.ai = Objects.requireNonNull(ai, "ai");
            return this;
        }

        /** Höchstens so viele Zeichen je eingebettetem Stück; Standard 1500. */
        public Builder embeddingChunkChars(int maxChunkChars) {
            this.maxChunkChars = maxChunkChars;
            return this;
        }

        private Path exportDirectory() {
            return exportDirectory != null ? exportDirectory : dataDirectory.resolve("exports");
        }

        private Path assetDirectory() {
            return assetDirectory != null ? assetDirectory : dataDirectory.resolve("assets");
        }

        /** Eine Quelle wie unter {@code space-harvester.sources}. */
        public Builder source(SourceSettingsConfig source) {
            sources.add(Objects.requireNonNull(source, "source"));
            return this;
        }

        public Builder sources(List<SourceSettingsConfig> sources) {
            sources.forEach(this::source);
            return this;
        }

        /**
         * Pflicht: darunter liegen {@code cache/pages}, {@code cache/jobs}, {@code cache/job-events},
         * {@code cache/enrichments} und, ohne eigene Angabe, {@code exports} und {@code assets}.
         */
        public Builder dataDirectory(Path dataDirectory) {
            this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
            return this;
        }

        /** Proxy und PAC wie {@code space-harvester.network.proxy}; Standard ohne Proxy. */
        public Builder proxy(ProxySettings proxy) {
            this.proxy = Objects.requireNonNull(proxy, "proxy");
            return this;
        }

        /** Vertrauensquellen für {@code trust-bundle} der Quellen; Standard keine. */
        public Builder trustBundles(TrustBundles trustBundles) {
            this.trustBundles = Objects.requireNonNull(trustBundles, "trustBundles");
            return this;
        }

        /**
         * Vertrauen für Quellen und KI-Dienst ohne eigenes {@code trust-bundle} wie {@code space-harvester.network.tls}
         * (#189); Standard nur der JVM-Truststore. Die Windows-Speicher werden erst bei der ersten TLS-Verbindung
         * gelesen.
         *
         * @throws IllegalArgumentException mit den Problemen, etwa einer unlesbaren CA-Datei
         */
        public Builder trust(TrustSettings trust) {
            Objects.requireNonNull(trust, "trust");
            List<String> problems = TrustPolicy.problems(trust);
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", problems));
            }
            this.trust = trust;
            return this;
        }

        /** {@code WEB_CLIENT} (Standard wie im Server) oder {@code DISABLED} für den synchronen Transport. */
        public Builder asyncTransport(ConfiguredSourceCatalog.AsyncTransport asyncTransport) {
            this.asyncTransport = Objects.requireNonNull(asyncTransport, "asyncTransport");
            return this;
        }

        public Builder accessPolicy(AccessPolicy accessPolicy) {
            this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
            return this;
        }

        public Builder jobLimits(JobLimits jobLimits) {
            this.jobLimits = Objects.requireNonNull(jobLimits, "jobLimits");
            return this;
        }

        public Builder searchTimeout(Duration searchTimeout) {
            this.searchTimeout = Objects.requireNonNull(searchTimeout, "searchTimeout");
            return this;
        }

        public Builder maxTreeNodes(int maxTreeNodes) {
            this.maxTreeNodes = maxTreeNodes;
            return this;
        }

        /**
         * Ob die Schlagwortsuche sichtbare Live-Treffer im Hintergrund in den Cache lädt (#180); Standard {@code true}.
         */
        public Builder prefetchSearchHits(boolean prefetchSearchHits) {
            this.prefetchSearchHits = prefetchSearchHits;
            return this;
        }

        /** TrueType-Schrift, die PDF-Exporte einbetten; Standard die eingebaute. */
        public Builder pdfFont(Path pdfFont) {
            this.pdfFont = pdfFont;
            return this;
        }

        /**
         * @throws IllegalStateException ohne {@link #dataDirectory}
         * @throws com.aresstack.spaceharvester.source.InvalidSourceConfigurationException mit allen Problemen der
         *                                                                                 Quellen
         */
        public Harvester build() {
            if (dataDirectory == null) {
                throw new IllegalStateException("dataDirectory fehlt");
            }
            return new Harvester(this);
        }
    }

    /**
     * Richtlinie ohne eigene Vorgabe: Die Bibliothek läuft im Vertrauensbereich des einbettenden Programms, der
     * Aufrufer sieht alles, was die Zugangsdaten der Quellen sehen, auch quellenweit.
     */
    private static final class TrustedCaller implements AccessPolicy {

        @Override
        public AccessDecision decide(AccessContext context, ResourceScope scope, AccessKind kind) {
            return AccessDecision.ALLOW;
        }

        @Override
        public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
            return AccessDecision.ALLOW;
        }
    }
}
