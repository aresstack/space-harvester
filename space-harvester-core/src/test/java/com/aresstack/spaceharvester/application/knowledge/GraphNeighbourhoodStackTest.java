package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Seitengraph über eine Seite samt Unterseiten (Checkbox „mit Unterseiten“), erfundene Testseiten. */
class GraphNeighbourhoodStackTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    private static final PageKey PAGE = new PageKey("wiki", "1");
    private static final PageKey ALPHA = new PageKey("wiki", "12");
    private static final PageKey BETA = new PageKey("wiki", "11");

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final Set<String> deniedSources = new HashSet<>();
    /** Seiten, deren Metadaten der Aufrufer sehen, deren Inhalt er aber nicht lesen darf. */
    private final Set<String> unreadablePages = new HashSet<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) ->
            deniedSources.contains(scope.sourceId())
                    || kind == AccessKind.CONTENT && unreadablePages.contains(scope.pageId())
                    ? AccessDecision.DENY : AccessDecision.ALLOW);
    private final StatementAnalysis statements = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store, access, () -> parser,
            StatementAnalysisTest.clock());
    private final KnowledgeGraph graph = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
            KnowledgeGraph.TermExpansion.NONE);
    private final GraphNeighbourhood neighbourhood = new GraphNeighbourhood(graph);

    @Test
    void thePageAndItsSubpagesFormOneGraphWithSubpageEdgesAndTheirOriginPages() {
        analyseFamily();

        GraphNeighbourhood.Stack stack = neighbourhood.pages(CALLER, PAGE, below(ALPHA, BETA),
                GraphNeighbourhood.Options.DEFAULT);
        GraphNeighbourhood.Subgraph subgraph = stack.graph();

        assertThat(subgraph.focus()).startsWith("wiki/1@");
        assertThat(subgraph.nodes().subList(0, 3)).as("Dokumente zuerst, Unterseiten nach Titel ohne Großschreibung")
                .allMatch(node -> node.kind() == PageGraph.NodeKind.DOCUMENT)
                .extracting(node -> node.page().key()).containsExactly(PAGE, ALPHA, BETA);
        assertThat(subgraph.edges()).filteredOn(edge -> edge.type() == GraphNeighbourhood.EdgeType.SUBPAGE)
                .hasSize(2).allSatisfy(edge -> {
                    assertThat(edge.from()).isEqualTo(subgraph.focus());
                    assertThat(edge.origin()).isEqualTo("STRUCTURE");
                    assertThat(edge.evidence()).isNull();
                });
        for (PageKey key : List.of(PAGE, ALPHA, BETA)) {
            assertThat(subgraph.nodes()).as("Aussagen von %s mit Herkunftsseite", key)
                    .anyMatch(node -> node.kind() == PageGraph.NodeKind.STATEMENT && node.page().key().equals(key));
        }
        assertThat(subgraph.edges()).filteredOn(edge -> edge.type().role()).allSatisfy(edge ->
                assertThat(GraphNeighbourhood.pageOf(edge.from())).isEqualTo(GraphNeighbourhood.pageOf(edge.to())));
        assertThat(subgraph.nodes()).extracting(GraphNeighbourhood.Node::id).doesNotHaveDuplicates();
        assertThat(subgraph.truncated()).isFalse();
        assertThat(stack.missing()).isEmpty();
        assertThat(stack.skippedStale()).isZero();
        assertThat(stack.considered()).isEqualTo(2);
        assertThat(GraphNeighbourhood.EdgeType.SUBPAGE.role()).isFalse();
        assertThat(GraphNeighbourhood.EdgeType.SUBPAGE.crossPage()).isFalse();
    }

    @Test
    void theLimitsApplyToTheWholeSetAndTheSubpageDocumentsComeBeforeTheStatements() {
        analyseFamily();
        PageSelection subpages = below(ALPHA, BETA);

        // Grenze 4: vorab nur max(1, 4 / 4) = 1 Dokument einer Unterseite, dann die Kernaussage der Seite.
        GraphNeighbourhood.Subgraph cut = neighbourhood.pages(CALLER, PAGE, subpages, limits(4)).graph();

        assertThat(cut.nodes()).hasSize(4);
        assertThat(cut.nodes().subList(0, 2)).allMatch(node -> node.kind() == PageGraph.NodeKind.DOCUMENT)
                .extracting(node -> node.page().key()).containsExactly(PAGE, ALPHA);
        assertThat(cut.nodes().get(2).kind()).isEqualTo(PageGraph.NodeKind.STATEMENT);
        assertThat(cut.nodes().get(2).page().key()).as("Kernaussage der Seite vor denen der Unterseiten")
                .isEqualTo(PAGE);
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.hidden()).isPositive();

        // Je Aussage zwei Beteiligte: drei Dokumente, die zwei Kernaussagen der Seite, eine der ersten Unterseite.
        GraphNeighbourhood.Subgraph fair = neighbourhood.pages(CALLER, PAGE, subpages, limits(12)).graph();
        assertThat(fair.nodes()).filteredOn(node -> node.kind() == PageGraph.NodeKind.STATEMENT)
                .as("Kernaussagen der Seite, dann die der Unterseiten nach Titel")
                .allMatch(GraphNeighbourhood.Node::core)
                .extracting(node -> node.page().key()).containsExactly(PAGE, PAGE, ALPHA);
        assertThat(fair.nodes()).filteredOn(node -> node.kind() == PageGraph.NodeKind.MENTION).hasSize(6);

        int total = neighbourhood.pages(CALLER, PAGE, subpages, limits(GraphNeighbourhood.MAX_LIMIT)).graph()
                .nodes().size();
        for (int limit = 1; limit < total; limit++) {
            GraphNeighbourhood.Subgraph part = neighbourhood.pages(CALLER, PAGE, subpages, limits(limit)).graph();
            assertThat(part.nodes().size() + part.hidden()).as("Grenze %d", limit).isEqualTo(total);
        }
    }

    /**
     * Architektenvorgabe W4 a: „ohne Analyse“ nennt nur Seiten, die der Aufrufer lesen darf und die nicht gesperrt
     * sind. Gesperrte und nicht lesbare Unterseiten erscheinen weder als Knoten noch dort noch in einem Zähler – auch
     * wenn eine Analyse von früher vorliegt.
     */
    @Test
    void onlyReadableUnlockedSubpagesWithoutAnalysisAreListedAsMissing() {
        analyseFamily();
        PageKey unanalysed = new PageKey("wiki", "13");
        PageKey locked = new PageKey("wiki", "14");
        PageKey lockedUnanalysed = new PageKey("wiki", "15");
        PageKey unreadable = new PageKey("wiki", "16");
        PageKey unreadableUnanalysed = new PageKey("wiki", "17");
        analyse("wiki", "14", "Gesperrt", "Die Revision prüft den Beleg.");
        cache.remove(locked); // gesperrte Seiten haben kein Seitendokument mehr
        analyse("wiki", "16", "Vertraulich", "Der Vorstand genehmigt das Budget.");
        unreadablePages.addAll(List.of("16", "17"));

        GraphNeighbourhood.Stack stack = neighbourhood.pages(CALLER, PAGE,
                below(List.of(ALPHA, unanalysed, locked, lockedUnanalysed, unreadable, unreadableUnanalysed),
                        List.of(locked, lockedUnanalysed)), GraphNeighbourhood.Options.DEFAULT);

        assertThat(stack.missing()).containsExactly(unanalysed);
        assertThat(stack.considered()).as("ALPHA und die nicht analysierte, lesbare Unterseite").isEqualTo(2);
        assertThat(stack.skippedStale()).isZero();
        assertThat(stack.graph().nodes()).noneMatch(node -> List.of(locked, unreadable).contains(node.page().key()))
                .anyMatch(node -> node.page().key().equals(ALPHA));
        assertThat(stack.graph().hidden()).isZero();

        // Ohne „Veraltete zeigen“ zählt die gesperrte (sonst als veraltet geltende) Seite auch dort nicht.
        GraphNeighbourhood.Options currentOnly = new GraphNeighbourhood.Options(50, 100,
                EnumSet.allOf(PageGraph.NodeKind.class), EnumSet.allOf(GraphNeighbourhood.EdgeType.class), false,
                null, null);
        assertThat(neighbourhood.pages(CALLER, PAGE, below(List.of(ALPHA, locked), List.of(locked)), currentOnly)
                .skippedStale()).isZero();

        GraphNeighbourhood.Stack headless = neighbourhood.pages(CALLER, new PageKey("wiki", "99"),
                below(ALPHA), GraphNeighbourhood.Options.DEFAULT);
        assertThat(headless.missing()).containsExactly(new PageKey("wiki", "99"));
        assertThat(headless.graph().focus()).isNull();
        assertThat(headless.graph().nodes()).isNotEmpty().allMatch(node -> node.page().key().equals(ALPHA));
    }

    @Test
    void staleSubpagesAreDroppedAndCountedOrShownAsStaleLikeInThePageView() {
        analyseFamily();
        cache.put(ALPHA, EnrichmentTestPages.text("wiki", "12", "2", "Ganz neuer Text."));
        PageSelection subpages = below(ALPHA, BETA);
        GraphNeighbourhood.Options currentOnly = new GraphNeighbourhood.Options(50, 100,
                EnumSet.allOf(PageGraph.NodeKind.class), EnumSet.allOf(GraphNeighbourhood.EdgeType.class), false,
                null, null);

        GraphNeighbourhood.Stack hidden = neighbourhood.pages(CALLER, PAGE, subpages, currentOnly);
        assertThat(hidden.skippedStale()).isEqualTo(1);
        assertThat(hidden.considered()).isEqualTo(2);
        assertThat(hidden.missing()).isEmpty();
        assertThat(hidden.graph().nodes()).noneMatch(node -> node.page().key().equals(ALPHA))
                .anyMatch(node -> node.page().key().equals(BETA));

        GraphNeighbourhood.Stack shown = neighbourhood.pages(CALLER, PAGE, subpages,
                GraphNeighbourhood.Options.DEFAULT);
        assertThat(shown.skippedStale()).isZero();
        assertThat(shown.graph().nodes()).filteredOn(node -> node.page().key().equals(ALPHA)).isNotEmpty()
                .allMatch(node -> node.page().state() == PageStatements.State.STALE);
    }

    @Test
    void linksStayWithinTheSetAndAppearOncePerPair() {
        analyse("wiki", "1", "Hauptseite", "Der Sachbearbeiter übermittelt den Beleg.");
        analyse("wiki", "12", "alpha", "Die Prüferin prüft den Beleg.");
        analyse("handbuch", "7", "Handbuch", "Die Buchhaltung prüft den Beleg.");
        PageSelection subpages = below(ALPHA);

        GraphNeighbourhood.Subgraph subgraph = neighbourhood.pages(CALLER, PAGE, subpages,
                GraphNeighbourhood.Options.DEFAULT).graph();

        assertThat(subgraph.nodes()).noneMatch(node -> node.page().key().sourceId().equals("handbuch"));
        List<GraphNeighbourhood.Edge> links = subgraph.edges().stream().filter(edge -> edge.type().crossPage())
                .toList();
        assertThat(links).singleElement().satisfies(edge -> {
            assertThat(GraphNeighbourhood.pageOf(edge.from())).contains(PAGE);
            assertThat(GraphNeighbourhood.pageOf(edge.to())).contains(ALPHA);
        });
    }

    @Test
    void withoutSubpagesTheStackIsExactlyThePageGraph() {
        analyse("wiki", "1", "Hauptseite", "Der Sachbearbeiter übermittelt dem Vorgesetzten den Beleg.");
        analyse("handbuch", "7", "Handbuch", "Die Buchhaltung prüft den Beleg.");
        GraphNeighbourhood.Subgraph page = neighbourhood.page(CALLER, PAGE, GraphNeighbourhood.Options.DEFAULT);

        PageKey locked = new PageKey("wiki", "14");
        for (PageSelection none : new PageSelection[] {null, below(), below(PAGE),
                below(List.of(locked), List.of(locked))}) {
            GraphNeighbourhood.Stack stack = neighbourhood.pages(CALLER, PAGE, none,
                    GraphNeighbourhood.Options.DEFAULT);
            assertThat(stack.graph()).isEqualTo(page);
            assertThat(stack.missing()).isEmpty();
            assertThat(stack.skippedStale()).isZero();
            assertThat(stack.considered()).isZero();
        }
        assertThat(page.edges()).as("Verbindung zu anderen Seiten wie bisher").anyMatch(edge -> edge.type().crossPage());
    }

    @Test
    void subpagesOutsideTheSourceFilterOrAnEarlierPageSetAreSkippedSilently() {
        analyseFamily();
        analyse("archiv", "3", "Archiv", "Der Kunde prüft den Antrag.");
        PageKey archive = new PageKey("archiv", "3");
        GraphNeighbourhood.Options onlyWiki = new GraphNeighbourhood.Options(50, 100,
                EnumSet.allOf(PageGraph.NodeKind.class), EnumSet.allOf(GraphNeighbourhood.EdgeType.class), true,
                "wiki", null);

        GraphNeighbourhood.Stack bySource = neighbourhood.pages(CALLER, PAGE,
                below(ALPHA, archive, new PageKey("archiv", "4")), onlyWiki);
        assertThat(bySource.missing()).isEmpty();
        assertThat(bySource.considered()).isEqualTo(1);
        assertThat(bySource.graph().nodes()).noneMatch(node -> node.page().key().equals(archive))
                .anyMatch(node -> node.page().key().equals(ALPHA));

        GraphNeighbourhood.Stack bySelection = neighbourhood.pages(CALLER, PAGE, below(ALPHA, BETA),
                GraphNeighbourhood.Options.DEFAULT.within(PageSet.ofKeys(List.of(PAGE, BETA))));
        assertThat(bySelection.graph().nodes()).noneMatch(node -> node.page().key().equals(ALPHA))
                .anyMatch(node -> node.page().key().equals(BETA));
        assertThat(bySelection.missing()).isEmpty();
        assertThat(bySelection.considered()).as("nur die Unterseite in der Auswahl im Baum").isEqualTo(1);
    }

    /**
     * Viele Unterseiten (Space-Wurzel, Übersichtsseite): vorab höchstens ein Viertel der Grenze an Dokumenten von
     * Unterseiten, die übrigen ausgeblendet gezählt; die Kernaussagen der Seite stehen trotzdem im Bild.
     */
    @Test
    void manySubpagesLeaveRoomForTheCoreStatementsOfThePage() {
        analyse("wiki", "1", "Hauptseite",
                "Der Sachbearbeiter übermittelt den Beleg. Der Vorgesetzte genehmigt den Urlaub.");
        List<PageKey> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String pageId = String.valueOf(100 + i);
            analyse("wiki", pageId, "Unterseite " + pageId, "Die Prüferin prüft den Antrag " + pageId + ".");
            keys.add(new PageKey("wiki", pageId));
        }
        GraphNeighbourhood.Options options = new GraphNeighbourhood.Options(50, 100,
                EnumSet.allOf(PageGraph.NodeKind.class), EnumSet.allOf(GraphNeighbourhood.EdgeType.class), true,
                null, null);

        GraphNeighbourhood.Stack stack = neighbourhood.pages(CALLER, PAGE, below(keys, List.of()), options);
        GraphNeighbourhood.Subgraph subgraph = stack.graph();

        assertThat(subgraph.nodes()).hasSizeLessThanOrEqualTo(50);
        assertThat(subgraph.nodes()).as("Kernaussagen der Seite trotz 60 Unterseiten")
                .filteredOn(node -> node.kind() == PageGraph.NodeKind.STATEMENT && node.page().key().equals(PAGE))
                .hasSize(2).allMatch(GraphNeighbourhood.Node::core);
        int head = subgraph.nodes().indexOf(subgraph.nodes().stream()
                .filter(node -> node.kind() == PageGraph.NodeKind.STATEMENT).findFirst().orElseThrow());
        assertThat(subgraph.nodes().subList(1, head)).as("vorab höchstens max(1, 50 / 4) Dokumente von Unterseiten")
                .hasSize(12).allMatch(node -> node.kind() == PageGraph.NodeKind.DOCUMENT);
        assertThat(subgraph.truncated()).isTrue();
        assertThat(subgraph.hidden()).isPositive();
        long documents = subgraph.nodes().stream().filter(node -> node.kind() == PageGraph.NodeKind.DOCUMENT).count();
        assertThat(documents).isLessThan(61);
        assertThat(subgraph.hidden()).as("die übrigen Dokumente zählen als ausgeblendet")
                .isGreaterThanOrEqualTo((int) (61 - documents));
        assertThat(stack.considered()).isEqualTo(60);
        assertThat(stack.missing()).isEmpty();
    }

    private static PageSelection below(PageKey... keys) {
        return below(List.of(keys), List.of());
    }

    /** Unterseiten wie aus {@code Holdings#descendants}: {@code locked} gehört zu {@code keys}. */
    private static PageSelection below(List<PageKey> keys, List<PageKey> locked) {
        return new PageSelection(PageSet.ofKeys(keys), HoldingCounts.ZERO, List.of(), true, PageSet.ofKeys(locked));
    }

    private static GraphNeighbourhood.Options limits(int maxNodes) {
        EnumSet<GraphNeighbourhood.EdgeType> noCrossPage = EnumSet.allOf(GraphNeighbourhood.EdgeType.class);
        noCrossPage.removeIf(GraphNeighbourhood.EdgeType::crossPage);
        return new GraphNeighbourhood.Options(maxNodes, GraphNeighbourhood.MAX_LIMIT,
                EnumSet.allOf(PageGraph.NodeKind.class), noCrossPage, true, null, null);
    }

    /** Seite 1 mit den Unterseiten 12 („alpha“) und 11 („Beta“): nach Titel kommt 12 vor 11. */
    private void analyseFamily() {
        analyse("wiki", "1", "Hauptseite",
                "Der Sachbearbeiter übermittelt den Beleg. Der Vorgesetzte genehmigt den Urlaub.");
        analyse("wiki", "12", "alpha", "Die Prüferin prüft den Antrag. Das Amt sendet den Bescheid.");
        analyse("wiki", "11", "Beta", "Der Kunde genehmigt den Vertrag. Die Bank sendet den Kredit.");
    }

    private void analyse(String sourceId, String pageId, String title, String text) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId, title, "DEV",
                null, "1", Instant.parse("2026-10-15T10:30:00Z"),
                URI.create("https://wiki.example.org/pages/viewpage.action?pageId=" + pageId),
                PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of(sourceId, metadata,
                new NativePayload(text.getBytes(StandardCharsets.UTF_8), "application/json", "test-text"),
                EnrichmentTestPages.RETRIEVED);
        cache.put(snapshot.key(), snapshot);
        statements.analyze(CALLER, snapshot, () -> false);
    }
}
