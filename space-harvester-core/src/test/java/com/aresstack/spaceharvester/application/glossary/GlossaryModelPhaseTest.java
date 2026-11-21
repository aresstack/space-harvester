package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation.Category;
import com.aresstack.spaceharvester.application.glossary.GlossaryEntry.ClassifiedBy;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
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
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.ResolutionCache;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.persistence.files.FileGlossaryStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase mit Modell je Seite (ADR 0032, FL5c), nur mit Fake-Modell und erfundenen Sätzen: Hinaus geht nur, was von
 * freigegebenen Seiten stammt; gefragt wird nur {@code UNKLAR}; die Kategorie folgt aus der Kennung; Entzug setzt
 * zurück. Die Handantworten prüfen nur Verarbeitung und Vorrang, nie Qualität.
 */
class GlossaryModelPhaseTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final RelationScope WIKI = new RelationScope("wiki", null);
    private static final JsonMapper JSON = JsonMapper.builder().build();
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
    private final Map<PageKey, PageText> texts = new HashMap<>();
    private final Set<PageKey> released = new HashSet<>();
    private final List<String> requests = new ArrayList<>();
    /** Handantworten je Suchwert; ohne Eintrag {@code DOMAIN_OBJECT}. */
    private final Map<String, String> answers = new HashMap<>();
    private final List<String> foreign = new ArrayList<>();
    private boolean available = true;
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
            return true;
        }

        @Override
        public String complete(String instructions, String input) {
            requests.add(input);
            StringBuilder answer = new StringBuilder("Hier: {\"terms\":[");
            for (String term : terms(input)) {
                answer.append("{\"term\":\"").append(term).append("\",\"reason\":\"")
                        .append(answers.getOrDefault(term, "DOMAIN_OBJECT")).append("\"},");
            }
            foreign.forEach(item -> answer.append(item).append(','));
            answer.setLength(answer.length() - 1);
            return answer.append("]}").toString();
        }
    };

    private static HarvestSource source() {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn("wiki");
        return source;
    }

    private static List<String> terms(String input) {
        List<String> terms = new ArrayList<>();
        JSON.readTree(input).get("terms").forEach(term -> terms.add(term.get("term").asString()));
        return terms;
    }

    /** Eine erfundene Seite mit einem Satz „‹Handelnder› ‹verb› ‹Betroffenes›.“ samt passendem Text. */
    private void page(String id, String actor, String verb, String affected) {
        PageKey key = new PageKey("wiki", id);
        String sentenceText = actor + " " + verb + " " + affected + ".";
        PageText text = new PageText("fake/v1", sentenceText, List.of());
        texts.put(key, text);
        int length = sentenceText.length();
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
        PageStatementAnalysis analysis = new PageStatementAnalysis(key, "FOE", "Seite " + id, "1",
                text.inputSha256(), "fake", "1", Instant.EPOCH, parsed);
        pages.add(new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(key, "FOE", "Seite " + id,
                PageStatements.State.CURRENT, null), analysis));
    }

    private static Argument sure(Role role, String mention) {
        return new Argument(role, mention, "rel", null, Priority.CORE, Basis.PARSER, true);
    }

    /** Welche Eimer ein Lauf im Modell-Cache ersetzt. */
    private final List<String> buckets = new ArrayList<>();
    private final ResolutionCache recording = new ResolutionCache() {
        private final ResolutionCache inner = ResolutionCache.inMemory();

        @Override
        public Optional<List<String>> find(String fingerprint) {
            return inner.find(fingerprint);
        }

        @Override
        public void replace(String sourceId, java.util.Map<String, List<String>> entries) {
            buckets.add(sourceId);
            inner.replace(sourceId, entries);
        }
    };

    @BeforeEach
    void setUp() {
        store = new FileGlossaryStore(directory);
        glossary = new Glossary(catalog, store, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                key -> Optional.empty(), Clock.fixed(T0, ZoneOffset.UTC));
        classification = new GlossaryClassification(catalog, new InMemoryEnrichmentStore(),
                new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), key -> Optional.empty(),
                key -> Optional.ofNullable(texts.get(key)), glossary, model, recording, jobs,
                new TermProcessor().version(), GlossaryClassification.Budget.DEFAULT, caller -> List.copyOf(pages),
                () -> settings, released::contains, Clock.fixed(T0, ZoneOffset.UTC));
        page("1", "Bewilligungsstelle", "prüft", "Zuwendung");
        page("2", "Antragsdatenbank", "speichert", "Zuwendung");
        page("3", "Geheimgremium", "verschließt", "Verschlusssache");
        released.add(new PageKey("wiki", "1"));
        released.add(new PageKey("wiki", "2"));
    }

    private GlossaryEntry entry(String term) {
        return store.find(GlossaryEntry.idOf(term, WIKI)).orElseThrow();
    }

    private Set<String> askedTerms() {
        Set<String> asked = new LinkedHashSet<>();
        requests.forEach(request -> asked.addAll(terms(request)));
        return asked;
    }

    @Test
    void onlyWordsOfReleasedPagesLeaveAndOnlyUnclearEntriesAreAsked() {
        GlossaryClassification.Outcome outcome = classification.job(WIKI, null, EDITOR);

        assertThat(requests).isNotEmpty();
        for (String request : requests) {
            assertThat(request).as("nichts von der nicht freigegebenen Seite")
                    .doesNotContain("Geheimgremium", "verschließt", "Verschlusssache", "Seite 3");
        }
        assertThat(entry("Geheimgremium").classification().by()).as("Regel kennt den Begriff, die KI nicht")
                .isNotEqualTo(ClassifiedBy.AI);
        assertThat(entry("Antragsdatenbank").classification().category()).isEqualTo(Category.TECHNISCH);
        assertThat(askedTerms()).as("Regel hat entschieden").doesNotContain("Antragsdatenbank")
                .contains("Zuwendung");
        JsonNode zuwendung = JSON.readTree(requests.get(0)).get("terms").valueStream()
                .filter(term -> term.get("term").asString().equals("Zuwendung")).findFirst().orElseThrow();
        assertThat(zuwendung.get("pages").asString()).isEqualTo("2-3");
        assertThat(zuwendung.get("sentences")).hasSize(2);
        GlossaryEntry.Classification result = entry("Zuwendung").classification();
        assertThat(result).isEqualTo(new GlossaryEntry.Classification(Category.FACHLICH, ClassifiedBy.AI,
                "DOMAIN_OBJECT", List.of(new PageKey("wiki", "1"), new PageKey("wiki", "2"))));
        assertThat(outcome.classified().classified()).isPositive();
        assertThat(outcome.modelSkipped()).isNull();
    }

    @Test
    void handAnswersAreProcessedByTheFixedTableAndForeignOnesFall() {
        // Nur Verarbeitung und Vorrang; die Antworten sind ausgedacht und sagen nichts über Qualität.
        answers.put("Zuwendung", "PERSON_ROLE");
        answers.put("Bewilligungsstelle", "MAGIC_ID");
        foreign.add("{\"term\":\"Fremdwort\",\"reason\":\"DOMAIN_OBJECT\"}");
        foreign.add("{\"term\":\"Zuwendung\",\"reason\":\"SYSTEM_COMPONENT\"}");
        foreign.add("\"kaputt\"");
        available = false;
        classification.job(WIKI, null, EDITOR);
        GlossaryEntry.Classification before = entry("Bewilligungsstelle").classification();
        available = true;

        classification.job(WIKI, null, EDITOR);

        assertThat(entry("Zuwendung").classification().category()).as("erste Kennung zählt")
                .isEqualTo(Category.ROLLE);
        assertThat(askedTerms()).contains("Bewilligungsstelle");
        assertThat(entry("Bewilligungsstelle").classification()).as("fremde Kennung fällt weg").isEqualTo(before);
        assertThat(store.find(GlossaryEntry.idOf("Fremdwort", WIKI))).isEmpty();
        assertThat(GlossaryModelPhase.table()).containsEntry(GlossaryModelPhase.Reason.DATA_FORMAT,
                Category.TECHNISCH).containsEntry(GlossaryModelPhase.Reason.ORGANISATION, Category.ROLLE)
                .containsEntry(GlossaryModelPhase.Reason.LEGAL_TERM, Category.FACHLICH)
                .containsEntry(GlossaryModelPhase.Reason.GENERIC_WORD, Category.UNKLAR).hasSize(9);
    }

    @Test
    void unreadableAnswersChangeNothing() {
        AcronymExpansionModel broken = new AcronymExpansionModel() {
            @Override
            public String version() {
                return "fake:model@1";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public boolean mayProcess(String sourceId) {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                return "keine Ahnung";
            }
        };
        GlossaryClassification run = new GlossaryClassification(catalog, new InMemoryEnrichmentStore(),
                new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), key -> Optional.empty(),
                key -> Optional.ofNullable(texts.get(key)), glossary, broken, ResolutionCache.inMemory(), jobs,
                new TermProcessor().version(), GlossaryClassification.Budget.DEFAULT, caller -> List.copyOf(pages),
                () -> GlossaryRuleSettings.DEFAULT, released::contains, Clock.fixed(T0, ZoneOffset.UTC));

        GlossaryClassification.Outcome outcome = run.job(WIKI, null, EDITOR);

        assertThat(outcome.classified().rejected()).isPositive();
        assertThat(store.all()).noneMatch(item -> item.classification() != null
                && item.classification().by() == ClassifiedBy.AI);
    }

    @Test
    void precedenceKeepsManualAndDecidedEntriesAndTheCacheAnswersUnchangedTerms() {
        classification.job(WIKI, null, EDITOR);
        GlossaryEntry zuwendung = entry("Zuwendung");
        store.write(List.of(zuwendung.classified(new GlossaryEntry.Classification(Category.UNKLAR,
                ClassifiedBy.MANUAL, null), null, T0)), List.of(), List.of());
        requests.clear();

        answers.put("Zuwendung", "SYSTEM_COMPONENT");
        GlossaryClassification.Outcome again = classification.job(WIKI, null, EDITOR);

        assertThat(askedTerms()).as("Hand und KI-Entscheidung werden nicht gefragt").doesNotContain("Zuwendung");
        assertThat(entry("Zuwendung").classification().by()).isEqualTo(ClassifiedBy.MANUAL);
        assertThat(again.classified().asked()).isEqualTo(again.classified().reused() + askedTerms().size());
    }

    @Test
    void precedenceDoesNotDependOnTheOrderOfTheRuns() {
        GlossaryEntry.Classification ai = new GlossaryEntry.Classification(Category.FACHLICH, ClassifiedBy.AI,
                "DOMAIN_OBJECT");
        GlossaryEntry.Classification decided = new GlossaryEntry.Classification(Category.TECHNISCH,
                ClassifiedBy.RULE, "TECHNICAL_HEAD");
        GlossaryEntry.Classification open = new GlossaryEntry.Classification(Category.UNKLAR, ClassifiedBy.RULE,
                null);
        GlossaryEntry.Classification manual = new GlossaryEntry.Classification(Category.ROLLE, ClassifiedBy.MANUAL,
                null);
        for (List<GlossaryEntry.Classification> pair : List.of(List.of(ai, decided), List.of(ai, open),
                List.of(ai, manual), List.of(decided, manual), List.of(open, manual))) {
            // Zwei Regel-Einordnungen sind kein Vorrang: der neuere Regellauf gilt (Wortliste geändert).
            GlossaryEntry.Classification forward = Glossary.nextClassification(
                    Glossary.nextClassification(null, pair.get(0)), pair.get(1));
            GlossaryEntry.Classification backward = Glossary.nextClassification(
                    Glossary.nextClassification(null, pair.get(1)), pair.get(0));
            assertThat(forward).as("gleich in beiden Reihenfolgen: %s", pair).isEqualTo(backward);
        }
        assertThat(Glossary.nextClassification(ai, decided)).as("bestimmte Regel vor KI").isEqualTo(decided);
        assertThat(Glossary.nextClassification(ai, open)).as("UNKLAR der Regel nie über die KI").isEqualTo(ai);
        assertThat(Glossary.nextClassification(decided, ai)).isEqualTo(decided);
        assertThat(Glossary.nextClassification(manual, decided)).isEqualTo(manual);

        classification.job(WIKI, null, EDITOR);
        assertThat(entry("Zuwendung").classification().by()).isEqualTo(ClassifiedBy.AI);
        settings = settings.with(GlossaryRuleSettings.Kind.TECHNICAL_WORDS, List.of("zuwendung"), List.of());

        classification.job(WIKI, null, EDITOR);

        assertThat(entry("Zuwendung").classification()).as("neue Wortliste wirkt auch nach der KI")
                .isEqualTo(new GlossaryEntry.Classification(Category.TECHNISCH, ClassifiedBy.RULE,
                        "TECHNICAL_HEAD"));
    }

    @Test
    void aPageWithdrawnDuringTheRunIsNotInTheNextRequests() {
        for (int i = 0; i < 30; i++) {
            String suffix = String.valueOf((char) ('a' + i / 26)) + (char) ('a' + i % 26);
            page("m" + i, "Mumpf" + suffix, "bewertet", "Plonk" + suffix);
            released.add(new PageKey("wiki", "m" + i));
        }
        page("w", "Zuzzel", "verschiebt", "Zuzzelziel");
        PageKey withdrawn = new PageKey("wiki", "w");
        released.add(withdrawn);
        List<String> seen = new ArrayList<>();
        AcronymExpansionModel withdrawing = new AcronymExpansionModel() {
            @Override
            public String version() {
                return "fake:model@1";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public boolean mayProcess(String sourceId) {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                seen.add(input);
                released.remove(withdrawn);
                return "{\"terms\":[]}";
            }
        };
        GlossaryClassification run = new GlossaryClassification(catalog, new InMemoryEnrichmentStore(),
                new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), key -> Optional.empty(),
                key -> Optional.ofNullable(texts.get(key)), glossary, withdrawing, ResolutionCache.inMemory(), jobs,
                new TermProcessor().version(), GlossaryClassification.Budget.DEFAULT, caller -> List.copyOf(pages),
                () -> settings, released::contains, Clock.fixed(T0, ZoneOffset.UTC));

        run.job(WIKI, null, EDITOR);

        assertThat(seen).hasSizeGreaterThanOrEqualTo(2);
        assertThat(terms(seen.get(0))).hasSize(GlossaryModelPhase.BATCH).doesNotContain("Zuzzel", "Zuzzelziel");
        for (String request : seen.subList(1, seen.size())) {
            assertThat(request).as("nach dem Entzug nichts mehr von der Seite")
                    .doesNotContain("Zuzzel", "verschiebt");
        }
    }

    @Test
    void earlierAiProposalsStayAndTheSkipNamesThePages() {
        GlossaryEntry earlier = new GlossaryEntry("gai", "Haushaltsverfahren", "haushaltsverfahren", WIKI,
                RelationOrigin.AI, ReviewStatus.PROPOSED, List.of(new com.aresstack.spaceharvester.application
                .terms.RelationEvidence("wiki", "1", 0, 10, "sha256:" + "c".repeat(64))), false, null,
                "fake:model@1", "ki", T0, T0, null, null, 1);
        store.write(List.of(earlier), List.of(), List.of());

        classification.job(WIKI, null, EDITOR);
        released.clear();
        JobContext context = mock(JobContext.class);
        GlossaryClassification.Outcome skipped = classification.job(WIKI, context, EDITOR);

        assertThat(store.find("gai")).as("kein Abgleich entfernt frühere KI-Vorschläge").get()
                .satisfies(entry -> assertThat(entry.evidenceStale()).isFalse());
        assertThat(skipped.modelSkipped()).isEqualTo(GlossaryClassification.SkipReason.NOT_RELEASED);
        ArgumentCaptor<JobEvent.Draft> events = ArgumentCaptor.forClass(JobEvent.Draft.class);
        verify(context, atLeastOnce()).record(events.capture());
        assertThat(events.getAllValues()).filteredOn(event -> event.code().equals("GLOSSARY_MODEL_SKIPPED:NOT_RELEASED"))
                .singleElement().satisfies(event -> assertThat(event.message())
                        .contains("keine Seite des Bereichs für die KI freigegeben"));
    }

    @Test
    void withdrawalRevertsToTheRuleStateAndTheNextRunAsksWithoutThatPage() {
        classification.job(WIKI, null, EDITOR);
        assertThat(entry("Zuwendung").classification().by()).isEqualTo(ClassifiedBy.AI);
        PageKey second = new PageKey("wiki", "2");

        released.remove(second);
        int reverted = glossary.withdrawAi(List.of(second), released::contains);

        assertThat(reverted).isPositive();
        assertThat(entry("Zuwendung").classification()).isEqualTo(new GlossaryEntry.Classification(Category.UNKLAR,
                ClassifiedBy.RULE, null));
        assertThat(store.history(entry("Zuwendung").id())).extracting(GlossaryStore.AuditEntry::action)
                .contains("unclassify");
        requests.clear();

        classification.job(WIKI, null, EDITOR);

        assertThat(askedTerms()).contains("Zuwendung");
        for (String request : requests) {
            assertThat(request).doesNotContain("speichert", "Antragsdatenbank");
        }
        assertThat(entry("Zuwendung").classification().pages()).containsExactly(new PageKey("wiki", "1"));
    }

    @Test
    void withOnlyStaleAnalysesTheSkipSaysAnalysisMissingNotRelease() {
        // D4: Fehlt jede aktuelle Aussagenanalyse, heißt der Grund so, nie „nicht für die KI freigegeben“.
        pages.replaceAll(page -> new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(page.page().key(),
                page.page().spaceKey(), page.page().title(), PageStatements.State.STALE, null), page.analysis()));
        JobContext context = mock(JobContext.class);

        GlossaryClassification.Outcome outcome = classification.job(WIKI, context, EDITOR);

        assertThat(outcome.modelSkipped()).isEqualTo(GlossaryClassification.SkipReason.NO_ANALYSIS);
        assertThat(requests).isEmpty();
        ArgumentCaptor<JobEvent.Draft> events = ArgumentCaptor.forClass(JobEvent.Draft.class);
        verify(context, atLeastOnce()).record(events.capture());
        assertThat(events.getAllValues()).filteredOn(event -> event.code().equals(
                        "GLOSSARY_MODEL_SKIPPED:NO_ANALYSIS:" + pages.size()))
                .singleElement().satisfies(event -> assertThat(event.message())
                        .endsWith(pages.size() + " Seiten ohne aktuelle Aussagenanalyse"));
        assertThat(events.getAllValues()).filteredOn(event -> event.code().equals(GlossaryClassification.RULES_DONE))
                .singleElement().satisfies(event -> assertThat(event.message())
                        .contains(pages.size() + " Seiten mit veralteter Aussagenanalyse übersprungen"));
        assertThat(GlossaryClassification.missingAnalysis(0)).isEqualTo("Aussagenanalyse fehlt");
    }

    @Test
    void neverAnalysedAndStalePagesAreNamedEvenWhenThePhaseRunsForTheRest() {
        // Auflage AT zu D4: 3 lokale Seiten, 1 aktuell, 1 veraltet, 1 nie analysiert → Phase über 1, Hinweis nennt 2.
        pages.clear();
        page("1", "Bewilligungsstelle", "prüft", "Zuwendung");
        page("2", "Antragsdatenbank", "speichert", "Zuwendung");
        pages.set(1, new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(pages.get(1).page().key(), "FOE",
                "Seite 2", PageStatements.State.STALE, null), pages.get(1).analysis()));
        classification.localPages((who, scope) -> com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(
                List.of(new PageKey("wiki", "1"), new PageKey("wiki", "2"), new PageKey("wiki", "9"))));
        JobContext context = mock(JobContext.class);

        GlossaryClassification.Outcome outcome = classification.job(WIKI, context, EDITOR);

        assertThat(outcome.modelSkipped()).isNull();
        assertThat(requests).isNotEmpty().allSatisfy(request -> assertThat(request).doesNotContain("speichert"));
        ArgumentCaptor<JobEvent.Draft> events = ArgumentCaptor.forClass(JobEvent.Draft.class);
        verify(context, atLeastOnce()).record(events.capture());
        assertThat(events.getAllValues()).extracting(JobEvent.Draft::code).contains("GLOSSARY_ANALYSIS_MISSING:2");
    }

    @Test
    void inASubtreeOnlyItsPagesCountAsMissingAnalysis() {
        // D4.2, Auflage 3: Teilbaum {1, 9}; die veraltete Seite 2 liegt außerhalb und zählt nicht.
        pages.clear();
        page("1", "Bewilligungsstelle", "prüft", "Zuwendung");
        page("2", "Antragsdatenbank", "speichert", "Zuwendung");
        pages.set(1, new KnowledgeGraph.AnalysedPage(new KnowledgeGraph.PageRef(pages.get(1).page().key(), "FOE",
                "Seite 2", PageStatements.State.STALE, null), pages.get(1).analysis()));
        classification.localPages((who, scope) -> com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(
                List.of(new PageKey("wiki", "1"), new PageKey("wiki", "2"), new PageKey("wiki", "9"))));
        JobContext context = mock(JobContext.class);

        classification.job(WIKI, com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(
                List.of(new PageKey("wiki", "1"), new PageKey("wiki", "9"))), context, EDITOR);

        ArgumentCaptor<JobEvent.Draft> events = ArgumentCaptor.forClass(JobEvent.Draft.class);
        verify(context, atLeastOnce()).record(events.capture());
        assertThat(events.getAllValues()).extracting(JobEvent.Draft::code).contains("GLOSSARY_ANALYSIS_MISSING:1")
                .doesNotContain("GLOSSARY_ANALYSIS_MISSING:2");
    }

    @Test
    void aSubtreeRunReplacesTheBucketOfItsStartPageAndLeavesTheSpaceBucket() {
        // D4.2, Auflage zu #1044: ein Eimer je Startseite, nicht je Seitenmenge.
        classification.job(WIKI, null, EDITOR);
        classification.job(WIKI, com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(
                List.of(new PageKey("wiki", "1"))), "1", null, EDITOR);
        classification.job(WIKI, com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(
                List.of(new PageKey("wiki", "1"), new PageKey("wiki", "2"))), "1", null, EDITOR);

        assertThat(buckets).containsExactly("classify\u0000wiki\u0000", "classify\u0000wiki\u0000\u0000subtree\u00001",
                "classify\u0000wiki\u0000\u0000subtree\u00001");
    }

    @Test
    void withoutAnyReleasedPageTheModelPhaseIsSkippedAndThePagesSurviveTheStore() {
        classification.job(WIKI, null, EDITOR);
        List<PageKey> kept = entry("Zuwendung").classification().pages();
        assertThat(new FileGlossaryStore(directory).find(entry("Zuwendung").id()).orElseThrow().classification()
                .pages()).isEqualTo(kept).hasSize(2);

        released.clear();
        requests.clear();
        GlossaryClassification.Outcome outcome = classification.job(WIKI, null, EDITOR);

        assertThat(outcome.modelSkipped()).isEqualTo(GlossaryClassification.SkipReason.NOT_RELEASED);
        assertThat(requests).isEmpty();
        available = false;
        assertThat(classification.job(WIKI, null, EDITOR).modelSkipped())
                .isEqualTo(GlossaryClassification.SkipReason.NO_MODEL);
    }

    @Test
    void countsAreSentCoarselyBanded() {
        assertThat(List.of(0, 1, 2, 3, 4, 9, 10, 49, 50, 500).stream().map(GlossaryModelPhase::band).toList())
                .containsExactly("0", "1", "2-3", "2-3", "4-9", "4-9", "10-49", "10-49", "50+", "50+");
    }

    @Test
    void onlyProposedEntriesWithoutDecisionAreAsked() {
        assertThat(GlossaryModelPhase.asks(null)).isTrue();
        assertThat(GlossaryModelPhase.asks(new GlossaryEntry.Classification(Category.UNKLAR, ClassifiedBy.RULE,
                null))).isTrue();
        assertThat(GlossaryModelPhase.asks(new GlossaryEntry.Classification(Category.UNKLAR, ClassifiedBy.AI,
                "CONTEXT_UNCLEAR"))).isTrue();
        assertThat(GlossaryModelPhase.asks(new GlossaryEntry.Classification(Category.UNKLAR, ClassifiedBy.MANUAL,
                null))).isFalse();
        assertThat(GlossaryModelPhase.asks(new GlossaryEntry.Classification(Category.ROLLE, ClassifiedBy.RULE,
                "ROLE_HEAD"))).isFalse();
    }
}
