package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.services.ServicePlan;
import com.aresstack.spaceharvester.application.background.services.ServicePreview;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.export.KnowledgeGraphExportUseCase;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    /** Ein Knoten, den erst „Wissensgraph erzeugen“ bringt (erfunden). */
    private static final String NACHTLAUF = DOC + "#s2.t4";

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

    // ---- Wissensgraph erzeugen (W5)

    /** Der Knopf steht rechts in der Kopfleiste direkt vor dem Export, mit zugänglichem Namen. */
    @Test
    void theGenerateButtonSitsInTheHeaderRightBeforeTheExport() {
        tab.page(WIKI);
        tab.refreshNow();

        assertThat(tab.generateOffered()).isTrue();
        List<String> tools = tab.headerTools();
        assertThat(tools.indexOf(KnowledgeGraphTab.GENERATE_TIP)).isEqualTo(
                tools.indexOf(KnowledgeGraphTab.EXPORT_TIP) - 1);
        tab.page(null);
        assertThat(tab.generateOffered()).as("ohne Seite gesperrt").isFalse();
    }

    /** Fehlt nichts, lädt der Graph nur neu: keine Rückfrage, kein Start, grau „vollständig“. */
    @Test
    void whenNothingIsMissingTheGraphOnlyReloadsWithoutAQuestion() {
        List<GraphGeneration.Preview> asked = new ArrayList<>();
        tab.confirmGenerateWith(preview -> asked.add(preview));
        showPage();
        actions.preview = preview(AnalyzerChoice.PARSER, 0, List.of(),
                stage("statements", "Aussagenanalyse", ServicePreview.State.NOTHING_MISSING, 0, 0, 0, 0),
                goal(2));

        tab.clickGenerate();
        assertThat(tab.generateOffered()).as("gesperrt, solange gezählt wird").isFalse();
        runner.runAll();

        assertThat(asked).isEmpty();
        assertThat(actions.started).isEmpty();
        assertThat(actions.calls).containsExactly("preview wiki/1", "page wiki/1");
        assertThat(tab.generateNoteForTests().text()).isEqualTo(KnowledgeGraphTab.COMPLETE);
        assertThat(tab.generateNoteForTests().tone()).isEqualTo(StatusNote.Tone.PLAIN);
        assertThat(tab.generateOffered()).isTrue();
    }

    /**
     * Nichts zu rechnen, aber Seiten bleiben ausgelassen (ohne KI-Freigabe, erst laden, abgeschaltete Vorstufe): keine
     * Rückfrage, nur neu laden, aber gelb „nichts zu rechnen“ statt „vollständig“.
     */
    @Test
    void nothingToComputeButPagesLeftOutIsYellowAndNeverComplete() {
        List<GraphGeneration.Preview> asked = new ArrayList<>();
        tab.confirmGenerateWith(preview -> asked.add(preview));
        showPage();

        actions.preview = preview(AnalyzerChoice.MODEL, 3, List.of(),
                stage("statements", "Aussagenanalyse", ServicePreview.State.NOTHING_MISSING, 0, 0, 0, 0), goal(3));
        tab.clickGenerate();
        runner.runAll();
        assertThat(tab.generateNoteForTests().text()).isEqualTo(KnowledgeGraphTab.NOTHING_TO_DO
                + " · 3 ohne KI-Freigabe (werden ausgelassen)");
        assertThat(tab.generateNoteForTests().tone()).isEqualTo(StatusNote.Tone.WARNING);

        String toLoad = "„Aussagenanalyse“: 4 Seiten erst laden – bleiben ausgelassen";
        actions.preview = preview(AnalyzerChoice.PARSER, 0, List.of(toLoad),
                stage("statements", "Aussagenanalyse", ServicePreview.State.NOTHING_MISSING, 0, 4, 0, 0), goal(4));
        tab.clickGenerate();
        runner.runAll();
        assertThat(tab.generateNoteForTests().text()).isEqualTo(KnowledgeGraphTab.NOTHING_TO_DO + " · " + toLoad);
        assertThat(tab.generateNoteForTests().tone()).isEqualTo(StatusNote.Tone.WARNING);

        String off = "„Aussagenanalyse“ ist abgeschaltet und wurde bewusst ausgelassen";
        actions.preview = preview(AnalyzerChoice.PARSER, 0, List.of(off),
                stage("statements", "Aussagenanalyse", ServicePreview.State.DISABLED, 0, 0, 0, 0), goal(2));
        tab.clickGenerate();
        runner.runAll();
        assertThat(tab.generateNoteForTests().text()).isEqualTo(KnowledgeGraphTab.NOTHING_TO_DO + " · " + off);
        assertThat(tab.generateNoteForTests().tone()).isEqualTo(StatusNote.Tone.WARNING);

        assertThat(asked).as("nie eine Rückfrage").isEmpty();
        assertThat(actions.started).isEmpty();
        assertThat(actions.calls).filteredOn(call -> call.startsWith("page")).hasSize(3);
    }

    /** Mit „mit Unterseiten“ gehören die Seite und ihre nicht gesperrten Unterseiten dazu, sonst nur die Seite. */
    @Test
    void theSetIsThePageAndWithSubpagesItsUnlockedSubpages() {
        tab.page(WIKI);
        assertThat(tab.generatePages().asMap()).isEqualTo(Map.of("wiki", java.util.Set.of("1")));

        tab.subpages(new PageSelection(PageSet.of(Map.of("wiki", List.of("2", "3"))), HoldingCounts.ZERO, List.of(),
                true, PageSet.of(Map.of("wiki", List.of("3")))), false);
        tab.confirmGenerateWith(preview -> false);
        actions.preview = missing();
        tab.refreshNow();
        tab.clickGenerate();
        runner.runAll();

        assertThat(actions.previewed).hasSize(1);
        assertThat(actions.previewed.get(0).asMap()).isEqualTo(Map.of("wiki", java.util.Set.of("1", "2")));
    }

    /**
     * Fehlt etwas, fragt das Overlay mit den Zahlen je Stufe: Seiten, gedämpft „erst laden“ und „läuft schon“,
     * abgeschaltete Stufen gelb, Stufen ohne Arbeit gar nicht; „Nein“ startet nichts.
     */
    @Test
    void whenSomethingIsMissingTheQuestionNamesTheNumbersPerStageAndNoStartsNothing() {
        List<GraphGeneration.Preview> asked = new ArrayList<>();
        tab.confirmGenerateWith(preview -> {
            asked.add(preview);
            return false;
        });
        showPage();
        actions.preview = preview(AnalyzerChoice.PARSER, 0, List.of(),
                stage("index", "Indexieren", ServicePreview.State.NOT_WIRED, 0, 0, 0, 0),
                stage("images", "Bilder beschreiben", ServicePreview.State.DISABLED, 0, 0, 0, 0),
                stage("statements", "Aussagenanalyse", ServicePreview.State.PLANNED, 12, 4, 0, 1),
                stage("glossary", "Glossar", ServicePreview.State.RUNNING, 0, 0, 3, 0),
                goal(16));
        actions.calls.clear();

        tab.clickGenerate();
        runner.runAll();

        assertThat(asked).hasSize(1);
        assertThat(GraphGeneration.lines(asked.get(0))).as("Stufe, Zahl, Zusatz je in eigener Spalte").containsExactly(
                new GraphGeneration.Line("Bilder beschreiben", "", "abgeschaltet (wird ausgelassen)", true),
                new GraphGeneration.Line("Aussagenanalyse", "12 Seiten", "4 erst laden", false),
                new GraphGeneration.Line("Glossar", "3 Seiten", "läuft schon", false),
                new GraphGeneration.Line("Wissensgraph", "16 Seiten", "", false));
        assertThat(GraphGeneration.text(asked.get(0))).contains("\nAussagenanalyse · 12 Seiten · 4 erst laden\n");
        assertThat(GraphGeneration.text(asked.get(0))).startsWith("Wissensgraph wirklich erzeugen?")
                .doesNotContain("Indexieren").doesNotContain("KI-Dienst");
        assertThat(GraphGeneration.body(asked.get(0)).getAccessibleContext().getAccessibleName())
                .isEqualTo(GraphGeneration.text(asked.get(0)));
        assertThat(actions.started).as("Nein startet nichts").isEmpty();
        assertThat(actions.calls).as("und lädt nichts").containsExactly("preview wiki/1");
        assertThat(tab.generateOffered()).isTrue();
    }

    /** Mit dem KI-Analysator: wie viele Seiten an den KI-Dienst gehen (nur die Zahl) und gelb die ohne Freigabe. */
    @Test
    void withTheModelAnalyzerTheQuestionCountsPagesForTheAiServiceWithoutAnAmount() {
        GraphGeneration.Preview model = preview(AnalyzerChoice.MODEL, 3, List.of(),
                stage("statements", "Aussagenanalyse", ServicePreview.State.PLANNED, 12, 0, 0, 1), goal(15));
        assertThat(GraphGeneration.modelLine(model)).isEqualTo("12 Seiten gehen an den KI-Dienst.");
        assertThat(GraphGeneration.notReleasedLine(model)).isEqualTo("3 ohne KI-Freigabe (werden ausgelassen)");
        assertThat(GraphGeneration.text(model)).doesNotContain("€").doesNotContain("EUR").doesNotContain("Kosten");

        // Mit dem Parser geht die Aussagenanalyse nicht an den KI-Dienst; ohne Bilder zählt der Core keine Seite ohne
        // Freigabe.
        GraphGeneration.Preview parser = preview(AnalyzerChoice.PARSER, 0, List.of(),
                stage("statements", "Aussagenanalyse", ServicePreview.State.PLANNED, 12, 0, 0, 1), goal(15));
        assertThat(GraphGeneration.modelLine(parser)).isNull();
        assertThat(GraphGeneration.notReleasedLine(parser)).isNull();
    }

    /**
     * „Bilder beschreiben“ gibt immer Seiten an den KI-Dienst, auch mit dem Parser; die gelbe Zeile nennt dann die
     * Bildseiten ohne Freigabe (der Core zählt sie mit). Mit dem KI-Analysator zählt eine Seite, die in beiden Stufen
     * ans Modell ginge, nur einmal.
     */
    @Test
    void imagesToDescribeGoToTheAiServiceWithAnyAnalyzerAndPagesCountOnce() {
        GraphGeneration.Preview parser = new GraphGeneration.Preview(new ServicePreview(List.of(
                stage("images", "Bilder beschreiben", ServicePreview.State.PLANNED, 4, 0, 0, 1),
                stage("statements", "Aussagenanalyse", ServicePreview.State.PLANNED, 2, 0, 0, 1), goal(5)),
                List.of(), 0, Map.of("images", PageSet.of(Map.of("wiki", List.of("1", "2", "3", "4"))),
                "statements", PageSet.of(Map.of("wiki", List.of("1", "5"))))), AnalyzerChoice.PARSER, 2);
        assertThat(GraphGeneration.modelLine(parser)).as("nur die Bildseiten").isEqualTo(
                "4 Seiten gehen an den KI-Dienst.");
        assertThat(GraphGeneration.notReleasedLine(parser)).isEqualTo("2 ohne KI-Freigabe (werden ausgelassen)");
        assertThat(GraphGeneration.text(parser)).contains("\nBilder beschreiben · 4 Seiten\n");

        GraphGeneration.Preview model = new GraphGeneration.Preview(parser.plan(), AnalyzerChoice.MODEL, 2);
        assertThat(GraphGeneration.modelLine(model)).as("Seite 1 in beiden Stufen zählt einmal").isEqualTo(
                "5 Seiten gehen an den KI-Dienst.");
    }

    /**
     * „Ja“ startet die Gruppe; die Statuszeile folgt ihr mit Fortschritt und ✕ (bricht über die Gruppe ab), der Knopf
     * ist gesperrt, und am Ende lädt der Graph genau einmal neu.
     */
    @Test
    void yesStartsTheGroupWhichTheStatusLineFollowsWithProgressAndCancelAndReloadsAtTheEnd() {
        int[] generated = {0};
        tab.onGenerated(() -> generated[0]++);
        List<StatusNote> notes = new ArrayList<>();
        tab.onNote(notes::add);
        tab.confirmGenerateWith(preview -> true);
        showPage();
        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(), 0);
        actions.calls.clear();

        tab.clickGenerate();
        runner.runAll();
        assertThat(actions.calls).as("Ja lädt nicht schon beim Start neu").containsExactly("preview wiki/1");
        actions.calls.clear();

        assertThat(actions.started).hasSize(1);
        assertThat(actions.watched).containsExactly("g-1");
        StatusNote progress = tab.generateNoteForTests();
        assertThat(progress.text()).isEqualTo("Wissensgraph wird erzeugt · Aussagenanalyse: 0 von 2 Teilen fertig");
        assertThat(progress.icon().glyph()).as("ein gezeichnetes ✕, kein Zeichen")
                .isEqualTo(com.aresstack.spaceharvester.app.ui.Glyph.CANCEL);
        assertThat(progress.icon().after()).as("direkt hinter dem Fortschritt").isEqualTo(progress.text());
        assertThat(progress.icon().tooltip()).isEqualTo(KnowledgeGraphTab.CANCEL_TIP);
        assertThat(notes.get(notes.size() - 1).text()).startsWith(progress.text()).contains("3 Knoten");
        assertThat(tab.generateOffered()).as("gesperrt, solange die Gruppe läuft").isFalse();
        assertThat(tab.generateTooltip()).isEqualTo(KnowledgeGraphTab.GENERATE_BUSY_TIP);

        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.RUNNING, "Wissensgraph")));
        assertThat(tab.generateNoteForTests().text()).contains("Wissensgraph: 1 von 2 Teilen fertig");

        tab.generateNoteForTests().icon().action().run();
        runner.runAll();
        assertThat(actions.cancelled).containsExactly("g-1");

        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        assertThat(actions.watchesClosed).isEqualTo(1);
        runner.runAll();

        assertThat(actions.calls).as("am Ende genau einmal neu geladen").containsExactly("page wiki/1");
        assertThat(tab.generateNoteForTests().text()).isEqualTo("Wissensgraph erzeugt · Einzelheiten unter Hintergrund");
        assertThat(tab.generateNoteForTests().tone()).isEqualTo(StatusNote.Tone.PLAIN);
        assertThat(tab.generateNoteForTests().link()).isEqualTo(KnowledgeGraphTab.JOBS_LINK);
        assertThat(generated[0]).isEqualTo(1);
        assertThat(tab.generateOffered()).isTrue();

        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        assertThat(actions.calls).as("späte Meldungen laden nicht noch einmal").hasSize(1);
    }

    /** Angehalten: gelb „angehalten“, der Reiter bleibt dran und folgt dem Fortsetzen bis zum Ende. */
    @Test
    void aPausedGroupStaysAttachedYellowAndIsFollowedAfterResuming() {
        tab.confirmGenerateWith(preview -> true);
        showPage();
        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.RUNNING, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(), 0);
        actions.graphNotes = List.of("Nach dem Neustart nicht von selbst fortgesetzt");
        tab.clickGenerate();
        runner.runAll();
        actions.calls.clear();

        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.PAUSED, "Wissensgraph")));
        runner.runAll();

        StatusNote paused = tab.generateNoteForTests();
        assertThat(paused.tone()).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(paused.text()).startsWith("Wissensgraph angehalten nach 1 von 2 Teilen")
                .contains("Nach dem Neustart nicht von selbst fortgesetzt");
        assertThat(actions.watchesClosed).as("bleibt dran").isZero();
        assertThat(actions.calls).as("lädt beim Anhalten nicht neu").isEmpty();
        assertThat(tab.generateOffered()).isFalse();

        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.RUNNING, "Wissensgraph")));
        assertThat(tab.generateNoteForTests().text()).startsWith(KnowledgeGraphTab.GENERATING);
        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        assertThat(actions.watchesClosed).isEqualTo(1);
        assertThat(actions.calls).containsExactly("page wiki/1");
    }

    /**
     * Angehaltene Vorstufe, der Abschlussteil wartet dahinter: gelb „angehalten“ wie bei einer angehaltenen Gruppe,
     * nicht „1 von 2 fertig“; der Reiter bleibt dran.
     */
    @Test
    void aPausedPrerequisiteWithTheClosingPartHeldBehindItShowsPausedYellow() {
        tab.confirmGenerateWith(preview -> true);
        showPage();
        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.RUNNING, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph", "p-1"))), List.of(), 0);
        tab.clickGenerate();
        runner.runAll();
        actions.calls.clear();

        actions.watcher.accept(group(part("p-1", JobState.PAUSED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph", "p-1")));
        runner.runAll();

        StatusNote paused = tab.generateNoteForTests();
        assertThat(paused.tone()).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(paused.text()).startsWith("Wissensgraph angehalten nach 0 von 2 Teilen")
                .contains("fortsetzen unter " + KnowledgeGraphTab.JOBS_LINK);
        assertThat(actions.watchesClosed).as("bleibt dran").isZero();
        assertThat(actions.calls).as("lädt beim Anhalten nicht neu").isEmpty();
        assertThat(tab.generateOffered()).isFalse();

        actions.watcher.accept(group(part("p-1", JobState.RUNNING, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph", "p-1")));
        assertThat(tab.generateNoteForTests().text())
                .isEqualTo("Wissensgraph wird erzeugt · Aussagenanalyse: 0 von 2 Teilen fertig");

        actions.watcher.accept(group(part("p-1", JobState.PAUSED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph")));
        assertThat(tab.generateNoteForTests().text()).as("ein freier wartender Teil ist nicht angehalten")
                .startsWith(KnowledgeGraphTab.GENERATING).contains("0 von 2 Teilen fertig");
    }

    /**
     * Neue Laufzeit (etwa nach dem Umschalten der Ablage): Der Reiter verfolgt die Gruppe auf ihr weiter und lädt am
     * Ende neu, statt auf der alten hängen zu bleiben.
     */
    @Test
    void afterARuntimeChangeTheGroupIsFollowedOnTheNewRuntimeAndReloadsAtTheEnd() {
        java.util.concurrent.atomic.AtomicReference<FakeActions> runtime = new java.util.concurrent.atomic
                .AtomicReference<>(actions);
        KnowledgeGraphTab tab = new KnowledgeGraphTab(runtime::get, runner);
        int[] generated = {0};
        tab.onGenerated(() -> generated[0]++);
        tab.confirmGenerateWith(preview -> true);
        tab.showingForTests(true);
        tab.page(WIKI);
        runner.runAll();
        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.RUNNING, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph", "p-1"))), List.of(), 0);
        tab.clickGenerate();
        runner.runAll();

        tab.runtimeChanged();
        assertThat(actions.watchesClosed).as("dieselbe Laufzeit: nichts zu tun").isZero();

        FakeActions next = new FakeActions();
        runtime.set(next);
        tab.runtimeChanged();

        assertThat(actions.watchesClosed).as("die alte Beobachtung ist zu").isEqualTo(1);
        assertThat(next.watched).containsExactly("g-1");
        assertThat(tab.generateOffered()).isFalse();
        next.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph", "p-1")));
        runner.runAll();

        assertThat(next.calls).containsExactly("page wiki/1");
        assertThat(generated[0]).isEqualTo(1);
        assertThat(tab.generateOffered()).isTrue();
        assertThat(tab.generateNoteForTests().text()).startsWith("Wissensgraph erzeugt");
    }

    /**
     * Hinweise des Planers und fehlende Abdeckung stehen gelb in der Statuszeile, eine gescheiterte Vorstufe rot mit
     * dem Kreispfeil zum Wiederholen; der Verweis auf die Einzelheiten bleibt grau.
     */
    @Test
    void planNotesAndMissingCoverageAreYellowAndAFailedStageIsRed() {
        List<StatusNote> shown = new ArrayList<>();
        tab.onNote(shown::add);
        tab.confirmGenerateWith(preview -> true);
        showPage();
        actions.preview = missing();
        String skipped = "„Bilder beschreiben“ ist abgeschaltet und wurde bewusst ausgelassen";
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(skipped), 0);
        actions.graphNotes = List.of("DEV: 3 von 5 Seiten mit aktueller Analyse, 2 erst laden");
        tab.clickGenerate();
        runner.runAll();

        assertThat(tab.generateNoteForTests().tone()).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(tab.generateNoteForTests().text()).as("das ✕ vor den Hinweisen, damit es sichtbar bleibt")
                .isEqualTo("Wissensgraph wird erzeugt · Aussagenanalyse: 0 von 2 Teilen fertig · " + skipped);

        actions.watcher.accept(group(part("p-1", JobState.FAILED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();

        StatusNote end = tab.generateNoteForTests();
        String failed = "Vorstufe „Aussagenanalyse“ gescheitert (DEV)";
        String coverage = "DEV: 3 von 5 Seiten mit aktueller Analyse, 2 erst laden";
        assertThat(end.tone()).as("die Zeile hat einen Fehler").isEqualTo(StatusNote.Tone.ERROR);
        assertThat(end.text()).as("die gescheiterte Stufe mit Kreispfeil gleich hinter dem Ergebnis, dann die Hinweise")
                .startsWith("Wissensgraph mit Fehler beendet · " + failed + " · " + skipped).contains(coverage)
                .endsWith("Einzelheiten unter Hintergrund");
        StatusNote line = shown.get(shown.size() - 1);
        String stand = line.text().substring(end.text().length() + 3);
        assertThat(stand).as("der Stand des Graphen dahinter").contains("Knoten");
        assertThat(line.toneOf(stand)).as("bleibt grau, auch hinter Rot").isEqualTo(StatusNote.Tone.PLAIN);
        assertThat(line.toneOf(failed)).isEqualTo(StatusNote.Tone.ERROR);
        assertThat(end.toneOf("Wissensgraph mit Fehler beendet")).isEqualTo(StatusNote.Tone.ERROR);
        assertThat(end.toneOf(failed)).isEqualTo(StatusNote.Tone.ERROR);
        assertThat(end.toneOf(skipped)).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(end.toneOf(coverage)).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(end.toneOf("Einzelheiten unter Hintergrund")).isEqualTo(StatusNote.Tone.PLAIN);
        assertThat(end.icon()).isNotNull();
        assertThat(end.icon().glyph()).isEqualTo(com.aresstack.spaceharvester.app.ui.Glyph.REFRESH);
        assertThat(end.icon().after()).as("der Kreispfeil steht direkt hinter der gescheiterten Stufe").isEqualTo(failed);
        assertThat(end.icon().tooltip()).isEqualTo("„Aussagenanalyse“ wiederholen");
        assertThat(end.link()).isEqualTo(KnowledgeGraphTab.JOBS_LINK);
    }

    /**
     * Der Kreispfeil wiederholt nur die gescheiterten und abgebrochenen Teile der gescheiterten Stufe (über den Weg von
     * ▶), keine anderen Stufen; der Reiter verfolgt die Gruppe wieder, zeigt Fortschritt mit ✕ und lädt am Ende neu.
     */
    @Test
    void theRetryArrowRetriesOnlyTheFailedStageAndFollowsTheGroupAgain() {
        int[] generated = {0};
        tab.onGenerated(() -> generated[0]++);
        tab.confirmGenerateWith(preview -> true);
        showPage();
        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-3", JobState.QUEUED, "Aussagenanalyse"), part("p-4", JobState.QUEUED, "Bilder beschreiben"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(), 0);
        tab.clickGenerate();
        runner.runAll();
        actions.watcher.accept(group(part("p-1", JobState.FAILED, "Aussagenanalyse"),
                part("p-3", JobState.CANCELLED, "Aussagenanalyse"), part("p-4", JobState.FAILED, "Bilder beschreiben"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        assertThat(actions.watchesClosed).isEqualTo(1);
        assertThat(generated[0]).isEqualTo(1);
        assertThat(tab.generateOffered()).isTrue();
        StatusNote end = tab.generateNoteForTests();
        assertThat(end.text()).contains("Vorstufe „Aussagenanalyse“ gescheitert (DEV)")
                .contains("Vorstufe „Bilder beschreiben“ gescheitert (DEV)");
        assertThat(end.icon().after()).isEqualTo("Vorstufe „Aussagenanalyse“ gescheitert (DEV)");
        actions.calls.clear();

        end.icon().action().run();
        assertThat(tab.generateOffered()).as("gesperrt, solange wiederholt wird").isFalse();
        runner.runAll();

        assertThat(actions.retried).as("nur die gescheiterten und abgebrochenen Teile dieser Stufe")
                .containsExactly("g-1 [p-1, p-3]");
        assertThat(actions.watched).as("die Gruppe wird wieder verfolgt").containsExactly("g-1", "g-1");
        actions.watcher.accept(group(part("p-1", JobState.RUNNING, "Aussagenanalyse"),
                part("p-3", JobState.QUEUED, "Aussagenanalyse"), part("p-4", JobState.FAILED, "Bilder beschreiben"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        assertThat(tab.generateNoteForTests().text())
                .startsWith("Wissensgraph wird erzeugt · Aussagenanalyse: 2 von 4 Teilen fertig");
        assertThat(tab.generateNoteForTests().icon().glyph()).isEqualTo(com.aresstack.spaceharvester.app.ui.Glyph.CANCEL);
        assertThat(tab.generateOffered()).isFalse();

        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-3", JobState.COMPLETED, "Aussagenanalyse"), part("p-4", JobState.FAILED, "Bilder beschreiben"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        assertThat(actions.watchesClosed).isEqualTo(2);
        assertThat(actions.calls).as("am Ende wieder neu geladen").containsExactly("page wiki/1");
        assertThat(generated[0]).isEqualTo(2);
        StatusNote again = tab.generateNoteForTests();
        assertThat(again.text()).doesNotContain("„Aussagenanalyse“ gescheitert")
                .contains("Vorstufe „Bilder beschreiben“ gescheitert (DEV)");
        assertThat(again.icon().tooltip()).as("jetzt wiederholt der Pfeil die übrige Stufe")
                .isEqualTo("„Bilder beschreiben“ wiederholen");

        again.icon().action().run();
        runner.runAll();
        assertThat(actions.retried).containsExactly("g-1 [p-1, p-3]", "g-1 [p-4]");
    }

    /**
     * Lässt sich nichts fortsetzen, sagt der Kreispfeil das: ohne Grund gelb „nichts zu wiederholen“ hinter der
     * gescheiterten Stufe, mit Grund (etwa ein fremder Eigentümer) rot; die Gruppe wird nicht noch einmal „fertig“ und
     * nicht neu geladen. Auch angehalten bleibt die Statuszeile nicht bei „wird wiederholt …“ stehen.
     */
    @Test
    void theRetryArrowSaysWhenNothingWasResumed() {
        int[] generated = {0};
        tab.onGenerated(() -> generated[0]++);
        tab.confirmGenerateWith(preview -> true);
        showPage();
        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(), 0);
        tab.clickGenerate();
        runner.runAll();
        actions.watcher.accept(group(part("p-1", JobState.FAILED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        String failed = "Vorstufe „Aussagenanalyse“ gescheitert (DEV)";
        actions.calls.clear();

        actions.retryOutcome = new com.aresstack.spaceharvester.app.runtime.AllJobs.Outcome(0, 0, null);
        tab.generateNoteForTests().icon().action().run();
        runner.runAll();

        StatusNote nothing = tab.generateNoteForTests();
        assertThat(nothing.text()).contains(failed + " · nichts zu wiederholen")
                .endsWith("Einzelheiten unter Hintergrund");
        assertThat(nothing.toneOf("nichts zu wiederholen")).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(nothing.toneOf(failed)).isEqualTo(StatusNote.Tone.ERROR);
        assertThat(nothing.icon()).as("kein Kreispfeil, der wieder nichts täte").isNull();
        assertThat(actions.watched).as("nicht wieder verfolgt").containsExactly("g-1");
        assertThat(actions.calls).as("nicht neu geladen").isEmpty();
        assertThat(generated[0]).isEqualTo(1);
        assertThat(tab.generateOffered()).isTrue();

        // Mit Grund: rot, mit dem Grund.
        tab.clickGenerate();
        runner.runAll();
        actions.watcher.accept(group(part("p-1", JobState.FAILED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        actions.retryOutcome = new com.aresstack.spaceharvester.app.runtime.AllJobs.Outcome(0, 1,
                new IllegalStateException("Nur der Eigentümer kann fortsetzen"));
        tab.generateNoteForTests().icon().action().run();
        runner.runAll();
        assertThat(tab.generateNoteForTests().text()).startsWith("„Aussagenanalyse“ ließ sich nicht wiederholen");
        assertThat(tab.generateNoteForTests().tone()).isEqualTo(StatusNote.Tone.ERROR);

        // Angehalten (der Abschlussteil wartet hinter einem angehaltenen Teil): kein Ereignis kommt, trotzdem eine
        // Antwort statt „wird wiederholt …“.
        tab.clickGenerate();
        runner.runAll();
        actions.watcher.accept(group(part("p-1", JobState.FAILED, "Aussagenanalyse"),
                part("p-3", JobState.PAUSED, "Bilder beschreiben"),
                part("p-2", JobState.QUEUED, "Wissensgraph", "p-3")));
        runner.runAll();
        assertThat(tab.generateNoteForTests().text()).startsWith("Wissensgraph angehalten");
        actions.retryOutcome = new com.aresstack.spaceharvester.app.runtime.AllJobs.Outcome(0, 0, null);
        tab.generateNoteForTests().icon().action().run();
        runner.runAll();
        assertThat(tab.generateNoteForTests().text()).startsWith("Wissensgraph angehalten")
                .contains(failed + " · nichts zu wiederholen").doesNotContain("wird wiederholt");
    }

    /**
     * Wechselt die Laufzeit, während der Kreispfeil wiederholt, verfolgt der Reiter die Gruppe danach auf der neuen
     * Laufzeit, nie auf der alten.
     */
    @Test
    void aRetryDuringARuntimeChangeFollowsTheGroupOnTheNewRuntime() {
        java.util.concurrent.atomic.AtomicReference<FakeActions> runtime = new java.util.concurrent.atomic
                .AtomicReference<>(actions);
        KnowledgeGraphTab tab = new KnowledgeGraphTab(runtime::get, runner);
        tab.confirmGenerateWith(preview -> true);
        tab.showingForTests(true);
        tab.page(WIKI);
        runner.runAll();
        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(), 0);
        tab.clickGenerate();
        runner.runAll();
        actions.watcher.accept(group(part("p-1", JobState.FAILED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();

        tab.generateNoteForTests().icon().action().run();
        FakeActions next = new FakeActions();
        runtime.set(next);
        tab.runtimeChanged();
        runner.runAll();

        assertThat(actions.retried).containsExactly("g-1 [p-1]");
        assertThat(actions.watched).as("auf der alten nicht wieder").containsExactly("g-1");
        assertThat(next.watched).containsExactly("g-1");
        next.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        assertThat(tab.generateNoteForTests().text()).startsWith("Wissensgraph erzeugt");
        assertThat(tab.generateOffered()).isTrue();
    }

    /**
     * Läuft über die Seite schon eine Gruppe des Aufrufers, die der Reiter nicht selbst gestartet hat (nach einem
     * Neustart fortgesetzt, anderswo gestartet), verfolgt er sie wie eine eigene: Knopf gesperrt mit „wird schon
     * erzeugt“, Fortschritt mit ✕, am Ende neu laden; ein Klick legt keine zweite an.
     */
    @Test
    void aGroupAlreadyRunningForThePageIsFollowedLikeAnOwnOne() {
        int[] generated = {0};
        tab.onGenerated(() -> generated[0]++);
        actions.running = group(part("p-1", JobState.RUNNING, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph", "p-1"));
        showPage();

        assertThat(actions.lookedUp).hasSize(1);
        assertThat(actions.lookedUp.get(0).pagesOf("wiki")).containsExactly("1");
        assertThat(actions.watched).containsExactly("g-1");
        assertThat(tab.generateOffered()).isFalse();
        assertThat(tab.generateTooltip()).isEqualTo("Wissensgraph wird schon erzeugt");
        StatusNote progress = tab.generateNoteForTests();
        assertThat(progress.text()).startsWith("Wissensgraph wird erzeugt · Aussagenanalyse: 0 von 2 Teilen fertig");
        assertThat(progress.icon().glyph()).isEqualTo(com.aresstack.spaceharvester.app.ui.Glyph.CANCEL);
        tab.clickGenerate();
        runner.runAll();
        assertThat(actions.previewed).isEmpty();
        assertThat(actions.started).isEmpty();

        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph", "p-1")));
        runner.runAll();
        assertThat(actions.calls).as("am Ende neu geladen").containsExactly("page wiki/1");
        assertThat(generated[0]).isEqualTo(1);
        assertThat(tab.generateOffered()).isTrue();
        assertThat(tab.generateNoteForTests().text()).startsWith("Wissensgraph erzeugt");
    }

    /** Nach dem Wechsel der Laufzeit fragt der Reiter einmal, ob dort schon eine Gruppe läuft; angehalten gelb. */
    @Test
    void afterARuntimeChangeARunningGroupOfTheNewRuntimeIsFollowed() {
        java.util.concurrent.atomic.AtomicReference<FakeActions> runtime = new java.util.concurrent.atomic
                .AtomicReference<>(actions);
        KnowledgeGraphTab tab = new KnowledgeGraphTab(runtime::get, runner);
        tab.showingForTests(true);
        tab.page(WIKI);
        runner.runAll();
        assertThat(actions.watched).isEmpty();
        assertThat(tab.generateOffered()).isTrue();

        FakeActions next = new FakeActions();
        next.running = group(part("p-1", JobState.PAUSED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph", "p-1"));
        runtime.set(next);
        tab.runtimeChanged();
        runner.runAll();

        assertThat(next.watched).containsExactly("g-1");
        assertThat(tab.generateOffered()).isFalse();
        assertThat(tab.generateNoteForTests().text()).startsWith("Wissensgraph angehalten");
        assertThat(tab.generateNoteForTests().tone()).isEqualTo(StatusNote.Tone.WARNING);
    }

    /**
     * „ohne Zugriff“ ist rot, Ausgelassenes gelb, auch im Fortschritt; der Fortschritt selbst bleibt grau mit ✕. Am
     * Ende ohne Fehler bleibt „Wissensgraph erzeugt“ grau.
     */
    @Test
    void withoutAccessIsRedAndLeftOutIsYellowWhileTheProgressStaysPlain() {
        tab.confirmGenerateWith(preview -> true);
        showPage();
        actions.preview = missing();
        String hidden = "2 Seiten ohne Zugriff ausgelassen";
        String skipped = "„Bilder beschreiben“ ist abgeschaltet und wurde bewusst ausgelassen";
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(skipped, hidden), 2);
        tab.clickGenerate();
        runner.runAll();

        StatusNote progress = tab.generateNoteForTests();
        String head = "Wissensgraph wird erzeugt · Aussagenanalyse: 0 von 2 Teilen fertig";
        assertThat(progress.text()).isEqualTo(head + " · " + skipped + " · " + hidden);
        assertThat(progress.tone()).isEqualTo(StatusNote.Tone.ERROR);
        assertThat(progress.toneOf(head)).isEqualTo(StatusNote.Tone.PLAIN);
        assertThat(progress.toneOf(skipped)).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(progress.toneOf(hidden)).isEqualTo(StatusNote.Tone.ERROR);
        assertThat(progress.icon().glyph()).isEqualTo(com.aresstack.spaceharvester.app.ui.Glyph.CANCEL);
        assertThat(progress.icon().glyph()).as("kein Kreispfeil ohne gescheiterte Stufe, nur das ✕")
                .isEqualTo(com.aresstack.spaceharvester.app.ui.Glyph.CANCEL);

        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        StatusNote end = tab.generateNoteForTests();
        assertThat(end.text()).startsWith("Wissensgraph erzeugt · " + skipped + " · " + hidden);
        assertThat(end.toneOf("Wissensgraph erzeugt")).isEqualTo(StatusNote.Tone.PLAIN);
        assertThat(end.toneOf(hidden)).isEqualTo(StatusNote.Tone.ERROR);
        assertThat(end.toneOf(skipped)).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(end.icon()).isNull();
    }

    /** „Wissensgraph ist vollständig“ steht nur kurz: der einmalige Timer räumt es weg, aber keine neuere Meldung. */
    @Test
    void theCompleteMessageIsShownBrieflyAndClearedOnce() {
        List<Runnable> timers = new ArrayList<>();
        tab.clearSoon(timers::add);
        List<StatusNote> notes = new ArrayList<>();
        tab.onNote(notes::add);
        showPage();
        actions.preview = preview(AnalyzerChoice.PARSER, 0, List.of(),
                stage("statements", "Aussagenanalyse", ServicePreview.State.NOTHING_MISSING, 0, 0, 0, 0), goal(2));

        tab.clickGenerate();
        runner.runAll();
        assertThat(tab.generateNoteForTests().text()).isEqualTo(KnowledgeGraphTab.COMPLETE);
        assertThat(notes.get(notes.size() - 1).text()).startsWith(KnowledgeGraphTab.COMPLETE + " · 3 Knoten");
        assertThat(timers).as("ein einmaliger Timer, kein Abfragen").hasSize(1);

        timers.get(0).run();
        assertThat(tab.generateNoteForTests()).isNull();
        assertThat(notes.get(notes.size() - 1).text()).startsWith("3 Knoten");

        // Ein später Timer räumt keine neuere Meldung weg.
        tab.clickGenerate();
        runner.runAll();
        assertThat(timers).hasSize(2);
        actions.preview = preview(AnalyzerChoice.MODEL, 3, List.of(),
                stage("statements", "Aussagenanalyse", ServicePreview.State.NOTHING_MISSING, 0, 0, 0, 0), goal(3));
        tab.clickGenerate();
        runner.runAll();
        assertThat(timers).as("gelb „nichts zu rechnen“ bleibt stehen").hasSize(2);
        timers.get(1).run();
        assertThat(tab.generateNoteForTests().text()).startsWith(KnowledgeGraphTab.NOTHING_TO_DO);

        // Mit der nächsten Seite ist auch „vollständig“ weg.
        actions.preview = preview(AnalyzerChoice.PARSER, 0, List.of(),
                stage("statements", "Aussagenanalyse", ServicePreview.State.NOTHING_MISSING, 0, 0, 0, 0), goal(2));
        tab.clickGenerate();
        runner.runAll();
        tab.page(new PageKey("wiki", "2"));
        assertThat(tab.generateNoteForTests()).isNull();
    }

    /**
     * Am Ende lädt der Graph neu, ohne zu verspringen: Zoom und Ausschnitt bleiben, vorhandene Knoten behalten ihren
     * Platz, neue kommen dazu, die Auswahl bleibt; „Zurück“ bekommt keinen Eintrag. Ist der gewählte Knoten weg, ist
     * auch die Auswahl weg.
     */
    @Test
    void theReloadAtTheEndKeepsViewportPositionsAndSelection() {
        tab.setSize(900, 600);
        layoutTree(tab);
        tab.confirmGenerateWith(preview -> true);
        showPage();
        tab.zoomAt(1.3, 120, 80);
        tab.selectNode(BELEG);
        double[] viewport = tab.viewport();
        java.awt.geom.Point2D doc = tab.positionOf(DOC);
        java.awt.geom.Point2D beleg = tab.positionOf(BELEG);
        boolean back = tab.backOffered();

        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(), 0);
        tab.clickGenerate();
        runner.runAll();
        actions.calls.clear();
        actions.grown = true;
        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();

        assertThat(actions.calls).containsExactly("page wiki/1");
        assertThat(tab.nodeIds()).as("der neue Knoten ist da").contains(NACHTLAUF);
        assertThat(tab.viewport()).as("Zoom und Ausschnitt bleiben").containsExactly(viewport);
        assertThat(tab.positionOf(DOC)).isEqualTo(doc);
        assertThat(tab.positionOf(BELEG)).isEqualTo(beleg);
        assertThat(tab.positionOf(NACHTLAUF)).isNotNull().isNotEqualTo(doc).isNotEqualTo(beleg);
        assertThat(tab.selectedNode()).as("die Auswahl bleibt").isEqualTo(BELEG);
        assertThat(tab.detailsText()).contains("den Beleg");
        assertThat(tab.backOffered()).as("kein neuer Eintrag für „Zurück“").isEqualTo(back);

        // Nächster Lauf: der gewählte Knoten ist weg, also auch die Auswahl; der Ausschnitt bleibt trotzdem.
        tab.clickGenerate();
        runner.runAll();
        actions.dropBeleg = true;
        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        assertThat(tab.nodeIds()).doesNotContain(BELEG);
        assertThat(tab.selectedNode()).isNull();
        assertThat(tab.viewport()).containsExactly(viewport);
        assertThat(tab.positionOf(DOC)).isEqualTo(doc);
    }

    /**
     * Auch Knoten aus „Nachbarn erweitern“ und verschobene behalten beim Neuladen am Ende ihren Platz; neue Knoten des
     * Einstiegs (der Lauf brachte „Nachtlauf“) suchen sich erst danach einen freien.
     */
    @Test
    void theReloadAtTheEndKeepsExpandedAndDraggedNodesInPlace() {
        tab.setSize(900, 600);
        layoutTree(tab);
        tab.confirmGenerateWith(preview -> true);
        showPage();
        tab.selectNode(BELEG);
        tab.clickExpand();
        runner.runAll();
        assertThat(tab.nodeIds()).contains(OTHER);
        tab.dragNode(OTHER, 420, -260);
        java.util.Map<String, java.awt.geom.Point2D> before = new java.util.LinkedHashMap<>();
        for (String id : tab.nodeIds()) {
            before.put(id, tab.positionOf(id));
        }

        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(), 0);
        tab.clickGenerate();
        runner.runAll();
        actions.calls.clear();
        actions.grown = true;
        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();

        assertThat(actions.calls).as("Einstieg und Erweiterung neu abgespielt").containsExactly("page wiki/1",
                "node " + BELEG);
        assertThat(tab.nodeIds()).contains(NACHTLAUF, OTHER);
        before.forEach((id, at) -> assertThat(tab.positionOf(id)).as("Platz von %s", id).isEqualTo(at));
        java.awt.geom.Point2D added = tab.positionOf(NACHTLAUF);
        assertThat(before.values()).as("der neue Knoten liegt auf keinem vorhandenen").noneMatch(at ->
                Math.abs(at.getX() - added.getX()) < 1 && Math.abs(at.getY() - added.getY()) < 1);
    }

    /** Unsichtbar am Ende: Der Reiter lädt beim nächsten Anzeigen neu, ebenfalls ohne zu verspringen. */
    @Test
    void finishedWhileHiddenReloadsInPlaceWhenShownAgain() {
        tab.setSize(900, 600);
        layoutTree(tab);
        tab.confirmGenerateWith(preview -> true);
        showPage();
        tab.zoomAt(0.8, 40, 40);
        tab.selectNode(STATEMENT);
        double[] viewport = tab.viewport();
        actions.preview = missing();
        actions.plan = new ServicePlan(Optional.of(group(part("p-1", JobState.QUEUED, "Aussagenanalyse"),
                part("p-2", JobState.QUEUED, "Wissensgraph"))), List.of(), 0);
        tab.clickGenerate();
        runner.runAll();
        actions.calls.clear();

        tab.showingForTests(false);
        actions.watcher.accept(group(part("p-1", JobState.COMPLETED, "Aussagenanalyse"),
                part("p-2", JobState.COMPLETED, "Wissensgraph")));
        runner.runAll();
        assertThat(actions.calls).as("unsichtbar lädt nichts").isEmpty();

        tab.showingForTests(true);
        tab.reloadInPlace();
        runner.runAll();
        assertThat(actions.calls).containsExactly("page wiki/1");
        assertThat(tab.viewport()).containsExactly(viewport);
        assertThat(tab.selectedNode()).isEqualTo(STATEMENT);
    }

    /**
     * Kopfleiste bei 900 px (UX): eine Zeile, nichts überdeckt sich; der Hinweis läuft nicht ins Suchfeld, die Knöpfe
     * samt „Wissensgraph erzeugen“ behalten ihre volle Breite. Auch schmaler bleibt es dabei.
     */
    @Test
    void atNineHundredPixelsTheHeaderStaysOneLineWithoutOverlap() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            tab.page(WIKI);
            tab.refreshNow();
            com.aresstack.spaceharvester.app.ui.controls.TabHeader header = tab.header();
            for (int width : new int[] {900, 760, 640}) {
                header.setSize(width, header.getPreferredSize().height);
                layoutTree(header);
                layoutTree(header);
                java.awt.Component left = header.getComponent(0);
                java.awt.Component search = header.getComponent(1);
                java.awt.Component right = header.getComponent(2);
                int row = Math.max(left.getPreferredSize().height, Math.max(search.getPreferredSize().height,
                        right.getPreferredSize().height));
                assertThat(header.getPreferredSize().height).as("eine Zeile bei %d px", width).isEqualTo(row);
                for (java.awt.Component part : new java.awt.Component[] {left, search, right}) {
                    assertThat(part.getY()).isGreaterThanOrEqualTo(0);
                    assertThat(part.getY() + part.getHeight()).as("innerhalb der Zeile bei %d px", width)
                            .isLessThanOrEqualTo(header.getHeight());
                }
                assertThat(left.getBounds().intersects(search.getBounds())).as("links vor dem Suchfeld").isFalse();
                assertThat(search.getBounds().intersects(right.getBounds())).as("Suchfeld vor den Knöpfen").isFalse();
                assertThat(left.getX() + left.getWidth()).isLessThanOrEqualTo(search.getX());
                assertThat(search.getWidth()).as("Suchfeld bei %d px", width).isGreaterThanOrEqualTo(
                        Math.min(160, search.getPreferredSize().width));
                for (java.awt.Component shown : ((java.awt.Container) left).getComponents()) {
                    if (shown.getWidth() > 0) {
                        java.awt.Rectangle at = javax.swing.SwingUtilities.convertRectangle(left, shown.getBounds(),
                                header);
                        assertThat(at.x + at.width).as("Titel, Abzeichen und Hinweis enden vor dem Suchfeld")
                                .isLessThanOrEqualTo(search.getX());
                    }
                }
                List<java.awt.Rectangle> buttons = new ArrayList<>();
                collectButtons(right, header, buttons);
                assertThat(buttons).as("Aktionen, Kopieren und Werkzeuge").hasSizeGreaterThanOrEqualTo(10);
                for (int i = 0; i < buttons.size(); i++) {
                    assertThat(buttons.get(i).getCenterY()).as("alle Knöpfe auf einer Linie")
                            .isCloseTo(buttons.get(0).getCenterY(), org.assertj.core.data.Offset.offset(1.0));
                    for (int j = i + 1; j < buttons.size(); j++) {
                        assertThat(buttons.get(i).intersects(buttons.get(j))).isFalse();
                    }
                }
                assertThat(tab.generateOffered()).isTrue();
            }
        });
    }

    private static void collectButtons(java.awt.Component component, java.awt.Component header,
                                       List<java.awt.Rectangle> into) {
        if (component instanceof javax.swing.AbstractButton button && button.isVisible()) {
            assertThat(button.getWidth()).as("volle Breite: %s", button.getToolTipText())
                    .isEqualTo(button.getPreferredSize().width);
            into.add(javax.swing.SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), header));
            return;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectButtons(child, header, into);
            }
        }
    }

    private static void layoutTree(java.awt.Component component) {
        if (component instanceof java.awt.Container container) {
            container.doLayout();
            for (java.awt.Component child : container.getComponents()) {
                layoutTree(child);
            }
        }
    }

    /** Die Rückfrage hat keinen Bildlauf; der Satz zum KI-Dienst steht als letzte Zeile direkt über Ja/Nein. */
    @Test
    void theQuestionHasNoScrollingAndTheAiSentenceIsTheLastLineAboveTheButtons() {
        GraphGeneration.Preview model = preview(AnalyzerChoice.MODEL, 3, List.of(),
                stage("statements", "Aussagenanalyse", ServicePreview.State.PLANNED, 12, 0, 0, 1), goal(15));
        model = new GraphGeneration.Preview(new ServicePreview(model.plan().stages(), List.of(), 1),
                AnalyzerChoice.MODEL, 3);
        javax.swing.JComponent body = GraphGeneration.body(model);
        java.awt.Component last = body.getComponent(body.getComponentCount() - 1);
        assertThat(((javax.swing.JLabel) last).getText()).isEqualTo("12 Seiten gehen an den KI-Dienst.");
        javax.swing.JLabel notReleased = label(body, "3 ohne KI-Freigabe (werden ausgelassen)");
        javax.swing.JLabel hidden = label(body, "1 Seite ohne Zugriff (wird ausgelassen)");
        assertThat(notReleased.getForeground()).as("gelb").isEqualTo(com.aresstack.spaceharvester.app.ui.Comic
                .STANDING_UNCLEAR);
        assertThat(hidden.getForeground()).as("rot").isEqualTo(com.aresstack.spaceharvester.app.ui.Comic.RED);
        assertThat(label(body, "12 Seiten")).as("die Zahl in eigener Spalte").isNotNull();
        assertThat(scrolls(body)).isFalse();
    }

    private static javax.swing.JLabel label(java.awt.Container container, String text) {
        for (java.awt.Component c : container.getComponents()) {
            if (c instanceof javax.swing.JLabel label && text.equals(label.getText())) {
                return label;
            }
        }
        throw new AssertionError("keine Zeile „" + text + "“");
    }

    private static boolean scrolls(java.awt.Component component) {
        if (component instanceof javax.swing.JScrollPane) {
            return true;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                if (scrolls(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ein abgeschaltetes Ziel startet nichts; die Statuszeile sagt gelb, warum. */
    @Test
    void aDisabledGoalStartsNothingAndSaysWhy() {
        showPage();
        String off = "„Wissensgraph“ ist abgeschaltet und wurde bewusst ausgelassen";
        actions.preview = preview(AnalyzerChoice.PARSER, 0, List.of(off),
                new ServicePreview.Stage("knowledge-graph", "Wissensgraph", ServicePreview.State.DISABLED, 0, 0, 0,
                        0));

        tab.clickGenerate();
        runner.runAll();

        assertThat(actions.started).isEmpty();
        assertThat(tab.generateNoteForTests().text()).isEqualTo(KnowledgeGraphTab.NOT_GENERATED + " · " + off);
        assertThat(tab.generateNoteForTests().tone()).isEqualTo(StatusNote.Tone.WARNING);
    }

    private void showPage() {
        tab.showingForTests(true);
        tab.page(WIKI);
        runner.runAll();
        actions.calls.clear();
    }

    private static GraphGeneration.Preview missing() {
        return preview(AnalyzerChoice.PARSER, 0, List.of(),
                stage("statements", "Aussagenanalyse", ServicePreview.State.PLANNED, 1, 0, 0, 1), goal(1));
    }

    private static GraphGeneration.Preview preview(AnalyzerChoice analyzer, int notReleased, List<String> notes,
                                                   ServicePreview.Stage... stages) {
        return new GraphGeneration.Preview(new ServicePreview(List.of(stages), notes, 0), analyzer, notReleased);
    }

    private static ServicePreview.Stage stage(String id, String title, ServicePreview.State state, int pages,
                                              int toLoad, int reused, int parts) {
        return new ServicePreview.Stage(id, title, state, pages, toLoad, reused, parts);
    }

    private static ServicePreview.Stage goal(int pages) {
        return stage("knowledge-graph", "Wissensgraph", ServicePreview.State.PLANNED, pages, 0, 0, 1);
    }

    private static BackgroundGroup group(JobStatus... parts) {
        return new BackgroundGroup("g-1", "knowledge-graph", "Wissensgraph", List.of(parts));
    }

    /** Ein Teil der Gruppe in Space „DEV“ der Quelle „wiki“, mit dem Titel seiner Stufe (auch ihre Art). */
    private static JobStatus part(String id, JobState state, String stage) {
        return new JobStatus(id, JobType.BACKGROUND, "wiki", "DEV", "app", state, JobProgress.NONE,
                state == JobState.FAILED ? new com.aresstack.spaceharvester.application.jobs.JobError("TEST", "Fehler")
                        : null, false,
                Instant.EPOCH, null, null, "1", null, Map.of(BackgroundJobs.GROUP, "g-1", BackgroundJobs.STEP_TITLE,
                stage, BackgroundJobs.KIND, kind(stage)), false, false, null);
    }

    /** Die Art einer Stufe im Dienst-Planer: der Abschlussteil „Wissensgraph“ oder eine Vorstufe. */
    private static String kind(String stage) {
        return switch (stage) {
            case "Wissensgraph" -> com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.KIND;
            case "Aussagenanalyse" -> "statements";
            default -> "images";
        };
    }

    /** Wie {@link #part(String, JobState, String)}, wartend hinter {@code after} ({@code Jobs#AFTER}). */
    private static JobStatus part(String id, JobState state, String stage, String after) {
        return new JobStatus(id, JobType.BACKGROUND, "wiki", "DEV", "app", state, JobProgress.NONE, null, false,
                Instant.EPOCH, null, null, "1", null, Map.of(BackgroundJobs.GROUP, "g-1", BackgroundJobs.STEP_TITLE,
                stage, BackgroundJobs.KIND, kind(stage), com.aresstack.spaceharvester.application.jobs.Jobs.AFTER,
                after), false, false, null);
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
        GraphGeneration.Preview preview;
        ServicePlan plan = new ServicePlan(Optional.empty(), List.of(), 0);
        final List<PageSet> previewed = new ArrayList<>();
        final List<PageSet> started = new ArrayList<>();
        final List<String> watched = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        Consumer<BackgroundGroup> watcher;
        int watchesClosed;
        List<String> graphNotes = List.of();
        final List<String> retried = new ArrayList<>();
        /** Nach „Wissensgraph erzeugen“: ein neuer Knoten „Nachtlauf“ auf der Seite. */
        boolean grown;
        /** Der Knoten „den Beleg“ fehlt (etwa nach einer neuen Analyse). */
        boolean dropBeleg;

        /** Was der Kreispfeil bewirkt; {@code null}: alle übergebenen Teile fortgesetzt. */
        com.aresstack.spaceharvester.app.runtime.AllJobs.Outcome retryOutcome;
        /** Eine laufende Gruppe, die der Reiter nicht selbst gestartet hat; {@code null} ohne. */
        BackgroundGroup running;
        final List<PageSet> lookedUp = new ArrayList<>();
        Object origin = this;

        @Override
        public com.aresstack.spaceharvester.app.runtime.AllJobs.Outcome retryGraph(String groupId,
                List<String> jobIds) {
            retried.add(groupId + " " + jobIds);
            return retryOutcome != null ? retryOutcome
                    : new com.aresstack.spaceharvester.app.runtime.AllJobs.Outcome(jobIds.size(), 0, null);
        }

        @Override
        public Optional<BackgroundGroup> runningGraph(PageSet pages) {
            lookedUp.add(pages);
            return Optional.ofNullable(running);
        }

        @Override
        public Object origin() {
            return origin;
        }

        @Override
        public GraphGeneration.Preview previewGraph(PageSet pages) {
            previewed.add(pages);
            calls.add("preview " + pages.asMap().entrySet().iterator().next().getKey() + "/"
                    + String.join(",", new java.util.TreeSet<>(pages.asMap().values().iterator().next())));
            return preview;
        }

        @Override
        public ServicePlan startGraph(PageSet pages) {
            started.add(pages);
            return plan;
        }

        @Override
        public AutoCloseable watchGraph(String groupId, Consumer<BackgroundGroup> listener) {
            watched.add(groupId);
            watcher = listener;
            return () -> watchesClosed++;
        }

        @Override
        public void cancelGraph(String groupId) {
            cancelled.add(groupId);
        }

        @Override
        public List<String> graphNotes(BackgroundGroup group) {
            return graphNotes;
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
            if (grown || dropBeleg) {
                List<GraphNeighbourhood.Node> nodes = new ArrayList<>(List.of(doc, statement));
                List<GraphNeighbourhood.Edge> edges = new ArrayList<>(List.of(new GraphNeighbourhood.Edge(DOC,
                        STATEMENT, GraphNeighbourhood.EdgeType.CONTAINS, "STRUCTURE", null, null, null, true)));
                if (!dropBeleg) {
                    nodes.add(beleg);
                    edges.add(new GraphNeighbourhood.Edge(DOC, BELEG, GraphNeighbourhood.EdgeType.CONTAINS,
                            "STRUCTURE", null, null, null, true));
                }
                if (grown) {
                    nodes.add(new GraphNeighbourhood.Node(NACHTLAUF, PageGraph.NodeKind.MENTION, "den Nachtlauf",
                            "Nachtlauf", WIKI_REF, 80, 93, false));
                    edges.add(new GraphNeighbourhood.Edge(DOC, NACHTLAUF, GraphNeighbourhood.EdgeType.CONTAINS,
                            "STRUCTURE", null, null, null, true));
                }
                return new GraphNeighbourhood.Subgraph(DOC, nodes, edges, false, 0);
            }
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
