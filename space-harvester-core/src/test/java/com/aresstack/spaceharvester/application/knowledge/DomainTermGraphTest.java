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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Fachsicht des Wissensgraphen (Paket W1); Begriffe und Texte erfunden. */
class DomainTermGraphTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final Set<String> deniedSources = new HashSet<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) ->
            deniedSources.contains(scope.sourceId()) ? AccessDecision.DENY : AccessDecision.ALLOW);
    private final StatementAnalysis statements = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store, access, () -> parser,
            StatementAnalysisTest.clock());
    private final KnowledgeGraph graph = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
            KnowledgeGraph.TermExpansion.NONE);
    private final List<DomainTermGraph.Term> terms = new ArrayList<>();
    private final List<DomainTermGraph.Synonym> synonyms = new ArrayList<>();
    private final List<DomainTermGraph.Term> technical = new ArrayList<>();
    private final DomainTermGraph fachsicht = new DomainTermGraph(graph, new DomainTermGraph.Vocabulary() {
        @Override
        public List<DomainTermGraph.Term> terms(AccessContext caller) {
            return terms;
        }

        @Override
        public List<DomainTermGraph.Synonym> synonyms(AccessContext caller) {
            return synonyms;
        }

        @Override
        public List<DomainTermGraph.Term> technical(AccessContext caller) {
            return technical;
        }
    });

    @Test
    void verbsBetweenConfirmedTermsBecomeEdgesWithEvidenceAndUnknownMentionsAddNothing() {
        term("Sachbearbeiter");
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg. Die Buchhaltung sendet den Beleg.");
        analyse("wiki", "2", "Der Sachbearbeiter prüft den Beleg.");

        DomainTermGraph.View view = around("Sachbearbeiter");

        assertThat(view.outcome()).isEqualTo(DomainTermGraph.Outcome.FOUND);
        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label)
                .as("„Buchhaltung“ ist kein Fachbegriff: weder Knoten noch Kante")
                .containsExactly("Sachbearbeiter", "Beleg");
        DomainTermGraph.Edge edge = view.edges().stream().filter(e -> e.kind() == DomainTermGraph.EdgeKind.VERB)
                .findFirst().orElseThrow();
        assertThat(edge.from()).isEqualTo("sachbearbeiter");
        assertThat(edge.to()).isEqualTo("beleg");
        assertThat(edge.verb()).isEqualTo("prüft");
        assertThat(edge.count()).isEqualTo(2);
        assertThat(edge.evidence()).extracting(e -> e.page().key().pageId()).containsExactly("1", "2");
        assertThat(edge.evidence()).allSatisfy(e -> {
            assertThat(e.fromText()).as("Beleg trägt den Wortlaut, keine Identität").isEqualTo("Sachbearbeiter");
            assertThat(e.toText()).isEqualTo("Beleg");
            assertThat(e.analyzerId()).isEqualTo("fake");
        });
        assertThat(view.analyzers()).containsExactly("fake");
        assertThat(view.edges()).hasSize(1);
    }

    @Test
    void synonymsAreOwnNodesJoinedByAnUndirectedSynonymEdgeAndCarryTheirOwnVerbEdges() {
        term("Antrag");
        term("Sachbearbeiter");
        synonyms.add(new DomainTermGraph.Synonym("Antrag", "Gesuch", new RelationScope(null, null)));
        analyse("wiki", "1", "Der Sachbearbeiter genehmigt das Gesuch.");

        DomainTermGraph.View view = around("Antrag");

        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label, DomainTermGraph.Node::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Antrag", DomainTermGraph.NodeKind.TERM),
                        org.assertj.core.groups.Tuple.tuple("Gesuch", DomainTermGraph.NodeKind.SYNONYM),
                        org.assertj.core.groups.Tuple.tuple("Sachbearbeiter", DomainTermGraph.NodeKind.TERM));
        assertThat(view.edges()).anySatisfy(edge -> {
            assertThat(edge.kind()).isEqualTo(DomainTermGraph.EdgeKind.SYNONYM);
            assertThat(edge.from()).isEqualTo("antrag");
            assertThat(edge.to()).isEqualTo("gesuch");
        });
        assertThat(view.edges()).anySatisfy(edge -> {
            assertThat(edge.kind()).isEqualTo(DomainTermGraph.EdgeKind.VERB);
            assertThat(edge.from()).isEqualTo("sachbearbeiter");
            assertThat(edge.to()).as("nicht mit dem Fachbegriff verschmolzen").isEqualTo("gesuch");
        });
    }

    @Test
    void aSynonymCountsOnlyWhereItsScopeOverlapsTheDomainTerm() {
        terms.add(new DomainTermGraph.Term("Bank", new RelationScope("finanzen", null)));
        term("Kunde");
        synonyms.add(new DomainTermGraph.Synonym("Bank", "Sitzbank", new RelationScope("garten", null)));
        synonyms.add(new DomainTermGraph.Synonym("Bank", "Geldinstitut", new RelationScope("finanzen", null)));
        analyse("garten", "1", "Der Kunde streicht die Sitzbank.");
        analyse("finanzen", "2", "Der Kunde wählt das Geldinstitut.");

        DomainTermGraph.View view = around("Bank");

        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label)
                .as("„Sitzbank“ gilt nur im Garten, „Bank“ nur in den Finanzen").containsExactly("Bank", "Geldinstitut");
        assertThat(view.edges()).filteredOn(edge -> edge.kind() == DomainTermGraph.EdgeKind.SYNONYM)
                .extracting(DomainTermGraph.Edge::to).containsExactly("geldinstitut");
        assertThat(DomainTermGraph.Lexicon.intersection(new RelationScope("a", "X"), new RelationScope("a", null)))
                .contains(new RelationScope("a", "X"));
        assertThat(DomainTermGraph.Lexicon.intersection(new RelationScope("a", "X"), new RelationScope("a", "Y")))
                .isEmpty();
        assertThat(DomainTermGraph.Lexicon.intersection(RelationScope.GLOBAL, new RelationScope("b", null)))
                .contains(new RelationScope("b", null));
    }

    @Test
    void aSynonymBetweenTwoDomainTermsMustHoldAtBothEndsWhateverItsDirection() {
        terms.add(new DomainTermGraph.Term("Konto", new RelationScope("finanzen", null)));
        terms.add(new DomainTermGraph.Term("Rechnung", new RelationScope("garten", null)));
        synonyms.add(new DomainTermGraph.Synonym("Konto", "Rechnung", new RelationScope("finanzen", null)));
        assertThat(around("Konto").edges()).as("„Rechnung“ gilt nur im Garten").isEmpty();

        synonyms.clear();
        synonyms.add(new DomainTermGraph.Synonym("Rechnung", "Konto", new RelationScope("finanzen", null)));
        assertThat(around("Konto").edges()).as("umgekehrt gespeichert dasselbe").isEmpty();

        terms.add(new DomainTermGraph.Term("Rechnung", new RelationScope("finanzen", null)));
        assertThat(around("Konto").edges()).as("gilt das Synonym an beiden Enden, verbindet es")
                .singleElement().satisfies(edge -> assertThat(edge.kind()).isEqualTo(DomainTermGraph.EdgeKind.SYNONYM));
        synonyms.clear();
        synonyms.add(new DomainTermGraph.Synonym("Konto", "Rechnung", new RelationScope("finanzen", null)));
        assertThat(around("Konto").edges()).as("in beiden Richtungen").hasSize(1);
    }

    @Test
    void staleStatementsCountOnlyIfTheyWouldFitTheFullViewInItsOwnOrder() {
        term("Kern");
        for (int i = 0; i < DomainTermGraph.MAX_NODES - 1; i++) {
            synonyms.add(new DomainTermGraph.Synonym("Kern", "Kernname" + (char) ('a' + i / 26) + (char) ('a' + i % 26),
                    RelationScope.GLOBAL));
        }
        StringBuilder text = new StringBuilder();
        for (char suffix = 'a'; suffix <= 'e'; suffix++) {
            term("Ziel" + suffix);
            text.append("Der Kern prüft die Ziel").append(suffix).append(". ");
        }
        analyse("wiki", "1", text.toString().strip());
        cache.put(new PageKey("wiki", "1"), EnrichmentTestPages.text("wiki", "1", "2", "Ganz neuer Text."));

        DomainTermGraph.View withStale = fachsicht.around(CALLER, "Kern", new DomainTermGraph.Options(2, true, null),
                () -> false);
        assertThat(withStale.nodes()).hasSize(DomainTermGraph.MAX_NODES);
        assertThat(withStale.nodes()).extracting(DomainTermGraph.Node::label).noneMatch(label -> label.startsWith("Ziel"));
        assertThat(around("Kern").skippedStale())
                .as("auch mit „veraltete zeigen“ wären die Ziele abgeschnitten, also nicht ausgelassen").isZero();
    }

    @Test
    void lockedPagesGiveNoEdgesAndATermWithoutVisibleEvidenceStandsAlone() {
        term("Revision");
        term("Beleg");
        analyse("geheim", "9", "Die Revision prüft den Beleg.");
        deniedSources.add("geheim");

        DomainTermGraph.View view = around("Revision");

        assertThat(view.outcome()).isEqualTo(DomainTermGraph.Outcome.FOUND);
        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label).containsExactly("Revision");
        assertThat(view.edges()).isEmpty();
        assertThat(view.skippedStale()).isZero();
    }

    @Test
    void termScopeMustCoverThePage() {
        terms.add(new DomainTermGraph.Term("Sachbearbeiter", new RelationScope("handbuch", null)));
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");

        assertThat(around("Beleg").edges()).isEmpty();
    }

    @Test
    void staleStatementsAreSkippedAndCountedUnlessWanted() {
        term("Sachbearbeiter");
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        cache.put(new PageKey("wiki", "1"), EnrichmentTestPages.text("wiki", "1", "2", "Ganz neuer Text."));

        DomainTermGraph.View current = around("Sachbearbeiter");
        assertThat(current.edges()).isEmpty();
        assertThat(current.skippedStale()).isEqualTo(1);

        DomainTermGraph.View withStale = fachsicht.around(CALLER, "Sachbearbeiter",
                new DomainTermGraph.Options(2, true, null), () -> false);
        assertThat(withStale.edges()).singleElement().satisfies(edge ->
                assertThat(edge.evidence().getFirst().page().state()).isEqualTo(PageStatements.State.STALE));
    }

    @Test
    void theSelectionLimitsThePagesAndTheStartMustBeAConfirmedTerm() {
        term("Sachbearbeiter");
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");

        assertThat(fachsicht.around(CALLER, "Sachbearbeiter", new DomainTermGraph.Options(1, false,
                PageSet.ofKeys(List.of(new PageKey("wiki", "2")))), () -> false).edges()).isEmpty();
        assertThat(around("Buchhaltung").outcome()).isEqualTo(DomainTermGraph.Outcome.NOT_A_TERM);
        assertThat(around("  ").outcome()).isEqualTo(DomainTermGraph.Outcome.EMPTY_START);
    }

    @Test
    void uncertainStatementsDoNotCount() {
        term("Beleg");
        term("Sachbearbeiter");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        assertThat(around("Beleg").edges()).as("Gegenprobe: plausibel zählt").hasSize(1);

        DomainTermGraph uncertain = new DomainTermGraph(caller -> graph.analysed(caller).stream().map(page -> {
            ParsedStatements result = page.analysis().result();
            List<ParsedStatements.Statement> doubtful = result.statements().stream().map(statement ->
                    new ParsedStatements.Statement(statement.id(), statement.sentence(), statement.start(),
                            statement.end(), statement.predicateText(), statement.predicateLemma(), statement.voice(),
                            statement.clause(), statement.core(), statement.arguments(), statement.findings(),
                            statement.paragraph(), ParsedStatements.Quality.UNCERTAIN, List.of("TEST"),
                            statement.review(), statement.features(), statement.derivation(), statement.check()))
                    .toList();
            return new KnowledgeGraph.AnalysedPage(page.page(), page.analysis().withResult(new ParsedStatements(
                    result.sentences(), result.mentions(), doubtful, result.attachments(), result.components(),
                    result.conllu())));
        }).toList(), vocabulary());

        assertThat(uncertain.around(CALLER, "Beleg", DomainTermGraph.Options.DEFAULT, () -> false).edges())
                .as("unsicher zählt nicht").isEmpty();
    }

    @Test
    void theStatusNamesTheAnalyzerEvenWithoutEdgesAndCountsOnlyStaleStatementsThatWouldBeShown() {
        term("Sachbearbeiter");
        term("Beleg");
        term("Kunde");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        analyse("wiki", "2", "Der Kunde sendet den Beleg.");
        cache.put(new PageKey("wiki", "1"), EnrichmentTestPages.text("wiki", "1", "2", "Ganz neuer Text."));
        cache.put(new PageKey("wiki", "2"), EnrichmentTestPages.text("wiki", "2", "2", "Auch neu."));
        DomainTermGraph chosen = new DomainTermGraph(graph::analysed, vocabulary(), () -> AnalyzerChoice.PARSER);

        DomainTermGraph.View near = chosen.around(CALLER, "Sachbearbeiter",
                new DomainTermGraph.Options(1, false, null), () -> false);

        assertThat(near.edges()).isEmpty();
        assertThat(near.analyzers()).as("auch ohne Kante").containsExactly("fake");
        assertThat(near.analyzer()).isEqualTo(AnalyzerChoice.PARSER);
        assertThat(near.skippedStale()).as("nur „prüft“ läge in Tiefe 1, „Kunde sendet“ nicht").isEqualTo(1);
        assertThat(chosen.around(CALLER, "Sachbearbeiter", DomainTermGraph.Options.DEFAULT, () -> false)
                .skippedStale()).as("in Tiefe 2 läge auch der Kunde").isEqualTo(2);
        assertThat(around("Kunde").analyzer()).as("ohne Angabe unbekannt").isNull();
    }

    @Test
    void aSynonymThatIsATechnicalEntryNeverBecomesANode() {
        term("Antrag");
        technical.add(new DomainTermGraph.Term("Formular-ID", new RelationScope(null, null)));
        synonyms.add(new DomainTermGraph.Synonym("Antrag", "Formular-ID", new RelationScope(null, null)));
        synonyms.add(new DomainTermGraph.Synonym("Antrag", "Gesuch", new RelationScope(null, null)));

        assertThat(around("Antrag").nodes()).extracting(DomainTermGraph.Node::label)
                .containsExactly("Antrag", "Gesuch");
        assertThat(around("Formular-ID").outcome()).isEqualTo(DomainTermGraph.Outcome.NOT_A_TERM);
    }

    @Test
    void suggestionsOfferOnlyConfirmedDomainTermsAndAdmittedSynonyms() {
        term("Antrag");
        term("Antragsteller");
        term("Bescheid");
        synonyms.add(new DomainTermGraph.Synonym("Antrag", "Folgeantrag", new RelationScope(null, null)));
        synonyms.add(new DomainTermGraph.Synonym("Kantine", "Mensa", new RelationScope(null, null)));

        assertThat(fachsicht.suggestions(CALLER, "antr", 8)).containsExactly("Antrag", "Antragsteller",
                "Folgeantrag");
        assertThat(fachsicht.suggestions(CALLER, "ens", 8)).as("Synonym ohne Fachbegriff fehlt").isEmpty();
        assertThat(fachsicht.suggestions(CALLER, "antr", 1)).containsExactly("Antrag");
    }

    @Test
    void depthOneStopsAtTheNeighbours() {
        term("Sachbearbeiter");
        term("Beleg");
        term("Kunde");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg. Der Kunde sendet den Beleg.");

        assertThat(fachsicht.around(CALLER, "Sachbearbeiter", new DomainTermGraph.Options(1, false, null),
                () -> false).nodes()).extracting(DomainTermGraph.Node::label).containsExactly("Sachbearbeiter",
                "Beleg");
        assertThat(around("Sachbearbeiter").nodes()).extracting(DomainTermGraph.Node::label)
                .containsExactly("Sachbearbeiter", "Beleg", "Kunde");
    }

    @Test
    void anEdgeCarriesTheHighestTrustAndACheckedStatementOutranksADeviatingOne() {
        term("Sachbearbeiter");
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        analyse("wiki", "2", "Der Sachbearbeiter prüft den Beleg.");
        DomainTermGraph checked = withTrust(Set.of("1"), Map.of("2", AnalyzerComparison.Agreement.DIFFERS));

        DomainTermGraph.Edge edge = checked.around(CALLER, "Sachbearbeiter", DomainTermGraph.Options.DEFAULT,
                () -> false).edges().getFirst();

        assertThat(edge.evidence()).extracting(DomainTermGraph.Evidence::trust)
                .containsExactly(DomainTermGraph.Trust.GEPRUEFT, DomainTermGraph.Trust.ABWEICHEND);
        assertThat(edge.trust()).isEqualTo(DomainTermGraph.Trust.GEPRUEFT);
        assertThat(edge.differs()).as("geprüft geht vor: kein „abweichend“").isFalse();
    }

    @Test
    void agreeingAnalyzersRankAboveUncheckedAndADeviationWithoutCheckMarksTheEdge() {
        term("Sachbearbeiter");
        term("Beleg");
        term("Kunde");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        analyse("wiki", "2", "Der Sachbearbeiter prüft den Beleg.");
        analyse("wiki", "3", "Der Kunde sendet den Beleg.");
        DomainTermGraph compared = withTrust(Set.of(), Map.of("1", AnalyzerComparison.Agreement.AGREES,
                "3", AnalyzerComparison.Agreement.DIFFERS));

        List<DomainTermGraph.Edge> edges = compared.around(CALLER, "Beleg", DomainTermGraph.Options.DEFAULT,
                () -> false).edges();

        assertThat(edges).filteredOn(e -> "prüft".equals(e.verb())).singleElement().satisfies(edge -> {
            assertThat(edge.trust()).isEqualTo(DomainTermGraph.Trust.EINIG);
            assertThat(edge.differs()).isFalse();
        });
        assertThat(edges).filteredOn(e -> "sendet".equals(e.verb())).singleElement().satisfies(edge -> {
            assertThat(edge.trust()).isEqualTo(DomainTermGraph.Trust.UNGEPRUEFT);
            assertThat(edge.differs()).isTrue();
        });
        assertThat(around("Beleg").edges()).as("ohne Vergleich alles ungeprüft")
                .allSatisfy(edge -> assertThat(edge.trust()).isEqualTo(DomainTermGraph.Trust.UNGEPRUEFT))
                .noneMatch(DomainTermGraph.Edge::differs);
    }

    @Test
    void checkedOnlyDropsEdgesWithoutCheckedEvidenceAndTheCountFollows() {
        term("Sachbearbeiter");
        term("Beleg");
        term("Kunde");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        analyse("wiki", "2", "Der Sachbearbeiter prüft den Beleg.");
        analyse("wiki", "3", "Der Kunde sendet den Beleg.");
        DomainTermGraph compared = withTrust(Set.of("1"), Map.of("2", AnalyzerComparison.Agreement.AGREES,
                "3", AnalyzerComparison.Agreement.AGREES));

        DomainTermGraph.View view = compared.around(CALLER, "Beleg",
                new DomainTermGraph.Options(2, false, null, true), () -> false);

        assertThat(view.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.verb()).isEqualTo("prüft");
            assertThat(edge.count()).as("N zählt nur Geprüftes").isEqualTo(1);
            assertThat(edge.evidence()).extracting(e -> e.page().key().pageId()).containsExactly("1");
        });
        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label).containsExactly("Beleg", "Sachbearbeiter");
    }

    @Test
    void checkedOnlyAlsoCountsOnlyCheckedStaleStatements() {
        term("Sachbearbeiter");
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        analyse("wiki", "2", "Der Sachbearbeiter prüft den Beleg.");
        cache.put(new PageKey("wiki", "2"), EnrichmentTestPages.text("wiki", "2", "2", "Ganz neuer Text."));
        DomainTermGraph.Options checkedOnly = new DomainTermGraph.Options(2, false, null, true);

        assertThat(withTrust(Set.of("1"), Map.of()).around(CALLER, "Sachbearbeiter", checkedOnly, () -> false)
                .skippedStale()).as("veraltet, aber ungeprüft: zählt in „Nur geprüft“ nicht").isZero();
        assertThat(withTrust(Set.of("1", "2"), Map.of()).around(CALLER, "Sachbearbeiter", checkedOnly, () -> false)
                .skippedStale()).as("veraltet und geprüft: zählt").isEqualTo(1);
    }

    @Test
    void aConfirmedReviewOrAPassedCheckIsCheckedWhateverTheComparisonSays() {
        ParsedStatements.Statement plain = new ParsedStatements.Statement("doc#s1.v1", 0, 0, 1, "prüft", "prüfen",
                ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(), 0,
                ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED);
        ParsedStatements.Statement confirmed = new ParsedStatements.Statement("doc#s1.v2", 0, 0, 1, "prüft",
                "prüfen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(), 0,
                ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.CONFIRMED);
        assertThat(DomainTermGraph.trust(confirmed, AnalyzerComparison.Agreement.DIFFERS))
                .isEqualTo(DomainTermGraph.Trust.GEPRUEFT);
        assertThat(DomainTermGraph.trust(plain, AnalyzerComparison.Agreement.DIFFERS))
                .isEqualTo(DomainTermGraph.Trust.ABWEICHEND);
        assertThat(DomainTermGraph.trust(plain.withCheck(new ParsedStatements.Check(ParsedStatements.Verdict.STIMMT,
                "v1", false)), AnalyzerComparison.Agreement.DIFFERS)).isEqualTo(DomainTermGraph.Trust.GEPRUEFT);
        assertThat(DomainTermGraph.trust(plain.withCheck(new ParsedStatements.Check(ParsedStatements.Verdict.FALSCH,
                "v1", false)), AnalyzerComparison.Agreement.AGREES)).isEqualTo(DomainTermGraph.Trust.EINIG);
        assertThat(DomainTermGraph.trust(plain, null)).isEqualTo(DomainTermGraph.Trust.UNGEPRUEFT);
    }

    /** Statements auf {@code checked} gelten als geprüft (Prüfschritt „stimmt“), {@code agreement} je Seite. */
    private DomainTermGraph withTrust(Set<String> checked, Map<String, AnalyzerComparison.Agreement> agreement) {
        return new DomainTermGraph(caller -> graph.analysed(caller).stream().map(page -> {
            if (!checked.contains(page.page().key().pageId())) {
                return page;
            }
            ParsedStatements result = page.analysis().result();
            ParsedStatements passed = new ParsedStatements(result.sentences(), result.mentions(),
                    result.statements().stream().map(statement -> statement.withCheck(new ParsedStatements.Check(
                            ParsedStatements.Verdict.STIMMT, "v1", false))).toList(), result.attachments(),
                    result.components(), result.conllu());
            return new KnowledgeGraph.AnalysedPage(page.page(), page.analysis().withResult(passed));
        }).toList(), vocabulary(), (caller, page) -> {
            AnalyzerComparison.Agreement found = agreement.get(page.key().pageId());
            Map<String, AnalyzerComparison.Comparison> byStatement = new java.util.LinkedHashMap<>();
            if (found != null) {
                graph.analysed(caller).stream().filter(analysed -> analysed.page().key().equals(page.key()))
                        .flatMap(analysed -> analysed.analysis().result().statements().stream())
                        .forEach(statement -> byStatement.put(statement.id(), compared(statement.id(), found)));
            }
            return byStatement;
        });
    }

    /** Ein Abgleich wie aus K2: die andere Ablage ist die KI; abweichend liest sie das Verb „senden“. */
    private static AnalyzerComparison.Comparison compared(String statementId, AnalyzerComparison.Agreement agreement) {
        AnalyzerComparison.Reading own = new AnalyzerComparison.Reading(AnalyzerChoice.PARSER, statementId, "prüfen",
                Set.of("sachbearbeiter"), Set.of("beleg"));
        AnalyzerComparison.Reading other = agreement == AnalyzerComparison.Agreement.NOT_COMPARABLE ? null
                : new AnalyzerComparison.Reading(AnalyzerChoice.MODEL, statementId,
                agreement == AnalyzerComparison.Agreement.DIFFERS ? "senden" : "prüfen", Set.of("sachbearbeiter"),
                Set.of("beleg"));
        return new AnalyzerComparison.Comparison(statementId, agreement, own, other,
                agreement == AnalyzerComparison.Agreement.DIFFERS
                        ? java.util.EnumSet.of(AnalyzerComparison.Difference.VERB) : Set.of());
    }

    @Test
    void theComparisonIsAskedOncePerPageOnlyForEdgesAndADeviationCarriesTheOtherReading() {
        term("Sachbearbeiter");
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg. Der Sachbearbeiter prüft den Beleg.");
        analyse("wiki", "2", "Die Buchhaltung sendet den Beleg.");
        List<String> asked = new ArrayList<>();
        DomainTermGraph compared = new DomainTermGraph(graph, vocabulary(), () -> null, (caller, page) -> {
            asked.add(page.key().pageId());
            Map<String, AnalyzerComparison.Comparison> byStatement = new java.util.LinkedHashMap<>();
            graph.analysed(caller).stream().filter(analysed -> analysed.page().key().equals(page.key()))
                    .flatMap(analysed -> analysed.analysis().result().statements().stream())
                    .forEach(statement -> byStatement.put(statement.id(),
                            compared(statement.id(), AnalyzerComparison.Agreement.DIFFERS)));
            return byStatement;
        });

        DomainTermGraph.Edge edge = compared.around(CALLER, "Sachbearbeiter", DomainTermGraph.Options.DEFAULT,
                () -> false).edges().getFirst();

        assertThat(asked).as("je Seite einmal, Seite 2 trägt keine Kante").containsExactly("1");
        assertThat(edge.evidence()).hasSize(2).allSatisfy(item -> {
            assertThat(item.trust()).isEqualTo(DomainTermGraph.Trust.ABWEICHEND);
            assertThat(item.otherReading()).isNotNull();
            assertThat(item.otherReading().analyzer()).isEqualTo(AnalyzerChoice.MODEL);
            assertThat(item.otherReading().verb()).isEqualTo("senden");
            assertThat(item.comparison().differences()).containsExactly(AnalyzerComparison.Difference.VERB);
        });
    }

    @Test
    void theComparisonIsNotAskedWhereItCannotChangeTheStage() {
        term("Sachbearbeiter");
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        analyse("wiki", "2", "Der Sachbearbeiter prüft den Beleg.");
        cache.put(new PageKey("wiki", "2"), EnrichmentTestPages.text("wiki", "2", "2", "Ganz neuer Text."));
        List<String> asked = new ArrayList<>();
        DomainTermGraph.Comparison counting = (caller, page) -> {
            asked.add(page.key().pageId());
            return Map.of();
        };

        new DomainTermGraph(graph, vocabulary(), () -> null, counting).around(CALLER, "Sachbearbeiter",
                new DomainTermGraph.Options(2, true, null), () -> false);
        assertThat(asked).as("veraltete Seite: nichts vergleichbar").containsExactly("1");
        asked.clear();
        new DomainTermGraph(graph, vocabulary(), () -> null, counting).around(CALLER, "Sachbearbeiter",
                new DomainTermGraph.Options(2, true, null, true), () -> false);
        assertThat(asked).as("„nur geprüft“ hängt nicht am Vergleich").isEmpty();
        DomainTermGraph checked = new DomainTermGraph(caller -> graph.analysed(caller).stream().map(page -> {
            ParsedStatements result = page.analysis().result();
            return new KnowledgeGraph.AnalysedPage(page.page(), page.analysis().withResult(new ParsedStatements(
                    result.sentences(), result.mentions(), result.statements().stream().map(statement ->
                    statement.withCheck(new ParsedStatements.Check(ParsedStatements.Verdict.STIMMT, "v1", false)))
                    .toList(), result.attachments(), result.components(), result.conllu())));
        }).toList(), vocabulary(), counting);
        checked.around(CALLER, "Sachbearbeiter", DomainTermGraph.Options.DEFAULT, () -> false);
        assertThat(asked).as("geprüft geht ohnehin vor").isEmpty();
    }

    @Test
    void aRecipientOrPrepositionalEdgeIsNeverAgreedBecauseTheComparisonDoesNotCoverIt() {
        AnalyzerComparison.Comparison onlyAffected = new AnalyzerComparison.Comparison("s",
                AnalyzerComparison.Agreement.DIFFERS, null, null,
                java.util.EnumSet.of(AnalyzerComparison.Difference.AFFECTED_OBJECT));
        AnalyzerComparison.Comparison verb = new AnalyzerComparison.Comparison("s",
                AnalyzerComparison.Agreement.DIFFERS, null, null,
                java.util.EnumSet.of(AnalyzerComparison.Difference.VERB));
        ParsedStatements.Role recipient = ParsedStatements.Role.RECIPIENT;

        assertThat(DomainTermGraph.trustFor(DomainTermGraph.Trust.EINIG, null, recipient))
                .isEqualTo(DomainTermGraph.Trust.UNGEPRUEFT);
        assertThat(DomainTermGraph.trustFor(DomainTermGraph.Trust.EINIG, null,
                ParsedStatements.Role.PREPOSITIONAL_OBJECT)).isEqualTo(DomainTermGraph.Trust.UNGEPRUEFT);
        assertThat(DomainTermGraph.trustFor(DomainTermGraph.Trust.ABWEICHEND, onlyAffected, recipient))
                .as("nur die Betroffenen weichen ab, die Kante endet am Empfänger")
                .isEqualTo(DomainTermGraph.Trust.UNGEPRUEFT);
        assertThat(DomainTermGraph.trustFor(DomainTermGraph.Trust.ABWEICHEND, verb, recipient))
                .as("ein anderes Verb trifft jede Kante").isEqualTo(DomainTermGraph.Trust.ABWEICHEND);
        assertThat(DomainTermGraph.trustFor(DomainTermGraph.Trust.EINIG, null, ParsedStatements.Role.AFFECTED_OBJECT))
                .isEqualTo(DomainTermGraph.Trust.EINIG);
        assertThat(DomainTermGraph.trustFor(DomainTermGraph.Trust.GEPRUEFT, verb, recipient))
                .isEqualTo(DomainTermGraph.Trust.GEPRUEFT);
    }

    @Test
    void anEntryOnlyInTheOtherStoreWithTheSameIdIsNotThisStatement() {
        term("Sachbearbeiter");
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        DomainTermGraph compared = new DomainTermGraph(graph, vocabulary(), () -> null, (caller, page) -> {
            Map<String, AnalyzerComparison.Comparison> byStatement = new java.util.LinkedHashMap<>();
            graph.analysed(caller).stream().flatMap(analysed -> analysed.analysis().result().statements().stream())
                    .forEach(statement -> byStatement.put(statement.id(), new AnalyzerComparison.Comparison(
                            statement.id(), AnalyzerComparison.Agreement.DIFFERS, null,
                            new AnalyzerComparison.Reading(AnalyzerChoice.MODEL, statement.id(), "senden", Set.of(),
                                    Set.of()), java.util.EnumSet.of(AnalyzerComparison.Difference.VERB))));
            return byStatement;
        });

        DomainTermGraph.Evidence item = compared.around(CALLER, "Sachbearbeiter", DomainTermGraph.Options.DEFAULT,
                () -> false).edges().getFirst().evidence().getFirst();

        assertThat(item.trust()).isEqualTo(DomainTermGraph.Trust.UNGEPRUEFT);
        assertThat(item.comparison()).isNull();
        assertThat(item.otherReading()).isNull();
    }

    @Test
    void theRealComparisonTreatsAPageOutsideTheCacheAsNotComparable() {
        AnalyzerComparison analyzers = new AnalyzerComparison(access, key -> Optional.empty(),
                (caller, analyzer, key) -> Optional.empty(), () -> AnalyzerChoice.PARSER);
        deniedSources.add("geheim");
        AnalyzerComparison denied = new AnalyzerComparison(access, key -> Optional.of(
                EnrichmentTestPages.text(key.sourceId(), key.pageId(), "1", "Text.")),
                (caller, analyzer, key) -> Optional.empty(), () -> AnalyzerChoice.PARSER);
        KnowledgeGraph.PageRef missing = new KnowledgeGraph.PageRef(new PageKey("wiki", "9"), null, null,
                PageStatements.State.CURRENT, null, null);
        KnowledgeGraph.PageRef secret = new KnowledgeGraph.PageRef(new PageKey("geheim", "1"), null, null,
                PageStatements.State.CURRENT, null, null);

        assertThat(DomainTermGraph.Comparison.of(analyzers).comparisons(CALLER, missing)).isEmpty();
        assertThat(DomainTermGraph.Comparison.of(denied).comparisons(CALLER, secret)).isEmpty();
    }

    @Test
    void onlyStatementsFromTheOriginalSentenceAreEvidenceSoACorrectionDoesNotCountTwice() {
        term("Sachbearbeiter");
        term("Beleg");
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        DomainTermGraph corrected = new DomainTermGraph(caller -> graph.analysed(caller).stream().map(page -> {
            ParsedStatements result = page.analysis().result();
            List<ParsedStatements.Statement> statements = new ArrayList<>(result.statements());
            for (ParsedStatements.Statement statement : result.statements()) {
                statements.add(statement.withDerivation(ParsedStatements.Derivation.modell(
                        "Der Sachbearbeiter prüft den Beleg.", statement.id(), ParsedStatements.Origin.ORIGINAL)));
                statements.add(statement.withDerivation(ParsedStatements.Derivation.vorstufe(
                        ParsedStatements.StageFlag.FACT, "Der Sachbearbeiter prüft den Beleg.")));
            }
            ParsedStatements doubled = new ParsedStatements(result.sentences(), result.mentions(), statements,
                    result.attachments(), result.components(), result.conllu());
            return new KnowledgeGraph.AnalysedPage(page.page(), page.analysis().withResult(doubled));
        }).toList(), vocabulary(), DomainTermGraph.Comparison.NONE);

        DomainTermGraph.Edge edge = corrected.around(CALLER, "Sachbearbeiter", DomainTermGraph.Options.DEFAULT,
                () -> false).edges().getFirst();

        assertThat(edge.count()).as("Korrektur und Vorstufe sind nie Beleg").isEqualTo(1);
        assertThat(edge.evidence()).singleElement().satisfies(evidence -> assertThat(
                evidence.statement().derivation().origin()).isEqualTo(ParsedStatements.Origin.ORIGINAL));
    }

    /**
     * Mit Auswahl liest die Fachsicht nur deren Seiten ({@link KnowledgeGraph#analysed(AccessContext, PageSet)}), nie
     * die ganze Ablage; ohne Auswahl den ganzen Durchgang. Ergebnis wie mit Lesen und Filtern danach.
     */
    @Test
    void withASelectionOnlyTheSelectedPagesAreReadNotTheWholeStore() {
        term("Planer");
        term("Ziel");
        term("Kunde");
        analyse("wiki", "1", "Der Planer prüft das Ziel.");
        analyse("wiki", "3", "Der Kunde prüft den Planer.");
        int[] full = {0};
        List<PageSet> selected = new ArrayList<>();
        DomainTermGraph counted = new DomainTermGraph(caller -> {
            full[0]++;
            return graph.analysed(caller);
        }, (caller, pages) -> {
            selected.add(pages);
            return graph.analysed(caller, pages);
        }, vocabulary(), () -> null, DomainTermGraph.Comparison.NONE);

        DomainTermGraph.View page = counted.onPages(CALLER, new DomainTermGraph.Options(2, false, pages("1")),
                () -> false);
        DomainTermGraph.View around = counted.around(CALLER, "Planer", new DomainTermGraph.Options(1, false,
                pages("1")), () -> false);

        assertThat(full[0]).as("kein Durchgang über die ganze Ablage").isZero();
        assertThat(selected).containsExactly(pages("1"), pages("1"));
        assertThat(page.nodes()).extracting(DomainTermGraph.Node::label).containsExactly("Planer", "Ziel");
        assertThat(around.edges()).extracting(DomainTermGraph.Edge::to).containsExactly("ziel");

        counted.around(CALLER, "Planer", DomainTermGraph.Options.DEFAULT, () -> false);
        assertThat(full[0]).as("ohne Auswahl der ganze Durchgang").isEqualTo(1);
        assertThat(selected).hasSize(2);
    }

    @Test
    void onPagesCollectsTheTermsOfTheGivenPagesOnlyWithoutACentre() {
        term("Planer");
        term("Ziel");
        term("Nachtlauf");
        term("Start");
        term("Kunde");
        analyse("wiki", "1", "Der Planer prüft das Ziel.");
        analyse("wiki", "2", "Der Nachtlauf sendet den Start.");
        analyse("wiki", "3", "Der Kunde prüft den Planer.");

        DomainTermGraph.View view = onPages(pages("1", "2"));

        assertThat(view.outcome()).isEqualTo(DomainTermGraph.Outcome.FOUND);
        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label)
                .as("„Kunde“ steht nur auf Seite 3").containsExactly("Nachtlauf", "Planer", "Start", "Ziel");
        assertThat(view.nodes()).allSatisfy(node -> {
            assertThat(node.depth()).as("keine Mitte").isZero();
            assertThat(node.parent()).isNull();
        });
        assertThat(view.edges()).extracting(DomainTermGraph.Edge::from, DomainTermGraph.Edge::verb,
                DomainTermGraph.Edge::to).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("planer", "prüft", "ziel"),
                org.assertj.core.groups.Tuple.tuple("nachtlauf", "sendet", "start"));
        assertThat(view.edges()).allSatisfy(edge -> assertThat(edge.count()).isEqualTo(1));
        assertThat(view.truncated()).isFalse();
        assertThat(view.skippedStale()).isZero();
        assertThat(view.analyzers()).containsExactly("fake");
    }

    @Test
    void onPagesOrdersByEvidenceCountThenByNameIgnoringCase() {
        term("Plan");
        term("Ziel");
        term("Nachtlauf");
        term("Start");
        term("bote");
        analyse("wiki", "1", "Der Plan prüft das Ziel. Der Plan prüft das Ziel. Der Nachtlauf sendet den Start. "
                + "Der Bote sendet den Start.");

        DomainTermGraph.View view = onPages(pages("1"));

        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label)
                .as("je zwei Belege vor je einem, bei Gleichstand nach Wortlaut ohne Groß-/Kleinschreibung")
                .containsExactly("Plan", "Start", "Ziel", "bote", "Nachtlauf");
        assertThat(view.edges()).filteredOn(edge -> "prüft".equals(edge.verb())).singleElement()
                .satisfies(edge -> assertThat(edge.count()).isEqualTo(2));
    }

    @Test
    void onPagesStopsAtMaxNodesWithAStableSelection() {
        term("Kern");
        List<String> targets = new ArrayList<>();
        for (int i = 0; i <= DomainTermGraph.MAX_NODES; i++) {
            String target = "Ziel" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
            term(target);
            targets.add(target);
        }
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int i = targets.size() - 1; i >= 0; i--) {
            (i % 2 == 0 ? first : second).append("Der Kern prüft die ").append(targets.get(i)).append(". ");
        }
        analyse("wiki", "2", second.toString().strip());
        analyse("wiki", "1", first.toString().strip());

        DomainTermGraph.View view = onPages(pages("1", "2"));

        assertThat(view.truncated()).as("42 Knoten gäbe es").isTrue();
        assertThat(view.nodes()).hasSize(DomainTermGraph.MAX_NODES);
        List<String> expected = new ArrayList<>(List.of("Kern"));
        expected.addAll(targets.subList(0, DomainTermGraph.MAX_NODES - 1));
        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label)
                .as("der Kern mit den meisten Belegen, dann alphabetisch").containsExactlyElementsOf(expected);
        assertThat(view.edges()).hasSize(DomainTermGraph.MAX_NODES - 1);
        assertThat(onPages(pages("1", "2")).nodes()).isEqualTo(view.nodes());
    }

    @Test
    void onPagesWithExactlyMaxNodesIsNotTruncatedButOneMoreIs() {
        term("Kern");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < DomainTermGraph.MAX_NODES - 1; i++) {
            String target = "Ziel" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
            term(target);
            text.append("Der Kern prüft die ").append(target).append(". ");
        }
        term("Plan");
        analyse("wiki", "1", text.toString().strip());
        analyse("wiki", "2", "Der Plan prüft den Kern.");

        DomainTermGraph.View exact = onPages(pages("1"));

        assertThat(exact.nodes()).as("Kern und 39 Ziele").hasSize(DomainTermGraph.MAX_NODES);
        assertThat(exact.truncated()).as("genau MAX_NODES Knoten passen").isFalse();

        DomainTermGraph.View oneMore = onPages(pages("1", "2"));

        assertThat(oneMore.nodes()).hasSize(DomainTermGraph.MAX_NODES);
        assertThat(oneMore.truncated()).as("ein Knoten mehr als MAX_NODES").isTrue();
    }

    @Test
    void onPagesNeverShowsANodeWithoutAVisiblePartner() {
        // 21 getrennte Paare mit je einem Beleg: 42 Knoten gleichen Gewichts, alle „Quelle…“ vor allen „Ziel…“.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i <= DomainTermGraph.MAX_NODES / 2; i++) {
            String suffix = "" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
            term("Quelle" + suffix);
            term("Ziel" + suffix);
            text.append("Die Quelle").append(suffix).append(" prüft das Ziel").append(suffix).append(". ");
        }
        analyse("wiki", "1", text.toString().strip());

        DomainTermGraph.View view = onPages(pages("1"));

        assertThat(view.truncated()).isTrue();
        assertThat(view.nodes()).hasSize(DomainTermGraph.MAX_NODES);
        Set<String> touched = new HashSet<>();
        view.edges().forEach(edge -> {
            touched.add(edge.from());
            touched.add(edge.to());
        });
        assertThat(view.nodes()).extracting(DomainTermGraph.Node::key).as("jeder Knoten mit sichtbarer Kante")
                .allMatch(touched::contains);
        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label)
                .as("das 21. Paar fällt ganz weg").doesNotContain("Quelleau", "Zielau")
                .contains("Quelleat", "Zielat");
    }

    @Test
    void onPagesShowsSynonymEdgesOnlyBetweenShownNodes() {
        term("Plan");
        term("Ziel");
        term("Start");
        synonyms.add(new DomainTermGraph.Synonym("Plan", "Entwurf", RelationScope.GLOBAL));
        synonyms.add(new DomainTermGraph.Synonym("Ziel", "Zweck", RelationScope.GLOBAL));
        analyse("wiki", "1", "Der Entwurf prüft das Ziel. Der Plan sendet den Start.");

        DomainTermGraph.View view = onPages(pages("1"));

        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label, DomainTermGraph.Node::kind)
                .as("„Zweck“ hat keine eigene Verb-Kante").containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Entwurf", DomainTermGraph.NodeKind.SYNONYM),
                        org.assertj.core.groups.Tuple.tuple("Plan", DomainTermGraph.NodeKind.TERM),
                        org.assertj.core.groups.Tuple.tuple("Start", DomainTermGraph.NodeKind.TERM),
                        org.assertj.core.groups.Tuple.tuple("Ziel", DomainTermGraph.NodeKind.TERM));
        assertThat(view.edges()).filteredOn(edge -> edge.kind() == DomainTermGraph.EdgeKind.SYNONYM)
                .extracting(DomainTermGraph.Edge::from, DomainTermGraph.Edge::to)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("plan", "entwurf"));
        assertThat(view.edges()).filteredOn(edge -> edge.kind() == DomainTermGraph.EdgeKind.VERB).hasSize(2);
    }

    @Test
    void onPagesWithoutAVerbBetweenTermsSaysNoEdges() {
        term("Plan");
        term("Ziel");
        // Ein Glossar-Synonym ist immer eine Kante im Aufbau; es darf weder zu FOUND führen noch durchsickern.
        synonyms.add(new DomainTermGraph.Synonym("Plan", "Entwurf", RelationScope.GLOBAL));
        analyse("wiki", "1", "Der Kunde prüft das Ziel.");

        DomainTermGraph.View view = onPages(pages("1"));

        assertThat(view.outcome()).isEqualTo(DomainTermGraph.Outcome.NO_EDGES);
        assertThat(view.nodes()).isEmpty();
        assertThat(view.edges()).as("keine Synonym-Kante ohne gezeigte Knoten").isEmpty();
        assertThat(view.truncated()).isFalse();
        assertThat(view.skippedStale()).isZero();
        assertThat(view.analyzers()).as("die Seite ist gelesen").containsExactly("fake");
        DomainTermGraph.View empty = onPages(PageSet.EMPTY);
        assertThat(empty.outcome()).isEqualTo(DomainTermGraph.Outcome.NO_EDGES);
        assertThat(empty.edges()).isEmpty();
    }

    @Test
    void onPagesCheckedOnlyKeepsOnlyCheckedEvidence() {
        term("Plan");
        term("Ziel");
        term("Nachtlauf");
        term("Start");
        analyse("wiki", "1", "Der Plan prüft das Ziel.");
        analyse("wiki", "2", "Der Nachtlauf sendet den Start.");

        DomainTermGraph.View view = withTrust(Set.of("1"), Map.of()).onPages(CALLER,
                new DomainTermGraph.Options(2, false, pages("1", "2"), true), () -> false);

        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label).containsExactly("Plan", "Ziel");
        assertThat(view.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.trust()).isEqualTo(DomainTermGraph.Trust.GEPRUEFT);
            assertThat(edge.count()).isEqualTo(1);
        });
    }

    @Test
    void onPagesSkipsAndCountsStaleStatementsUnlessWanted() {
        term("Plan");
        term("Ziel");
        term("Nachtlauf");
        term("Start");
        analyse("wiki", "1", "Der Plan prüft das Ziel.");
        analyse("wiki", "2", "Der Nachtlauf sendet den Start.");
        cache.put(new PageKey("wiki", "1"), EnrichmentTestPages.text("wiki", "1", "2", "Ganz neuer Text."));

        DomainTermGraph.View current = onPages(pages("1", "2"));
        assertThat(current.nodes()).extracting(DomainTermGraph.Node::label).containsExactly("Nachtlauf", "Start");
        assertThat(current.skippedStale()).isEqualTo(1);

        DomainTermGraph.View withStale = fachsicht.onPages(CALLER,
                new DomainTermGraph.Options(2, true, pages("1", "2")), () -> false);
        assertThat(withStale.nodes()).extracting(DomainTermGraph.Node::label)
                .containsExactly("Nachtlauf", "Plan", "Start", "Ziel");
        assertThat(withStale.skippedStale()).isZero();
        assertThat(withStale.edges()).filteredOn(edge -> "prüft".equals(edge.verb())).singleElement()
                .satisfies(edge -> assertThat(edge.evidence().getFirst().page().state())
                        .isEqualTo(PageStatements.State.STALE));

        DomainTermGraph.View onlyStale = onPages(pages("1"));
        assertThat(onlyStale.outcome()).isEqualTo(DomainTermGraph.Outcome.NO_EDGES);
        assertThat(onlyStale.skippedStale()).as("mit „veraltete zeigen“ stünde die Kante").isEqualTo(1);
    }

    @Test
    void onPagesStaleStatementsCountOnlyIfTheyWouldFitThePageWideView() {
        term("Kern");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < DomainTermGraph.MAX_NODES; i++) {
            String target = "Ziel" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
            term(target);
            text.append("Der Kern prüft die ").append(target).append(". ");
        }
        term("Zug");
        term("Zustand");
        analyse("wiki", "1", text.toString().strip());
        analyse("wiki", "2", "Der Zug sendet den Zustand. Der Kern sendet die Zielaa.");
        cache.put(new PageKey("wiki", "2"), EnrichmentTestPages.text("wiki", "2", "2", "Ganz neuer Text."));

        DomainTermGraph.View view = onPages(pages("1", "2"));
        assertThat(view.truncated()).isTrue();
        assertThat(view.skippedStale()).as("auch mit „veraltete zeigen“ wären Zug und Zustand abgeschnitten, "
                + "„Kern sendet Zielaa“ aber nicht").isEqualTo(1);
    }

    @Test
    void onPagesNamingAPageTheCallerCannotSeeGetsNothingFromIt() {
        term("Plan");
        term("Ziel");
        term("Nachtlauf");
        term("Start");
        analyse("wiki", "1", "Der Nachtlauf sendet den Start.");
        analyse("wiki", "9", "Der Plan prüft das Ziel.");
        DomainTermGraph visible = new DomainTermGraph(caller -> graph.analysed(caller).stream()
                .filter(page -> !page.page().key().pageId().equals("9")).toList(), vocabulary());

        DomainTermGraph.View view = visible.onPages(CALLER,
                new DomainTermGraph.Options(2, false, pages("1", "9")), () -> false);

        assertThat(view.nodes()).extracting(DomainTermGraph.Node::label).containsExactly("Nachtlauf", "Start");
        assertThat(view.edges()).singleElement().satisfies(edge -> assertThat(edge.evidence())
                .extracting(e -> e.page().key().pageId()).containsExactly("1"));
        assertThat(onPages(pages("1", "9")).nodes()).as("Gegenprobe: sichtbar trägt sie bei").hasSize(4);
    }

    @Test
    void onPagesNeedsASelectionAndCanBeCancelled() {
        term("Plan");
        term("Ziel");
        analyse("wiki", "1", "Der Plan prüft das Ziel.");

        assertThatThrownBy(() -> fachsicht.onPages(CALLER, DomainTermGraph.Options.DEFAULT, () -> false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Seitenauswahl");
        assertThatThrownBy(() -> fachsicht.onPages(CALLER, new DomainTermGraph.Options(2, false, pages("1")),
                () -> true)).isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void negationModalityAndTimeSplitAnEdgeIntoStatementsWithTheirOwnEvidenceAndStage() {
        term("Plan");
        term("Ziel");
        analyse("wiki", "1", "Der Plan prüft das Ziel. Der Plan prüft das Ziel.");
        analyse("wiki", "2", "Der Plan prüft das Ziel.");
        analyse("wiki", "3", "Der Plan prüft das Ziel.");
        analyse("wiki", "4", "Der Plan prüft das Ziel.");
        Map<String, ParsedStatements.Features> features = Map.of(
                "1", new ParsedStatements.Features(ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE,
                        ParsedStatements.Temporality.GILT),
                "2", new ParsedStatements.Features(ParsedStatements.Negation.NEGATED, ParsedStatements.Modality.NONE,
                        ParsedStatements.Temporality.GILT),
                "3", new ParsedStatements.Features(ParsedStatements.Negation.NEGATED, ParsedStatements.Modality.MUSS,
                        ParsedStatements.Temporality.GEPLANT),
                "4", ParsedStatements.Features.UNKNOWN);
        DomainTermGraph split = new DomainTermGraph(caller -> withFeatures(caller, page -> {
            ParsedStatements.Features chosen = features.get(page);
            return statement -> {
                // Grundform „prüfen“; Seite 3 ohne Grundform (nur die gebeugte Form aus dem Text)
                ParsedStatements.Statement changed = rebuilt(statement, chosen, page.equals("3") ? "" : "prüfen");
                return page.equals("2") ? changed.withReview(ParsedStatements.Review.CONFIRMED) : changed;
            };
        }), vocabulary());

        DomainTermGraph.View view = split.around(CALLER, "Plan", DomainTermGraph.Options.DEFAULT, () -> false);

        assertThat(view.edges()).filteredOn(edge -> edge.kind() == DomainTermGraph.EdgeKind.VERB)
                .extracting(DomainTermGraph.Edge::phrase, DomainTermGraph.Edge::count, DomainTermGraph.Edge::trust)
                .as("„nicht bestimmt“ zählt wie schlicht; „nicht prüfen“ ist nie Beleg für „prüfen“")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("prüfen", 3, DomainTermGraph.Trust.UNGEPRUEFT),
                        org.assertj.core.groups.Tuple.tuple("nicht prüfen", 1, DomainTermGraph.Trust.GEPRUEFT),
                        // ohne Grundform kein „muss nicht prüft“: Modalverb und Verneinung entfallen im Wortlaut
                        org.assertj.core.groups.Tuple.tuple("prüft (geplant)", 1, DomainTermGraph.Trust.UNGEPRUEFT));
        assertThat(split.around(CALLER, "Plan", new DomainTermGraph.Options(2, false, null, true), () -> false)
                .edges()).as("„nur geprüft“ je Teil").singleElement()
                .satisfies(edge -> assertThat(edge.qualifiers().negated()).isTrue());
        DomainTermGraph.View page = split.onPages(CALLER, new DomainTermGraph.Options(2, false,
                pages("1", "2", "3", "4")), () -> false);
        assertThat(page.nodes()).extracting(DomainTermGraph.Node::label).containsExactly("Plan", "Ziel");
        assertThat(page.edges()).hasSize(3);
    }

    @Test
    void theGroupRanksPartnersByAllTheirStatementsNotByTheStrongestOne() {
        term("Plan");
        term("Ziel");
        term("Start");
        analyse("wiki", "1", "Der Plan prüft das Ziel.");
        analyse("wiki", "2", "Der Plan prüft das Ziel.");
        analyse("wiki", "3", "Der Plan prüft das Ziel.");
        analyse("wiki", "4", "Der Plan prüft den Start. Der Plan prüft den Start.");
        Map<String, ParsedStatements.Features> features = Map.of(
                "2", new ParsedStatements.Features(ParsedStatements.Negation.NEGATED, ParsedStatements.Modality.NONE,
                        ParsedStatements.Temporality.GILT),
                "3", new ParsedStatements.Features(ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.MUSS,
                        ParsedStatements.Temporality.GILT));
        DomainTermGraph split = new DomainTermGraph(caller -> withFeatures(caller, page -> statement ->
                rebuilt(statement, features.getOrDefault(page, ParsedStatements.Features.UNKNOWN))), vocabulary());

        DomainTermGraph.View group = split.group(CALLER, "Plan", DomainTermGraph.Options.DEFAULT, () -> false);

        assertThat(group.nodes()).extracting(DomainTermGraph.Node::label)
                .as("Ziel trägt drei Aussagen mit zusammen drei Belegen, Start eine mit zwei")
                .containsExactly("Plan", "Ziel", "Start");
    }

    @Test
    void theWordingNamesModalNegationAndTimeButNeverGilt() {
        DomainTermGraph.Qualifiers plain = DomainTermGraph.Qualifiers.PLAIN;
        assertThat(plain.phrase("prüfen")).isEqualTo("prüfen");
        assertThat(plain.plain()).isTrue();
        assertThat(new DomainTermGraph.Qualifiers(true, null, null).phrase("prüfen")).isEqualTo("nicht prüfen");
        assertThat(new DomainTermGraph.Qualifiers(false, ParsedStatements.Modality.KANN,
                ParsedStatements.Temporality.UNKNOWN).phrase("prüfen")).isEqualTo("kann prüfen");
        assertThat(new DomainTermGraph.Qualifiers(true, ParsedStatements.Modality.MUSS,
                ParsedStatements.Temporality.GEPLANT).phrase("prüfen")).isEqualTo("muss nicht prüfen (geplant)");
        assertThat(new DomainTermGraph.Qualifiers(true, ParsedStatements.Modality.MUSS,
                ParsedStatements.Temporality.GEPLANT).phrase("prüft", false))
                .as("ohne Grundform kein Vorsatz vor der gebeugten Form").isEqualTo("prüft (geplant)");
        DomainTermGraph.Qualifiers earlier = new DomainTermGraph.Qualifiers(false, ParsedStatements.Modality.NONE,
                ParsedStatements.Temporality.FRUEHER);
        assertThat(earlier.phrase("prüfen")).isEqualTo("prüfen (früher)");
        assertThat(earlier.past()).isTrue();
        assertThat(new DomainTermGraph.Qualifiers(false, ParsedStatements.Modality.SOLL,
                ParsedStatements.Temporality.VERWORFEN).phrase("prüfen")).isEqualTo("soll prüfen (verworfen)");
        assertThat(DomainTermGraph.Qualifiers.of(null)).isEqualTo(plain);
    }

    private List<KnowledgeGraph.AnalysedPage> withFeatures(AccessContext caller, java.util.function.Function<String,
            java.util.function.UnaryOperator<ParsedStatements.Statement>> how) {
        return graph.analysed(caller).stream().map(page -> {
            ParsedStatements result = page.analysis().result();
            java.util.function.UnaryOperator<ParsedStatements.Statement> change = how.apply(page.page().key().pageId());
            return new KnowledgeGraph.AnalysedPage(page.page(), page.analysis().withResult(new ParsedStatements(
                    result.sentences(), result.mentions(), result.statements().stream().map(change).toList(),
                    result.attachments(), result.components(), result.conllu())));
        }).toList();
    }

    private static ParsedStatements.Statement rebuilt(ParsedStatements.Statement statement,
                                                      ParsedStatements.Features features) {
        return rebuilt(statement, features, statement.predicateLemma());
    }

    private static ParsedStatements.Statement rebuilt(ParsedStatements.Statement statement,
                                                      ParsedStatements.Features features, String lemma) {
        return new ParsedStatements.Statement(statement.id(), statement.sentence(), statement.start(),
                statement.end(), statement.predicateText(), lemma, statement.voice(),
                statement.clause(), statement.core(), statement.arguments(), statement.findings(),
                statement.paragraph(), statement.quality(), statement.qualityReasons(), statement.review(), features,
                statement.derivation(), statement.check());
    }

    private DomainTermGraph.View onPages(PageSet pages) {
        return fachsicht.onPages(CALLER, new DomainTermGraph.Options(2, false, pages), () -> false);
    }

    private static PageSet pages(String... pageIds) {
        return PageSet.ofKeys(java.util.Arrays.stream(pageIds).map(id -> new PageKey("wiki", id)).toList());
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

            @Override
            public List<DomainTermGraph.Term> technical(AccessContext caller) {
                return technical;
            }
        };
    }

    private DomainTermGraph.View around(String start) {
        return fachsicht.around(CALLER, start, DomainTermGraph.Options.DEFAULT, () -> false);
    }

    private void term(String term) {
        terms.add(new DomainTermGraph.Term(term, new RelationScope(null, null)));
    }

    private void analyse(String sourceId, String pageId, String text) {
        PageSnapshot snapshot = EnrichmentTestPages.text(sourceId, pageId, "1", text);
        cache.put(snapshot.key(), snapshot);
        statements.analyze(CALLER, snapshot, () -> false);
    }
}
