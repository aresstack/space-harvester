package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Begriffswissen für Chat und Suche (Paket W6); Begriffe, Seiten und Texte erfunden (Quelle „wiki“, Space „DEV“). */
class TermKnowledgeTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    private final StatementAnalysis statements = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store, access, () -> parser,
            StatementAnalysisTest.clock());
    private final KnowledgeGraph graph = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
            KnowledgeGraph.TermExpansion.NONE);
    private final List<DomainTermGraph.Term> terms = new ArrayList<>();
    private final List<DomainTermGraph.Synonym> synonyms = new ArrayList<>();
    private final List<PageKey> delivered = new ArrayList<>();
    private final List<String> deliveredSpaces = new ArrayList<>();
    private final TermKnowledge.Delivery recording = (spaceKey, key) -> {
        deliveredSpaces.add(spaceKey);
        delivered.add(key);
    };

    @Test
    void aTermAnswersWithItsSynonymsStatementsNeighboursAndDeliversExactlyTheEvidencePages() {
        term("Plan");
        term("Ziel");
        term("Start");
        synonyms.add(new DomainTermGraph.Synonym("Plan", "Vorhaben", new RelationScope(null, null)));
        analyse("1", "Der Plan prüft das Ziel.");
        analyse("2", "Das Vorhaben sendet den Start.");
        analyse("3", "Der Start genehmigt das Ziel.");

        TermKnowledge.Answer answer = knowledge().answer(CALLER, "Plan", pages("1", "2", "3"), recording,
                () -> false);

        assertThat(answer.outcome()).isEqualTo(TermKnowledge.Outcome.FOUND);
        assertThat(answer.terms()).extracting(TermKnowledge.Term::term).containsExactly("Plan", "Vorhaben");
        assertThat(answer.terms()).extracting(TermKnowledge.Term::kind)
                .containsExactly(DomainTermGraph.NodeKind.TERM, DomainTermGraph.NodeKind.SYNONYM);
        assertThat(answer.terms().get(0).statements()).singleElement().satisfies(statement -> {
            assertThat(statement.subject()).isEqualTo("Plan");
            assertThat(statement.verb()).isEqualTo("prüft");
            assertThat(statement.object()).isEqualTo("Ziel");
            assertThat(statement.trust()).isEqualTo(DomainTermGraph.Trust.UNGEPRUEFT);
            assertThat(statement.evidence()).isEqualTo(1);
            assertThat(statement.pages()).singleElement().isEqualTo(
                    new TermKnowledge.Page(new PageKey("wiki", "1"), "DEV", "Seite 1"));
            assertThat(statement.omittedPages()).isZero();
        });
        assertThat(answer.terms().get(1).statements()).extracting(TermKnowledge.Statement::verb)
                .containsExactly("sendet");
        assertThat(answer.neighbours()).as("„Start genehmigt Ziel“ berührt die Gruppe nicht, die Enden schon")
                .containsExactlyInAnyOrder("Ziel", "Start");
        assertThat(answer.pages()).extracting(TermKnowledge.Page::key)
                .as("Seite 3 trägt keine Aussage der Antwort").containsExactly(key("1"), key("2"));
        assertThat(delivered).as("genau die gelieferten Seiten, vor der Antwort").containsExactly(key("1"), key("2"));
        assertThat(deliveredSpaces).containsOnly("DEV");
        assertThat(answer.limits().cut()).isFalse();
    }

    @Test
    void onlyThePagesOfTheScopeAreReadAndNothingOutsideIsDelivered() {
        term("Plan");
        term("Ziel");
        analyse("1", "Der Plan prüft das Ziel.");
        analyse("9", "Der Plan sendet das Ziel.");
        List<PageSet> asked = new ArrayList<>();
        DomainTermGraph fachsicht = new DomainTermGraph(caller -> {
            throw new AssertionError("nie die ganze Ablage lesen");
        }, (caller, pages) -> {
            asked.add(pages);
            return graph.analysed(caller, pages);
        }, vocabulary(), () -> null, DomainTermGraph.Comparison.NONE);

        TermKnowledge.Answer answer = new TermKnowledge(fachsicht).answer(CALLER, "Plan", pages("1"), recording,
                () -> false);

        assertThat(asked).singleElement().isEqualTo(pages("1"));
        assertThat(answer.terms().getFirst().statements()).extracting(TermKnowledge.Statement::verb)
                .containsExactly("prüft");
        assertThat(delivered).containsExactly(key("1"));
    }

    @Test
    void rejectedStatementsNeverCountConfirmedComeFirstAndDeviatingLast() {
        term("Plan");
        term("Ziel");
        term("Start");
        term("Nachtlauf");
        analyse("1", "Der Plan sendet den Start. Der Plan sendet den Start.");
        analyse("2", "Der Plan prüft das Ziel.");
        analyse("3", "Der Plan genehmigt den Nachtlauf.");
        analyse("4", "Der Plan übermittelt das Ziel.");
        Map<String, ParsedStatements.Review> reviews = Map.of("2", ParsedStatements.Review.CONFIRMED,
                "4", ParsedStatements.Review.REJECTED);
        DomainTermGraph fachsicht = new DomainTermGraph(caller -> rewrite(caller, page -> statement -> statement
                .withReview(reviews.getOrDefault(page, statement.review()))), vocabulary(), () -> null,
                (caller, page) -> {
                    Map<String, AnalyzerComparison.Comparison> differs = new java.util.LinkedHashMap<>();
                    if (page.key().pageId().equals("3")) {
                        graph.analysed(caller).stream().filter(p -> p.page().key().equals(page.key()))
                                .flatMap(p -> p.analysis().result().statements().stream())
                                .forEach(s -> differs.put(s.id(), differs(s.id())));
                    }
                    return differs;
                });

        TermKnowledge.Answer answer = new TermKnowledge(fachsicht).answer(CALLER, "Plan",
                pages("1", "2", "3", "4"), recording, () -> false);

        assertThat(answer.terms().getFirst().statements())
                .extracting(TermKnowledge.Statement::verb, TermKnowledge.Statement::trust)
                .as("geprüft vor ungeprüft (trotz weniger Belegen), abweichend zuletzt, verworfen nie")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("prüft", DomainTermGraph.Trust.GEPRUEFT),
                        org.assertj.core.groups.Tuple.tuple("sendet", DomainTermGraph.Trust.UNGEPRUEFT),
                        org.assertj.core.groups.Tuple.tuple("genehmigt", DomainTermGraph.Trust.ABWEICHEND));
        assertThat(delivered).as("die verworfene Aussage liefert ihre Seite nicht").doesNotContain(key("4"));
    }

    @Test
    void statementsNeighboursAndEvidencePagesAreCappedAndTheCutIsReported() {
        term("Plan");
        List<String> objects = IntStream.range(0, TermKnowledge.MAX_STATEMENTS + 2)
                .mapToObj(i -> "Ziel" + (char) ('a' + i / 26) + (char) ('a' + i % 26)).toList();
        objects.forEach(this::term);
        StringBuilder text = new StringBuilder();
        objects.forEach(object -> text.append("Der Plan prüft das ").append(object).append(". "));
        analyse("1", text.toString());
        term("Start");
        for (int page = 2; page <= 2 + TermKnowledge.MAX_PAGES_PER_STATEMENT; page++) {
            analyse(String.valueOf(page), "Der Plan sendet den Start. Der Plan sendet den Start.");
        }

        TermKnowledge.Answer answer = knowledge().answer(CALLER, "Plan", pages("1", "2", "3", "4", "5"), recording,
                () -> false);

        List<TermKnowledge.Statement> shown = answer.terms().getFirst().statements();
        assertThat(shown).hasSize(TermKnowledge.MAX_STATEMENTS);
        assertThat(answer.limits().omittedStatements()).isEqualTo(3);
        assertThat(shown.getFirst().verb()).as("die Aussage mit den meisten Belegen zuerst").isEqualTo("sendet");
        assertThat(shown.getFirst().evidence()).isEqualTo(8);
        assertThat(shown.getFirst().pages()).hasSize(TermKnowledge.MAX_PAGES_PER_STATEMENT);
        assertThat(shown.getFirst().omittedPages()).isEqualTo(1);
        assertThat(answer.limits().omittedEvidencePages()).isEqualTo(1);
        assertThat(answer.neighbours()).hasSize(TermKnowledge.MAX_NEIGHBOURS).startsWith("Start");
        assertThat(answer.limits().omittedNeighbours()).as("Nachbarn nur aus den 20 gelieferten Aussagen")
                .isEqualTo(TermKnowledge.MAX_STATEMENTS - TermKnowledge.MAX_NEIGHBOURS);
        assertThat(answer.limits().cut()).isTrue();
        assertThat(delivered).as("nur genannte Belegseiten").containsExactly(key("2"), key("3"), key("4"), key("1"));
    }

    @Test
    void neighboursComeOnlyFromDeliveredStatements() {
        term("Plan");
        List<String> objects = IntStream.range(0, TermKnowledge.MAX_STATEMENTS + 1)
                .mapToObj(i -> "Ziel" + (char) ('a' + i / 26) + (char) ('a' + i % 26)).toList();
        objects.forEach(this::term);
        StringBuilder strong = new StringBuilder();
        objects.subList(0, TermKnowledge.MAX_STATEMENTS)
                .forEach(object -> strong.append("Der Plan prüft das ").append(object).append(". "));
        analyse("1", strong.toString());
        analyse("2", strong.toString());
        String weak = objects.getLast();
        analyse("3", "Der Plan sendet das " + weak + ".");

        TermKnowledge.Answer answer = knowledge().answer(CALLER, "Plan", pages("1", "2", "3"), recording,
                () -> false);

        assertThat(answer.terms().getFirst().statements()).hasSize(TermKnowledge.MAX_STATEMENTS)
                .noneMatch(statement -> statement.object().equals(weak));
        assertThat(answer.limits().omittedStatements()).isEqualTo(1);
        assertThat(answer.neighbours()).doesNotContain(weak);
        assertThat(answer.neighbours().size() + answer.limits().omittedNeighbours())
                .as("die 21. Aussage trägt keinen Nachbarn bei").isEqualTo(TermKnowledge.MAX_STATEMENTS);
        assertThat(delivered).doesNotContain(key("3"));
    }

    @Test
    void theGraphCountsAsCutOnlyWhenEdgesOfTheTermAreMissing() {
        term("Plan");
        term("Ziel");
        List<String> far = IntStream.range(0, DomainTermGraph.MAX_NODES + 5)
                .mapToObj(i -> "Fern" + (char) ('a' + i / 26) + (char) ('a' + i % 26)).toList();
        far.forEach(this::term);
        StringBuilder text = new StringBuilder("Der Plan prüft das Ziel. ");
        far.forEach(object -> text.append("Das Ziel sendet das ").append(object).append(". "));
        analyse("1", text.toString());

        TermKnowledge.Answer complete = knowledge().answer(CALLER, "Plan", pages("1"), recording, () -> false);
        assertThat(complete.terms().getFirst().statements()).singleElement()
                .satisfies(statement -> assertThat(statement.object()).isEqualTo("Ziel"));
        assertThat(complete.limits().graphTruncated()).as("Nachbarn des Nachbarn fehlen, die des Begriffs nicht")
                .isFalse();
        assertThat(complete.limits().cut()).isFalse();

        TermKnowledge.Answer crowded = knowledge().answer(CALLER, "Ziel", pages("1"), recording, () -> false);
        assertThat(crowded.limits().graphTruncated()).as("dem Begriff selbst fehlen Kanten").isTrue();
    }

    @Test
    void aSourceWhoseDataIsBeingWithdrawnGivesNothingAndIsNotDelivered() {
        term("Plan");
        term("Ziel");
        java.util.Set<String> withdrawing = new java.util.HashSet<>();
        KnowledgeGraph guarded = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
                KnowledgeGraph.TermExpansion.NONE, sourceId -> !withdrawing.contains(sourceId));
        analyse("1", "Der Plan prüft das Ziel.");
        TermKnowledge knowledge = new TermKnowledge(new DomainTermGraph(guarded, vocabulary()));
        assertThat(knowledge.answer(CALLER, "Plan", pages("1"), recording, () -> false).pages())
                .as("Gegenprobe").hasSize(1);
        delivered.clear();

        withdrawing.add("wiki");
        TermKnowledge.Answer answer = knowledge.answer(CALLER, "Plan", pages("1"), recording, () -> false);

        assertThat(answer.terms().getFirst().statements()).isEmpty();
        assertThat(delivered).isEmpty();
    }

    @Test
    void cuttingOnlyEvidencePagesStillCountsAsCut() {
        term("Plan");
        term("Start");
        for (int page = 1; page <= TermKnowledge.MAX_PAGES_PER_STATEMENT + 1; page++) {
            analyse(String.valueOf(page), "Der Plan sendet den Start.");
        }

        TermKnowledge.Answer answer = knowledge().answer(CALLER, "Plan", pages("1", "2", "3", "4"), recording,
                () -> false);

        assertThat(answer.terms().getFirst().statements()).singleElement()
                .satisfies(statement -> assertThat(statement.omittedPages()).isEqualTo(1));
        assertThat(answer.limits().omittedStatements()).isZero();
        assertThat(answer.limits().omittedNeighbours()).isZero();
        assertThat(answer.limits().omittedEvidencePages()).isEqualTo(1);
        assertThat(answer.limits().cut()).isTrue();
        assertThat(delivered).containsExactly(key("1"), key("2"), key("3"));
    }

    @Test
    void negationModalityAndTimeSeparateStatementsWithTheirOwnEvidenceAndStage() {
        term("Plan");
        term("Ziel");
        analyse("1", "Der Plan prüft das Ziel.");
        analyse("2", "Der Plan prüft das Ziel.");
        analyse("3", "Der Plan prüft das Ziel.");
        Map<String, ParsedStatements.Features> features = Map.of(
                "1", new ParsedStatements.Features(ParsedStatements.Negation.AFFIRMED,
                        ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT),
                "2", new ParsedStatements.Features(ParsedStatements.Negation.NEGATED,
                        ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT),
                "3", new ParsedStatements.Features(ParsedStatements.Negation.AFFIRMED,
                        ParsedStatements.Modality.KANN, ParsedStatements.Temporality.GEPLANT));
        DomainTermGraph fachsicht = new DomainTermGraph(caller -> rewrite(caller, page -> statement -> {
            ParsedStatements.Statement changed = withFeatures(statement, features.get(page));
            return page.equals("2") ? changed.withReview(ParsedStatements.Review.CONFIRMED) : changed;
        }), vocabulary());

        TermKnowledge.Answer answer = new TermKnowledge(fachsicht).answer(CALLER, "Plan", pages("1", "2", "3"),
                recording, () -> false);

        assertThat(answer.terms().getFirst().statements())
                .extracting(TermKnowledge.Statement::negation, TermKnowledge.Statement::modality,
                        TermKnowledge.Statement::temporality, TermKnowledge.Statement::trust,
                        TermKnowledge.Statement::evidence)
                .as("„prüft nicht“ ist eine eigene Aussage, nie ein Beleg für „prüft“; die Stufe gilt je Teil")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ParsedStatements.Negation.NEGATED,
                                ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT,
                                DomainTermGraph.Trust.GEPRUEFT, 1),
                        org.assertj.core.groups.Tuple.tuple(ParsedStatements.Negation.AFFIRMED,
                                ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT,
                                DomainTermGraph.Trust.UNGEPRUEFT, 1),
                        org.assertj.core.groups.Tuple.tuple(ParsedStatements.Negation.AFFIRMED,
                                ParsedStatements.Modality.KANN, ParsedStatements.Temporality.GEPLANT,
                                DomainTermGraph.Trust.UNGEPRUEFT, 1));
        assertThat(answer.terms().getFirst().statements()).extracting(statement -> statement.pages().getFirst().key())
                .containsExactly(key("2"), key("1"), key("3"));
    }

    @Test
    void undeterminedFeaturesStayUnknownUnlessSomeEvidenceDeterminesThem() {
        term("Plan");
        term("Ziel");
        term("Start");
        analyse("1", "Der Plan prüft das Ziel.");
        analyse("2", "Der Plan prüft das Ziel.");
        analyse("3", "Der Plan prüft den Start.");
        analyse("4", "Der Plan prüft den Start.");
        Map<String, ParsedStatements.Features> features = Map.of(
                "1", new ParsedStatements.Features(ParsedStatements.Negation.AFFIRMED,
                        ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT),
                "2", ParsedStatements.Features.UNKNOWN,
                "3", ParsedStatements.Features.UNKNOWN,
                "4", ParsedStatements.Features.UNKNOWN);
        DomainTermGraph fachsicht = new DomainTermGraph(caller -> rewrite(caller, page -> statement ->
                withFeatures(statement, features.get(page))), vocabulary());

        TermKnowledge.Answer answer = new TermKnowledge(fachsicht).answer(CALLER, "Plan",
                pages("1", "2", "3", "4"), recording, () -> false);

        assertThat(answer.terms().getFirst().statements())
                .extracting(TermKnowledge.Statement::object, TermKnowledge.Statement::negation,
                        TermKnowledge.Statement::modality, TermKnowledge.Statement::temporality,
                        TermKnowledge.Statement::evidence)
                .as("gemischt: der bestimmte Wert gilt; nur unbestimmt: der Chat erfährt „nicht bestimmt“")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Ziel", ParsedStatements.Negation.AFFIRMED,
                                ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT, 2),
                        org.assertj.core.groups.Tuple.tuple("Start", ParsedStatements.Negation.UNKNOWN,
                                ParsedStatements.Modality.UNKNOWN, ParsedStatements.Temporality.UNKNOWN, 2));
    }

    @Test
    void aLargeScopeIsCappedInAFixedOrderAndTheCutIsReported() {
        List<PageKey> keys = new ArrayList<>();
        IntStream.range(0, TermKnowledge.MAX_PAGES + 7).forEach(i -> keys.add(new PageKey(i % 2 == 0 ? "wiki"
                : "akte", String.format("%04d", i))));
        PageSet scope = PageSet.ofKeys(keys);

        PageSet capped = TermKnowledge.capped(scope);

        assertThat(capped.size()).isEqualTo(TermKnowledge.MAX_PAGES);
        assertThat(capped.pagesOf("akte")).as("Quellen in fester Ordnung: „akte“ vor „wiki“")
                .hasSize((TermKnowledge.MAX_PAGES + 7) / 2);
        assertThat(TermKnowledge.capped(scope)).isEqualTo(capped);
        assertThat(TermKnowledge.capped(pages("1"))).isEqualTo(pages("1"));

        term("Plan");
        TermKnowledge.Answer answer = knowledge().answer(CALLER, "Plan", scope, recording, () -> false);
        assertThat(answer.limits().pagesInScope()).isEqualTo(TermKnowledge.MAX_PAGES + 7);
        assertThat(answer.limits().pagesRead()).isEqualTo(TermKnowledge.MAX_PAGES);
        assertThat(answer.limits().cut()).isTrue();
    }

    @Test
    void staleStatementsAreLeftOutAndCounted() {
        term("Plan");
        term("Ziel");
        analyse("1", "Der Plan prüft das Ziel.");
        cache.put(key("1"), EnrichmentTestPages.text("wiki", "1", "2", "Ganz neuer Text."));

        TermKnowledge.Answer answer = knowledge().answer(CALLER, "Plan", pages("1"), recording, () -> false);

        assertThat(answer.terms().getFirst().statements()).isEmpty();
        assertThat(answer.limits().skippedStale()).isEqualTo(1);
        assertThat(delivered).isEmpty();
    }

    @Test
    void emptyInputsUnknownTermsAndAFailingDeliveryGiveNoStatements() {
        term("Plan");
        term("Ziel");
        analyse("1", "Der Plan prüft das Ziel.");
        TermKnowledge knowledge = knowledge();

        assertThat(knowledge.answer(CALLER, "  ", pages("1"), recording, () -> false).outcome())
                .isEqualTo(TermKnowledge.Outcome.EMPTY_QUERY);
        assertThat(knowledge.answer(CALLER, "Plan", PageSet.EMPTY, recording, () -> false).outcome())
                .isEqualTo(TermKnowledge.Outcome.EMPTY_SCOPE);
        TermKnowledge.Answer unknown = knowledge.answer(CALLER, "Pla", pages("1"), recording, () -> false);
        assertThat(unknown.outcome()).isEqualTo(TermKnowledge.Outcome.NOT_A_TERM);
        assertThat(unknown.candidates()).containsExactly("Plan");
        assertThat(unknown.terms()).isEmpty();
        assertThat(delivered).isEmpty();

        assertThatThrownBy(() -> knowledge.answer(CALLER, "Plan", pages("1"), (spaceKey, key) -> {
            throw new IllegalStateException("Liste nicht schreibbar");
        }, () -> false)).as("ohne Meldung keine Antwort").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> knowledge.answer(CALLER, "Plan", pages("1"), recording, () -> true))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(knowledge.answer(CALLER, "Plan", pages("1"), TermKnowledge.Delivery.NONE, () -> false)
                .pages()).as("die Suche meldet ausdrücklich nichts").extracting(TermKnowledge.Page::key)
                .containsExactly(key("1"));
    }

    private TermKnowledge knowledge() {
        return new TermKnowledge(new DomainTermGraph(graph, vocabulary()));
    }

    /** Die gespeicherten Analysen mit umgeschriebenen Aussagen je Seite. */
    private List<KnowledgeGraph.AnalysedPage> rewrite(AccessContext caller,
                                                      Function<String, UnaryOperator<ParsedStatements.Statement>> how) {
        return graph.analysed(caller).stream().map(page -> {
            ParsedStatements result = page.analysis().result();
            UnaryOperator<ParsedStatements.Statement> change = how.apply(page.page().key().pageId());
            return new KnowledgeGraph.AnalysedPage(page.page(), page.analysis().withResult(new ParsedStatements(
                    result.sentences(), result.mentions(), result.statements().stream().map(change).toList(),
                    result.attachments(), result.components(), result.conllu())));
        }).toList();
    }

    private static ParsedStatements.Statement withFeatures(ParsedStatements.Statement statement,
                                                           ParsedStatements.Features features) {
        return new ParsedStatements.Statement(statement.id(), statement.sentence(), statement.start(),
                statement.end(), statement.predicateText(), statement.predicateLemma(), statement.voice(),
                statement.clause(), statement.core(), statement.arguments(), statement.findings(),
                statement.paragraph(), statement.quality(), statement.qualityReasons(), statement.review(), features,
                statement.derivation(), statement.check());
    }

    private static AnalyzerComparison.Comparison differs(String statementId) {
        AnalyzerComparison.Reading own = new AnalyzerComparison.Reading(AnalyzerChoice.PARSER, statementId,
                "genehmigen", java.util.Set.of("plan"), java.util.Set.of("nachtlauf"));
        AnalyzerComparison.Reading other = new AnalyzerComparison.Reading(AnalyzerChoice.MODEL, statementId,
                "senden", java.util.Set.of("plan"), java.util.Set.of("nachtlauf"));
        return new AnalyzerComparison.Comparison(statementId, AnalyzerComparison.Agreement.DIFFERS, own, other,
                java.util.EnumSet.of(AnalyzerComparison.Difference.VERB));
    }

    private DomainTermGraph.Vocabulary vocabulary() {
        return new DomainTermGraph.Vocabulary() {
            @Override
            public List<DomainTermGraph.Term> terms(AccessContext caller) {
                return terms;
            }

            @Override
            public List<DomainTermGraph.Synonym> synonyms(AccessContext caller) {
                return synonyms;
            }
        };
    }

    private void term(String term) {
        terms.add(new DomainTermGraph.Term(term, new RelationScope(null, null)));
    }

    private void analyse(String pageId, String text) {
        PageSnapshot snapshot = EnrichmentTestPages.text("wiki", pageId, "1", text);
        cache.put(snapshot.key(), snapshot);
        statements.analyze(CALLER, snapshot, () -> false);
    }

    private static PageKey key(String pageId) {
        return new PageKey("wiki", pageId);
    }

    private static PageSet pages(String... pageIds) {
        return PageSet.ofKeys(Arrays.stream(pageIds).map(TermKnowledgeTest::key).toList());
    }
}
