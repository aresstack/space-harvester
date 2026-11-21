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

    @Test
    void theViewShowsTheTabsAndCountsRunningBackgroundJobs() throws Exception {
        Path home = Files.createTempDirectory("bgtab");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context);
                view.tabs.select(ActivitiesView.JOBS_TAB);
                view.showJobsForTest(sample());
                assertThat(view.rowsForTest()).extracting(JobStatus::id).containsExactly("s1", "e1");
                assertThat(view.backgroundRunning.isVisible()).isTrue();
                assertThat(view.backgroundRunning.getText()).isEqualTo("laufend 1");

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
}
