package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Reiter „Analysen → Graph“ ohne Core: Laden, Erweitern, Zurück, Tabelle, Sprung, Seitenwechsel (#499 F). */
class KnowledgeGraphTabTest {

    private static final PageKey WIKI = new PageKey("wiki", "1");
    private static final KnowledgeGraph.PageRef WIKI_REF = new KnowledgeGraph.PageRef(WIKI, "DEV", "Seite 1",
            PageStatements.State.CURRENT, null);
    private static final KnowledgeGraph.PageRef OTHER_REF = new KnowledgeGraph.PageRef(new PageKey("handbuch", "7"),
            "OPS", "Belege", PageStatements.State.STALE, PageStatements.TEXT_CHANGED);
    private static final String DOC = "wiki/1@0123456789ab";
    private static final String STATEMENT = DOC + "#s1.v3";
    private static final String BELEG = DOC + "#s1.t10";
    private static final String OTHER = "handbuch/7@abcdefabcdef#s1.t4";

    private final FakeActions actions = new FakeActions();
    private final DeferredRunner runner = new DeferredRunner();
    private final KnowledgeGraphTab tab = new KnowledgeGraphTab(() -> actions, runner);

    @Test
    void thePageIsLoadedOnlyWhenAskedAndShowsNodesEdgesAndCandidates() {
        tab.page(WIKI);
        runner.runAll();
        assertThat(actions.calls).as("unsichtbarer Reiter lädt nichts").isEmpty();

        tab.showPageNow();
        runner.runAll();

        assertThat(actions.calls).containsExactly("page wiki/1");
        assertThat(tab.nodeIds()).containsExactly(DOC, STATEMENT, BELEG);
        assertThat(tab.edgeCount()).isEqualTo(3);
        assertThat(tab.statusText()).startsWith("3 Knoten, 3 Kanten");
        assertThat(tab.tableRows()).isEqualTo(3);
        assertThat(tab.tableValue(1, 1)).isEqualTo("Betroffen");
        assertThat(tab.tableValue(1, 4)).isEqualTo("sicher");
    }

    @Test
    void expandingAddsNeighboursAndBackRestoresTheView() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();

        tab.selectNode(BELEG);
        tab.clickExpand();
        runner.runAll();

        assertThat(actions.calls).containsExactly("page wiki/1", "node " + BELEG);
        assertThat(tab.nodeIds()).contains(DOC, BELEG, OTHER);
        assertThat(tab.statusText()).contains("1 veraltete Seite(n)").contains("gekürzt, 4 weitere Knoten");
        int candidateRow = -1;
        for (int i = 0; i < tab.tableRows(); i++) {
            if (String.valueOf(tab.tableValue(i, 1)).contains("Kandidat")) {
                candidateRow = i;
            }
        }
        assertThat(candidateRow).isNotNegative();
        assertThat(tab.tableValue(candidateRow, 6)).isEqualTo("veraltet");

        tab.selectNode(OTHER);
        assertThat(tab.detailsText()).contains("Erwähnung: Beleg")
                .contains("Veraltet (text-changed)").contains("gleiche Grundform?");

        tab.clickBack();
        assertThat(tab.nodeIds()).containsExactly(DOC, STATEMENT, BELEG);
    }

    @Test
    void onlyNodesOfTheOpenCurrentPageJumpToTheReaderText() {
        List<List<TermsTab.Span>> jumps = new ArrayList<>();
        tab.onJump(jumps::add);
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();

        tab.selectNode(BELEG);
        assertThat(tab.jumpOffered()).isTrue();
        tab.clickJump();
        assertThat(jumps).containsExactly(List.of(new TermsTab.Span(58, 67)));

        tab.selectNode(DOC);
        assertThat(tab.jumpOffered()).isFalse();
    }

    @Test
    void aLateResultOfALeftPageIsDiscarded() {
        tab.page(WIKI);
        tab.showPageNow();
        tab.page(new PageKey("wiki", "2"));
        runner.runAll();

        assertThat(tab.nodeIds()).isEmpty();
    }

    @Test
    void aTermWithoutHitsSaysSoAndOptionsCarryTheFilters() {
        actions.empty = true;
        tab.page(WIKI);
        tab.searchTerm("Unbekannt");
        runner.runAll();

        assertThat(tab.nodeIds()).isEmpty();
        assertThat(tab.detailsText()).isEqualTo(KnowledgeGraphTab.EMPTY_TERM);
        tab.setShowStale(false);
        GraphNeighbourhood.Options options = tab.options();
        assertThat(options.includeStale()).isFalse();
        assertThat(options.maxNodes()).isEqualTo(GraphNeighbourhood.DEFAULT_MAX_NODES);
        assertThat(options.types()).contains(GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE);
    }

    private static final class FakeActions implements KnowledgeGraphTab.Actions {

        final List<String> calls = new ArrayList<>();
        boolean empty;

        @Override
        public GraphNeighbourhood.Subgraph page(PageKey key, GraphNeighbourhood.Options options) {
            calls.add("page " + key.sourceId() + "/" + key.pageId());
            GraphNeighbourhood.Node doc = new GraphNeighbourhood.Node(DOC, PageGraph.NodeKind.DOCUMENT, "Seite 1", null,
                    WIKI_REF, -1, -1, false);
            GraphNeighbourhood.Node statement = new GraphNeighbourhood.Node(STATEMENT, PageGraph.NodeKind.STATEMENT,
                    "übermittelt", "übermitteln", WIKI_REF, 19, 30, true);
            GraphNeighbourhood.Node beleg = new GraphNeighbourhood.Node(BELEG, PageGraph.NodeKind.MENTION, "den Beleg",
                    "Beleg", WIKI_REF, 58, 67, false);
            return new GraphNeighbourhood.Subgraph(DOC, List.of(doc, statement, beleg), List.of(
                    new GraphNeighbourhood.Edge(DOC, STATEMENT, GraphNeighbourhood.EdgeType.CONTAINS, "STRUCTURE", null,
                            null, null, true),
                    new GraphNeighbourhood.Edge(STATEMENT, BELEG, GraphNeighbourhood.EdgeType.AFFECTED_OBJECT, "PARSER",
                            "obj", null, "s1", true),
                    new GraphNeighbourhood.Edge(DOC, BELEG, GraphNeighbourhood.EdgeType.CONTAINS, "STRUCTURE", null,
                            null, null, true)), false, 0);
        }

        @Override
        public GraphNeighbourhood.Subgraph node(String nodeId, GraphNeighbourhood.Options options) {
            calls.add("node " + nodeId);
            GraphNeighbourhood.Node beleg = new GraphNeighbourhood.Node(BELEG, PageGraph.NodeKind.MENTION, "den Beleg",
                    "Beleg", WIKI_REF, 58, 67, false);
            GraphNeighbourhood.Node other = new GraphNeighbourhood.Node(OTHER, PageGraph.NodeKind.MENTION, "Beleg",
                    "Beleg", OTHER_REF, 20, 25, false);
            return new GraphNeighbourhood.Subgraph(BELEG, List.of(beleg, other), List.of(
                    new GraphNeighbourhood.Edge(BELEG, OTHER, GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE, "beleg",
                            null, null, null, false)), true, 4);
        }

        @Override
        public GraphNeighbourhood.Subgraph term(String term, GraphNeighbourhood.Options options) {
            calls.add("term " + term);
            return GraphNeighbourhood.Subgraph.empty();
        }
    }

    private static final class DeferredRunner implements KnowledgeGraphTab.Runner {

        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            pending.add(() -> {
                T result;
                try {
                    result = work.call();
                } catch (Throwable e) {
                    onFailure.accept(e);
                    return;
                }
                onSuccess.accept(result);
            });
        }

        void runAll() {
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
            }
        }
    }
}
