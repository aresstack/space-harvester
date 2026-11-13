package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
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
    void deleteAllRemovesEveryReadableAnalysisIncludingDisabledSourcesAndKeepsTheRest() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.");
        analyse("handbuch", "7", "Die Buchhaltung prüft den Beleg.");
        analyse("geheim", "3", "Die Revision prüft den Beleg.");
        deniedSources.add("geheim");
        KnowledgeGraph withoutWiki = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
                (caller, term, sourceId) -> expansions.expansions(caller, term, sourceId),
                sourceId -> !sourceId.equals("wiki"));

        assertThat(withoutWiki.deleteAll(CALLER)).isEqualTo(2);

        assertThat(store.all()).extracting(analysis -> analysis.key().sourceId()).containsExactly("geheim");
        assertThat(graph().pages(CALLER)).isEmpty();
        assertThat(graph().search(CALLER, "Beleg", 50).hits()).isEmpty();
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
    void twoOwnTermsWithTheSameConfirmedTargetKeepAllTheirMentions() {
        analyse("wiki", "1", "Der Sachbearbeiter übermittelt den Beleg. Der Kunde prüft den Nachweis.");
        analyse("handbuch", "7", "Die Buchhaltung prüft die Quittung.");
        expansions = (caller, term, sourceId) -> term.equalsIgnoreCase("beleg") || term.equalsIgnoreCase("nachweis")
                ? List.of("Quittung") : List.of();

        assertThat(graph().links(CALLER, new PageKey("wiki", "1"))).singleElement().satisfies(link -> {
            assertThat(link.match()).isEqualTo(KnowledgeGraph.Match.CONFIRMED_TERM_RELATION);
            assertThat(link.mentions()).as("Beleg und Nachweis").hasSize(2);
        });
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

    @Test
    void anAttachmentWithItsOwnAnalysisIsADocumentOfItsPage() {
        // ADR 0026, Punkt 10 (AP9c): Herkunft mit DocumentKey, Rechte der Seite, Seiten unverändert
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        DocumentKey attachment = DocumentKey.attachment(new PageKey("wiki", "1"), "42");
        String input = analyseDocument(attachment, "Die Buchhaltung sendet den Beleg.");

        assertThat(store.all()).extracting(PageStatementAnalysis::document)
                .containsExactly(DocumentKey.page(new PageKey("wiki", "1")));
        assertThat(graph().pages(CALLER)).extracting(KnowledgeGraph.PageRef::document)
                .containsExactly(DocumentKey.page(new PageKey("wiki", "1")), attachment);
        assertThat(graph().page(CALLER, new PageKey("wiki", "1")).orElseThrow().page().document().isPage()).isTrue();
        // Ohne Lesetext des Dokuments gilt seine Analyse nie stillschweigend als aktuell.
        assertThat(graph().page(CALLER, attachment).orElseThrow().page()).satisfies(ref -> {
            assertThat(ref.key()).isEqualTo(new PageKey("wiki", "1"));
            assertThat(ref.state()).isEqualTo(PageStatements.State.STALE);
            assertThat(ref.reason()).isEqualTo(PageStatements.NO_TEXT);
        });
        assertThat(graph(document -> document.equals(attachment) ? Optional.of(input) : Optional.empty())
                .page(CALLER, attachment).orElseThrow().page().state()).isEqualTo(PageStatements.State.CURRENT);

        KnowledgeGraph.Search search = graph().search(CALLER, "Beleg", 50);
        assertThat(search.hits()).extracting(hit -> hit.page().document())
                .containsExactly(DocumentKey.page(new PageKey("wiki", "1")), attachment);
        assertThat(search.hits().get(1).mention().id()).startsWith("wiki/1@").contains("~" + attachment.fileName());
        // Seite und eigener Anhang sind verschiedene Dokumente: der gleiche Begriff verbindet sie als Kandidat.
        assertThat(graph().links(CALLER, new PageKey("wiki", "1"))).singleElement()
                .satisfies(link -> assertThat(link.target().document()).isEqualTo(attachment));
    }

    @Test
    void aDocumentHasTheRightsOfItsPageAndGoesWithDeleteAll() {
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        analyseDocument(DocumentKey.attachment(new PageKey("wiki", "1"), "42"), "Die Buchhaltung sendet den Beleg.");
        analyse("geheim", "3", "Die Revision prüft den Beleg.");
        DocumentKey secret = DocumentKey.attachment(new PageKey("geheim", "3"), "7");
        analyseDocument(secret, "Die Revision sendet den Beleg.");
        deniedSources.add("geheim");

        assertThat(graph().page(CALLER, secret)).isEmpty();
        assertThat(graph().search(CALLER, "Revision", 50).hits()).isEmpty();

        assertThat(graph().deleteAll(CALLER)).isEqualTo(2);

        assertThat(store.all()).extracting(analysis -> analysis.key().sourceId()).containsExactly("geheim");
        assertThat(store.documents()).extracting(PageStatementAnalysis::document).containsExactly(secret);
    }

    @Test
    void aNodeOfADocumentIsFoundAgainByItsId() {
        analyse("wiki", "1", "Der Sachbearbeiter prüft den Beleg.");
        DocumentKey attachment = DocumentKey.attachment(new PageKey("wiki", "1"), "42");
        analyseDocument(attachment, "Die Buchhaltung sendet den Beleg.");
        String mention = graph().page(CALLER, attachment).orElseThrow().analysis().result().mentions().getFirst().id();

        assertThat(GraphNeighbourhood.pageOf(mention)).contains(new PageKey("wiki", "1"));
        assertThat(GraphNeighbourhood.documentOf(mention)).contains(attachment.fileName());
        assertThat(GraphNeighbourhood.documentOf(graph().page(CALLER, new PageKey("wiki", "1")).orElseThrow()
                .analysis().result().mentions().getFirst().id())).isEmpty();
        GraphNeighbourhood.Subgraph around = new GraphNeighbourhood(graph())
                .node(CALLER, mention, GraphNeighbourhood.Options.DEFAULT);
        assertThat(around.focus()).isEqualTo(mention);
        assertThat(around.nodes()).filteredOn(node -> node.id().equals(mention)).singleElement()
                .satisfies(node -> assertThat(node.page().document()).isEqualTo(attachment));
    }

    /** Legt eine Analyse eines Anhangs ab, wie es ein späterer Auftrag täte; gibt den Eingabe-Hash zurück. */
    private String analyseDocument(DocumentKey document, String text) {
        String input = "sha256:" + Integer.toHexString(text.hashCode()) + "0".repeat(56);
        ParsedStatements result = parser.analyze(PageStatementAnalysis.documentId(document, input), text, () -> false);
        store.replace(new PageStatementAnalysis(document.page(), null, "Anhang", "sha256:" + "c".repeat(64), input,
                parser.id(), parser.version(), java.time.Instant.parse("2026-11-13T12:00:00Z"), result, document));
        return input;
    }

    private KnowledgeGraph graph(java.util.function.Function<DocumentKey, Optional<String>> documentInputs) {
        return new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
                snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
                (caller, term, sourceId) -> expansions.expansions(caller, term, sourceId), sourceId -> true,
                documentInputs);
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
