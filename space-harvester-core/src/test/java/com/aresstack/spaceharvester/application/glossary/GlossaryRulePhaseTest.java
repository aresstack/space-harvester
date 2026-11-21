package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation.Category;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Argument;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Basis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.MentionKind;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Priority;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Quality;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Role;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Voice;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.AcronymExpansionModel;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ResolutionCache;
import com.aresstack.spaceharvester.persistence.files.FileGlossaryStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Auftrag „Fachbegriffe ermitteln“ mit erster Phase „Begriffe ableiten“ (ADR 0032, FL5b): ohne Modell, Teilergebnisse
 * sofort, eine ausgefallene Modellphase löscht nichts und nennt Grund und Zahlen; Wortlisten als Einstellung.
 */
class GlossaryRulePhaseTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final RelationScope WIKI = new RelationScope("wiki", null);
    private static final ParsedStatements.Features FACT = new ParsedStatements.Features(
            ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT);

    @TempDir
    Path directory;

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source());
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source()) : Optional.empty();
        }
    };
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10, Duration.ofHours(1)),
            Clock.fixed(T0, ZoneOffset.UTC));
    private final List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private boolean available;
    private boolean released;
    private GlossaryRuleSettings settings = GlossaryRuleSettings.DEFAULT;
    private GlossaryStore store;
    private Glossary glossary;
    private GlossaryClassification classification;

    private final AcronymExpansionModel model = new AcronymExpansionModel() {
        @Override
        public String version() {
            return "fake:model@1";
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
            asked.add(input);
            return "{\"technical\":[]}";
        }
    };

    private static HarvestSource source() {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn("wiki");
        return source;
    }

    /** Eine erfundene Seite mit einem Satz „‹Handelnder› ‹verb› ‹Betroffenes›.“ im Space {@code space}. */
    private void page(String id, String space, String actor, String verb, String affected) {
        PageKey key = new PageKey("wiki", id);
        int length = actor.length() + verb.length() + affected.length() + 3;
        ParsedStatements.Sentence sentence = new ParsedStatements.Sentence(0, 0, length);
        String first = "wiki/" + id + "@1#s0.t0";
        String second = "wiki/" + id + "@1#s0.t1";
        List<ParsedStatements.Mention> mentions = List.of(
                new ParsedStatements.Mention(first, 0, 0, actor.length(), actor, actor, actor, MentionKind.NOUN,
                        false, Set.of()),
                new ParsedStatements.Mention(second, 0, length - affected.length() - 1, length - 1, affected,
                        affected, affected, MentionKind.NOUN, false, Set.of()));
        ParsedStatements.Statement statement = new ParsedStatements.Statement("wiki/" + id + "@1#s0.v2", 0,
                actor.length() + 1, actor.length() + 1 + verb.length(), verb, verb, Voice.ACTIVE,
                ParsedStatements.Clause.MAIN, true, List.of(sure(Role.ACTOR, first), sure(Role.AFFECTED_OBJECT,
                second)), Set.of(), 0, Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED, FACT, null);
        ParsedStatements parsed = new ParsedStatements(List.of(sentence), mentions, List.of(statement), List.of(),
                List.of(), null);
        PageStatementAnalysis analysis = new PageStatementAnalysis(key, space, "Seite " + id, "1",
                "sha256:" + "c".repeat(64), "fake", "1", Instant.EPOCH, parsed);
        pages.add(new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(key, space, "Seite " + id,
                PageStatements.State.CURRENT, null), analysis));
    }

    private static Argument sure(Role role, String mention) {
        return new Argument(role, mention, "rel", null, Priority.CORE, Basis.PARSER, true);
    }

    @BeforeEach
    void setUp() {
        store = new FileGlossaryStore(directory);
        glossary = new Glossary(catalog, store, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                key -> Optional.empty(), Clock.fixed(T0, ZoneOffset.UTC));
        classification = new GlossaryClassification(catalog, new InMemoryEnrichmentStore(),
                new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), key -> Optional.empty(),
                key -> Optional.<PageText>empty(), glossary, model, ResolutionCache.inMemory(), jobs,
                new TermProcessor().version(), GlossaryClassification.Budget.DEFAULT, caller -> List.copyOf(pages),
                () -> settings, Clock.fixed(T0, ZoneOffset.UTC));
        page("1", "FOE", "Bewilligungsstelle", "prüft", "Antrag");
        page("2", "FOE", "Antragsdatenbank", "speichert", "Antrag");
        page("3", "IT", "Server", "liefert", "Bescheid");
    }

    private Optional<GlossaryEntry> entry(String term, RelationScope scope) {
        return store.find(GlossaryEntry.idOf(term, scope));
    }

    @Test
    void withoutModelTheRulePhaseRunsAndTheResultNamesWhyTheModelPhaseWasSkipped() {
        List<JobEvent.Draft> events = new ArrayList<>();
        List<String> phases = new ArrayList<>();
        JobContext context = context(events, phases);

        GlossaryClassification.Outcome outcome = classification.job(WIKI, context, EDITOR);

        assertThat(outcome.model()).isNull();
        assertThat(outcome.modelSkipped()).isEqualTo(GlossaryClassification.SkipReason.NO_MODEL);
        assertThat(outcome.rules().pages()).isEqualTo(3);
        assertThat(outcome.rules().run().added()).isEqualTo(outcome.rules().proposed()).isPositive();
        GlossaryEntry antrag = entry("Antrag", WIKI).orElseThrow();
        assertThat(antrag.origin()).isEqualTo(RelationOrigin.RULE);
        assertThat(antrag.supportedBy()).containsExactly(RelationOrigin.RULE);
        assertThat(antrag.evidence()).extracting(RelationEvidence::pageId).contains("1", "2");
        assertThat(entry("Antragsdatenbank", WIKI).orElseThrow().classification().category())
                .isEqualTo(Category.TECHNISCH);
        assertThat(phases).startsWith(GlossaryClassification.RULE_PHASE);
        assertThat(events).extracting(JobEvent.Draft::code).containsExactly(GlossaryClassification.RULES_DONE,
                "GLOSSARY_MODEL_SKIPPED:NO_MODEL");
        assertThat(events).allSatisfy(event -> assertThat(event.severity()).isEqualTo(JobEvent.Severity.INFO));
        assertThat(events.get(0).message()).contains("3 Seiten");
        assertThat(asked).isEmpty();
    }

    @Test
    void aSkippedModelPhaseDeletesNothingAndAReleasedModelStillRuns() {
        available = true;
        released = true;
        classification.job(WIKI, null, EDITOR);
        store.write(List.of(new GlossaryEntry("gai", "Haushaltsverfahren", "haushaltsverfahren", WIKI,
                RelationOrigin.AI, com.aresstack.spaceharvester.application.terms.ReviewStatus.PROPOSED,
                List.of(new RelationEvidence("wiki", "1", 0, 10, "sha256:" + "c".repeat(64))), false, null,
                "fake:model@1", "ki", T0, T0, null, null, 1)), List.of(), List.of());

        released = false;
        GlossaryClassification.Outcome outcome = classification.job(WIKI, null, EDITOR);

        assertThat(outcome.modelSkipped()).isEqualTo(GlossaryClassification.SkipReason.NOT_RELEASED);
        assertThat(store.find("gai")).as("ausgefallene Modellphase löscht nichts").isPresent();
        assertThat(entry("Antrag", WIKI)).isPresent();
    }

    @Test
    void aSpaceScopeOnlyUsesPagesOfThatSpaceAndMaxTermsKeepsTheRest() {
        RelationScope it = new RelationScope("wiki", "IT");
        settings = settings.withMaxTerms(1);

        GlossaryClassification.Outcome outcome = classification.job(it, null, EDITOR);

        assertThat(outcome.rules().pages()).isEqualTo(1);
        assertThat(outcome.rules().proposed()).isEqualTo(1);
        assertThat(outcome.rules().retained()).isEqualTo(1);
        assertThat(entry("Antrag", it)).isEmpty();
    }

    @Test
    void aSubtreeRunOnlySupplementsAndLosesNothingFromOutside() {
        // D4.2, Auflage 1: „Antrag“ ist im Space auf fünf Seiten belegt; ein Lauf über zwei davon lässt alle fünf.
        RelationScope foe = new RelationScope("wiki", "FOE");
        page("4", "FOE", "Prüfstelle", "bearbeitet", "Antrag");
        page("5", "FOE", "Sachbearbeitung", "ergänzt", "Antrag");
        page("6", "FOE", "Poststelle", "erfasst", "Antrag");
        classification.job(foe, null, EDITOR);
        GlossaryEntry before = entry("Antrag", foe).orElseThrow();
        // Die Regel belegt mit höchstens einigen Fundstellen; welche, entscheidet die Wichtigkeit.
        assertThat(before.evidence()).isNotEmpty();
        GlossaryEntry outside = entry("Poststelle", foe).orElseThrow();

        GlossaryClassification.Outcome outcome = classification.job(foe,
                com.aresstack.spaceharvester.application.snapshot.PageSet.of(Map.of("wiki", List.of("1", "2"))),
                null, EDITOR);

        assertThat(outcome.rules().pages()).isEqualTo(2);
        assertThat(outcome.rules().run().withdrawn()).isZero();
        assertThat(outcome.rules().run().stale()).isZero();
        GlossaryEntry after = entry("Antrag", foe).orElseThrow();
        assertThat(after.evidence()).as("Belege von außerhalb bleiben").containsAll(before.evidence());
        assertThat(after.supportedBy()).isEqualTo(before.supportedBy());
        assertThat(after.classification()).isEqualTo(before.classification());
        // Nur außerhalb belegt: weder entfallen noch veraltet.
        assertThat(entry("Poststelle", foe)).hasValueSatisfying(entry -> {
            assertThat(entry.evidence()).isEqualTo(outside.evidence());
            assertThat(entry.evidenceStale()).isFalse();
        });
    }

    @Test
    void supplementingKeepsEvidenceOnFivePagesWhenTheSubtreeCoversTwo() {
        RelationScope foe = new RelationScope("wiki", "FOE");
        List<RelationEvidence> five = List.of("1", "2", "4", "5", "6").stream()
                .map(id -> new RelationEvidence("wiki", id, 0, 6, "sha256:" + "c".repeat(64))).toList();
        glossary.propose(RelationOrigin.RULE, "regel", foe, "r1", List.of(new Glossary.Proposal("Antrag", five)),
                Set.of());

        Glossary.ProposalRun run = glossary.supplement(RelationOrigin.AI, "ki", foe, "k1",
                List.of(new Glossary.Proposal("Antrag", List.of(
                        new RelationEvidence("wiki", "1", 3, 9, "sha256:" + "d".repeat(64)),
                        new RelationEvidence("wiki", "2", 0, 6, "sha256:" + "c".repeat(64))))));

        GlossaryEntry antrag = entry("Antrag", foe).orElseThrow();
        assertThat(run.updated()).isEqualTo(1);
        assertThat(antrag.evidence()).extracting(RelationEvidence::pageId).containsExactly("1", "2", "4", "5", "6");
        assertThat(antrag.evidence().get(0).start()).as("Seite 1 mit dem neuen Beleg").isEqualTo(3);
        assertThat(antrag.supportedBy()).containsExactlyInAnyOrder(RelationOrigin.RULE, RelationOrigin.AI);
    }

    @Test
    void aSubtreeStartsAsAJobWithItsStartPageAndNeedsASpace() {
        List<com.aresstack.spaceharvester.application.snapshot.PageSet> asked = new ArrayList<>();
        classification.subtrees((caller, sourceId, spaceKey, pageId) -> {
            com.aresstack.spaceharvester.application.snapshot.PageSet set =
                    com.aresstack.spaceharvester.application.snapshot.PageSet.of(Map.of("wiki", List.of("1")));
            asked.add(set);
            return set;
        });

        List<JobStatus> started = classification.start(EDITOR, "wiki", "FOE", "1");

        assertThat(started).singleElement().satisfies(status -> {
            assertThat(status.rootPageId()).isEqualTo("1");
            assertThat(status.partitionKey()).isEqualTo("FOE");
        });
        assertThat(asked).hasSize(1);
        assertThat(entry("Antragsdatenbank", new RelationScope("wiki", "FOE"))).isEmpty();
        assertThat(entry("Bewilligungsstelle", new RelationScope("wiki", "FOE"))).isPresent();
        assertThatThrownBy(() -> classification.start(EDITOR, "wiki", null, "1"))
                .isInstanceOf(com.aresstack.spaceharvester.application.sources.InvalidQueryException.class);
    }

    @Test
    void theJobStartsWithoutModelOrRelease() {
        assertThat(classification.available()).isTrue();
        assertThat(classification.modelAvailable()).isFalse();

        assertThat(classification.start(EDITOR, "wiki", null)).hasSize(1);

        assertThat(entry("Antrag", WIKI)).isPresent();
    }

    @Test
    void wordListsFromTheSettingsChangeTheClassification() {
        settings = settings.with(GlossaryRuleSettings.Kind.TECHNICAL_HEADS, List.of(), List.of("datenbank"))
                .with(GlossaryRuleSettings.Kind.ROLE_HEADS, List.of("bewilligungsstelle"), List.of());

        classification.job(WIKI, null, EDITOR);

        assertThat(entry("Antragsdatenbank", WIKI).orElseThrow().classification().category())
                .isEqualTo(Category.UNKLAR);
        assertThat(entry("Bewilligungsstelle", WIKI).orElseThrow().classification())
                .isEqualTo(new GlossaryEntry.Classification(Category.ROLLE, GlossaryEntry.ClassifiedBy.RULE,
                        "ROLE_HEAD"));
    }

    @Test
    void theOverviewListsImportancePerScopeAndDetailsPerTermWithoutStoringAnything() {
        GlossaryOverview overview = new GlossaryOverview(caller -> List.copyOf(pages), () -> settings);

        GlossaryOverview.Overview foe = overview.importance(EDITOR, "wiki", "FOE", () -> false);

        assertThat(foe.pages()).isEqualTo(2);
        assertThat(foe.rows().get(0).normalized()).isEqualTo("antrag");
        assertThat(foe.rows().get(0).importance().pages()).isEqualTo(2);
        assertThat(foe.rows()).extracting(GlossaryOverview.Row::normalized).doesNotContain("server");
        assertThat(overview.importance(EDITOR, null, null, () -> false).rows()).hasSize(5);
        GlossaryDerivation.Term antrag = overview.term(EDITOR, "wiki", null, "Antrag", () -> false).orElseThrow();
        assertThat(antrag.undergoes()).extracting(GlossaryDerivation.Use::lemma).contains("prüft", "speichert");
        assertThat(overview.term(EDITOR, "wiki", "IT", "Antrag", () -> false)).isEmpty();
        assertThat(store.all()).as("nichts gespeichert").isEmpty();
        assertThatThrownBy(() -> overview.importance(EDITOR, null, "FOE", () -> false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsKeepTheShortHeadRuleAndBothLoadersReadTheKeys() {
        assertThatThrownBy(() -> GlossaryRuleSettings.DEFAULT.with(GlossaryRuleSettings.Kind.TECHNICAL_HEADS,
                List.of("port"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(GlossaryRuleSettings.Kind.TECHNICAL_WORDS.addKey());
        assertThat(GlossaryRuleSettings.DEFAULT.with(GlossaryRuleSettings.Kind.TECHNICAL_WORDS, List.of(" Port ",
                "port", "Load  Balancer"), List.of()).added(GlossaryRuleSettings.Kind.TECHNICAL_WORDS))
                .containsExactly("port", "load balancer");

        Properties properties = new Properties();
        properties.setProperty(GlossaryRuleSettings.MAX_TERMS, "120");
        properties.setProperty(GlossaryRuleSettings.Kind.ROLE_WORDS.addKey(), "Sachgebiet, amt");
        properties.setProperty(GlossaryRuleSettings.Kind.TECHNICAL_HEADS.excludeKey() + "[0]", "Datei");
        GlossaryRuleSettings read = HarvesterConfiguration.fromProperties(properties).orThrow().terms()
                .glossaryRules();
        assertThat(read.maxTerms()).isEqualTo(120);
        assertThat(read.added(GlossaryRuleSettings.Kind.ROLE_WORDS)).containsExactly("sachgebiet", "amt");
        assertThat(read.excluded(GlossaryRuleSettings.Kind.TECHNICAL_HEADS)).containsExactly("datei");
        assertThat(HarvesterConfiguration.fromProperties(new Properties()).orThrow().terms().glossaryRules())
                .isEqualTo(GlossaryRuleSettings.DEFAULT);

        List<String> problems = new ArrayList<>();
        assertThat(GlossaryRuleSettings.accepted(GlossaryRuleSettings.Kind.ROLE_HEADS, true,
                List.of("amt", "Sachgebietsleitung", "a/b"), problems::add))
                .as("nur das falsche Wort fällt").containsExactly("sachgebietsleitung");
        assertThat(problems).hasSize(2).anySatisfy(problem -> assertThat(problem).contains("amt"))
                .anySatisfy(problem -> assertThat(problem).contains("a/b"));
        for (String word : List.of("Load-Balancer", "Übergabe  Stelle", "Prüfer-In", "SCHNITTSTELLE")) {
            assertThat(GlossaryRuleSettings.normalize(word)).as("eine Normalisierung")
                    .isEqualTo(com.aresstack.spaceharvester.application.terms.TermText.normalize(word));
        }
        assertThat(GlossaryDerivation.Rules.of(GlossaryRuleSettings.DEFAULT.with(
                GlossaryRuleSettings.Kind.TECHNICAL_WORDS, List.of("Über-Gabe"), List.of()))
                .words(GlossaryRuleSettings.Kind.TECHNICAL_WORDS)).contains("über-gabe");
        assertThat(GlossaryDerivation.ruleFor("Über-Gabe", GlossaryDerivation.Rules.of(GlossaryRuleSettings.DEFAULT
                .with(GlossaryRuleSettings.Kind.TECHNICAL_WORDS, List.of("Über-Gabe"), List.of()))))
                .isEqualTo(GlossaryDerivation.CategoryRule.TECHNICAL_HEAD);

        Properties wrong = new Properties();
        wrong.setProperty(GlossaryRuleSettings.Kind.ROLE_HEADS.addKey(), "amt");
        wrong.setProperty(GlossaryRuleSettings.MAX_TERMS, "0");
        assertThat(HarvesterConfiguration.fromProperties(wrong).problems())
                .anySatisfy(problem -> assertThat(problem.toString())
                        .contains(GlossaryRuleSettings.Kind.ROLE_HEADS.addKey()))
                .anySatisfy(problem -> assertThat(problem.toString()).contains(GlossaryRuleSettings.MAX_TERMS));
    }

    private static JobContext context(List<JobEvent.Draft> events, List<String> phases) {
        return new JobContext() {
            @Override
            public String jobId() {
                return "job";
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public boolean cancelRequested() {
                return false;
            }

            @Override
            public void discovered(long count) {
            }

            @Override
            public void processed(long count) {
            }

            @Override
            public void written(long count) {
            }

            @Override
            public void failed(long count) {
            }

            @Override
            public void phase(String component, String phase) {
                phases.add(phase);
            }

            @Override
            public void record(JobEvent.Draft event) {
                events.add(event);
            }
        };
    }
}
