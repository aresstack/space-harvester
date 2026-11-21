package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.release.PageReleases;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Der automatische Synonymlauf je Quelle (SY1, ADR 0019): nach der Anreicherung und bei neuer KI-Freigabe einer Seite,
 * nie ohne Änderung, ausschaltbar, ohne Embedding-Modell nur Regeln; Bestätigtes bleibt stehen. Fake-Embedding.
 */
class SynonymAutomaticTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source("wiki"));
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return all().stream().filter(source -> source.id().equals(sourceId)).findFirst();
        }
    };
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final Enrichments enrichments = new Enrichments(PLAIN, List.of(new TermProcessor()), store,
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10, Duration.ofHours(1)),
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Set<String> released = new HashSet<>(Set.of("wiki"));
    private Optional<EmbeddingProvider> provider = Optional.of(vectorsByWord(Map.of("auto", 0, "kfz", 0)));
    private SynonymBudget budget = SynonymBudget.DEFAULT;
    private TermRelations relations;
    private SynonymDiscovery discovery;
    private int pages;

    private final SynonymJudgmentModel model = new SynonymJudgmentModel() {
        @Override
        public String version() {
            return "fake:v1";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean mayProcess(String sourceId) {
            return released.contains(sourceId);
        }

        @Override
        public String complete(String instructions, String input) {
            throw new AssertionError("kein Chat-Modell");
        }
    };

    private static HarvestSource source(String id) {
        HarvestSource source = Mockito.mock(HarvestSource.class);
        Mockito.when(source.id()).thenReturn(id);
        return source;
    }

    private void cached(String text) {
        String pageId = String.valueOf(++pages);
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV",
                null, "1", T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of("wiki", metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
    }

    private List<TermRelation> synonyms() {
        return relations.stored().stream().filter(relation -> relation.origin() == RelationOrigin.AI
                && relation.type() == RelationType.SYNONYM).toList();
    }

    @BeforeEach
    void setUp() {
        AccessGuard guard = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
        relations = new TermRelations(catalog, new InMemoryTermRelationStore(), guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
        SynonymCandidates candidates = new SynonymCandidates(catalog, store,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)).flatMap(PLAIN::project), relations, () -> provider,
                released::contains, new TermProcessor().version());
        discovery = new SynonymDiscovery(catalog, candidates, guard, relations, model, ResolutionCache.inMemory(),
                jobs, () -> budget, Clock.fixed(T0, ZoneOffset.UTC));
        cached("Das Auto steht in der Garage. Das Auto ist neu.");
        cached("Das Kfz wird zugelassen. Jedes Kfz braucht ein Kennzeichen.");
    }

    @Test
    void afterEnrichmentTheSourceRunsOnceAndAgainOnlyAfterAChange() {
        Optional<JobStatus> first = discovery.automatic("wiki");

        assertThat(first).isPresent();
        assertThat(jobs.status(first.get().id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(synonyms()).hasSize(1);
        assertThat(discovery.automatic("wiki")).isEmpty();

        cached("Der Wagen fährt. Der Wagen ist rot.");
        assertThat(discovery.automatic("wiki")).isPresent();
    }

    @Test
    void theAutomaticRunHasTheCoreAsOwnerAndARecognizableName() {
        // D4.4: in der Auftragsliste wie die übrigen Aufträge des Core, als „Synonyme erkennen“.
        discovery.owner("space-harvester-core");

        JobStatus status = discovery.automatic("wiki").orElseThrow();

        assertThat(status.owner()).isEqualTo("space-harvester-core");
        assertThat(status.type()).isEqualTo(JobType.ENRICHMENT);
        assertThat(status.parameters()).containsEntry(SynonymDiscovery.TASK_PARAMETER, SynonymDiscovery.TASK);
    }

    @Test
    void confirmedProposalsStayAndUnsupportedOnesAreWithdrawn() {
        discovery.automatic("wiki");
        TermRelation pair = synonyms().get(0);
        relations.confirm(EDITOR, pair.id(), null);

        provider = Optional.of(vectorsByWord(Map.of("auto", 0, "kfz", 1)));
        cached("Ein Fahrrad hat zwei Räder.");
        discovery.automatic("wiki");

        assertThat(relations.stored()).filteredOn(relation -> relation.id().equals(pair.id()))
                .singleElement().extracting(TermRelation::status).isEqualTo(ReviewStatus.CONFIRMED);
    }

    @Test
    void switchedOffOrUnknownSourcesStartNothing() {
        budget = SynonymBudget.DEFAULT.withAutomatic(false);

        assertThat(discovery.automatic("wiki")).isEmpty();
        assertThat(discovery.automatic("fremd")).isEmpty();
        assertThat(synonyms()).isEmpty();
    }

    @Test
    void withoutEmbeddingModelOnlyRulesRunWithoutFailure() {
        provider = Optional.empty();

        JobStatus status = discovery.automatic("wiki").orElseThrow();

        assertThat(jobs.status(status.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(synonyms()).isEmpty();
    }

    @Test
    void aRunCancelledBeforeItStartsDoesNotBlockTheSource() {
        List<Runnable> queued = new ArrayList<>();
        Jobs held = new Jobs(new InMemoryJobStore(), queued::add, new JobLimits(1, 10, Duration.ofHours(1)),
                Clock.fixed(T0, ZoneOffset.UTC));
        // Ein laufender Auftrag belegt den einzigen Platz; der Synonymlauf wartet dahinter (QUEUED).
        held.submit(JobType.ENRICHMENT, "wiki", null, "anderer", JobTask.blocking(context -> { }));
        SynonymCandidates candidates = new SynonymCandidates(catalog, store,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)).flatMap(PLAIN::project), relations, () -> provider,
                released::contains, new TermProcessor().version());
        SynonymDiscovery waiting = new SynonymDiscovery(catalog, candidates,
                new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), relations, model,
                ResolutionCache.inMemory(), held, () -> budget, Clock.fixed(T0, ZoneOffset.UTC));

        JobStatus first = waiting.automatic("wiki").orElseThrow();
        assertThat(held.status(first.id()).state()).isEqualTo(JobState.QUEUED);
        assertThat(waiting.automatic("wiki")).as("ein wartender Lauf je Quelle").isEmpty();
        held.cancel(first.id());

        assertThat(waiting.automatic("wiki")).isPresent();
    }

    @Test
    void asBackgroundJobTheRunIsVisibleWithKindTitleAndOwner() {
        Jobs hosting = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(4, 10, Duration.ofHours(1)),
                Clock.fixed(T0, ZoneOffset.UTC));
        SynonymDiscovery hosted = discovery(hosting);
        hosted.background(new BackgroundJobs(hosting, "app"));

        JobStatus first = hosted.automatic("wiki").orElseThrow();

        assertThat(first.type()).isEqualTo(JobType.BACKGROUND);
        assertThat(first.owner()).as("sichtbar in der Auftragsliste der App").isEqualTo("app");
        assertThat(first.parameter(BackgroundJobs.KIND)).isEqualTo(SynonymDiscovery.BACKGROUND_KIND);
        assertThat(first.parameter(BackgroundJobs.TITLE)).isEqualTo("Synonyme erkennen");
        assertThat(hosting.status(first.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(hosting.list(null, JobType.ENRICHMENT)).as("kein Auftrag ohne Auftraggeber").isEmpty();
        assertThat(synonyms()).hasSize(1);
        assertThat(hosted.automatic("wiki")).as("ohne Änderung kein neuer Lauf").isEmpty();
    }

    @Test
    void aWaitingBackgroundRunTakesTheNextChangeAlongAndACancelledOneBlocksNothing() {
        // Ein einziger Platz: ein Hintergrundauftrag startet nie und wartet (QUEUED).
        Jobs hosting = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 10, Duration.ofHours(1)),
                Clock.fixed(T0, ZoneOffset.UTC));
        SynonymDiscovery hosted = discovery(hosting);
        BackgroundJobs background = new BackgroundJobs(hosting, "app");
        hosted.background(background);

        JobStatus first = hosted.automatic("wiki").orElseThrow();
        assertThat(hosting.status(first.id()).state()).isEqualTo(JobState.QUEUED);
        cached("Der Wagen fährt. Der Wagen ist rot.");
        assertThat(hosted.automatic("wiki")).as("der wartende Teil nimmt die Änderung mit").isEmpty();

        hosting.cancel(first.id(), "app");

        assertThat(hosted.automatic("wiki")).isPresent();
    }

    @Test
    void aPausedBackgroundRunHoldsTheNextChangeBackAndRunsItWhenResumed() {
        // Ein Platz: der Teil wartet und lässt sich anhalten, bevor er läuft.
        Jobs hosting = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 10, Duration.ofHours(1)),
                Clock.fixed(T0, ZoneOffset.UTC));
        SynonymDiscovery hosted = discovery(hosting);
        BackgroundJobs background = new BackgroundJobs(hosting, "app");
        hosted.background(background);
        JobStatus first = hosted.automatic("wiki").orElseThrow();
        String group = first.parameter(BackgroundJobs.GROUP);

        background.pause(group);
        cached("Der Wagen fährt. Der Wagen ist rot.");

        assertThat(hosted.automatic("wiki")).as("der angehaltene Teil hält den Anlass zurück").isEmpty();
        assertThat(hosting.status(first.id()).state()).isEqualTo(JobState.PAUSED);
        assertThat(synonyms()).isEmpty();

        hosting.parallelPreparations(2);
        background.resume(group);

        assertThat(hosting.status(first.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(synonyms()).isNotEmpty();
        assertThat(hosted.automatic("wiki")).as("die Änderung ist mitgerechnet").isEmpty();
    }

    private SynonymDiscovery discovery(Jobs on) {
        SynonymCandidates candidates = new SynonymCandidates(catalog, store,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)).flatMap(PLAIN::project), relations, () -> provider,
                released::contains, new TermProcessor().version());
        return new SynonymDiscovery(catalog, candidates,
                new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), relations, model,
                ResolutionCache.inMemory(), on, () -> budget, Clock.fixed(T0, ZoneOffset.UTC));
    }

    /** Zählt, wie viele Eingaben an das Modell gingen. */
    private final List<String> embedded = new ArrayList<>();

    private EmbeddingProvider counting(EmbeddingProvider inner, String modelId) {
        return new EmbeddingProvider() {
            @Override
            public String modelId() {
                return modelId;
            }

            @Override
            public int dimensions() {
                return inner.dimensions();
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                embedded.addAll(texts);
                return inner.embed(texts);
            }
        };
    }

    private SynonymCandidates candidates() {
        return new SynonymCandidates(catalog, store,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)).flatMap(PLAIN::project), relations, () -> provider,
                released::contains, new TermProcessor().version());
    }

    private SynonymDiscovery discovery(SynonymVectorCache vectors) {
        SynonymCandidates candidates = candidates();
        candidates.vectors(vectors);
        return new SynonymDiscovery(catalog, candidates, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                relations, model, ResolutionCache.inMemory(), jobs, () -> budget, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void cachedVectorsAreNotEmbeddedAgain() {
        provider = Optional.of(counting(vectorsByWord(Map.of("auto", 0, "kfz", 0)), "fake@1"));
        SynonymDiscovery withCache = discovery(SynonymVectorCache.inMemory());
        withCache.automatic("wiki");
        int first = embedded.size();
        assertThat(first).isGreaterThanOrEqualTo(2);

        cached("Der Wagen fährt. Der Wagen ist rot.");
        withCache.automatic("wiki");

        // Nur der neue Begriff geht an das Modell; Auto und Kfz kommen aus dem Cache.
        assertThat(embedded.subList(first, embedded.size())).allMatch(input -> !input.startsWith("Auto:")
                && !input.startsWith("Kfz:")).isNotEmpty();
    }

    @Test
    void theBudgetCountsOnlyNewVectorsAndTheRunContinuesLater() {
        provider = Optional.of(counting(vectorsByWord(Map.of("auto", 0, "kfz", 0)), "fake@1"));
        cached("Der Wagen fährt. Der Wagen ist rot.");
        budget = new SynonymBudget(2000, 2, 0, true);
        SynonymVectorCache vectors = SynonymVectorCache.inMemory();
        SynonymDiscovery withCache = discovery(vectors);

        withCache.automatic("wiki");
        assertThat(vectors.state("wiki")).as("unvollständig: kein Stand").isEmpty();
        int first = embedded.size();
        assertThat(first).isEqualTo(2);

        // Ohne Änderung läuft er weiter, weil ein Begriff noch keinen Vektor hat.
        assertThat(withCache.automatic("wiki")).isPresent();
        assertThat(embedded.size()).isGreaterThan(first);
    }

    @Test
    void theStateSurvivesARestartAndAModelChangeStartsARun() {
        provider = Optional.of(counting(vectorsByWord(Map.of("auto", 0, "kfz", 0)), "fake@1"));
        SynonymVectorCache vectors = SynonymVectorCache.inMemory();
        discovery(vectors).automatic("wiki");
        assertThat(vectors.state("wiki")).isPresent();

        // Neue Instanz wie nach einem Neustart: derselbe Stand, kein Lauf.
        assertThat(discovery(vectors).automatic("wiki")).isEmpty();

        provider = Optional.of(counting(vectorsByWord(Map.of("auto", 0, "kfz", 0)), "fake@2"));
        assertThat(discovery(vectors).automatic("wiki")).isPresent();
    }

    @Test
    void aWithdrawnPageReleaseRemovesItsVectors() {
        released.clear();
        PageReleases releases = PageReleases.inMemory();
        SynonymVectorCache vectors = SynonymVectorCache.inMemory();
        SynonymCandidates candidates = candidates();
        candidates.vectors(vectors);
        candidates.pageReleases(releases::contains);
        SynonymDiscovery withCache = new SynonymDiscovery(catalog, candidates,
                new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), relations, model,
                ResolutionCache.inMemory(), jobs, () -> budget, Clock.fixed(T0, ZoneOffset.UTC));
        withCache.pageReleases(releases::contains);
        releases.subscribe(withCache::releasesChanged);
        releases.add(List.of(new PageReleases.Entry(new PageKey("wiki", "1"), "DEV", null, PageReleases.CHAT, T0),
                new PageReleases.Entry(new PageKey("wiki", "2"), "DEV", null, PageReleases.CHAT, T0)));
        assertThat(synonyms()).hasSize(1);

        releases.remove(List.of(new PageKey("wiki", "1")));

        assertThat(synonyms()).as("ohne Vektor kein gestützter KI-Vorschlag").isEmpty();
    }

    @Test
    void aWithdrawnSourceReleaseRemovesVectorsExceptForReleasedPages() {
        provider = Optional.of(counting(vectorsByWord(Map.of("auto", 0, "kfz", 0)), "fake@1"));
        SynonymVectorCache vectors = SynonymVectorCache.inMemory();
        SynonymDiscovery withCache = discovery(vectors);
        withCache.automatic("wiki");
        assertThat(vectors.state("wiki")).isPresent();
        int first = embedded.size();

        // Seite 2 bleibt über die Seitenliste freigegeben, Seite 1 nicht.
        PageKey listed = new PageKey("wiki", "2");
        withCache.withdrawn(List.of(new PageKey("wiki", "1"), listed), listed::equals);

        assertThat(vectors.state("wiki")).isEmpty();
        released.clear();
        embedded.clear();
        SynonymCandidates candidates = candidates();
        candidates.vectors(vectors);
        candidates.pageReleases(listed::equals);
        candidates.collect(SynonymScope.source("wiki"), budget, null);
        // Kfz (Seite 2) kommt aus dem Cache, Auto (Seite 1) darf nicht mehr ans Modell.
        assertThat(first).isGreaterThanOrEqualTo(2);
        assertThat(embedded).isEmpty();
    }

    @Test
    void aChangedPageLeavesNoOldVector() {
        provider = Optional.of(counting(vectorsByWord(Map.of("auto", 0, "kfz", 0)), "fake@1"));
        SynonymVectorCache vectors = SynonymVectorCache.inMemory();
        SynonymDiscovery withCache = discovery(vectors);
        withCache.automatic("wiki");
        java.util.Set<String> before = vectorHashes(vectors);

        // Seite 2 neu mit anderem Text: Kfz bekommt eine neue Eingabe.
        PageMetadata metadata = new PageMetadata("2", "confluence:wiki/page/2", "Seite 2", "DEV", null, "2", T0,
                null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of("wiki", metadata, new NativePayload(
                "Das Kfz fährt schnell. Jedes Kfz hat Räder.".getBytes(StandardCharsets.UTF_8), "text/plain",
                "test"), T0);
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
        withCache.automatic("wiki");

        java.util.Set<String> after = vectorHashes(vectors);
        assertThat(after).hasSameSizeAs(before).isNotEqualTo(before);
    }

    /** Die Eingabe-Hashes im Cache, über die Eingaben, die an das Modell gingen. */
    private java.util.Set<String> vectorHashes(SynonymVectorCache vectors) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        for (String input : embedded) {
            String hash = sha256(input);
            if (vectors.find("fake@1", hash).isPresent()) {
                found.add(hash);
            }
        }
        return found;
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aNewPageReleaseStartsTheRunOfItsSourceAgain() {
        discovery.automatic("wiki");
        assertThat(discovery.automatic("wiki")).isEmpty();

        PageReleases releases = PageReleases.inMemory();
        releases.subscribe(discovery::releasesChanged);
        int before = jobs.list(null, JobType.ENRICHMENT).size();
        releases.add(List.of(new PageReleases.Entry(new PageKey("wiki", "1"), "DEV", null, PageReleases.CHAT, T0)));

        assertThat(jobs.list(null, JobType.ENRICHMENT)).hasSize(before + 1);
    }

    private static EmbeddingProvider vectorsByWord(Map<String, Integer> groups) {
        Map<String, Integer> axes = new HashMap<>(groups);
        return new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "fake-embeddings@1";
            }

            @Override
            public int dimensions() {
                return 64;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                List<float[]> vectors = new ArrayList<>();
                for (String text : texts) {
                    String word = text.substring(0, text.indexOf(':')).toLowerCase(java.util.Locale.ROOT);
                    int axis = axes.computeIfAbsent(word, w -> Math.min(63, 8 + axes.size()));
                    float[] vector = new float[64];
                    vector[axis] = 1f;
                    vectors.add(vector);
                }
                return vectors;
            }
        };
    }
}
