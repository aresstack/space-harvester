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
    private final DomainTermGraph fachsicht = new DomainTermGraph(graph, new DomainTermGraph.Vocabulary() {
        @Override
        public List<DomainTermGraph.Term> terms(AccessContext caller) {
            return terms;
        }

        @Override
        public List<DomainTermGraph.Synonym> synonyms(AccessContext caller) {
            return synonyms;
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
        analyse("wiki", "1", "Er prüft den Beleg.");

        assertThat(around("Beleg").edges()).isEmpty();
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
