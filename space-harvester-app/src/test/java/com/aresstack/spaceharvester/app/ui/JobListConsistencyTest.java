package com.aresstack.spaceharvester.app.ui;

import static com.aresstack.spaceharvester.app.ui.ActivitiesBackgroundTabTest.group;
import static com.aresstack.spaceharvester.app.ui.ActivitiesBackgroundTabTest.job;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Die Auftragsliste einheitlich (Angelo 23.11.2026): jeder Auftrag in genau einer Liste, ein wartender sagt, worauf er
 * wartet, und auch schmal steht der Space. Nur erfundene Quellen und Spaces.
 */
class JobListConsistencyTest {

    private static final JobProgress NONE = new JobProgress(0, 0, 0, 0);

    @Test
    void theEnrichmentTheAppStartsAfterASyncStandsOnlyUnderBackground() {
        JobStatus sync = job("s1", JobType.SYNC, "ALPHA", JobState.COMPLETED, Map.of(), 1,
                new JobProgress(40, 40, 40, 0));
        JobStatus afterSync = job("a1", JobType.ENRICHMENT, "ALPHA", JobState.COMPLETED,
                Map.of(EnrichmentsAfterSync.TASK_PARAMETER, EnrichmentsAfterSync.TASK), 2,
                new JobProgress(1034, 1034, 1034, 0));
        JobStatus byClick = job("c1", JobType.ENRICHMENT, "ALPHA", JobState.COMPLETED, Map.of(), 3,
                new JobProgress(1, 1, 1, 0));
        JobStatus part = job("b1", JobType.BACKGROUND, null, JobState.RUNNING, group("g1", "KI-Anreicherung"), 4,
                new JobProgress(2, 1, 1, 0));
        List<JobStatus> all = List.of(sync, afterSync, byClick, part);

        // „etwas läuft entweder im Vordergrund oder im Hintergrund, aber nicht beides“
        assertThat(ActivitiesView.tabJobs(all, false)).extracting(JobStatus::id).containsExactly("s1", "c1");
        assertThat(ActivitiesView.tabJobs(all, true)).extracting(JobStatus::id).containsExactly("a1", "b1");
        for (JobStatus each : all) {
            long tabs = (ActivitiesView.tabJobs(all, false).contains(each) ? 1 : 0)
                    + (ActivitiesView.tabJobs(all, true).contains(each) ? 1 : 0);
            assertThat(tabs).as(each.id()).isEqualTo(1);
        }
    }

    @Test
    void aWaitingJobSaysWhatItWaitsFor() {
        // Drei Plätze, alle belegt: der Abgleich wartet auf einen Platz.
        List<JobStatus> busy = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            busy.add(job("r" + i, JobType.SYNC, "S" + i, JobState.RUNNING, Map.of(), i, NONE));
        }
        JobStatus waitingSync = job("w1", JobType.SYNC, "ALPHA", JobState.QUEUED, Map.of(), 9, NONE);
        List<JobStatus> all = new ArrayList<>(busy);
        all.add(waitingSync);
        assertThat(WaitReason.of(waitingSync, all, 3)).hasValueSatisfying(reason ->
                assertThat(reason).contains("alle 3 Plätze belegt", "3 laufen", "Vorbereitung"));

        // Ein Platz frei, aber Folgeaufträge belegen schon alle bis auf einen.
        List<JobStatus> followUps = new ArrayList<>();
        followUps.add(job("f1", JobType.UPDATE_SUMMARY, "ALPHA", JobState.RUNNING, Map.of(), 1, NONE));
        followUps.add(job("f2", JobType.UPDATE_SUMMARY, "ALPHA", JobState.RUNNING, Map.of(), 2, NONE));
        JobStatus waitingPart = job("f3", JobType.UPDATE_SUMMARY, "ALPHA", JobState.QUEUED, Map.of(), 3, NONE);
        followUps.add(waitingPart);
        assertThat(WaitReason.of(waitingPart, followUps, 3)).hasValueSatisfying(reason ->
                assertThat(reason).contains("höchstens 2 von 3", "jetzt 2"));

