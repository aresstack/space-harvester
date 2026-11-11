package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.AcronymExpansionModel;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ResolutionCache;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.persistence.files.FileGlossaryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fachglossar: KI-Lauf nur auf Wunsch, Schutzregeln für Benutzerdaten, Ablage über Neustarts. */
class GlossaryClassificationTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final Instant T0 = Instant.parse("2026-11-09T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));
    private static final RelationScope WIKI = new RelationScope("wiki", null);

    @TempDir
    Path directory;

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source("wiki"), source("secret"));
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return Set.of("wiki", "secret").contains(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };
    private final InMemoryEnrichmentStore index = new InMemoryEnrichmentStore();
    private final Enrichments enrichments = new Enrichments(PLAIN, List.of(new TermProcessor()), index,
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final List<String> inputs = new ArrayList<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10, Duration.ofHours(1)),
            Clock.fixed(T0, ZoneOffset.UTC));
    private final AccessGuard guard = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    private BiFunction<String, String, String> answers = choosing(Set.of());
    private boolean available = true;
    private String modelVersion = "fake:model@abc";
    private GlossaryStore store;
    private Glossary glossary;
    private GlossaryClassification classification;

    private final AcronymExpansionModel model = new AcronymExpansionModel() {
        @Override
        public String version() {
            return modelVersion;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean mayProcess(String sourceId) {
            return "wiki".equals(sourceId);
        }

        @Override
        public String complete(String instructions, String input) {
            inputs.add(input);
            return answers.apply(instructions, input);
        }
    };

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    /** Wählt von den gefragten Kandidaten die genannten, dazu einen erfundenen Begriff. */
    private static BiFunction<String, String, String> choosing(Set<String> technical) {
        return (instructions, input) -> {
            List<String> chosen = new ArrayList<>();
            for (JsonNode candidate : JSON.readTree(input).get("candidates")) {
                if (technical.contains(candidate.get("term").asString())) {
                    chosen.add("\"" + candidate.get("term").asString() + "\"");
                }
            }
            chosen.add("\"Erfundenes Fachwort\"");
            return "{\"technical\":" + chosen + "}";
        };
    }

    private void cached(String pageId, String version, String text) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV",
                null, version, T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of("wiki", metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
    }

    private void open(GlossaryStore next) {
        store = next;
        glossary = new Glossary(catalog, store, guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
        classification = new GlossaryClassification(catalog, index, guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)).flatMap(PLAIN::project), glossary, model,
                ResolutionCache.inMemory(), jobs, new TermProcessor().version(), GlossaryClassification.Budget.DEFAULT,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private Optional<GlossaryEntry> find(String term, ReviewStatus status) {
        return glossary.entries(EDITOR, new Glossary.Search(term, status, null, null, null, null, 50, null))
                .entries().stream().filter(entry -> entry.normalized().equals(term.toLowerCase())).findFirst();
    }

    @BeforeEach
    void setUp() {
        open(new FileGlossaryStore(directory));
        cached("1", "1", "Das Datenprodukt wird im Haushaltsverfahren geprüft. Eine Möglichkeit fehlt.");
        cached("2", "1", "Jedes Datenprodukt hat eine Möglichkeit zur Freigabe im Haushaltsverfahren.");
    }

    @Test
    void technicalTermsBecomeAiProposalsWithEvidenceAndCommonWordsAndInventionsDoNot() {
        answers = choosing(Set.of("Datenprodukt", "Haushaltsverfahren"));

        Glossary.ProposalRun run = classification.run(WIKI, null);

        assertThat(run.added()).isEqualTo(2);
        GlossaryEntry entry = find("datenprodukt", ReviewStatus.PROPOSED).orElseThrow();
        assertThat(entry.origin()).isEqualTo(RelationOrigin.AI);
        assertThat(entry.evidence()).hasSize(2);
        assertThat(entry.explanation()).isNull();
        assertThat(entry.generation()).startsWith("fake:model@abc;" + GlossaryClassification.PROMPT_VERSION + "@");
        assertThat(find("möglichkeit", null)).isEmpty();
        assertThat(find("erfundenes fachwort", null)).isEmpty();
        assertThat(inputs.get(0)).contains("[[Datenprodukt]]").contains("Möglichkeit");
    }

    @Test
    void manualEntriesSurviveEveryRunAndAreNeverSentToTheModel() {
        GlossaryEntry manual = glossary.add(EDITOR, new Glossary.NewEntry("Datenprodukt", WIKI, "Eigene Erklärung",
                List.of()));
        GlossaryEntry unknown = glossary.add(EDITOR, new Glossary.NewEntry("Berechtigungsmanagement", WIKI, null,
                List.of()));
        answers = choosing(Set.of("Haushaltsverfahren"));

        classification.run(WIKI, null);

        assertThat(store.find(manual.id()).orElseThrow()).isEqualTo(manual);
        assertThat(store.find(unknown.id()).orElseThrow()).isEqualTo(unknown);
        assertThat(inputs).noneMatch(input -> input.contains("\"Datenprodukt\""));
    }

    @Test
    void confirmedAiTermsStayConfirmedAndOnlyGetStaleWhenNoLongerFound() {
        answers = choosing(Set.of("Datenprodukt"));
        classification.run(WIKI, null);
        GlossaryEntry confirmed = glossary.confirm(EDITOR, find("datenprodukt", null).orElseThrow().id(), null);
        answers = choosing(Set.of());

        classification.run(WIKI, null);
        assertThat(store.find(confirmed.id()).orElseThrow()).isEqualTo(confirmed);

        cached("1", "2", "Hier steht nichts Fachliches mehr.");
        cached("2", "2", "Auch hier nicht.");
        Glossary.ProposalRun run = classification.run(WIKI, null);

        GlossaryEntry stale = store.find(confirmed.id()).orElseThrow();
        assertThat(run.stale()).isEqualTo(1);
        assertThat(stale.status()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(stale.origin()).isEqualTo(RelationOrigin.AI);
        assertThat(stale.evidenceStale()).isTrue();
    }

    @Test
    void rejectedTermsNeverComeBackAfterRefreshRestartAndModelChangeButCanBeRestored() {
        answers = choosing(Set.of("Datenprodukt"));
        classification.run(WIKI, null);
        GlossaryEntry rejected = glossary.reject(EDITOR, find("datenprodukt", null).orElseThrow().id(), null);

        open(new FileGlossaryStore(directory));
        modelVersion = "fake:other@def";
        inputs.clear();
        classification.run(WIKI, null);

        assertThat(store.find(rejected.id()).orElseThrow().status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(find("datenprodukt", null)).isEmpty();
        assertThat(inputs).noneMatch(input -> input.contains("\"Datenprodukt\""));

        GlossaryEntry restored = glossary.restore(EDITOR, rejected.id(), null);
        assertThat(restored.status()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(restored.origin()).isEqualTo(RelationOrigin.AI);
        assertThat(glossary.history(EDITOR, rejected.id())).extracting(GlossaryStore.AuditEntry::action)
                .containsExactly("propose", "reject", "restore");
    }

    @Test
    void aStaleConfirmedTermLosesTheMarkWhenItIsFoundAgain() {
        answers = choosing(Set.of("Datenprodukt"));
        classification.run(WIKI, null);
        String id = glossary.confirm(EDITOR, find("datenprodukt", null).orElseThrow().id(), null).id();
        cached("1", "2", "Hier steht nichts Fachliches mehr.");
        cached("2", "2", "Auch hier nicht.");
        classification.run(WIKI, null);
        assertThat(store.find(id).orElseThrow().evidenceStale()).isTrue();

        cached("1", "3", "Das Datenprodukt ist zurück.");
        classification.run(WIKI, null);

        GlossaryEntry back = store.find(id).orElseThrow();
        assertThat(back.evidenceStale()).isFalse();
        assertThat(back.status()).isEqualTo(ReviewStatus.CONFIRMED);
    }

    @Test
    void purgingASourceRemovesItsAiEntriesAndKeepsManualOnes() {
        answers = choosing(Set.of("Datenprodukt"));
        classification.run(WIKI, null);
        GlossaryEntry manual = glossary.add(EDITOR, new Glossary.NewEntry("Datenhaus", WIKI, null, List.of()));

        assertThat(glossary.removeSource("wiki", "purge")).isEqualTo(1);

        assertThat(store.all()).extracting(GlossaryEntry::id).containsExactly(manual.id());
    }

    @Test
    void onlyUnreviewedProposalsAreReplacedOrWithdrawn() {
        answers = choosing(Set.of("Datenprodukt", "Haushaltsverfahren"));
        classification.run(WIKI, null);
        glossary.confirm(EDITOR, find("haushaltsverfahren", null).orElseThrow().id(), null);
        answers = choosing(Set.of());
        // Geänderter Text: der Cache antwortet nicht, das Modell wird neu gefragt.
        cached("1", "2", "Ein Datenprodukt wird im Haushaltsverfahren geprüft. Eine Möglichkeit fehlt.");

        Glossary.ProposalRun run = classification.run(WIKI, null);

        assertThat(run.withdrawn()).isEqualTo(1);
        assertThat(find("datenprodukt", null)).isEmpty();
        assertThat(find("haushaltsverfahren", ReviewStatus.CONFIRMED)).isPresent();
    }

    @Test
    void aFailedOrCancelledRunChangesNothing() {
        answers = choosing(Set.of("Datenprodukt"));
        classification.run(WIKI, null);
        GlossaryEntry before = find("datenprodukt", null).orElseThrow();
        List<GlossaryEntry> snapshot = store.all();
        answers = (instructions, input) -> {
            throw new IllegalStateException("Dienst weg");
        };
        cached("1", "2", "Neuer Text ohne Fachbegriff.");

        assertThatThrownBy(() -> classification.run(WIKI, null)).isInstanceOf(JobFailedException.class);
        assertThat(store.all()).isEqualTo(snapshot);

        JobContext cancelled = mock(JobContext.class);
        org.mockito.Mockito.doThrow(new java.util.concurrent.CancellationException()).when(cancelled).checkpoint();
        assertThatThrownBy(() -> classification.run(WIKI, cancelled))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(store.find(before.id())).contains(before);
    }

    @Test
    void eachAnsweredRequestShowsItsProposalsAtOnceAndTheObserverHearsIt() {
        classification = new GlossaryClassification(catalog, index, guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)).flatMap(PLAIN::project), glossary, model,
                ResolutionCache.inMemory(), jobs, new TermProcessor().version(),
                new GlossaryClassification.Budget(300, 3, 160, 1, 600_000), Clock.fixed(T0, ZoneOffset.UTC));
        List<Integer> seen = new ArrayList<>();
        Runnable unsubscribe = glossary.subscribe(() -> seen.add(store.all().size()));
        BiFunction<String, String, String> choose = choosing(Set.of("Datenprodukt", "Haushaltsverfahren"));
        int[] calls = {0};
        answers = (instructions, input) -> {
            if (++calls[0] == 2) {
                // Die erste Antwort ist da: ihr Vorschlag steht schon im Glossar, bevor der Lauf endet.
                assertThat(store.all()).hasSize(1);
                throw new IllegalStateException("Dienst weg");
            }
            return choose.apply(instructions, input);
        };

        assertThatThrownBy(() -> classification.run(WIKI, null)).isInstanceOf(JobFailedException.class);

        // Abbruch oder Fehler: was schon vorgeschlagen ist, bleibt.
        assertThat(store.all()).hasSize(1);
        assertThat(seen).containsExactly(1);
        unsubscribe.run();
        glossary.add(EDITOR, new Glossary.NewEntry("Fachwort", WIKI, null, List.of()));
        assertThat(seen).containsExactly(1);
    }

    @Test
    void anUnreadableAnswerKeepsTheProposals() {
        answers = choosing(Set.of("Datenprodukt"));
        classification.run(WIKI, null);
        answers = (instructions, input) -> "Ich weiß es nicht.";
        cached("1", "2", "Das Datenprodukt bleibt im Haushaltsverfahren.");

        classification.run(WIKI, null);

        assertThat(find("datenprodukt", ReviewStatus.PROPOSED)).isPresent();
    }

    @Test
    void anUnchangedStateIsNotAskedAgainAndASecondRunAddsNoDuplicates() {
        answers = choosing(Set.of("Datenprodukt"));
        classification.run(WIKI, null);
        int asked = inputs.size();

        Glossary.ProposalRun again = classification.run(WIKI, null);

        assertThat(inputs).hasSize(asked);
        assertThat(again.added()).isZero();
        assertThat(store.all()).hasSize(1);
    }

    @Test
    void withoutModelOrReleaseTheRunDoesNotStartButManualCareWorks() {
        assertThatThrownBy(() -> classification.start(EDITOR, "secret", null))
                .isInstanceOf(GlossaryUnavailableException.class)
                .extracting(e -> ((GlossaryUnavailableException) e).reason())
                .isEqualTo(GlossaryUnavailableException.Reason.NOT_RELEASED);
        assertThat(classification.start(EDITOR, null, null)).hasSize(1);
        available = false;
        assertThatThrownBy(() -> classification.start(EDITOR, "wiki", null))
                .isInstanceOf(GlossaryUnavailableException.class);

        GlossaryEntry manual = glossary.add(EDITOR, new Glossary.NewEntry("Datenhaus", RelationScope.GLOBAL, null,
                List.of()));
        assertThat(glossary.explain(EDITOR, manual.id(), "Zentrale Plattform", manual.revision()).explanation())
                .isEqualTo("Zentrale Plattform");
        assertThatThrownBy(() -> glossary.explain(EDITOR, manual.id(), "x", manual.revision()))
                .isInstanceOf(GlossaryConflictException.class);
    }

    @Test
    void aSpaceScopeOnlyUsesPagesOfThatSpace() {
        answers = choosing(Set.of("Datenprodukt"));

        Glossary.ProposalRun run = classification.run(new RelationScope("wiki", "OPS"), null);

        assertThat(run.added()).isZero();
        assertThat(inputs).isEmpty();
    }
}
