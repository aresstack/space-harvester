package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

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
        DomainTermGraph checked = withTrust(Set.of("1"), Map.of("2", DomainTermGraph.Agreement.DIFFERS));

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
        DomainTermGraph compared = withTrust(Set.of(), Map.of("1", DomainTermGraph.Agreement.AGREES,
                "3", DomainTermGraph.Agreement.DIFFERS));

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
        DomainTermGraph compared = withTrust(Set.of("1"), Map.of("2", DomainTermGraph.Agreement.AGREES,
                "3", DomainTermGraph.Agreement.AGREES));

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
    void aConfirmedReviewOrAPassedCheckIsCheckedWhateverTheComparisonSays() {
        ParsedStatements.Statement plain = new ParsedStatements.Statement("doc#s1.v1", 0, 0, 1, "prüft", "prüfen",
                ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(), 0,
                ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED);
        ParsedStatements.Statement confirmed = new ParsedStatements.Statement("doc#s1.v2", 0, 0, 1, "prüft",
                "prüfen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(), Set.of(), 0,
                ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.CONFIRMED);
        assertThat(DomainTermGraph.trust(confirmed, DomainTermGraph.Agreement.DIFFERS))
                .isEqualTo(DomainTermGraph.Trust.GEPRUEFT);
        assertThat(DomainTermGraph.trust(plain, DomainTermGraph.Agreement.DIFFERS))
                .isEqualTo(DomainTermGraph.Trust.ABWEICHEND);
        assertThat(DomainTermGraph.trust(plain.withCheck(new ParsedStatements.Check(ParsedStatements.Verdict.STIMMT,
                "v1", false)), DomainTermGraph.Agreement.DIFFERS)).isEqualTo(DomainTermGraph.Trust.GEPRUEFT);
        assertThat(DomainTermGraph.trust(plain.withCheck(new ParsedStatements.Check(ParsedStatements.Verdict.FALSCH,
                "v1", false)), DomainTermGraph.Agreement.AGREES)).isEqualTo(DomainTermGraph.Trust.EINIG);
        assertThat(DomainTermGraph.trust(plain, null)).isEqualTo(DomainTermGraph.Trust.UNGEPRUEFT);
    }

    /** Statements auf {@code checked} gelten als geprüft (Prüfschritt „stimmt“), {@code agreement} je Seite. */
    private DomainTermGraph withTrust(Set<String> checked, Map<String, DomainTermGraph.Agreement> agreement) {
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
        }).toList(), vocabulary(), (caller, page, statement) ->
                agreement.getOrDefault(page.key().pageId(), DomainTermGraph.Agreement.NOT_COMPARABLE));
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
