package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.controls.BackgroundWait;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Reiter „Hintergrund“ der Auftragsverwaltung (Angelo 21.11.2026): jeder Auftrag steht in genau einem Reiter, je
 * Hintergrundgruppe eine Zeile mit ihren Unteraufträgen darunter. Nur erfundene Quellen und Titel.
 */
class ActivitiesBackgroundTabTest {

    private static final Instant T = Instant.parse("2026-11-21T18:00:00Z");

    static JobStatus job(String id, JobType type, String space, JobState state, Map<String, String> parameters,
                         long minute, JobProgress progress) {
        return new JobStatus(id, type, "wiki", space, "space-harvester-core", state, progress, null, false,
                T.plusSeconds(60 * minute), state == JobState.QUEUED ? null : T.plusSeconds(60 * minute), null, null,
                null, parameters, false, false, null);
    }

    static Map<String, String> group(String id, String title) {
        return Map.of(BackgroundJobs.GROUP, id, BackgroundJobs.TITLE, title, BackgroundJobs.KIND, "pages");
    }

    static List<JobStatus> sample() {
        return List.of(
                job("s1", JobType.SYNC, "ALPHA", JobState.RUNNING, Map.of(), 1, new JobProgress(40, 12, 12, 0)),
                job("e1", JobType.EXPORT, "BETA", JobState.COMPLETED, Map.of(), 2, new JobProgress(8, 8, 8, 0)),
                job("b1", JobType.BACKGROUND, "ALPHA", JobState.RUNNING, group("g1", "Nachbarseiten laden"), 3,
                        new JobProgress(20, 5, 5, 0)),
                job("b2", JobType.BACKGROUND, "BETA", JobState.QUEUED, group("g1", "Nachbarseiten laden"), 3,
                        new JobProgress(0, 0, 0, 0)),
                job("b3", JobType.BACKGROUND, "GAMMA", JobState.COMPLETED, group("g2", "Bilder beschreiben"), 0,
                        new JobProgress(14, 14, 13, 1)));
    }

    @Test
    void everyJobStandsInExactlyOneTab() {
        List<JobStatus> jobs = sample();

        assertThat(ActivitiesView.visible(jobs, false)).extracting(JobStatus::id).containsExactly("s1", "e1");
        List<JobStatus> background = ActivitiesView.visible(jobs, true);
        assertThat(background).extracting(JobStatus::id).containsExactly(ActivitiesView.GROUP_PREFIX + "g1",
                ActivitiesView.GROUP_PREFIX + "g2", "b1", "b2", "b3");
        // Sortiert wie Exporte mit Bildern: jede Gruppe mit ihren Unteraufträgen darunter.
        assertThat(ActivitiesView.grouped(background, java.util.Set.of())).extracting(JobStatus::id)
                .containsExactly(ActivitiesView.GROUP_PREFIX + "g1", "b1", "b2", ActivitiesView.GROUP_PREFIX + "g2",
                        "b3");
    }

    @Test
    void aGroupRowSumsItsPartsAndRunsWhileOneRuns() {
        List<JobStatus> rows = ActivitiesView.groupRows(ActivitiesView.tabJobs(sample(), true));

        JobStatus neighbours = rows.get(0);
        assertThat(ActivitiesView.groupRow(neighbours)).isTrue();
        assertThat(Texts.type(neighbours)).isEqualTo("Nachbarseiten laden");
        assertThat(neighbours.state()).isEqualTo(JobState.RUNNING);
        assertThat(neighbours.progress()).isEqualTo(new JobProgress(20, 5, 5, 0));
        assertThat(neighbours.lifecycle().actions()).contains(JobAction.PAUSE, JobAction.CANCEL);
        JobStatus images = rows.get(1);
        assertThat(images.state()).isEqualTo(JobState.COMPLETED);
        assertThat(images.finishedAt()).isNull();
    }

