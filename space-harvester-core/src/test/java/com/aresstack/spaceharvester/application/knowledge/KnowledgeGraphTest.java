package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeGraphTest {

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
    private KnowledgeGraph.TermExpansion expansions = KnowledgeGraph.TermExpansion.NONE;

    @Test
    void twoPagesFromDifferentSourcesAreSearchableTogetherWithSeparateEvidence() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");

        KnowledgeGraph.Search search = graph().search(CALLER, "Beleg", 50);

        assertThat(search.total()).isEqualTo(2);
        assertThat(search.pages()).isEqualTo(2);
        assertThat(search.hits()).extracting(hit -> hit.page().key().sourceId()).containsExactly("handbuch", "wiki");
        assertThat(search.hits()).extracting(hit -> hit.mention().id()).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).contains("@"));
        assertThat(search.hits()).allSatisfy(hit -> {
            assertThat(hit.match()).isEqualTo(KnowledgeGraph.Match.LEXICAL);
            assertThat(hit.statements()).singleElement().satisfies(participation -> assertThat(participation.role())
                    .isEqualTo(ParsedStatements.Role.AFFECTED_OBJECT));
            assertThat(hit.page().state()).isEqualTo(PageStatements.State.CURRENT);
        });
    }

    @Test
    void statementsOfADisabledSourceStayStoredButInvisible() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");
        KnowledgeGraph withoutWiki = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
                (caller, term, sourceId) -> expansions.expansions(caller, term, sourceId),
                sourceId -> !sourceId.equals("wiki"));

        assertThat(withoutWiki.search(CALLER, "Beleg", 50).hits()).extracting(hit -> hit.page().key().sourceId())
                .containsExactly("handbuch");
        assertThat(withoutWiki.pages(CALLER)).extracting(page -> page.key().sourceId()).containsExactly("handbuch");
        assertThat(store.all()).hasSize(2);
        assertThat(graph().pages(CALLER)).hasSize(2);
    }

    @Test
    void theSameWordOnTwoPagesIsOnlyALexicalCandidateNeverAnIdentity() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");

        List<KnowledgeGraph.Link> links = graph().links(CALLER, new PageKey("wiki", "1"));

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.term()).isEqualTo("beleg");
            assertThat(link.match()).isEqualTo(KnowledgeGraph.Match.LEXICAL);
            assertThat(link.target().key()).isEqualTo(new PageKey("handbuch", "7"));
            assertThat(link.mentions()).singleElement().asString().startsWith("wiki/1@");
            assertThat(link.targetMentions()).singleElement().asString().startsWith("handbuch/7@");
        });
        // Beide Erwähnungen bleiben eigene Knoten; es gibt keine Kante zwischen den Seiten.
        assertThat(graph().page(CALLER, new PageKey("wiki", "1")).orElseThrow().graph().edges())
                .noneMatch(edge -> edge.to().startsWith("handbuch/"));
    }

    @Test
    void pronounsStayUnresolvedAndConnectNothing() {
        analyse("wiki", "1", "Der Sachbearbeiter prüft ihn.");
        analyse("handbuch", "7", "Die Buchhaltung sendet ihn.");

        assertThat(graph().links(CALLER, new PageKey("wiki", "1"))).isEmpty();
        assertThat(graph().search(CALLER, "ihn", 10).total()).isZero();
        assertThat(store.find(new PageKey("wiki", "1")).orElseThrow().result().statements()).singleElement()
                .satisfies(statement -> assertThat(statement.findings()).contains("UNRESOLVED_PRONOUN"));
    }

    @Test
    void aConfirmedTermRelationConnectsDifferentWordsAsSuch() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft die Quittung.");
        expansions = (caller, term, sourceId) -> term.equalsIgnoreCase("beleg") ? List.of("Quittung") : List.of();

        KnowledgeGraph.Search search = graph().search(CALLER, "Beleg", 10);

        assertThat(search.hits()).extracting(KnowledgeGraph.Hit::match)
                .containsExactly(KnowledgeGraph.Match.CONFIRMED_TERM_RELATION, KnowledgeGraph.Match.LEXICAL);
        assertThat(graph().links(CALLER, new PageKey("wiki", "1"))).singleElement()
                .satisfies(link -> assertThat(link.match()).isEqualTo(KnowledgeGraph.Match.CONFIRMED_TERM_RELATION));
    }

    @Test
    void aLockedSourceRevealsNoNodesStatementsOrCounts() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("geheim", "9", "Die Revision prüft den Beleg.");
        deniedSources.add("geheim");
        KnowledgeGraph graph = graph();

        assertThat(graph.search(CALLER, "Beleg", 10).total()).isEqualTo(1);
        assertThat(graph.search(CALLER, "Revision", 10).total()).isZero();
        assertThat(graph.search(CALLER, "Revision", 10).pages()).isZero();
        assertThat(graph.links(CALLER, new PageKey("wiki", "1"))).isEmpty();
        assertThat(graph.links(CALLER, new PageKey("geheim", "9"))).isEmpty();
        assertThat(graph.page(CALLER, new PageKey("geheim", "9"))).isEmpty();
        assertThat(graph.pages(CALLER)).extracting(KnowledgeGraph.PageRef::key)
                .containsExactly(new PageKey("wiki", "1"));
    }

    @Test
    void reAnalysingOnePageReplacesOnlyItsProjection() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");
        PageStatementAnalysis other = store.find(new PageKey("handbuch", "7")).orElseThrow();

        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Antrag.");

        KnowledgeGraph graph = graph();
        assertThat(store.find(new PageKey("handbuch", "7"))).contains(other);
        assertThat(graph.search(CALLER, "Beleg", 10).hits()).extracting(hit -> hit.page().key().sourceId())
                .containsExactly("handbuch");
        assertThat(graph.search(CALLER, "Antrag", 10).total()).isEqualTo(1);
        assertThat(graph.page(CALLER, new PageKey("wiki", "1")).orElseThrow().graph().nodes())
                .extracting(PageGraph.Node::label).doesNotContain("Beleg");
    }

    @Test
    void aChangedOrRemovedPageIsShownAsStaleNotAsCurrentFact() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");
        cache.put(new PageKey("wiki", "1"), page("wiki", "1", "4", "Der Sachbearbeiter übermittelt nichts mehr."));
        cache.remove(new PageKey("handbuch", "7"));

        KnowledgeGraph.Search search = graph().search(CALLER, "Beleg", 10);

        assertThat(search.hits()).extracting(hit -> hit.page().reason())
                .containsExactly(PageStatements.PAGE_MISSING, PageStatements.TEXT_CHANGED);
        assertThat(search.hits()).allSatisfy(hit -> assertThat(hit.page().state())
                .isEqualTo(PageStatements.State.STALE));
        assertThat(parser.calls).hasValue(2);
    }

    private void analyse(String sourceId, String pageId, String text) {
        PageSnapshot snapshot = page(sourceId, pageId, "1", text);
        cache.put(snapshot.key(), snapshot);
        statements.analyze(CALLER, snapshot, () -> false);
    }

    private KnowledgeGraph graph() {
        return new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
                (caller, term, sourceId) -> expansions.expansions(caller, term, sourceId));
    }

    private static PageSnapshot page(String sourceId, String pageId, String version, String text) {
        return EnrichmentTestPages.text(sourceId, pageId, version, text);
    }
}