        // Hintergrund hat Nachrang hinter einem wartenden Auftrag des Benutzers.
        JobStatus background = job("b1", JobType.BACKGROUND, "ALPHA", JobState.QUEUED, group("g1", "KI-Anreicherung"),
                5, NONE);
        assertThat(WaitReason.of(background, List.of(waitingSync, background), 3)).hasValueSatisfying(reason ->
                assertThat(reason).contains("Nachrang", "1 anderer Auftrag wartet"));
        assertThat(WaitReason.of(background, List.of(background), 1)).hasValueSatisfying(reason ->
                assertThat(reason).contains("nur einem Platz"));

        // Ein Teil mit Vorgänger wartet auf ihn.
        JobStatus first = job("p1", JobType.BACKGROUND, "ALPHA", JobState.RUNNING, group("g2", "Seiten laden"), 1,
                NONE);
        JobStatus second = job("p2", JobType.BACKGROUND, "ALPHA", JobState.QUEUED, Map.of(Jobs.AFTER, "p1"), 2,
                NONE);
        assertThat(WaitReason.of(second, List.of(first, second), 3)).hasValueSatisfying(reason ->
                assertThat(reason).contains("vorigen Teil", "Läuft"));
        // Mehrere Vorstufen, durch Komma getrennt: jede zählt (Codex #1174).
        JobStatus third = job("p3", JobType.BACKGROUND, "ALPHA", JobState.QUEUED, Map.of(Jobs.AFTER, "fertig,p1"), 3,
                NONE);
        assertThat(WaitReason.of(third, List.of(first, third), 3)).hasValueSatisfying(reason ->
                assertThat(reason).contains("vorigen Teil", "Läuft"));

        // Nur wartende haben einen Grund.
        assertThat(WaitReason.of(busy.get(0), all, 3)).isEmpty();
    }

    @Test
    void localWorkWaitsOnlyForLocalWorkAndTakesNoPlace() {
        // Angelo 24.11.2026: lokale Arbeit wie der MaltParser belegt keinen Platz unter „Vorbereitung“.
        JobStatus analysis = job("l1", JobType.STATEMENT_ANALYSIS, "ALPHA", JobState.RUNNING, Map.of(), 1, NONE);
        JobStatus reread = job("l2", JobType.REREAD, "ALPHA", JobState.QUEUED, Map.of(), 2, NONE);
        JobStatus sync = job("s1", JobType.SYNC, "ALPHA", JobState.QUEUED, Map.of(), 3, NONE);
        List<JobStatus> all = List.of(analysis, reread, sync);

        assertThat(WaitReason.of(reread, all, 1)).hasValueSatisfying(reason ->
                assertThat(reason).contains("lokale Arbeit", "1 lokaler Auftrag läuft", "keinen Platz"));
        assertThat(WaitReason.of(sync, all, 1)).as("die laufende Analyse belegt den einen Platz nicht")
                .hasValue("startet gleich");
    }

    @Test
    void theTooltipOfAWaitingBarNamesTheReason() {
        JobStatus waiting = job("w1", JobType.SYNC, "ALPHA", JobState.QUEUED, Map.of(), 9, NONE);
        RowActions.Action bar = ActivitiesView.progressSlots(waiting, null, ActivitiesView.stateText(waiting),
                "alle 3 Plätze belegt").get(0);
        assertThat(bar.tooltip()).startsWith("Wartet: alle 3 Plätze belegt");
    }

    @Test
    void inTheSidebarTheSpaceColumnStays() throws Exception {
        Path home = Files.createTempDirectory("joblist-space");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context, null);
                view.showJobsForTest(List.of(job("s1", JobType.SYNC, "ALPHA", JobState.RUNNING, Map.of(), 1,
                        new JobProgress(40, 12, 12, 0))));
                javax.swing.JComponent root = view.component();
                root.setSize(420, 520);
                layout(root);
                layout(root);
                assertThat(view.narrow()).isTrue();
                javax.swing.JTable table = findTable(root);
                javax.swing.table.TableColumn space = table.getColumnModel().getColumn(ActivitiesView.JobsModel.SPACE);
                assertThat(space.getWidth()).isGreaterThanOrEqualTo(ActivitiesView.NARROW_SPACE_MIN);
                assertThat(table.getValueAt(0, ActivitiesView.JobsModel.SPACE)).isEqualTo("ALPHA");
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

    private static javax.swing.JTable findTable(java.awt.Component component) {
        if (component instanceof javax.swing.JTable table) {
            return table;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                javax.swing.JTable found = findTable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
