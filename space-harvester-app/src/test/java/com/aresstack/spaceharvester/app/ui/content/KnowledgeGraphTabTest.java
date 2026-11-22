package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
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
    }
}
