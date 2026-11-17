package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeLogListener;
import com.aresstack.spaceharvester.application.changelog.ChangeReport;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.changelog.ReportedPage;
import com.aresstack.spaceharvester.application.changelog.SummaryPage;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.changelog.UpdateText;
import com.aresstack.spaceharvester.application.changelog.ContentDiff;
import com.aresstack.spaceharvester.application.changelog.ContentChange;
import com.aresstack.spaceharvester.application.changelog.ParagraphChange;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.observe.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SourceUpdatesViewTest {

    private static final Instant FIRST = Instant.parse("2026-11-10T08:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-11-16T18:30:00Z");

    private final FakeActions actions = new FakeActions();
    /** „Text“: die Änderungen nach Art. */
    private final SourceUpdatesView view = new SourceUpdatesView(() -> actions, new SyncRunner(),
            SourceUpdatesView.Mode.CHANGES);
    /** „Leseansicht“: die KI-Zusammenfassung. */
    private final SourceUpdatesView summary = new SourceUpdatesView(() -> actions, new SyncRunner(),
            SourceUpdatesView.Mode.SUMMARY);
    private final List<ReportedPage> opened = new ArrayList<>();
    private final List<String> status = new ArrayList<>();
    private boolean visible = true;

    @BeforeEach
    void setUp() {
        for (SourceUpdatesView each : List.of(view, summary)) {
            each.zone(ZoneOffset.UTC);
            each.onOpen(opened::add);
            each.onStatus(status::add);
            each.showingForTest(() -> visible);
        }
    }

    @Test
    void theOpenUpdateComesFirstAndIsMarkedAsNotYetAnUpdate() {
        view.source("wiki", "Confluence HaVdB");

        assertThat(view.updateHeadings()).containsExactly("Seit dem letzten Update", "16.11.2026, 18:30 · angelo",
                "10.11.2026, 08:00 · angelo");
        assertThat(view.counts(actions.open())).isEqualTo("Änderungen seit dem letzten manuellen Update: 1 neu");
        assertThat(view.counts(actions.initial())).isEqualTo("Erstbefüllung · 340 Seiten");
        assertThat(view.selectedUpdate()).as("das jüngste abgeschlossene gewählt").isEqualTo(actions.second().id());
        assertThat(view.subscribed()).isTrue();
    }

    @Test
    void anUpdateMarkedByTheCoreItselfShowsOnlyTheDate() {
        ChangeUpdate s = actions.second();
        ChangeUpdate byCore = new ChangeUpdate(s.id(), s.sourceId(), s.from(), s.to(), new UpdateMark("wiki", SECOND,
                com.aresstack.spaceharvester.core.Harvester.OWNER, List.of("CLUK"), 0, false), false, s.kinds(),
                s.spaces(), s.pages(), s.loaded(), s.reloaded(), false);
        view.zone(ZoneOffset.UTC);

        assertThat(view.heading(byCore)).isEqualTo("16.11.2026, 18:30");
        assertThat(view.heading(s)).isEqualTo("16.11.2026, 18:30 · angelo");
    }

    @Test
    void thePreselectionIsTheLatestUpdateWithChangesNotAnEmptyOneOrTheInitialFill() {
        ChangeUpdate open = actions.open();
        ChangeUpdate changed = actions.second();
        ChangeUpdate initial = actions.initial();
        ChangeUpdate empty = new ChangeUpdate("leer", "wiki", changed.to(), changed.to().plusSeconds(3600),
                new UpdateMark("wiki", changed.to().plusSeconds(3600), "angelo", List.of("CLUK"), 0, false), false,
                Map.of(), Map.of(), 0, 0, 0, false);

        assertThat(SourceUpdatesView.preselected(List.of(open, empty, changed, initial)))
                .as("jüngstes leer, das davor nicht").isEqualTo(changed.id());
        assertThat(SourceUpdatesView.preselected(List.of(open, empty, initial))).as("sonst das jüngste abgeschlossene")
                .isEqualTo("leer");
        assertThat(SourceUpdatesView.preselected(List.of(open, initial))).isEqualTo(initial.id());
        assertThat(SourceUpdatesView.preselected(List.of(open))).isEqualTo(ChangeUpdate.OPEN);
        assertThat(SourceUpdatesView.preselected(List.of())).isNull();
    }

    @Test
    void theTextTabShowsTheChangedParagraphsByHeadingAndLoadsLargeUpdatesInWindows() {
        actions.textLimit = 2;
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");

        List<String> texts = view.reportTexts();
        assertThat(texts).containsSubsequence("Betriebshandbuch", "Betrieb › Fristen", "~ Frist 10 Tage → Frist 14 Tage",
                "+ Neu: Notfallkontakt", "− Alter Absatz");
        assertThat(texts).as("erst zwei von drei Seiten").doesNotContain("Alte Seite").contains("Weitere 1 Seite laden");
        assertThat(view.copyText()).as("Kopierform ist der Update-Text des Core").startsWith("# Update wiki")
                .contains("## Betriebshandbuch").contains("weitere Seiten ab Nr. 3 von 3");

        assertThat(view.clickReportLink("Weitere 1 Seite laden")).isTrue();
        assertThat(actions.textOffsets).endsWith(0, 2);
        assertThat(view.reportTexts()).contains("Alte Seite").doesNotContain("Weitere 1 Seite laden");
        assertThat(view.copyText()).contains("## Alte Seite").as("die Zeile des ersten Fensters entfällt")
                .doesNotContain("weitere Seiten ab");
    }

    @Test
    void aMessageBetweenClickAndAnswerDoesNotDropTheNextPages() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        SourceUpdatesView slow = new SourceUpdatesView(() -> actions, new SourceUpdatesView.Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                waiting.add(() -> {
                    try {
                        onSuccess.accept(work.call());
                    } catch (Exception e) {
                        onFailure.accept(e);
                    }
                });
            }
        }, SourceUpdatesView.Mode.CHANGES);
        slow.showingForTest(() -> true);
        actions.textLimit = 2;
        slow.source("wiki", "Confluence HaVdB");
        while (!waiting.isEmpty()) {
            waiting.remove(0).run();
        }
        assertThat(slow.clickReportLink("Weitere 1 Seite laden")).isTrue();
        assertThat(waiting).hasSize(1);
        Runnable more = waiting.remove(0);

        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        while (!waiting.isEmpty()) {
            waiting.remove(0).run();
        }
        more.run();

        assertThat(slow.reportTexts()).as("die Antwort nach der Meldung gilt").contains("Alte Seite")
                .doesNotContain("Weitere 1 Seite laden");
    }

    @Test
    void messagesDuringARunGiveExactlyOneFollowUp() throws Exception {
        List<Runnable> waiting = new ArrayList<>();
        SourceUpdatesView slow = new SourceUpdatesView(() -> actions, new SourceUpdatesView.Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                waiting.add(() -> {
                    try {
                        onSuccess.accept(work.call());
                    } catch (Exception e) {
                        onFailure.accept(e);
                    }
                });
            }
        }, SourceUpdatesView.Mode.CHANGES);
        slow.showingForTest(() -> true);
        slow.source("wiki", "Confluence HaVdB");
        while (!waiting.isEmpty()) {
            waiting.remove(0).run();
        }
        slow.clickUpdate("Seit dem letzten Update");
        waiting.remove(0).run();
        int updates = actions.updateCalls;

        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(waiting).as("ein Lauf").hasSize(1);
        actions.listener.changed("wiki");
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(waiting).as("kein zweiter Lauf, solange einer läuft").hasSize(1);

        waiting.remove(0).run();
        assertThat(waiting).as("das offene Update wird im selben Lauf neu gelesen").hasSize(1);
        waiting.remove(0).run();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(waiting).as("genau ein Nachlauf").hasSize(1);
        while (!waiting.isEmpty()) {
            waiting.remove(0).run();
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        }
        assertThat(actions.updateCalls).isEqualTo(updates + 2);
    }

    @Test
    void aHiddenTabReadsNothingAndCatchesUpOnceWhenShown() throws Exception {
        view.source("wiki", "Confluence HaVdB");
        int updates = actions.updateCalls;
        visible = false;

        actions.listener.changed("wiki");
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(actions.updateCalls).as("verdeckt wird nicht gelesen").isEqualTo(updates);

        visible = true;
        view.becameVisible();
        assertThat(actions.updateCalls).isEqualTo(updates + 1);
        view.becameVisible();
        assertThat(actions.updateCalls).as("nur einmal nachholen").isEqualTo(updates + 1);
    }

    @Test
    void rereadingTheOpenUpdateKeepsThePagesLoadedSoFar() throws Exception {
        actions.textLimit = 1;
        actions.openGrows = true;
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("Seit dem letzten Update");
        assertThat(view.clickReportLink("Weitere 1 Seite laden")).isTrue();
        assertThat(view.reportTexts()).contains("Neue Seite", "Noch eine Seite");
        int renders = view.reportRenders();

        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });

        assertThat(view.reportTexts()).as("nicht auf das erste Fenster zurückgefallen").contains("Neue Seite",
                "Noch eine Seite").doesNotContain("Weitere 1 Seite laden");
        assertThat(view.reportRenders()).as("unverändert: nichts neu gezeichnet, die Ansicht springt nicht")
                .isEqualTo(renders);
        assertThat(view.copyText()).doesNotContain("weitere Seiten ab");
    }

    @Test
    void aSpaceShowsTheSameUpdatesAsAnExcerptAndSummarizesForItsLevel() {
        summary.source("wiki", "DEV", "DEV");

        assertThat(summary.spaceKey()).isEqualTo("DEV");
        assertThat(actions.levels).as("jede Abfrage für den Space").isNotEmpty().containsOnly("DEV");
        assertThat(summary.selectedUpdate()).as("ohne Update mit Änderungen im Space: das jüngste abgeschlossene")
                .isEqualTo(actions.second().id());
        assertThat(summary.paleClouds()).as("leer und Erstbefüllung blass").hasSize(2);

        summary.source("wiki", "Confluence HaVdB");
        assertThat(summary.spaceKey()).as("die ganze Quelle").isNull();
        assertThat(actions.levels).last().isNull();
        summary.clickSummarize();
        assertThat(actions.summarizedLevel).isNull();
    }

    @Test
    void theUpdatesAreCloudsOnTopWithTheNewestMarkedAndAClickMovesTheMark() {
        view.source("wiki", "Confluence HaVdB");

        assertThat(view.sky().isVisible()).isTrue();
        assertThat(view.sky().navigation().chapters()).extracting(Navigation.Section::label)
                .as("das älteste links, das jüngste rechts")
                .containsExactly("10.11.2026, 08:00 · angelo", "16.11.2026, 18:30 · angelo", "Seit dem letzten Update");
        assertThat(view.markedUpdate()).as("standardmäßig das jüngste abgeschlossene Update")
                .isEqualTo(actions.second().id());
        assertThat(view.sky().dashedForTest(2)).as("„Seit dem letzten Update“ gestrichelt").isTrue();
        assertThat(view.sky().dashedForTest(1)).isFalse();
        assertThat(view.paleClouds()).as("Erstbefüllung blass").containsExactly("10.11.2026, 08:00 · angelo");
        assertThat(view.cloudTooltip("16.11.2026, 18:30 · angelo")).as("der Tooltip nennt auch die Zahlen")
                .isEqualTo("16.11.2026, 18:30 · angelo · " + view.counts(actions.second()));

        assertThat(view.clickUpdate("Seit dem letzten Update")).isTrue();
        assertThat(view.markedUpdate()).isEqualTo(view.selectedUpdate()).isEqualTo(ChangeUpdate.OPEN);

        summary.source("wiki", "Confluence HaVdB");
        summary.select(view.selectedUpdate());
        assertThat(summary.markedUpdate()).as("die andere Ansicht markiert mit").isEqualTo(view.selectedUpdate());
    }

    @Test
    void anUpdateListsItsPagesByKindWithPathAndPendingContentAndLocalCountsApart() {
        view.source("wiki", "Confluence HaVdB");

        assertThat(view.clickUpdate("16.11.2026, 18:30 · angelo")).isTrue();

        List<String> texts = view.reportTexts();
        assertThat(texts).contains("Geändert (1)", "Umbenannt (1)", "Entfernt (1)", "Betriebshandbuch", "Alte Seite")
                .contains("CLUK › Fachverfahren · Version 7 statt 6 · Inhalt noch nicht geladen")
                .contains("CLUK · früher „Kurzbeschreibung“")
                .contains("Außerdem im Bestand: 4 Inhalte erstmals lokal geladen, 1 geänderter Inhalt nachgeladen.");
        assertThat(texts).as("geladen und nachgeladen sind keine Änderungen der Quelle")
                .noneMatch(text -> text.startsWith("Erstmals geladen") || text.startsWith("Nachgeladen"));
        assertThat(view.copyText()).as("Update-Text des Core").contains("## Betriebshandbuch");
    }

    @Test
    void aClickOpensAChangedPageButRemovedPagesAreNotClickable() {
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");

        assertThat(view.clickPage("Betriebshandbuch")).isTrue();
        assertThat(opened).singleElement().satisfies(page -> assertThat(page.pageId()).isEqualTo("11"));
        assertThat(view.clickPage("Alte Seite")).isFalse();
        assertThat(opened).hasSize(1);
    }

    @Test
    void theInitialFillShowsOnlyCountsWithoutAPageList() {
        view.source("wiki", "Confluence HaVdB");

        view.clickUpdate("10.11.2026, 08:00 · angelo");

        assertThat(view.reportTexts()).contains(
                "Erstbefüllung: 340 Seiten kamen in den Bestand. Eine Seitenliste gibt es dafür nicht.",
                "Space CLUK: 300 Seiten", "Space DEV: 40 Seiten");
    }

    @Test
    void aMessageLeavesAFinishedUpdateAloneAndKeepsTheChoice() throws Exception {
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");
        int updates = actions.updateCalls;
        int reports = actions.reportCalls;
        int renders = view.reportRenders();

        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });

        assertThat(actions.updateCalls).isEqualTo(updates + 1);
        assertThat(view.selectedUpdate()).as("die Ansicht springt nicht").isEqualTo(actions.second().id());
        assertThat(actions.reportCalls).as("ein abgeschlossenes Update ändert sich nie mehr").isEqualTo(reports);
        assertThat(view.reportRenders()).isEqualTo(renders);
    }

    @Test
    void aMessageRereadsTheOpenUpdateButRedrawsOnlyWhenItChanged() throws Exception {
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("Seit dem letzten Update");
        int reports = actions.reportCalls;
        int renders = view.reportRenders();

        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(actions.reportCalls).isEqualTo(reports + 1);
        assertThat(view.reportRenders()).as("unverändert: nichts neu gezeichnet").isEqualTo(renders);

        actions.openGrows = true;
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(view.reportRenders()).isEqualTo(renders + 1);
        assertThat(view.reportTexts()).contains("Noch eine Seite");
        assertThat(view.selectedUpdate()).isEqualTo(ChangeUpdate.OPEN);
    }

    @Test
    void withoutChangesTheViewSaysHowUpdatesComeAbout() {
        actions.none = true;

        view.source("wiki", "Confluence HaVdB");

        assertThat(view.updateHeadings()).isEmpty();
        assertThat(view.reportTexts()).containsExactly(SourceUpdatesView.NO_UPDATES);
    }

    @Test
    void leavingTheSourceEndsTheSubscription() {
        view.source("wiki", "Confluence HaVdB");

        view.source(null, null);

        assertThat(actions.closed).isTrue();
        assertThat(view.subscribed()).isFalse();
        assertThat(view.sourceId()).isNull();
    }

    @Test
    void openingTheReadingViewCreatesTheMissingSummaryByItselfOnceAndSaysSo() throws Exception {
        summary.source("wiki", "Confluence HaVdB");

        assertThat(summary.selectedUpdate()).isEqualTo(actions.second().id());
        assertThat(actions.summarizeCalls).as("von selbst beim Öffnen").isEqualTo(1);
        assertThat(summary.busy()).isTrue();
        assertThat(summary.reportTexts()).contains(SourceUpdatesView.SUMMARIZING);
        assertThat(SourceUpdatesView.SUMMARIZING).isEqualTo("Zusammenfassung wird erstellt …");

        actions.finish(JobState.FAILED);
        assertThat(summary.reportTexts()).as("nicht erstellt: Einstieg zum erneuten Versuch")
                .contains(SourceUpdatesView.NO_SUMMARY).doesNotContain(SourceUpdatesView.SUMMARIZING);
        assertThat(summary.summarizeEntryShown()).isTrue();

        summary.clickUpdate("10.11.2026, 08:00 · angelo");
        summary.clickUpdate("16.11.2026, 18:30 · angelo");
        assertThat(actions.summarizeCalls).as("einmal je Update und Ebene, nicht bei jedem Klick").isEqualTo(1);
    }

    @Test
    void theInitialFillAndAHiddenTabStartNothing() throws Exception {
        visible = false;
        summary.source("wiki", "Confluence HaVdB");
        assertThat(actions.summarizeCalls).as("verdeckt nichts erstellen").isZero();

        visible = true;
        summary.becameVisible();
        assertThat(actions.summarizeCalls).as("erst beim Öffnen").isEqualTo(1);

        actions.finish(JobState.COMPLETED);
        summary.clickUpdate("10.11.2026, 08:00 · angelo");
        assertThat(summary.summarizeOffered()).as("Erstbefüllung").isFalse();
        assertThat(summary.summarizeTip()).isEqualTo(SourceUpdatesView.INITIAL_TIP);
        assertThat(summary.summarizeEntryShown()).isFalse();
        assertThat(actions.summarizeCalls).isEqualTo(1);
    }

    @Test
    void summarizingRunsAsAJobAndShowsTheSummaryAboveTheKindsWithLinksToItsPages() throws Exception {
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("16.11.2026, 18:30 · angelo");

        assertThat(actions.summarizeCalls).as("beim Öffnen von selbst gestartet").isEqualTo(1);
        assertThat(summary.busy()).as("BusyOverlay über dem Bericht").isTrue();
        assertThat(summary.summarizeEntryShown()).isFalse();
        assertThat(summary.summarizeOffered()).as("nie zwei zugleich").isFalse();
        assertThat(status).contains(SourceUpdatesView.SUMMARIZING);
        summary.clickUpdate("10.11.2026, 08:00 · angelo");
        assertThat(summary.busy()).as("nur über dem Update, das zusammengefasst wird").isFalse();
        summary.clickUpdate("16.11.2026, 18:30 · angelo");
        assertThat(summary.busy()).isTrue();

        actions.finish(JobState.COMPLETED);

        assertThat(summary.busy()).isFalse();
        List<String> texts = summary.reportTexts();
        assertThat(texts).containsSubsequence("Zusammenfassung", "Erstellt am 16.11.2026, 19:00 mit gpt-test",
                "Das Betriebshandbuch wurde überarbeitet.", "→ Betriebshandbuch");
        assertThat(texts).as("die Änderungen stehen unter „Text“").doesNotContain("Geändert (1)");
        assertThat(summary.summarizeLabel()).isEqualTo(SourceUpdatesView.RESUMMARIZE);
        assertThat(summary.summarizeTip()).isEqualTo(SourceUpdatesView.RESUMMARIZE_TIP);
        assertThat(summary.summarizeEntryShown()).as("mit Zusammenfassung nur noch das Werkzeug oben").isFalse();
        assertThat(status).last().isEqualTo("Zusammenfassung erstellt");
        assertThat(summary.copyText()).contains("Zusammenfassung (Erstellt am").contains("überarbeitet");

        assertThat(summary.clickSummaryLink("Betriebshandbuch")).isTrue();
        assertThat(opened).singleElement().satisfies(page -> assertThat(page.pageId()).isEqualTo("11"));
        assertThat(summary.clickSummaryLink("Alte Seite")).as("entfernte Seiten öffnen nichts").isFalse();
        assertThat(summary.clickSummaryLink("Kurzbeschreibung DATENH")).as("ohne Titel: Titel aus dem Bericht").isTrue();
    }

    @Test
    void aStoredSummaryIsReadBackWithStaleAndUnreadableNotes() {
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, "Eine neue Seite.", List.of(
                new SummaryStatement("Eine neue Seite.", List.of(new SummaryPage("20", "Neue Seite")))), "gpt-test",
                SECOND, 2, true);
        summary.source("wiki", "Confluence HaVdB");

        assertThat(summary.reportTexts()).contains("Eine neue Seite.", "→ Neue Seite",
                "Das Update hat sich danach geändert; diese Zusammenfassung kennt nicht alles.",
                "2 Änderungen durfte die KI nicht lesen; sie fehlen in der Zusammenfassung.");
        assertThat(summary.summarizeLabel()).isEqualTo(SourceUpdatesView.RESUMMARIZE);
        assertThat(summary.counts(summarized(actions.second()))).endsWith(" · zusammengefasst");
        assertThat(actions.summarizeCalls).as("eine vorhandene Zusammenfassung entsteht nie von selbst neu").isZero();
    }

    @Test
    void aNewChangeAfterTheSummaryOfTheOpenUpdateGivesTheNoteButNoJob() throws Exception {
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, "Eine neue Seite.", List.of(
                new SummaryStatement("Eine neue Seite.", List.of(new SummaryPage("20", "Neue Seite")))), "gpt-test",
                SECOND, 0, false);
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("Seit dem letzten Update");
        assertThat(summary.reportTexts()).doesNotContain(
                "Das Update hat sich danach geändert; diese Zusammenfassung kennt nicht alles.");

        actions.openGrows = true;
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, "Eine neue Seite.", List.of(
                new SummaryStatement("Eine neue Seite.", List.of(new SummaryPage("20", "Neue Seite")))), "gpt-test",
                SECOND, 0, true);
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });

        assertThat(summary.reportTexts()).contains(
                "Das Update hat sich danach geändert; diese Zusammenfassung kennt nicht alles.");
        assertThat(actions.summarizeCalls).as("kein Modellaufruf je Abgleich").isZero();
    }

    @Test
    void clickingThroughLevelsCancelsWaitingAutoJobsSoAtMostOneIsActive() {
        actions.jobNow = JobState.QUEUED;
        summary.source("wiki", "Confluence HaVdB");
        summary.source("wiki", "CLUK", "CLUK");
        summary.source("wiki", "ARCH", "ARCH");

        assertThat(actions.summarizeCalls).isEqualTo(3);
        assertThat(actions.cancels).as("die zwei wartenden abgebrochen").isEqualTo(2);
        assertThat(actions.mostActive).as("höchstens ein Auftrag zur Zeit").isEqualTo(1);
        assertThat(summary.busy()).as("der letzte wartet für die gezeigte Ebene").isTrue();
    }

    @Test
    void aWaitingJobThatAnotherWindowStartedIsNeverCancelledFromHere() {
        actions.jobNow = JobState.QUEUED;
        actions.reuseJob = "fremd-1";
        summary.source("wiki", "Confluence HaVdB");
        summary.source("wiki", "CLUK", "CLUK");
        assertThat(actions.cancels).as("eigener, neuer Auftrag: abgebrochen").isEqualTo(1);

        summary.source("wiki", "Confluence HaVdB");
        summary.source("wiki", "ARCH", "ARCH");
        assertThat(actions.cancels).as("dieselbe Kennung wie zuvor gilt nicht mehr als eigener").isEqualTo(1);
    }

    @Test
    void aRunningAutoJobFinishesAndTheNextLevelGetsItsJobAfterwards() throws Exception {
        summary.source("wiki", "Confluence HaVdB");
        summary.source("wiki", "CLUK", "CLUK");

        assertThat(actions.summarizeCalls).as("ein laufender bleibt, kein zweiter").isEqualTo(1);
        assertThat(actions.cancels).isZero();

        actions.finish(JobState.COMPLETED);
        assertThat(actions.summarizeCalls).as("danach der für die gezeigte Ebene").isEqualTo(2);
        assertThat(summary.spaceKey()).isEqualTo("CLUK");
    }

    @Test
    void cancellingEndsTheJobAndKeepsThePreviousSummary() throws Exception {
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("16.11.2026, 18:30 · angelo");
        summary.clickSummarize();

        summary.cancelRunningSummary();

        assertThat(actions.cancelled).isEqualTo("job-1");
        assertThat(summary.busy()).isFalse();
        assertThat(summary.reportTexts()).doesNotContain("Zusammenfassung");
        assertThat(status).last().isEqualTo("Abgebrochen; die bisherige Zusammenfassung bleibt.");
        assertThat(summary.summarizeOffered()).isTrue();
    }

    @Test
    void aFailedJobSaysWhyAndOffersItAgain() throws Exception {
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("16.11.2026, 18:30 · angelo");
        summary.clickSummarize();

        actions.finish(JobState.FAILED);

        assertThat(status).last().isEqualTo("Zusammenfassung fehlgeschlagen: Modell nicht erreichbar");
        assertThat(summary.busy()).isFalse();
        assertThat(summary.summarizeOffered()).isTrue();
    }

    @Test
    void withoutAChatModelSummarizingIsNotOffered() {
        actions.available = false;
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("16.11.2026, 18:30 · angelo");

        assertThat(summary.summarizeOffered()).isFalse();
        assertThat(summary.summarizeTip()).isEqualTo(SourceUpdatesView.NO_MODEL);
        assertThat(summary.summarizeEntryShown()).isFalse();
    }

    @Test
    void theChangesViewNeverOffersOrShowsASummary() {
        actions.stored = actions.summaryOf();
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("16.11.2026, 18:30 · angelo");

        assertThat(view.summarizeOffered()).isFalse();
        assertThat(view.reportTexts()).contains("Geändert (1)").doesNotContain("Zusammenfassung",
                "Das Betriebshandbuch wurde überarbeitet.");
        assertThat(view.copyText()).doesNotContain("Zusammenfassung");
    }

    @Test
    void choosingAnUpdateInOneViewIsReported() {
        List<String> chosen = new ArrayList<>();
        view.onSelected(chosen::add);
        view.source("wiki", "Confluence HaVdB");

        view.clickUpdate("16.11.2026, 18:30 · angelo");

        assertThat(chosen).containsExactly(actions.second().id());
    }

    /** Bildschirmfoto der Quellenseite zur Sichtprüfung; nur mit der Umgebungsvariable {@code SCREENSHOTS_DIR}. */
    @Test
    void renderSourcePage() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory != null && !directory.isBlank());
        java.nio.file.Path out = java.nio.file.Files.createDirectories(java.nio.file.Path.of(directory));
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            view.source("wiki", "Confluence HaVdB");
            view.clickUpdate("16.11.2026, 18:30 · angelo");
            AnalysisTabsScreenshotTest.write(view, out.resolve("quellenseite-text.png"));
            summary.source("wiki", "Confluence HaVdB");
            summary.clickUpdate("16.11.2026, 18:30 · angelo");
            AnalysisTabsScreenshotTest.write(summary, out.resolve("quellenseite-einstieg.png"));
            summary.clickSummarize();
            AnalysisTabsScreenshotTest.write(summary, out.resolve("quellenseite-zusammenfassen.png"));
            actions.finishNow(JobState.COMPLETED);
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> AnalysisTabsScreenshotTest.write(summary,
                out.resolve("quellenseite-lesen.png")));
    }

    private static ChangeUpdate summarized(ChangeUpdate u) {
        return new ChangeUpdate(u.id(), u.sourceId(), u.from(), u.to(), u.mark(), u.initial(), u.kinds(), u.spaces(),
                u.pages(), u.loaded(), u.reloaded(), true);
    }

    static final class SyncRunner implements SourceUpdatesView.Runner {
        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            try {
                onSuccess.accept(work.call());
            } catch (Exception e) {
                onFailure.accept(e);
            }
        }
    }

    static final class FakeActions implements SourceUpdatesView.Actions {

        boolean none;
        boolean closed;
        int updateCalls;
        int reportCalls;
        boolean openGrows;
        int summarizeCalls;
        boolean available = true;
        String cancelled;
        /** Zustand, den der Core für den Auftrag meldet; wartend oder laufend. */
        JobState jobNow = JobState.RUNNING;
        int cancels;
        int active;
        int mostActive;
        /** Wie der Core (#842): liefert den schon wartenden Auftrag eines anderen Fensters. */
        String reuseJob;
        JobListener jobs;
        UpdateSummary stored;

        static JobStatus job(JobState state) {
            return job("job-1", state);
        }

        static JobStatus job(String id, JobState state) {
            return new JobStatus(id, JobType.EXPORT, "wiki", null, "local", state, new JobProgress(1, 0, 0, 0),
                    state == JobState.FAILED ? new JobError("X", "Modell nicht erreichbar") : null, false, SECOND,
                    null, null, null, null, Map.of(), false, false, null);
        }

        /** Der Core beendet den Auftrag: Ablage, Meldung des Auftrags (Zustell-Thread), dann EDT. */
        void finish(JobState state) throws Exception {
            finishNow(state);
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        }

        void finishNow(JobState state) {
            if (state == JobState.COMPLETED) {
                stored = summaryOf();
            }
            jobs.changed(job(state));
        }

        private UpdateSummary summaryOf() {
            String first = "Das Betriebshandbuch wurde überarbeitet.";
            String second = "Eine alte Seite ist weggefallen, eine andere heißt jetzt „Kurzbeschreibung DATENH“.";
            return new UpdateSummary("wiki", second().id(), first + " " + second, List.of(
                    new SummaryStatement(first, List.of(new SummaryPage("11", "Betriebshandbuch"))),
                    new SummaryStatement(second, List.of(new SummaryPage("13", "Alte Seite"),
                            new SummaryPage("12", null)))),
                    "gpt-test", SECOND.plusSeconds(1800), 0, false);
        }
        ChangeLogListener listener;

        ChangeUpdate open() {
            return new ChangeUpdate(ChangeUpdate.OPEN, "wiki", SECOND, null, null, false, Map.of(ChangeKind.NEW, 1),
                    Map.of("CLUK", 1), 1, 0, 0, false);
        }

        ChangeUpdate second() {
            Map<ChangeKind, Integer> kinds = new EnumMap<>(ChangeKind.class);
            kinds.put(ChangeKind.CONTENT_CHANGED, 1);
            kinds.put(ChangeKind.RENAMED, 1);
            kinds.put(ChangeKind.REMOVED, 1);
            return new ChangeUpdate(String.valueOf(SECOND.toEpochMilli()), "wiki", FIRST, SECOND,
                    new UpdateMark("wiki", SECOND, "angelo", List.of("CLUK"), 0, false), false, kinds,
                    Map.of("CLUK", 3), 3, 4, 1, false);
        }

        ChangeUpdate initial() {
            Map<String, Integer> spaces = new HashMap<>();
            spaces.put("CLUK", 300);
            spaces.put("DEV", 40);
            return new ChangeUpdate(String.valueOf(FIRST.toEpochMilli()), "wiki", null, FIRST,
                    new UpdateMark("wiki", FIRST, "angelo", List.of("CLUK", "DEV"), 0, true), true,
                    Map.of(ChangeKind.NEW, 340), spaces, 340, 0, 0, false);
        }

        /** Die Ebenen der Abfragen, in Reihenfolge; {@code null} die ganze Quelle. */
        final List<String> levels = new ArrayList<>();
        String summarizedLevel = "nie";

        @Override
        public List<ChangeUpdate> updates(String sourceId, String spaceKey) {
            updateCalls++;
            levels.add(spaceKey);
            if ("DEV".equals(spaceKey)) {
                // Im Space DEV hat das jüngste Update nichts geändert: dieselben Marken, Zähler nur für den Space.
                ChangeUpdate s = second();
                return List.of(new ChangeUpdate(s.id(), s.sourceId(), s.from(), s.to(), s.mark(), false, Map.of(),
                        Map.of(), 0, 0, 0, false), initial());
            }
            return none ? List.of() : List.of(open(), second(), initial());
        }

        @Override
        public ChangeReport report(String sourceId, String updateId, String spaceKey) {
            reportCalls++;
            levels.add(spaceKey);
            if (updateId.equals(initial().id())) {
                return new ChangeReport(initial(), List.of());
            }
            if (updateId.equals(ChangeUpdate.OPEN)) {
                List<ReportedPage> pages = new ArrayList<>(List.of(page("20", ChangeKind.NEW, "Neue Seite",
                        List.of("CLUK"), null, null, null, false)));
                if (openGrows) {
                    pages.add(page("21", ChangeKind.NEW, "Noch eine Seite", List.of("CLUK"), null, null, null, false));
                }
                return new ChangeReport(open(), pages);
            }
            return new ChangeReport(second(), List.of(
                    page("11", ChangeKind.CONTENT_CHANGED, "Betriebshandbuch", List.of("CLUK", "Fachverfahren"),
                            null, "6", "7", true),
                    page("12", ChangeKind.RENAMED, "Kurzbeschreibung DATENH", List.of("CLUK"), "Kurzbeschreibung",
                            null, null, false),
                    page("13", ChangeKind.REMOVED, "Alte Seite", List.of(), null, null, null, false)));
        }

        int textLimit = SourceUpdatesView.TEXT_PAGES;
        final List<Integer> textOffsets = new ArrayList<>();

        /** Wie der Core: der Bericht mit Unterschieden, seitenweise, dazu die Textform. */
        @Override
        public UpdateText updateText(String sourceId, String updateId, String spaceKey, int offset, int limit) {
            textOffsets.add(offset);
            ChangeReport full = report(sourceId, updateId, spaceKey);
            List<ReportedPage> pages = new ArrayList<>();
            for (ReportedPage page : full.pages()) {
                pages.add(page.pageId().equals("11") ? withDiff(page) : page);
            }
            int end = Math.min(pages.size(), offset + Math.min(limit, textLimit));
            List<ReportedPage> window = pages.subList(Math.min(offset, end), end);
            StringBuilder markdown = new StringBuilder(offset == 0 ? "# Update wiki\n\n" : "");
            List<UpdateText.Section> sections = new ArrayList<>();
            for (ReportedPage page : window) {
                int start = markdown.length();
                markdown.append("## ").append(page.title()).append('\n');
                sections.add(new UpdateText.Section(page.pageId(), start, markdown.length() - start, page.kind()));
            }
            if (end < pages.size()) {
                // Wie der Core: ein Fenster mit weiteren Seiten endet mit dieser Zeile.
                markdown.append("_… weitere Seiten ab Nr. ").append(end + 1).append(" von ").append(pages.size())
                        .append("_\n");
            }
            return new UpdateText(UpdateText.FORMAT, sourceId, updateId, spaceKey, pages.size(), offset,
                    new ChangeReport(full.update(), window), markdown.toString(), sections);
        }

        private static ReportedPage withDiff(ReportedPage p) {
            ContentDiff diff = new ContentDiff(ContentDiff.Status.AVAILABLE, List.of(new ContentChange("wiki", p.pageId(),
                    "6", "7", SECOND, List.of(
                    new ParagraphChange(ParagraphChange.Kind.CHANGED, "Frist 10 Tage", "Frist 14 Tage",
                            List.of("Betrieb", "Fristen")),
                    new ParagraphChange(ParagraphChange.Kind.ADDED, null, "Neu: Notfallkontakt",
                            List.of("Betrieb", "Fristen")),
                    new ParagraphChange(ParagraphChange.Kind.REMOVED, "Alter Absatz", null, List.of())), false)));
            return new ReportedPage(p.sourceId(), p.spaceKey(), p.pageId(), p.kind(), p.kinds(), p.title(), p.path(),
                    p.url(), p.oldVersion(), p.newVersion(), p.oldTitle(), p.newTitle(), p.oldParentId(),
                    p.newParentId(), p.oldSpaceKey(), p.newSpaceKey(), p.contentPending(), p.at(), diff);
        }

        @Override
        public UpdateSummary summary(String sourceId, String updateId, String spaceKey) {
            return stored;
        }

        @Override
        public boolean summariesAvailable() {
            return available;
        }

        @Override
        public JobStatus summarize(String sourceId, String updateId, String spaceKey) {
            summarizeCalls++;
            summarizedLevel = spaceKey;
            if (reuseJob != null) {
                return job(reuseJob, JobState.QUEUED);
            }
            active++;
            mostActive = Math.max(mostActive, active);
            return job("job-" + summarizeCalls, JobState.QUEUED);
        }

        @Override
        public JobStatus job(String jobId) {
            return job(jobId, jobNow);
        }

        @Override
        public JobStatus cancelJob(String jobId) {
            cancelled = jobId;
            cancels++;
            active--;
            return job(jobId, JobState.CANCELLED);
        }

        @Override
        public Subscription subscribeJobs(JobListener listener) {
            jobs = listener;
            return () -> jobs = null;
        }

        @Override
        public Subscription subscribe(String sourceId, ChangeLogListener listener) {
            this.listener = listener;
            return () -> closed = true;
        }

        private static ReportedPage page(String id, ChangeKind kind, String title, List<String> path,
                                         String oldTitle, String oldVersion, String newVersion,
                                         boolean pending) {
            return new ReportedPage("wiki", "CLUK", id, kind, Set.of(kind), title, path,
                    URI.create("https://wiki.example/" + id), oldVersion, newVersion, oldTitle, title, null, null,
                    null, null, pending, SECOND, null);
        }
    }
}
