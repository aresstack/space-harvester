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
import com.aresstack.spaceharvester.application.configuration.ConfigurationChange;
import com.aresstack.spaceharvester.application.configuration.ConfigurationListener;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionStatus;
import com.aresstack.spaceharvester.application.acquisition.BackgroundFill;
import com.aresstack.spaceharvester.application.acquisition.FindingCataloging;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.ConfigurationRejectedException;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.Swappable;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.EnrichingCacheFill;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync;
import com.aresstack.spaceharvester.application.enrichment.OutlineProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.TextSegmentation;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.language.ActivityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.EntityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.PropertyProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.RelationProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.export.EnrichmentAnalyses;
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
import com.aresstack.spaceharvester.application.passages.OpenNlpSentenceSegmenter;
import com.aresstack.spaceharvester.application.passages.PassageChunks;
import com.aresstack.spaceharvester.application.passages.PassageSegmentation;
import com.aresstack.spaceharvester.application.passages.PassageProcessor;
import com.aresstack.spaceharvester.application.passages.PassageQueries;
import com.aresstack.spaceharvester.application.passages.PassageSnippets;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.ExcerptPriority;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.query.PageCacheFill;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.application.query.SearchHitPrefetch;
import com.aresstack.spaceharvester.application.query.CandidateSelectionStrategy;
import com.aresstack.spaceharvester.application.query.progressive.HitScorer;
import com.aresstack.spaceharvester.application.query.progressive.AiQueryExpansion;
import com.aresstack.spaceharvester.application.query.progressive.QueryVariantModel;
import com.aresstack.spaceharvester.application.query.progressive.RuleBasedExpansion;
import com.aresstack.spaceharvester.application.query.progressive.SearchOrchestrator;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import com.aresstack.spaceharvester.application.query.progressive.VariantSearch;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.application.ranking.PageRenewal;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.ranking.SearchReranker;
import com.aresstack.spaceharvester.application.ranking.SemanticPreparation;
import com.aresstack.spaceharvester.application.ranking.SemanticVariantSearch;
import com.aresstack.spaceharvester.application.ranking.SwappableReranker;
import com.aresstack.spaceharvester.application.facts.PageFacts;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.searchrun.SearchRuns;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.reading.ReadingViews;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.SourceSnapshots;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sources.SourceType;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sync.Reconciliation;
import com.aresstack.spaceharvester.application.sync.SpaceSync;
import com.aresstack.spaceharvester.application.sync.ChangeEvents;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.AcronymRelationRule;
import com.aresstack.spaceharvester.output.convert.AnalysisConverter;
import com.aresstack.spaceharvester.output.convert.DocxConverter;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.output.convert.JsonPackageConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownPackageConverter;
import com.aresstack.spaceharvester.output.convert.PdfConverter;
import com.aresstack.spaceharvester.output.export.FileSystemExportArtifactWriter;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileTermRelationStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobEventStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneCatalogGaps;
import com.aresstack.spaceharvester.persistence.lucene.LuceneFillQueue;
import com.aresstack.spaceharvester.persistence.lucene.LuceneFindingStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageSearchIndex;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSearchRunLog;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSyncBaselineStore;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.GitSourceAdapter;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import com.aresstack.spaceharvester.source.LocalFilesTextProjector;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import com.aresstack.spaceharvester.source.SourceAdapter;
import com.aresstack.spaceharvester.source.SourceSecrets;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.ai.RemoteAi;
import com.aresstack.spaceharvester.source.ai.VisionImageDescriber;
import com.aresstack.spaceharvester.application.images.ImageProcessor;
import com.aresstack.spaceharvester.application.images.PageImageAnalysis;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

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

    /** So lange bleiben Läufe im Suchlauf-Protokoll, wenn nichts anderes eingestellt ist. */
    public static final Duration DEFAULT_SEARCH_RUN_MAX_AGE = Duration.ofDays(30);

    private static final System.Logger LOG = System.getLogger(Harvester.class.getName());
    private static final Duration POLL = Duration.ofMillis(50);
    /** Gleichzeitige Abrufe, mit denen die Schlagwortsuche gefundene Seiten in den Cache lädt. */
    private static final int SEARCH_FILL_PARALLEL = 4;

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
    private final AcquisitionScheduler acquisition;
    private final BackgroundFill backgroundFill;
    /** Ob Füllung bzw. ihre Parallelität der Konfiguration folgen (ADR 0015), statt im Builder fest zu sein. */
    private final boolean fillFollows;
    private final boolean parallelismFollows;
    private final SearchOrchestrator progressiveSearch;
    private final VariantModels variantModels;
    private final RelevanceSearch relevance;
    private final PassageQueries passages;
    private final TermQueries terms;
    private final SearchRuns searchRuns;
    private final PageFacts facts;
    private final TermRelations termRelations;
    private final AcronymRelationRule acronymRelationRule;
    private final Jobs jobs;
    private final SpaceSyncJobs syncs;
    private final ChangeEvents changeEvents;
    private final Reconciliation reconciliation;
    private final Exports exports;
    private final ExportConversions conversions;
    private final Assets assets;
    private final PageImageAnalysis imageAnalysis;
    private final SignInRequests signIns;
    private final Enrichments enrichments;
    private final ConfiguredSourceCatalog catalog;
    private final SourceManagement sourceManagement;
    private final TrustBundles trustBundles;
    private final String userAgent;
    /** Die wirksame Konfiguration, wenn der Builder einen {@link ConfigurationManager} bekam (ADR 0015). */
    private volatile HarvesterConfiguration settings;
    /** Austauschbares Embedding-Modell; {@code null}, wenn es fest eingestellt ist. */
    private final Swappable<EmbeddingGeneration> embeddingModel;
    /** Austauschbarer Reranker; {@code null}, wenn er fest eingestellt ist. */
    private final SwappableReranker liveReranker;
    /** Die aus der Konfiguration eingeplanten Abgleiche je Space; eigene über {@link #reconcile} gehören nicht dazu. */
    private final Map<String, ConfiguredSchedule> configuredSchedules = new LinkedHashMap<>();

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
            ConfigurationManager manager = builder.configuration;
            this.settings = manager == null ? null : manager.current().configuration();
            // Was der Builder nach configuration() selbst festlegt, bleibt fest; alles andere folgt der Konfiguration.
            boolean liveAi = manager != null && !builder.aiFixed;
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
            // #182: Suchlauf-Protokoll neben der Diagnoseablage, nur Lucene.
            SearchRunLog runLog = SearchRunLog.NONE;
            if (builder.searchRuns) {
                LuceneSearchRunLog lucene = LuceneSearchRunLog.open(cacheDirectory.resolve("search-runs"),
                        builder.searchRunMaxAge, clock);
                opened.add(lucene);
                runLog = lucene;
            }
            this.searchRuns = new SearchRuns(runLog, builder.searchRunAdministrators);
            EmbeddingProcessor embedding = null;
            ExecutorService background = null;
            Swappable<EmbeddingGeneration> model = null;
            if (builder.embeddingProvider == null && liveAi) {
                // ADR 0015: die Kette steht immer, das Modell wechselt mit der Konfiguration oder fehlt (aus).
                Swappable<EmbeddingGeneration> current = Swappable.of(new EmbeddingGeneration(
                        embeddingModel(builder.ai, catalog, trustBundles, builder.userAgent), builder.ai));
                model = current;
                // Freigabe aus denselben Einstellungen wie das Modell: Neu freigegebene Inhalte gehen nie an den
                // Endpunkt einer früheren Generation.
                embedding = new EmbeddingProcessor(() -> current.get().provider().orElse(null),
                        builder.maxChunkChars,
                        KnowledgeTextSegmentation.of(builder.chunkTokens, builder.chunkOverlapSentences),
                        sourceId -> current.get().ai().mayProcessExternally(sourceId));
            } else if (builder.embeddingProvider != null || builder.ai.embeddings()) {
                // ADR 0011: über den externen Dienst oder das Modell des Programms (#188), im Hintergrund, nur für
                // freigegebene Quellen.
                AiServiceSettings ai = builder.ai;
                embedding = builder.embeddingProvider != null
                        ? new EmbeddingProcessor(builder.embeddingProvider, builder.maxChunkChars,
                                KnowledgeTextSegmentation.of(builder.chunkTokens, builder.chunkOverlapSentences), builder.embeddingSources)
                        : new EmbeddingProcessor(RemoteAi.of(ai, catalog, trustBundles, builder.userAgent)
                                .embeddings(), builder.maxChunkChars, KnowledgeTextSegmentation.of(builder.chunkTokens, builder.chunkOverlapSentences),
                                ai::mayProcessExternally);
            }
            // #181: Satzgrenzen für Absätze; mit Modell je semantischem Absatz ein Embedding (withPassages, ADR 0020).
            TextSegmentation sentenceRules = KnowledgeTextSegmentation.defaults();
            SentenceSegmenter sentences = OpenNlpSentenceSegmenter.german(SentenceSegmenter.splitter(sentenceRules));
            if (embedding != null) {
                embedding = embedding.withPassages(new PassageChunks(new PassageSegmentation(sentences,
                        sentenceRules)));
            }
            this.embeddingModel = model;
            if (embedding != null) {
                ExecutorService started = Enrichments.background(1000);
                background = started;
                opened.add(() -> {
                    Enrichments.stop(started, Duration.ofSeconds(10));
                });
            }
            List<EnrichmentProcessor> processors = new ArrayList<>(List.of(new AcronymProcessor(),
                    new OutlineProcessor(), new EntityProcessor(), new PropertyProcessor(), new ActivityProcessor(),
                    new RelationProcessor(), new TermProcessor()));
            if (embedding != null) {
                processors.add(embedding);
            }
            // #181: semantische Absätze, Satzvektoren vom selben Modell wie embedding, sonst nach Struktur.
