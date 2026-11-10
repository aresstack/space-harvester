package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
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
import com.aresstack.spaceharvester.application.jobs.Jobs;
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
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@link SynonymDiscovery} mit Fake-Modell: Regelstufe ohne Modell, Urteile nur mit Belegen, nie transitiv, Cache
 * ohne Doppel, Entscheidungen und fremde Erzeuger unberührt, nichts aus nicht freigegebenen Quellen, Umfänge getrennt.
 */
class SynonymDiscoveryTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final AccessContext READER = AccessContext.technicalClient("reader");
    private static final Instant T0 = Instant.parse("2026-11-09T00:00:00Z");
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));
    private static final String SYNONYM = "{\"judgments\":[{\"pair\":1,\"relation\":\"synonym\",\"a\":[1],\"b\":[1]}]}";

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source("wiki"), source("intern"));
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
    private final List<String> inputs = new ArrayList<>();
    private final Set<String> released = new HashSet<>(Set.of("wiki"));
    private final ResolutionCache resolutionCache = ResolutionCache.inMemory();
    private BiFunction<String, String, String> answers = (instructions, input) -> SYNONYM;
    private boolean available = true;
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
            return available;
        }

        @Override
        public boolean mayProcess(String sourceId) {
            return released.contains(sourceId);
        }

        @Override
        public String complete(String instructions, String input) {
            inputs.add(input);
            return answers.apply(instructions, input);
        }
    };

    private static HarvestSource source(String id) {
        HarvestSource source = Mockito.mock(HarvestSource.class);
        Mockito.when(source.id()).thenReturn(id);
        return source;
    }

    private void cached(String sourceId, String space, String text) {
        String pageId = String.valueOf(++pages);
        PageMetadata metadata = new PageMetadata(pageId, "confluence:" + sourceId + "/page/" + pageId,
                "Seite " + pageId, space, null, "1", T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of(sourceId, metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
    }

    /** Zwei bestätigte Ausschreibungen desselben Akronyms: der Kandidat „Rückmeldeschlüssel / Rückmeldungsschlüssel“. */
    private void siblings(String sourceId) {
        cached(sourceId, "DEV", "Der RSL steht für Rückmeldeschlüssel. Der Rückmeldeschlüssel ist Pflicht.");
        cached(sourceId, "DEV", "Der RSL ist der Rückmeldungsschlüssel der Meldung. Der Rückmeldungsschlüssel wird "
                + "vergeben.");
        relations.add(EDITOR, new TermRelations.NewRelation("RSL", "Rückmeldeschlüssel", RelationType.ABBREVIATION,
                RelationScope.GLOBAL, null, List.of()));
        relations.add(EDITOR, new TermRelations.NewRelation("RSL", "Rückmeldungsschlüssel",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));
    }

    private List<TermRelation> automatic(RelationOrigin origin, RelationType type) {
        return relations.stored().stream().filter(relation -> relation.origin() == origin
                && (type == null || relation.type() == type)).toList();
    }

    private SynonymDiscovery.Outcome run(SynonymScope scope) {
        return discovery.run(scope, SynonymBudget.DEFAULT, null);
    }

    @BeforeEach
    void setUp() {
        AccessGuard guard = new AccessGuard((context, scope, kind) -> kind == AccessKind.SYNC
                && context.equals(READER) ? AccessDecision.DENY : AccessDecision.ALLOW);
        relations = new TermRelations(catalog, new InMemoryTermRelationStore(), guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
        SynonymCandidates candidates = new SynonymCandidates(catalog, store,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)).flatMap(PLAIN::project), relations, Optional::empty,
                released::contains, new TermProcessor().version());
        discovery = new SynonymDiscovery(catalog, candidates, guard, relations, model, resolutionCache, jobs,
                () -> SynonymBudget.DEFAULT, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void withoutModelOnlyRulesProposeAndNothingIsSent() {
        available = false;
        cached("wiki", "DEV", "Die E-Mail geht an die Fachabteilung.");
        cached("wiki", "DEV", "Jede Email wird im Postfach archiviert.");
        siblings("wiki");

        SynonymDiscovery.Outcome outcome = run(SynonymScope.source("wiki"));

        assertThat(outcome.aiUsed()).isFalse();
        assertThat(outcome.rules().added()).isEqualTo(1);
        assertThat(outcome.skipped()).isEqualTo(1);
        assertThat(inputs).isEmpty();
        TermRelation variant = automatic(RelationOrigin.RULE, null).get(0);
        assertThat(variant.type()).isEqualTo(RelationType.VARIANT);
        assertThat(variant.status()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(variant.scope()).isEqualTo(new RelationScope("wiki", null));
        assertThat(variant.evidence()).isNotEmpty();
        assertThat(variant.createdBy()).isEqualTo(SynonymDiscovery.ACTOR_RULE);
        assertThat(automatic(RelationOrigin.AI, null)).isEmpty();
    }

    @Test
    void aSynonymVerdictWithEvidenceOnBothSidesBecomesAProposalAndLeavesAcronymProposalsAlone() {
        siblings("wiki");
        RelationScope scope = new RelationScope("wiki", null);
        relations.propose("ai:acronyms", RelationOrigin.AI, scope, Set.of(RelationType.ABBREVIATION), "a1",
                List.of(new TermRelations.Proposal("ABC", "Alpha Beta Gamma", RelationType.ABBREVIATION,
                        List.of(new RelationEvidence("wiki", "1", 0, 3, "sha256:x")))));

        SynonymDiscovery.Outcome outcome = run(SynonymScope.source("wiki"));

        assertThat(outcome.aiUsed()).isTrue();
        assertThat(outcome.asked()).isEqualTo(1);
        assertThat(outcome.ai().added()).isEqualTo(1);
        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0)).contains("[[Rückmeldeschlüssel]]").contains("[[Rückmeldungsschlüssel]]")
                .doesNotContain("ABC");
        TermRelation synonym = automatic(RelationOrigin.AI, RelationType.SYNONYM).get(0);
        assertThat(synonym.status()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(synonym.scope()).isEqualTo(scope);
        assertThat(synonym.createdBy()).isEqualTo(SynonymDiscovery.ACTOR_AI);
        assertThat(synonym.evidence()).hasSize(2);
        assertThat(synonym.evidence()).extracting(RelationEvidence::pageId).containsExactlyInAnyOrder("1", "2");
        assertThat(automatic(RelationOrigin.AI, RelationType.ABBREVIATION)).extracting(TermRelation::fromTerm)
                .containsExactly("ABC");
        // Bestätigte und manuelle Beziehungen bleiben.
        assertThat(relations.stored().stream().filter(r -> r.origin() == RelationOrigin.MANUAL)).hasSize(2);
    }

    @Test
    void relatedStaysRelatedAndOtherVerdictsYieldNothing() {
        siblings("wiki");
        answers = (instructions, input) -> "{\"judgments\":[{\"pair\":1,\"relation\":\"related\",\"a\":[1],\"b\":[1]}]}";
        assertThat(run(SynonymScope.source("wiki")).ai().added()).isEqualTo(1);
        assertThat(automatic(RelationOrigin.AI, null)).extracting(TermRelation::type)
                .containsExactly(RelationType.RELATED);

        for (String answer : List.of(
                "{\"judgments\":[{\"pair\":1,\"relation\":\"unclear\",\"a\":[1],\"b\":[1]}]}",
                "{\"judgments\":[{\"pair\":1,\"relation\":\"abbreviation\",\"a\":[1],\"b\":[1]}]}",
                "{\"judgments\":[{\"pair\":1,\"relation\":\"synonym\",\"a\":[1],\"b\":[]}]}",
                "{\"judgments\":[{\"pair\":7,\"relation\":\"synonym\",\"a\":[1],\"b\":[1]}]}",
                "{\"judgments\":[{\"pair\":1,\"relation\":\"synonym\",\"a\":[9],\"b\":[1]}]}")) {
            answers = (instructions, input) -> answer;
            cached("wiki", "DEV", "Neue Seite " + pages + " nennt den Rückmeldeschlüssel.");
            run(SynonymScope.source("wiki"));
            assertThat(automatic(RelationOrigin.AI, null)).as(answer).isEmpty();
        }
    }

    @Test
    void aSecondRunReusesTheCacheAndCreatesNoDuplicates() {
        siblings("wiki");
        run(SynonymScope.source("wiki"));

        SynonymDiscovery.Outcome second = run(SynonymScope.source("wiki"));

        assertThat(inputs).hasSize(1);
        assertThat(second.reused()).isEqualTo(1);
        assertThat(second.ai()).isEqualTo(new TermRelations.ProposalRun(0, 0, 0, 0, 0));
        assertThat(automatic(RelationOrigin.AI, null)).hasSize(1);
    }

    @Test
    void confirmedOrRejectedPairsAreNeverAskedAgainAndKeepTheirDecision() {
        siblings("wiki");
        run(SynonymScope.source("wiki"));
        TermRelation proposed = automatic(RelationOrigin.AI, null).get(0);
        relations.reject(EDITOR, proposed.id(), proposed.revision());
        cached("wiki", "DEV", "Der Rückmeldeschlüssel steht auch hier.");

        SynonymDiscovery.Outcome again = run(SynonymScope.source("wiki"));

        assertThat(inputs).hasSize(1);
        assertThat(again.asked()).isZero();
        assertThat(relations.relation(EDITOR, proposed.id()).status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(automatic(RelationOrigin.AI, null)).extracting(TermRelation::status)
                .containsExactly(ReviewStatus.REJECTED);
    }

    @Test
    void anUnreleasedSourceIsNeverSentToTheModel() {
        siblings("intern");

        SynonymDiscovery.Outcome outcome = run(SynonymScope.source("intern"));

        assertThat(inputs).isEmpty();
        assertThat(outcome.skipped()).isEqualTo(1);
        assertThat(automatic(RelationOrigin.AI, null)).isEmpty();

        released.add("intern");
        assertThat(run(SynonymScope.source("intern")).asked()).isEqualTo(1);
        // Freigabe wieder weg: das Paar wird übersprungen, sein Vorschlag bleibt, nichts geht an den Dienst.
        released.remove("intern");
        cached("intern", "DEV", "Geändert: der Rückmeldeschlüssel.");
        SynonymDiscovery.Outcome withheld = run(SynonymScope.source("intern"));
        assertThat(withheld.asked()).isZero();
        assertThat(withheld.skipped()).isEqualTo(1);
        assertThat(inputs).hasSize(1);
        assertThat(automatic(RelationOrigin.AI, null)).hasSize(1);
    }

    @Test
    void aModelFailurePreservesTheEarlierProposals() {
        siblings("wiki");
        run(SynonymScope.source("wiki"));
        cached("wiki", "DEV", "Geändert: Der Rückmeldeschlüssel steht auch hier. Der Rückmeldungsschlüssel auch.");
        answers = (instructions, input) -> {
            throw new IllegalStateException("Dienst weg");
        };

        assertThatThrownBy(() -> run(SynonymScope.source("wiki"))).isInstanceOf(JobFailedException.class)
                .hasMessageNotContaining("Rückmelde");
        assertThat(automatic(RelationOrigin.AI, null)).hasSize(1);
    }

    @Test
    void scopesStaySeparate() {
        siblings("wiki");
        run(SynonymScope.space("wiki", "DEV"));
        run(SynonymScope.source("wiki"));

        assertThat(automatic(RelationOrigin.AI, null)).extracting(TermRelation::scope)
                .containsExactlyInAnyOrder(new RelationScope("wiki", "DEV"), new RelationScope("wiki", null));
        assertThat(inputs).hasSize(2);
    }

    @Test
    void startRunsAsAJobWithRightsOnTheScope() {
        siblings("wiki");
        assertThatThrownBy(() -> discovery.start(READER, new SynonymDiscovery.Request(SynonymScope.ALL, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(discovery.availability(SynonymScope.ALL).released()).containsExactly("wiki");
        assertThat(discovery.availability(SynonymScope.ALL).withheld()).containsExactly("intern");

        JobStatus job = discovery.start(EDITOR, new SynonymDiscovery.Request(SynonymScope.ALL, 0));

        assertThat(discovery.started(job.id())).isTrue();
        assertThat(jobs.status(job.id(), EDITOR.jobOwner()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(job.sourceId()).isEqualTo(Jobs.ALL_SOURCES);
        assertThat(inputs).isEmpty();
        assertThat(automatic(RelationOrigin.AI, null)).isEmpty();
    }
}
