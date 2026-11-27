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
        assertThat(view.selectedUpdate()).as("mit Änderungen seither ist das offene gewählt (Angelo, 22.11.2026)")
                .isEqualTo(ChangeUpdate.OPEN);
        assertThat(view.subscribed()).isTrue();
    }

    @Test
    void behindTheRedLineTheCircleArrowsRetryTheFailedSyncsOfTheLatestUpdate() {
        actions.withOpen = false;
        actions.failed = 2;
        actions.failedSpaces = List.of("DEV");
        List<String> retried = new ArrayList<>();
        view.onRetry((sourceId, spaces) -> retried.add(sourceId + ":" + spaces));
        view.source("wiki", "Confluence HaVdB");

        assertThat(view.reportTexts()).contains("2 Abgleiche sind dabei gescheitert.");
        javax.swing.JButton retry = retryButton(view);
        assertThat(retry).isNotNull();
        assertThat(retry.getToolTipText()).isEqualTo("Abgleich wiederholen (ein Space)");
        assertThat(retry.getAccessibleContext().getAccessibleName()).isEqualTo(retry.getToolTipText());

        retry.doClick();

        assertThat(retried).containsExactly("wiki:[DEV]");
        assertThat(retry.isEnabled()).as("gesperrt, nicht ausgeblendet").isFalse();
        assertThat(retry.isVisible()).isTrue();
        assertThat(status).contains(SourceUpdatesView.RETRYING);
    }

    @Test
    void withoutARetryHandlerOnlyTheRedLineStands() {
        actions.withOpen = false;
        actions.failed = 1;
        view.source("wiki", "Confluence HaVdB");

        assertThat(view.reportTexts()).contains("Ein Abgleich ist dabei gescheitert.");
        assertThat(retryButton(view)).isNull();
        assertThat(SourceUpdatesView.retryTip(0)).isEqualTo("Abgleich wiederholen (alle Spaces der Quelle)");
        assertThat(SourceUpdatesView.retryTip(38)).isEqualTo("Abgleich wiederholen (38 Spaces)");
    }

    private static javax.swing.JButton retryButton(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JButton button && "updates.retry".equals(button.getName())) {
                return button;
            }
            if (child instanceof java.awt.Container container) {
                javax.swing.JButton found = retryButton(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void anUpdateMarkedByTheCoreItselfShowsOnlyTheDate() {
        ChangeUpdate s = actions.second();
        ChangeUpdate byCore = new ChangeUpdate(s.id(), s.sourceId(), s.from(), s.to(), new UpdateMark("wiki", SECOND,
                com.aresstack.spaceharvester.core.Harvester.OWNER, List.of("BAU"), 0, false), false, s.kinds(),
                s.spaces(), s.pages(), s.loaded(), s.reloaded(), false);
        view.zone(ZoneOffset.UTC);

        assertThat(view.heading(byCore)).isEqualTo("16.11.2026, 18:30");
        assertThat(view.heading(s)).isEqualTo("16.11.2026, 18:30 · angelo");
    }

    @Test
    void thePreselectionIsSinceTheLastUpdateWhenItHasChanges() {
        ChangeUpdate open = actions.open();
        ChangeUpdate changed = actions.second();
        ChangeUpdate initial = actions.initial();

        assertThat(open.pages()).isPositive();
        assertThat(SourceUpdatesView.preselected(List.of(open, changed, initial)))
                .as("das Neueste zuerst, nicht die Vergangenheit").isEqualTo(ChangeUpdate.OPEN);
        assertThat(SourceUpdatesView.preselected(List.of(changed, initial)))
                .as("nach der Synchronisierung das neue Update").isEqualTo(changed.id());
    }

    private static ChangeUpdate noChanges(ChangeUpdate open) {
        return new ChangeUpdate(open.id(), open.sourceId(), open.from(), open.to(), open.mark(), open.initial(),
                Map.of(), Map.of(), 0, 0, 0, false);
    }

    @Test
    void thePreselectionIsTheLatestUpdateWithChangesNotAnEmptyOneOrTheInitialFill() {
        ChangeUpdate open = noChanges(actions.open());
        ChangeUpdate changed = actions.second();
        ChangeUpdate initial = actions.initial();
        ChangeUpdate empty = new ChangeUpdate("leer", "wiki", changed.to(), changed.to().plusSeconds(3600),
                new UpdateMark("wiki", changed.to().plusSeconds(3600), "angelo", List.of("BAU"), 0, false), false,
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
        actions.withOpen = false;
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
        assertThat(view.markedUpdate()).as("mit Änderungen seither standardmäßig „Seit dem letzten Update“")
                .isEqualTo(ChangeUpdate.OPEN);
        assertThat(view.sky().dashedForTest(2)).as("„Seit dem letzten Update“ gestrichelt").isTrue();
        assertThat(view.sky().dashedForTest(1)).isFalse();
        assertThat(view.paleClouds()).as("Erstbefüllung blass").containsExactly("10.11.2026, 08:00 · angelo");
        assertThat(view.cloudTooltip("16.11.2026, 18:30 · angelo")).as("der Tooltip nennt auch die Zahlen")
                .isEqualTo("16.11.2026, 18:30 · angelo · " + view.counts(actions.second()));

        assertThat(view.clickUpdate("16.11.2026, 18:30 · angelo")).isTrue();
        assertThat(view.markedUpdate()).isEqualTo(view.selectedUpdate()).isEqualTo(actions.second().id());

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
                .contains("BAU › Fachverfahren · Version 7 statt 6 · Inhalt noch nicht geladen")
                .contains("BAU · früher „Kurzbeschreibung“")
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
                "Space BAU: 300 Seiten", "Space DEV: 40 Seiten");
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
        actions.withOpen = false;
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
        actions.withOpen = false;
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
        assertThat(texts).containsSubsequence("Zusammenfassung", "Erstellt am 16.11.2026, 19:00",
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
        assertThat(summary.clickSummaryLink("Kurzbeschreibung Nachtlauf")).as("ohne Titel: Titel aus dem Bericht").isTrue();
    }

    @Test
    void afterTheRetryTheOpenReadingViewSummarizesAgainQuietlyAndKeepsThePreviousSummaryUntilThen()
            throws Exception {
        UpdateSummary before = new UpdateSummary("wiki", actions.second().id(), "Bisher: eine Seite geändert.",
                List.of(new SummaryStatement("Bisher: eine Seite geändert.", List.of())), "gpt-test", SECOND, 0,
                false);
        actions.stored = before;
        summary.source("wiki", "Confluence HaVdB");
        assertThat(actions.summarizeCalls).isZero();
        actions.retryJobs.put("sync-1", JobState.RUNNING);
        actions.retryJobs.put("sync-2", JobState.RUNNING);
        summary.followRetry("wiki", List.of("sync-1", "sync-2"));

        // Während der Wiederholung wird die Zusammenfassung veraltet: keine sichtbare Neuerstellung, kein Hinweis.
        actions.stored = new UpdateSummary("wiki", actions.second().id(), "Bisher: eine Seite geändert.",
                List.of(new SummaryStatement("Bisher: eine Seite geändert.", List.of())), "gpt-test", SECOND, 0,
                true);
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(actions.summarizeCalls).as("nicht mitten in der Wiederholung").isZero();
        assertThat(summary.reportTexts()).contains("Bisher: eine Seite geändert.");

        actions.finishRetry("sync-1");
        assertThat(actions.summarizeCalls).as("erst wenn alle Aufträge durch sind").isZero();
        actions.finishRetry("sync-2");

        assertThat(actions.summarizeCalls).as("still neu zusammengefasst").isEqualTo(1);
        assertThat(summary.busy()).as("kein Ring: die Seite hat schon Inhalt").isFalse();
        assertThat(summary.reportTexts()).as("die bisherige bleibt bis dahin stehen")
                .contains("Bisher: eine Seite geändert.").doesNotContain(SourceUpdatesView.SUMMARIZING);

        actions.finish(JobState.COMPLETED);
        assertThat(summary.reportTexts()).contains("Das Betriebshandbuch wurde überarbeitet.")
                .doesNotContain("Bisher: eine Seite geändert.");
        assertThat(status).last().isEqualTo("Zusammenfassung aktualisiert");
        assertThat(actions.summarizeCalls).isEqualTo(1);
    }

    @Test
    void whenTheDailyUpdateChangesItsIdTheQuietRunCoversTheNewIdWithoutARing() throws Exception {
        actions.withOpen = false;
        actions.stored = new UpdateSummary("wiki", actions.second().id(), "Bisher: eine Seite geändert.",
                List.of(), "gpt-test", SECOND, 0, false);
        summary.source("wiki", "Confluence HaVdB");
        actions.retryJobs.put("sync-1", JobState.RUNNING);
        summary.followRetry("wiki", List.of("sync-1"));

        actions.secondShift = 60_000;
        actions.stored = null;
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(summary.selectedUpdate()).isEqualTo(actions.second().id());
        assertThat(actions.summarizeCalls).as("keine sichtbare Neuerstellung für die neue Kennung").isZero();
        assertThat(summary.busy()).isFalse();
        assertThat(summary.reportTexts()).contains("Bisher: eine Seite geändert.");

        actions.finishRetry("sync-1");
        assertThat(actions.summarizeCalls).isEqualTo(1);
        assertThat(summary.busy()).isFalse();
        assertThat(summary.reportTexts()).contains("Bisher: eine Seite geändert.");
        actions.finish(JobState.COMPLETED);
        assertThat(summary.reportTexts()).contains("Das Betriebshandbuch wurde überarbeitet.");
    }

    @Test
    void aSummaryTheCoreDiscardedForTheSameUpdateIsNotKeptOnScreen() throws Exception {
        actions.withOpen = false;
        actions.stored = new UpdateSummary("wiki", actions.second().id(), "Vertraulich bisher.", List.of(),
                "gpt-test", SECOND, 0, false);
        summary.source("wiki", "Confluence HaVdB");
        actions.retryJobs.put("sync-1", JobState.RUNNING);
        summary.followRetry("wiki", List.of("sync-1"));

        // Gleiche Kennung, keine Zusammenfassung mehr: verworfen (etwa KI-Freigabe entzogen), nicht verlängert.
        actions.stored = null;
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        actions.finishRetry("sync-1");

        assertThat(summary.reportTexts()).doesNotContain("Vertraulich bisher.");
    }

    @Test
    void aRevokedReleaseWithAnExtendedUpdateRemovesThePreviousSummaryAtOnce() throws Exception {
        actions.stored = new UpdateSummary("wiki", actions.second().id(), "Vertraulich bisher.", List.of(),
                "gpt-test", SECOND, 0, false);
        summary.source("wiki", "Confluence HaVdB");
        actions.retryJobs.put("sync-1", JobState.RUNNING);
        summary.followRetry("wiki", List.of("sync-1"));

        // KI-Freigabe entzogen und das Tages-Update verlängert (neue Kennung): einmal geprüft, „nein“.
        actions.stillValid = false;
        actions.secondShift = 60_000;
        actions.stored = null;
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });

        assertThat(actions.validityChecks).isEqualTo(1);
        assertThat(summary.reportTexts()).doesNotContain("Vertraulich bisher.");
        assertThat(summary.busy()).isFalse();
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(actions.validityChecks).as("nur einmal je Wechsel").isEqualTo(1);
    }

    @Test
    void aReleaseChangeDuringTheQuietRunDropsThePreviousSummary() throws Exception {
        actions.stored = new UpdateSummary("wiki", actions.second().id(), "Bisher.", List.of(), "gpt-test", SECOND,
                0, true);
        summary.source("wiki", "Confluence HaVdB");
        actions.retryJobs.put("sync-1", JobState.RUNNING);
        summary.followRetry("wiki", List.of("sync-1"));
        actions.finishRetry("sync-1");
        assertThat(actions.summarizeCalls).isEqualTo(1);
        assertThat(summary.reportTexts()).contains("Bisher.");

        actions.stored = null;
        actions.releases.run();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(summary.reportTexts()).doesNotContain("Bisher.");
        assertThat(summary.busy()).isFalse();
    }

    @Test
    void aRetryThatChangedNothingStartsNoModelRun() throws Exception {
        actions.stored = new UpdateSummary("wiki", actions.second().id(), "Unverändert.", List.of(), "gpt-test",
                SECOND, 0, false);
        summary.source("wiki", "Confluence HaVdB");
        actions.retryJobs.put("sync-1", JobState.RUNNING);
        summary.followRetry("wiki", List.of("sync-1"));
        actions.finishRetry("sync-1");
        assertThat(actions.summarizeCalls).isZero();
        assertThat(summary.reportTexts()).contains("Unverändert.");
    }

    @Test
    void aHiddenReadingViewSummarizesQuietlyOnlyWhenShownAgain() throws Exception {
        actions.stored = new UpdateSummary("wiki", actions.second().id(), "Bisher.", List.of(), "gpt-test", SECOND,
                0, true);
        summary.source("wiki", "Confluence HaVdB");
        actions.retryJobs.put("sync-1", JobState.RUNNING);
        summary.followRetry("wiki", List.of("sync-1"));
        visible = false;
        actions.finishRetry("sync-1");
        assertThat(actions.summarizeCalls).isZero();

        visible = true;
        summary.becameVisible();
        assertThat(actions.summarizeCalls).isEqualTo(1);
        assertThat(summary.busy()).isFalse();
        assertThat(summary.reportTexts()).as("still: kein Hinweis „veraltet“ während der Neuerstellung")
                .doesNotContain("Das Update hat sich danach geändert; diese Zusammenfassung kennt nicht alles.");
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
    void aTopicalSummaryShowsTopicsUnderTheSpaceNameWithNumbersAndTheModelOnlyAsTooltip() {
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, null, "Neu ist ein Konzept zum Beispielarchiv.",
                List.of(new SummaryStatement("Das Archiv wird auf ein neues Format umgestellt.",
                        List.of(new SummaryPage("20", "Neue Seite", "BAU")), "Archivumstellung",
                        "Ausführlich: Umstellung im März, offen ist die Schnittstelle.")),
                "update-summary-v2:gpt-test", SECOND, 0, false, Map.of("BAU", "Bauwesen Beispiel"));
        List<String[]> spaces = new ArrayList<>();
        summary.onOpenSpace((source, space) -> spaces.add(new String[] {source, space}));
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("Seit dem letzten Update");

        assertThat(summary.reportTexts()).containsSubsequence("Zusammenfassung",
                "Neu ist ein Konzept zum Beispielarchiv.", "Bauwesen Beispiel", "1 neu", "Archivumstellung",
                "Das Archiv wird auf ein neues Format umgestellt.", "→ Neue Seite");
        assertThat(summary.reportTexts()).noneMatch(text -> text != null && text.contains("gpt-test"));
        assertThat(SummaryReport.modelTip(actions.stored)).isEqualTo("Erstellt mit update-summary-v2:gpt-test");
        assertThat(summary.clickSummaryText("Bauwesen Beispiel")).isTrue();
        assertThat(spaces).singleElement().satisfies(opened -> assertThat(opened).containsExactly("wiki", "BAU"));
    }

    @Test
    void whileThePodcastTellsATopicTheReportMarksItYellowLikeTheSearch() throws Exception {
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, null, "Zwei Neuerungen im Beispielarchiv.",
                List.of(new SummaryStatement("Das Archiv wird auf ein neues Format umgestellt.",
                                List.of(new SummaryPage("20", "Neue Seite", "BAU")), "Archivumstellung", null),
                        new SummaryStatement("Die Suche findet jetzt auch Anhänge.",
                                List.of(new SummaryPage("21", "Suche", "BAU")), "Suche in Anhängen", null)),
                "update-summary-v2:gpt-test", SECOND, 0, false, Map.of("BAU", "Bauwesen Beispiel"));
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("Seit dem letzten Update");

        summary.followPodcast(List.of(1));

        assertThat(summary.podcastMarked()).containsExactlyInAnyOrder("Suche in Anhängen",
                "Die Suche findet jetzt auch Anhänge.");

        // ein Abgleich zeichnet den Bericht neu, während der Podcast läuft: die Markierung bleibt
        int renders = summary.reportRenders();
        actions.openGrows = true;
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(summary.reportRenders()).isGreaterThan(renders);
        assertThat(summary.podcastMarked()).containsExactlyInAnyOrder("Suche in Anhängen",
                "Die Suche findet jetzt auch Anhänge.");

        summary.followPodcast(List.of(0));

        assertThat(summary.podcastMarked()).as("nur das laufende Thema").containsExactlyInAnyOrder(
                "Archivumstellung", "Das Archiv wird auf ein neues Format umgestellt.");

        // Einstieg und Abschluss erzählen den Überblick: der Überblickstext ist gelb (Angelo 23.11.2026)
        summary.followPodcast(List.of(com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.OVERVIEW));

        assertThat(summary.podcastMarked()).as("Einstieg").containsExactly("Zwei Neuerungen im Beispielarchiv.");

        summary.followPodcast(List.of());

        assertThat(summary.podcastMarked()).isEmpty();
    }

    @Test
    void topicsThePodcastNeverTellsAreGreyAndTheYellowStillComesOnTop() {
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, null, "Zwei Neuerungen im Beispielarchiv.",
                List.of(new SummaryStatement("Das Archiv wird auf ein neues Format umgestellt.",
                                List.of(new SummaryPage("20", "Neue Seite", "BAU")), "Archivumstellung", null),
                        new SummaryStatement("Die Suche findet jetzt auch Anhänge.",
                                List.of(new SummaryPage("21", "Suche", "BAU")), "Suche in Anhängen", null)),
                "update-summary-v2:gpt-test", SECOND, 0, false, Map.of("BAU", "Bauwesen Beispiel"));
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("Seit dem letzten Update");

        // ohne Podcast ist nichts grau
        assertThat(summary.podcastGaps()).isEmpty();

        // der Podcast erzählt den Überblick und die Archivumstellung, nie die Suche
        summary.coverPodcast(java.util.Optional.of(List.of(
                com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.OVERVIEW, 0)));

        assertThat(summary.podcastGaps()).containsExactlyInAnyOrder("Suche in Anhängen",
                "Die Suche findet jetzt auch Anhänge.");
        assertThat(summary.podcastMarked()).isEmpty();

        summary.followPodcast(List.of(0));

        assertThat(summary.podcastMarked()).containsExactlyInAnyOrder("Archivumstellung",
                "Das Archiv wird auf ein neues Format umgestellt.");
        assertThat(summary.podcastGaps()).as("das Grau bleibt neben dem Gelb").containsExactlyInAnyOrder(
                "Suche in Anhängen", "Die Suche findet jetzt auch Anhänge.");

        summary.coverPodcast(java.util.Optional.empty());

        assertThat(summary.podcastGaps()).isEmpty();
        assertThat(summary.podcastMarked()).hasSize(2);
    }

    @Test
    void theReportStaysOnTheMarkedTopicWhenItIsLaidOutAgain() throws Exception {
        // Angelo 23.11.2026: „er hat das Gelbe markiert und dann scrollt er aber aus dem Bereich weg, nach unten“
        List<SummaryStatement> statements = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            statements.add(new SummaryStatement(("Meldung " + i + " erzählt etwas über das Beispielarchiv und "
                    + "seine Umstellung. ").repeat(3), List.of(new SummaryPage(String.valueOf(100 + i), "Seite " + i,
                    "BAU")), "Thema " + i, null));
        }
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, null, "Viele Neuerungen im Beispielarchiv.",
                statements, "update-summary-v2:gpt-test", SECOND, 0, false, Map.of("BAU", "Bauwesen Beispiel"));
        javax.swing.JRootPane root = new javax.swing.JRootPane();
        root.getContentPane().setLayout(new java.awt.BorderLayout());
        root.getContentPane().add(summary, java.awt.BorderLayout.CENTER);
        root.setSize(960, 500);
        root.getLayeredPane().setSize(960, 500);
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("Seit dem letzten Update");
        for (int k = 0; k < 3; k++) {
            javax.swing.SwingUtilities.invokeAndWait(() -> layOut(root));
        }

        summary.followPodcast(List.of(9));
        java.awt.Rectangle followed = summary.markedInView().get(0);
        assertThat(followed.y).as("das Thema steht im Blick").isBetween(0, summary.viewHeight() - followed.height);

        // jedes Auslegen (neue Untertitel, Zeitleiste, ein Bild) misst die Absätze neu: die Ansicht bleibt stehen
        for (int k = 0; k < 3; k++) {
            javax.swing.SwingUtilities.invokeAndWait(() -> layOut(root));
        }
        assertThat(summary.markedInView().get(0)).isEqualTo(followed);
    }

    @Test
    void eachPodcastPictureStandsOnceBesideTheFirstMessageOfItsTopicNeverCollectedAtTheEnd() {
        List<SummaryStatement> statements = List.of(
                new SummaryStatement("Text A.", List.of(new SummaryPage("1", "A", "BAU")), "Archiv", null),
                new SummaryStatement("Text B.", List.of(new SummaryPage("2", "B", "BAU")), "Suche", null),
                new SummaryStatement("Text C.", List.of(new SummaryPage("1", "A", "BAU")), "Archiv nochmal", null),
                new SummaryStatement("Text D.", List.of(), "Neue Vorlage", null));
        Map<Integer, Integer> at = SummaryReport.placements(List.of(1, 2, 0, 3), statements, List.of(
                topicPicture(0, List.of("1"), List.of("Archiv"), "Bild Archiv"),
                topicPicture(1, List.of("1"), List.of("Archiv nochmal"), "Zweites Bild derselben Seite"),
                topicPicture(2, List.of("99"), List.of("neue  vorlage"), "Bild über den Titel"),
                topicPicture(3, List.of("98"), List.of("Gibt es nicht"), "Bild ohne Stelle")));

        assertThat(at).as("die erste Meldung in der Reihenfolge des Berichts; jede trägt höchstens ein Bild; "
                + "sonst über den Titel; ohne Stelle nirgends").containsExactlyInAnyOrderEntriesOf(
                Map.of(2, 0, 0, 1, 3, 2));
    }

    @Test
    void theReportShowsThePodcastPicturesBesideTheirMessagesAndOpensThemInTheViewer() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("bilder");
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, null, "Zwei Neuerungen im Beispielarchiv.",
                List.of(new SummaryStatement("Das Archiv wird auf ein neues Format umgestellt.",
                                List.of(new SummaryPage("20", "Neue Seite", "BAU")), "Archivumstellung", null),
                        new SummaryStatement("Die Suche findet jetzt auch Anhänge.",
                                List.of(new SummaryPage("21", "Suche", "BAU")), "Suche in Anhängen", null)),
                "update-summary-v2:gpt-test", SECOND, 0, false, Map.of("BAU", "Bauwesen Beispiel"));
        actions.pictures = List.of(
                new com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture(0, "Suche",
                        List.of("21"), List.of("Suche in Anhängen"), png(dir.resolve("0.png"), "Suchmaske mit Anhang")),
                new com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture(1, "Archiv",
                        List.of("20"), List.of("Archivumstellung"), png(dir.resolve("1.png"), "Altes und neues Format")));
        javax.swing.JRootPane root = new javax.swing.JRootPane();
        root.getContentPane().setLayout(new java.awt.BorderLayout());
        root.getContentPane().add(summary, java.awt.BorderLayout.CENTER);
        root.setSize(960, 700);
        root.getLayeredPane().setSize(960, 700);
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("Seit dem letzten Update");
        root.validate();

        assertThat(summary.reportPictures()).as("in der Reihenfolge des Berichts, nicht der Themen")
                .containsExactly("Altes und neues Format", "Suchmaske mit Anhang");
        assertThat(summary.reportTexts()).containsSubsequence("Das Archiv wird auf ein neues Format umgestellt.",
                "Altes und neues Format", "Die Suche findet jetzt auch Anhänge.", "Suchmaske mit Anhang");

        // markiert bleibt der ganze Text, auch neben dem Bild
        summary.followPodcast(List.of(1));
        assertThat(summary.podcastMarked()).containsExactlyInAnyOrder("Suche in Anhängen",
                "Die Suche findet jetzt auch Anhänge.");

        // der Podcast öffnet das Bild seines Kapitels aus der Stelle im Text; eines ohne Bild bleibt zu
        assertThat(summary.cuePicture(0)).isTrue();
        assertThat(com.aresstack.spaceharvester.app.ui.controls.ViewerPopup.current(summary)).isPresent();
        assertThat(summary.cuePicture(7)).isFalse();
        assertThat(summary.clickReportPicture(0)).isTrue();
        com.aresstack.spaceharvester.app.ui.controls.ViewerPopup.current(summary).orElseThrow().close();

        // ein neu gezeichneter Bericht behält seine Bilder
        actions.openGrows = true;
        actions.listener.changed("wiki");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(summary.reportPictures()).containsExactly("Altes und neues Format", "Suchmaske mit Anhang");
    }

    /**
     * Angelo 23.11.2026: Der Podcast der Quelle steht in der Podcast-Ansicht (Reiter „Text“), die Zusammenfassung bleibt
     * in der Leseansicht und ist seine Grundlage. Die Podcast-Ansicht markiert die Seiten des laufenden Themas und
     * zeigt sein Bild neben der ersten davon.
     */
    @Test
    void thePodcastOfASourceStandsInThePodcastViewAndTellsTheExistingSummary() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("bilder");
        actions.openGrows = true;
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, null, "Zwei Neuerungen im Beispielarchiv.",
                List.of(new SummaryStatement("Das Archiv wird auf ein neues Format umgestellt.",
                                List.of(new SummaryPage("20", "Neue Seite", "BAU")), "Archivumstellung", null),
                        new SummaryStatement("Die Suche findet jetzt auch Anhänge.",
                                List.of(new SummaryPage("21", "Noch eine Seite", "BAU")), "Suche in Anhängen", null)),
                "update-summary-v2:gpt-test", SECOND, 0, false, Map.of("BAU", "Bauwesen Beispiel"));
        actions.pictures = List.of(
                new com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture(1, "Suche",
                        List.of("21"), List.of("Suche in Anhängen"), png(dir.resolve("1.png"), "Suchmaske mit Anhang")));
        javax.swing.JRootPane root = new javax.swing.JRootPane();
        root.getContentPane().setLayout(new java.awt.BorderLayout());
        root.getContentPane().add(view, java.awt.BorderLayout.CENTER);
        root.setSize(960, 700);
        root.getLayeredPane().setSize(960, 700);
        List<UpdateSummary> forwarded = new ArrayList<>();
        summary.onSummary(forwarded::add);
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("Seit dem letzten Update");
        view.source("wiki", "Confluence HaVdB");
        view.clickUpdate("Seit dem letzten Update");
        root.validate();

        assertThat(view.spoken()).as("die vorhandene Zusammenfassung, keine neue").isEqualTo(actions.stored);
        assertThat(actions.summarizeCalls).isZero();
        assertThat(summary.spoken()).as("die Leseansicht spielt keinen Podcast").isNull();
        assertThat(forwarded).as("die Leseansicht reicht ihre Zusammenfassung weiter").contains(actions.stored);
        assertThat(view.podcastRenewable()).isEqualTo(view.podcast().renewable());
        assertThat(summary.podcastRenewable()).isFalse();

        view.followPodcast(List.of(1));
        assertThat(view.podcastMarked()).as("die Seite des laufenden Themas").containsExactly("Noch eine Seite");
        // zwei Themen derselben Seite: nach dem Zurücknehmen ist nichts mehr gelb
        view.followPodcast(List.of(0, 1, 1));
        view.followPodcast(List.of());
        assertThat(view.podcastMarked()).isEmpty();
        view.followPodcast(List.of(com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.OVERVIEW));
        assertThat(view.podcastMarked()).as("Einstieg: die Überschrift des Updates").hasSize(1);
        view.followPodcast(List.of());
        assertThat(view.podcastMarked()).isEmpty();

        assertThat(view.reportPictures()).containsExactly("Suchmaske mit Anhang");
        assertThat(view.cuePicture(1)).isTrue();
        assertThat(com.aresstack.spaceharvester.app.ui.controls.ViewerPopup.current(view)).isPresent();
        com.aresstack.spaceharvester.app.ui.controls.ViewerPopup.current(view).orElseThrow().close();
        assertThat(view.cuePicture(0)).as("ein Thema ohne Bild bleibt zu").isFalse();

        // eine neue Zusammenfassung der Leseansicht übernimmt die Podcast-Ansicht; eine fremde nicht
        UpdateSummary newer = new UpdateSummary("wiki", ChangeUpdate.OPEN, null, "Neu zusammengefasst.",
                actions.stored.statements(), "update-summary-v2:gpt-test", SECOND, 0, false, Map.of());
        view.adopt(new UpdateSummary("wiki", "anderes-update", null, "Fremd.", List.of(), "m", SECOND, 0, false,
                Map.of()));
        assertThat(view.spoken()).isEqualTo(actions.stored);
        view.adopt(newer);
        assertThat(view.spoken()).isEqualTo(newer);
    }

    /** Legt ohne Bildschirm aus wie validate() mit Fenster (kopflos hat der Baum keinen Peer). */
    static void layOut(java.awt.Component component) {
        if (component instanceof java.awt.Container container) {
            container.doLayout();
            for (java.awt.Component child : container.getComponents()) {
                layOut(child);
            }
        }
    }

    private static com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture topicPicture(
            int topic, List<String> pageIds, List<String> titles, String caption) {
        return new com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture(topic, "Thema",
                pageIds, titles, new com.aresstack.spaceharvester.application.podcast.Podcast.Picture(
                java.nio.file.Path.of("bild-" + topic + ".png"), "image/png", caption));
    }

    private static com.aresstack.spaceharvester.application.podcast.Podcast.Picture png(java.nio.file.Path file,
                                                                                        String caption)
            throws java.io.IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(160, 90,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(image, "png", file.toFile());
        return new com.aresstack.spaceharvester.application.podcast.Podcast.Picture(file, "image/png", caption);
    }

    @Test
    void anOldCountingSummaryStaysVisibleAndIsReplacedOnceByATopicalOne() {
        actions.stored = new UpdateSummary("wiki", ChangeUpdate.OPEN, "Eine neue Seite.", List.of(
                new SummaryStatement("Eine neue Seite.", List.of(new SummaryPage("20", "Neue Seite")))),
                "update-summary-v1:gpt-test", SECOND, 0, false);
        summary.source("wiki", "Confluence HaVdB");
        summary.clickUpdate("Seit dem letzten Update");

        assertThat(summary.reportTexts()).contains("Eine neue Seite.");
        assertThat(actions.summarizeCalls).as("einmal von selbst neu, inhaltlich").isEqualTo(1);
        summary.clickUpdate("Seit dem letzten Update");
        assertThat(actions.summarizeCalls).isEqualTo(1);
    }

    @Test
    void topicsFromSeveralSpacesStandApartAndManyPagesAreFolded() {
        SummaryStatement one = new SummaryStatement("A", List.of(new SummaryPage("1", "Eins", "BAU")), "A", null);
        SummaryStatement both = new SummaryStatement("B", List.of(new SummaryPage("2", "Zwei", "BAU"),
                new SummaryPage("3", "Drei", "DEV")), "B", null);
        assertThat(SummaryReport.topicsBySpace(List.of(one, both)))
                .containsEntry("BAU", List.of(one)).containsEntry(SummaryReport.ACROSS_SPACES, List.of(both));
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
        summary.source("wiki", "BAU", "BAU");
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
        summary.source("wiki", "BAU", "BAU");
        assertThat(actions.cancels).as("eigener, neuer Auftrag: abgebrochen").isEqualTo(1);

        summary.source("wiki", "Confluence HaVdB");
        summary.source("wiki", "ARCH", "ARCH");
        assertThat(actions.cancels).as("dieselbe Kennung wie zuvor gilt nicht mehr als eigener").isEqualTo(1);
    }

    @Test
    void aRunningAutoJobFinishesAndTheNextLevelGetsItsJobAfterwards() throws Exception {
        summary.source("wiki", "Confluence HaVdB");
        summary.source("wiki", "BAU", "BAU");

        assertThat(actions.summarizeCalls).as("ein laufender bleibt, kein zweiter").isEqualTo(1);
        assertThat(actions.cancels).isZero();

        actions.finish(JobState.COMPLETED);
        assertThat(actions.summarizeCalls).as("danach der für die gezeigte Ebene").isEqualTo(2);
        assertThat(summary.spaceKey()).isEqualTo("BAU");
    }

    @Test
    void cancellingEndsTheJobAndKeepsThePreviousSummary() throws Exception {
        actions.withOpen = false;
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
        actions.withOpen = false;
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

        /** Gescheiterte Abgleiche des jüngsten Updates und ihre Spaces. */
        int failed;
        List<String> failedSpaces = List.of();
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
        /** Alle Abos der Auftragsmeldungen: das des Zusammenfassens und das einer verfolgten Wiederholung. */
        final List<JobListener> jobs = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** Zustand der Wiederholungsaufträge, wie {@link #job} ihn meldet. */
        final Map<String, JobState> retryJobs = new HashMap<>();
        UpdateSummary stored;
        /** Die fertigen Bilder des Podcasts zur Zusammenfassung. */
        List<com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture> pictures = List.of();
        /** Wie das Verlängern des Tages-Updates (DailyUpdateMarks): seine Kennung wechselt auf die der letzten Runde. */
        long secondShift;

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
            jobs.forEach(listener -> listener.changed(job(state)));
        }

        /** Ein Wiederholungsauftrag endet: Ablage, Meldung (Zustell-Thread), dann EDT. */
        void finishRetry(String jobId) throws Exception {
            retryJobs.put(jobId, JobState.COMPLETED);
            jobs.forEach(listener -> listener.changed(job(jobId, JobState.COMPLETED)));
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        }

        private UpdateSummary summaryOf() {
            String first = "Das Betriebshandbuch wurde überarbeitet.";
            String second = "Eine alte Seite ist weggefallen, eine andere heißt jetzt „Kurzbeschreibung Nachtlauf“.";
            return new UpdateSummary("wiki", second().id(), first + " " + second, List.of(
                    new SummaryStatement(first, List.of(new SummaryPage("11", "Betriebshandbuch"))),
                    new SummaryStatement(second, List.of(new SummaryPage("13", "Alte Seite"),
                            new SummaryPage("12", null)))),
                    "gpt-test", SECOND.plusSeconds(1800), 0, false);
        }
        ChangeLogListener listener;

        ChangeUpdate open() {
            return new ChangeUpdate(ChangeUpdate.OPEN, "wiki", SECOND, null, null, false, Map.of(ChangeKind.NEW, 1),
                    Map.of("BAU", 1), 1, 0, 0, false);
        }

        ChangeUpdate second() {
            Map<ChangeKind, Integer> kinds = new EnumMap<>(ChangeKind.class);
            kinds.put(ChangeKind.CONTENT_CHANGED, 1);
            kinds.put(ChangeKind.RENAMED, 1);
            kinds.put(ChangeKind.REMOVED, 1);
            return new ChangeUpdate(String.valueOf(SECOND.toEpochMilli() + secondShift), "wiki", FIRST, SECOND,
                    new UpdateMark("wiki", SECOND, "angelo", List.of("BAU"), failed, false, failedSpaces), false, kinds,
                    Map.of("BAU", 3), 3, 4, 1, false);
        }

        ChangeUpdate initial() {
            Map<String, Integer> spaces = new HashMap<>();
            spaces.put("BAU", 300);
            spaces.put("DEV", 40);
            return new ChangeUpdate(String.valueOf(FIRST.toEpochMilli()), "wiki", null, FIRST,
                    new UpdateMark("wiki", FIRST, "angelo", List.of("BAU", "DEV"), 0, true), true,
                    Map.of(ChangeKind.NEW, 340), spaces, 340, 0, 0, false);
        }

        /** Die Ebenen der Abfragen, in Reihenfolge; {@code null} die ganze Quelle. */
        final List<String> levels = new ArrayList<>();
        String summarizedLevel = "nie";
        /** Ob es Änderungen „Seit dem letzten Update“ gibt; ohne sie ist das jüngste abgeschlossene vorgewählt. */
        boolean withOpen = true;

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
            return none ? List.of() : withOpen ? List.of(open(), second(), initial()) : List.of(second(), initial());
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
                        List.of("BAU"), null, null, null, false)));
                if (openGrows) {
                    pages.add(page("21", ChangeKind.NEW, "Noch eine Seite", List.of("BAU"), null, null, null, false));
                }
                return new ChangeReport(open(), pages);
            }
            return new ChangeReport(second(), List.of(
                    page("11", ChangeKind.CONTENT_CHANGED, "Betriebshandbuch", List.of("BAU", "Fachverfahren"),
                            null, "6", "7", true),
                    page("12", ChangeKind.RENAMED, "Kurzbeschreibung Nachtlauf", List.of("BAU"), "Kurzbeschreibung",
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
        public List<com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture> podcastPictures(
                UpdateSummary summary) {
            return pictures;
        }

        @Override
        public UpdateSummary summary(String sourceId, String updateId, String spaceKey) {
            return stored;
        }

        boolean stillValid = true;
        int validityChecks;
        Runnable releases;

        @Override
        public boolean summaryStillValid(String sourceId, String updateId, String spaceKey) {
            validityChecks++;
            return stillValid;
        }

        @Override
        public Subscription subscribeReleases(Runnable listener) {
            releases = listener;
            return () -> releases = null;
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
            if (retryJobs.containsKey(jobId)) {
                return job(jobId, retryJobs.get(jobId));
            }
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
            jobs.add(listener);
            return () -> jobs.remove(listener);
        }

        @Override
        public Subscription subscribe(String sourceId, ChangeLogListener listener) {
            this.listener = listener;
            return () -> closed = true;
        }

        private static ReportedPage page(String id, ChangeKind kind, String title, List<String> path,
                                         String oldTitle, String oldVersion, String newVersion,
                                         boolean pending) {
            return new ReportedPage("wiki", "BAU", id, kind, Set.of(kind), title, path,
                    URI.create("https://wiki.example/" + id), oldVersion, newVersion, oldTitle, title, null, null,
                    null, null, pending, SECOND, null);
        }
    }
}
