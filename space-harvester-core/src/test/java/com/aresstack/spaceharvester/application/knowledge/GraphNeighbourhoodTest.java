package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Fokussierte Teilgraphen für den Reiter „Analysen → Graph“ (#499 Abschnitt F). */
class GraphNeighbourhoodTest {

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
    private final GraphNeighbourhood neighbourhood = new GraphNeighbourhood(graph);

    @Test
    void aPageShowsItsDocumentStatementsMentionsAndDashedCandidatesToOtherPages() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");

        GraphNeighbourhood.Subgraph subgraph = neighbourhood.page(CALLER, new PageKey("wiki", "1"),
                GraphNeighbourhood.Options.DEFAULT);

        assertThat(subgraph.focus()).startsWith("wiki/1@");
        assertThat(subgraph.nodes().getFirst().kind()).isEqualTo(PageGraph.NodeKind.DOCUMENT);
        assertThat(subgraph.nodes().get(1).kind()).as("Kernaussage vor Erwähnungen")
                .isEqualTo(PageGraph.NodeKind.STATEMENT);
        assertThat(subgraph.edges()).anySatisfy(edge -> {
            assertThat(edge.type()).isEqualTo(GraphNeighbourhood.EdgeType.ACTOR);
            assertThat(edge.evidence()).isEqualTo("s0");
        });
        GraphNeighbourhood.Edge candidate = subgraph.edges().stream()
                .filter(edge -> edge.type().crossPage()).findFirst().orElseThrow();
        assertThat(candidate.type()).isEqualTo(GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE);
        assertThat(candidate.certain()).isFalse();
        assertThat(candidate.from()).startsWith("wiki/1@");
        assertThat(candidate.to()).startsWith("handbuch/7@").isNotEqualTo(candidate.from());
        assertThat(subgraph.nodes()).extracting(GraphNeighbourhood.Node::id).doesNotHaveDuplicates();
        assertThat(subgraph.truncated()).isFalse();
    }

    @Test
    void aMentionFocusExpandsToItsStatementsAndTheMatchingMentionOnTheOtherPage() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");
        String beleg = mention("wiki", "1", "Beleg");

        GraphNeighbourhood.Subgraph subgraph = neighbourhood.node(CALLER, beleg, GraphNeighbourhood.Options.DEFAULT);

        assertThat(subgraph.focus()).isEqualTo(beleg);
        assertThat(subgraph.nodes()).extracting(GraphNeighbourhood.Node::kind)
                .contains(PageGraph.NodeKind.DOCUMENT, PageGraph.NodeKind.STATEMENT, PageGraph.NodeKind.MENTION);
        assertThat(subgraph.nodes()).filteredOn(node -> node.page().key().sourceId().equals("handbuch"))
                .singleElement().satisfies(node -> assertThat(node.label()).isEqualTo("Beleg"));
        assertThat(subgraph.edges()).filteredOn(edge -> edge.type().crossPage()).singleElement()
                .satisfies(edge -> assertThat(edge.origin()).isEqualTo("beleg"));
    }

    @Test
    void limitsCutTheProjectionAndSayHowManyNodesAreHidden() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg. Der Kunde prüft den Antrag.");

        GraphNeighbourhood.Subgraph subgraph = neighbourhood.page(CALLER, new PageKey("wiki", "1"),
                new GraphNeighbourhood.Options(3, 2, EnumSet.allOf(PageGraph.NodeKind.class),
                        EnumSet.allOf(GraphNeighbourhood.EdgeType.class), true, null, null));

        assertThat(subgraph.nodes()).hasSize(3);
        assertThat(subgraph.edges()).hasSizeLessThanOrEqualTo(2);
        assertThat(subgraph.truncated()).isTrue();
        assertThat(subgraph.hidden()).isPositive();
    }

    @Test
    void filtersHideKindsTypesAndStalePages() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        GraphNeighbourhood.Options statementsOnly = new GraphNeighbourhood.Options(50, 100,
                EnumSet.of(PageGraph.NodeKind.DOCUMENT, PageGraph.NodeKind.STATEMENT),
                EnumSet.of(GraphNeighbourhood.EdgeType.CONTAINS), true, null, null);

        GraphNeighbourhood.Subgraph subgraph = neighbourhood.page(CALLER, new PageKey("wiki", "1"), statementsOnly);
        assertThat(subgraph.nodes()).extracting(GraphNeighbourhood.Node::kind)
                .containsOnly(PageGraph.NodeKind.DOCUMENT, PageGraph.NodeKind.STATEMENT);
        assertThat(subgraph.edges()).allSatisfy(edge ->
                assertThat(edge.type()).isEqualTo(GraphNeighbourhood.EdgeType.CONTAINS));

        cache.put(new PageKey("wiki", "1"), EnrichmentTestPages.text("wiki", "1", "2", "Ganz neuer Text."));
        GraphNeighbourhood.Options currentOnly = new GraphNeighbourhood.Options(50, 100,
                EnumSet.allOf(PageGraph.NodeKind.class), EnumSet.allOf(GraphNeighbourhood.EdgeType.class), false,
                null, null);
        assertThat(neighbourhood.page(CALLER, new PageKey("wiki", "1"), currentOnly).nodes()).isEmpty();
        assertThat(neighbourhood.page(CALLER, new PageKey("wiki", "1"), GraphNeighbourhood.Options.DEFAULT)
                .nodes().getFirst().page().state()).isEqualTo(PageStatements.State.STALE);
    }

    @Test
    void lockedPagesRevealNoNodesEdgesOrHiddenCounts() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("geheim", "9", "Die Revision prüft den Beleg.");
        deniedSources.add("geheim");

        GraphNeighbourhood.Subgraph page = neighbourhood.page(CALLER, new PageKey("wiki", "1"),
                GraphNeighbourhood.Options.DEFAULT);
        GraphNeighbourhood.Subgraph term = neighbourhood.term(CALLER, "Beleg", GraphNeighbourhood.Options.DEFAULT);

        assertThat(page.nodes()).noneMatch(node -> node.page().key().sourceId().equals("geheim"));
        assertThat(page.edges()).noneMatch(edge -> edge.type().crossPage());
        assertThat(term.nodes()).noneMatch(node -> node.page().key().sourceId().equals("geheim"));
        assertThat(term.hidden()).isZero();
        assertThat(neighbourhood.page(CALLER, new PageKey("geheim", "9"), GraphNeighbourhood.Options.DEFAULT))
                .isEqualTo(GraphNeighbourhood.Subgraph.empty());
        assertThat(neighbourhood.node(CALLER, mentionUnchecked("geheim", "9"), GraphNeighbourhood.Options.DEFAULT))
                .isEqualTo(GraphNeighbourhood.Subgraph.empty());
    }

    @Test
    void aTermFocusCollectsMentionsWithTheirStatementsAcrossPages() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");

        GraphNeighbourhood.Subgraph subgraph = neighbourhood.term(CALLER, "beleg", GraphNeighbourhood.Options.DEFAULT);

        assertThat(subgraph.nodes()).filteredOn(node -> node.kind() == PageGraph.NodeKind.MENTION).hasSize(2);
        assertThat(subgraph.edges()).filteredOn(edge -> edge.type() == GraphNeighbourhood.EdgeType.AFFECTED_OBJECT)
                .hasSize(2);
        assertThat(subgraph.edges()).noneMatch(edge -> edge.type().crossPage());
    }

    @Test
    void aLongPageStillShowsParticipantsRoleEdgesAndCandidatesWithinTheDefaultLimits() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            text.append("Der Sachbearbeiter übermittelt den Antrag. ");
        }
        text.append("Die Buchhaltung prüft den Beleg.");
        analyse("wiki", "1", text.toString());
        analyse("handbuch", "7", "Der Kunde prüft den Beleg.");

        GraphNeighbourhood.Subgraph subgraph = neighbourhood.page(CALLER, new PageKey("wiki", "1"),
                GraphNeighbourhood.Options.DEFAULT);

        assertThat(subgraph.nodes()).hasSizeLessThanOrEqualTo(GraphNeighbourhood.DEFAULT_MAX_NODES);
        assertThat(subgraph.edges()).hasSizeLessThanOrEqualTo(GraphNeighbourhood.DEFAULT_MAX_EDGES);
        assertThat(subgraph.truncated()).isTrue();
        assertThat(subgraph.nodes()).filteredOn(node -> node.kind() == PageGraph.NodeKind.MENTION).isNotEmpty();
        assertThat(subgraph.edges()).anyMatch(edge -> edge.type().role());
        assertThat(subgraph.edges()).as("Verbindung zur anderen Seite trotz voller Seite")
                .anyMatch(edge -> edge.type() == GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE);
        assertThat(subgraph.edges()).allSatisfy(edge -> {
            assertThat(subgraph.nodes()).anyMatch(node -> node.id().equals(edge.from()));
            assertThat(subgraph.nodes()).anyMatch(node -> node.id().equals(edge.to()));
        });
    }

    @Test
    void hiddenCountsEachMissingNodeExactlyOnce() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg. Der Kunde prüft den Antrag.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg und den Antrag.");
        PageKey key = new PageKey("wiki", "1");
        GraphNeighbourhood.Options all = new GraphNeighbourhood.Options(GraphNeighbourhood.MAX_LIMIT,
                GraphNeighbourhood.MAX_LIMIT, EnumSet.allOf(PageGraph.NodeKind.class),
                EnumSet.allOf(GraphNeighbourhood.EdgeType.class), true, null, null);
        int total = neighbourhood.page(CALLER, key, all).nodes().size();

        for (int limit = 1; limit < total; limit++) {
            GraphNeighbourhood.Subgraph cut = neighbourhood.page(CALLER, key, new GraphNeighbourhood.Options(limit,
                    GraphNeighbourhood.MAX_LIMIT, all.kinds(), all.types(), true, null, null));
            assertThat(cut.nodes().size() + cut.hidden()).as("Grenze %d", limit).isEqualTo(total);
        }
    }

    @Test
    void linksRemovedByFiltersReserveNothingAndUnusedSpaceGoesToStatementsFirst() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append("Der Sachbearbeiter übermittelt den Antrag. ");
        }
        text.append("Die Buchhaltung prüft den Beleg.");
        analyse("wiki", "1", text.toString());
        analyse("handbuch", "7", "Der Kunde prüft den Beleg.");
        EnumSet<GraphNeighbourhood.EdgeType> noCrossPage = EnumSet.allOf(GraphNeighbourhood.EdgeType.class);
        noCrossPage.removeIf(GraphNeighbourhood.EdgeType::crossPage);

        GraphNeighbourhood.Subgraph onlyWiki = neighbourhood.page(CALLER, new PageKey("wiki", "1"),
                new GraphNeighbourhood.Options(50, 100, EnumSet.allOf(PageGraph.NodeKind.class),
                        EnumSet.allOf(GraphNeighbourhood.EdgeType.class), true, "wiki", null));
        GraphNeighbourhood.Subgraph withoutLinks = neighbourhood.page(CALLER, new PageKey("wiki", "1"),
                new GraphNeighbourhood.Options(50, 100, EnumSet.allOf(PageGraph.NodeKind.class), noCrossPage, true,
                        null, null));

        assertThat(onlyWiki.nodes()).extracting(GraphNeighbourhood.Node::id)
                .containsExactlyElementsOf(withoutLinks.nodes().stream().map(GraphNeighbourhood.Node::id).toList());
        assertThat(onlyWiki.nodes()).filteredOn(node -> node.kind() == PageGraph.NodeKind.STATEMENT)
                .as("Aussagen vor bloßen Erwähnungen").hasSizeGreaterThanOrEqualTo(16);
    }

    @Test
    void nominalChainsKeepAllTheirEdgesEvenWithoutStatements() {
        analyse("wiki", "1", "Antrag des Kunden des Amtes.");

        GraphNeighbourhood.Subgraph subgraph = neighbourhood.page(CALLER, new PageKey("wiki", "1"),
                GraphNeighbourhood.Options.DEFAULT);

        assertThat(subgraph.edges()).filteredOn(edge -> edge.type() == GraphNeighbourhood.EdgeType.NOMINAL)
                .hasSize(2);
    }

    @Test
    void sourceAndStaleFiltersApplyBeforeTheSearchLimit() {
        for (int i = 1; i <= 4; i++) {
            analyse("archiv", String.valueOf(i), "Die Buchhaltung prüft den Beleg.");
        }
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        GraphNeighbourhood.Options onlyWiki = new GraphNeighbourhood.Options(2, 100,
                EnumSet.allOf(PageGraph.NodeKind.class), EnumSet.allOf(GraphNeighbourhood.EdgeType.class), true,
                "wiki", null);

        GraphNeighbourhood.Subgraph subgraph = neighbourhood.term(CALLER, "Beleg", onlyWiki);

        assertThat(subgraph.focus()).startsWith("wiki/1@");
        assertThat(subgraph.nodes()).allMatch(node -> node.page().key().sourceId().equals("wiki"));
        assertThat(subgraph.hidden()).as("nur fehlende Knoten im gewählten Umfang").isLessThanOrEqualTo(1);
    }

    @Test
    void anEdgeFilterAlsoHidesTheNodesOnlyThatEdgeWouldReach() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");
        EnumSet<GraphNeighbourhood.EdgeType> noCrossPage = EnumSet.allOf(GraphNeighbourhood.EdgeType.class);
        noCrossPage.removeIf(GraphNeighbourhood.EdgeType::crossPage);
        GraphNeighbourhood.Options withoutLinks = new GraphNeighbourhood.Options(50, 100,
                EnumSet.allOf(PageGraph.NodeKind.class), noCrossPage, true, null, null);
        EnumSet<GraphNeighbourhood.EdgeType> noRoles = EnumSet.allOf(GraphNeighbourhood.EdgeType.class);
        noRoles.removeIf(GraphNeighbourhood.EdgeType::role);
        GraphNeighbourhood.Options withoutRoles = new GraphNeighbourhood.Options(50, 100,
                EnumSet.allOf(PageGraph.NodeKind.class), noRoles, true, null, null);

        GraphNeighbourhood.Subgraph page = neighbourhood.page(CALLER, new PageKey("wiki", "1"), withoutLinks);
        GraphNeighbourhood.Subgraph mention = neighbourhood.node(CALLER, mention("wiki", "1", "Beleg"), withoutRoles);
        GraphNeighbourhood.Subgraph term = neighbourhood.term(CALLER, "Beleg", withoutRoles);

        assertThat(page.nodes()).noneMatch(node -> node.page().key().sourceId().equals("handbuch"));
        assertThat(mention.nodes()).noneMatch(node -> node.kind() == PageGraph.NodeKind.STATEMENT);
        assertThat(term.nodes()).noneMatch(node -> node.kind() == PageGraph.NodeKind.STATEMENT);
    }

    @Test
    void aTermEdgeCarriesTheSameProvenanceAsInThePageView() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");

        GraphNeighbourhood.Edge fromTerm = neighbourhood.term(CALLER, "Beleg", GraphNeighbourhood.Options.DEFAULT)
                .edges().stream().filter(edge -> edge.type().role()).findFirst().orElseThrow();
        GraphNeighbourhood.Edge fromPage = neighbourhood.page(CALLER, new PageKey("wiki", "1"),
                GraphNeighbourhood.Options.DEFAULT).edges().stream()
                .filter(edge -> edge.from().equals(fromTerm.from()) && edge.to().equals(fromTerm.to()))
                .findFirst().orElseThrow();

        assertThat(fromTerm).isEqualTo(fromPage);
        assertThat(fromTerm.origin()).isEqualTo("PARSER");
        assertThat(fromTerm.relation()).isEqualTo("obj");
    }

    @Test
    void nodeIdsAreParsedIntoTheirPage() {
        assertThat(GraphNeighbourhood.pageOf("wiki/12@0123456789ab#s1.t2")).contains(new PageKey("wiki", "12"));
        assertThat(GraphNeighbourhood.pageOf("wiki/a/b@0123456789ab")).contains(new PageKey("wiki", "a/b"));
        assertThat(GraphNeighbourhood.pageOf("kaputt")).isEmpty();
        assertThat(GraphNeighbourhood.pageOf("/1@x")).isEmpty();
    }

    private String mention(String sourceId, String pageId, String text) {
        return store.find(new PageKey(sourceId, pageId)).orElseThrow().result().mentions().stream()
                .filter(mention -> mention.text().equals(text)).findFirst().orElseThrow().id();
    }

    private String mentionUnchecked(String sourceId, String pageId) {
        return store.find(new PageKey(sourceId, pageId)).orElseThrow().result().mentions().getFirst().id();
    }

    private void analyse(String sourceId, String pageId, String text) {
        PageSnapshot snapshot = EnrichmentTestPages.text(sourceId, pageId, "1", text);
        cache.put(snapshot.key(), snapshot);
        statements.analyze(CALLER, snapshot, () -> false);
    }
}