    /**
     * W5: „Wissensgraph erzeugen“ steht im Reiter „Hintergrund“ als eine Gruppe „Wissensgraph“ aus der Aussagenanalyse
     * und dem Abschlussteil; sie läuft, solange ein Teil läuft, und lässt sich als Ganzes abbrechen.
     */
    @Test
    void aKnowledgeGraphGroupIsOneRowNamedAfterTheGraph() {
        Map<String, String> statements = Map.of(BackgroundJobs.GROUP, "g-w5", BackgroundJobs.TITLE, "Wissensgraph",
                BackgroundJobs.KIND, "statements", BackgroundJobs.GROUP_KIND, "knowledge-graph",
                BackgroundJobs.STEP_TITLE, "Aussagenanalyse");
        Map<String, String> closing = Map.of(BackgroundJobs.GROUP, "g-w5", BackgroundJobs.TITLE, "Wissensgraph",
                BackgroundJobs.KIND, "knowledge-graph", BackgroundJobs.GROUP_KIND, "knowledge-graph",
                BackgroundJobs.STEP_TITLE, "Wissensgraph");
        List<JobStatus> rows = ActivitiesView.groupRows(ActivitiesView.tabJobs(List.of(
                job("k1", JobType.BACKGROUND, "DEV", JobState.RUNNING, statements, 1, new JobProgress(12, 4, 4, 0)),
                job("k2", JobType.BACKGROUND, "DEV", JobState.QUEUED, closing, 1, JobProgress.NONE)), true));

        assertThat(rows).hasSize(1);
        assertThat(ActivitiesView.groupRow(rows.get(0))).isTrue();
        assertThat(Texts.type(rows.get(0))).isEqualTo("Wissensgraph");
        assertThat(rows.get(0).state()).isEqualTo(JobState.RUNNING);
        assertThat(rows.get(0).lifecycle().actions()).contains(JobAction.CANCEL);
    }

