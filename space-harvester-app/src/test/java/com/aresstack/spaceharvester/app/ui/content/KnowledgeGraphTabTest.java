package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.export.KnowledgeGraphExportUseCase;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Reiter „Analysen → Graph“ ohne Core: Laden, Erweitern, Zurück, Tabelle, Sprung, Seitenwechsel (#499 F). */
class KnowledgeGraphTabTest {

    static final PageKey WIKI = new PageKey("wiki", "1");
    private static final KnowledgeGraph.PageRef WIKI_REF = new KnowledgeGraph.PageRef(WIKI, "DEV", "Seite 1",
            PageStatements.State.CURRENT, null);
    private static final KnowledgeGraph.PageRef OTHER_REF = new KnowledgeGraph.PageRef(new PageKey("handbuch", "7"),
            "OPS", "Belege", PageStatements.State.STALE, PageStatements.TEXT_CHANGED);
    private static final String DOC = "wiki/1@0123456789ab";
    private static final String STATEMENT = DOC + "#s1.v3";
    static final String BELEG = DOC + "#s1.t10";
    private static final String OTHER = "handbuch/7@abcdefabcdef#s1.t4";
    private static final KnowledgeGraph.PageRef SUB_REF = new KnowledgeGraph.PageRef(new PageKey("wiki", "2"), "DEV",
            "Anhang Alpha", PageStatements.State.CURRENT, null);
    private static final String SUB_DOC = "wiki/2@fedcba987654";

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
        assertThat(tab.tableRows()).as("je Knoten und je Kante eine Zeile").isEqualTo(6);
        assertThat(tab.tableValue(0, 0)).isEqualTo("Dokument „Seite 1“");
        assertThat(tab.tableValue(4, 1)).isEqualTo("Betroffen");
        assertThat(tab.tableValue(4, 4)).isEqualTo("sicher");
    }

    @Test
    void deletingAllStatementsAsksFirstAndDoesNothingOnNo() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        actions.calls.clear();
        tab.confirmDeleteWith(() -> false);

        assertThat(tab.deleteAllOffered()).as("Knopf oben rechts in der Kopfleiste").isTrue();
        tab.clickDeleteAll();
        runner.runAll();

        assertThat(actions.calls).as("Nein löscht nichts").isEmpty();
        assertThat(tab.nodeIds()).hasSize(3);
    }

    @Test
    void deletingAllStatementsEmptiesTheGraphReportsInTheStatusLineAndTellsThePage() {
        List<String> status = new ArrayList<>();
        int[] deleted = {0};
        tab.onStatus(status::add);
        tab.onDeleted(() -> deleted[0]++);
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        actions.calls.clear();
        tab.confirmDeleteWith(() -> true);

        tab.clickDeleteAll();
        assertThat(tab.deleteAllOffered()).as("gesperrt, solange gelöscht wird").isFalse();
        runner.runAll();

        assertThat(actions.calls).startsWith("deleteAll").contains("page wiki/1");
        assertThat(tab.nodeIds()).as("Graph danach leer").isEmpty();
        assertThat(status.get(status.size() - 1)).contains("Aussagen von 3 Seiten gelöscht");
        assertThat(deleted[0]).as("Aussagen-Reiter lädt neu").isEqualTo(1);
        assertThat(tab.deleteAllOffered()).isTrue();
    }

    @Test
    void afterDeletingBackNoLongerRestoresTheDeletedGraph() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        tab.selectNode(BELEG);
        tab.clickExpand();
        runner.runAll();
        assertThat(tab.backOffered()).isTrue();
        tab.confirmDeleteWith(() -> true);

        tab.clickDeleteAll();
        runner.runAll();

        assertThat(tab.backOffered()).as("frühere Ansichten zeigen Gelöschtes").isFalse();
        tab.clickBack();
        runner.runAll();
        assertThat(tab.nodeIds()).isEmpty();
    }

    @Test
    void aFailedDeletionNamesTheReasonAndKeepsTheGraph() {
        List<String> status = new ArrayList<>();
        tab.onStatus(status::add);
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        actions.deleteFailure = new IllegalStateException("Datenverzeichnis schreibgeschützt");
        tab.confirmDeleteWith(() -> true);

        tab.clickDeleteAll();
        runner.runAll();

        assertThat(status.get(status.size() - 1)).contains("Aussagen nicht gelöscht: Datenverzeichnis schreibgeschützt");
        assertThat(tab.nodeIds()).hasSize(3);
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
        assertThat(tab.statusText()).contains("1 veraltete Seite(n)").contains("gekürzt").doesNotContain("weitere Knoten");
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
        // Codex zu #570: auch per Tastatur (Enter auf dem Link, Leertaste in der Tabelle öffnet die Details).
        tab.pressEnterOnJump();
        assertThat(jumps).hasSize(2);
        tab.selectTableRow(2);
        tab.pressSpaceInTable();
        assertThat(tab.jumpOffered()).isTrue();

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
        assertThat(tab.hintText()).isEqualTo(KnowledgeGraphTab.EMPTY_TERM);
        tab.setShowStale(false);
        GraphNeighbourhood.Options options = tab.options();
        assertThat(options.includeStale()).isFalse();
        assertThat(options.maxNodes()).isEqualTo(GraphNeighbourhood.DEFAULT_MAX_NODES);
        assertThat(options.types()).as("Kandidaten anfangs aus")
                .doesNotContain(GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE)
                .contains(GraphNeighbourhood.EdgeType.CONFIRMED_TERM_RELATION);
        tab.setShowCandidates(true);
        assertThat(tab.options().types()).contains(GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE);
    }

    @Test
    void theTreeSelectionBoundsTheGraphUntilTheFilterIsSwitchedOff() {
        assertThat(tab.options().pages()).as("ohne Auswahl keine Grenze").isNull();
        PageSet pages = PageSet.of(Map.of("wiki", List.of("1", "2")));
        tab.within(pages);
        assertThat(tab.options().pages()).isSameAs(pages);
        tab.setSelectionOnly(false);
        assertThat(tab.options().pages()).as("Filter aus: ganzer Graph").isNull();
        tab.setSelectionOnly(true);
        tab.within(null);
        assertThat(tab.options().pages()).as("Auswahl aufgehoben").isNull();
    }

    /**
     * W4: mit „mit Unterseiten“ rechnet die Seitenansicht über Seite und Unterseiten; die Statuszeile nennt die Zahl
     * der Unterseiten, die Seiten ohne Analyse mit Titel und ausgeblendete veraltete. Abgehakt wieder nur die Seite.
     */
    @Test
    void withSubpagesThePageGraphSpansThePageAndItsSubpagesAndNamesThoseWithoutAnalysis() {
        PageSelection below = below("2", "3");
        List<GraphNeighbourhood.Stack> loaded = new ArrayList<>();
        tab.onSubpages(loaded::add);
        tab.page(WIKI);
        tab.subpages(below, false);
        tab.showPageNow();
        runner.runAll();

        assertThat(actions.calls).containsExactly("pages wiki/1 +2");
        assertThat(actions.lastSubpages).isEqualTo(below);
        assertThat(tab.nodeIds()).contains(DOC, SUB_DOC);
        assertThat(tab.statusText()).isEqualTo("4 Knoten, 4 Kanten · mit 2 Unterseiten · ohne Analyse: Anhang Beta");
        assertThat(loaded).hasSize(1);
        assertThat(tab.subpageStack()).isSameAs(loaded.get(0));
        assertThat(tab.tableValue(tab.tableRows() - 1, 1)).as("Unterseiten-Kante in der Tabelle benannt")
                .isEqualTo("Unterseite");
        assertThat(tab.describe(new GraphNeighbourhood.Edge(DOC, SUB_DOC, GraphNeighbourhood.EdgeType.SUBPAGE,
                "STRUCTURE", null, null, null, true))).contains("Seitenbaum").doesNotContain("nicht fachlich geprüft");

        // Dieselbe Menge lädt nicht neu; ausgeblendete veraltete Unterseiten nennt die Statuszeile.
        tab.subpages(below("3", "2"), false);
        assertThat(actions.calls).hasSize(1);
        actions.skippedStale = 1;
        tab.showPageNow();
        runner.runAll();
        assertThat(tab.statusText()).endsWith(" · mit 2 Unterseiten · ohne Analyse: Anhang Beta"
                + " · 1 veraltete Seite(n) ausgeblendet");

        actions.calls.clear();
        tab.subpages(null, false);
        tab.showPageNow();
        runner.runAll();
        assertThat(actions.calls).as("abgehakt: wieder nur die Seite").containsExactly("page wiki/1");
        assertThat(tab.statusText()).isEqualTo("3 Knoten, 3 Kanten");
        assertThat(tab.subpageStack()).isNull();
    }

    /**
     * Holdings melden geänderten Bestand bei gleicher Menge (etwa eine Unterseite neu geerntet, ihre Analyse jetzt
     * veraltet): mit {@code reload} rechnet der Graph neu, ohne nicht.
     */
    @Test
    void theSameSubpagesWithReloadComputeTheGraphAgain() {
        List<GraphNeighbourhood.Stack> loaded = new ArrayList<>();
        tab.onSubpages(loaded::add);
        tab.page(WIKI);
        tab.subpages(below("2", "3"), false);
        tab.showPageNow();
        runner.runAll();
        GraphNeighbourhood.Stack first = tab.subpageStack();

        tab.subpages(below("2", "3"), false);
        runner.runAll();
        assertThat(actions.calls).as("ohne reload nichts neu").containsExactly("pages wiki/1 +2");

        actions.skippedStale = 1;
        tab.subpages(below("2", "3"), true);
        assertThat(tab.subpageStack()).as("bis zum neuen Ergebnis das, was der Graph noch zeigt").isSameAs(first);
        tab.showPageNow();
        runner.runAll();
        assertThat(actions.calls).containsExactly("pages wiki/1 +2", "pages wiki/1 +2");
        assertThat(tab.subpageStack()).isNotSameAs(first);
        assertThat(tab.subpageStack().skippedStale()).isEqualTo(1);
        assertThat(loaded).hasSize(2);
    }

    /** Ein Abgleich im Space ändert Zahlen und Übersicht der Auswahl, nicht die Menge: kein Neubeginn der Ansicht. */
    @Test
    void newCountsForTheSameSubpagesKeepTheViewAndOnlyReload() {
        tab.page(WIKI);
        tab.subpages(below("2", "3"), false);
        tab.showPageNow();
        runner.runAll();
        GraphNeighbourhood.Stack first = tab.subpageStack();
        PageSelection recounted = new PageSelection(below("2", "3").pages(), new HoldingCounts(2, 1, 1, 0),
                List.of(), false, below("2", "3").locked());

        tab.subpages(recounted, true);

        assertThat(KnowledgeGraphTab.sameSet(recounted, below("2", "3"))).isTrue();
        assertThat(tab.subpageStack()).as("kein Neubeginn: der Abschnitt zeigt weiter das Gezeigte").isSameAs(first);
        tab.showPageNow();
        runner.runAll();
        assertThat(actions.calls).containsExactly("pages wiki/1 +2", "pages wiki/1 +2");
        assertThat(KnowledgeGraphTab.sameSet(below("2"), below("2", "3"))).isFalse();
        assertThat(KnowledgeGraphTab.sameSet(null, null)).isTrue();
        assertThat(KnowledgeGraphTab.sameSet(below("2"), null)).isFalse();
    }

    /** „Seite neu laden“ zeigt dieselbe Seite noch einmal: die Unterseiten bleiben, die Checkbox meldet nichts Neues. */
    @Test
    void showingTheSamePageAgainKeepsTheSubpages() {
        tab.page(WIKI, "DEV");
        tab.subpages(below("2", "3"), false);
        tab.showPageNow();
        runner.runAll();

        tab.page(WIKI, "DEV");
        assertThat(tab.subpageStack()).as("die Ansicht beginnt neu").isNull();
        tab.showPageNow();
        runner.runAll();
        assertThat(actions.calls).containsExactly("pages wiki/1 +2", "pages wiki/1 +2");
        assertThat(tab.subpageStack()).isNotNull();
        assertThat(tab.statusText()).contains(" · mit 2 Unterseiten");
        // W4 c: der Export bleibt beim Umfang der Seite, Unterseiten-Kanten gibt es nur in der Ansicht.
        assertThat(tab.exportChoices().getFirst().scope())
                .isEqualTo(new KnowledgeGraphExportUseCase.Scope.Page("wiki", "1"));

        tab.page(new PageKey("wiki", "9"), "DEV");
        tab.showPageNow();
        runner.runAll();
        assertThat(actions.calls.getLast()).as("andere Seite: ohne Unterseiten, bis die Checkbox sie meldet")
                .isEqualTo("page wiki/9");
    }

    /** Eine andere Menge verwirft die Historie, auch die gerade gezeigte Ansicht der alten Menge. */
    @Test
    void newSubpagesLeaveNothingForBackFromTheOldSet() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        assertThat(tab.backOffered()).isFalse();

        tab.subpages(below("2", "3"), false);
        tab.showPageNow();
        runner.runAll();

        assertThat(actions.calls).containsExactly("page wiki/1", "pages wiki/1 +2");
        assertThat(tab.backOffered()).as("die Ansicht nur der Seite gehört nicht in die Historie").isFalse();
    }

    /** „Zurück“ bringt mit der Ansicht und ihren Filtern auch das Ergebnis, das der Abschnitt darunter zeigt. */
    @Test
    void backRestoresTheSubpageResultOfTheRestoredView() {
        List<GraphNeighbourhood.Stack> loaded = new ArrayList<>();
        tab.onSubpages(loaded::add);
        actions.skippedStale = 1;
        tab.page(WIKI);
        tab.subpages(below("2", "3"), false);
        tab.showPageNow();
        runner.runAll();
        GraphNeighbourhood.Stack staleHidden = tab.subpageStack();
        assertThat(staleHidden.skippedStale()).isEqualTo(1);

        tab.toggleShowStale();
        runner.runAll();
        assertThat(tab.subpageStack().skippedStale()).isZero();

        tab.clickBack();
        assertThat(tab.showsStale()).isFalse();
        assertThat(tab.subpageStack()).isSameAs(staleHidden);
        assertThat(loaded).hasSize(3).last().isSameAs(staleHidden);
    }

    /** Scheitert das Neuladen, bleibt der Abschnitt beim Ergebnis, das der Graph weiter zeigt. */
    @Test
    void aFailedReloadKeepsTheSubpageResultTheGraphStillShows() {
        List<GraphNeighbourhood.Stack> loaded = new ArrayList<>();
        tab.onSubpages(loaded::add);
        tab.page(WIKI);
        tab.subpages(below("2", "3"), false);
        tab.showPageNow();
        runner.runAll();
        GraphNeighbourhood.Stack shown = tab.subpageStack();

        actions.pagesFailure = new IllegalStateException("Ablage gesperrt");
        tab.toggleShowStale();
        runner.runAll();

        assertThat(tab.statusText()).startsWith("Graph nicht geladen");
        assertThat(tab.subpageStack()).isSameAs(shown);
        assertThat(loaded).as("keine leere Meldung an den Abschnitt").containsExactly(shown);

        // Ohne früheres Ergebnis endet das Laden des Abschnitts mit „nichts“.
        tab.page(new PageKey("wiki", "9"));
        tab.subpages(below("2"), false);
        tab.showPageNow();
        runner.runAll();
        assertThat(loaded).hasSize(2).last().isNull();
    }

    /** Die Statuszeile zählt nur die Unterseiten, die in die Rechnung eingingen (Filter, Rechte, Sperre). */
    @Test
    void theStatusCountsOnlyTheSubpagesTheGraphConsidered() {
        actions.considered = 1;
        tab.page(WIKI);
        tab.subpages(below("2", "3", "4"), false);
        tab.showPageNow();
        runner.runAll();

        assertThat(tab.statusText()).contains(" · mit 1 Unterseite · ").doesNotContain("3 Unterseiten");
    }

    /** Architektenvorgabe zu W4, Punkt 2: Unterseiten jenseits der Grenze nennt die Statuszeile als Zahl. */
    @Test
    void theStatusNamesTheSubpagesBeyondTheLimitAsNotConsidered() {
        actions.considered = 200;
        actions.notConsidered = 3;
        tab.page(WIKI);
        tab.subpages(below("2", "3", "4"), false);
        tab.showPageNow();
        runner.runAll();

        assertThat(tab.statusText()).contains(" · mit 200 Unterseiten · 3 Unterseiten nicht berücksichtigt · ");
        assertThat(new KnowledgeGraphTab.Coverage(200, List.of(), 0, 1).text())
                .isEqualTo(" · mit 200 Unterseiten · 1 Unterseite nicht berücksichtigt");
        assertThat(new KnowledgeGraphTab.Coverage(2, List.of(), 0, 0).text()).isEqualTo(" · mit 2 Unterseiten");
    }

    /**
     * Architektenvorgabe zu W4, Punkt 4: Scheitert das erste Laden, endet der Abschnitt mit „nichts“. Fragt er später
     * wieder (etwa nach „Absätze“ und zurück), lädt der Graph neu, statt den Abschnitt ewig laden zu lassen.
     */
    @Test
    void theSectionAskingAgainAfterAFailedLoadReloadsOrGetsNothing() {
        List<GraphNeighbourhood.Stack> loaded = new ArrayList<>();
        tab.onSubpages(loaded::add);
        actions.pagesFailure = new IllegalStateException("Ablage gesperrt");
        tab.page(WIKI);
        tab.subpages(below("2", "3"), false);
        tab.showPageNow();
        runner.runAll();
        assertThat(loaded).containsExactly((GraphNeighbourhood.Stack) null);

        // Läuft das Laden noch, kommt das Ergebnis von selbst: keine zweite Abfrage.
        actions.pagesFailure = null;
        tab.reload();
        tab.requestSubpages();
        runner.runAll();
        assertThat(actions.calls).containsExactly("pages wiki/1 +2", "pages wiki/1 +2");
        assertThat(loaded).hasSize(2).last().isNotNull();

        // Liegt ein Ergebnis vor, bekommt der Abschnitt es sofort.
        tab.requestSubpages();
        assertThat(loaded).hasSize(3).last().isSameAs(tab.subpageStack());
        assertThat(actions.calls).hasSize(2);

        // Gescheitert und nichts läuft: neu laden.
        tab.page(new PageKey("wiki", "9"));
        tab.subpages(below("2"), false);
        actions.pagesFailure = new IllegalStateException("Ablage gesperrt");
        tab.showPageNow();
        runner.runAll();
        assertThat(loaded).last().isNull();
        actions.pagesFailure = null;
        tab.requestSubpages();
        runner.runAll();
        assertThat(actions.calls).last().isEqualTo("pages wiki/9 +1");
        assertThat(actions.calls).hasSize(4);
        assertThat(loaded).last().isNotNull().isSameAs(tab.subpageStack());

        // Ohne Unterseiten oder mit einer anderen Ansicht (Begriff) gleich „nichts“.
        int before = loaded.size();
        tab.subpages(null, false);
        tab.requestSubpages();
        assertThat(loaded).hasSize(before + 1).last().isNull();
        tab.subpages(below("2"), false);
        tab.showPageNow();
        actions.pagesFailure = new IllegalStateException("Ablage gesperrt");
        runner.runAll();
        tab.searchTerm("Beleg");
        runner.runAll();
        int calls = actions.calls.size();
        tab.requestSubpages();
        runner.runAll();
        assertThat(loaded).last().isNull();
        assertThat(actions.calls).hasSize(calls);
    }

    /**
     * Architektenvorgabe zu W4, Punkt 5: Abschalten der Unterseiten lädt nicht sofort. Verlässt der Graph die Seite im
     * selben Durchlauf, lädt nichts mehr für die alte Seite; späte Ergebnisse der alten Menge verfallen. Bleibt die
     * Seite (Checkbox abgehakt), danach wieder die Seite allein.
     */
    @Test
    void switchingSubpagesOffLoadsNothingForTheOldPageAndDropsLateResults() {
        List<Runnable> later = new ArrayList<>();
        List<GraphNeighbourhood.Stack> loaded = new ArrayList<>();
        tab.later(later::add);
        tab.onSubpages(loaded::add);
        tab.showingForTests(true);
        tab.page(WIKI);
        runner.runAll();
        tab.subpages(below("2", "3"), false);
        runner.runAll();
        assertThat(actions.calls).containsExactly("page wiki/1", "pages wiki/1 +2");

        // Seite verlassen: erst die Unterseiten abgemeldet, dann die neue Seite.
        tab.subpages(null, false);
        tab.page(new PageKey("wiki", "9"));
        later.forEach(Runnable::run);
        later.clear();
        runner.runAll();
        assertThat(actions.calls).containsExactly("page wiki/1", "pages wiki/1 +2", "page wiki/9");

        // Ein spätes Ergebnis der alten Menge verfällt.
        tab.subpages(below("2"), false);
        int shown = loaded.size();
        tab.subpages(null, false);
        runner.runAll();
        assertThat(actions.calls).as("abgeschaltet: nur die schon laufende Abfrage, keine neue")
                .containsExactly("page wiki/1", "pages wiki/1 +2", "page wiki/9", "pages wiki/9 +1");
        assertThat(loaded).as("kein Ergebnis der alten Menge").hasSize(shown);
        assertThat(tab.subpageStack()).isNull();
        assertThat(tab.nodeIds()).doesNotContain(SUB_DOC);

        // Die Seite bleibt (abgehakt): danach wieder die Seite allein.
        later.forEach(Runnable::run);
        runner.runAll();
        assertThat(actions.calls).last().isEqualTo("page wiki/9");
        assertThat(tab.statusText()).isEqualTo("3 Knoten, 3 Kanten");
    }

    /**
     * Review zu W4, Punkt 4: Ersetzt eine Begriffssuche das noch laufende Laden mit Unterseiten, endet der Abschnitt
     * „Aus Unterseiten“ mit „nichts“, statt ewig zu laden.
     */
    @Test
    void aTermSearchReplacingTheRunningSubpageLoadEndsTheSection() {
        List<GraphNeighbourhood.Stack> loaded = new ArrayList<>();
        tab.onSubpages(loaded::add);
        tab.page(WIKI);
        tab.subpages(below("2", "3"), false);
        tab.showPageNow();
        tab.requestSubpages();
        assertThat(loaded).as("läuft noch: kommt von selbst").isEmpty();

        tab.searchTerm("Beleg");
        assertThat(loaded).containsExactly((GraphNeighbourhood.Stack) null);
        runner.runAll();
        assertThat(loaded).as("das verworfene Laden meldet nichts mehr")
                .containsExactly((GraphNeighbourhood.Stack) null);
        assertThat(tab.subpageStack()).isNull();
    }

    /**
     * Review zu W4, Punkte 1 und 4: Erweitern während des Ladens mit Unterseiten gilt der aktuellen Menge, auch wenn
     * noch die Seite allein steht; die Erweiterung bleibt in der Menge ohne gesperrte Unterseiten, und der Abschnitt
     * „Aus Unterseiten“ bekommt sein Ergebnis.
     */
    @Test
    void expandingInTheSubpageViewStaysInTheSetAndDeliversTheSection() {
        List<GraphNeighbourhood.Stack> loaded = new ArrayList<>();
        tab.onSubpages(loaded::add);
        tab.later(task -> { }); // „abgehakt, danach die Seite allein“ übernimmt hier showPageNow()
        tab.showingForTests(true);
        tab.page(WIKI);
        runner.runAll();
        PageSelection below = new PageSelection(PageSet.of(Map.of("wiki", List.of("2", "3"))), HoldingCounts.ZERO,
                List.of(), true, PageSet.of(Map.of("wiki", List.of("3"))));
        tab.subpages(below, false);
        tab.requestSubpages();

        tab.selectNode(BELEG);
        tab.clickExpand();
        runner.runAll();

        assertThat(actions.calls).containsExactly("page wiki/1", "pages wiki/1 +2", "pages wiki/1 +2",
                "node " + BELEG);
        assertThat(loaded).hasSize(1).last().isNotNull().isSameAs(tab.subpageStack());
        assertThat(tab.nodeIds()).contains(SUB_DOC, OTHER);
        PageSet set = actions.lastNodeOptions.pages();
        assertThat(set).isNotNull();
        assertThat(set.contains(WIKI)).isTrue();
        assertThat(set.contains(new PageKey("wiki", "2"))).isTrue();
        assertThat(set.contains(new PageKey("wiki", "3"))).as("gesperrt").isFalse();
        assertThat(set.contains(OTHER_REF.key())).as("fremde Seite").isFalse();

        // Ohne Unterseiten bleibt die Erweiterung ohne Grenze.
        tab.subpages(null, false);
        tab.showPageNow();
        runner.runAll();
        tab.selectNode(BELEG);
        tab.clickExpand();
        runner.runAll();
        assertThat(actions.calls).last().isEqualTo("node " + BELEG);
        assertThat(actions.lastNodeOptions.pages()).isNull();
    }

    @Test
    void theStatusNamesAtMostThreeSubpagesWithoutAnalysis() {
        assertThat(new KnowledgeGraphTab.Coverage(5, List.of("Alpha", "Beta", "Gamma", "Delta", "Epsilon"), 0).text())
                .isEqualTo(" · mit 5 Unterseiten · ohne Analyse: Alpha, Beta, Gamma und 2 weitere");
        assertThat(new KnowledgeGraphTab.Coverage(1, List.of(), 0).text()).isEqualTo(" · mit 1 Unterseite");
    }

    @Test
    void candidatesAndStalePagesStartHiddenSoTheGraphStaysReadable() {
        assertThat(tab.showsCandidates()).isFalse();
        assertThat(tab.showsStale()).isFalse();
        assertThat(tab.options().includeStale()).isFalse();
        assertThat(tab.filterHighlighted()).as("Grundeinstellung gilt nicht als gefiltert").isFalse();

        tab.setShowCandidates(true);
        tab.refreshNow();
        assertThat(tab.filterHighlighted()).isTrue();
    }

    @Test
    void aFilterChangeRepeatsTheTermSearchAndBackRestoresFiltersWithTheView() {
        tab.page(WIKI);
        tab.setShowStale(true);
        tab.searchTerm("Beleg");
        runner.runAll();
        assertThat(tab.nodeIds()).containsExactly(BELEG, OTHER);

        tab.toggleShowStale();
        runner.runAll();

        assertThat(actions.calls).containsExactly("term Beleg", "term Beleg");
        assertThat(actions.lastOptions.includeStale()).isFalse();
        assertThat(tab.nodeIds()).as("Begriffsansicht bleibt, nur gefiltert").containsExactly(BELEG);

        tab.clickBack();
        assertThat(tab.nodeIds()).containsExactly(BELEG, OTHER);
        assertThat(tab.options().includeStale()).as("Filter der wiederhergestellten Ansicht").isTrue();
    }

    @Test
    void aPageHiddenByFiltersSaysSoInsteadOfNotAnalysed() {
        tab.page(WIKI);
        tab.setShowStale(false);
        actions.pageStale = true;
        tab.showPageNow();
        runner.runAll();

        assertThat(tab.nodeIds()).isEmpty();
        assertThat(tab.hintText()).isEqualTo(KnowledgeGraphTab.FILTERED);

        actions.empty = true;
        tab.showPageNow();
        runner.runAll();
        assertThat(tab.hintText()).isEqualTo(KnowledgeGraphTab.NOT_ANALYSED);
    }

    @Test
    void everyNodeHasATableRowAndTheRowFollowsTheGraphSelection() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();

        tab.selectTableRow(2);
        assertThat(tab.selectedNode()).isEqualTo(BELEG);
        assertThat(tab.jumpOffered()).as("Erwähnung per Tastatur erreichbar").isTrue();

        tab.selectNode(STATEMENT);
        assertThat(tab.selectedTableRow()).isEqualTo(1);
        tab.pressEnterInTable();
        runner.runAll();
        assertThat(actions.calls).containsExactly("page wiki/1", "node " + STATEMENT);
    }

    @Test
    void aSecondExpandWhileTheFirstIsRunningKeepsBothAndAFilterChangeReplaysThem() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();

        tab.selectNode(BELEG);
        tab.clickExpand();
        tab.selectNode(STATEMENT);
        tab.clickExpand();
        runner.runAll();

        assertThat(actions.calls).as("zweite Erweiterung wiederholt alles, die erste geht nicht verloren")
                .containsExactly("page wiki/1", "node " + BELEG, "page wiki/1", "node " + BELEG,
                        "node " + STATEMENT);
        assertThat(tab.nodeIds()).contains(OTHER);

        actions.calls.clear();
        tab.toggleShowStale();
        runner.runAll();
        assertThat(actions.calls).containsExactly("page wiki/1", "node " + BELEG, "node " + STATEMENT);
    }

    @Test
    void confirmedRelationsAreNotLabelledAsCandidates() {
        actions.confirmed = true;
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        tab.selectNode(BELEG);
        tab.clickExpand();
        runner.runAll();

        boolean confirmedRow = false;
        for (int i = 0; i < tab.tableRows(); i++) {
            String type = String.valueOf(tab.tableValue(i, 1));
            assertThat(type).doesNotContain("Kandidat");
            confirmedRow |= type.equals("Begriffsbeziehung (bestätigt)");
        }
        assertThat(confirmedRow).isTrue();
    }

    @Test
    void aNewAnalysisMakesTheGraphLoadAgain() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        assertThat(tab.pageLoaded()).isTrue();

        tab.analysisChanged();

        assertThat(tab.pageLoaded()).as("lädt beim nächsten Anzeigen neu").isFalse();
    }

    @Test
    void closingThePageClearsTableStatusAndButtons() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();

        tab.page(null);

        assertThat(tab.nodeIds()).isEmpty();
        assertThat(tab.tableRows()).isZero();
        assertThat(tab.statusText()).isEqualTo("Keine Seite geöffnet.");
    }

    @Test
    void theExportOffersPageSubtreeSpaceSourceAndAllAndNeverLoadsOrAnalyses() {
        tab.page(WIKI, "DEV");

        List<KnowledgeGraphTab.ExportChoice> choices = tab.exportChoices();
        assertThat(choices).extracting(KnowledgeGraphTab.ExportChoice::label).containsExactly("Diese Seite",
                "Unterzweig ab dieser Seite", "Space „DEV“", "Quelle „wiki“", "Gesamter Graph");
        assertThat(choices).extracting(KnowledgeGraphTab.ExportChoice::scope).containsExactly(
                new KnowledgeGraphExportUseCase.Scope.Page("wiki", "1"),
                new KnowledgeGraphExportUseCase.Scope.Subtree("wiki", "1"),
                new KnowledgeGraphExportUseCase.Scope.Space("wiki", "DEV"),
                new KnowledgeGraphExportUseCase.Scope.Source("wiki"),
                new KnowledgeGraphExportUseCase.Scope.All());

        tab.startExport(choices.get(1));
        assertThat(tab.exportEnabled()).as("gesperrt, solange der Start läuft").isFalse();
        runner.runAll();

        assertThat(actions.exports).as("veraltete nur auf ausdrücklichen Wunsch, auch wenn die Ansicht sie zeigt")
                .containsExactly("zweig wiki 1 nur aktuelle");
        assertThat(actions.calls).as("Export lädt keinen Teilgraphen und analysiert nichts").isEmpty();
        assertThat(tab.exportNoteText()).isEqualTo(KnowledgeGraphTab.EXPORT_STARTED);
        assertThat(tab.exportEnabled()).isTrue();
    }

    @Test
    void withoutAnOpenPageOnlyTheWholeGraphCanBeExported() {
        tab.page(null);

        assertThat(tab.exportChoices()).filteredOn(choice -> choice.scope() != null)
                .extracting(KnowledgeGraphTab.ExportChoice::label).containsExactly("Gesamter Graph");
        tab.page(WIKI);
        assertThat(tab.exportChoices().get(2).scope()).as("ohne bekannten Space kein Space-Umfang").isNull();
    }

    @Test
    void staleAnalysesAreExportedOnlyWhenExplicitlyIncluded() {
        tab.page(WIKI, "DEV");
        tab.toggleShowStale();
        runner.runAll();
        tab.includeStaleInExport(true);

        tab.startExport(tab.exportChoices().get(2));
        runner.runAll();

        assertThat(actions.exports).as("unabhängig vom Ansichtsfilter").containsExactly("space wiki DEV mit veralteten");
    }

    @Test
    void theExportNoteIsClearedOnAPageChangeAndALateCallbackDoesNotWriteIt() {
        tab.page(WIKI, "DEV");
        tab.startExport(tab.exportChoices().get(0));
        runner.runAll();
        assertThat(tab.exportNoteText()).isEqualTo(KnowledgeGraphTab.EXPORT_STARTED);

        tab.page(new PageKey("wiki", "2"), "DEV");
        assertThat(tab.exportNoteText()).isEmpty();

        tab.startExport(tab.exportChoices().get(0));
        tab.page(WIKI, "DEV");
        runner.runAll();
        assertThat(tab.exportNoteText()).as("Rückmeldung zur vorigen Seite bleibt weg").isEmpty();
        assertThat(tab.exportEnabled()).isTrue();
    }

    @Test
    void graphInteractionsWhileTheExportStartsDoNotSwallowItsOutcome() {
        tab.page(WIKI, "DEV");
        tab.showPageNow();
        runner.runAll();
        actions.exportFailure = new IllegalStateException("Warteschlange voll");

        tab.startExport(tab.exportChoices().get(4));
        tab.toggleShowStale();
        tab.searchTerm("Beleg");
        runner.runAll();

        assertThat(tab.exportNoteText()).isEqualTo("Export nicht gestartet: Warteschlange voll");
    }

    @Test
    void aSecondExportWhileStartingIsIgnoredAndAFailureNamesTheReason() {
        tab.page(WIKI, "DEV");
        actions.exportFailure = new IllegalStateException("Exportordner nicht beschreibbar");

        tab.startExport(tab.exportChoices().get(4));
        tab.startExport(tab.exportChoices().get(4));
        runner.runAll();

        assertThat(actions.exports).hasSize(1);
        assertThat(tab.exportNoteText()).isEqualTo("Export nicht gestartet: Exportordner nicht beschreibbar");
        assertThat(tab.exportEnabled()).isTrue();
    }

    // ---- Fachsicht im Seitengraphen (W4b)

    /** Oben im Filtermenü „Nur Fachbegriffe“, darunter „Nur geprüft“ (ohne Fachsicht gesperrt), dann der Bestand. */
    @Test
    void theFilterMenuStartsWithTheDomainViewAndLocksTheStockFiltersInIt() {
        tab.within(PageSet.of(Map.of("wiki", List.of("1"))));
        assertThat(tab.filterMenuTexts()).startsWith("Nur Fachbegriffe", "Nur geprüft", "—", "Dokumente", "Aussagen",
                "Erwähnungen", "Rollen", "Nominale Bezüge", "Seitenverbindungen");
        assertThat(tab.domainChoice().isSelected()).as("Standard aus").isFalse();
        assertThat(tab.checkedChoice().isEnabled()).isFalse();
        assertThat(tab.checkedChoice().getToolTipText()).isEqualTo("Gilt nur mit „Nur Fachbegriffe“");
        assertThat(tab.filterButton().marked()).isFalse();
        String selectionTip = tab.filterItem("Nur Auswahl im Baum").getToolTipText();

        tab.domainChoice().doClick();

        assertThat(tab.checkedChoice().isEnabled()).isTrue();
        assertThat(tab.checkedChoice().getToolTipText()).isEqualTo(
                "Nur Verbindungen mit geprüften Belegen; die Zahl zählt nur diese");
        for (String text : List.of("Dokumente", "Aussagen", "Erwähnungen", "Rollen", "Nominale Bezüge",
                "Seitenverbindungen", "Gleiche Grundform (Kandidaten)", "Nur diese Quelle", "Nur Auswahl im Baum")) {
            assertThat(tab.filterItem(text).isVisible()).as(text).isTrue();
            assertThat(tab.filterItem(text).isEnabled()).as(text).isFalse();
            assertThat(tab.filterItem(text).getToolTipText()).as(text).isEqualTo("Gilt nicht in der Fachsicht");
        }
        assertThat(tab.filterItem("Veraltete zeigen").isEnabled()).as("wirkt auch in der Fachsicht").isTrue();
        assertThat(tab.filterButton().marked()).as("Punkt am Filter-Symbol").isTrue();
        assertThat(tab.filterButton().getToolTipText()).contains("Fachsicht");

        tab.domainChoice().doClick();

        assertThat(tab.filterItem("Dokumente").isEnabled()).isTrue();
        assertThat(tab.filterItem("Dokumente").getToolTipText()).isNull();
        assertThat(tab.filterItem("Nur Auswahl im Baum").getToolTipText()).isEqualTo(selectionTip);
        assertThat(tab.checkedChoice().isEnabled()).isFalse();
        assertThat(tab.filterButton().marked()).isFalse();
        assertThat(tab.filterButton().getToolTipText()).isEqualTo(KnowledgeGraphTab.FILTER_TIP);
    }

    @Test
    void switchingTheDomainViewOnShowsThePageWideViewOfThePageAlone() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();

        tab.domainChoice().doClick();
        runner.runAll();

        assertThat(actions.domainCalls).containsExactly("onPages {wiki=[1]}");
        DomainTermGraph.Options options = actions.domainOptions.getLast();
        assertThat(options.checkedOnly()).isFalse();
        assertThat(options.includeStale()).isFalse();
        assertThat(tab.domainCanvas().centred()).as("seitenweit ohne Mitte").isFalse();
        assertThat(tab.domainCanvas().labels()).containsExactly("Plan", "Ziel", "Nachtlauf");
        assertThat(tab.statusText()).isEqualTo("3 Fachbegriffe, 2 Verbindungen");
        assertThat(tab.overviewButton().isVisible()).isTrue();
        assertThat(tab.overviewButton().isEnabled()).as("gesperrt in der seitenweiten Sicht").isFalse();
        assertThat(tab.overviewButton().getToolTipText()).isEqualTo("Zurück zur Gesamtsicht");
        assertThat(tab.overviewButton().getText()).as("nur Symbol").isNullOrEmpty();
        assertThat(tab.overviewButton().getIcon()).as("eigenes Symbol „alles zeigen“, kein Pfeil wie „Zurück“")
                .isSameAs(com.aresstack.spaceharvester.app.ui.Glyph.OVERVIEW)
                .isNotSameAs(com.aresstack.spaceharvester.app.ui.Glyph.BACK);
    }

    @Test
    void withSubpagesTheDomainViewSpansThePageAndItsUnlockedSubpages() {
        PageSelection below = new PageSelection(PageSet.of(Map.of("wiki", List.of("2", "3", "4"))),
                HoldingCounts.ZERO, List.of(), true, PageSet.of(Map.of("wiki", List.of("4"))));
        tab.page(WIKI);
        tab.subpages(below, false);
        tab.domainChoice().doClick();
        tab.showPageNow();
        runner.runAll();

        assertThat(actions.domainOptions.getLast().pages().asMap()).containsExactly(
                Map.entry("wiki", java.util.Set.of("1", "2", "3")));
        assertThat(tab.statusText()).isEqualTo("3 Fachbegriffe, 2 Verbindungen · mit 2 Unterseiten");

        // Abgehakt: wieder nur die Seite.
        tab.subpages(null, false);
        tab.showPageNow();
        runner.runAll();
        assertThat(actions.domainOptions.getLast().pages().asMap()).containsExactly(
                Map.entry("wiki", java.util.Set.of("1")));
        assertThat(tab.statusText()).isEqualTo("3 Fachbegriffe, 2 Verbindungen");
    }

    @Test
    void clickingATermCentresItAndBackReturnsToThePageWideView() {
        tab.page(WIKI);
        tab.subpages(below("2"), false);
        tab.showPageNow();
        tab.domainChoice().doClick();
        runner.runAll();
        PageSet pages = actions.domainOptions.getLast().pages();

        tab.clickDomainNode("Plan");
        runner.runAll();

        assertThat(actions.domainCalls).containsExactly("onPages {wiki=[1, 2]}", "around Plan {wiki=[1, 2]}");
        assertThat(actions.domainOptions.getLast().pages()).isEqualTo(pages);
        assertThat(actions.domainOptions.getLast().depth()).isEqualTo(2);
        assertThat(tab.domainCentre()).isEqualTo("Plan");
        assertThat(tab.domainCanvas().centred()).isTrue();
        assertThat(tab.overviewButton().isEnabled()).isTrue();
        assertThat(tab.statusText()).isEqualTo("2 Fachbegriffe, 1 Verbindung um „Plan“ · mit 1 Unterseite");

        tab.clickOverview();
        runner.runAll();

        assertThat(actions.domainCalls.getLast()).isEqualTo("onPages {wiki=[1, 2]}");
        assertThat(tab.domainCentre()).isNull();
        assertThat(tab.domainCanvas().centred()).isFalse();
        assertThat(tab.overviewButton().isEnabled()).isFalse();
    }

    @Test
    void checkedAndStaleReloadTheDomainViewAndKeepTheCentre() {
        tab.page(WIKI);
        tab.showPageNow();
        tab.domainChoice().doClick();
        runner.runAll();
        tab.clickDomainNode("Plan");
        runner.runAll();
        actions.domainCalls.clear();
        actions.calls.clear();

        tab.checkedChoice().doClick();
        runner.runAll();
        assertThat(actions.domainCalls).containsExactly("around Plan {wiki=[1]}");
        assertThat(actions.domainOptions.getLast().checkedOnly()).isTrue();

        tab.toggleShowStale();
        runner.runAll();
        assertThat(actions.domainCalls).containsExactly("around Plan {wiki=[1]}", "around Plan {wiki=[1]}");
        assertThat(actions.domainOptions.getLast().includeStale()).isTrue();
        assertThat(actions.calls).as("der Bestand rechnet in der Fachsicht nicht neu").isEmpty();
        assertThat(tab.filterButton().getToolTipText()).contains("Nur geprüft", "Veraltete zeigen");

        // Zurück im Bestand folgt er „Veraltete zeigen“.
        tab.domainChoice().doClick();
        runner.runAll();
        assertThat(actions.calls).containsExactly("page wiki/1");
        assertThat(actions.lastOptions.includeStale()).isTrue();
        assertThat(tab.statusText()).startsWith("3 Knoten");
    }

    @Test
    void theDomainStatusCountsSingularAndPluralAndNamesStaleAndTruncation() {
        assertThat(KnowledgeGraphTab.domainStatusText(view(2, 1, false, 0), null, -1, false))
                .isEqualTo("2 Fachbegriffe, 1 Verbindung");
        assertThat(KnowledgeGraphTab.domainStatusText(view(2, 1, false, 1), "Plan", 1, false))
                .isEqualTo("2 Fachbegriffe, 1 Verbindung um „Plan“ · mit 1 Unterseite"
                        + " · 1 veraltete Aussage ausgeblendet");
        assertThat(KnowledgeGraphTab.domainStatusText(view(1, 1, false, 0), null, -1, false))
                .isEqualTo("1 Fachbegriff, 1 Verbindung");
        assertThat(KnowledgeGraphTab.domainStatusText(view(40, 52, true, 2), null, 3, false))
                .isEqualTo("40 Fachbegriffe, 52 Verbindungen · mit 3 Unterseiten · 2 veraltete Aussagen ausgeblendet"
                        + " · die 40 mit den meisten Belegen");
        assertThat(KnowledgeGraphTab.domainStatusText(view(40, 45, true, 0), "Plan", -1, false))
                .as("around kürzt nach Nachbarschaft, nicht nach Belegen")
                .isEqualTo("40 Fachbegriffe, 45 Verbindungen um „Plan“ · die ersten 40 Fachbegriffe");
        assertThat(KnowledgeGraphTab.DOMAIN_TRUNCATED).contains(String.valueOf(DomainTermGraph.MAX_NODES));
    }

    @Test
    void anEmptyPageWideViewExplainsItselfOnTheCanvasNotInTheStatusLine() {
        actions.domainEmpty = true;
        tab.page(WIKI);
        tab.showPageNow();
        tab.domainChoice().doClick();
        runner.runAll();

        assertThat(tab.domainCanvas().emptyText()).isEqualTo(
                "Auf dieser Seite sind keine bestätigten Fachbegriffe durch ein Verb verbunden.");
        assertThat(tab.statusText()).isBlank();

        tab.subpages(below("2"), false);
        tab.showPageNow();
        runner.runAll();
        assertThat(tab.domainCanvas().emptyText()).isEqualTo("Auf dieser Seite und ihren Unterseiten sind keine "
                + "bestätigten Fachbegriffe durch ein Verb verbunden.");
        assertThat(tab.statusText()).isEqualTo("mit 1 Unterseite");

        tab.checkedChoice().doClick();
        runner.runAll();
        assertThat(tab.domainCanvas().emptyText()).isEqualTo(KnowledgeGraphTab.NO_CHECKED_DOMAIN_EDGES_BELOW);

        actions.domainEmpty = false;
        tab.checkedChoice().doClick();
        runner.runAll();
        assertThat(tab.domainCanvas().emptyText()).as("mit Knoten kein Text").isNull();
    }

    @Test
    void anOvertakenDomainResultIsDiscarded() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        tab.domainChoice().doClick();
        // Die seitenweite Abfrage läuft noch, da kommt „Nur geprüft“: die erste ist überholt.
        tab.checkedChoice().doClick();
        assertThat(runner.pendingCount()).isEqualTo(2);

        // Die neuere Abfrage endet zuerst (leer), die überholte danach mit Knoten: sie darf nichts mehr zeigen.
        actions.domainEmpty = true;
        runner.runAt(1);
        actions.domainEmpty = false;
        runner.runAll();

        assertThat(actions.domainCalls).hasSize(2);
        assertThat(actions.cancelledSeen).as("die überholte Rechnung erfährt es").containsExactly(false, true);
        assertThat(tab.domainCanvas().labels()).as("nur das neuere Ergebnis").isEmpty();
        assertThat(tab.domainCanvas().emptyText()).isEqualTo(KnowledgeGraphTab.NO_CHECKED_DOMAIN_EDGES);
        assertThat(tab.statusText()).isBlank();
    }

    @Test
    void anOvertakenDomainFailureIsDiscarded() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        tab.domainChoice().doClick();
        tab.checkedChoice().doClick();

        runner.runAt(1);
        actions.domainFailure = new IllegalStateException("Ablage gesperrt");
        runner.runAll();

        assertThat(tab.domainCanvas().labels()).containsExactly("Plan", "Ziel", "Nachtlauf");
        assertThat(tab.statusText()).isEqualTo("3 Fachbegriffe, 2 Verbindungen");
    }

    @Test
    void aFailedCentreKeepsThePageWideViewAndTheTermCanBeClickedAgain() {
        tab.page(WIKI);
        tab.showPageNow();
        tab.domainChoice().doClick();
        runner.runAll();

        actions.domainFailure = new IllegalStateException("Ablage gesperrt");
        tab.clickDomainNode("Plan");
        runner.runAll();

        assertThat(tab.statusText()).startsWith("Fachsicht nicht geladen");
        assertThat(tab.domainCentre()).as("nie gezeigt, also keine Mitte").isNull();
        assertThat(tab.domainCanvas().centred()).isFalse();
        assertThat(tab.overviewButton().isEnabled()).isFalse();

        actions.domainFailure = null;
        tab.clickDomainNode("Plan");
        runner.runAll();

        assertThat(actions.domainCalls).containsExactly("onPages {wiki=[1]}", "around Plan {wiki=[1]}",
                "around Plan {wiki=[1]}");
        assertThat(tab.domainCentre()).isEqualTo("Plan");
        assertThat(tab.domainCanvas().centred()).isTrue();
        assertThat(tab.overviewButton().isEnabled()).isTrue();
    }

    @Test
    void aLoneCentreExplainsItselfLikeTheOverview() {
        tab.page(WIKI);
        tab.showPageNow();
        tab.domainChoice().doClick();
        runner.runAll();
        actions.aroundAlone = true;

        tab.clickDomainNode("Plan");
        runner.runAll();

        assertThat(tab.domainCanvas().labels()).containsExactly("Plan");
        assertThat(tab.statusText()).isEqualTo(
                "Zu diesem Fachbegriff gibt es auf dieser Seite noch keine Aussagen mit anderen Fachbegriffen.");

        tab.checkedChoice().doClick();
        runner.runAll();
        assertThat(tab.statusText()).isEqualTo("Zu diesem Fachbegriff gibt es auf dieser Seite noch keine geprüften "
                + "Aussagen mit anderen Fachbegriffen.");

        tab.subpages(below("2"), false);
        runner.runAll();
        tab.showPageNow();
        runner.runAll();
        assertThat(tab.statusText()).isEqualTo("Zu diesem Fachbegriff gibt es auf dieser Seite und ihren Unterseiten "
                + "noch keine geprüften Aussagen mit anderen Fachbegriffen. · mit 1 Unterseite");
    }

    @Test
    void withAllSubpagesLockedTheDomainViewSpeaksOfThePageAlone() {
        PageSelection locked = new PageSelection(PageSet.of(Map.of("wiki", List.of("2", "3"))),
                HoldingCounts.ZERO, List.of(), true, PageSet.of(Map.of("wiki", List.of("2", "3"))));
        tab.page(WIKI);
        tab.subpages(locked, false);
        tab.domainChoice().doClick();
        tab.showPageNow();
        runner.runAll();

        assertThat(actions.domainOptions.getLast().pages().asMap()).containsExactly(
                Map.entry("wiki", java.util.Set.of("1")));
        assertThat(tab.statusText()).isEqualTo("3 Fachbegriffe, 2 Verbindungen");

        actions.domainEmpty = true;
        tab.toggleShowStale();
        runner.runAll();
        assertThat(tab.domainCanvas().emptyText()).isEqualTo(KnowledgeGraphTab.NO_DOMAIN_EDGES);
        assertThat(tab.statusText()).isBlank();
    }

    @Test
    void leavingTheDomainViewCatchesUpOnStaleWhileTheStockLoadIsStillRunning() {
        tab.domainChoice().doClick();
        tab.page(WIKI);
        tab.showPageNow();
        // Bestand (ohne Veraltete) und Fachsicht laufen; nur die Fachsicht endet.
        assertThat(runner.pendingCount()).isEqualTo(2);
        runner.runAt(1);
        tab.toggleShowStale();
        runner.runAt(1);

        tab.domainChoice().doClick();
        runner.runAll();

        assertThat(actions.calls).containsExactly("page wiki/1", "page wiki/1");
        assertThat(actions.lastOptions.includeStale()).isTrue();
        assertThat(tab.statusText()).startsWith("3 Knoten");
    }

    @Test
    void aFailedStockLoadKeepsTheSharedStaleChoiceInTheDomainView() {
        tab.page(WIKI);
        tab.showPageNow();
        runner.runAll();
        tab.domainChoice().doClick();
        runner.runAll();
        tab.toggleShowStale();
        runner.runAll();

        actions.pageFailure = new IllegalStateException("Ablage gesperrt");
        tab.reload();
        runner.runAll();

        assertThat(tab.filterItem("Veraltete zeigen").isSelected()).as("die Wahl des Nutzers bleibt").isTrue();
        assertThat(actions.domainOptions.getLast().includeStale()).isTrue();
        assertThat(tab.filterButton().getToolTipText()).contains("Veraltete zeigen");

        // Zurück im Bestand holt er „Veraltete zeigen“ nach.
        actions.pageFailure = null;
        actions.calls.clear();
        tab.domainChoice().doClick();
        runner.runAll();
        assertThat(actions.calls).containsExactly("page wiki/1");
        assertThat(actions.lastOptions.includeStale()).isTrue();
    }

    @Test
    void theExportStaysTheStockGraphAndSaysSoInTheDomainView() {
        tab.page(WIKI);
        assertThat(tab.exportTip()).isEqualTo(KnowledgeGraphTab.EXPORT_TIP);
        tab.domainChoice().doClick();
        assertThat(tab.exportTip()).isEqualTo("Exportiert den Bestandsgraphen, nicht die Fachsicht");
        assertThat(tab.exportAccessibleName()).as("auch für Screenreader").isEqualTo(tab.exportTip());
        assertThat(tab.exportChoices()).extracting(KnowledgeGraphTab.ExportChoice::label).contains("Diese Seite");
        tab.domainChoice().doClick();
        assertThat(tab.exportTip()).isEqualTo(KnowledgeGraphTab.EXPORT_TIP);
        assertThat(tab.exportAccessibleName()).isEqualTo(KnowledgeGraphTab.EXPORT_TIP);
    }

    @Test
    void theDomainViewLastsForTheSessionAndAPageChangeStartsItPageWide() {
        tab.page(WIKI);
        tab.showPageNow();
        tab.domainChoice().doClick();
        runner.runAll();
        tab.clickDomainNode("Plan");
        runner.runAll();
        assertThat(tab.overviewButton().isEnabled()).isTrue();

        tab.page(new PageKey("wiki", "2"));
        assertThat(tab.domainChoice().isSelected()).as("gilt für die Sitzung").isTrue();
        assertThat(tab.domainCentre()).isNull();
        assertThat(tab.overviewButton().isEnabled()).isFalse();
        tab.showPageNow();
        runner.runAll();

        assertThat(actions.domainCalls.getLast()).isEqualTo("onPages {wiki=[2]}");
        assertThat(tab.domainCanvas().centred()).isFalse();
        assertThat(tab.statusText()).isEqualTo("3 Fachbegriffe, 2 Verbindungen");
    }

    @Test
    void aLateDomainResultOfALeftPageIsDiscarded() {
        tab.page(WIKI);
        tab.showPageNow();
        tab.domainChoice().doClick();
        tab.page(new PageKey("wiki", "2"));
        runner.runAll();

        assertThat(tab.domainCanvas().labels()).isEmpty();
        assertThat(tab.statusText()).isBlank();
    }

    /** Eine Fachsicht mit {@code nodes} Knoten und {@code edges} Kanten, erfundene Begriffe. */
    private static DomainTermGraph.View view(int nodes, int edges, boolean truncated, int skippedStale) {
        List<DomainTermGraph.Node> list = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            list.add(new DomainTermGraph.Node("t" + i, "Begriff " + i, DomainTermGraph.NodeKind.TERM, 0, null));
        }
        List<DomainTermGraph.Edge> links = new ArrayList<>();
        for (int i = 0; i < edges; i++) {
            links.add(new DomainTermGraph.Edge("t0", "t1", DomainTermGraph.EdgeKind.VERB, "planen", 1, List.of()));
        }
        return new DomainTermGraph.View(DomainTermGraph.Outcome.FOUND, list, links, truncated, skippedStale,
                java.util.Set.of());
    }

    /** Unterseiten wie aus {@code Holdings#descendants}, erfundene Seiten-IDs der Quelle „wiki“. */
    private static PageSelection below(String... pageIds) {
        return new PageSelection(PageSet.of(Map.of("wiki", List.of(pageIds))), HoldingCounts.ZERO, List.of(), true);
    }

    static final class FakeActions implements KnowledgeGraphTab.Actions {

        final List<String> calls = new ArrayList<>();
        boolean empty;
        boolean pageStale;
        boolean confirmed;
        GraphNeighbourhood.Options lastOptions;
        GraphNeighbourhood.Options lastNodeOptions;
        PageSelection lastSubpages;
        int skippedStale;
        /** Wie viele Unterseiten der Core berücksichtigt; negativ: alle übergebenen. */
        int considered = -1;
        /** Wie viele Unterseiten der Core über {@code MAX_SUBPAGES} hinaus nicht berücksichtigt. */
        int notConsidered;
        RuntimeException pagesFailure;
        final List<String> exports = new ArrayList<>();
        RuntimeException exportFailure;
        RuntimeException deleteFailure;
        int deletedPages = 3;
        final List<String> domainCalls = new ArrayList<>();
        final List<DomainTermGraph.Options> domainOptions = new ArrayList<>();
        /** Je Abfrage der Fachsicht, ob sie beim Rechnen schon überholt war. */
        final List<Boolean> cancelledSeen = new ArrayList<>();
        boolean domainEmpty;
        /** Die Mitte ohne Verbindung (around liefert nur sie). */
        boolean aroundAlone;
        RuntimeException domainFailure;
        RuntimeException pageFailure;

        @Override
        public DomainTermGraph.View domainTermGraphOfPages(DomainTermGraph.Options options,
                                                           BooleanSupplier cancelled) {
            domainCalls.add("onPages " + new java.util.TreeMap<>(sorted(options.pages())));
            domainOptions.add(options);
            cancelledSeen.add(cancelled.getAsBoolean());
            if (domainFailure != null) {
                throw domainFailure;
            }
            if (domainEmpty) {
                return new DomainTermGraph.View(DomainTermGraph.Outcome.NO_EDGES, List.of(), List.of(), false, 0,
                        java.util.Set.of());
            }
            return new DomainTermGraph.View(DomainTermGraph.Outcome.FOUND, List.of(
                    new DomainTermGraph.Node("plan", "Plan", DomainTermGraph.NodeKind.TERM, 0, null),
                    new DomainTermGraph.Node("ziel", "Ziel", DomainTermGraph.NodeKind.TERM, 0, null),
                    new DomainTermGraph.Node("nachtlauf", "Nachtlauf", DomainTermGraph.NodeKind.TERM, 0, null)),
                    List.of(new DomainTermGraph.Edge("plan", "ziel", DomainTermGraph.EdgeKind.VERB, "erreichen", 3,
                                    List.of()),
                            new DomainTermGraph.Edge("nachtlauf", "plan", DomainTermGraph.EdgeKind.VERB, "starten", 1,
                                    List.of())), false, 0, java.util.Set.of());
        }

        @Override
        public DomainTermGraph.View domainTermGraph(String start, DomainTermGraph.Options options,
                                                    BooleanSupplier cancelled) {
            domainCalls.add("around " + start + " " + new java.util.TreeMap<>(sorted(options.pages())));
            domainOptions.add(options);
            cancelledSeen.add(cancelled.getAsBoolean());
            if (domainFailure != null) {
                throw domainFailure;
            }
            if (aroundAlone) {
                return new DomainTermGraph.View(DomainTermGraph.Outcome.FOUND, List.of(
                        new DomainTermGraph.Node("plan", "Plan", DomainTermGraph.NodeKind.TERM, 0, null)), List.of(),
                        false, 0, java.util.Set.of());
            }
            return new DomainTermGraph.View(DomainTermGraph.Outcome.FOUND, List.of(
                    new DomainTermGraph.Node("plan", "Plan", DomainTermGraph.NodeKind.TERM, 0, null),
                    new DomainTermGraph.Node("ziel", "Ziel", DomainTermGraph.NodeKind.TERM, 1, "plan")),
                    List.of(new DomainTermGraph.Edge("plan", "ziel", DomainTermGraph.EdgeKind.VERB, "erreichen", 3,
                            List.of())), false, 0, java.util.Set.of());
        }

        private static Map<String, List<String>> sorted(PageSet pages) {
            Map<String, List<String>> sorted = new java.util.TreeMap<>();
            pages.asMap().forEach((source, ids) -> sorted.put(source, ids.stream().sorted().toList()));
            return sorted;
        }

        @Override
        public int deleteAllStatements() {
            calls.add("deleteAll");
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            empty = true;
            return deletedPages;
        }

        @Override
        public JobStatus export(KnowledgeGraphExportUseCase.Scope scope, boolean includeStale) {
            exports.add(scope.describe() + (includeStale ? " mit veralteten" : " nur aktuelle"));
            if (exportFailure != null) {
                throw exportFailure;
            }
            return null;
        }

        @Override
        public GraphNeighbourhood.Subgraph page(PageKey key, GraphNeighbourhood.Options options) {
            if (options.maxNodes() > 1) {
                calls.add("page " + key.sourceId() + "/" + key.pageId());
                lastOptions = options;
                if (pageFailure != null) {
                    throw pageFailure;
                }
            }
            if (empty || pageStale && !options.includeStale()) {
                return GraphNeighbourhood.Subgraph.empty();
            }
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
        public GraphNeighbourhood.Stack pages(PageKey key, PageSelection subpages,
                                              GraphNeighbourhood.Options options) {
            if (options.maxNodes() > 1) {
                calls.add("pages " + key.sourceId() + "/" + key.pageId() + " +" + subpages.size());
                lastSubpages = subpages;
            }
            if (pagesFailure != null) {
                throw pagesFailure;
            }
            int before = calls.size();
            GraphNeighbourhood.Subgraph base = page(key, options);
            calls.subList(before, calls.size()).clear();
            List<GraphNeighbourhood.Node> nodes = new ArrayList<>(base.nodes());
            nodes.add(new GraphNeighbourhood.Node(SUB_DOC, PageGraph.NodeKind.DOCUMENT, "Anhang Alpha", null, SUB_REF,
                    -1, -1, false));
            List<GraphNeighbourhood.Edge> edges = new ArrayList<>(base.edges());
            edges.add(new GraphNeighbourhood.Edge(DOC, SUB_DOC, GraphNeighbourhood.EdgeType.SUBPAGE, "STRUCTURE", null,
                    null, null, true));
            return new GraphNeighbourhood.Stack(new GraphNeighbourhood.Subgraph(DOC, nodes, edges, false, 0),
                    List.of(new PageKey("wiki", "3")), options.includeStale() ? 0 : skippedStale,
                    considered < 0 ? subpages.size() : considered, notConsidered);
        }

        @Override
        public Map<PageKey, String> titles(java.util.Collection<PageKey> keys) {
            return keys.contains(new PageKey("wiki", "3")) ? Map.of(new PageKey("wiki", "3"), "Anhang Beta") : Map.of();
        }

        @Override
        public GraphNeighbourhood.Subgraph node(String nodeId, GraphNeighbourhood.Options options) {
            calls.add("node " + nodeId);
            lastNodeOptions = options;
            GraphNeighbourhood.Node beleg = new GraphNeighbourhood.Node(BELEG, PageGraph.NodeKind.MENTION, "den Beleg",
                    "Beleg", WIKI_REF, 58, 67, false);
            GraphNeighbourhood.Node other = new GraphNeighbourhood.Node(OTHER, PageGraph.NodeKind.MENTION, "Beleg",
                    "Beleg", OTHER_REF, 20, 25, false);
            return new GraphNeighbourhood.Subgraph(BELEG, List.of(beleg, other), List.of(
                    new GraphNeighbourhood.Edge(BELEG, OTHER, confirmed
                            ? GraphNeighbourhood.EdgeType.CONFIRMED_TERM_RELATION
                            : GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE, "beleg",
                            null, null, null, false)), true, 4);
        }

        @Override
        public GraphNeighbourhood.Subgraph term(String term, GraphNeighbourhood.Options options) {
            if (options.maxNodes() > 1) {
                calls.add("term " + term);
                lastOptions = options;
            }
            if (empty) {
                return GraphNeighbourhood.Subgraph.empty();
            }
            GraphNeighbourhood.Node beleg = new GraphNeighbourhood.Node(BELEG, PageGraph.NodeKind.MENTION, "den Beleg",
                    "Beleg", WIKI_REF, 58, 67, false);
            GraphNeighbourhood.Node other = new GraphNeighbourhood.Node(OTHER, PageGraph.NodeKind.MENTION, "Beleg",
                    "Beleg", OTHER_REF, 20, 25, false);
            return new GraphNeighbourhood.Subgraph(BELEG, options.includeStale() ? List.of(beleg, other)
                    : List.of(beleg), List.of(), false, 0);
        }
    }

    static final class DeferredRunner implements KnowledgeGraphTab.Runner {

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

        /** Führt nur die {@code index}-te wartende Arbeit aus (von 0), etwa eine neuere vor einer älteren. */
        void runAt(int index) {
            pending.remove(index).run();
        }

        int pendingCount() {
            return pending.size();
        }
    }
}