<<<<<<< HEAD
            processors.add(new PassageProcessor(sentences, sentenceRules, embedding));
=======
            TextSegmentation sentenceRules = KnowledgeTextSegmentation.defaults();
            processors.add(new PassageProcessor(
                    OpenNlpSentenceSegmenter.german(SentenceSegmenter.splitter(sentenceRules)), sentenceRules,
                    embedding));
            // ADR 0021: Bildbeschreibung über das Vision-Modell der wirksamen Konfiguration, nur auf Abruf; der
            // Prozessor images rechnet nie selbst, er bestimmt nur, wann ein Ergebnis gilt.
            AiServiceSettings startAi = builder.ai;
            ImageDescriber describer = builder.imageDescriber != null ? builder.imageDescriber
                    : new VisionImageDescriber(() -> liveAi ? this.settings.ai() : startAi, catalog, trustBundles,
                            builder.userAgent);
            processors.add(new ImageProcessor(() -> describer));
>>>>>>> origin/main
            // Auch lokale Dateien und Quellen über discover/load (#187) bekommen Anreicherungen und Embeddings, wie im
            // Volltextindex.
            PageTextProjector projector = PageTextProjector.firstOf(List.of(new ConfluenceStorageTextProjector(),
                    new LocalFilesTextProjector(), new KnowledgeDocumentTextProjector()));
            this.enrichments = new Enrichments(projector, processors, enrichmentStore, clock, background);
            // #180: Volltext über den Cache, abgeleitet und neu aufbaubar.
            LucenePageTextIndex textIndex = LucenePageTextIndex.open(cacheDirectory.resolve("text"));
            opened.add(textIndex);
            PageTextIndexing texts = new PageTextIndexing(List.of(new ConfluenceStorageTextProjector(),
                    new LocalFilesTextProjector(), new KnowledgeDocumentTextProjector()), textIndex);

            this.access = new AccessGuard(builder.accessPolicy);
            LucenePageCacheReader reader = new LucenePageCacheReader(cache);
            LucenePageSearchIndex index = new LucenePageSearchIndex(cache, builder.searchTimeout);
            this.snapshots = new SourceSnapshots(catalog);
            this.sources = new SourceQueries(catalog);
            this.spaceKeys = new SpaceKeyCheck(sources);
            this.freshness = new PageFreshness(sources, snapshots, clock);
            this.pages = new PageQueries(catalog, reader, access, freshness,
                    new EnrichingCacheFill(PageCacheFill.of(cache::state, cache::trySave), enrichments, texts),
                    builder.maxTreeNodes, freshnessRule(builder), clock);
            this.addresses = new PageAddresses(catalog, access, pages);
            this.passages = new PassageQueries(pages, enrichments, projector);
            this.readingViews = new ReadingViews(catalog, pages, access, new ReadingViewStore(enrichmentStore), clock);
            // ADR 0016: Funde der Quellensuche technischer Aufrufer als Kopfdaten in den Katalog, im Hintergrund; die
            // Füllung erfährt die neuen Lücken, auch in Spaces ohne bestätigte Generation.
            AtomicReference<BackgroundFill> filling = new AtomicReference<>();
            FindingCataloging findings = new FindingCataloging(catalog, access, new LuceneFindingStore(cache),
                    gaps -> {
                        BackgroundFill fill = filling.get();
                        if (fill != null) {
                            fill.offer(gaps);
                        }
                    }, clock);
            opened.add(findings);
            this.navigation = new PageNavigation(catalog, reader, access, findings);
            this.search = new PageSearch(catalog, index, access);
            // #119: Begriffsindex in der Anreicherungsablage, Rechte je Seite wie die Suche.
            this.terms = new TermQueries(catalog, enrichmentStore, access, cache::findHeader, cache::find, projector,
                    new TermProcessor().version());
            this.termRelations = new TermRelations(catalog,
                    new FileTermRelationStore(builder.dataDirectory.resolve("term-relations")), access,
                    cache::findHeader, clock);
            this.acronymRelationRule = new AcronymRelationRule(catalog, enrichmentStore, access, cache::findHeader,
                    termRelations, new TermProcessor().version(), clock);
            // ADR 0016: Die Warteschlange der Hintergrundfüllung schließt erst nach dem Planer, damit kein Abruf mehr
            // endet, wenn sie zu ist.
            // Folgt die Füllung der Konfiguration, gibt es die Warteschlange immer, damit sie sich ohne Neustart
            // einschalten lässt.
            boolean fillFollows = manager != null && !builder.backgroundFillFixed;
            this.fillFollows = fillFollows;
            this.parallelismFollows = manager != null && !builder.backgroundParallelismFixed;
            LuceneFillQueue fillQueue = builder.backgroundFill || fillFollows
                    ? LuceneFillQueue.open(cacheDirectory.resolve("fill-queue")) : null;
            int backgroundParallelism = parallelismFollows
                    ? settings.sync().backgroundParallelism() : builder.backgroundParallelism;
            if (fillQueue != null) {
                opened.add(fillQueue);
            }
            // ADR 0016: eine Warteschlange für Suche und Hintergrund, über denselben Beschaffungsweg wie jeder Abruf.
            AcquisitionScheduler scheduler = new AcquisitionScheduler(pages::acquire,
                    new AcquisitionScheduler.Limits(SEARCH_FILL_PARALLEL,
                            fillQueue != null ? backgroundParallelism : 0,
                            AcquisitionScheduler.Limits.DEFAULT.perSource(), AcquisitionScheduler.Limits.DEFAULT.capacity()),
                    null, AcquisitionScheduler.DEFAULT_RESUME_DELAY);
            opened.add(scheduler);
            this.acquisition = scheduler;
            SearchHitPrefetch prefetch = null;
            if (builder.prefetchSearchHits) {
                // ADR 0013: gefundene Seiten mit den Rechten des Aufrufers in den Cache laden, dann dort suchen.
                prefetch = new SearchHitPrefetch(key -> texts.cachedVersion(key, cache::findHeader, cache::find),
                        key -> cache.findHeader(key).map(PageHeader::metadata), scheduler,
                        SearchHitPrefetch.DEFAULT_WAIT, builder.searchWait);
            }
            SearchReranker reranker = builder.reranker;
            SwappableReranker swappable = null;
            if (reranker == SearchReranker.NONE && liveAi) {
                // ADR 0015: Modell und Option „umordnen“ wechseln zur Laufzeit; ohne beides ordnet nichts um.
                swappable = new SwappableReranker(modelReranker(settings, catalog, trustBundles, builder.userAgent));
                reranker = swappable;
            } else if (reranker == SearchReranker.NONE && builder.ai.reranking()) {
                // #181: Cross-Encoder des Dienstes, Texte nur aus freigegebenen Quellen.
                reranker = new ModelReranker(builder.ai.rerankModel(), RemoteAi.of(builder.ai, catalog, trustBundles,
                        builder.userAgent).reranker(), builder.ai::mayProcessExternally);
            }
            // #154: mit Cross-Encoder lädt die Schlagwortsuche die vielversprechendsten Funde zuerst; mit
            // Konfiguration folgt die Bewertung dem jeweils eingestellten Rerank-Modell.
            ExcerptPriority excerpts;
            if (!builder.excerptRanking) {
                excerpts = ExcerptPriority.off();
            } else if (swappable != null) {
                SwappableReranker live = swappable;
                excerpts = ExcerptPriority.following(() -> live.current() instanceof ModelReranker cross
                        ? cross.excerptPriority() : ExcerptPriority.off());
            } else {
                excerpts = reranker instanceof ModelReranker cross ? cross.excerptPriority() : ExcerptPriority.off();
            }
            this.keywordSearch = new KeywordSearch(catalog, access,
                    new PassageSnippets(textIndex, enrichmentStore::find), prefetch, builder.discoveryQueries,
                    excerpts, builder.selection(), runLog, findings);
            // ADR 0017: Varianten vom Chat-Modell der wirksamen Konfiguration, ohne Modell oder bei Fehlern nach Regeln;
            // bewertet wird immer gegen die ursprüngliche Anfrage.
            AiServiceSettings fixedAi = builder.ai;
            this.variantModels = new VariantModels(() -> liveAi ? this.settings.ai() : fixedAi, catalog,
                    trustBundles, builder.userAgent);
            PageRenewal renewal = null;
            if (embedding != null) {
                // ADR 0011, Punkt 7: Relevanztreffer live prüfen, neuere Versionen im Hintergrund nachladen.
                ExecutorService renewals = Enrichments.background(100);
                opened.add(() -> {
                    Enrichments.stop(renewals, Duration.ofSeconds(10));
                });
                renewal = new PageRenewal(pages, renewals);
            }
            // ADR 0013, Punkt 3: mit Haken erst finden, laden und fehlende Embeddings berechnen.
            SemanticPreparation preparation = embedding == null || prefetch == null ? null
                    : new SemanticPreparation(keywordSearch, key -> reader.page(key).map(CachedPage::snapshot),
                    enrichments, embedding.id(), SemanticPreparation.DEFAULT_WAIT);
            this.relevance = new RelevanceSearch(catalog, index, access, enrichmentStore, enrichmentStore, embedding,
                    renewal, reranker, textIndex, preparation, runLog);
            // #182: Fakten aus den Anreicherungen, Rechte je Seite an ihrem Space im Cache.
            this.facts = new PageFacts(catalog, access, pages, enrichments, enrichmentStore, cache::findHeader);
            // ADR 0017 mit ADR 0013: semantisch findet jede Variante nur, Treffer kommen aus den Vektoren.
            RuleBasedExpansion rules = new RuleBasedExpansion();
            SearchOrchestrator orchestrator = new SearchOrchestrator(VariantSearch.of(keywordSearch),
                    new SemanticVariantSearch(relevance, textIndex),
                    new AiQueryExpansion(variantModels, rules, AiQueryExpansion.DEFAULT_TIMEOUT), rules,
                    HitScorer.of(excerpts), runLog);
            opened.add(orchestrator);
            this.progressiveSearch = orchestrator;
            this.liveReranker = swappable;
            this.syncs = new SpaceSyncJobs(jobs, sources, new LuceneSyncBaselineStore(cache), snapshots, clock,
                    List.of(new EnrichmentsAfterSync(enrichments, cache, jobs, texts)), retentionRule(builder));
            // ADR 0014: Webhooks markieren nur den Katalog; ein- und ausgeschaltet je Quelle über sync.events.
            boolean eventsFixed = builder.changeEvents;
            Map<String, char[]> webhookSecrets = Map.copyOf(builder.webhookSecrets);
            this.changeEvents = new ChangeEvents(syncs, sources, manager == null ? sourceId -> eventsFixed
                    : sourceId -> this.settings.syncFor(sourceId).events(), sourceId -> {
                        char[] secret = webhookSecrets.get(sourceId);
                        return secret == null ? null : secret.clone();
                    });
            opened.add(changeEvents);
            FileSystemExportArtifactWriter exportWriter = new FileSystemExportArtifactWriter(builder.exportDirectory());
            ExportConverters exportConverters = new ExportConverters(List.of(new JsonArrayConverter(),
                    new AnalysisConverter(new EnrichmentAnalyses(enrichments)),
                    new JsonPackageConverter(), new MarkdownConverter(), new MarkdownPackageConverter(), new DocxConverter(), new PdfConverter(builder.pdfFont)),
                    ExportConverters.DEFAULT_MAX_BYTES);
            this.exports = new Exports(jobs, reader, exportWriter, exportConverters, syncs, clock);
            FileSystemAssetStore assetStore = new FileSystemAssetStore(builder.assetDirectory());
            // #183: Typerkennung am Inhalt beim Ablegen, Text von Dokument-Anhängen über Tika
            this.assets = new Assets(catalog, assetStore, builder.maxAssetBytes, clock, describer,
                    TikaDocumentText.shared());
            this.imageAnalysis = new PageImageAnalysis(assets, enrichments, clock);
            // Bilder gehören zum Datenbestand: nach jedem veröffentlichten Export ein eigener Auftrag ASSETS.
            this.caller = AccessContext.technicalClient(OWNER);
            // ADR 0018: verlangt die Quelle einen Einmalcode, wartet der Auftrag auf die Oberfläche.
            this.signIns = new SignInRequests(builder.signInWait, clock);
            ExportAssetCollection images = new ExportAssetCollection(jobs, exportWriter, sources, assets,
                    (owner, sourceId) -> OWNER.equals(owner)
                            && access.permitsSourceWide(caller, sourceId, AccessKind.CONTENT), signIns);
            exports.onPublished(images::start);
            this.conversions = new ExportConversions(jobs, exportWriter, exportConverters, images);
            this.sourceManagement = new SourceManagement(catalog, jobs, cache, enrichmentStore, textIndex, assetStore,
                    exportWriter, clock);
            this.reconciliation = new Reconciliation(syncs, OWNER, clock);
            opened.add(reconciliation);
            if (fillQueue != null) {
                // ADR 0016, Punkt 5: technisch, mit den Zugangsdaten der Quelle, unabhängig von sync.mode; je Quelle
                // abschaltbar (sync.sources.<Quelle>.background-fill).
                AccessContext technical = caller;
                BackgroundFill fill = new BackgroundFill(scheduler, fillQueue, new LuceneCatalogGaps(cache), technical,
                        sourceId -> catalog.find(sourceId).isPresent()
                                && access.permitsSourceWide(technical, sourceId, AccessKind.CONTENT)
                                && (!fillFollows || settings.syncFor(sourceId).backgroundFill()),
                        BackgroundFill.Timing.DEFAULT,
                        fillFollows ? settings.sync().backgroundFillAnywhere() : builder.backgroundFill);
                opened.add(fill);
                this.backgroundFill = fill;
                filling.set(fill);
            } else {
                this.backgroundFill = null;
            }
            if (manager != null) {
                scheduleConfigured(plannedSchedules(settings, false));
                catchUpOnStart();
                ConfigurationManager.Registration registration = manager.register(this::prepare);
                opened.add(registration::close);
            }
            if (backgroundFill != null) {
                backgroundFill.start();
            }
            this.resources = List.copyOf(opened);
        } catch (RuntimeException e) {
            closeAll(opened, e);
            throw e;
        }
    }

    /** Aktualität je Quelle: aus der Konfiguration, wenn sie nicht fest eingestellt ist (ADR 0015). */
    private Function<String, CacheFreshness> freshnessRule(Builder builder) {
        if (builder.configuration == null || builder.freshnessFixed) {
            CacheFreshness fixed = builder.cacheFreshness;
            return sourceId -> fixed;
        }
        return sourceId -> settings.freshnessFor(sourceId);
    }

    /** Frist für gesperrte Seiten je Quelle: aus der Konfiguration, wenn sie nicht fest eingestellt ist. */
    private Function<String, Duration> retentionRule(Builder builder) {
        if (builder.configuration == null || builder.retentionFixed) {
            Duration fixed = builder.withheldRetention;
            return sourceId -> fixed;
        }
        return sourceId -> settings.syncFor(sourceId).withheldRetention();
    }

    /** Ein Embedding-Modell (oder keines) mit den Einstellungen, aus denen es entstand, samt Freigabe je Quelle. */
    private record EmbeddingGeneration(Optional<EmbeddingProvider> provider, AiServiceSettings ai) {
    }

    /**
     * Das Embedding-Modell des Dienstes oder leer ohne Embeddings.
     *
     * @throws IllegalArgumentException wenn Kennung oder Dimension unbrauchbar sind
     */
    private static Optional<EmbeddingProvider> embeddingModel(AiServiceSettings ai, ConfiguredSourceCatalog catalog,
                                                              TrustBundles trustBundles, String userAgent) {
        if (!ai.embeddings()) {
            return Optional.empty();
        }
        EmbeddingProvider provider = RemoteAi.of(ai, catalog, trustBundles, userAgent).embeddings();
        EmbeddingProcessor.check(provider);
        return Optional.of(provider);
    }

    /** Der Cross-Encoder des Dienstes (#181), wenn die Konfiguration umordnen lässt; sonst keiner. */
    private static SearchReranker modelReranker(HarvesterConfiguration configuration, ConfiguredSourceCatalog catalog,
                                                TrustBundles trustBundles, String userAgent) {
        if (!configuration.reranking()) {
            return SearchReranker.NONE;
        }
        AiServiceSettings ai = configuration.ai();
        return new ModelReranker(ai.rerankModel(), RemoteAi.of(ai, catalog, trustBundles, userAgent).reranker(),
                ai::mayProcessExternally);
    }

    /**
     * Bereitet einen Wechsel der Konfiguration vor (ADR 0015): baut Embedding-Modell, Reranker und Zeitplan neu, wo
     * sich etwas geändert hat, und tauscht erst beim Aktivieren. Regeln je Quelle (Aktualität, Frist, Betriebsart)
     * liest jeder Abruf und jeder Lauf aus der dann wirksamen Konfiguration; laufende Aufträge enden mit ihren.
     */
    private ConfigurationListener.Preparation prepare(ConfigurationChange change) {
        HarvesterConfiguration next = change.next();
        List<ConfiguredSchedule> planned = change.syncChanged() ? plannedSchedules(next, true) : null;
        EmbeddingGeneration model = null;
        if (embeddingModel != null && change.embeddingChanged()) {
            try {
                model = new EmbeddingGeneration(embeddingModel(next.ai(), catalog, trustBundles, userAgent),
                        next.ai());
            } catch (RuntimeException e) {
                throw new ConfigurationRejectedException("ai." + AiServiceSettings.EMBEDDING_MODEL,
                        "Das Embedding-Modell lässt sich nicht einsetzen (" + e.getClass().getSimpleName() + ")");
            }
        }
        SearchReranker reranker = null;
        if (liveReranker != null && change.rerankerChanged()) {
            try {
                reranker = modelReranker(next, catalog, trustBundles, userAgent);
            } catch (RuntimeException e) {
                throw new ConfigurationRejectedException("ai." + AiServiceSettings.RERANK_MODEL,
                        "Das Rerank-Modell lässt sich nicht einsetzen (" + e.getClass().getSimpleName() + ")");
            }
        }
        EmbeddingGeneration nextModel = model;
        SearchReranker nextReranker = reranker;
        return () -> {
            settings = next;
            if (backgroundFill != null && fillFollows) {
                // Regeln je Quelle liest die Füllung beim Einreihen und Laden; wake() sucht danach neu im Katalog.
                backgroundFill.enabled(next.sync().backgroundFillAnywhere());
            }
            if (backgroundFill != null && parallelismFollows) {
                acquisition.backgroundParallelism(next.sync().backgroundParallelism());
            }
            if (nextModel != null) {
                embeddingModel.swap(nextModel);
            }
            if (nextReranker != null) {
                liveReranker.swap(nextReranker);
            }
            if (planned != null) {
                scheduleConfigured(planned);
            }
        };
    }

    /** Ein Abgleich, den die Konfiguration einplant ({@code sync.spaces} mit dem Abstand der Quelle). */
    private record ConfiguredSchedule(String sourceId, String spaceKey, Duration interval, SyncMode mode) {

        String key() {
            return sourceId + "/" + spaceKey;
        }
    }

    /**
     * Die Abgleiche aus {@code sync.spaces}: je Eintrag mit Abstand und Betriebsart seiner Quelle; ohne Abstand keiner.
     *
     * @param strict ob Probleme die Konfiguration ablehnen; sonst werden die Einträge übersprungen
     * @throws ConfigurationRejectedException bei unbekannter Quelle oder zu kurzem Abstand, wenn {@code strict}
     */
    private List<ConfiguredSchedule> plannedSchedules(HarvesterConfiguration configuration, boolean strict) {
        List<ConfiguredSchedule> planned = new ArrayList<>();
        List<ConfigurationProblem> problems = new ArrayList<>();
        for (String entry : configuration.sync().spaces()) {
            int slash = entry.indexOf('/');
            if (slash < 1 || slash == entry.length() - 1) {
                problems.add(new ConfigurationProblem(SyncSettings.SPACES,
                        "Jeder Eintrag hat die Form <Quelle>/<Space-Key>, nicht „" + entry + "“"));
                continue;
            }
            String sourceId = entry.substring(0, slash);
            SyncSettings rules = configuration.syncFor(sourceId);
            if (rules.interval() == null) {
                continue;
            }
            if (catalog.find(sourceId).isEmpty()) {
                problems.add(new ConfigurationProblem(SyncSettings.SPACES, "Unbekannte Quelle „" + sourceId + "“"));
                continue;
            }
            if (rules.interval().compareTo(Reconciliation.MIN_INTERVAL) < 0) {
                problems.add(new ConfigurationProblem(SyncSettings.INTERVAL,
                        "Der Abstand muss mindestens eine Minute betragen"));
                continue;
            }
            planned.add(new ConfiguredSchedule(sourceId, entry.substring(slash + 1), rules.interval(),
                    rules.mode()));
        }
        if (!problems.isEmpty()) {
            if (strict) {
                throw new ConfigurationRejectedException(problems.stream().distinct().toList());
            }
            LOG.log(System.Logger.Level.WARNING, "{0} Einträge des Zeitplans übersprungen", problems.size());
        }
        return planned;
    }

    /**
     * Ersetzt die Abgleiche aus der Konfiguration; eigene Einplanungen über {@link #reconcile} bleiben und gehen einem
     * Eintrag für denselben Space vor.
     */
    private void scheduleConfigured(List<ConfiguredSchedule> planned) {
        synchronized (configuredSchedules) {
            Set<String> own = new HashSet<>();
            for (Reconciliation.Schedule schedule : reconciliation.schedules()) {
                String key = schedule.sourceId() + "/" + schedule.spaceKey();
                if (!configuredSchedules.containsKey(key)) {
                    own.add(key);
                }
            }
            for (ConfiguredSchedule previous : configuredSchedules.values()) {
                reconciliation.cancel(previous.sourceId(), previous.spaceKey());
            }
            configuredSchedules.clear();
            for (ConfiguredSchedule schedule : planned) {
                if (own.contains(schedule.key()) || configuredSchedules.containsKey(schedule.key())) {
                    continue;
                }
                reconciliation.schedule(schedule.sourceId(), schedule.spaceKey(), schedule.interval(),
                        schedule.mode());
                configuredSchedules.put(schedule.key(), schedule);
            }
        }
    }

    /** Ein Space gehört ab jetzt dem Aufrufer von {@link #reconcile} oder {@link #stopReconciling}. */
    private void releaseConfigured(String sourceId, String spaceKey) {
        synchronized (configuredSchedules) {
            configuredSchedules.remove(sourceId + "/" + spaceKey);
        }
    }

    /** Holt beim Start nach, wo die Regeln der Quelle es verlangen ({@code sync.catch-up-on-start}). */
    private void catchUpOnStart() {
        HarvesterConfiguration configuration = settings;
        try {
            List<JobStatus> started = syncs.catchUpAll(OWNER, sourceId -> {
                SyncSettings rules = configuration.syncFor(sourceId);
                return rules.catchUpOnStart() ? rules.mode() : null;
            });
            if (!started.isEmpty()) {
                LOG.log(System.Logger.Level.INFO, "Nachholen beim Start: {0} Spaces", started.size());
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Nachholen beim Start nicht gestartet ({0})",
                    e.getClass().getName());
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

    /**
     * Die Quellentypen, die dieser Harvester kennt (#187): die eingebauten und die über
     * {@link Builder#sourceAdapter} eingehängten, in Registrierungsreihenfolge, mit Anzeigename und ob der Typ einen
     * Eintrag {@code options.credentialRef} verlangt.
     */
    public List<SourceType> sourceTypes() {
        return sources.types();
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

    /**
     * Semantische Absätze einer Seite (#181, ADR 0020); in der Leseansicht ohne Aufrufer
     * {@link com.aresstack.spaceharvester.application.passages.PagePassages#read} mit {@link #enrichments()}.
     */
    public PassageQueries passages() {
        return passages;
    }

    /**
     * Begriffsindex (#119, ADR 0019): Akronyme und Bezeichner aller berechtigten Seiten mit Fundstellen und Snippets auf
     * Abruf; für eine einzelne Seite {@link com.aresstack.spaceharvester.application.terms.PageTerms#read}.
     */
    public TermQueries terms() {
        return terms;
    }

    /**
     * Begriffsbeziehungen (#120, ADR 0019 Punkt 5): Abkürzung, Synonym, Schreibvariante, fachlich verwandt, mit
     * Herkunft, Prüfstatus und Sperren. Liegen unter {@code <dataDirectory>/term-relations}, außerhalb des Caches.
     */
    public TermRelations termRelations() {
        return termRelations;
    }

    /** Regelvorschläge aus den Akronym-Ausschreibungen des Begriffsindex, je Quelle ({@code sync}). */
    public AcronymRelationRule acronymRelationRule() {
        return acronymRelationRule;
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
     * Der Beschaffungsplaner (ADR 0016): eine Warteschlange für die Funde der Suche und die Hintergrundfüllung, mit
     * reservierter Kapazität für den Vordergrund. {@link AcquisitionScheduler#pauseBackground()} hält den Hintergrund
     * während einer Aktion des Benutzers an.
     */
    public AcquisitionScheduler acquisition() {
        return acquisition;
    }

    /**
     * Die Hintergrundfüllung (ADR 0016, Punkt 5), leer mit {@link Builder#backgroundFill(boolean) backgroundFill(false)}.
     * {@link BackgroundFill#enabled(boolean)} schaltet sie zur Laufzeit an oder aus.
     */
    public Optional<BackgroundFill> backgroundFill() {
        return Optional.ofNullable(backgroundFill);
    }

    /**
     * Stand der Beschaffung (ADR 0016): Warteschlangen, Pause und Zähler je Quelle, nie Seiten-IDs oder Suchtexte.
     */
    public AcquisitionStatus acquisitionStatus() {
        return backgroundFill != null ? backgroundFill.status() : BackgroundFill.withoutFill(acquisition);
    }

    /**
     * Progressive Suche (ADR 0017): die ursprüngliche Anfrage sofort, dann Varianten in Wellen, bewertet gegen die
     * ursprüngliche Anfrage, bis das Qualitätsziel des {@link SearchProfile} erreicht oder das Budget aufgebraucht ist.
     */
    public SearchOrchestrator progressiveSearch() {
        return progressiveSearch;
    }

    /**
     * Das Standardprofil der progressiven Suche: Varianten vom Chat-Modell ({@link SearchProfile.Expansion#AI}), wenn
     * die wirksame Konfiguration eines hat, sonst nach Regeln.
     *
     * <p>Dieses Profil ist der Haken „Semantische Suche“ (ADR 0013): semantisch
     * ({@link SearchProfile.Ranking#SEMANTIC}), wenn ein Embedding-Modell verfügbar ist, sonst wie bei den Varianten die
     * wirksame Wahl, also die Schlagwortsuche. Semantisch finden die Varianten nur, und die Treffer kommen aus der
     * Vektorsuche mit der ursprünglichen Anfrage; jede Suchanfrage hat dafür {@link SemanticVariantSearch#VARIANT_TIMEOUT}
     * und die Suche {@link SemanticVariantSearch#TIME_BUDGET}. Eine ausdrücklich semantische Suche ohne Modell scheitert mit
     * {@link com.aresstack.spaceharvester.application.ranking.SemanticSearchUnavailableException}.
     */
    public SearchProfile searchProfile() {
        SearchProfile profile = SearchProfile.defaults().withExpansion(variantModels.get().isPresent()
                ? SearchProfile.Expansion.AI : SearchProfile.Expansion.RULES);
        return relevance.semantic() ? SemanticVariantSearch.profile(profile) : profile;
    }

    /**
     * Suche nach Relevanz ({@code sort=relevance}, experimentell): nur mit Embedding-Dienst ({@link Builder#ai}).
     */
    public RelevanceSearch relevance() {
        return relevance;
    }

    /** Anhänge und Bilder einer Seite, abgelegt unter dem Asset-Verzeichnis. */
    /**
     * Das Suchlauf-Protokoll (#182): Schlagwortsuche, semantische und progressive Suche legen je Lauf Anfrage, Quellen,
     * Trefferzahlen je Stufe und Dauer ab (unter {@code cache/search-runs/}). Ein Lauf ist nur für seinen Aufrufer
     * lesbar und für die mit {@link Builder#searchRuns} genannten Verwalter; ausschalten mit
     * {@code searchRuns(false, …)}.
     */
    public SearchRuns searchRuns() {
        return searchRuns;
    }

    /**
     * Abfragbare Fakten je Seite aus den Anreicherungen (#182): Akronyme, Begriffe, Eigenschaften, mit den Rechten des
     * Aufrufers je Seite.
     */
    public PageFacts facts() {
        return facts;
    }

    public Assets assets() {
        return assets;
    }

    /**
     * Bildanalyse (ADR 0021, #362): Bilder der Lese-Ansicht einer Seite mit dem Vision-Modell aus
     * {@code ai.vision-model} beschreiben ({@link PageImageAnalysis#describe}, nur auf Abruf, nie auf dem EDT) und die
     * gespeicherten Beschreibungen lesen ({@link PageImageAnalysis#read}).
     */
    public PageImageAnalysis imageAnalysis() {
        return imageAnalysis;
    }

    /**
     * Offene Anmeldungen (ADR 0018): verlangt eine Quelle beim Beschaffen von Bildern einen Einmalcode, wartet der
     * Auftrag hier („Wartet auf Anmeldung“), sofern {@link Builder#signInWait} eine Wartezeit setzt. Die Oberfläche zeigt {@link SignInRequests#pending()}, sendet den Code
     * mit {@link SignInRequests#submit} und kann ihn neu anfordern ({@link SignInRequests#resend}).
     */
    public SignInRequests signIns() {
        return signIns;
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

    /**
     * Änderungsquelle Webhooks (ADR 0014, Standard aus): nimmt signierte Aufrufe der Quelle an und markiert nur den
     * Katalog (veraltet bzw. nicht geladen, gelöscht oder gesperrt), ohne Inhalte zu laden. Wirkt nur, wenn für die
     * Quelle {@code sync.events} gilt (ohne {@link Builder#configuration} {@link Builder#changeEvents}) und
     * {@link Builder#webhookSecret} ein Geheimnis setzt.
     */
    public ChangeEvents changeEvents() {
        return changeEvents;
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

    /**
     * Wie {@link #sync(String, String)}, in der Betriebsart {@code mode}: {@link SyncMode#CATALOG_ONLY} initialisiert
     * oder bereinigt nur den Seitenkatalog, {@link SyncMode#SYNC_CACHE} hält zusätzlich gecachte Inhalte aktuell (ADR 0014).
     */
    public JobStatus sync(String sourceId, String spaceKey, SyncMode mode) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        requireActive(sourceId);
        return syncs.start(sourceId, spaceKey, OWNER, mode);
    }

    /**
     * Holt verpasste Änderungen eines Space nach, ohne zu warten (ADR 0014): eine Änderungssuche seit {@code since} und
     * eine Papierkorbabfrage, wo die Quelle das kann, sonst ein Abgleich ohne Erstbefüllung. Bekannte Seiten mit neuer
     * Version werden neu geladen, unbekannte nur in den Katalog übernommen, gelöschte entfernt.
     *
     * @param since ab wann, oder {@code null} für den Stand des letzten Laufs
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn die Richtlinie {@code SYNC}
     *                                                                               nicht freigibt
     */
    public JobStatus catchUp(String sourceId, String spaceKey, Instant since) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        requireActive(sourceId);
        return syncs.catchUp(sourceId, spaceKey, OWNER, since);
    }

    /**
     * Vorschau eines Abgleichs („Bestand prüfen“, ADR 0014): was ein Lauf in der Betriebsart ändern würde, neue,
     * geänderte, verschobene, gelöschte und gesperrte Seiten, ohne zu laden oder zu schreiben. Wartet auf die
     * Inventur.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn die Richtlinie {@code SYNC}
     *                                                                               nicht freigibt
     */
    public com.aresstack.spaceharvester.application.sync.SyncPreview preview(String sourceId, String spaceKey,
                                                                            SyncMode mode) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        requireActive(sourceId);
        return syncs.preview(sourceId, spaceKey, mode);
    }

    /**
     * Bestand eines Space im Cache: Katalogstand, bekannte Seiten und Seiten mit Inhalt (ADR 0014). Fragt die Quelle
     * nicht.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn die Richtlinie
     *                                                                               {@code METADATA} nicht freigibt
     */
    public com.aresstack.spaceharvester.application.sync.SpaceCatalog catalog(String sourceId, String spaceKey) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
        requireActive(sourceId);
        return syncs.catalog(sourceId, spaceKey);
    }

    /** Wie {@link #catchUp(String, String, Instant)}, in der Betriebsart {@code mode}. */
    public JobStatus catchUp(String sourceId, String spaceKey, Instant since, SyncMode mode) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        requireActive(sourceId);
        return syncs.catchUp(sourceId, spaceKey, OWNER, since, mode);
    }

    /**
     * Holt für alle Spaces mit bestätigter Generation die verpassten Änderungen nach, etwa beim Start (ADR 0014); nur
     * Spaces, für die die Richtlinie {@code SYNC} freigibt. Wartet nicht.
     *
     * @return die gestarteten Aufträge
     */
    public List<JobStatus> catchUpAll() {
        List<JobStatus> started = new java.util.ArrayList<>();
        for (com.aresstack.spaceharvester.application.sync.SyncBaselineStore.SpaceRef space : syncs.confirmedSpaces()) {
            if (access.permits(caller, ResourceScope.space(space.sourceId(), space.spaceKey()), AccessKind.SYNC)
                    && catalog.find(space.sourceId()).isPresent()) {
                try {
                    started.add(syncs.catchUp(space.sourceId(), space.spaceKey(), OWNER, null));
                } catch (com.aresstack.spaceharvester.application.sync.SyncAlreadyRunningException e) {
                    // läuft schon
                } catch (com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException e) {
                    break;
                }
            }
        }
        return started;
    }

    /**
     * Plant den regelmäßigen Abgleich eines Space ein oder ersetzt seine Einplanung (ADR 0014): je {@code interval} eine
     * Metadateninventur, die nur geänderte Inhalte lädt und Fehlendes einzeln prüft. Ohne {@code loadUnknown} lädt sie
     * keine unbekannten Seiten, sondern hält nur aktuell, was der Cache schon kennt. Gilt bis {@link #close()}; die
     * Anwendung plant beim Start aus ihrer Konfiguration neu ein.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn die Richtlinie {@code SYNC}
     *                                                                               nicht freigibt
     * @throws IllegalArgumentException bei einem Abstand unter {@link Reconciliation#MIN_INTERVAL}
     */
    public Reconciliation.Schedule reconcile(String sourceId, String spaceKey, Duration interval,
                                             boolean loadUnknown) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        requireActive(sourceId);
        releaseConfigured(sourceId, spaceKey);
        return reconciliation.schedule(sourceId, spaceKey, interval, loadUnknown);
    }

    /** Wie {@link #reconcile(String, String, Duration, boolean)}, in der Betriebsart {@code mode}. */
    public Reconciliation.Schedule reconcile(String sourceId, String spaceKey, Duration interval, SyncMode mode) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        requireActive(sourceId);
        releaseConfigured(sourceId, spaceKey);
        return reconciliation.schedule(sourceId, spaceKey, interval, mode);
    }

    /** Nimmt den Space aus dem regelmäßigen Abgleich; {@code false}, wenn er nicht eingeplant war. */
    public boolean stopReconciling(String sourceId, String spaceKey) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        releaseConfigured(sourceId, spaceKey);
        return reconciliation.cancel(sourceId, spaceKey);
    }

    /** Die eingeplanten regelmäßigen Abgleiche mit letztem und nächstem Start, soweit {@code SYNC} sie freigibt. */
    public List<Reconciliation.Schedule> reconciliations() {
        return reconciliation.schedules().stream()
                .filter(schedule -> access.permits(caller,
                        ResourceScope.space(schedule.sourceId(), schedule.spaceKey()), AccessKind.SYNC))
                .toList();
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
        private int discoveryQueries = KeywordSearch.DEFAULT_MAX_QUERIES;
        private boolean excerptRanking = true;
        private boolean searchRuns = true;
        private Duration searchRunMaxAge = DEFAULT_SEARCH_RUN_MAX_AGE;
        private Predicate<AccessContext> searchRunAdministrators = context -> false;
        private int searchLoads = KeywordSearch.DEFAULT_LOADS;
        private boolean searchLoadsSet;
        private CandidateSelectionStrategy.Kind selectionKind;
        private CandidateSelectionStrategy selection;
        private boolean backgroundFill = true;
        private int backgroundParallelism = BackgroundFill.DEFAULT_PARALLELISM;
        private boolean backgroundFillFixed;
        private boolean backgroundParallelismFixed;
        private Duration searchWait = Duration.ZERO;
        private CacheFreshness cacheFreshness = CacheFreshness.DEFAULT;
        private Duration withheldRetention = SpaceSync.DEFAULT_WITHHELD_RETENTION;
        private Path pdfFont;
        private Path exportDirectory;
        private Path assetDirectory;
        private long maxAssetBytes = 50L * 1024 * 1024;
        private Duration signInWait = Duration.ZERO;
        private boolean changeEvents;
        private final Map<String, char[]> webhookSecrets = new HashMap<>();
        private ImageDescriber imageDescriber;
        private AiServiceSettings ai = AiServiceSettings.NONE;
        private int maxChunkChars = 1500;
        private int chunkTokens = KnowledgeTextSegmentation.DEFAULT_MAX_TOKENS;
        private int chunkOverlapSentences = KnowledgeTextSegmentation.DEFAULT_OVERLAP_SENTENCES;
        private final List<SourceAdapter> sourceAdapters = new ArrayList<>();
        private SourceSecrets secretProvider;
        private EmbeddingProvider embeddingProvider;
        private Predicate<String> embeddingSources;
        private SearchReranker reranker = SearchReranker.NONE;
        private ConfigurationManager configuration;
        private boolean aiFixed;
        private boolean freshnessFixed;
        private boolean retentionFixed;

        private Builder() {
        }

        /**
         * Konfiguration zur Laufzeit (ADR 0015): übernimmt aus {@link ConfigurationManager#current()} KI-Dienst,
         * Aktualität vorhandener Inhalte, Frist für gesperrte Seiten und das Laden der Suchtreffer, und folgt danach
         * jedem aktivierten Wechsel ohne Neustart:
         * <ul>
         *     <li>Aktualität ({@code freshnessFor}) und Frist ({@code syncFor}) je Quelle beim nächsten Abruf bzw.
         *     Lauf;</li>
         *     <li>Embedding-Modell und Reranker: neue Suchen nutzen die neuen, laufende behalten ihre; Vektoren des
         *     alten Modells werden ohne neuen Abruf der Seiten neu berechnet, sobald die Suche sie braucht;</li>
         *     <li>Zeitplan aus {@code sync.spaces} mit Abstand und Betriebsart je Quelle; eigene Einplanungen über
         *     {@link Harvester#reconcile} bleiben;</li>
         *     <li>beim Bauen holt jede Quelle mit {@code catch-up-on-start} ihre bestätigten Spaces nach.</li>
         * </ul>
         * Was nach diesem Aufruf mit {@link #ai}, {@link #cacheFreshness}, {@link #withheldRetention},
         * {@link #embeddings} oder {@link #reranker} fest eingestellt wird, bleibt fest und folgt der Konfiguration
         * nicht. Der Harvester meldet sich beim Schließen ab.
         */
        public Builder configuration(ConfigurationManager manager) {
            this.configuration = Objects.requireNonNull(manager, "manager");
            HarvesterConfiguration current = manager.current().configuration();
            this.ai = current.ai();
            this.cacheFreshness = current.search().cacheFreshness();
            this.withheldRetention = current.sync().withheldRetention();
            this.prefetchSearchHits = current.search().prefetchHits();
            this.aiFixed = false;
            this.freshnessFixed = false;
            this.retentionFixed = false;
            this.backgroundFillFixed = false;
            this.backgroundParallelismFixed = false;
            return this;
        }

        /**
         * Ein weiterer Quellentyp des einbettenden Programms (#188), z. B. MediaWiki; ein Typ, den der Core schon kennt
         * ({@link ConfiguredSourceCatalog#TYPES} und {@code git} mit Kopien unter {@code <dataDirectory>/git}), wird
         * durch ihn ersetzt. Ein Typ zweimal ist ein Fehler beim Bauen.
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
            List<SourceAdapter> defaults = new ArrayList<>(ConfiguredSourceCatalog.DEFAULT_ADAPTERS);
            // Git braucht einen Ordner für die Kopien der Repositorys (#187).
            defaults.add(new GitSourceAdapter(dataDirectory.resolve("git")));
            for (SourceAdapter adapter : defaults) {
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

        /**
         * Änderungsquelle Webhooks für alle Quellen ein- oder ausschalten (Standard aus); mit {@link #configuration}
         * gilt stattdessen {@code sync.events} je Quelle.
         */
        public Builder changeEvents(boolean changeEvents) {
            this.changeEvents = changeEvents;
            return this;
        }

        /**
         * Geheimnis, mit dem die Quelle ihre Webhook-Aufrufe signiert (HMAC-SHA256, ADR 0014); ohne Geheimnis nimmt
         * {@link Harvester#changeEvents()} für die Quelle nichts an. Der Builder kopiert es; der Core liest es nie aus
         * der Umgebung, speichert und protokolliert es nicht.
         */
        public Builder webhookSecret(String sourceId, char[] secret) {
            Objects.requireNonNull(sourceId, "sourceId");
            if (secret == null || secret.length == 0) {
                webhookSecrets.remove(sourceId);
            } else {
                webhookSecrets.put(sourceId, secret.clone());
            }
            return this;
        }

        /**
         * Wie lange ein Auftrag auf einen Einmalcode wartet (ADR 0018). Standard {@link Duration#ZERO}: nie, denn ohne
         * Eingabemaske könnte niemand antworten; eine Oberfläche, die {@link #signIns()} bedient, setzt etwa
         * {@link SignInRequests#DEFAULT_WAIT}.
         */
        public Builder signInWait(Duration signInWait) {
            this.signInWait = Objects.requireNonNull(signInWait, "signInWait");
            return this;
        }

        /** Größte einzelne Datei, die als Asset abgelegt wird; Standard 50 MiB wie im Server. */
        public Builder maxAssetBytes(long maxAssetBytes) {
            this.maxAssetBytes = maxAssetBytes;
            return this;
        }

        /**
         * Eigene Bildbeschreibung statt des Vision-Modells aus {@code ai.vision-model} (ADR 0021), etwa für Tests; ohne
         * gilt das Vision-Modell der Konfiguration.
         */
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
            this.aiFixed = configuration != null;
            return this;
        }

        /**
         * Zerlegung für die semantische Suche (#181, wie {@code knowledge.chunk.maxTokens} und
         * {@code knowledge.chunk.overlapSentences} im Enterprise AI Client): Budget je Stück in Tokens (Standard 350,
         * mindestens 8) und wie viele Schlusssätze das nächste Stück wiederholt (Standard 1, 0 ohne Überlappung). Eine
         * Änderung ändert die Prozessorversion; die Seiten werden neu eingebettet.
         */
        public Builder embeddingChunking(int maxTokens, int overlapSentences) {
            KnowledgeTextSegmentation.of(maxTokens, overlapSentences);
            this.chunkTokens = maxTokens;
            this.chunkOverlapSentences = overlapSentences;
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
         * Ob die Schlagwortsuche gefundene Seiten in den Cache lädt, bevor sie dort sucht (ADR 0013); Standard
         * {@code true}. Ohne findet sie nur, was schon im Cache liegt.
         */
        public Builder prefetchSearchHits(boolean prefetchSearchHits) {
            this.prefetchSearchHits = prefetchSearchHits;
            return this;
        }

        /**
         * Höchstens so viele Anfragen schickt die Schlagwortsuche je Quelle für eine Frage (ADR 0013, Fortschreibung):
         * Eine Frage mit mehr als {@value com.aresstack.spaceharvester.application.query.SearchTerms#MAX_QUERY_WORDS}
         * Wörtern geht als ihre stärksten wörtlichen Begriffe und ihre Schlüsselwörter an die Quelle, ein knapper
         * Suchausdruck unverändert. Standard {@value KeywordSearch#DEFAULT_MAX_QUERIES} wie im Enterprise AI Client; 1
         * schickt immer den Suchtext unverändert.
         */
        public Builder discoveryQueries(int maxQueries) {
            if (maxQueries < 1 || maxQueries > KeywordSearch.MAX_QUERIES) {
                throw new IllegalArgumentException("maxQueries muss zwischen 1 und " + KeywordSearch.MAX_QUERIES
                        + " liegen");
            }
            this.discoveryQueries = maxQueries;
            return this;
        }

        /**
         * Wie viele Funde je Stapel die Schlagwortsuche höchstens in den Cache lädt (1 bis
         * {@value KeywordSearch#MAX_LIMIT}, Standard ohne Grenze): in der Rangfolge nach Titel und Auszug, die besten
         * zuerst. Ohne {@link #candidateSelection} heißt das „die besten N“ ({@code top-n}, wie bisher), mit ihr ist es
         * die Obergrenze der gewählten Strategie. Alle Funde erscheinen sofort als vorläufige Treffer
         * ({@link KeywordSearch.Results#found()}); die Suche wartet nicht auf das Laden.
         */
        public Builder searchLoads(int searchLoads) {
            if (searchLoads < 1 || searchLoads > KeywordSearch.MAX_LIMIT) {
                throw new IllegalArgumentException("searchLoads muss zwischen 1 und " + KeywordSearch.MAX_LIMIT
                        + " liegen");
            }
            this.searchLoads = searchLoads;
            this.searchLoadsSet = true;
            return this;
        }

        /**
         * Welche Funde eines Stapels die Schlagwortsuche jetzt lädt (ADR 0016, Punkt 4): {@code ORDER} alle in der
         * Reihenfolge der Quellen, {@code TOP_N} die besten {@link #searchLoads} nach Titel und Auszug, {@code ADAPTIVE}
         * (Standard) so viele, wie der Vordergrund bald schafft ({@link CandidateSelectionStrategy.Adaptive}).
         * {@link #searchLoads} begrenzt jede.
         */
        public Builder candidateSelection(CandidateSelectionStrategy.Kind kind) {
            this.selectionKind = Objects.requireNonNull(kind, "kind");
            this.selection = null;
            return this;
        }

        /** Wie {@link #candidateSelection(CandidateSelectionStrategy.Kind)}, mit einer eigenen Strategie. */
        public Builder candidateSelection(CandidateSelectionStrategy selection) {
            this.selection = Objects.requireNonNull(selection, "selection");
            this.selectionKind = null;
            return this;
        }

        /** Die wirksame Auswahl: eigene, gewählte mit Obergrenze, sonst bei gesetztem {@code searchLoads} top-n. */
        private CandidateSelectionStrategy selection() {
            if (selection != null) {
                return selection;
            }
            if (selectionKind != null) {
                return CandidateSelectionStrategy.of(selectionKind, searchLoads);
            }
            return searchLoadsSet ? CandidateSelectionStrategy.topN(searchLoads)
                    : CandidateSelectionStrategy.of(CandidateSelectionStrategy.Kind.ADAPTIVE, searchLoads);
        }

        /**
         * Ob die Hintergrundfüllung läuft (ADR 0016, Punkt 5; Standard {@code true}): Sie lädt in Leerlaufzeit Seiten,
         * die der Katalog kennt, deren Inhalt aber fehlt oder veraltet ist, zuletzt geänderte zuerst, unabhängig von
         * der Betriebsart ({@code sync.mode}). Die Warteschlange liegt unter {@code <dataDirectory>/cache/fill-queue}
         * und überdauert das Beenden. Mit {@link #configuration} folgt die Füllung {@code sync.background-fill} und
         * {@code sync.sources.<Quelle>.background-fill}, bis dieser Aufruf sie danach fest einstellt.
         */
        public Builder backgroundFill(boolean backgroundFill) {
            this.backgroundFill = backgroundFill;
            this.backgroundFillFixed = configuration != null;
            return this;
        }

        /**
         * So viele Seiten lädt der Hintergrund höchstens zugleich (1 bis {@value BackgroundFill#MAX_PARALLELISM},
         * Standard {@value BackgroundFill#DEFAULT_PARALLELISM}); der Vordergrund behält seine eigenen Plätze. Mit
         * {@link #configuration} gilt {@code sync.background-parallelism}, bis dieser Aufruf sie danach festlegt.
         */
        public Builder backgroundParallelism(int backgroundParallelism) {
            if (backgroundParallelism < 1 || backgroundParallelism > BackgroundFill.MAX_PARALLELISM) {
                throw new IllegalArgumentException("backgroundParallelism muss zwischen 1 und "
                        + BackgroundFill.MAX_PARALLELISM + " liegen");
            }
            this.backgroundParallelism = backgroundParallelism;
            this.backgroundParallelismFixed = configuration != null;
            return this;
        }

        /**
         * Wie lange die Schlagwortsuche für technische Aufrufer auf das Laden ihrer Funde wartet (Standard gar nicht):
         * Was in der Zeit fertig wird, steht schon in den Treffern, der Rest läuft weiter und meldet sich über {@link
         * KeywordSearch.Results#progress()}. Delegierte Benutzer warten wie bisher höchstens fünf Sekunden.
         */
        public Builder searchWait(Duration searchWait) {
            if (searchWait == null || searchWait.isNegative()) {
                throw new IllegalArgumentException("searchWait darf nicht negativ sein");
            }
            this.searchWait = searchWait;
            return this;
        }

        /**
         * Ob der Cross-Encoder des KI-Dienstes (#181) die Funde der Schlagwortsuche nach Titel und Auszug bewertet, um
         * nur die besten in den Cache zu laden (#154, {@link ExcerptPriority}); Standard {@code true},
         * wirksam nur mit {@code rerank-model} oder einem {@link ModelReranker} über {@link #reranker}. Texte gehen nur
         * aus Quellen mit Freigabe für externe Verarbeitung hinaus. Ohne Bewertung laden die ersten in der
         * Reihenfolge der Quellen.
         */
        public Builder excerptRanking(boolean excerptRanking) {
            this.excerptRanking = excerptRanking;
            return this;
        }

        /**
         * Suchlauf-Protokoll (#182), Standard an mit {@link Harvester#DEFAULT_SEARCH_RUN_MAX_AGE}. Ein Lauf enthält den
         * Suchtext (wie im Enterprise AI Client), nie im Log.
         *
         * @param enabled ob Läufe abgelegt werden
         * @param maxAge  so lange bleiben sie, {@code null} ohne Frist (höchstens {@value SearchRunLog#MAX_RUNS} Läufe)
         */
        public Builder searchRuns(boolean enabled, Duration maxAge) {
            if (maxAge != null && (maxAge.isNegative() || maxAge.isZero())) {
                throw new IllegalArgumentException("maxAge muss positiv sein");
            }
            this.searchRuns = enabled;
            this.searchRunMaxAge = maxAge;
            return this;
        }

        /**
         * Wer alle Läufe lesen darf, nicht nur die eigenen (Standard: niemand); durchgereichte Endnutzer nie.
         */
        public Builder searchRunAdministrators(Predicate<AccessContext> administrators) {
            this.searchRunAdministrators = Objects.requireNonNull(administrators, "administrators");
            return this;
        }

        /**
         * Wann eine Seite aus dem Cache ohne Rückfrage bei der Quelle gilt (ADR 0013): Zeitfenster ab dem Abruf und ob
         * danach die Version geprüft oder die Seite neu abgerufen wird. Standard {@link CacheFreshness#DEFAULT}: jeder
         * Abruf prüft die Version.
         */
        public Builder cacheFreshness(CacheFreshness cacheFreshness) {
            this.cacheFreshness = Objects.requireNonNull(cacheFreshness, "cacheFreshness");
            this.freshnessFixed = configuration != null;
            return this;
        }

        /**
         * Wie lange der Abgleich die Rohdaten einer gesperrten Seite behält (ADR 0014): Meldet die Quelle dem technischen
         * Konto „nicht gefunden“, ohne die Löschung zu bestätigen, ist die Seite sofort für jede Ausgabe gesperrt und
         * aus den Indizes genommen; nach dieser Frist entfernt ein Abgleich auch die Rohdaten. Standard
         * {@link SpaceSync#DEFAULT_WITHHELD_RETENTION}.
         */
        public Builder withheldRetention(Duration withheldRetention) {
            Objects.requireNonNull(withheldRetention, "withheldRetention");
            if (withheldRetention.isNegative()) {
                throw new IllegalArgumentException("withheldRetention darf nicht negativ sein");
            }
            this.withheldRetention = withheldRetention;
            this.retentionFixed = configuration != null;
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

    /**
     * Das Chat-Modell der wirksamen Konfiguration für die Suchvarianten (ADR 0017), je Einstellungsstand einmal gebaut;
     * leer ohne Dienst oder Chat-Modell.
     */
    private static final class VariantModels implements Supplier<Optional<QueryVariantModel>> {

        private record Built(AiServiceSettings settings, Optional<QueryVariantModel> model) {
        }

        private final Supplier<AiServiceSettings> settings;
        private final ConfiguredSourceCatalog catalog;
        private final TrustBundles trustBundles;
        private final String userAgent;
        private volatile Built built;

        VariantModels(Supplier<AiServiceSettings> settings, ConfiguredSourceCatalog catalog, TrustBundles trustBundles,
                      String userAgent) {
            this.settings = settings;
            this.catalog = catalog;
            this.trustBundles = trustBundles;
            this.userAgent = userAgent;
        }

        @Override
        public Optional<QueryVariantModel> get() {
            AiServiceSettings current = settings.get();
            Built last = built;
            if (last != null && last.settings() == current) {
                return last.model();
            }
            Optional<QueryVariantModel> model;
            try {
                model = current != null && current.chat()
                        ? Optional.of(RemoteAi.of(current, catalog, trustBundles, userAgent).queryVariants())
                        : Optional.empty();
            } catch (RuntimeException e) {
                // Etwa ein nicht eingerichtetes trust-bundle: dann Regeln, wie ohne Modell.
                model = Optional.empty();
            }
            built = new Built(current, model);
            return model;
        }
    }
}