    @Test
    void theViewShowsTheTabsAndCountsRunningBackgroundJobs() throws Exception {
        Path home = Files.createTempDirectory("bgtab");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                view.tabs.select(ActivitiesView.JOBS_TAB);
                view.showJobsForTest(sample());
                assertThat(view.rowsForTest()).extracting(JobStatus::id).containsExactly("s1", "e1");
                assertThat(view.tabs.count(ActivitiesView.BACKGROUND_TAB)).isEqualTo(1);
                assertThat(view.tabs.button(ActivitiesView.BACKGROUND_TAB).getToolTipText())
                        .isEqualTo("1 Hintergrundauftrag läuft");

                view.tabs.select(ActivitiesView.BACKGROUND_TAB);
                assertThat(view.backgroundTab()).isTrue();
                assertThat(view.rowsForTest()).hasSize(5);
                assertThat(view.pauseAll.getToolTipText()).contains("Hintergrundaufträge");
                view.tabs.select(ActivitiesView.JOBS_TAB);
            });
        } finally {
            context.shutdown();
        }
    }

    @Test
    void at900PixelsTheTabsWithTheirCountAndEveryToolStayVisible() throws Exception {
        Path home = Files.createTempDirectory("bgtab900");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                view.tabs.select(ActivitiesView.BACKGROUND_TAB);
                view.showJobsForTest(sample());
                javax.swing.JComponent root = view.component();
                root.setSize(900, 520);
                layout(root);
                java.awt.Rectangle visible = new java.awt.Rectangle(0, 0, 900, 520);
                for (javax.swing.JComponent part : List.<javax.swing.JComponent>of(
                        view.tabs.button(ActivitiesView.JOBS_TAB), view.tabs.button(ActivitiesView.BACKGROUND_TAB),
                        view.expandAll, view.collapseAll, view.preparations.box(), view.downloads.box(),
                        view.connections.box(), view.deleteFinished, view.moreJobs, view.pauseAll, view.cancelAll)) {
                    java.awt.Rectangle bounds = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(),
                            root);
                    assertThat(part.isShowing() || part.isVisible()).isTrue();
                    assertThat(visible.contains(bounds)).as(part.getClass().getSimpleName() + " " + bounds).isTrue();
                    // Nicht vom Nachbarn verdeckt: die volle Breite, die das Teil braucht.
                    assertThat(bounds.width).as("Breite " + part.getClass().getSimpleName())
                            .isGreaterThanOrEqualTo(Math.min(part.getPreferredSize().width, part.getMinimumSize()
                                    .width));
                }
                assertThat(view.tabs.button(ActivitiesView.BACKGROUND_TAB).getWidth())
                        .isGreaterThanOrEqualTo(view.tabs.button(ActivitiesView.BACKGROUND_TAB)
                                .getPreferredSize().width);
                view.tabs.select(ActivitiesView.JOBS_TAB);
            });
        } finally {
            context.shutdown();
        }
    }

    private static void layout(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layout(child);
            }
        }
    }

    @Test
    void backgroundWaitShowsTheRingUntilTheGroupIsFinished() throws Exception {
        List<Consumer<BackgroundGroup>> listeners = new ArrayList<>();
        List<BackgroundGroup> done = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            BusyOverlay overlay = new BusyOverlay(new JPanel());
            BackgroundWait wait = new BackgroundWait(overlay, (groupId, listener) -> {
                listeners.add(listener);
                return Subscription.NONE;
            });
            wait.follow("g1", "Nachbarseiten werden geladen …", done::add);
            assertThat(overlay.isBusy()).isTrue();
            assertThat(wait.waiting()).isTrue();

            listeners.get(0).accept(new BackgroundGroup("g1", "pages", "Nachbarseiten laden", List.of()));

            assertThat(overlay.isBusy()).isFalse();
            assertThat(wait.waiting()).isFalse();
            assertThat(done).hasSize(1);
        });
    }

    @Test
    void aReplacedWaitForTheSameGroupIgnoresTheOldQueuedCompletion() throws Exception {
        List<Consumer<BackgroundGroup>> listeners = new ArrayList<>();
        List<String> done = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            BusyOverlay overlay = new BusyOverlay(new JPanel());
            BackgroundWait wait = new BackgroundWait(overlay, (groupId, listener) -> {
                listeners.add(listener);
                return Subscription.NONE;
            });
            BackgroundGroup finished = new BackgroundGroup("g1", "pages", "Nachbarseiten laden", List.of());
            wait.follow("g1", "erstes Warten", group -> done.add("alt"));
            wait.follow("g1", "zweites Warten", group -> done.add("neu"));

            // Die schon eingereihte Meldung des ersetzten Wartens kommt noch an: sie löst nichts aus.
            listeners.get(0).accept(finished);
            assertThat(done).isEmpty();
            assertThat(overlay.isBusy()).isTrue();

            listeners.get(1).accept(finished);
            assertThat(done).containsExactly("neu");
            assertThat(overlay.isBusy()).isFalse();
        });
    }

    @Test
    void tabJobsHoldOnlyRealJobsOfTheirTabNeverGroupRows() {
        List<JobStatus> background = ActivitiesView.tabJobs(sample(), true);
        assertThat(background).extracting(JobStatus::id).containsExactly("b1", "b2", "b3");
        assertThat(ActivitiesView.tabJobs(sample(), false)).extracting(JobStatus::id).containsExactly("s1", "e1");
        assertThat(ActivitiesView.visible(sample(), true)).anyMatch(ActivitiesView::groupRow);
        assertThat(background).noneMatch(ActivitiesView::groupRow);
    }

    static JobStatus part(String id, JobState state, boolean cancelRequested, boolean pauseRequested,
                          com.aresstack.spaceharvester.application.jobs.PauseReason reason, boolean incomplete) {
        return new JobStatus(id, JobType.BACKGROUND, "wiki", "ALPHA", "space-harvester-core", state,
                new JobProgress(4, 4, 4, 0), null, cancelRequested, T, T, state.terminal() ? T : null, null, null,
                group("g9", "Nachbarseiten laden"), incomplete, pauseRequested, reason);
    }

    @Test
    void theGroupStateFollowsTheLifecycleOfItsParts() {
        // Ein Teil hält gerade an, der andere ist schon angehalten: „Anhalten angefordert“, nicht „Läuft“.
        JobStatus pausing = ActivitiesView.groupRows(List.of(
                part("p1", JobState.RUNNING, true, true, null, false),
                part("p2", JobState.PAUSED, false, false, null, false))).get(0);
        assertThat(pausing.lifecycle().key()).isEqualTo("PAUSE_REQUESTED");
        assertThat(Texts.state(pausing)).isEqualTo("Anhalten angefordert");

        // Verbindung verloren geht vor dem Anhalten des Benutzers.
        JobStatus lost = ActivitiesView.groupRows(List.of(
                part("p1", JobState.PAUSED, false, false,
                        com.aresstack.spaceharvester.application.jobs.PauseReason.USER, false),
                part("p2", JobState.PAUSED, false, false,
                        com.aresstack.spaceharvester.application.jobs.PauseReason.CONNECTION_LOST, false))).get(0);
        assertThat(lost.lifecycle().key()).isEqualTo("PAUSED_CONNECTION_LOST");

        // Ein unvollständiger Teil macht die Gruppe „Fertig, unvollständig“.
        JobStatus partial = ActivitiesView.groupRows(List.of(
                part("p1", JobState.COMPLETED, false, false, null, false),
                part("p2", JobState.COMPLETED, false, false, null, true))).get(0);
        assertThat(partial.lifecycle().key()).isEqualTo("COMPLETED_WITH_ERRORS");
    }

    @Test
    void theHelpSitsBehindAQuestionMarkButton() throws Exception {
        Path home = Files.createTempDirectory("bghelp");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                List<com.aresstack.spaceharvester.app.ui.controls.HelpButton> help = new ArrayList<>();
                collect(view.component(), help);
                assertThat(help).hasSize(1);
                assertThat(help.get(0).popup()).isNotNull();
            });
        } finally {
            context.shutdown();
        }
    }

    private static void collect(java.awt.Component component,
                                List<com.aresstack.spaceharvester.app.ui.controls.HelpButton> found) {
        if (component instanceof com.aresstack.spaceharvester.app.ui.controls.HelpButton help) {
            found.add(help);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collect(child, found);
            }
        }
    }
}
