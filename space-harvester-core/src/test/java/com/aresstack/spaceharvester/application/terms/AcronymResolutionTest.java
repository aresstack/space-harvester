package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link AcronymResolution}: Modellantworten werden gegen die Fundstellen geprüft und nur Vorschläge. */
class AcronymResolutionTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final AccessContext READER = AccessContext.technicalClient("reader");
    private static final Instant T0 = Instant.parse("2026-10-19T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source("wiki"));
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final Enrichments enrichments = new Enrichments(PLAIN, List.of(new TermProcessor()), store,
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final List<String> inputs = new ArrayList<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10, Duration.ofHours(1)),
            Clock.fixed(T0, ZoneOffset.UTC));
    private BiFunction<String, String, String> answers = (instructions, input) -> "{\"expansions\":[]}";
    private boolean available = true;
    private boolean released = true;
    private TermRelations relations;
    private AcronymResolution resolution;

    private final AcronymExpansionModel model = new AcronymExpansionModel() {
        @Override
        public String version() {
            return "fake:model@abc";
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean mayProcess(String sourceId) {
            return released;
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

    private void cached(String pageId, String text) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV",
                null, "1", T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of("wiki", metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
    }

    /** Nennt je Akronym die Ausschreibung mit den Textstellen, die sie enthalten. */
    private static BiFunction<String, String, String> naming(Map<String, List<String>> meanings) {
        return (instructions, input) -> {
            StringBuilder out = new StringBuilder("{\"expansions\":[");
            JsonNode acronyms = JSON.readTree(input).get("acronyms");
            boolean first = true;
            for (JsonNode acronym : acronyms) {
                for (String meaning : meanings.getOrDefault(acronym.get("acronym").asString(), List.of())) {
                    List<Integer> ids = new ArrayList<>();
                    for (JsonNode snippet : acronym.get("snippets")) {
                        if (snippet.get("text").asString().contains(meaning)) {
                            ids.add(snippet.get("id").asInt());
                        }
                    }
                    out.append(first ? "" : ",").append("{\"acronym\":\"").append(acronym.get("acronym").asString())
                            .append("\",\"expansion\":\"").append(meaning).append("\",\"snippets\":")
                            .append(ids.isEmpty() ? "[1]" : ids.toString()).append('}');
                    first = false;
                }
            }
            return out.append("]}").toString();
        };
    }

    private List<TermRelation> proposals(String term) {
        return relations.relations(EDITOR, new TermRelations.RelationSearch(term, null, null, RelationOrigin.AI,
                null, 50, null)).relations();
    }

    @BeforeEach
    void setUp() {
        AccessGuard guard = new AccessGuard((context, scope, kind) -> kind == AccessKind.SYNC
                && context.equals(READER) ? AccessDecision.DENY : AccessDecision.ALLOW);
        relations = new TermRelations(catalog, new InMemoryTermRelationStore(), guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
        resolution = new AcronymResolution(catalog, store, guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)).flatMap(PLAIN::project), relations, model, jobs,
                new TermProcessor().version(), Clock.fixed(T0, ZoneOffset.UTC));
        cached("1", "Jede Meldung trägt den RSL der Fachabteilung.");
        cached("2", "Der RSL steht für Rückmeldeschlüssel und wird vergeben.");
    }

    @Test
    void anExplanationInTheSnippetsBecomesAnAiProposalWithItsEvidence() {
        answers = naming(Map.of("RSL", List.of("Rückmeldeschlüssel")));

        TermRelations.ProposalRun run = resolution.run("wiki", null);

        assertThat(run.added()).isEqualTo(1);
        TermRelation rsl = proposals("rsl").get(0);
        assertThat(rsl.toTerm()).isEqualTo("Rückmeldeschlüssel");
        assertThat(rsl.origin()).isEqualTo(RelationOrigin.AI);
        assertThat(rsl.status()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(rsl.createdBy()).isEqualTo(AcronymResolution.ACTOR);
        assertThat(rsl.generation()).startsWith("fake:model@abc@");
        assertThat(rsl.evidence()).extracting(RelationEvidence::pageId).containsExactly("2");
        // An das Modell gehen nur kurze, markierte Fundstellen, nie die Anweisung im Text oder ganze Seiten.
        assertThat(inputs).singleElement().asString().contains("[[RSL]]").doesNotContain("Seite 1");
    }

    @Test
    void anExpansionThatIsNotInTheSnippetsIsDropped() {
        answers = (instructions, input) -> "Gern: {\"expansions\":[{\"acronym\":\"RSL\",\"expansion\":"
                + "\"Rechenschaftslegung\",\"snippets\":[1,2]},{\"acronym\":\"XYZ\",\"expansion\":\"Rückmeldeschlüssel\","
                + "\"snippets\":[2]},{\"acronym\":\"RSL\",\"expansion\":\"RSL\",\"snippets\":[2]}]}";

        assertThat(resolution.run("wiki", null).added()).isZero();
        assertThat(proposals("rsl")).isEmpty();
    }

    @Test
    void differentMeaningsBecomeSeparateProposals() {
        cached("3", "Im Lager ist RSL die Abkürzung für Regalstellplatz-Liste.");
        answers = naming(Map.of("RSL", List.of("Rückmeldeschlüssel", "Regalstellplatz-Liste")));

        resolution.run("wiki", null);

        assertThat(proposals("rsl")).extracting(TermRelation::toTerm)
                .containsExactlyInAnyOrder("Rückmeldeschlüssel", "Regalstellplatz-Liste");
        assertThat(proposals("rsl")).allSatisfy(relation -> assertThat(relation.evidence()).hasSize(1));
    }

    @Test
    void repeatedRunsAddNothingAndARejectionHolds() {
        answers = naming(Map.of("RSL", List.of("Rückmeldeschlüssel")));
        resolution.run("wiki", null);
        TermRelation rsl = proposals("rsl").get(0);

        assertThat(resolution.run("wiki", null)).isEqualTo(new TermRelations.ProposalRun(0, 0, 0, 0, 0));

        relations.reject(EDITOR, rsl.id(), null);
        assertThat(resolution.run("wiki", null).suppressed()).isEqualTo(1);
        assertThat(relations.relation(EDITOR, rsl.id()).status()).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void anUnchangedStateIsNotAskedAgainButAChangedTextIs() {
        answers = naming(Map.of("RSL", List.of("Rückmeldeschlüssel")));
        resolution.run("wiki", null);

        assertThat(resolution.run("wiki", null)).isEqualTo(new TermRelations.ProposalRun(0, 0, 0, 0, 0));
        assertThat(inputs).hasSize(1);
        assertThat(proposals("rsl")).hasSize(1);

        cached("1", "Neu: Der RSL ist der Rückmeldeschlüssel der Fachabteilung.");
        resolution.run("wiki", null);
        assertThat(inputs).hasSize(2);
        assertThat(proposals("rsl").get(0).evidence()).extracting(RelationEvidence::pageId)
                .containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void aFailingModelLeavesTheLastStateAndNamesNoText() {
        answers = naming(Map.of("RSL", List.of("Rückmeldeschlüssel")));
        resolution.run("wiki", null);
        cached("1", "Geändert: Jede Meldung trägt den RSL.");
        answers = (instructions, input) -> {
            throw new IllegalStateException("Dienst weg");
        };

        assertThatThrownBy(() -> resolution.run("wiki", null)).isInstanceOf(JobFailedException.class)
                .hasMessageNotContaining("RSL");
        assertThat(proposals("rsl")).hasSize(1);
    }

    @Test
    void withoutModelOrReleaseItDoesNotStart() {
        assertThatThrownBy(() -> resolution.start(READER, "wiki")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolution.start(EDITOR, "unbekannt")).isInstanceOf(UnknownSourceException.class);
        released = false;
        assertThatThrownBy(() -> resolution.start(EDITOR, "wiki"))
                .isInstanceOfSatisfying(AcronymResolutionUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(AcronymResolutionUnavailableException.Reason.NOT_RELEASED));
        available = false;
        assertThatThrownBy(() -> resolution.start(EDITOR, "wiki"))
                .isInstanceOfSatisfying(AcronymResolutionUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(AcronymResolutionUnavailableException.Reason.NO_MODEL));
        assertThat(inputs).isEmpty();
    }

    @Test
    void runsAsAnEnrichmentJobOfTheSource() {
        answers = naming(Map.of("RSL", List.of("Rückmeldeschlüssel")));

        JobStatus job = resolution.start(EDITOR, "wiki");

        JobStatus done = jobs.status(job.id());
        assertThat(done.type()).isEqualTo(JobType.ENRICHMENT);
        assertThat(done.sourceId()).isEqualTo("wiki");
        assertThat(done.partitionKey()).isNull();
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(proposals("rsl")).hasSize(1);
        // Nur eigene Aufträge zählen, nicht jeder Anreicherungsauftrag ohne Space.
        assertThat(resolution.started(job.id())).isTrue();
        assertThat(resolution.started("fremd")).isFalse();
    }

    @Test
    void anUnreadableAnswerKeepsTheProposalsAndIsAskedAgain() {
        answers = naming(Map.of("RSL", List.of("Rückmeldeschlüssel")));
        resolution.run("wiki", null);
        cached("1", "Geändert: Jede Meldung trägt den RSL.");
        answers = (instructions, input) -> "Leider abgeschnitten: {\"expansions\":[";

        assertThat(resolution.run("wiki", null).withdrawn()).isZero();
        assertThat(proposals("rsl")).hasSize(1);

        answers = naming(Map.of("RSL", List.of("Rückmeldeschlüssel")));
        resolution.run("wiki", null);
        assertThat(inputs).hasSize(3);
        assertThat(proposals("rsl")).hasSize(1);
    }

    @Test
    void anEntryWithoutEvidenceDoesNotBlockTheSameExpansionWithEvidence() {
        answers = (instructions, input) -> "{\"expansions\":[{\"acronym\":\"RSL\",\"expansion\":"
                + "\"Rückmeldeschlüssel\",\"snippets\":[99]},{\"acronym\":\"RSL\",\"expansion\":"
                + "\"Rückmeldeschlüssel\",\"snippets\":[1,2]}]}";

        assertThat(resolution.run("wiki", null).added()).isEqualTo(1);
    }

    @Test
    void anAcronymWithoutReadableTextKeepsItsProposals() {
        answers = naming(Map.of("RSL", List.of("Rückmeldeschlüssel")));
        resolution.run("wiki", null);
        // Neuer Text im Cache, Begriffe noch nicht neu berechnet: Die Fundstellen passen zu keinem lesbaren Text.
        for (String pageId : List.of("1", "2")) {
            PageSnapshot old = cache.values().stream().filter(snapshot -> snapshot.key().pageId().equals(pageId))
                    .findFirst().orElseThrow();
            cache.put(old.key(), PageSnapshot.of("wiki", old.metadata(), new NativePayload(
                    "Ganz anderer Text.".getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0));
        }

        assertThat(resolution.run("wiki", null).withdrawn()).isZero();
        assertThat(proposals("rsl")).hasSize(1);
        assertThat(inputs).hasSize(1);
    }

    @Test
    void theFingerprintDependsOnTheSource() {
        AcronymResolution.Acronym acronym = new AcronymResolution.Acronym("rsl", "RSL");

        assertThat(AcronymResolution.fingerprint("wiki", "m", acronym))
                .isNotEqualTo(AcronymResolution.fingerprint("spiegel", "m", acronym));
    }
}
