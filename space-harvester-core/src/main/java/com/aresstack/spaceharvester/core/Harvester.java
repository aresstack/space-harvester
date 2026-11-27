package com.aresstack.spaceharvester.core;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ConnectionBudget;
import com.aresstack.spaceharvester.application.assets.DownloadDurations;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.DocumentRereads;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.ExtractionStore;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.assets.LargeFileGate;
import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeLog;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.changelog.ManualSyncRounds;
import com.aresstack.spaceharvester.application.changelog.TextContentDiffer;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.check.StatementCheckStore;
import com.aresstack.spaceharvester.application.check.StatementChecks;
import com.aresstack.spaceharvester.application.configuration.ConfigurationChange;
import com.aresstack.spaceharvester.application.configuration.ConfigurationListener;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionStatus;
import com.aresstack.spaceharvester.application.acquisition.BackgroundFill;
import com.aresstack.spaceharvester.application.acquisition.FindingCataloging;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.ExportSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationProblem;
import com.aresstack.spaceharvester.application.configuration.ConfigurationRejectedException;
import com.aresstack.spaceharvester.application.configuration.ChangeLogSettings;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.NetworkSettings;
import com.aresstack.spaceharvester.application.configuration.Swappable;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.EnrichingCacheFill;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.DocumentEnrichments;
import com.aresstack.spaceharvester.application.enrichment.DocumentTexts;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.ReleaseRecomputation;
import com.aresstack.spaceharvester.application.enrichment.VersionRecomputation;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync;
import com.aresstack.spaceharvester.application.enrichment.OutlineProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.TextSegmentation;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.language.ActivityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.EntityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.PropertyProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.RelationProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementProcessor;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementSettings;
import com.aresstack.spaceharvester.application.export.EnrichmentAnalyses;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.export.SelectionDataset;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportPackageImport;
import com.aresstack.spaceharvester.application.export.ExportRequest;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.harvest.SpaceKeyCheck;
import com.aresstack.spaceharvester.application.holdings.Holdings;
import com.aresstack.spaceharvester.application.holdings.HoldingsChanges;
import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.structure.PageConnections;
import com.aresstack.spaceharvester.application.structure.PageLinksDirectory;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.jobcontrol.JobActions;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.passages.DocumentNavigationBuilder;
import com.aresstack.spaceharvester.application.passages.NavigationProcessor;
import com.aresstack.spaceharvester.application.passages.NavigationQueries;
import com.aresstack.spaceharvester.application.passages.OpenNlpSentenceSegmenter;
import com.aresstack.spaceharvester.application.passages.PassageChunks;
import com.aresstack.spaceharvester.application.passages.PassageSegmentation;
import com.aresstack.spaceharvester.application.passages.PassageProcessor;
import com.aresstack.spaceharvester.application.passages.PassagePayload;
import com.aresstack.spaceharvester.application.passages.PassageQueries;
import com.aresstack.spaceharvester.application.passages.PassageSnippets;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.ExcerptPriority;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.SearchExpansion;
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
import com.aresstack.spaceharvester.application.query.progressive.NeighborCandidates;
import com.aresstack.spaceharvester.application.query.progressive.NeighborSearch;
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
import com.aresstack.spaceharvester.application.facts.EditedFactStore;
import com.aresstack.spaceharvester.application.facts.FactEdits;
import com.aresstack.spaceharvester.application.facts.PageFacts;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.searchrun.SearchRuns;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.release.AiRelease;
import com.aresstack.spaceharvester.application.release.PageReleaseService;
import com.aresstack.spaceharvester.application.reading.ReadingViews;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SourceSnapshots;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceDataPurge;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sources.SourceType;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sync.Reconciliation;
import com.aresstack.spaceharvester.application.sync.SpaceSync;
import com.aresstack.spaceharvester.application.sync.ChangeEvents;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.Exclusions;
import com.aresstack.spaceharvester.application.sync.Indexing;
import com.aresstack.spaceharvester.application.sync.SyncBeforeSearch;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.AcronymRelationRule;
import com.aresstack.spaceharvester.persistence.files.FileSynonymVectorCache;
import com.aresstack.spaceharvester.application.glossary.Glossary;
import com.aresstack.spaceharvester.application.glossary.GlossarySynonyms;
import com.aresstack.spaceharvester.application.glossary.GlossaryClassification;
import com.aresstack.spaceharvester.application.terms.AcronymResolution;
import com.aresstack.spaceharvester.application.terms.SynonymCandidates;
import com.aresstack.spaceharvester.application.terms.SynonymDiscovery;
import com.aresstack.spaceharvester.application.configuration.EnrichmentSettings;
import com.aresstack.spaceharvester.application.configuration.TermSettings;
import com.aresstack.spaceharvester.output.convert.AnalysisConverter;
import com.aresstack.spaceharvester.output.convert.DocxConverter;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.output.convert.JsonPackageConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownPackageConverter;
import com.aresstack.spaceharvester.output.convert.PdfConverter;
import com.aresstack.spaceharvester.output.export.FileSystemExportArtifactWriter;
import com.aresstack.spaceharvester.output.export.EnrichmentImportedAssets;
import com.aresstack.spaceharvester.output.export.ZipExportPackageReader;
import com.aresstack.spaceharvester.persistence.files.FileFactEditStore;
import com.aresstack.spaceharvester.persistence.files.FileReadStates;
import com.aresstack.spaceharvester.persistence.files.FileExclusionMarks;
import com.aresstack.spaceharvester.persistence.files.FileIndexMarks;
import com.aresstack.spaceharvester.persistence.files.FileNavigationMarks;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore;
import com.aresstack.spaceharvester.persistence.files.FileInventoryLog;
import com.aresstack.spaceharvester.persistence.files.FileGlossaryStore;
import com.aresstack.spaceharvester.persistence.files.FileResolutionCache;
import com.aresstack.spaceharvester.persistence.files.FileKnowledgeGraphStore;
import com.aresstack.spaceharvester.persistence.files.FileTermRelationStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneEnrichmentStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobEventStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneJobStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneCatalogGaps;
import com.aresstack.spaceharvester.persistence.lucene.LuceneFillQueue;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageReleases;
import com.aresstack.spaceharvester.persistence.lucene.LuceneFindingStore;
import com.aresstack.spaceharvester.persistence.lucene.LuceneHoldingsCatalog;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LuceneLinkIndex;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageLinkPages;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageSearchIndex;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSearchRunLog;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSyncBaselineStore;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.GitSourceAdapter;
import com.aresstack.spaceharvester.source.LocalFilesSourceAdapter;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import com.aresstack.spaceharvester.source.LocalFilesTextProjector;
import com.aresstack.spaceharvester.source.LargeFileTexts;
import com.aresstack.spaceharvester.source.TikaDocumentStructure;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import com.aresstack.spaceharvester.source.SourceAdapter;
import com.aresstack.spaceharvester.source.SourceSecrets;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.ai.RemoteAi;
import com.aresstack.spaceharvester.source.ai.StatementChatModels;
import com.aresstack.spaceharvester.source.ai.ChatAcronymExpansionModel;
import com.aresstack.spaceharvester.source.ai.ChatStepModel;
import com.aresstack.spaceharvester.source.ai.ChatUpdateSummaryModel;
import com.aresstack.spaceharvester.application.simplification.SimplificationStore;
import com.aresstack.spaceharvester.application.simplification.Simplifications;
import com.aresstack.spaceharvester.source.ai.ChatSynonymJudgmentModel;
import com.aresstack.spaceharvester.source.ai.ChatNavigationLabeler;
import com.aresstack.spaceharvester.source.ai.VisionImageDescriber;
import com.aresstack.spaceharvester.application.images.ImageProcessor;
import com.aresstack.spaceharvester.application.images.PageImageAnalysis;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphStore;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerStores;
import com.aresstack.spaceharvester.application.knowledge.ModelStatementAnalyzer;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs;
import com.aresstack.spaceharvester.application.knowledge.export.KnowledgeGraphExportUseCase;
import com.aresstack.spaceharvester.application.metadata.SourceQueryExports;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalyzer;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageLinks;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
    /** Kennung des KI-Schritts der Fristenanalyse (ADR 0035). */
    public static final String DEADLINE_STEP = "deadline-facts";

    /** So lange bleiben Läufe im Suchlauf-Protokoll, wenn nichts anderes eingestellt ist. */
    public static final Duration DEFAULT_SEARCH_RUN_MAX_AGE = Duration.ofDays(30);

    private static final System.Logger LOG = System.getLogger(Harvester.class.getName());
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
    private final Holdings holdings;
    private final AiRelease aiRelease;
    private final PageReleaseService pageReleaseService;
    private final ExportAssetCollection assetCollection;
    private final PageAddresses addresses;
    private final ReadingViews readingViews;
    private final PageNavigation navigation;
    private final PageLinks pageLinks;
    private final PageSearch search;
    private final KeywordSearch keywordSearch;
    private final AcquisitionScheduler acquisition;
    private final BackgroundFill backgroundFill;
    /** Ob Füllung bzw. ihre Parallelität der Konfiguration folgen (ADR 0015), statt im Builder fest zu sein. */
    private final boolean fillFollows;
    private final boolean parallelismFollows;
    private final SearchOrchestrator progressiveSearch;
    private final NeighborSearch neighborSearch;
    private final com.aresstack.spaceharvester.application.query.progressive.TermSearch termSearch;
    private final VariantModels variantModels;
    private final RelevanceSearch relevance;
    private final PageConnections pageConnections;
    private final PassageQueries passages;
    private final NavigationQueries documentNavigation;
    private final TermQueries terms;
    private final SearchRuns searchRuns;
    private final PageFacts facts;
    private final TermRelations termRelations;
    private final AcronymRelationRule acronymRelationRule;
    private FileSynonymVectorCache synonymVectors;
    private final AcronymResolution acronymResolution;
    private final Glossary glossary;
    private final GlossarySynonyms glossarySynonyms;
    private final com.aresstack.spaceharvester.application.glossary.TechnicalTerms technicalTerms;
    private final GlossaryClassification glossaryClassification;
    private final com.aresstack.spaceharvester.application.glossary.GlossaryOverview glossaryOverview;
    private final SynonymDiscovery synonymDiscovery;
    private final FactEdits factEdits;
    private final Jobs jobs;
    /** Seiten-Cache samt Änderungsprotokoll und Update-Marken (ADR 0030). */
    private final LucenePageCache pageCache;
    private final ChangeLog changeLog;
    /** Die Update-Zusammenfassung als Podcast (Angelo 21.11.2026). */
    private final com.aresstack.spaceharvester.application.podcast.UpdatePodcasts podcasts;
    private final com.aresstack.spaceharvester.application.podcast.PagePodcasts pagePodcasts;
    /** Plan und Einzelteile der Schaubilder (Bildgenerierung); {@code null} ohne Anreicherung. */
    private com.aresstack.spaceharvester.application.podcast.DiagramComposer.Models diagramModels;
    private final SpaceSyncJobs syncs;
    private final Indexing indexing;
    private final Exclusions exclusions;
    private final com.aresstack.spaceharvester.application.sync.NavigationMarks navigationMarks;
    private final ChangeEvents changeEvents;
    private final Reconciliation reconciliation;
    /** Bestandsinventur nach {@code sync.inventory-interval}, nur aus der Konfiguration (ADR 0014/0015). */
    private final Reconciliation inventories;
    private final Exports exports;
    private final ExportConversions conversions;
    private final DocumentRereads rereads;
    private final com.aresstack.spaceharvester.application.assets.PageAssetHarvests pageAssetHarvests;
    private final JobActions jobActions;
    /** Hintergrundaufträge (Angelo 21.11.2026, ADR 0025 Nachtrag). */
    private final com.aresstack.spaceharvester.application.background.BackgroundJobs backgroundJobs;
    private final com.aresstack.spaceharvester.application.background.ConnectionChecks connectionChecks;
    private final com.aresstack.spaceharvester.application.background.AttachmentPrefetches attachmentPrefetches;
    private final com.aresstack.spaceharvester.application.network.NetworkState network;
    /** „Seiten nachladen“ als Hintergrundauftrag. */
    private final com.aresstack.spaceharvester.application.background.PageLoads pageLoads;
    private final com.aresstack.spaceharvester.application.background.services.ServicePlanner servicePlanner;
    private final com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs knowledgeGraphJobs;
    private final Assets assets;
    private final com.aresstack.spaceharvester.application.presentation.TranscriptAnalyses transcriptAnalyses;
    private final ConnectionBudget connections;
    private final PageImageAnalysis imageAnalysis;
    /** Bilder automatisch beschreiben (Fahrplan B1); entsteht nach der Anreicherung, die sie als Folgeschritt ruft. */
    private volatile com.aresstack.spaceharvester.application.images.AutomaticImageDescriptions automaticImages;
    private volatile com.aresstack.spaceharvester.application.images.ImageDescriptionJobs imageDescriptions;
    private final com.aresstack.spaceharvester.application.presentation.VideoPresentations videoPresentations;
    private final com.aresstack.spaceharvester.application.quality.SearchQualityRuns searchQuality;
    private final Simplifications simplifications;
    private final com.aresstack.spaceharvester.application.deadlines.DeadlineAnalysisService documentAnalyses;
    private final StatementChecks statementChecks;
    private final StatementAnalysis statementAnalysis;
    private final KnowledgeGraph knowledgeGraph;
    /**
     * Das Löschen der Quellendaten (#1090), sobald es angelegt ist; der Knowledge Graph entsteht vorher und fragt es
     * erst beim Lesen.
     */
    private final java.util.concurrent.atomic.AtomicReference<SourceDataPurge> withdrawals =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison analyzerComparison;
    private final com.aresstack.spaceharvester.application.knowledge.VerbQueries verbQueries;
    private final com.aresstack.spaceharvester.application.standing.StatusQuo statusQuo;
    private final com.aresstack.spaceharvester.application.standing.ReadingAdvice readingAdvice;
    private final StatementAnalysisJobs statementAnalysisJobs;
    private final KnowledgeGraphExportUseCase knowledgeGraphExport;
    private final SourceQueryExports sourceQueryExports;
    private final java.nio.file.Path knowledgeGraphExportDirectory;
    private final SignInRequests signIns;
    private final Enrichments enrichments;
    /** „Neu berechnen“ einer Seite als Auftrag (Fundstelle 21). */
    private final com.aresstack.spaceharvester.application.enrichment.PageRecomputation pageRecomputation;
    private final ConfiguredSourceCatalog catalog;
    private final SourceManagement sourceManagement;
    private final ExportPackageImport packageImport;
    private final TrustBundles trustBundles;
    private final String userAgent;
    /** Die KI-Einstellungen der wirksamen Konfiguration, oder die festen des Builders. */
    private final Supplier<AiServiceSettings> currentAi;
    /** Ob Proxy und Vertrauen ohne Bundle der Konfiguration folgen ({@code network.*}, ADR 0015). */
    private final boolean networkFollows;
    /**
     * Die Einstellung für Dokument-Anhänge im Export (ADR 0023), je Auftrag aus der gerade wirksamen Konfiguration
     * gelesen (ADR 0015); ohne {@link ConfigurationManager} die Standardwerte.
     */
    static java.util.function.Supplier<ExportSettings> exportSettings(ConfigurationManager manager) {
        return () -> manager == null ? ExportSettings.DEFAULT : manager.current().configuration().export();
    }

    /** Die wirksame Konfiguration, wenn der Builder einen {@link ConfigurationManager} bekam (ADR 0015). */
    private volatile HarvesterConfiguration settings;
    /** Textprojektion wie in Anreicherungen und Volltextindex: Confluence, lokale Dateien, Dokumente. */
    private final PageTextProjector pageTexts;
    /** {@code sync.before-search} nur mit Konfiguration; steht, sobald die Abgleiche verdrahtet sind. */
    private volatile SyncBeforeSearch syncBeforeSearch;
    /** Austauschbares Embedding-Modell; {@code null}, wenn es fest eingestellt ist. */
    private final Swappable<EmbeddingGeneration> embeddingModel;
    /** Austauschbarer Reranker; {@code null}, wenn er fest eingestellt ist. */
    private final SwappableReranker liveReranker;
    /** Rechnet nach einem Wechsel der KI-Freigabe neu; {@code null}, wenn die Freigabe fest eingestellt ist. */
    private final ReleaseRecomputation releaseRecomputation;
    /** Die aus der Konfiguration eingeplanten Abgleiche je Space; eigene über {@link #reconcile} gehören nicht dazu. */
    private final Map<String, ConfiguredSchedule> configuredSchedules = new LinkedHashMap<>();
    private final List<ConfiguredSchedule> configuredInventories = new ArrayList<>();

    /**
     * Misst den Start des Core für das Protokoll: jeder Schritt über einer Sekunde mit Namen und Dauer, am Ende die
     * Gesamtdauer. Keine Inhalte, nur Schrittnamen.
     */
    private static final class StartTimer {

        private static final long SLOW_NANOS = TimeUnit.SECONDS.toNanos(1);
        private final long started = System.nanoTime();
        private long last = started;

        void step(String name) {
            long now = System.nanoTime();
            if (now - last >= SLOW_NANOS) {
                LOG.log(System.Logger.Level.INFO, "Start: {0} dauerte {1} ms", name,
                        TimeUnit.NANOSECONDS.toMillis(now - last));
            }
            last = now;
        }

        void done() {
            LOG.log(System.Logger.Level.INFO, "Core gestartet in {0} ms",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    private Harvester(Builder builder) {
        List<AutoCloseable> opened = new ArrayList<>();
        // Abgleiche beim Start als Hintergrundaufträge (ADR 0025 Nachtrag, Punkt 9): erst nach dem Fortsetzen
        // unterbrochener Teile, damit nichts doppelt beginnt.
        List<Runnable> startScans = new ArrayList<>();
        StartTimer start = new StartTimer();
        // F39-10: eine Tika-Extraktion mit den Grenzen aus assets.extraction.* für lokale Dateien, Anhänge, Export
        // und Fristen, statt der gemeinsamen Instanz mit festen Werten.
        TikaDocumentText documentText = new TikaDocumentText(builder.extractionLimits);
        try {
            this.networkFollows = builder.configuration != null && !builder.networkFixed;
            // Folgt das Netzwerk der Konfiguration, liest das Vertrauen ohne Bundle die Regel je Handshake.
            TrustBundles trustBundles = networkFollows
                    ? TrustBundles.reconfigurable(builder.trustBundles, builder.trust)
                    : TrustBundles.withDefault(builder.trustBundles, TrustPolicy.defaultTrust(builder.trust));
            ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(builder.sources, builder.userAgent,
                    trustBundles, builder.proxy, builder.asyncTransport, builder.adapters(),
                    builder.secretProvider, builder.sourceTimeouts);
            opened.add(catalog);
            this.catalog = catalog;
            start.step("Quellen");
            this.trustBundles = trustBundles;
            this.userAgent = builder.userAgent;
            ConfigurationManager manager = builder.configuration;
            this.settings = manager == null ? null : manager.current().configuration();
            // Was der Builder nach configuration() selbst festlegt, bleibt fest; alles andere folgt der Konfiguration.
            boolean liveAi = manager != null && !builder.aiFixed;
            this.currentAi = () -> liveAi ? this.settings.ai() : builder.ai;
            Path cacheDirectory = builder.dataDirectory.resolve("cache");
            LucenePageCache cache = LucenePageCache.open(cacheDirectory.resolve("pages"), builder.maxPageBytes);
            opened.add(cache);
            this.pageCache = cache;
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
            // Hintergrundaufträge (ADR 0025 Nachtrag): was die App von sich aus anstößt, nachrangig und nach einem
            // Neustart fortgesetzt. Früh, damit sich jeder Baustein mit seiner Art anmelden kann.
            this.backgroundJobs = new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, OWNER);
            // Fundstelle 17: eine Verbindungsprüfung, die länger als eine Sekunde dauert, als Hintergrundteil.
            this.connectionChecks = new com.aresstack.spaceharvester.application.background.ConnectionChecks(
                    backgroundJobs);
            // Fundstelle 18: die übrigen Anhänge einer geöffneten Art je Quelle und Space als Hintergrundteil.
            // Angelo 22.11.2026: ohne Netz warten statt scheitern; ein Netzzustand für alle Netz-Aufträge.
            this.network = new com.aresstack.spaceharvester.application.network.NetworkState();
            opened.add(network);
            // jobs.max-waiting-prefetches, wirkt beim Start.
            this.attachmentPrefetches = new com.aresstack.spaceharvester.application.background.AttachmentPrefetches(
                    backgroundJobs, builder.jobLimits.maxWaitingPrefetches());
            opened.add(attachmentPrefetches);
            attachmentPrefetches.network(network);

            Clock clock = Clock.systemUTC();
            LuceneEnrichmentStore enrichmentStore = LuceneEnrichmentStore.open(cacheDirectory.resolve("enrichments"));
            opened.add(enrichmentStore);
            // ADR 0028: die für die KI freigegebenen Seiten neben dem Cache, als Hash-Menge im Speicher.
            LucenePageReleases pageReleases = LucenePageReleases.open(cacheDirectory.resolve("page-releases"));
            opened.add(pageReleases);
            this.aiRelease = new AiRelease(sourceId -> (liveAi ? this.settings.ai() : builder.ai)
                    .mayProcessExternally(sourceId), pageReleases);
            // #182: Suchlauf-Protokoll neben der Diagnoseablage, nur Lucene.
            SearchRunLog runLog = SearchRunLog.NONE;
            if (builder.searchRuns) {
                LuceneSearchRunLog lucene = LuceneSearchRunLog.open(cacheDirectory.resolve("search-runs"),
                        builder.searchRunMaxAge, clock);
                opened.add(lucene);
                runLog = lucene;
            }
            this.searchRuns = new SearchRuns(runLog, builder.searchRunAdministrators);
            start.step("Ablagen öffnen und Aufträge übernehmen");
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
                // ADR 0028: auch die Seiten der Liste freigegebener Seiten, gefragt bei jeder Seite.
                embedding = embedding.withPassages(new PassageChunks(new PassageSegmentation(sentences,
                        sentenceRules))).withPageReleases(pageReleases::contains);
            }
            this.embeddingModel = model;
            if (embedding != null || builder.statements.enabled()) {
                // Auch die Satzanalyse (#108) rechnet über das Netz: nie im Abgleich selbst.
                ExecutorService started = Enrichments.background(1000);
                background = started;
                opened.add(() -> {
                    Enrichments.stop(started, Duration.ofSeconds(10));
                });
            }
            // Akronyme mit denselben Satzgrenzen wie die Absätze (EAC: OpenNLP, sonst Splitter).
            AcronymProcessor acronyms = new AcronymProcessor(sentences);
            TermProcessor termProcessor = new TermProcessor(acronyms);
            List<EnrichmentProcessor> processors = new ArrayList<>(List.of(acronyms,
                    new OutlineProcessor(), new EntityProcessor(), new PropertyProcessor(), new ActivityProcessor(),
                    new RelationProcessor(), termProcessor));
            processors.addAll(builder.additionalProcessors);
            builder.snapshotProcessors.forEach(factory -> processors.add(factory.apply(cache::find)));
            if (embedding != null) {
                processors.add(embedding);
            }
            // #181: semantische Absätze, Satzvektoren vom selben Modell wie embedding, sonst nach Struktur.
            processors.add(new PassageProcessor(sentences, sentenceRules, embedding));
            // #181: Dokumentnavigation aus den Embeddings je Absatz, ohne sie nur die Überschriften (ADR 0020).
            // Themenabschnitte benennt das Chat-Modell der wirksamen Konfiguration (ChatSectionLabeler), nur für
            // freigegebene Quellen und nur in der Anreicherung.
            AiServiceSettings startAi = builder.ai;
            DocumentNavigationBuilder navigationBuilder = new DocumentNavigationBuilder();
            processors.add(new NavigationProcessor(navigationBuilder, embedding, enrichmentStore::find,
                    enrichmentStore::find, new ChatNavigationLabeler(() -> liveAi ? this.settings.ai() : startAi, catalog, trustBundles,
                            builder.userAgent)));
            // ADR 0021: Bildbeschreibung über das Vision-Modell der wirksamen Konfiguration, nur auf Abruf; der
            // Prozessor images rechnet nie selbst, er bestimmt nur, wann ein Ergebnis gilt.
            ImageDescriber describer = builder.imageDescriber != null ? builder.imageDescriber
                    : new VisionImageDescriber(() -> liveAi ? this.settings.ai() : startAi, catalog, trustBundles,
                            builder.userAgent);
            processors.add(new ImageProcessor(() -> describer));
            Predicate<String> statementsReleased = null;
            StatementProcessor statementProcessor = null;
            if (builder.statements.enabled()) {
                // #108: Satzanalyse über das Chat-Modell (ai.chat-model) der wirksamen Konfiguration, zusätzlich zu
                // entities/activities/relations, im Hintergrund und nur für Quellen in external-processing-sources.
                StatementSettings statementSettings = builder.statements;
                StatementChatModels chats = new StatementChatModels(() -> liveAi ? this.settings.ai() : startAi,
                        catalog, trustBundles, builder.userAgent);
                statementsReleased = chats.mayProcessExternally();
                statementProcessor = new StatementProcessor(sentences, () -> statementSettings, chats,
                        statementsReleased, pageReleases::contains);
                processors.add(statementProcessor);
            }
            // Auch lokale Dateien und Quellen über discover/load (#187) bekommen Anreicherungen und Embeddings, wie im
            // Volltextindex.
            TikaDocumentStructure documentStructure = new TikaDocumentStructure(builder.extractionLimits.parallel(),
                    ExtractionStore.DEFAULT_SECTION_CHARS);
            DocumentExtractions structuredDocuments = new DocumentExtractions(documentStructure,
                    new FileSystemExtractionStore(builder.assetDirectory()), builder.extractionLimits);
            PageTextProjector projector = PageTextProjector.firstOf(List.of(new ConfluenceStorageTextProjector(),
                    new LocalFilesTextProjector(documentText), new KnowledgeDocumentTextProjector(),
                    new com.aresstack.spaceharvester.source.LocalReferenceTextProjector(structuredDocuments),
                    // Angelo 20.11.2026: Videoseiten ohne Text lesen ihr Transkript, die letzte Korrektur zuerst; die
                    // Ablage entsteht erst weiter unten.
                    new com.aresstack.spaceharvester.application.presentation.VideoTranscriptTextProjector(
                            this::assets)));
            this.enrichments = new Enrichments(projector, processors, enrichmentStore, clock, background);
            enrichments.localReader(new com.aresstack.spaceharvester.application.enrichment.LocalReaderAnalyses(
                    new com.aresstack.spaceharvester.application.enrichment.LocalReadingText(structuredDocuments),
                    enrichments, navigationBuilder));
            // Angelo 22.11.2026: Auch die KI-Anreicherung rechnet so breit, wie ai.parallel erlaubt, gelesen je Teil.
            enrichments.parallel(() -> {
                AiServiceSettings ai = currentAi.get();
                return ai == null ? 1 : ai.parallel();
            });
            enrichments.network(network);
            if (background != null) {
                // ADR 0025 Nachtrag, Punkt 10: die Warteschlange der Netz-Prozessoren als „KI-Anreicherung“ je Quelle;
                // schließt nach den Komponenten, die einreihen, und vor der Ablage.
                Enrichments hostedEnrichments = enrichments;
                // Angelo 22.11.2026: Die Warteschlange überlebt den Neustart (nur Schlüssel, nie Text).
                hostedEnrichments.background(backgroundJobs, cache::find,
                        new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(
                                cacheDirectory.resolve("enrichment-queue")));
                opened.add(() -> hostedEnrichments.closeBackground(Duration.ofSeconds(10)));
            }
            this.pageRecomputation = new com.aresstack.spaceharvester.application.enrichment.PageRecomputation(jobs,
                    enrichments);
            this.pageTexts = projector;
            // ADR 0015: Wechselt die Freigabe einer Quelle, rechnen embedding, passages und navigation ihre Seiten im
            // Cache neu, eingeschaltet auch statements (#108); images rechnet nur auf Abruf und prüft die Freigabe dann
            // selbst. Schließt vor dem Hintergrund. Auch ohne beide Modelle: Die Rücknahme der Bildbeschreibungen
            // (ADR 0028) läuft über ihn, auch wenn nur ein Vision-Modell eingestellt ist.
            {
                List<String> releaseDependent = new ArrayList<>();
                if (model != null) {
                    releaseDependent.addAll(List.of(embedding.id(), PassageProcessor.ID, NavigationProcessor.ID));
                }
                if (statementsReleased != null) {
                    releaseDependent.add(StatementProcessor.ID);
                }
                // Ohne Embedding-Modell tauscht die Generation nicht, covers bliebe beim alten Stand; die Satzanalyse
                // fragt die Freigabe darum selbst.
                EmbeddingProcessor embeddings = model != null ? embedding : null;
                Predicate<String> statementsFollow = statementsReleased;
                AiRelease release = this.aiRelease;
                Predicate<String> released = embeddings == null && statementsFollow == null ? release::sourceReleased
                        : sourceId -> embeddings != null && embeddings.covers(sourceId)
                                || statementsFollow != null && statementsFollow.test(sourceId);
                ReleaseRecomputation recomputation = new ReleaseRecomputation(enrichments,
                        cache::snapshots, released, releaseDependent);
                opened.add(recomputation);
                // ADR 0028: Der Abgleich beim Start fragt je Seite auch die Liste freigegebener Seiten.
                recomputation.pageReleases(pageReleases::contains);
                // Was die Freigabe verpasste, während der Dienst nicht lief oder ein Lauf abbrach, gleicht der Start
                // einmal ab, ohne den Start aufzuhalten; mit Satzanalyse auch nur mit Chat-Modell. Alle Läufe sind
                // Hintergrundaufträge „KI-Freigabe prüfen“, „KI-Freigabe nachziehen“, „Seitenfreigabe nachziehen“.
                StatementProcessor statementsOnStart = statementProcessor;
                java.util.function.BooleanSupplier releaseModel = () -> embeddings != null && embeddings.available()
                        || statementsOnStart != null && statementsOnStart.available();
                recomputation.background(backgroundJobs, cache::find, releaseModel,
                        PassagePayload.REASON_NOT_APPROVED, cache::pageCount);
                List<String> releaseSources = catalog.configs().stream().map(SourceSettingsConfig::id).toList();
                startScans.add(() -> recomputation.reconcileOnStart(releaseSources, releaseModel,
                        PassagePayload.REASON_NOT_APPROVED));
                // ADR 0028: Eintragen rechnet mit Modell, Entfernen ohne und löscht die KI-Ergebnisse; nur Seiten,
                // deren Quelle nicht als Ganzes freigegeben ist, sonst ändert sich nichts.
                opened.add(pageReleases.subscribe(change -> recomputation.recomputePages(
                        release.outsideReleasedSources(change.added()),
                        release.outsideReleasedSources(change.removed()), cache::find)));
                this.releaseRecomputation = recomputation;
            }
            // Ergebnisse lokaler Prozessoren einer älteren Version (etwa nach geänderter Akronymerkennung) rechnet der
            // Start einmal aus dem Cache neu, gedrosselt und ohne den Start aufzuhalten, als Hintergrundauftrag
            // „Analysen nach Update nachrechnen“.
            VersionRecomputation versions = new VersionRecomputation(enrichments, cache::snapshots);
            opened.add(versions);
            start.step("Anreicherung");
            versions.background(backgroundJobs, cache::pageCount);
            List<String> versionSources = catalog.configs().stream().map(SourceSettingsConfig::id).toList();
            startScans.add(() -> versions.reconcileOnStart(versionSources));
            // #180: Volltext über den Cache, abgeleitet und neu aufbaubar.
            LucenePageTextIndex textIndex = LucenePageTextIndex.open(cacheDirectory.resolve("text"));
            opened.add(textIndex);
            PageTextIndexing texts = new PageTextIndexing(List.of(new ConfluenceStorageTextProjector(),
                    new LocalFilesTextProjector(documentText), new KnowledgeDocumentTextProjector()), textIndex,
                    new com.aresstack.spaceharvester.application.query.LocalPageSections(structuredDocuments));

            this.access = new AccessGuard(builder.accessPolicy);
            var dependentEnrichments = new com.aresstack.spaceharvester.application.enrichment.DependentEnrichments(
                    enrichments, cache, backgroundJobs, sourceId -> catalog.find(sourceId).isPresent()
                            && access.permitsSourceWide(AccessContext.technicalClient(OWNER), sourceId, AccessKind.CONTENT));
            // ADR 0027: Bestand je Space; jeder Commit des Cache meldet ihm seine Änderungen. Schließt vor dem Cache.
            LuceneHoldingsCatalog holdingsCatalog = new LuceneHoldingsCatalog(cache);
            this.holdings = new Holdings(catalog, holdingsCatalog, access, builder.maxTreeNodes);
            holdings.localIndex(textIndex);
            textIndex.holdingsChanges(holdings.localIndexChanges());
            opened.add(holdings);
            // Paket V1: Verzeichnis der Verweise aus den gespeicherten Seiten; hört wie der Bestand auf jeden Commit.
            LuceneLinkIndex linkIndex = LuceneLinkIndex.open(cacheDirectory.resolve("links"));
            opened.add(linkIndex);
            this.pageLinks = new PageLinks(new LucenePageLinkPages(cache), linkIndex,
                    List.of(new ConfluenceStorageLinks()), access);
            opened.add(pageLinks);
            // Abgleich beim Start als Hintergrundauftrag „Verweise abgleichen“; nach einem Abgleich eines Space und
            // für einzelne Seiten bleibt es beim Bündler.
            pageLinks.background(backgroundJobs);
            List<String> linkSources = catalog.configs().stream().map(SourceSettingsConfig::id).toList();
            startScans.add(() -> pageLinks.reconcileOnStart(linkSources));
            // ADR 0028: Eintragen aus der Auswahl, gebündelte Liste und Entfernen für die Oberfläche.
            AtomicReference<DocumentEnrichments> sectionTexts = new AtomicReference<>();
            this.pageReleaseService = new PageReleaseService(pageReleases, holdings, access, cache::findHeader,
                    key -> {
                        Optional<PageSnapshot> snapshot = cache.find(key);
                        if (snapshot.isPresent() && com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                                .recognizes(snapshot.get().payload())) {
                            DocumentEnrichments sections = sectionTexts.get();
                            return sections != null && sections.hasCurrentLocalAnalysis(snapshot.get(), "embedding");
                        }
                        return enrichmentStore.find(key).stream().anyMatch(found -> "embedding".equals(found.processorId())
                                && found.status() == Enrichment.Status.OK);
                    }, clock);
            LucenePageCacheReader reader = new LucenePageCacheReader(cache);
            LucenePageSearchIndex index = new LucenePageSearchIndex(cache, builder.searchTimeout);
            this.snapshots = builder.localOriginalReferences
                    ? new SourceSnapshots(catalog, new com.aresstack.spaceharvester.application.snapshot.LocalOriginalStorage(
                            new com.aresstack.spaceharvester.application.assets.DirectDocumentExtractions(structuredDocuments),
                            LocalFilesTextProjector::readable))
                    : new SourceSnapshots(catalog);
            // Größere Dateien nimmt der Cache nicht an: gar nicht erst herunterladen, nur die Kopfdaten ablegen.
            long maxPageBytes = builder.maxPageBytes;
            snapshots.maxBytes(() -> maxPageBytes);
            // Für sie den Textauszug statt des Originals, bis zu dieser Größe: Suche und Text-Ansicht brauchen nur ihn.
            // Das Original kommt dafür auf die Platte und wird als Strom gelesen (ADR 0026), wie große Anhänge.
            long maxTextFileBytes = builder.maxTextFileBytes;
            snapshots.maxTextBytes(() -> maxTextFileBytes);
            LargeFileTexts largeFileTexts = new LargeFileTexts(documentStructure, () -> builder.extractionLimits,
                    cacheDirectory.resolve("large-files"));
            snapshots.largeFileText(largeFileTexts::text);
            // ADR 0027: jeder Inhaltsabruf bei der Quelle (Planer, Lesen, Abgleich, Export) meldet dem Bestand „lädt“.
            snapshots.loads(holdings);
            this.sources = new SourceQueries(catalog);
            this.spaceKeys = new SpaceKeyCheck(sources);
            this.freshness = new PageFreshness(sources, snapshots, clock, builder.checkTimeout,
                    builder.largeFileTimeout);
            this.pages = new PageQueries(catalog, reader, access, freshness,
                    new EnrichingCacheFill(PageCacheFill.of(cache::state, cache::trySave, cache::saveWaiting),
                            enrichments, texts, snapshot -> {
                                dependentEnrichments.changed(snapshot.key().sourceId());
                                DocumentEnrichments sections = sectionTexts.get();
                                if (sections != null) {
                                    sections.indexedLocal(snapshot);
                                }
                            }),
                    builder.maxTreeNodes, freshnessRule(builder), clock, builder.queryLimits);
            this.addresses = new PageAddresses(catalog, access, pages);
            // ADR 0026, AP9a: Lesetexte der Abschnitte von Anhängen, sobald die Analysen je Dokument stehen (unten).
            DocumentTexts documentTexts = (document, section, inputSha256, extraction) -> sectionTexts.get() == null
                    ? Optional.empty() : sectionTexts.get().sectionText(document, section, inputSha256, extraction);
            this.passages = new PassageQueries(pages, enrichments, projector, documentTexts);
            this.documentNavigation = new NavigationQueries(pages, enrichments, projector, navigationBuilder);
            this.readingViews = new ReadingViews(catalog, pages, access, new ReadingViewStore(enrichmentStore), clock);
            if (jobs != null) {
                // Fundstelle 15, Abnahme #1080: Vorladen beim Aufklappen als Klick-Auftrag „Lese-Ansichten vorladen“.
                readingViews.jobs(jobs);
            }
            // ADR 0016: Funde der Quellensuche technischer Aufrufer als Kopfdaten in den Katalog, im Hintergrund; die
            // Füllung erfährt die neuen Lücken, auch in Spaces ohne bestätigte Generation.
            AtomicReference<BackgroundFill> filling = new AtomicReference<>();
            FindingCataloging findings = new FindingCataloging(catalog, access, new LuceneFindingStore(cache),
                    gaps -> {
                        BackgroundFill fill = filling.get();
                        if (fill != null) {
                            fill.offer(gaps);
                        }
                    }, clock, sourceId -> manager != null && !builder.backgroundFillFixed
                            ? this.settings.syncFor(sourceId).backgroundFill() : builder.backgroundFill);
            opened.add(findings);
            if (backgroundJobs != null) {
                // Fundstelle 14: Funde katalogisieren als Hintergrundart „Funde katalogisieren“.
                findings.background(backgroundJobs);
            }
            this.navigation = new PageNavigation(catalog, reader, access, findings, pageLinks);
            this.search = new PageSearch(catalog, index, access);
            // #119: Begriffsindex in der Anreicherungsablage, Rechte je Seite wie die Suche.
            this.terms = new TermQueries(catalog, enrichmentStore, access, cache::findHeader, cache::find, projector,
                    termProcessor.version(), documentTexts, builder.queryLimits);
            // Die Verwalter der Begriffszahlen hören wie Bestand und Verweise auf jeden Commit des Seiten-Caches.
            // Die gemerkten Verben je Seite (VerbQueries) entstehen erst später; bis dahin geht die Meldung ins Leere.
            java.util.concurrent.atomic.AtomicReference<Runnable> verbsStale =
                    new java.util.concurrent.atomic.AtomicReference<>(() -> { });
            HoldingsChanges cacheChanges = HoldingsChanges.all(holdings, pageLinks, terms.managedTotals())
                    .andThen(() -> verbsStale.get().run());
            cache.changes(cacheChanges);
            FileResolutionCache resolutionCache = new FileResolutionCache(builder.dataDirectory.resolve("term-relations"));
            this.termRelations = new TermRelations(catalog,
                    new FileTermRelationStore(builder.dataDirectory.resolve("term-relations")), access,
                    cache::findHeader, clock, enrichmentStore, termProcessor.version());
            this.acronymRelationRule = new AcronymRelationRule(catalog, enrichmentStore, access, cache::findHeader,
                    termRelations, termProcessor.version(), clock, cache::find,
                    new com.aresstack.spaceharvester.application.enrichment.LocalReadingText(structuredDocuments));
            // #122: KI-Akronymauflösung über das Chat-Modell der wirksamen Konfiguration, nur für freigegebene Quellen.
            ChatAcronymExpansionModel chatModel = new ChatAcronymExpansionModel(
                    () -> liveAi ? this.settings.ai() : startAi, catalog, trustBundles, builder.userAgent);
            this.acronymResolution = new AcronymResolution(catalog, enrichmentStore, access, cache::findHeader,
                    key -> cache.find(key).flatMap(projector::project), termRelations, chatModel, resolutionCache,
                    jobs, termProcessor.version(), clock);
            // #122 Stufe 2: Fachglossar als Nutzerdaten neben den Beziehungen; KI nur auf Knopfdruck, dasselbe Modell.
            this.glossary = new Glossary(catalog, new FileGlossaryStore(builder.dataDirectory.resolve("glossary")),
                    access, cache::findHeader, clock);
            // Geprüfte Glossare (Angelo 23.11.2026) beim Start übernehmen, nur mit terms.glossary.approved.
            if (builder.terms.glossaryApproved()) {
                com.aresstack.spaceharvester.application.glossary.ApprovedGlossary.bundled().forEach(glossary::adopt);
            }
            // K5b: Suchmethode „Nur Fachbegriffe“ über Glossar und Synonymbeziehungen, nichts gespeichert.
            // Reihenfolge wie im Reiter „Fachglossar“: Wichtigkeit aus der Übersicht (entsteht weiter unten, gefragt
            // wird erst bei der Suche), je Bereich gehalten bis zur nächsten Aussagenanalyse.
            this.technicalTerms = new com.aresstack.spaceharvester.application.glossary.TechnicalTerms(glossary,
                    termRelations, (caller, scope) -> technicalImportance(caller, scope));
            // ADR 0032, FL5b: erste Phase „Begriffe ableiten“ aus den Aussagenanalysen, ohne Modell; Regeln aus
            // glossary.rules.* der wirksamen Konfiguration.
            TermSettings glossaryTerms = builder.terms;
            Harvester self = this; // der Wissensgraph entsteht weiter unten, gefragt wird erst im Auftrag
            // D4: Modellantworten des Glossars dauerhaft im Datei-Cache wie bei den Synonymen.
            FileResolutionCache glossaryCache = new FileResolutionCache(builder.dataDirectory.resolve("glossary")
                    .resolve(GlossaryClassification.CACHE_DIRECTORY));
            this.glossaryClassification = new GlossaryClassification(catalog, enrichmentStore, access,
                    cache::findHeader, key -> cache.find(key).flatMap(projector::project), glossary, chatModel,
                    glossaryCache, jobs, termProcessor.version(), GlossaryClassification.Budget.DEFAULT,
                    who -> self.knowledgeGraph.analysed(who),
                    () -> (this.settings != null ? this.settings.terms() : glossaryTerms).glossaryRules(),
                    this.aiRelease::mayProcess, clock);
            // D4: „ohne aktuelle Aussagenanalyse“ zählt auch nie analysierte Seiten des Bereichs, Rechte des Aufrufers.
            Holdings glossaryHoldings = this.holdings;
            this.glossaryClassification.localPages((who, scope) -> glossaryHoldings.resolve(who, scope.global()
                    ? new com.aresstack.spaceharvester.application.holdings.Selection(catalog.all().stream()
                    .map(source -> com.aresstack.spaceharvester.application.holdings.Selection.Root.source(
                            source.id())).toList())
                    : com.aresstack.spaceharvester.application.holdings.Selection.of(
                    new com.aresstack.spaceharvester.application.holdings.Selection.Root(scope.sourceId(),
                            scope.spaceKey(), null))).pages());
            // D4.2: eine Seite samt Unterseiten mit den Rechten des Aufrufers.
            this.glossaryClassification.subtrees((caller, sourceId, spaceKey, pageId) -> holdings.resolve(caller,
                    Selection.of(Selection.Root.subtree(sourceId, spaceKey, pageId))).pages());
            // #336: Synonymerkennung auf Knopfdruck; Embedding- und Chat-Modell folgen der Konfiguration, Text nur
            // aus freigegebenen Quellen, Budget aus terms.synonyms.*.
            EmbeddingProcessor embeddingForTerms = embedding;
            ChatSynonymJudgmentModel judge = new ChatSynonymJudgmentModel(() -> liveAi ? this.settings.ai() : startAi,
                    catalog, trustBundles, builder.userAgent);
            FileResolutionCache synonymCache = new FileResolutionCache(
                    builder.dataDirectory.resolve("term-relations").resolve("synonyms"));
            TermSettings startTerms = builder.terms;
            SynonymCandidates synonymCandidates = new SynonymCandidates(catalog, enrichmentStore,
                    cache::findHeader, key -> cache.find(key).flatMap(projector::project), termRelations,
                    () -> embeddingForTerms == null ? Optional.empty() : embeddingForTerms.currentProvider(),
                    judge::mayProcess, termProcessor.version());
            // SY2: Vektoren und Stand je Quelle dauerhaft neben dem Ergebnis-Cache.
            FileSynonymVectorCache synonymVectors = new FileSynonymVectorCache(
                    builder.dataDirectory.resolve("term-relations").resolve("synonyms"));
            synonymCandidates.vectors(synonymVectors);
            // K3: Synonyme wahlweise nur aus bestätigten Fachbegriffen des Glossars.
            Glossary synonymGlossary = this.glossary;
            synonymCandidates.glossaryTerms(scope -> synonymGlossary.confirmedTerms(scope.relationScope()));
            this.synonymVectors = synonymVectors;
            this.synonymDiscovery = new SynonymDiscovery(catalog, synonymCandidates, access, termRelations, judge,
                    synonymCache, jobs,
                    () -> (this.settings != null ? this.settings.terms() : startTerms).synonyms(), clock);
            synonymDiscovery.pageReleases(pageReleases::contains);
            // D4.4: Der automatische Lauf gehört wie die übrigen Hintergrundaufträge dem Core.
            synonymDiscovery.owner(OWNER);
            // Der automatische Lauf ist ein Hintergrundauftrag (Angelo 21.11.2026: nichts läuft unsichtbar).
            synonymDiscovery.background(backgroundJobs);
            // SY1: Neue KI-Freigaben von Seiten lösen den automatischen Lauf ihrer Quelle aus.
            opened.add(pageReleases.subscribe(synonymDiscovery::releasesChanged));
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
                    null, AcquisitionScheduler.DEFAULT_RESUME_DELAY, (sourceId, trigger) -> {
                        if (trigger == ChangeTrigger.SEARCH) {
                            return manager != null && !builder.prefetchSearchHitsFixed
                                    ? this.settings.sync().override(sourceId).effectivePrefetch(this.settings.search().prefetchHits())
                                    : builder.prefetchSearchHits;
                        }
                        if (trigger == ChangeTrigger.BACKGROUND) {
                            return fillFollows ? this.settings.syncFor(sourceId).backgroundFill() : builder.backgroundFill;
                        }
                        return true;
                    });
            opened.add(scheduler);
            // Angelo 26.11.2026 (WLAN): vorübergehend gescheiterte Seiten seitenweise erneut anfordern statt scheitern,
            // nach jobs.retry.*; der Vordergrund wartet ohne Netz, bis es zurück ist.
            com.aresstack.spaceharvester.application.jobs.RetrySettings acquisitionRetry = builder.jobLimits.retry();
            scheduler.retry(() -> acquisitionRetry);
            scheduler.network(network);
            this.acquisition = scheduler;
            this.pageLoads = new com.aresstack.spaceharvester.application.background.PageLoads(backgroundJobs,
                    scheduler, AccessContext.technicalClient(OWNER),
                    key -> texts.cachedVersion(key, cache::findHeader, cache::find).isPresent(),
                    key -> cache.findHeader(key).map(header -> header.metadata().partitionKey()));
            // K4: Fachbegriffe nach der Anreicherung als Hintergrundauftrag, nur mit terms.glossary.automatic.
            TermSettings automaticTerms = builder.terms;
            this.glossaryClassification.background(backgroundJobs, AccessContext.technicalClient(OWNER),
                    () -> (this.settings != null ? this.settings.terms() : automaticTerms).glossaryAutomatic());
            SearchHitPrefetch prefetch = null;
            // ADR 0015: Folgt das Vorabladen der Konfiguration, gibt es den Lader immer; ob ein Stapel lädt, liest die
            // Suche je Stapel aus search.prefetch-hits.
            boolean prefetchFollows = manager != null && !builder.prefetchSearchHitsFixed;
            if (builder.prefetchSearchHits || prefetchFollows) {
                // ADR 0013: gefundene Seiten mit den Rechten des Aufrufers in den Cache laden, dann dort suchen.
                prefetch = new SearchHitPrefetch(key -> texts.cachedVersion(key, cache::findHeader, cache::find),
                        key -> cache.findHeader(key).map(PageHeader::metadata), scheduler,
                        SearchHitPrefetch.DEFAULT_WAIT, builder.searchWait);
                // Fundstelle 13: Was nach der Rückkehr der Suche weiterlädt, steht als „Suchtreffer laden“ in der Liste.
                com.aresstack.spaceharvester.application.background.SearchHitLoads hitLoads =
                        new com.aresstack.spaceharvester.application.background.SearchHitLoads(backgroundJobs,
                                scheduler, AccessContext.technicalClient(OWNER),
                                key -> cache.findHeader(key).map(header -> header.metadata().partitionKey()));
                opened.add(hitLoads);
                // Ohne Netz steht der Teil als „Wartet auf Netzverbindung“; der Planer wartet mit den Abrufen.
                hitLoads.network(network);
                prefetch.hitLoads(hitLoads);
            }
            SearchReranker reranker = builder.reranker;
            SwappableReranker swappable = null;
            if (reranker == SearchReranker.NONE && liveAi) {
                // ADR 0015: Modell und Option „umordnen“ wechseln zur Laufzeit; ohne beides ordnet nichts um.
                swappable = new SwappableReranker(modelReranker(settings, catalog, trustBundles, builder.userAgent,
                        pageReleases::contains));
                reranker = swappable;
            } else if (reranker == SearchReranker.NONE && builder.ai.reranking()) {
                // #181: Cross-Encoder des Dienstes, Texte nur aus freigegebenen Quellen.
                reranker = new ModelReranker(builder.ai.rerankModel(), RemoteAi.of(builder.ai, catalog, trustBundles,
                        builder.userAgent).reranker(), builder.ai::mayProcessExternally)
                        .withPageReleases(pageReleases::contains);
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
            // K5a: vorgeschlagene Synonyme bestätigter Fachbegriffe, nur mit der Suchmethode „Synonyme der Fachbegriffe“.
            this.glossarySynonyms = new GlossarySynonyms(glossary, termRelations);
            this.keywordSearch = new KeywordSearch(catalog, access,
                    new PassageSnippets(textIndex, enrichmentStore::find), prefetch, builder.discoveryQueries,
                    excerpts, builder.selection(), runLog, findings,
                    sourceId -> prefetchFollows
                            ? this.settings.sync().override(sourceId).effectivePrefetch(this.settings.search().prefetchHits())
                            : builder.prefetchSearchHits, sourceIds -> {
                        SyncBeforeSearch before = this.syncBeforeSearch;
                        if (before != null) {
                            before.searching(sourceIds);
                        }
                    }, SearchExpansion.typed(termRelations::counterparts), glossarySynonyms.expansion(),
                    sourceId -> fillFollows ? this.settings.syncFor(sourceId).backgroundFill() : builder.backgroundFill);
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
                // Fundstelle 10: Mit Hintergrundaufträgen sichtbar als „Veraltete Treffer nachladen“.
                PageRenewal visible = new PageRenewal(pages, renewals, pageLoads, keywordSearch::allowsPrefetch);
                opened.add(visible);
                renewal = visible;
            }
            // ADR 0013, Punkt 3: mit Haken erst finden, laden und fehlende Embeddings berechnen.
            SemanticPreparation preparation = embedding == null || prefetch == null ? null
                    : new SemanticPreparation(keywordSearch, key -> reader.page(key).map(CachedPage::snapshot),
                    enrichments, embedding.id(), SemanticPreparation.DEFAULT_WAIT, jobs,
                    (snapshot, processorId, deadline) -> {
                        DocumentEnrichments sections = sectionTexts.get();
                        return sections != null && sections.awaitLocal(snapshot, processorId, deadline);
                    });
            // Angelo 24.11.2026: Aufträge „Suchtreffer vorbereiten“ entstehen nicht mehr; unterbrochene alte entfallen.
            SemanticPreparation.forgetInterrupted(jobs);
            // ADR 0026, AP9a: auch in den Vektoren der Anhänge, solange enrichment.documents.enabled gilt.
            EnrichmentSettings searchEnrichment = builder.enrichment;
            this.relevance = new RelevanceSearch(catalog, index, access, enrichmentStore, enrichmentStore, embedding,
                    renewal, reranker, textIndex, preparation, runLog,
                    () -> (this.settings != null ? this.settings.enrichment() : searchEnrichment).documents(),
                    documentTexts);
            // Paket G1: Strukturgraph aus lokalem Index, Verzeichnis der Verweise, Begriffen und gespeicherten Vektoren.
            this.pageConnections = new PageConnections(catalog, index, access, PageLinksDirectory.of(pageLinks), terms,
                    termRelations, embedding == null ? null
                    : new PageConnections.Vectors(enrichmentStore, enrichmentStore, embedding));
            // #182: Fakten aus den Anreicherungen mit den manuellen und geprüften (außerhalb des Caches), Rechte je
            // Seite an ihrem Space im Cache.
            FileFactEditStore factEditStore = new FileFactEditStore(builder.dataDirectory.resolve("facts"));
            EditedFactStore editedFacts = new EditedFactStore(enrichmentStore, factEditStore);
            this.facts = new PageFacts(catalog, access, pages, enrichments, editedFacts, cache::findHeader,
                    factEditStore);
            this.factEdits = new FactEdits(access, pages, enrichments, editedFacts, factEditStore, clock);
            // ADR 0017 mit ADR 0013: semantisch findet jede Variante nur, Treffer kommen aus den Vektoren.
            RuleBasedExpansion rules = new RuleBasedExpansion();
            // W8: Suchmethode „Fachbegriffe“; das Begriffswissen entsteht weiter unten, gefragt wird erst in der Suche.
            Harvester searching = this;
            Holdings termHoldings = this.holdings;
            this.termSearch = new com.aresstack.spaceharvester.application.query.progressive.TermSearch(keywordSearch,
                    (who, query) -> searchScope(termHoldings, catalog.globalSearchSources().stream().map(HarvestSource::id).toList(),
                            who, query),
                    com.aresstack.spaceharvester.application.query.progressive.TermSearch.Knowledge.of(
                            searching::termKnowledge, searching::termVocabulary));
            SearchOrchestrator orchestrator = new SearchOrchestrator(VariantSearch.of(keywordSearch),
                    new SemanticVariantSearch(relevance, textIndex),
                    new AiQueryExpansion(variantModels, rules, AiQueryExpansion.DEFAULT_TIMEOUT), rules,
                    HitScorer.of(excerpts), runLog, new NeighborCandidates(pageConnections),
                    new com.aresstack.spaceharvester.application.query.progressive.BackgroundNeighborLoader(pageLoads,
                            backgroundJobs, keywordSearch::allowsPrefetch), termSearch, keywordSearch::includesSource);
            // R2: dieselbe Methode zur Schlagwortsuche ohne progressive Suche, als eigener Aufruf nach einem Stapel.
            this.neighborSearch = new NeighborSearch(keywordSearch, new NeighborCandidates(pageConnections),
                    new com.aresstack.spaceharvester.application.query.progressive.BackgroundNeighborLoader(pageLoads,
                            backgroundJobs, keywordSearch::allowsPrefetch));
            opened.add(orchestrator);
            this.progressiveSearch = orchestrator;
            start.step("Suche");
            this.liveReranker = swappable;
            LuceneSyncBaselineStore baselineStore = new LuceneSyncBaselineStore(cache);
            // ADR 0030: Aufbewahrung des Änderungsprotokolls beim Start und nach jedem Abgleich, nie nach Zeitgeber.
            Clock pruneClock = clock;
            Runnable pruneChangeLog = () -> {
                HarvesterConfiguration current = this.settings;
                (current != null ? current.changeLog() : ChangeLogSettings.DEFAULT).cutoff(pruneClock.instant())
                        .ifPresent(cutoff -> {
                            try {
                                cache.pruneChangeLog(cutoff);
                            } catch (RuntimeException e) {
                                LOG.log(System.Logger.Level.WARNING, "Änderungsprotokoll nicht aufgeräumt ({0})",
                                        e.getClass().getName());
                            }
                        });
            };
            // Beim Start im Hintergrund: Das Aufräumen wartet auf den Schreiblauf des Cache und hielte sonst den Start auf.
            Thread.ofVirtual().name("change-log-prune").start(pruneChangeLog);
            // ADR 0026, AP9a: Analysen je Anhang und enthaltener Datei als Auftrag der Anreicherung wie bei Seiten;
            // der Lauf nach dem Abgleich holt den Bestand nach.
            EnrichmentSettings startEnrichment = builder.enrichment;
            DocumentEnrichments documentEnrichments = new DocumentEnrichments(enrichments, jobs,
                    () -> this.settings != null ? this.settings.enrichment() : startEnrichment);
            // Als Hintergrundauftrag „Anhänge analysieren“, sichtbar im Reiter „Hintergrund“ (Angelo 21.11.2026): je
            // Space höchstens ein Teil, Arbeit aus den Anhängen der Seiten des Space, Start erst nach dem Abgleich.
            documentEnrichments.background(backgroundJobs, (sourceId, spaceKey) -> spaceKey == null ? List.of()
                    : cache.knownPages(sourceId, spaceKey).keySet());
            documentEnrichments.network(network);
            documentEnrichments.localDocuments(cache::find, structuredDocuments);
            terms.localOriginals(documentEnrichments::currentLocalExtraction, enrichments::localReadingText);
            relevance.localOriginals(documentEnrichments::currentLocalExtraction);
            opened.add(documentEnrichments);
            sectionTexts.set(documentEnrichments);
            pageRecomputation.localDocuments(documentEnrichments);
            EnrichmentsAfterSync afterSync = new EnrichmentsAfterSync(enrichments, cache, jobs, texts,
                    // #120: Regelvorschläge der Begriffsbeziehungen nach jeder Anreicherung einer Quelle.
                    // SY1: danach der automatische Synonymlauf der Quelle als Hintergrundauftrag.
                    // B1: danach die automatische Bildbeschreibung der Quelle als eigener Auftrag (Standard aus).
                    // K4: danach die Fachbegriffe der Quelle als Hintergrundauftrag (Standard aus).
                    List.of(dependentEnrichments::changed, acronymRelationRule::refresh,
                            sourceId -> synonymDiscovery.automatic(sourceId),
                            sourceId -> glossaryClassification.automatic(sourceId),
                            sourceId -> {
                                var automatic = this.automaticImages;
                                if (automatic != null) {
                                    automatic.automatic(sourceId);
                                }
                            }),
                    documentEnrichments);
            this.syncs = new SpaceSyncJobs(jobs, sources, baselineStore, snapshots, clock,
                    List.of(afterSync, run -> pruneChangeLog.run(), run -> indexed(run)), retentionRule(builder));
            syncs.connectionLostAfter(builder.jobLimits.retry().pauseAfterFailures());
            // Indexieren (Angelo 20.11.2026): markierte Teilbäume lädt jeder Abgleich vollständig; Bilder und Anhänge
            // folgen als eigene Aufträge, sobald die Seiten da sind.
            FileIndexMarks indexMarks = new FileIndexMarks(builder.dataDirectory);
            syncs.indexMarks(indexMarks);
            this.indexing = new Indexing(indexMarks, syncs, this::collectIndexedAssets);
            // Ausschließen (Angelo 26.11.2026): Ausgeschlossenes fehlt in Baum und Suche; löscht nichts.
            PageNavigation browsing = this.navigation;
            this.exclusions = new Exclusions(new FileExclusionMarks(builder.dataDirectory),
                    (sourceId, pageId) -> reader.header(new PageKey(sourceId, pageId))
                            .map(header -> header.metadata().parentPageId())
                            .orElseGet(() -> browsing.browsedParent(sourceId, pageId)),
                    (sourceId, pageId) -> reader.header(new PageKey(sourceId, pageId))
                            .map(header -> header.metadata().title()).orElse(null));
            navigation.exclusions(exclusions);
            // Erwerbsgrund „Navigation“ (ADR 0037, Punkt 3): nur dieser Grund wird gespeichert.
            this.navigationMarks = new FileNavigationMarks(builder.dataDirectory);
            syncs.navigationMarks(navigationMarks);
            keywordSearch.exclusions(exclusions);
            findings.exclusions(exclusions);
            syncs.exclusions(exclusions);
            // Automatische Auslöser laden Ausgeschlossenes nie; ausdrückliches Öffnen, Lesen und Export bleiben möglich.
            Exclusions excluded = this.exclusions;
            acquisition.withhold((key, trigger) -> automatic(trigger)
                    && excluded.excluded(key.sourceId(), null, key.pageId()));
            // ADR 0030: Update-Marke je Quelle, sobald keine manuelle Synchronisierung mehr läuft oder angehalten wartet.
            // ADR 0030, CL5: Unterschied der Lesetexte, wenn ein gespeicherter Inhalt ersetzt wird.
            cache.contentDiffer(new TextContentDiffer(pageTexts));
            // ADR 0031: Bearbeiter der gespeicherten Fassung in der Protokollzeile, soweit die Quelle ihn liefert.
            cache.editors(new com.aresstack.spaceharvester.source.confluence.ConfluenceEditors());
            opened.add(ManualSyncRounds.start(jobs, cache, clock));
            // ADR 0030, Abschnitt 5: Zusammenfassen nur auf Wunsch, an das Modell nur Seiten mit KI-Freigabe.
            AiRelease summaryRelease = this.aiRelease;
            this.changeLog = new ChangeLog(cache, access, cache::findHeader,
                    () -> catalog.all().stream().map(HarvestSource::id).toList(),
                    new ChangeLog.Summaries(jobs, new ChatUpdateSummaryModel(
                            () -> liveAi ? this.settings.ai() : startAi, catalog, trustBundles, builder.userAgent),
                            summaryRelease::mayProcess, clock,
                            // Worum es geht (Angelo, 21.11.2026): Lesetext und Space-Namen aus dem Cache.
                            new com.aresstack.spaceharvester.application.changelog.SnapshotSummaryMaterial(
                                    cache::find, pageTexts),
                            // Plan vorab (Angelo 22.11.2026), Zeile „Zusammenfassung – Plan“.
                            ChatUpdateSummaryModel.plan(() -> liveAi ? this.settings.ai() : startAi, catalog,
                                    trustBundles, builder.userAgent)))
                    // Höchstens ein Update je Tag (Angelo, 21.11.2026), Tage in der Zeitzone des Rechners.
                    .perDay(java.time.ZoneId.systemDefault())
                    .globalSources(catalog::includeInGlobalSearch)
                    // Bereichszusammenfassung (ADR 0034 §7, Angelo 24.11.2026): Bestand statt Änderungen, alle Seiten.
                    .areas(holdings);
            opened.add(changeLog);
            // Podcast der Update-Zusammenfassung (Angelo 21.11.2026): Plan und Texte über ai.chat-model, Audio über die
            // TTS-Modelle des Dienstes; Ablage unter data/podcasts, damit ein neuer Lauf dort weitermacht.
            java.util.function.Supplier<com.aresstack.spaceharvester.ai.AiServiceSettings> podcastAi =
                    () -> liveAi ? this.settings.ai() : startAi;
            // Plan und Sprechertexte mit eigener Zeile „Podcast“ (Angelo 22.11.2026): ai.podcast-model,
            // ai.podcast-max-tokens, ai.podcast-thinking; Sprache (TTS) bleibt bei podcastAi.
            java.util.function.Supplier<com.aresstack.spaceharvester.ai.AiServiceSettings> podcastText = () -> {
                com.aresstack.spaceharvester.ai.AiServiceSettings ai = podcastAi.get();
                return ai == null ? null : ai.podcast();
            };
            this.podcasts = new com.aresstack.spaceharvester.application.podcast.UpdatePodcasts(jobs,
                    new ChatStepModel(com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.VERSION,
                            podcastText, () -> null, catalog, trustBundles, builder.userAgent),
                    new com.aresstack.spaceharvester.source.ai.RemotePodcastSpeech(podcastAi, catalog, trustBundles,
                            builder.userAgent, sentences),
                    new com.aresstack.spaceharvester.application.podcast.PodcastStore(
                            builder.dataDirectory.resolve("podcasts")),
                    com.aresstack.spaceharvester.application.podcast.PodcastVoices.bundled(),
                    changeLog::ancestors,
                    () -> {
                        com.aresstack.spaceharvester.ai.AiServiceSettings current = podcastAi.get();
                        return current == null ? 1 : current.parallel();
                    })
                    // „Willkommen zum Confluence-Update“ (Angelo 22.11.2026): die Art der Quelle, nie der Server.
                    .sourceTypes(sourceId -> catalog.find(sourceId).map(HarvestSource::type).orElse(null));
            // Erklär-Podcast einer Seite (Angelo 21.11.2026): derselbe Ablauf, Eingabe die Seite und ihre Unterseiten;
            // an das Modell nur lokal liegende, lesbare Seiten mit KI-Freigabe (ADR 0028), wie das Wissen des Chats.
            PageQueries podcastPages = this.pages;
            AiRelease podcastRelease = this.aiRelease;
            PageTextProjector podcastTexts = this.pageTexts;
            LucenePageCache podcastCache = this.pageCache;
            ChangeLog podcastChangeLog = this.changeLog;
            this.pagePodcasts = new com.aresstack.spaceharvester.application.podcast.PagePodcasts(jobs,
                    new ChatStepModel(com.aresstack.spaceharvester.application.podcast.PagePodcasts.VERSION,
                            podcastText, () -> null, catalog, trustBundles, builder.userAgent),
                    new com.aresstack.spaceharvester.source.ai.RemotePodcastSpeech(podcastAi, catalog, trustBundles,
                            builder.userAgent, sentences),
                    new com.aresstack.spaceharvester.application.podcast.PodcastStore(
                            builder.dataDirectory.resolve("podcasts")),
                    com.aresstack.spaceharvester.application.podcast.PodcastVoices.bundled(),
                    new com.aresstack.spaceharvester.application.podcast.PagePodcasts.Pages() {
                        @Override
                        public Optional<com.aresstack.spaceharvester.application.podcast.PagePodcasts.Page> page(
                                AccessContext who, String sourceId, String pageId) {
                            try {
                                return podcastPages.cached(who, sourceId, pageId)
                                        .map(com.aresstack.spaceharvester.application.query.DeliveredPage::snapshot)
                                        .filter(snapshot -> podcastRelease.mayProcess(snapshot.key()))
                                        .flatMap(snapshot -> podcastTexts.project(snapshot).map(text ->
                                                new com.aresstack.spaceharvester.application.podcast.PagePodcasts
                                                        .Page(pageId, snapshot.metadata().title(),
                                                        snapshot.spaceKey(), text)));
                            } catch (RuntimeException e) {
                                return Optional.empty();
                            }
                        }

                        @Override
                        public List<String> children(AccessContext who, String sourceId, String pageId) {
                            return podcastCache.allChildren(new PageKey(sourceId, pageId)).stream()
                                    .map(com.aresstack.spaceharvester.persistence.lucene.CachedPageHeader::header)
                                    .sorted(java.util.Comparator.comparing(header -> header.metadata().title(),
                                            String.CASE_INSENSITIVE_ORDER))
                                    .map(header -> header.metadata().pageId()).toList();
                        }

                        @Override
                        public List<String> ancestors(AccessContext who, String sourceId, String pageId) {
                            return podcastChangeLog.ancestors(who, sourceId, pageId);
                        }
                    },
                    () -> {
                        com.aresstack.spaceharvester.ai.AiServiceSettings current = podcastAi.get();
                        return current == null ? 1 : current.parallel();
                    });
            start.step("Änderungsprotokoll");
            if (manager != null) {
                // ADR 0015: vor jeder Suche nachholen, je Quelle aus der wirksamen Konfiguration.
                this.syncBeforeSearch = new SyncBeforeSearch(syncs, () -> this.settings, OWNER, clock);
            }
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
                    // Bildbeschreibungen über die exportierten Bytes, auch einzeln beschriebene Bilder.
                    new AnalysisConverter(new EnrichmentAnalyses(enrichments, editedFacts,
                            sha256 -> assets().storedDescription(sha256))),
                    new JsonPackageConverter(), new MarkdownConverter(), new MarkdownPackageConverter(), new DocxConverter(), new PdfConverter(builder.pdfFont)),
                    ExportConverters.DEFAULT_MAX_BYTES);
            this.exports = new Exports(jobs, reader, exportWriter, exportConverters, syncs, holdingsCatalog, clock);
            exports.localExports(new com.aresstack.spaceharvester.application.export.LocalExportSnapshots(
                    structuredDocuments, snapshots::exportLocalOriginal, builder.maxPageBytes));
            exports.fileTexts(new LocalFilesTextProjector(documentText));
            FileSystemAssetStore assetStore = new FileSystemAssetStore(builder.assetDirectory());
            // #183: Typerkennung am Inhalt beim Ablegen, Text von Dokument-Anhängen über Tika, im Volltextindex mit
            // der Seite als Eltern; Bildanhänge dort mit ihrer vorhandenen Beschreibung (#371)
            // ADR 0018: verlangt die Quelle einen Einmalcode, wartet der Auftrag auf die Oberfläche; beim Lesen auf
            // Abruf legt Assets die Anforderung ohne Warten an.
            this.signIns = new SignInRequests(builder.signInWait, clock);
            long maxAssetBytes = Assets.maxBytes(builder.maxAssetBytes);
            this.assets = new Assets(catalog, assetStore, maxAssetBytes, clock, describer,
                    documentText, textIndex, new LargeFileGate(builder.largeAssetThresholdBytes,
                            builder.maxConcurrentLargeAssets, LargeFileGate.DEFAULT_WAIT), signIns);
            // ADR 0026, AP3b: abgelegte Dokumente aus der Datei lesen, Text in Abschnitten unter extractions/.
            assets.documentExtractions(structuredDocuments);
            assets.indexedDocuments(documentEnrichments);
            // Angelo 20.11.2026: Neues Transkript oder neue Korrektur → Analysen neu, gelöschtes → Analysen weg.
            com.aresstack.spaceharvester.application.presentation.TranscriptAnalyses transcriptAnalyses =
                    new com.aresstack.spaceharvester.application.presentation.TranscriptAnalyses(enrichments,
                            cache::find);
            opened.add(transcriptAnalyses);
            this.transcriptAnalyses = transcriptAnalyses;
            documentEnrichments.assets(assets);
            // ADR 0028: Bilder einer Seite auf der Liste freigegebener Seiten gehen auch aus einer gesperrten Quelle an
            // das Vision-Modell.
            assets.pageReleases(pageReleases::contains);
            // Import eines JSON-Pakets wie ein Abgleich ohne Quelle; danach Volltext und Anreicherungen. Die
            // mitgebrachten Bilder und Anhänge je Seite nimmt ein Export, wenn die Quelle nicht erreichbar ist.
            EnrichmentImportedAssets importedAssets = new EnrichmentImportedAssets(enrichmentStore, clock);
            this.packageImport = new ExportPackageImport(jobs, new ZipExportPackageReader(
                    builder.maxPageBytes, maxAssetBytes), baselineStore,
                    sourceId -> catalog.config(sourceId).isPresent(), assetStore, assets::adoptText,
                    new ReadingViewStore(enrichmentStore), importedAssets, List.of(afterSync), clock);
            // Genug Plätze für die Dateien aller gleichzeitig ladenden Aufträge (jobs.parallel-downloads).
            // Gesamtbudget der Verbindungen aller ladenden Aufträge (jobs.download-connections, Angelo 11.11.2026).
            this.connections = new ConnectionBudget(builder.jobLimits.downloadConnections());
            assets.downloadLimit(Math.max(jobs.parallelDownloads(), connections.total()));
            // Restzeit (Angelo 11.11.2026): Dauer je geladener Datei, über Neustarts im Anwendungszustand.
            DownloadDurations durations = new DownloadDurations(
                    builder.dataDirectory.resolve("download-durations.properties"), clock);
            assets.durations(durations);
            opened.add(durations);
            this.imageAnalysis = new PageImageAnalysis(assets, enrichments, clock);
            // Bilder im Podcast (Angelo 21.11.2026): je Thema ein Seitenbild nach seiner Beschreibung, sonst ein
            // Schaubild aus Teilen. Plan und Einzelteile sind getrennt einstellbar (Angelo 22.11.2026):
            // „Bildgenerierung – Plan“ (ai.image-plan-*) und „– Einzelteile“ (ai.image-*), damit lange SVG-Antworten
            // nicht abbrechen und jede Stufe für sich justiert werden kann.
            com.aresstack.spaceharvester.application.podcast.StoredPageImages podcastImages =
                    new com.aresstack.spaceharvester.application.podcast.StoredPageImages(cache::find,
                            this.imageAnalysis, assets);
            ChatStepModel podcastPlanner = new ChatStepModel("podcast-picture-plan", () -> {
                AiServiceSettings ai = liveAi ? this.settings.ai() : startAi;
                return ai == null ? null : ai.imagePlan();
            }, () -> null, catalog, trustBundles, builder.userAgent);
            ChatStepModel podcastDrawer = new ChatStepModel("podcast-pictures", () -> {
                AiServiceSettings ai = liveAi ? this.settings.ai() : startAi;
                return ai == null ? null : ai.imageParts();
            }, () -> null, catalog, trustBundles, builder.userAgent);
            this.podcasts.pictures(podcastPlanner, podcastDrawer, podcastImages);
            // Schaubilder im Chat (Angelo 27.11.2026): dieselben Zeilen „Bildgenerierung – Plan/Einzelteile“.
            this.diagramModels = new com.aresstack.spaceharvester.application.podcast.DiagramComposer.Models(
                    podcastPlanner, podcastDrawer);
            this.pagePodcasts.pictures(podcastPlanner, podcastDrawer, podcastImages);
            // B1: Bilder automatisch beschreiben nach dem Abgleich und nach neuer KI-Freigabe, nur mit images.automatic.
            com.aresstack.spaceharvester.application.configuration.ImageSettings startImages = builder.images;
            // D2: jeder Lauf „Bilder beschreiben“ über das Auftragssystem, im Hintergrund ein Unterauftrag je Space.
            java.util.function.Supplier<com.aresstack.spaceharvester.application.configuration.ImageSettings>
                    imageSettings = () -> this.settings != null ? this.settings.images() : startImages;
            this.imageDescriptions = new com.aresstack.spaceharvester.application.images.ImageDescriptionJobs(
                    backgroundJobs, jobs, OWNER, imageAnalysis, cache::snapshots, cache::find,
                    (caller, root) -> holdings.resolve(caller,
                            new com.aresstack.spaceharvester.application.holdings.Selection(List.of(root))).pages(),
                    imageSettings, AccessContext.technicalClient(OWNER));
            this.automaticImages = new com.aresstack.spaceharvester.application.images.AutomaticImageDescriptions(
                    imageDescriptions, imageSettings, sourceId -> catalog.find(sourceId).isPresent());
            opened.add(pageReleases.subscribe(automaticImages::releasesChanged));
            // ADR 0028: Verliert eine Seite die Freigabe (Liste oder Quelle), entfallen auch ihre Bildbeschreibungen
            // und Zusammenfassungen mit ihr, und die Analysen ihrer Anhänge, die mit Modell entstanden, rechnen ohne neu.
            PageImageAnalysis pageImages = this.imageAnalysis;
            AiRelease withdrawn = this.aiRelease;
            ChangeLog summaries = this.changeLog;
            com.aresstack.spaceharvester.application.podcast.UpdatePodcasts podcastsAfterWithdrawal =
                    this.podcasts;
            // ADR 0032, FL3: Vorstufe „Sätze vereinfachen“ nur auf Auftrag, an das Modell nur Seiten mit KI-Freigabe;
            // ihre Ablage zählt als Seiteninhalt und fällt mit der Seite, der Quelle und der Freigabe.
            this.simplifications = new Simplifications(new ChatStepModel(ChatStepModel.SIMPLIFICATION,
                    () -> liveAi ? this.settings.ai() : startAi,
                    () -> this.settings.simplification().model(), () -> this.settings.simplification().maxTokens(),
                    () -> this.settings.simplification().thinking(), catalog, trustBundles, builder.userAgent),
                    new SimplificationStore(enrichmentStore, clock), withdrawn::mayProcess);
            Simplifications simplified = this.simplifications;
            // ADR 0032, FL4: Prüfschritt nur auf Auftrag, gleicher Modellweg wie die Vorstufe (Temperatur 0, abgeschnitten
            // = Fehler), an das Modell nur Seiten mit KI-Freigabe; seine Ablage zählt als Seiteninhalt.
            this.statementChecks = new StatementChecks(new ChatStepModel(ChatStepModel.CHECK,
                    () -> liveAi ? this.settings.ai() : startAi,
                    () -> this.settings.check().model(), () -> this.settings.check().maxTokens(),
                    () -> this.settings.check().thinking(), catalog,
                    trustBundles, builder.userAgent),
                    new StatementCheckStore(enrichmentStore, clock), withdrawn::mayProcess,
                    StatementChecks.pageAccess(access, page -> cache.find(page).map(PageSnapshot::spaceKey)));
            StatementChecks checked = this.statementChecks;
            // ADR 0035, Abschnitt 4: Dokumentanalyse der Fristen-Fachlogik als Auftrag; KI über die Modellzeile
            // „Fristen“ (ai.deadline-*), je Sachverhalt parallel bis ai.parallel, an das Modell nur freigegebene
            // Dokumente. Die Ablage (E-Akte) schließt die App an.
            java.util.function.Supplier<AiServiceSettings> deadlineAi = () -> {
                AiServiceSettings ai = liveAi ? this.settings.ai() : startAi;
                return ai == null ? null : ai.deadline();
            };
            ChatStepModel deadlineModel = new ChatStepModel(DEADLINE_STEP, deadlineAi, () -> null, catalog,
                    trustBundles, builder.userAgent);
            this.documentAnalyses = new com.aresstack.spaceharvester.application.deadlines.DeadlineAnalysisService(
                    jobs, OWNER, documentText,
                    new com.aresstack.spaceharvester.application.deadlines.IdentifierExtractor(sentences),
                    new com.aresstack.spaceharvester.application.deadlines.AiFactExtractor(deadlineModel,
                            com.aresstack.spaceharvester.ai.ModelRetry.logging(
                                    () -> Thread.currentThread().isInterrupted()),
                            com.aresstack.spaceharvester.application.deadlines.AiFactExtractor.DEFAULT_MAX_CHARS,
                            com.aresstack.spaceharvester.application.deadlines.IdentifierExtractor::parts, () -> {
                                AiServiceSettings ai = deadlineAi.get();
                                return ai == null ? 1 : ai.parallel();
                            }),
                    new com.aresstack.spaceharvester.application.deadlines.ExtractionValidator(
                            com.aresstack.spaceharvester.application.deadlines.IdentifierExtractor::parts),
                    withdrawn::mayProcess);
            // „In PPTX umwandeln“ (Angelo 20.11.2026): fünf Aufträge je Video, Text-Modell = ai.chat-model, an den
            // KI-Dienst nur Seiten mit KI-Freigabe; die Zwischenergebnisse fallen mit der Freigabe.
            PageQueries presentationPages = this.pages;
            com.aresstack.spaceharvester.application.presentation.PresentationStore presentationStore =
                    new com.aresstack.spaceharvester.application.presentation.PresentationStore(enrichmentStore, clock);
            this.videoPresentations = new com.aresstack.spaceharvester.application.presentation.VideoPresentations(
                    jobs, (who, sourceId, pageId) -> presentationPages.page(who, sourceId, pageId, false).snapshot(),
                    assets, imageAnalysis,
                    com.aresstack.spaceharvester.application.marks.VideoMarks.slides(enrichmentStore, assets),
                    new ChatStepModel(com.aresstack.spaceharvester.application.presentation.VideoPresentations
                            .CORRECTION_STEP, () -> liveAi ? this.settings.ai() : startAi, () -> null, catalog,
                            trustBundles, builder.userAgent),
                    presentationStore, withdrawn::mayProcess, builder.exportDirectory().resolve("presentations"));
            com.aresstack.spaceharvester.application.presentation.VideoPresentations presentations =
                    this.videoPresentations;
            java.util.concurrent.atomic.AtomicReference<StatementAnalysis> graphAnalysis =
                    new java.util.concurrent.atomic.AtomicReference<>();
            releaseRecomputation.withdrawal(pages -> {
                pageImages.withdraw(pages, withdrawn::mayProcess);
                simplified.withdraw(pages.stream().map(PageSnapshot::key).toList(), withdrawn::mayProcess);
                checked.withdraw(pages.stream().map(PageSnapshot::key).toList(), withdrawn::mayProcess);
                presentations.withdraw(pages.stream().map(PageSnapshot::key).toList(), withdrawn::mayProcess);
                // ADR 0032, FL3: Aussagen der Vorstufe tragen Modelltext der Seite und fallen aus dem Graph
                StatementAnalysis graph = graphAnalysis.get();
                if (graph != null) {
                    graph.withdrawStage(pages.stream().map(PageSnapshot::key).toList(), withdrawn::mayProcess);
                    // ADR 0032, FL4: Urteile und Korrekturen fallen weg, die Aussagen stehen wie vor dem Prüflauf
                    graph.withdrawChecks(pages.stream().map(PageSnapshot::key).toList(), withdrawn::mayProcess);
                    // Fahrplan Paket A2: Analysen des KI-Analysators sind Modellergebnisse der Seite und fallen ganz
                    graph.withdrawModel(pages.stream().map(PageSnapshot::key).toList(), withdrawn::mayProcess);
                }
                documentEnrichments.withdrawn(pages, withdrawn::mayProcess);
                // ADR 0032, FL5c: KI-Einordnungen mit Belegen von entzogenen Seiten fallen auf den Regelstand zurück
                this.glossary.withdrawAi(pages.stream().map(PageSnapshot::key).toList(), withdrawn::mayProcess);
                // D4.3: die Modellantworten des Glossars zu Quellen mit entzogenen Seiten.
                this.glossaryClassification.withdrawn(pages.stream().map(PageSnapshot::key).toList());
                // ADR 0030, Abschnitt 5: Zusammenfassungen, in die eine solche Seite einging, entfallen.
                summaries.withdrawn(pages.stream().map(PageSnapshot::key).toList());
                // Erklär-Podcasts, in die eine solche Seite einging, entfallen (ADR 0028).
                this.pagePodcasts.withdrawn(pages.stream().map(PageSnapshot::key).toList());
                // Podcasts, deren Zusammenfassung dabei wegfiel, entfallen mit ihr.
                podcastsAfterWithdrawal.prune(meta -> cache.summary(meta.sourceId(), meta.updateId(), meta.spaceKey())
                        .map(stored -> com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.key(
                                stored.sourceId(), stored.updateId(), stored.spaceKey(), stored.text(),
                                String.valueOf(stored.createdAt())))
                        .filter(meta.key()::equals).isPresent());
                // SY2: Vektoren der Synonymerkennung aus diesen Seiten und der Stand ihrer Quellen.
                synonymDiscovery.withdrawn(pages.stream().map(PageSnapshot::key).toList(), withdrawn::mayProcess);
            });
            // #499, ADR 0024: Aussagenanalyse nur auf Knopfdruck; der Analysator kommt vom Einbettenden (Desktop-App).
            // Der Graph liegt außerhalb des Caches und wird erst beim ersten Zugriff gelesen.
            // Fahrplan Paket A2: die Analysen des KI-Analysators liegen in einer eigenen Ablage und überschreiben die des
            // MaltParsers nie; Graph, Stand, Begriffe und Export lesen aus genau einer, der eingestellten (Vorgabe
            // MaltParser).
            // Jede neue, ersetzte oder entfernte Analyse meldet sich beim Graphen (D6), gleich aus welchem Auftrag. Die
            // Meldung sitzt innen an jeder Ablage, damit AnalyzerStores als solche erkennbar bleibt.
            java.util.concurrent.atomic.AtomicReference<KnowledgeGraph> observedGraph =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Runnable analysesChanged = () -> {
                KnowledgeGraph graph = observedGraph.get();
                if (graph != null) {
                    graph.analysesChanged();
                }
            };
            AnalyzerStores graphStore = new AnalyzerStores(
                    new com.aresstack.spaceharvester.application.knowledge.ObservedKnowledgeGraphStore(
                            new FileKnowledgeGraphStore(builder.dataDirectory.resolve("knowledge-graph")),
                            analysesChanged),
                    new com.aresstack.spaceharvester.application.knowledge.ObservedKnowledgeGraphStore(
                            new FileKnowledgeGraphStore(builder.dataDirectory.resolve("knowledge-graph-model")),
                            analysesChanged),
                    this::statementAnalyzerChoice);
            StatementAnalyzer statementAnalyzer = builder.statementAnalyzer;
            // Fahrplan Paket A2: zweiter Analysator über den eingestellten KI-Dienst, derselbe Modellweg wie Vorstufe
            // und Prüfschritt (Temperatur 0, Denken aus); nur auf Auftrag, an den Dienst nur Seiten mit KI-Freigabe.
            StatementAnalyzer modelAnalyzer = new ModelStatementAnalyzer(new ChatStepModel(ModelStatementAnalyzer.STEP,
                    () -> liveAi ? this.settings.ai() : startAi,
                    () -> this.settings == null ? null : this.settings.analyzer().model(),
                    () -> this.settings == null ? null : this.settings.analyzer().maxTokens(),
                    () -> this.settings == null ? null : this.settings.analyzer().thinking(), catalog, trustBundles,
                    builder.userAgent), sentences);
            java.util.function.Function<PageSnapshot, Optional<PageText>> readerText = snapshot -> {
                try {
                    return projector.project(snapshot);
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            };
            // ADR 0032, FL6 und W3: Entscheidungen zu Stand und Prüfung als Nutzerdaten. Die Prüfung durch Menschen wirkt
            // an einer Stelle beim Lesen der Analysen (Aussagen-Reiter und Knowledge Graph), direkt aus der Ablage.
            com.aresstack.spaceharvester.application.standing.StandingDecisionStore standingDecisions =
                    new com.aresstack.spaceharvester.persistence.files.FileStandingDecisionStore(
                            builder.dataDirectory.resolve("standing"));
            this.statementAnalysis = new StatementAnalysis(readerText, graphStore, access, () -> statementAnalyzer,
                    clock, simplified, checked, () -> modelAnalyzer, withdrawn::mayProcess,
                    new com.aresstack.spaceharvester.application.standing.DecisionReviews(standingDecisions));
            graphAnalysis.set(this.statementAnalysis);
            // Eine Stelle für alle Leser (Graph, Fachsicht, Chat, Suche): auch keine Quelle mit offenem Löschen.
            this.knowledgeGraph = new KnowledgeGraph(graphStore, access, cache::find, readerText, statementAnalysis,
                    termRelations::expansions, analysedSources(sourceId -> catalog.find(sourceId).isPresent(),
                            sourceId -> {
                                SourceDataPurge purge = withdrawals.get();
                                return purge == null ? null : purge.openWithdrawal(sourceId);
                            }), document -> Optional.empty(),
                    // Bereich des Begriffswissens: nur Kopfdaten (Space), nie der ganze Snapshot samt Text.
                    key -> cache.findHeader(key).map(header -> header.metadata().partitionKey()));
            observedGraph.set(this.knowledgeGraph);
            opened.add(this.knowledgeGraph::close);
            // K2: je Ablage eine eigene Sicht mit denselben Rechten und demselben Stand je Seite, für den Abgleich.
            // Die Sichten teilen das Gedächtnis der Eingabe-Hashes mit der Hauptsicht: Was diese vergisst (neues
            // Transkript, Neuaufbau), vergessen sie mit, sonst gälte eine Videoseite dort weiter als AKTUELL.
            java.util.Map<AnalyzerChoice, KnowledgeGraph> byAnalyzer = new java.util.EnumMap<>(AnalyzerChoice.class);
            for (AnalyzerChoice choice : AnalyzerChoice.values()) {
                byAnalyzer.put(choice, this.knowledgeGraph.sharingInputs(graphStore.store(choice)));
            }
            this.analyzerComparison = new com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison(
                    access, cache::find, (who, choice, key) -> byAnalyzer.get(choice).page(who, key),
                    graphStore::selected);
            this.verbQueries = new com.aresstack.spaceharvester.application.knowledge.VerbQueries(
                    this.knowledgeGraph).remembering();
            // Geänderte Analysen und Prüfungen sowie jeder Commit des Seiten-Caches machen die gemerkten Verben
            // ungültig; die nächste Abfrage liest neu (kein Polling).
            this.knowledgeGraph.subscribe(verbQueries::invalidate);
            verbsStale.set(verbQueries::invalidate);
            // Ein neues Transkript ändert den Text einer Videoseite, nicht ihre Payload: Der Graph vergisst den
            // gemerkten Eingabe-Hash, die Analysen rechnen neu.
            KnowledgeGraph transcriptGraph = this.knowledgeGraph;
            com.aresstack.spaceharvester.application.presentation.TranscriptAnalyses analyses = this.transcriptAnalyses;
            this.assets.transcriptChanges(key -> {
                transcriptGraph.forgetInput(key);
                verbQueries.invalidate();
                analyses.changed(key);
            });
            // ADR 0032, FL6: Stand und Status-quo-Sicht aus gespeicherten Aussagen; Entscheidungen als Nutzerdaten.
            // ADR 0032, FL5b: Teilzahlen der Begriffsableitung je Aufrufer für den Glossar-Reiter.
            this.glossaryOverview = new com.aresstack.spaceharvester.application.glossary.GlossaryOverview(
                    knowledgeGraph::analysed,
                    () -> (this.settings != null ? this.settings.terms() : builder.terms).glossaryRules(), glossary);
            knowledgeGraph.subscribe(technicalTerms::forget);
            this.statusQuo = new com.aresstack.spaceharvester.application.standing.StatusQuo(knowledgeGraph::analysed,
                    cache::findHeader, standingDecisions, access,
                    () -> com.aresstack.spaceharvester.application.standing.StandingWords.of(
                            this.settings.standing()), clock,
                    new com.aresstack.spaceharvester.application.standing.SentenceSource() {
                        @Override
                        public Optional<KnowledgeGraph.AnalysedPage> analysis(AccessContext who,
                                                                              KnowledgeGraph.PageRef page) {
                            return page.document() == null ? knowledgeGraph.page(who, page.key())
                                    : knowledgeGraph.page(who, page.document());
                        }

                        @Override
                        public Optional<String> text(KnowledgeGraph.PageRef page, String inputSha256) {
                            // Lesetexte von Anhängen kommen erst mit deren Analyse (ADR 0026); bis dahin kein Satz.
                            if (page.document() != null) {
                                return Optional.empty();
                            }
                            return cache.find(page.key()).flatMap(readerText)
                                    .filter(text -> text.inputSha256().equals(inputSha256)).map(PageText::text);
                        }
                    });
            // W3: ✓/✕ im Overlay „Belege“ prüfen in der Ablage des Analysators, dessen Analyse der Beleg zeigt, nicht in
            // der gerade eingestellten; eine im Bestand gesperrte Seite (ADR 0014) ist nicht prüfbar.
            Holdings stockForReviews = this.holdings;
            this.statusQuo.analysesOf((who, choice, keys) -> byAnalyzer.get(choice).analysed(who,
                    com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(keys)));
            this.statusQuo.lockedPages((who, keys) -> {
                java.util.Set<PageKey> locked = new java.util.HashSet<>();
                stockForReviews.locate(who, com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(keys))
                        .forEach((key, located) -> {
                            if (located.state() == com.aresstack.spaceharvester.application.holdings.HoldingState
                                    .LOCKED) {
                                locked.add(key);
                            }
                        });
                return locked;
            });
            // Eine gesetzte oder zurückgenommene Prüfung ändert die wirksame Prüfung der Analysen: Graph, Fachsicht,
            // Aussagen und Verben ziehen ohne Klick nach (W3, D6).
            opened.add(statusQuo.subscribeReviews(knowledgeGraph::analysesChanged));
            this.readingAdvice = new com.aresstack.spaceharvester.application.standing.ReadingAdvice(statusQuo,
                    knowledgeGraph::analysed,
                    new com.aresstack.spaceharvester.application.standing.ChangeLogRecentChanges(changeLog), clock);
            this.statementAnalysisJobs = new StatementAnalysisJobs(jobs, new StatementAnalysisJobs.Discovery() {
                @Override
                public PageNavigation.Children children(AccessContext who, String sourceId, String spaceKey,
                                                        String parentPageId, String cursor) {
                    return navigation.children(who, sourceId, spaceKey, parentPageId, cursor);
                }

                @Override
                public List<Partition> spaces(AccessContext who, String sourceId) {
                    access.require(who, ResourceScope.source(sourceId), AccessKind.METADATA);
                    return access.filter(who, sources.partitions(sourceId), AccessKind.METADATA,
                            partition -> ResourceScope.space(sourceId, partition.key()));
                }

                @Override
                public String spaceKey(AccessContext who, String sourceId, String pageId) {
                    return navigation.position(who, sourceId, pageId).spaceKey();
                }
            }, (who, sourceId, pageId) -> pages.page(who, sourceId, pageId, false).snapshot(), statementAnalysis,
                    access, OWNER);
            // Fahrplan D3: Teilbaum, Space und Quelle als Hintergrundgruppe, nach einem Neustart mit dem App-Benutzer
            // fortgesetzt; missing() liest nur Bestand und Cache.
            this.statementAnalysisJobs.background(backgroundJobs, AccessContext.technicalClient(OWNER));
            Holdings stockForAnalyses = this.holdings;
            this.statementAnalysisJobs.local(new StatementAnalysisJobs.LocalPages() {
                @Override
                public Map<PageKey, Holdings.Located> locate(AccessContext who,
                        com.aresstack.spaceharvester.application.snapshot.PageSet set) {
                    return stockForAnalyses.locate(who, set);
                }

                @Override
                public Optional<PageSnapshot> stored(PageKey key) {
                    return cache.find(key);
                }
            });
            // W5: „Wissensgraph erzeugen“ als Ziel des Planers, ein Abschlussteil je Space; vor resumeInterrupted
            // angemeldet, damit unterbrochene Teile weiterlaufen und ihre Vorgänger-Regel gilt.
            this.knowledgeGraphJobs = new com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs(
                    this.statementAnalysisJobs, knowledgeGraph::analysesChanged);
            this.knowledgeGraphJobs.background(backgroundJobs, AccessContext.technicalClient(OWNER));
            // D1 (Nachtrag ADR 0025): Dienst-Planer mit den Beschreibungen der Analysedienste; die Aussagenanalyse
            // arbeitet schon (D3), die übrigen Arten baut B1.
            var imagesStart = startImages;
            TermSettings plannerTerms = builder.terms;
            this.servicePlanner = services(backgroundJobs, () -> (this.settings != null ? this.settings.images()
                    : imagesStart).automatic(), () -> (this.settings != null ? this.settings.terms()
                    : plannerTerms).glossaryAutomatic(), access,
                    key -> cache.findHeader(key).map(header -> header.metadata().partitionKey()),
                    this.statementAnalysisJobs, imageDescriptions, this.knowledgeGraphJobs);
            this.knowledgeGraphExport = new KnowledgeGraphExportUseCase(jobs, knowledgeGraph,
                    key -> cache.findHeader(key).map(header -> header.metadata().parentPageId()), clock, OWNER);
            this.knowledgeGraphExportDirectory = builder.exportDirectory().resolve("knowledge-graph");
            // Angelo 16.11.2026: Confluence-Metadaten und Analytics als eigene Exporte aus der Quelle, je Wurzel
            // der Auswahl ein Auftrag; die Seiten kommen aus dem Bestand, die Daten per REST-Abfrage.
            Holdings selectedPages = this.holdings;
            this.sourceQueryExports = new SourceQueryExports(jobs, catalog, new SourceQueryExports.Scopes() {
                @Override
                public List<Selection.Root> outputs(AccessContext who, Selection selection) {
                    return selectedPages.outputs(who, selection);
                }

                @Override
                public List<String> pages(AccessContext who, Selection.Root root) {
                    return selectedPages.resolve(who, Selection.of(root)).pages().pagesOf(root.sourceId()).stream()
                            .sorted(java.util.Comparator.comparingInt(String::length).thenComparing(
                                    java.util.Comparator.naturalOrder())).toList();
                }

                @Override
                public SourceQueryExports.PageName name(String sourceId, String pageId) {
                    return cache.findHeader(new PageKey(sourceId, pageId)).map(header -> new SourceQueryExports
                            .PageName(header.metadata().title(), header.metadata().url() == null ? null
                            : header.metadata().url().toString())).orElse(null);
                }
            }, access, clock, OWNER);
            // Bilder gehören zum Datenbestand: nach jedem veröffentlichten Export ein eigener Auftrag ASSETS.
            this.caller = AccessContext.technicalClient(OWNER);
            ExportAssetCollection images = new ExportAssetCollection(jobs, exportWriter, sources, assets,
                    (owner, sourceId) -> OWNER.equals(owner)
                            && access.permitsSourceWide(caller, sourceId, AccessKind.CONTENT), signIns,
                    // Dokument-Anhänge (ADR 0023): je Lauf die gerade wirksame Einstellung (ADR 0015).
                    exportSettings(manager));
            images.connections(connections);
            ReadingViewStore readingViews = new ReadingViewStore(enrichmentStore);
            images.imported(importedAssets, readingViews);
            // Ihr Stand gehört zum Fingerabdruck einer Ausgabe aus dem Bestand (ADR 0029).
            exports.imported(importedAssets, readingViews);
            // ADR 0029, Schritt 7: Bilder und Anhänge einer Auswahl ohne Export beschaffen, für die Aufträge dieser App.
            Holdings selected = this.holdings;
            AccessContext app = this.caller;
            images.selections((owner, root) -> OWNER.equals(owner)
                    ? Optional.of(new SelectionDataset(root.sourceId(), root.spaceKey(), root.pageId(),
                    selected.storedPages(app, Selection.of(root)).stream()
                            .map(page -> cache.findHeader(page.key())).flatMap(Optional::stream).toList(),
                    cache::find))
                    : Optional.empty());
            this.assetCollection = images;
            // Bilder und Anhänge entstehen mit dem Export und warten auf seinen Text (Angelo 11.11.2026).
            exports.onSubmitted(images::announce);
            exports.onPublished(images::start);
            this.conversions = new ExportConversions(jobs, exportWriter, exportConverters, images);
            conversions.fileTexts(new LocalFilesTextProjector(documentText));
            // ADR 0026, Punkt 11: abgelegte Dokumente ohne Download neu lesen; der Space für den Volltextindex
            // kommt aus dem Cache.
            this.rereads = new DocumentRereads(jobs, assets, assetStore,
                    sourceId -> catalog.config(sourceId).isPresent(),
                    key -> cache.findHeader(key).map(header -> header.metadata().partitionKey()),
                    new FileReadStates(builder.assetDirectory()), clock);
            // Fundstelle 24: „Anhänge ablegen“ einer Seite als Auftrag.
            this.pageAssetHarvests = new com.aresstack.spaceharvester.application.assets.PageAssetHarvests(jobs,
                    assets);
            this.jobActions = new JobActions(jobs, exports, syncs, conversions, rereads, sourceQueryExports);
            jobActions.presentations(videoPresentations);
            // Q1: Suchqualität über den Suchweg des Chats; der Bereich wie im Chat als Seitenmenge aus dem Bestand.
            Holdings selectionHoldings = this.holdings;
            PageQueries cachedPages = this.pages;
            this.searchQuality = new com.aresstack.spaceharvester.application.quality.SearchQualityRuns(catalog::all,
                    access, key -> cache.findHeader(key).map(header -> header.metadata().partitionKey()),
                    (who, selection) -> selectionHoldings.resolve(who, selection).pages(),
                    (who, key) -> cachedPages.cached(who, key.sourceId(), key.pageId()).isPresent(),
                    () -> this.progressiveSearch, this::searchProfile, jobs, clock);
            jobActions.searchQuality(searchQuality);
            jobActions.background(backgroundJobs);
            // ADR 0025 Punkt 17: Wiederanlauf nach Verbindungsabbruch und nachgeholtes „Nur Fehler“ wie ein Klick,
            // mit derselben Lese- und Exportprüfung wie beim Start.
            // Ohne Wiederanlauf beim Start (App, Angelo 24.11.2026) bleiben beim Beenden angehaltene Aufträge stehen, bis
            // jemand sie fortsetzt; ein Verbindungsabbruch zur Laufzeit läuft weiter von selbst wieder an.
            jobs.recovery((status, action) -> jobActions.perform(status.id(), status.owner(), action,
                    exportAccess()), null, builder.autoStart);
            // Bezugspunkte der Inventur im Cache, damit Neustarts den Abstand nicht zurücksetzen.
            FileInventoryLog inventoryLog = new FileInventoryLog(cacheDirectory);
            KnowledgeGraph graph = this.knowledgeGraph;
            // „Daten bereinigen“ und „Quelle entfernen“ leeren dieselben Ablagen; die Füllschlange zuerst.
            SourceDataPurge purge = new SourceDataPurge(jobs, SourceDataStores.builder()
                    // Über die Füllung, sobald es sie gibt: Sie bereinigt ihren Spiegel mit (Abnahme #1087).
                    .fillQueue(fillQueue == null ? null : sourceId -> {
                        BackgroundFill current = filling.get();
                        if (current != null) {
                            current.removeSource(sourceId);
                        } else {
                            fillQueue.removeSource(sourceId);
                        }
                    })
                    // Auch ohne gehostete Warteschlange: Dateien eines früheren Laufs gehören mit zur Quelle.
                    .enrichmentQueue(background != null ? enrichments::forgetQueued
                            : new com.aresstack.spaceharvester.persistence.files.FileEnrichmentQueue(
                                    cacheDirectory.resolve("enrichment-queue"))::removeSource)
                    .pageReleases(pageReleases)
                    .pageCache(cache)
                    .fullText(textIndex)
                    .enrichments(enrichmentStore)
                    .assets(assetStore, exportWriter, clock)
                    .termRelations(termRelations, resolutionCache)
                    .synonymCache(synonymCache)
                    .synonymVectors(synonymVectors)
                    .glossary(glossary)
                    .glossaryCache(glossaryCache)
                    .factEdits(factEditStore, clock)
                    .knowledgeGraph(graphStore, graph::forgetInputs)
                    .standing(statusQuo)
                    .inventoryLog(inventoryLog)
                    .build(), sourceId -> catalog.config(sourceId).isPresent(),
                    // Die Füllung bleibt für die ganze Bereinigung gesperrt, nicht nur beim Leeren ihrer Schlange: Sonst
                    // reihte sie die Seiten aus dem noch nicht geleerten Katalog wieder ein.
                    ((SourceDataPurge.Quiesce) sourceId -> {
                        BackgroundFill current = filling.get();
                        return current == null ? () -> { } : current.holdSource(sourceId);
                    }).andThen(sourceId -> scheduler.quiesce(sourceId, SourceDataPurge.JOB_STOP_TIMEOUT)));
            this.sourceManagement = new SourceManagement(catalog, jobs, purge);
            withdrawals.set(purge);
            jobActions.purge(purge);
            // Quellenübergreifende Aufträge lassen die Quellen aus, deren Löschen bei ihrem Start offen war (Abnahme
            // #1090): festgehalten am Auftrag, auch auf den Threads, die seine Arbeit startet.
            jobs.openWithdrawals(purge::openWithdrawals);
            access.withhold(sourceId -> Jobs.withheldSources().contains(sourceId));
            try {
                // Fundstelle 22: Löschen entfernter oder umgestellter Quellen setzt nach dem Neustart fort.
                sourceManagement.resumeWithdrawals();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Löschen entfernter Quellen nicht fortgesetzt ({0})",
                        e.getClass().getName());
            }
            start.step("Exporte, Bilder und Aussagen");
            this.reconciliation = new Reconciliation(syncs, OWNER, clock);
            opened.add(reconciliation);
            this.inventories = Reconciliation.inventories(syncs, OWNER, clock, inventoryLog);
            opened.add(inventories);
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
                fill.network(network);
                this.backgroundFill = fill;
                filling.set(fill);
                // Fundstelle 12: sichtbar als „Hintergrundfüllung“, je Quelle und Space ein Teil auf eigener Spur.
                com.aresstack.spaceharvester.application.background.FillJobs fillJobs =
                        new com.aresstack.spaceharvester.application.background.FillJobs(backgroundJobs, jobs);
                opened.add(fillJobs);
                fillJobs.network(network);
                fillJobs.attach(fill, key -> cache.findHeader(key).map(header -> header.metadata().partitionKey()));
                // ADR 0027: jeder Commit des Seiten-Cache weckt die Suche nach Lücken, nie in Abständen.
                cache.changes(cacheChanges.andThen(fill::wake));
            } else {
                this.backgroundFill = null;
            }
            if (manager != null) {
                scheduleConfigured(plannedSchedules(settings, false));
                scheduleInventories(plannedInventories(settings, false));
                catchUpOnStart();
                ConfigurationManager.Registration registration = manager.register(this::prepare);
                opened.add(registration::close);
            }
            if (backgroundFill != null) {
                backgroundFill.start();
            }
            // ADR 0026, Punkt 11 (AP5c): nach einem Wechsel der Extraktor-Version oder geänderten Grenzen liest ein
            // eigener Auftrag die abgelegten Dokumente neu, ohne den Start aufzuhalten.
            // Ohne Start ohne Klick (App) entfällt das; neu gelesen wird beim nächsten Abruf oder Synchronisieren.
            if (builder.autoStart) {
                rereads.afterChange(catalog.configs().stream().map(SourceSettingsConfig::id).toList(), OWNER);
            }
            // Hintergrundaufträge, die das Beenden unterbrochen hat, laufen von selbst weiter; erst jetzt, da alle Arten
            // angemeldet sind.
            if (builder.autoStart) {
                backgroundJobs.resumeInterrupted();
            } else {
                // Angelo 24.11.2026: „frisch gestartet, nichts angeklickt“ liefen schon 12 Aufträge. Unterbrochene
                // bleiben sichtbar stehen und laufen erst weiter, wenn jemand sie fortsetzt.
                backgroundJobs.keepInterrupted();
            }
            // Danach die Abgleiche beim Start; Quellen mit fortgesetztem Teil derselben Art fallen heraus. Ohne Start ohne
            // Klick (App, Angelo 24.11.2026) laufen „Verweise abgleichen“, „KI-Freigabe prüfen“ und „Analysen nach Update
            // nachrechnen“ nicht über alle Seiten an; nur ein unterbrochener Entzug einer KI-Freigabe läuft zu Ende,
            // damit die Ergebnisse entzogener Seiten nicht stehen bleiben.
            if (builder.autoStart) {
                startScans.forEach(Runnable::run);
            } else if (releaseRecomputation != null) {
                releaseRecomputation.resumeWithdrawals();
            }
            start.step("Zeitpläne und Nachholen");
            this.resources = List.copyOf(opened);
            start.done();
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

    /**
     * Der Cross-Encoder des Dienstes (#181), wenn die Konfiguration umordnen lässt; sonst keiner.
     *
     * @param pageReleased ob eine Seite auf der Liste freigegebener Seiten steht (ADR 0028)
     */
    private static SearchReranker modelReranker(HarvesterConfiguration configuration, ConfiguredSourceCatalog catalog,
                                                TrustBundles trustBundles, String userAgent,
                                                Predicate<PageKey> pageReleased) {
        if (!configuration.reranking()) {
            return SearchReranker.NONE;
        }
        AiServiceSettings ai = configuration.ai();
        return new ModelReranker(ai.rerankModel(), RemoteAi.of(ai, catalog, trustBundles, userAgent).reranker(),
                ai::mayProcessExternally).withPageReleases(pageReleased);
    }

    /**
     * Bereitet einen Wechsel der Konfiguration vor (ADR 0015): baut Embedding-Modell, Reranker und Zeitplan neu, wo
     * sich etwas geändert hat, und tauscht erst beim Aktivieren. Regeln je Quelle (Aktualität, Frist, Betriebsart)
     * liest jeder Abruf und jeder Lauf aus der dann wirksamen Konfiguration; laufende Aufträge enden mit ihren. Proxy
     * und Vertrauen ohne Bundle ({@code network.*}) prüft sie hier und stellt der Katalog beim Aktivieren um.
     */
    private ConfigurationListener.Preparation prepare(ConfigurationChange change) {
        HarvesterConfiguration next = change.next();
        NetworkSettings network = networkFollows && change.networkChanged() ? next.network() : null;
        if (network != null && !network.problems().isEmpty()) {
            throw new ConfigurationRejectedException(network.problems());
        }
        List<ConfiguredSchedule> planned = change.syncChanged() ? plannedSchedules(next, true) : null;
        List<ConfiguredSchedule> plannedInventories = change.syncChanged() ? plannedInventories(next, true) : null;
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
                reranker = modelReranker(next, catalog, trustBundles, userAgent, aiRelease.pages()::contains);
            } catch (RuntimeException e) {
                throw new ConfigurationRejectedException("ai." + AiServiceSettings.RERANK_MODEL,
                        "Das Rerank-Modell lässt sich nicht einsetzen (" + e.getClass().getSimpleName() + ")");
            }
        }
        EmbeddingGeneration nextModel = model;
        SearchReranker nextReranker = reranker;
        ConfigurationListener.Preparation releases = releaseRecomputation.prepare(change);
        return () -> {
            com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice storeBefore = statementAnalyzerChoice();
            settings = next;
            // Eine andere Ablage der Aussagenanalyse (statements.analyzer): Graph, Stand, Glossar, Fachsicht und Verben
            // lesen neu, ohne Klick (D6/D6a).
            KnowledgeGraph graph = this.knowledgeGraph;
            if (graph != null && storeBefore != next.analyzer().analyzer()) {
                graph.analysesChanged();
            }
            if (network != null) {
                // Vor dem Austausch der Modelle; die schon gebauten halten Route und Vertrauen über den Katalog.
                catalog.reconfigure(network);
            }
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
            if (plannedInventories != null) {
                scheduleInventories(plannedInventories);
            }
            // Erst nach dem Tausch: Ab hier fragt jede Berechnung, auch eine schon eingereihte, die neue Freigabe.
            releases.commit();
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
        return planned(configuration, strict, SyncSettings::interval, SyncSettings.INTERVAL);
    }

    /**
     * Die Bestandsinventuren aus {@code sync.spaces}: je Eintrag mit {@code inventory-interval} und Betriebsart seiner
     * Quelle; ohne Abstand keine.
     *
     * @throws ConfigurationRejectedException wie {@link #plannedSchedules}
     */
    private List<ConfiguredSchedule> plannedInventories(HarvesterConfiguration configuration, boolean strict) {
        return planned(configuration, strict, SyncSettings::inventoryInterval, SyncSettings.INVENTORY_INTERVAL);
    }

    private List<ConfiguredSchedule> planned(HarvesterConfiguration configuration, boolean strict,
                                             Function<SyncSettings, Duration> intervalOf, String intervalKey) {
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
            Duration interval = intervalOf.apply(rules);
            if (interval == null) {
                continue;
            }
            if (catalog.find(sourceId).isEmpty()) {
                problems.add(new ConfigurationProblem(SyncSettings.SPACES, "Unbekannte Quelle „" + sourceId + "“"));
                continue;
            }
            if (interval.compareTo(Reconciliation.MIN_INTERVAL) < 0) {
                problems.add(new ConfigurationProblem(intervalKey,
                        "Der Abstand muss mindestens eine Minute betragen"));
                continue;
            }
            planned.add(new ConfiguredSchedule(sourceId, entry.substring(slash + 1), interval, rules.mode()));
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

    /** Ersetzt die Bestandsinventuren aus der Konfiguration; laufende enden regulär. */
    private void scheduleInventories(List<ConfiguredSchedule> planned) {
        synchronized (configuredInventories) {
            for (ConfiguredSchedule previous : configuredInventories) {
                inventories.cancel(previous.sourceId(), previous.spaceKey());
            }
            configuredInventories.clear();
            for (ConfiguredSchedule schedule : planned) {
                inventories.schedule(schedule.sourceId(), schedule.spaceKey(), schedule.interval(), schedule.mode());
                configuredInventories.add(schedule);
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
            List<JobStatus> started = syncs.catchUpAll(OWNER, ChangeTrigger.STARTUP, sourceId -> {
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

    /**
     * Import eines JSON-Pakets ({@code export.zip} ab {@code json-package/3}) in den Cache einer eingerichteten Quelle,
     * ohne die Quelle zu fragen. Prüft keine {@code AccessPolicy}, wie {@link #sourceManagement()}.
     */
    public ExportPackageImport packageImport() {
        return packageImport;
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

    /**
     * Der Klartext einer Seite, wie ihn Anreicherungen, Volltextindex und Absätze sehen: Confluence-Storage, lokale
     * Dateien und Dokumente über discover/load (#187), der erste passende Projektor gewinnt. Rechnet nur über die
     * übergebene Payload, lädt nichts nach und prüft keine Rechte; wer den Snapshot hat, darf ihn lesen.
     *
     * @return die Projektion, oder leer, wenn kein Projektor das Format kennt
     * @throws IllegalArgumentException wenn die Payload das Format verletzt, das sie angibt
     */
    public Optional<PageText> pageText(PageSnapshot snapshot) {
        return pageTexts.project(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /**
     * Read a bounded remote media range after verifying page content permission.
     * Never expose provider credentials or transport details to desktop clients.
     */
    public byte[] mediaRange(String sourceId, String pageId, long offset, int maximumBytes) {
        return openMediaRange(sourceId, pageId).read(offset, maximumBytes);
    }

    /** Authorize the page once for a viewer session rather than for every media chunk. */
    public com.aresstack.spaceharvester.application.sources.MediaByteRange openMediaRange(
            String sourceId, String pageId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        HarvestSource source = catalog.find(sourceId)
                .orElseThrow(() -> new com.aresstack.spaceharvester.application.sources.UnknownSourceException(sourceId));
        com.aresstack.spaceharvester.application.sources.PageMetadata metadata = source.pageMetadata(pageId);
        access.require(caller, ResourceScope.page(sourceId, metadata.partitionKey(), pageId), AccessKind.CONTENT);
        return (offset, maximumBytes) -> {
            if (offset < 0 || maximumBytes < 1 || maximumBytes > 262144) {
                throw new IllegalArgumentException("Invalid media byte range");
            }
            return source.mediaRange(pageId, offset, maximumBytes);
        };
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

    /** Bestand je Space mit Zählern je Teilbaum und Meldungen bei jeder Änderung (ADR 0027). */
    /** Bilder und Anhänge beschaffen, für Exporte und für eine Auswahl ohne Export (ADR 0029, Schritt 7). */
    public ExportAssetCollection assetCollection() {
        return assetCollection;
    }

    /** Die Liste freigegebener Seiten für die Oberfläche (ADR 0028): Eintragen, Liste, Entfernen. */
    public PageReleaseService pageReleaseService() {
        return pageReleaseService;
    }

    public Holdings holdings() {
        return holdings;
    }

    /**
     * Die Freigabeprüfung für den KI-Dienst mit der Liste der freigegebenen Seiten (ADR 0028): Quelle in
     * {@code ai.external-processing-sources} oder Seite auf der Liste.
     */
    /**
     * Aus welcher Ablage Graph, Stand, Begriffe und Export lesen und welcher Analysator vorgewählt ist
     * ({@code space-harvester.statements.analyzer}, Fahrplan Paket A2/A3); Vorgabe MaltParser.
     */
    public com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice statementAnalyzerChoice() {
        HarvesterConfiguration current = this.settings;
        return current == null ? com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER
                : current.analyzer().analyzer();
    }

    public AiRelease aiRelease() {
        return aiRelease;
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
     * Die Dokumentanalyse der Fristen-Fachlogik (ADR 0035): Ergebnisse je Dokumentstand, Analysieren als Auftrag. Die
     * App schließt die Ablage an ({@code content(…)}), etwa die E-Akte.
     */
    public com.aresstack.spaceharvester.application.deadlines.DeadlineAnalysisService documentAnalyses() {
        return documentAnalyses;
    }

    /**
     * Live-Navigation (#177): Kindseiten in der Reihenfolge der Quelle mit {@code hasChildren}, Lage einer Seite mit
     * Space-Namen und Vorfahren, Querverweise; Rechte je Knoten.
     */
    /** Verzeichnis der Verweise zwischen lokal gespeicherten Seiten, beide Richtungen (Paket V1). */
    public PageLinks pageLinks() {
        return pageLinks;
    }

    /**
     * Strukturgraph (Paket G1): Zusammenhänge einer Seite aus Hierarchie, Verweisen, bestätigten Begriffsbeziehungen und
     * ähnlichen Abschnitten, jede Kante mit Art, Grundlage und Herkunft. Ergänzt den Wissensgraphen der OO-Analyse
     * ({@link #knowledgeGraph()}), ersetzt ihn nicht.
     */
    public PageConnections pageConnections() {
        return pageConnections;
    }

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
     * Dokumentnavigation einer Seite: Überschriften und erkannte Themenabschnitte (#181, ADR 0020); in der Leseansicht
     * ohne Aufrufer {@link com.aresstack.spaceharvester.application.passages.DocumentNavigationView#read} mit
     * {@link #enrichments()}.
     */
    public NavigationQueries documentNavigation() {
        return documentNavigation;
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

    /** KI-Akronymauflösung als Vorschlagslauf je Quelle (#122). */
    public AcronymResolution acronymResolution() {
        return acronymResolution;
    }

    /** Suchmethode „Synonyme der Fachbegriffe einbeziehen“ (K5a). */
    public GlossarySynonyms glossarySynonyms() {
        return glossarySynonyms;
    }

    /** Fachglossar: Pflege von Hand und Abgleich der KI-Läufe. */
    public Glossary glossary() {
        return glossary;
    }

    /** Wichtigkeit je Suchwert für „Nur Fachbegriffe“ (K5b), wie der Reiter „Fachglossar“; ohne Übersicht keine. */
    private java.util.Map<String, com.aresstack.spaceharvester.application.glossary.GlossaryDerivation.Importance>
            technicalImportance(AccessContext caller,
                                com.aresstack.spaceharvester.application.glossary.TechnicalTerms.Scope scope) {
        com.aresstack.spaceharvester.application.glossary.GlossaryOverview overview = this.glossaryOverview;
        if (overview == null || scope.sourceIds().size() > 1) {
            return java.util.Map.of();
        }
        String sourceId = scope.sourceIds().isEmpty() ? null : scope.sourceIds().iterator().next();
        String spaceKey = sourceId != null && scope.spaceKeys().size() == 1 ? scope.spaceKeys().iterator().next()
                : null;
        java.util.Map<String, com.aresstack.spaceharvester.application.glossary.GlossaryDerivation.Importance> result =
                new java.util.HashMap<>();
        overview.importance(caller, sourceId, spaceKey, () -> false).rows()
                .forEach(row -> result.put(row.normalized(), row.importance()));
        return result;
    }

    /** Suchmethode „Nur Fachbegriffe“ (K5b): Fachbegriffe zu einem Suchtext, auch über ihre Synonyme. */
    public com.aresstack.spaceharvester.application.glossary.TechnicalTerms technicalTerms() {
        return technicalTerms;
    }

    /** „Fachbegriffe ermitteln“: KI-Lauf nur auf ausdrücklichen Wunsch. */
    public GlossaryClassification glossaryClassification() {
        return glossaryClassification;
    }

    /** Teilzahlen und Einzelheiten der Begriffsableitung je Aufrufer (ADR 0032, FL5b), nichts gespeichert. */
    public com.aresstack.spaceharvester.application.glossary.GlossaryOverview glossaryOverview() {
        return glossaryOverview;
    }

    /**
     * Synonymerkennung auf Knopfdruck (#336): Kandidaten aus Regeln und Embedding-Vorauswahl, Bedeutungsprüfung über
     * das Chat-Modell, Vorschläge in {@link #termRelations()}. Nie beim Start, Abgleich, Suchen oder Öffnen.
     */
    public SynonymDiscovery synonymDiscovery() {
        return synonymDiscovery;
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
     * Meldet einen Empfänger für den Stand der Beschaffung an (ADR 0027), statt {@link #acquisitionStatus()} in
     * Abständen zu lesen: nach jeder Änderung von Planer und Hintergrundfüllung der jüngste Stand, gebündelt, auf dem
     * Zustell-Thread des Core. Erst anmelden, dann {@link #acquisitionStatus()} lesen.
     */
    public Subscription subscribeAcquisition(Consumer<AcquisitionStatus> listener) {
        Objects.requireNonNull(listener, "listener");
        if (backgroundFill != null) {
            return backgroundFill.subscribe(listener);
        }
        return acquisition.subscribe(status -> listener.accept(BackgroundFill.withoutFill(acquisition)));
    }

    /**
     * Progressive Suche (ADR 0017): die ursprüngliche Anfrage sofort, dann Varianten in Wellen, bewertet gegen die
     * ursprüngliche Anfrage, bis das Qualitätsziel des {@link SearchProfile} erreicht oder das Budget aufgebraucht ist.
     */
    public SearchOrchestrator progressiveSearch() {
        return progressiveSearch;
    }

    /** Suchmethode „Nachbarseiten“ (R2) zur Schlagwortsuche, nach einem Stapel; nur mit Haken des Benutzers. */
    public NeighborSearch neighborSearch() {
        return neighborSearch;
    }

    /**
     * Suchmethode „Fachbegriffe“ (W8) zur Schlagwortsuche ohne progressive Suche, als eigener Aufruf nach einem
     * Stapel; die progressive Suche nutzt dieselbe mit {@code SearchProfile#technicalTerms()}.
     */
    public com.aresstack.spaceharvester.application.query.progressive.TermSearch termSearch() {
        return termSearch;
    }

    /**
     * Die Seiten einer Suche für das Begriffswissen (W8): ihre Seitengrenze, sonst die Seiten ihrer Quellen (ohne
     * Angabe alle eingerichteten) und Spaces mit den Rechten des Aufrufers. Eine Quelle oder ein Space ohne Leserecht
     * fällt weg, statt die Suche zu sperren; ausgeschlossene Spaces fehlen schon im Bereich.
     */
    static com.aresstack.spaceharvester.application.snapshot.PageSet searchScope(Holdings holdings,
            List<String> configured, AccessContext caller, KeywordSearch.Query query) {
        if (query.pages() != null) {
            return withoutLocked(holdings, caller, query.pages());
        }
        List<String> sources = query.sourceId() != null ? List.of(query.sourceId())
                : !query.sourceIds().isEmpty() ? List.copyOf(query.sourceIds()) : configured;
        Set<String> excluded = query.excludedSpaceKeys();
        Map<String, List<String>> pages = new LinkedHashMap<>();
        for (String sourceId : sources) {
            List<Selection.Root> roots;
            if (!query.spaceKeys().isEmpty()) {
                roots = query.spaceKeys().stream().filter(space -> !excluded.contains(space))
                        .map(space -> Selection.Root.space(sourceId, space)).toList();
            } else if (excluded.isEmpty()) {
                roots = List.of(Selection.Root.source(sourceId));
            } else {
                // Ausgeschlossene Spaces fallen schon hier weg, damit sie die Grenze des Begriffswissens nicht
                // verbrauchen: erst die Spaces der Quelle, dann nur die übrigen.
                com.aresstack.spaceharvester.application.holdings.PageSelection whole =
                        resolved(holdings, caller, Selection.Root.source(sourceId));
                roots = whole == null ? List.of() : whole.spaces().stream()
                        .map(com.aresstack.spaceharvester.application.holdings.SpaceSummary::spaceKey).distinct()
                        .filter(space -> !excluded.contains(space))
                        .map(space -> Selection.Root.space(sourceId, space)).toList();
            }
            for (Selection.Root root : roots) {
                com.aresstack.spaceharvester.application.holdings.PageSelection selection =
                        resolved(holdings, caller, root);
                if (selection != null) {
                    // Gesperrte Seiten (404, ADR 0029) fallen wie ausgeschlossene Spaces vor der Grenze weg.
                    com.aresstack.spaceharvester.application.snapshot.PageSet locked = selection.locked();
                    selection.pages().asMap().forEach((source, ids) -> {
                        List<String> open = ids.stream().filter(id -> !locked.contains(source, id)).toList();
                        if (!open.isEmpty()) {
                            pages.computeIfAbsent(source, unused -> new ArrayList<>()).addAll(open);
                        }
                    });
                }
            }
        }
        return com.aresstack.spaceharvester.application.snapshot.PageSet.of(pages);
    }

    /** Die Seitengrenze der Anfrage ohne gesperrte Seiten; scheitert das Nachsehen, bleibt sie, wie sie ist. */
    private static com.aresstack.spaceharvester.application.snapshot.PageSet withoutLocked(Holdings holdings,
            AccessContext caller, com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        Map<com.aresstack.spaceharvester.application.snapshot.PageKey, Holdings.Located> located;
        try {
            located = holdings.locate(caller, pages);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Gesperrte Seiten der Seitengrenze nicht geprüft ({0})",
                    e.getClass().getName());
            return pages;
        }
        if (located == null || located.values().stream().noneMatch(where -> where.state()
                == com.aresstack.spaceharvester.application.holdings.HoldingState.LOCKED)) {
            return pages;
        }
        Map<String, List<String>> open = new LinkedHashMap<>();
        for (String sourceId : pages.sourceIds()) {
            for (String pageId : pages.pagesOf(sourceId)) {
                Holdings.Located where = located.get(
                        new com.aresstack.spaceharvester.application.snapshot.PageKey(sourceId, pageId));
                if (where == null || where.state()
                        != com.aresstack.spaceharvester.application.holdings.HoldingState.LOCKED) {
                    open.computeIfAbsent(sourceId, unused -> new ArrayList<>()).add(pageId);
                }
            }
        }
        return com.aresstack.spaceharvester.application.snapshot.PageSet.of(open);
    }

    /** Eine Wurzel mit den Rechten des Aufrufers; ohne Leserecht oder nicht (mehr) eingerichtet {@code null}. */
    private static com.aresstack.spaceharvester.application.holdings.PageSelection resolved(Holdings holdings,
            AccessContext caller, Selection.Root root) {
        try {
            return holdings.resolve(caller, Selection.of(root));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Bereich der Fachbegriffe ohne eine Wurzel ({0})",
                    e.getClass().getName());
            return null;
        }
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

    /**
     * Manuelle und geprüfte Fakten (#182): anlegen, bestätigen oder verwerfen, entfernen, Protokoll. Abgelegt unter
     * {@code <dataDirectory>/facts}, außerhalb des Caches.
     */
    public FactEdits factEdits() {
        return factEdits;
    }

    public Assets assets() {
        return assets;
    }

    /** Hält die Analysen von Videoseiten beim Transkript und seiner letzten Korrektur (Angelo 20.11.2026). */
    public com.aresstack.spaceharvester.application.presentation.TranscriptAnalyses transcriptAnalyses() {
        return transcriptAnalyses;
    }

    /** Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3): Modellaufruf und Ablage je Satz, nur für freigegebene Seiten. */
    public Simplifications simplifications() {
        return simplifications;
    }

    /** Prüfschritt (ADR 0032, FL4): Modellaufruf und Ablage je Aussage, nur für freigegebene Seiten. */
    public StatementChecks statementChecks() {
        return statementChecks;
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
     * „In PPTX umwandeln“ (Angelo 20.11.2026): aus einem Video mit Folienmarken eine PowerPoint-Datei mit Vortragstext
     * je Folie, als fünf Aufträge in der Auftragsliste.
     */
    /** „Suchqualität messen“ (Q1): Testbestand lesen, Auftrag starten und fortsetzen. */
    public com.aresstack.spaceharvester.application.quality.SearchQualityRuns searchQuality() {
        return searchQuality;
    }

    public com.aresstack.spaceharvester.application.presentation.VideoPresentations videoPresentations() {
        return videoPresentations;
    }

    /**
     * Aussagenanalyse einer Seite auf Knopfdruck (#499, ADR 0024): {@link StatementAnalysis#analyze} nur auf
     * ausdrückliche Anforderung, nie auf dem EDT; {@link StatementAnalysis#read} liest nur. Ohne
     * {@link Builder#statementAnalyzer} (Dienst) ist {@link StatementAnalysis#available()} {@code false}.
     */
    public StatementAnalysis statementAnalysis() {
        return statementAnalysis;
    }

    /**
     * Globaler Knowledge Graph über alle manuell analysierten Seiten (#499, ADR 0024), mit Rechteprüfung je Seite.
     * Liegt unter {@code <dataDirectory>/knowledge-graph}, außerhalb des Caches.
     */
    public KnowledgeGraph knowledgeGraph() {
        return knowledgeGraph;
    }

    /**
     * Wortschatz der Suchmethode „Fachbegriffe“ (W8): bestätigte Fachbegriffe und beide Enden bestätigter Synonyme,
     * die der Aufrufer sehen darf, normalisiert. Nur Wortgruppen daraus fragen das Begriffswissen.
     */
    java.util.Set<String> termVocabulary(AccessContext caller) {
        return termVocabulary(
                new com.aresstack.spaceharvester.application.glossary.GlossaryVocabulary(glossary, termRelations),
                caller);
    }

    /** Wie oben, aus einem Wortschatz. */
    static java.util.Set<String> termVocabulary(
            com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.Vocabulary vocabulary,
            AccessContext caller) {
        java.util.Set<String> words = new java.util.HashSet<>();
        java.util.function.Consumer<String> add = word -> words.add(
                com.aresstack.spaceharvester.application.terms.TermText.normalize(word).strip());
        vocabulary.terms(caller).forEach(term -> add.accept(term.term()));
        vocabulary.synonyms(caller).forEach(synonym -> {
            add.accept(synonym.fromTerm());
            add.accept(synonym.toTerm());
        });
        return words;
    }

    /**
     * Fachsicht des Wissensgraphen (Paket W1): bestätigte Fachbegriffe und Synonyme als Knoten, Verben der Aussagen als
     * Kanten; liest nur. Das Vertrauen je Beleg (W2) kommt aus dem Abgleich der Analysatoren (K2).
     */
    public com.aresstack.spaceharvester.application.knowledge.DomainTermGraph domainTermGraph() {
        return new com.aresstack.spaceharvester.application.knowledge.DomainTermGraph(knowledgeGraph,
                new com.aresstack.spaceharvester.application.glossary.GlossaryVocabulary(glossary, termRelations),
                this::statementAnalyzerChoice,
                com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.Comparison.of(analyzerComparison));
    }

    /**
     * Die Quellen, deren Aussagen der Knowledge Graph liest: eingerichtet und ohne offenes Löschen (#1090: entfernt,
     * umgestellt, wartend oder in Selbstheilung). Gilt für alle Leser des Graphen, also Fachsicht, Begriffswissen, Chat
     * und Suche.
     *
     * @param openWithdrawal der Auftrag, der die Daten der Quelle gerade löscht, oder {@code null}
     */
    static Predicate<String> analysedSources(Predicate<String> configured, Function<String, String> openWithdrawal) {
        Objects.requireNonNull(configured, "configured");
        Objects.requireNonNull(openWithdrawal, "openWithdrawal");
        return sourceId -> configured.test(sourceId) && openWithdrawal.apply(sourceId) == null;
    }

    /**
     * Begriffswissen für Chat und Suche (Paket W6) auf der Fachsicht: begrenzt, nur im Bereich, gelieferte Seiten an
     * die Meldung des Aufrufers. Liest nur.
     */
    public com.aresstack.spaceharvester.application.knowledge.TermKnowledge termKnowledge() {
        return new com.aresstack.spaceharvester.application.knowledge.TermKnowledge(domainTermGraph(), aiRelease);
    }

    /** Verzeichnis der Verben einer Seitenmenge aus den Aussagen (Paket K1), mit Rechteprüfung je Seite. */
    public com.aresstack.spaceharvester.application.knowledge.VerbQueries verbQueries() {
        return verbQueries;
    }

    /** Abgleich der beiden Aussagenanalysatoren je Seite (K2); rechnet auf Abruf, speichert nichts. */
    public com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison analyzerComparison() {
        return analyzerComparison;
    }

    /** Stand und Status-quo-Sicht (ADR 0032, FL6). */
    public com.aresstack.spaceharvester.application.standing.StatusQuo statusQuo() {
        return statusQuo;
    }

    /** Leseempfehlung je Auswahl und Thema (ADR 0032, FL6). */
    public com.aresstack.spaceharvester.application.standing.ReadingAdvice readingAdvice() {
        return readingAdvice;
    }

    /**
     * Aussagenanalyse für eine Seite, einen Unterzweig, einen Space oder eine Quelle als ausdrücklich gestarteter
     * Auftrag (#499 Abschnitt E, ADR 0024); nur die Desktop-App startet ihn auf Nutzerwunsch.
     */
    public StatementAnalysisJobs statementAnalysisJobs() {
        return statementAnalysisJobs;
    }

    /**
     * JSON-LD-Export des Knowledge Graph als Auftrag (#499 Abschnitt G, ADR 0024); liest nur vorhandene Analysen und
     * stößt nie eine an.
     */
    /** Confluence-Metadaten und Analytics als eigene Exporte aus der Quelle (Angelo 16.11.2026). */
    public SourceQueryExports sourceQueryExports() {
        return sourceQueryExports;
    }

    public KnowledgeGraphExportUseCase knowledgeGraphExport() {
        return knowledgeGraphExport;
    }

    /** Startet den Graph-Export in {@code <exportDirectory>/knowledge-graph}; der Aufrufer wartet nicht. */
    public JobStatus startKnowledgeGraphExport(AccessContext caller, KnowledgeGraphExportUseCase.Scope scope,
                                               boolean includeStale) {
        return knowledgeGraphExport.start(caller,
                new KnowledgeGraphExportUseCase.Request(scope, includeStale, knowledgeGraphExportDirectory));
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
     * Die Proxy-Route je Ziel aller Quellen; die Oberfläche zeigt {@link ProxyRoutes#pacState()} („Proxy-Skript seit …
     * nicht erneuert“) und hört mit {@link ProxyRoutes#onPacChange} darauf.
     */
    public ProxyRoutes proxyRoutes() {
        return catalog.routes();
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

    /** Wie {@link #aiModels(AiServiceSettings)} mit den wirksamen Einstellungen; ohne Dienst leer. */
    public List<AiModel> aiModels() {
        AiServiceSettings current = currentAi.get();
        return current == null ? List.of() : aiModels(current);
    }

    /**
     * Der Chat-Port des KI-Dienstes der wirksamen Konfiguration für den Chat der App (ADR 0012: der Chat selbst
     * bleibt außerhalb des Core): Route, TLS, Token und Frist wie {@link #aiModels}; {@code null} ohne Dienst oder
     * Chat-Modell. Je Aufruf frisch gebaut, damit geänderte Einstellungen ohne Neustart gelten (ADR 0015).
     */
    public AiChat aiChat() {
        AiServiceSettings current = currentAi.get();
        if (current == null || !current.chat()) {
            return null;
        }
        try {
            return RemoteAi.of(current, catalog, trustBundles, userAgent).chat();
        } catch (IllegalArgumentException notConfigured) {
            // Etwa ein nicht eingerichtetes trust-bundle: dann kein Chat, wie ohne Modell.
            return null;
        }
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

    /** „Neu berechnen“ einer Seite als gewöhnlicher Auftrag {@code ENRICHMENT} (Fundstelle 21). */
    public com.aresstack.spaceharvester.application.enrichment.PageRecomputation pageRecomputation() {
        return pageRecomputation;
    }

    /** Vorgabe für {@link Builder#maxTextFileBytes}: 200 MiB. */
    public static final long DEFAULT_MAX_TEXT_FILE_BYTES = 200L * 1024 * 1024;

    /** Verbindungsprüfungen, die länger als eine Sekunde dauern, als Hintergrundteil (Fundstelle 17). */
    public com.aresstack.spaceharvester.application.background.ConnectionChecks connectionChecks() {
        return connectionChecks;
    }

    /** „Anhänge vorladen“ als Hintergrundteil je Quelle und Space (Fundstelle 18). */
    /**
     * Der Netzzustand (Angelo 22.11.2026): offline nach einem Netzfehler, zurück mit dem ersten Abruf mit Antwort;
     * Netz-Aufträge warten offline, statt zu scheitern. Beobachter über {@code network().subscribe(...)}.
     */
    public com.aresstack.spaceharvester.application.network.NetworkState network() {
        return network;
    }

    public com.aresstack.spaceharvester.application.background.AttachmentPrefetches attachmentPrefetches() {
        return attachmentPrefetches;
    }

    /** Abgleich eines Space in den Cache als Auftrag; Status und Delta über {@link SpaceSyncJobs#status}. */
    /** „Anhänge ablegen“ einer Seite als Auftrag (Fundstelle 24). */
    public com.aresstack.spaceharvester.application.assets.PageAssetHarvests pageAssetHarvests() {
        return pageAssetHarvests;
    }

    public SpaceSyncJobs syncs() {
        return syncs;
    }

    /** Indexieren: markierte Teilbäume vollständig laden und aktuell halten, samt Bildern und Anhängen. */
    public Indexing indexing() {
        return indexing;
    }

    /** Auslöser ohne ausdrücklichen Klick auf genau diese Seite (ADR 0037, Punkt 10). */
    private static boolean automatic(ChangeTrigger trigger) {
        return trigger == ChangeTrigger.SEARCH || trigger == ChangeTrigger.BACKGROUND
                || trigger == ChangeTrigger.STARTUP || trigger == ChangeTrigger.SCHEDULE
                || trigger == ChangeTrigger.EVENT;
    }

    /** Ausschließen: was der Benutzer ausgeschlossen hat, fehlt in Baum und Suche und wird nicht indexiert. */
    public Exclusions exclusions() {
        return exclusions;
    }

    /** Die über die Navigationsbefüllung erworbenen Ressourcen (ADR 0037, Punkt 3). */
    public com.aresstack.spaceharvester.application.sync.NavigationMarks navigationMarks() {
        return navigationMarks;
    }

    /** Bilder und Anhänge einer markierten Wurzel beschaffen; vor dem Aufbau der Beschaffung nichts. */
    private void collectIndexedAssets(String sourceId, String spaceKey, String pageId) {
        ExportAssetCollection collection = assetCollection;
        if (collection != null) {
            collection.collect(OWNER, new Selection.Root(sourceId, spaceKey, pageId));
        }
    }

    /** Folgearbeit nach einem Abgleich: Bilder und Anhänge markierter Teilbäume ({@link Indexing}). */
    private void indexed(SpaceSync.SyncRun run) {
        Indexing current = this.indexing;
        if (current != null) {
            current.committed(run);
        }
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

    /**
     * „Neu lesen“ (ADR 0026, Punkt 11): liest die abgelegten Dokumente einer Quelle ohne Download neu, als eigener,
     * anhaltbarer Auftrag. Der bisherige Text bleibt lieferbar und durchsuchbar, bis das neue Lesen Text liefert.
     *
     * @param statuses leer für alle Dokumente ohne Extraktion der eingestellten Version (etwa nach einem Versionswechsel
     *                 oder Import), sonst nur Dokumente, deren Extraktion einen dieser Status hat (etwa nach geänderten
     *                 Grenzen)
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Leserecht auf die Quelle
     */
    public JobStatus reread(String sourceId, Set<ExtractionStatus> statuses) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        return rereads.start(sourceId, OWNER, statuses);
    }

    /**
     * Aktionen auf Aufträgen (Anhalten, Fortsetzen, Jetzt starten, Abbrechen, Löschen) mit Prüfung am Zustandsobjekt;
     * Fortsetzen bleibt im selben Eintrag, auch nach einem Neustart.
     */
    public JobActions jobActions() {
        return jobActions;
    }

    /**
     * Hintergrundaufträge (ADR 0025 Nachtrag): Gruppen mit einem Unterauftrag je Space, nachrangig, nach einem Neustart
     * fortgesetzt; Arten melden sich mit {@code register} an.
     */
    public com.aresstack.spaceharvester.application.background.BackgroundJobs backgroundJobs() {
        return backgroundJobs;
    }

    /**
     * Der Dienst-Planer (D1, Nachtrag ADR 0025): startet ein Analyseziel samt fehlender Vorstufen als eine
     * Hintergrundgruppe, je Stufe und Space ein Unterauftrag.
     */
    public com.aresstack.spaceharvester.application.background.services.ServicePlanner servicePlanner() {
        return servicePlanner;
    }

    /** „Wissensgraph erzeugen“ (W5): der Abschlussteil je Space als Ziel des Dienst-Planers. */
    public com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs knowledgeGraphJobs() {
        return knowledgeGraphJobs;
    }

    /**
     * Die Beschreibungen der Analysedienste (D1). Die Aussagenanalyse arbeitet über ihre Art (D3), sofern
     * {@code statements} angeschlossen ist, „Bilder beschreiben“ über seine (D2), sofern {@code images} angeschlossen
     * ist, der „Wissensgraph“ als Abschlussteil je Space (W5), sofern {@code graph} angeschlossen ist; die übrigen Arten
     * fehlen noch. Die Starttypen ändern am heutigen Verhalten nichts.
     *
     * @param statements die Aussagenanalyse oder {@code null}, dann nur als Beschreibung
     * @param images     „Bilder beschreiben“ oder {@code null}, dann nur als Beschreibung
     * @param graph      der Abschlussteil „Wissensgraph“ oder {@code null}, dann nur als Beschreibung
     */
    static com.aresstack.spaceharvester.application.background.services.ServicePlanner services(
            com.aresstack.spaceharvester.application.background.BackgroundJobs background,
            java.util.function.BooleanSupplier imagesAutomatic, java.util.function.BooleanSupplier glossaryAutomatic,
            AccessGuard access,
            java.util.function.Function<PageKey, java.util.Optional<String>> spaceOf,
            StatementAnalysisJobs statements,
            com.aresstack.spaceharvester.application.images.ImageDescriptionJobs images,
            com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs graph) {
        com.aresstack.spaceharvester.application.background.services.AnalysisService.Missing visibleOf =
                (caller, pages) -> {
                    Map<com.aresstack.spaceharvester.application.background.services.MissingPages.Scope,
                            java.util.Set<String>> visible = new java.util.LinkedHashMap<>();
                    int hidden = 0;
                    for (Map.Entry<String, java.util.Set<String>> source : pages.asMap().entrySet()) {
                        for (String pageId : source.getValue()) {
                            String space = spaceOf.apply(new PageKey(source.getKey(), pageId)).orElse(null);
                            if (access.canRead(caller, source.getKey(), space, pageId)) {
                                visible.computeIfAbsent(new com.aresstack.spaceharvester.application.background
                                        .services.MissingPages.Scope(source.getKey(), space),
                                        ignored -> new java.util.TreeSet<>()).add(pageId);
                            } else {
                                hidden++;
                            }
                        }
                    }
                    return new com.aresstack.spaceharvester.application.background.services.MissingPages(visible,
                            hidden);
                };
        var planner = new com.aresstack.spaceharvester.application.background.services.ServicePlanner(background,
                visibleOf);
        var manual = (java.util.function.Supplier<
                com.aresstack.spaceharvester.application.background.services.ServiceStart>) () ->
                com.aresstack.spaceharvester.application.background.services.ServiceStart.MANUAL;
        var automatic = (java.util.function.Supplier<
                com.aresstack.spaceharvester.application.background.services.ServiceStart>) () ->
                com.aresstack.spaceharvester.application.background.services.ServiceStart.AUTOMATIC;
        var nothing = com.aresstack.spaceharvester.application.background.services.AnalysisService.Missing.NOTHING;
        planner.register(new com.aresstack.spaceharvester.application.background.services.AnalysisService(
                "index", "Indexieren", automatic, List.of(), null, nothing));
        planner.register(new com.aresstack.spaceharvester.application.background.services.AnalysisService(
                "images", "Bilder beschreiben", () -> imagesAutomatic.getAsBoolean()
                ? com.aresstack.spaceharvester.application.background.services.ServiceStart.AUTOMATIC
                : com.aresstack.spaceharvester.application.background.services.ServiceStart.MANUAL,
                List.of("index"), images == null ? null
                : com.aresstack.spaceharvester.application.images.ImageDescriptionJobs.KIND,
                images == null ? nothing : (caller, pages) -> visibleOf.of(caller, images.missing(caller, pages)),
                images == null ? com.aresstack.spaceharvester.application.background.services.AnalysisService
                        .Parameters.NONE : images::plannerParameters));
        planner.register(statements == null
                ? new com.aresstack.spaceharvester.application.background.services.AnalysisService(
                "statements", "Aussagenanalyse", manual, List.of("index"), null, nothing)
                : new com.aresstack.spaceharvester.application.background.services.AnalysisService(
                "statements", "Aussagenanalyse", manual, List.of("index"), StatementAnalysisJobs.KIND,
                (caller, pages) -> missingPerSpace(statements.missing(caller, pages), spaceOf),
                statements::plannerParameters));
        planner.register(new com.aresstack.spaceharvester.application.background.services.AnalysisService(
                "glossary", "Glossar", () -> glossaryAutomatic.getAsBoolean()
                ? com.aresstack.spaceharvester.application.background.services.ServiceStart.AUTOMATIC
                : com.aresstack.spaceharvester.application.background.services.ServiceStart.MANUAL,
                List.of("index"), null, nothing));
        planner.register(new com.aresstack.spaceharvester.application.background.services.AnalysisService(
                "knowledge-graph", "Wissensgraph", manual, List.of("statements", "images"), graph == null ? null
                : com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.KIND, nothing,
                graph == null ? com.aresstack.spaceharvester.application.background.services.AnalysisService
                        .Parameters.NONE : graph::plannerParameters));
        return planner;
    }

    /**
     * Die fehlenden Seiten der Aussagenanalyse je Quelle und Space (D1/D3); den Space nennt der gespeicherte Kopf, denn
     * fehlend sind nur lokale Seiten. Die Zahl der erst zu ladenden Seiten geht mit (W5), ein fehlender Zugriff ist sie
     * nicht.
     */
    static com.aresstack.spaceharvester.application.background.services.MissingPages missingPerSpace(
            StatementAnalysisJobs.Missing missing,
            java.util.function.Function<PageKey, java.util.Optional<String>> spaceOf) {
        Map<com.aresstack.spaceharvester.application.background.services.MissingPages.Scope, java.util.Set<String>>
                grouped = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, java.util.Set<String>> source : missing.pages().asMap().entrySet()) {
            for (String pageId : source.getValue()) {
                String space = spaceOf.apply(new PageKey(source.getKey(), pageId)).orElse(null);
                grouped.computeIfAbsent(new com.aresstack.spaceharvester.application.background.services.MissingPages
                        .Scope(source.getKey(), space), ignored -> new java.util.TreeSet<>()).add(pageId);
            }
        }
        return new com.aresstack.spaceharvester.application.background.services.MissingPages(grouped, 0,
                missing.toLoad());
    }

    /** „Bilder beschreiben“ über das Auftragssystem (D2): Hintergrundläufe, Knopf je Seite, fehlende Beschreibungen. */
    public com.aresstack.spaceharvester.application.images.ImageDescriptionJobs imageDescriptions() {
        return imageDescriptions;
    }

    /**
     * Die Seiten der Menge, die „Wissensgraph erzeugen“ (W5) ohne KI-Freigabe ausließe, jede nur einmal: mit dem
     * KI-Analysator die der Aussagenanalyse ({@link StatementAnalysisJobs#notReleased}), dazu immer die mit Bildern,
     * die „Bilder beschreiben“ ohne Freigabe nicht beschreibt
     * ({@link com.aresstack.spaceharvester.application.images.ImageDescriptionJobs#missingNotReleased}), nur die, die
     * der Aufrufer lesen darf. Fragt weder Quelle noch Modell.
     */
    public com.aresstack.spaceharvester.application.snapshot.PageSet knowledgeGraphNotReleased(AccessContext caller,
            com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        java.util.Set<PageKey> keys = new java.util.LinkedHashSet<>();
        StatementAnalysisJobs statements = statementAnalysisJobs;
        if (statements != null && statements.selected()
                == com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL) {
            keys.addAll(keysOf(statements.notReleased(caller, pages)));
        }
        com.aresstack.spaceharvester.application.images.ImageDescriptionJobs images = imageDescriptions;
        if (images != null) {
            for (PageKey key : keysOf(images.missingNotReleased(caller, pages))) {
                String space = pageCache.findHeader(key).map(header -> header.metadata().partitionKey()).orElse(null);
                if (access.canRead(caller, key.sourceId(), space, key.pageId())) {
                    keys.add(key);
                }
            }
        }
        return com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(keys);
    }

    private static List<PageKey> keysOf(com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        List<PageKey> keys = new java.util.ArrayList<>();
        pages.asMap().forEach((sourceId, pageIds) -> pageIds.forEach(pageId ->
                keys.add(new PageKey(sourceId, pageId))));
        return keys;
    }

    /** „Seiten nachladen“ als Hintergrundauftrag, etwa fehlende Nachbarseiten eines Suchtreffers. */
    public com.aresstack.spaceharvester.application.background.PageLoads pageLoads() {
        return pageLoads;
    }

    /** Alle Aufträge dieses Harvesters. */
    /**
     * Wie viele Downloads (Bilder, Anhänge, Exporte aus der Quelle) höchstens gleichzeitig vorbereiten
     * ({@code jobs.parallel-preparations}).
     */
    public int parallelPreparations() {
        return jobs.parallelPreparations();
    }

    /** Ändert {@link #parallelPreparations()} sofort (1 bis {@value JobLimits#MAX_PARALLEL_PREPARATIONS}). */
    public void parallelPreparations(int value) {
        jobs.parallelPreparations(value);
    }

    /**
     * Wie viele Downloads (Bilder, Anhänge, Exporte aus der Quelle) höchstens gleichzeitig laden
     * ({@code jobs.parallel-downloads}).
     */
    public int parallelDownloads() {
        return jobs.parallelDownloads();
    }

    /**
     * Ändert {@link #parallelDownloads()} sofort (0 bis {@value JobLimits#MAX_PARALLEL_DOWNLOADS}); bei 0 bereiten
     * Downloads nur vor und warten dann, bis wieder geladen werden darf.
     */
    public void parallelDownloads(int value) {
        jobs.parallelDownloads(value);
        // Laufende Downloads laufen zu Ende: ihre Plätze bleiben, bis sie fertig sind.
        assets.downloadLimit(Math.max(Math.max(value, jobs.downloadingNow()), connections.total()));
    }

    /**
     * Gleichzeitige Verbindungen aller ladenden Bilder- und Anhängeaufträge zusammen ({@code jobs.download-connections},
     * Angelo 11.11.2026), nach geschätzter Restzeit auf sie verteilt ({@link ConnectionBudget}).
     */
    public int downloadConnections() {
        return connections.total();
    }

    /**
     * Ändert {@link #downloadConnections()} sofort (0 bis {@value JobLimits#MAX_DOWNLOAD_CONNECTIONS}); bei 0 laden
     * Bilder- und Anhängeaufträge nichts und warten. Textdownloads betrifft das nicht.
     */
    public void downloadConnections(int value) {
        connections.total(value);
        assets.downloadLimit(Math.max(Math.max(jobs.parallelDownloads(), jobs.downloadingNow()), value));
    }

    /**
     * Durchschnittliche Dauer je tatsächlich von der Quelle geladener Datei, über alle Exporte und Neustarts, für die
     * Restzeit eines Bilder- ({@link JobType#ASSETS}) oder Anhängeauftrags ({@link JobType#ATTACHMENTS}); vor der
     * ersten Datei ein Startwert. Ein Transfer, ohne Parallelität und ohne Treffer aus dem Speicher.
     *
     * @throws IllegalArgumentException für andere Auftragsarten
     */
    public java.time.Duration averageDownload(JobType type) {
        return switch (type) {
            case ASSETS -> assets.durations().average(DownloadDurations.Kind.IMAGE);
            case ATTACHMENTS -> assets.durations().average(DownloadDurations.Kind.DOCUMENT);
            default -> throw new IllegalArgumentException("keine Downloads: " + type);
        };
    }

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
     * Vorschau für „Bereinigen“ (ADR 0037, Punkt 3): Seiten des Space ohne Erwerbsgrund und mit unbekanntem Grund,
     * nach einer frischen Inventur; schreibt nichts. Fragt die Quelle, also nie auf dem EDT.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne {@code SYNC} für den Space
     */
    public SpaceSyncJobs.CleanupPreview cleanupPreview(String sourceId, String spaceKey) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        requireActive(sourceId);
        return syncs.cleanupPreview(sourceId, spaceKey);
    }

    /**
     * {@link #cleanupPreview} für jeden abgeglichenen Bereich der Quelle, in der Reihenfolge der Bereiche.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne {@code SYNC} für die Quelle
     */
    public java.util.List<SpaceSyncJobs.CleanupPreview> cleanupPreviews(String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.SYNC);
        requireActive(sourceId);
        return syncs.confirmedSpaces().stream().filter(space -> space.sourceId().equals(sourceId))
                .map(space -> syncs.cleanupPreview(sourceId, space.spaceKey())).toList();
    }

    /**
     * „Bereinigen“: entfernt von {@code pageIds} die Seiten, die nach einer neuen Inventur noch keinen Erwerbsgrund
     * haben, aus dem lokalen Bestand; Originale in der Quelle bleiben unberührt. Ein Abgleichsauftrag.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne {@code SYNC} für den Space
     */
    public JobStatus cleanUp(String sourceId, String spaceKey, java.util.Set<String> pageIds) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        requireActive(sourceId);
        return syncs.startCleanup(sourceId, spaceKey, OWNER, pageIds);
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

    /** Änderungsprotokoll und Updates je Quelle (ADR 0030), mit den Rechten des Aufrufers je Seite. */
    public ChangeLog changeLog() {
        return changeLog;
    }

    /** Der Erklär-Podcast einer Seite (Angelo 21.11.2026). */
    /**
     * Ein Schaubild zu {@code topic} aus {@code material} (Chat, Angelo 27.11.2026), gezeichnet wie die Bilder im
     * Podcast mit den Zeilen „Bildgenerierung – Plan“ und „– Einzelteile“; die Einzelteile laufen höchstens
     * {@code ai.parallel} gleichzeitig. Leer, wenn kein Modell eingestellt ist oder kein brauchbarer Plan entsteht.
     * Nie auf dem EDT.
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.podcast.DiagramComposer.Diagram> composeDiagram(
            String topic, String material) {
        var models = diagramModels;
        if (models == null || !models.available()) {
            return java.util.Optional.empty();
        }
        AiServiceSettings ai = currentAi.get();
        int parallel = ai == null ? AiServiceSettings.DEFAULT_PARALLEL : ai.parallel();
        java.util.concurrent.ExecutorService parts = java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(1, parallel), work -> {
                    Thread thread = new Thread(work, "chat-schaubild");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            return new com.aresstack.spaceharvester.application.podcast.DiagramComposer(models, parts, attempt -> { })
                    .compose(topic, material, com.aresstack.spaceharvester.application.podcast.DiagramComposer
                            .Memory.NONE);
        } finally {
            parts.shutdownNow();
        }
    }

    public com.aresstack.spaceharvester.application.podcast.PagePodcasts pagePodcasts() {
        return pagePodcasts;
    }

    /** Die Update-Zusammenfassung als Podcast (Angelo 21.11.2026). */
    public com.aresstack.spaceharvester.application.podcast.UpdatePodcasts podcasts() {
        return podcasts;
    }

    /** Die Zeilen des Änderungsprotokolls einer Quelle (ADR 0030) ohne Rechteprüfung, für Tests. */
    List<ChangeEntry> changeEntries(String sourceId) {
        return pageCache.changeEntries(sourceId);
    }

    /** Die Update-Marken einer Quelle (ADR 0030), für Tests. */
    List<UpdateMark> updateMarks(String sourceId) {
        return pageCache.marks(sourceId);
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
                    started.add(syncs.catchUp(space.sourceId(), space.spaceKey(), OWNER, null, SyncMode.SYNC_CACHE,
                            ChangeTrigger.STARTUP));
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

    /**
     * Die eingeplanten Bestandsinventuren aus {@code sync.inventory-interval} mit letztem und nächstem Start, soweit
     * {@code SYNC} sie freigibt; nur mit Konfiguration.
     */
    public List<Reconciliation.Schedule> inventories() {
        return inventories.schedules().stream()
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
        return exports.start(exportAccess().apply(ExportRequest.of(sourceId, spaceKey, OWNER, formats)
                .withRefresh(refresh)));
    }

    /**
     * Lese- und Exportprüfung eines Exports dieser App: jede Seite nur, wenn die Richtlinie sie dem Aufrufer zeigt, und
     * der ganze Lauf nur, solange {@code EXPORT} und {@code CONTENT} für den Space freigegeben sind. Gilt beim Start und
     * ebenso beim Fortsetzen, Nachladen und Wiederanlauf, auch nach einem Neustart aus den abgelegten Angaben.
     */
    private UnaryOperator<ExportRequest> exportAccess() {
        return request -> request
                .withVisible(header -> access.canRead(caller, header.key().sourceId(),
                        header.metadata().partitionKey(), header.key().pageId()))
                .withAuthorized(() -> exportPermitted(request.sourceId(), request.spaceKey()));
    }

    /**
     * Führt eine Aktion auf einem Auftrag dieser App aus ({@link JobActions#perform}); Exporte prüfen dabei Lesbarkeit
     * und Exportrecht wie beim Start ({@link #exportAccess()}); Hintergrundteile eines Benutzers setzt nur er selbst
     * fort ({@link #caller()}).
     */
    public JobStatus performJobAction(String jobId, JobAction action) {
        return jobActions.perform(jobId, OWNER, action, exportAccess(), caller);
    }

    /** Setzt einen Export dieser App im selben Eintrag fort, mit Lese- und Exportprüfung wie beim Start. */
    public JobStatus resumeExport(String exportId) {
        return exports.resume(exportId, OWNER, exportAccess());
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
     * Löscht einen Auftrag in jedem Zustand samt seinen Diagnoseereignissen; einen wartenden oder laufenden bricht das
     * vorher ab ({@link Jobs#delete}). Das Exportartefakt im Exportverzeichnis verschwindet mit dem Auftrag beim
     * nächsten Aufräumen; anderswo gespeicherte Kopien bleiben.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException wenn es den Auftrag nicht gibt
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
     * Löscht alle Aufträge in jedem Zustand samt ihren Ereignissen; wartende und laufende werden vorher abgebrochen.
     *
     * @return Anzahl gelöschter Aufträge
     */
    public int deleteAllJobs() {
        return jobs.deleteAll(OWNER, null);
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
     * Wartet, bis der Auftrag einen Endzustand erreicht hat, ohne Abfrage im Takt (ADR 0027, {@link Jobs#await}).
     *
     * @return der Endzustand ({@link JobState#COMPLETED}, {@link JobState#FAILED} oder {@link JobState#CANCELLED})
     * @throws TimeoutException     wenn der Auftrag nach {@code timeout} noch läuft; er läuft weiter
     * @throws InterruptedException wenn der wartende Thread unterbrochen wird; der Auftrag läuft weiter
     */
    public JobStatus await(String jobId, Duration timeout) throws TimeoutException, InterruptedException {
        return jobs.await(jobId, OWNER, timeout);
    }

    /** Beendet Aufträge, schließt die Indizes und die Verbindungen der Quellen. */
    @Override
    public void close() {
        // Vor allem anderen (Abnahme #1030): laufende Hintergrundaufträge gelten als unterbrochen, bevor der Planer
        // ihre Abrufe abwirft; sonst endeten sie als gescheitert und setzten nach dem Neustart nicht fort.
        try {
            if (jobs != null) {
                jobs.interruptBackground();
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Hintergrundaufträge nicht als unterbrochen markiert ({0})",
                    e.getClass().getName());
        }
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
        private StatementAnalyzer statementAnalyzer;
        private String userAgent = "space-harvester-core";
        private ProxySettings proxy = ProxySettings.disabled();
        private TrustBundles trustBundles = TrustBundles.NONE;
        private TrustSettings trust = TrustSettings.jvm();
        private ConfiguredSourceCatalog.AsyncTransport asyncTransport = ConfiguredSourceCatalog.AsyncTransport.WEB_CLIENT;
        private AccessPolicy accessPolicy = new TrustedCaller();
        private JobLimits jobLimits = JobLimits.DEFAULTS;
        private Duration searchTimeout = Duration.ofSeconds(5);
        private Duration checkTimeout = PageFreshness.DEFAULT_TIMEOUT;
        private Duration largeFileTimeout = PageFreshness.DEFAULT_LARGE_FILE_TIMEOUT;
        private int maxTreeNodes = 50_000;
        private com.aresstack.spaceharvester.source.network.SourceTimeouts sourceTimeouts = com.aresstack.spaceharvester.source.network.SourceTimeouts.DEFAULTS;
        private com.aresstack.spaceharvester.application.query.QueryLimits queryLimits =
                com.aresstack.spaceharvester.application.query.QueryLimits.DEFAULTS;
        private int maxPageBytes = LucenePageCache.DEFAULT_MAX_PAYLOAD_BYTES;
        private boolean localOriginalReferences = true;
        private long maxTextFileBytes = DEFAULT_MAX_TEXT_FILE_BYTES;
        private boolean prefetchSearchHits = true;
        private boolean prefetchSearchHitsFixed;
        private int discoveryQueries = KeywordSearch.DEFAULT_MAX_QUERIES;
        private boolean excerptRanking = true;
        private boolean searchRuns = true;
        private Duration searchRunMaxAge = DEFAULT_SEARCH_RUN_MAX_AGE;
        private Predicate<AccessContext> searchRunAdministrators = context -> false;
        private int searchLoads = KeywordSearch.DEFAULT_LOADS;
        private boolean searchLoadsSet;
        private CandidateSelectionStrategy.Kind selectionKind;
        private CandidateSelectionStrategy selection;
        /** Ob beim Start Aufträge ohne Klick anlaufen ({@link #autoStart}). */
        private boolean autoStart = true;
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
        private long maxAssetBytes;
        private long largeAssetThresholdBytes = LargeFileGate.DEFAULT_THRESHOLD_BYTES;
        private ExtractionLimits extractionLimits = ExtractionLimits.DEFAULTS;
        private int maxConcurrentLargeAssets = LargeFileGate.DEFAULT_MAX_CONCURRENT;
        private Duration signInWait = Duration.ZERO;
        private boolean changeEvents;
        private final Map<String, char[]> webhookSecrets = new HashMap<>();
        private ImageDescriber imageDescriber;
        private StatementSettings statements = StatementSettings.DEFAULT;
        private TermSettings terms = TermSettings.DEFAULT;
        private com.aresstack.spaceharvester.application.configuration.ImageSettings images =
                com.aresstack.spaceharvester.application.configuration.ImageSettings.DEFAULT;
        private EnrichmentSettings enrichment = EnrichmentSettings.DEFAULT;
        private AiServiceSettings ai = AiServiceSettings.NONE;
        private int maxChunkChars = 0;
        private int chunkTokens = KnowledgeTextSegmentation.DEFAULT_MAX_TOKENS;
        private int chunkOverlapSentences = KnowledgeTextSegmentation.DEFAULT_OVERLAP_SENTENCES;
        private final List<SourceAdapter> sourceAdapters = new ArrayList<>();
        private final List<EnrichmentProcessor> additionalProcessors = new ArrayList<>();
        private final List<java.util.function.Function<java.util.function.Function<PageKey, Optional<PageSnapshot>>, EnrichmentProcessor>> snapshotProcessors = new ArrayList<>();
        private SourceSecrets secretProvider;
        private EmbeddingProvider embeddingProvider;
        private Predicate<String> embeddingSources;
        private SearchReranker reranker = SearchReranker.NONE;
        private ConfigurationManager configuration;
        private boolean aiFixed;
        private boolean networkFixed;
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
         *     <li>Freigabe je Quelle ({@code ai.external-processing-sources}): die Seiten der Quelle im Cache rechnen
         *     {@code embedding}, {@code passages} und {@code navigation} im Hintergrund neu, mit bzw. ohne Modell;</li>
         *     <li>Zeitplan aus {@code sync.spaces} mit Abstand und Betriebsart je Quelle; eigene Einplanungen über
         *     {@link Harvester#reconcile} bleiben;</li>
         *     <li>beim Bauen holt jede Quelle mit {@code catch-up-on-start} ihre bestätigten Spaces nach;</li>
         *     <li>Proxy und Vertrauen ohne Bundle ({@code network.*}): neue Verbindungen nehmen die neue Route und
         *     prüfen nach dem neuen Vertrauen, der PAC-Cache wird verworfen, alle Quellen öffnen neu (Trust-Bundles und
         *     Client-Zertifikate neu gelesen). Das gilt nur, solange weder {@link #proxy} noch {@link #trust} gesetzt
         *     ist, gleich ob vor oder nach diesem Aufruf; dann bleibt das Netzwerk fest.</li>
         * </ul>
         * Was nach diesem Aufruf mit {@link #ai}, {@link #cacheFreshness}, {@link #withheldRetention},
         * {@link #embeddings} oder {@link #reranker} fest eingestellt wird, bleibt fest und folgt der Konfiguration
         * nicht. Der Harvester meldet sich beim Schließen ab.
         */
        public Builder configuration(ConfigurationManager manager) {
            this.configuration = Objects.requireNonNull(manager, "manager");
            HarvesterConfiguration current = manager.current().configuration();
            this.ai = current.ai();
            if (!networkFixed) {
                this.proxy = current.network().proxy();
                this.trust = current.network().trust();
            }
            this.cacheFreshness = current.search().cacheFreshness();
            this.withheldRetention = current.sync().withheldRetention();
            this.prefetchSearchHits = current.search().prefetchHits();
            this.prefetchSearchHitsFixed = false;
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

        /**
         * Lokaler Satzanalysator für die Aussagenanalyse auf Knopfdruck (#499): nur die Desktop-App setzt ihn (MaltParser
         * aus {@code :nlp-parser}); ohne ihn bleibt {@link Harvester#statementAnalysis()} ohne Analysator, der Graph
         * lesbar. Der Core ruft ihn nie von sich aus.
         */
        public Builder statementAnalyzer(StatementAnalyzer analyzer) {
            this.statementAnalyzer = Objects.requireNonNull(analyzer, "analyzer");
            return this;
        }

        /**
         * Die Quellentypen, die ein so gebauter Harvester mit {@link Harvester#sourceTypes()} meldet, ohne ihn zu
         * starten: für Oberflächen, die die Liste auch ohne laufende Laufzeit brauchen (Quellenformular). Verlangt wie
         * {@link #build()} ein {@link #dataDirectory}.
         */
        public List<SourceType> sourceTypes() {
            if (dataDirectory == null) {
                throw new IllegalStateException("dataDirectory fehlt");
            }
            return ConfiguredSourceCatalog.types(adapters());
        }

        /**
         * Die Quellentypen: die des Core, ein ersetzter an seinem Platz, eigene neue dahinter. Mit
         * {@link #configuration} liest der eingebaute Typ {@code files} die freigegebenen Ordner bei jedem Zugriff aus
         * der wirksamen Konfiguration, ohne sie keine.
         */
        private List<SourceAdapter> adapters() {
            List<SourceAdapter> defaults = new ArrayList<>();
            for (SourceAdapter adapter : ConfiguredSourceCatalog.DEFAULT_ADAPTERS) {
                defaults.add(configuration != null && adapter instanceof LocalFilesSourceAdapter
                        ? LocalFilesSourceAdapter.following(configuration) : adapter);
            }
            // Git braucht einen Ordner für die Kopien der Repositorys (#187).
            defaults.add(new GitSourceAdapter(dataDirectory.resolve("git")));
            List<SourceAdapter> adapters = new ArrayList<>();
            for (SourceAdapter adapter : defaults) {
                adapters.add(sourceAdapters.stream().filter(own -> own.type().equals(adapter.type())).findFirst()
                        .orElse(adapter));
            }
            for (SourceAdapter own : sourceAdapters) {
                if (adapters.stream().noneMatch(added -> added.type().equals(own.type()))) {
                    adapters.add(own);
                }
            }
            return adapters;
        }

        /** Override the default reference-based local storage pipeline. */
        public Builder localOriginalReferences(boolean enabled) {
            this.localOriginalReferences = enabled;
            return this;
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

        /**
         * Größte einzelne Datei, die als Asset abgelegt wird; Standard {@code 0}, ohne Grenze wie im Server (Angelo
         * 11.11.2026). Dateien über {@link Assets#MAX_IN_MEMORY_BYTES} gibt es nur als Strom.
         */
        /**
         * Größte Datei bzw. Seite, die der Cache ablegt ({@code space-harvester.cache.max-page-size}); größere Dateien
         * werden nicht heruntergeladen, nur ihre Kopfdaten abgelegt. Standard
         * {@link LucenePageCache#DEFAULT_MAX_PAYLOAD_BYTES}.
         */
        public Builder maxPageBytes(long maxPageBytes) {
            if (maxPageBytes <= 0 || maxPageBytes > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("maxPageBytes muss zwischen 1 und " + (Integer.MAX_VALUE - 8)
                        + " liegen");
            }
            this.maxPageBytes = (int) maxPageBytes;
            return this;
        }

        /**
         * Größte Datei über {@link #maxPageBytes}, deren Textauszug statt des Originals abgelegt wird
         * ({@code space-harvester.cache.max-text-file-size}); {@code 0} nie. Die Datei wird dafür einmal ganz geladen.
         * Standard {@link #DEFAULT_MAX_TEXT_FILE_BYTES}.
         */
        public Builder maxTextFileBytes(long maxTextFileBytes) {
            if (maxTextFileBytes < 0) {
                throw new IllegalArgumentException("maxTextFileBytes darf nicht negativ sein");
            }
            this.maxTextFileBytes = maxTextFileBytes;
            return this;
        }

        public Builder maxAssetBytes(long maxAssetBytes) {
            if (maxAssetBytes < 0) {
                throw new IllegalArgumentException("maxAssetBytes darf nicht negativ sein");
            }
            this.maxAssetBytes = maxAssetBytes;
            return this;
        }

        /**
         * Schutzgrenzen beim Lesen von Dokumenten (ADR 0026, {@code space-harvester.assets.extraction.*}); Standard
         * {@link ExtractionLimits#DEFAULTS}. {@code parallel} bestimmt die gleichzeitigen Läufe.
         */
        public Builder extractionLimits(ExtractionLimits extractionLimits) {
            this.extractionLimits = Objects.requireNonNull(extractionLimits, "extractionLimits");
            return this;
        }

        /**
         * Ab welcher Größe (ausschließlich) eine Datei als groß gilt und unter die gemeinsame Schranke fällt
         * ({@link LargeFileGate}); Standard 20 MiB.
         */
        public Builder largeAssetThresholdBytes(long largeAssetThresholdBytes) {
            if (largeAssetThresholdBytes < 0) {
                throw new IllegalArgumentException("largeAssetThresholdBytes darf nicht negativ sein");
            }
            this.largeAssetThresholdBytes = largeAssetThresholdBytes;
            return this;
        }

        /** Wie viele große Dateien höchstens gleichzeitig verarbeitet werden ({@link LargeFileGate}); Standard 2. */
        public Builder maxConcurrentLargeAssets(int maxConcurrentLargeAssets) {
            if (maxConcurrentLargeAssets < 1) {
                throw new IllegalArgumentException("maxConcurrentLargeAssets muss positiv sein");
            }
            this.maxConcurrentLargeAssets = maxConcurrentLargeAssets;
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
         * Satzanalyse über das Chat-Modell (Prozessor {@code statements}, #108); Standard aus
         * ({@link StatementSettings#DEFAULT}). Eingeschaltet schickt sie Absätze der Seiten an {@code ai.chat-model},
         * aber nur aus Quellen in {@code external-processing-sources}, im Hintergrund der Anreicherungen; ohne
         * Chat-Modell wird der Prozessor übersprungen. Wirkt beim Bauen; das Chat-Modell folgt der Konfiguration.
         */
        public Builder statements(StatementSettings statements) {
            this.statements = Objects.requireNonNull(statements, "statements");
            return this;
        }

        /** Automatische Bildbeschreibung ohne Konfigurationsverwaltung (Fahrplan B1); Standard aus. */
        public Builder images(com.aresstack.spaceharvester.application.configuration.ImageSettings images) {
            this.images = java.util.Objects.requireNonNull(images, "images");
            return this;
        }

        /**
         * Budget der Synonymerkennung (#336, {@code terms.synonyms.*}) für eine feste Konfiguration; mit
         * {@link #configuration} folgt es der wirksamen Konfiguration.
         */
        public Builder terms(TermSettings terms) {
            this.terms = Objects.requireNonNull(terms, "terms");
            return this;
        }

        /**
         * Analysen je Anhang und enthaltener Datei (ADR 0026, AP9a, {@code enrichment.documents.*}) für eine feste
         * Konfiguration; mit {@link #configuration} folgen sie der wirksamen Konfiguration.
         */
        public Builder enrichment(EnrichmentSettings enrichment) {
            this.enrichment = Objects.requireNonNull(enrichment, "enrichment");
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

        /**
         * Höchstens so viele Zeichen je eingebettetem Stück, mindestens 100; Standard 0: ohne Zeichengrenze, nur das
         * Token-Budget wie im Enterprise AI Client. Nur für Dienste mit eigener Zeichengrenze.
         */
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

        /** Supply processors with a read-only lookup of stored snapshots, never a live source. */
        public Builder snapshotEnrichmentProcessor(java.util.function.Function<java.util.function.Function<PageKey, Optional<PageSnapshot>>, EnrichmentProcessor> factory) {
            snapshotProcessors.add(java.util.Objects.requireNonNull(factory));
            return this;
        }

        public Builder enrichmentProcessor(EnrichmentProcessor processor) {
            additionalProcessors.add(java.util.Objects.requireNonNull(processor));
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

        /**
         * Proxy und PAC wie {@code space-harvester.network.proxy}; Standard ohne Proxy. Gesetzt bleibt das Netzwerk
         * fest und folgt {@link #configuration} nicht.
         */
        public Builder proxy(ProxySettings proxy) {
            this.proxy = Objects.requireNonNull(proxy, "proxy");
            this.networkFixed = true;
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
         * gelesen. Gesetzt bleibt das Netzwerk fest und folgt {@link #configuration} nicht.
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
            this.networkFixed = true;
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

        /**
         * Frist einer Prüfung gegen die Quelle beim Öffnen und Beschaffen einer Seite
         * ({@code space-harvester.cache.check-timeout}); Standard {@link PageFreshness#DEFAULT_TIMEOUT}.
         */
        public Builder checkTimeout(Duration checkTimeout) {
            if (checkTimeout == null || checkTimeout.isNegative() || checkTimeout.isZero()) {
                throw new IllegalArgumentException("checkTimeout muss positiv sein");
            }
            this.checkTimeout = checkTimeout;
            return this;
        }

        /**
         * Frist dieser Prüfung für eine Datei über der Größengrenze, deren Textauszug das ganze Original braucht
         * ({@code space-harvester.cache.large-file-timeout}); Standard {@link PageFreshness#DEFAULT_LARGE_FILE_TIMEOUT}.
         */
        public Builder largeFileTimeout(Duration largeFileTimeout) {
            if (largeFileTimeout == null || largeFileTimeout.isNegative() || largeFileTimeout.isZero()) {
                throw new IllegalArgumentException("largeFileTimeout muss positiv sein");
            }
            this.largeFileTimeout = largeFileTimeout;
            return this;
        }

        public Builder maxTreeNodes(int maxTreeNodes) {
            this.maxTreeNodes = maxTreeNodes;
            return this;
        }

        /** Netz-Fristen der Quellen ({@code space-harvester.network.timeouts.*}). */
        public Builder sourceTimeouts(com.aresstack.spaceharvester.source.network.SourceTimeouts sourceTimeouts) {
            this.sourceTimeouts = java.util.Objects.requireNonNull(sourceTimeouts, "sourceTimeouts");
            return this;
        }

        /** Stapelgrößen der Abfragen ({@code space-harvester.query.max-page-limit}, {@code max-term-scan}). */
        public Builder queryLimits(com.aresstack.spaceharvester.application.query.QueryLimits queryLimits) {
            this.queryLimits = java.util.Objects.requireNonNull(queryLimits, "queryLimits");
            return this;
        }

        /**
         * Ob die Schlagwortsuche gefundene Seiten in den Cache lädt, bevor sie dort sucht (ADR 0013); Standard
         * {@code true}. Ohne findet sie nur, was schon im Cache liegt. Mit {@link #configuration} folgt sie
         * {@code search.prefetch-hits}, bis dieser Aufruf sie danach fest einstellt.
         */
        public Builder prefetchSearchHits(boolean prefetchSearchHits) {
            this.prefetchSearchHits = prefetchSearchHits;
            this.prefetchSearchHitsFixed = configuration != null;
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
         * Ob beim Start Aufträge ohne Klick anlaufen (Standard {@code true}, wie im Dienst): Fortsetzen der vom Beenden
         * unterbrochenen und ohne Verbindung angehaltenen Aufträge, die Abgleiche über alle Seiten („Verweise
         * abgleichen“, „KI-Freigabe prüfen“, „Analysen nach Update nachrechnen“) und „Neu lesen“ nach einem Wechsel der
         * Extraktion. Die App schaltet das ab (Angelo 24.11.2026: beim Start läuft nichts ohne Klick): Unterbrochene
         * Aufträge bleiben sichtbar stehen, bis jemand sie fortsetzt; nur ein unterbrochener Entzug einer KI-Freigabe
         * läuft zu Ende. Eingestellte Zeitpläne ({@code sync.interval}, {@code sync.catch-up-on-start}) gelten weiter.
         */
        public Builder autoStart(boolean autoStart) {
            this.autoStart = autoStart;
            return this;
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
