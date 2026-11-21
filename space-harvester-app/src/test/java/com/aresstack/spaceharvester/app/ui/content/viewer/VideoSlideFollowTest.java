package com.aresstack.spaceharvester.app.ui.content.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Der Player folgt „Folienwechsel erkennen“ (Architekt 21.11.2026): ein spätes „läuft“ nach dem Ende holt den Ring
 * nicht über den Hinweis zurück, eine Wiederholung schon; mit nur einem Platz steht statt des Rings ein gelber Hinweis.
 */
final class VideoSlideFollowTest {

    private static final String GROUP = "video-slides:wiki:42:r1";

    @Test
    void aLateRunningEventAfterTheEndKeepsThePlateButARetryBringsTheRingBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoViewerPanel viewer = viewer();
            Host host = new Host(false);
            VideoSlideFollow follow = new VideoSlideFollow(viewer, host);

            follow.follow(group(JobState.QUEUED, null));
            assertThat(viewer.slideRingShown()).isTrue();
            Consumer<BackgroundGroup> runningWatch = host.watches.get(0);

            host.finished.get(0).accept(group(JobState.CANCELLED, null));
            assertThat(viewer.slidesPlate().isVisible()).isTrue();
            assertThat(viewer.slideRingShown()).isFalse();

            runningWatch.accept(group(JobState.RUNNING, null));
            assertThat(viewer.slideRingShown()).as("spätes „läuft“ ändert nichts").isFalse();
            assertThat(viewer.slidesPlate().isVisible()).isTrue();

            host.watches.get(host.watches.size() - 1).accept(group(JobState.RUNNING, null));
            assertThat(viewer.slideRingShown()).as("Wiederholung im Reiter „Hintergrund“").isTrue();
            assertThat(viewer.slidesPlate().isVisible()).isFalse();
        });
    }

    @Test
    void theOnlySlotShowsAYellowPlateWithTheWayToTheSettingInsteadOfARing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoViewerPanel viewer = viewer();
            Host host = new Host(true);
            VideoSlideFollow follow = new VideoSlideFollow(viewer, host);

            follow.follow(group(JobState.QUEUED, null));

            assertThat(viewer.slideRingShown()).isFalse();
            assertThat(viewer.slidesPlate().isVisible()).isTrue();
            assertThat(viewer.slidesPlate().error()).as("gelb").isFalse();
            assertThat(viewer.slidesPlate().sentence()).isEqualTo(VideoSlideFollow.SINGLE_SLOT_SENTENCE);
            assertThat(viewer.playerMessage().isShowing() || viewer.playerMessage().isVisible())
                    .as("die Meldung des Players bleibt sichtbar").isTrue();
            viewer.slidesPlate().clickAction();
            assertThat(host.opened).containsExactly("job-1");

            host.single = false;
            host.watches.get(host.watches.size() - 1).accept(group(JobState.RUNNING, null));
            assertThat(viewer.slideRingShown()).as("zweiter Platz eingestellt, der Teil läuft").isTrue();
        });
    }

    @Test
    void aFailureShowsTheRealReasonInRed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoViewerPanel viewer = viewer();
            VideoSlideFollow follow = new VideoSlideFollow(viewer, new Host(false));

            follow.follow(group(JobState.FAILED, new JobError("X", "Index nicht lesbar")));

            assertThat(viewer.slidesPlate().error()).isTrue();
            assertThat(viewer.slidesPlate().sentence()).isEqualTo("Folienwechsel nicht erkannt: Index nicht lesbar");
        });
    }

    private static VideoViewerPanel viewer() {
        return new VideoViewerPanel("Vortrag.mp4", 10, "video/mp4", (offset, length) -> new byte[0]);
    }

    private static BackgroundGroup group(JobState state, JobError error) {
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        JobStatus part = new JobStatus("job-1", JobType.BACKGROUND, "wiki", "CLUK", "app", state, JobProgress.NONE,
                error, false, now, null, null, null, null, Map.of(), false, false, null);
        return new BackgroundGroup(GROUP, "video-slides", "Folienwechsel erkennen", List.of(part));
    }

    /** Liefert alles sofort; Meldungen löst der Test selbst aus. */
    private static final class Host implements VideoSlideFollow.Host {
        final List<Consumer<BackgroundGroup>> watches = new ArrayList<>();
        final List<Consumer<BackgroundGroup>> finished = new ArrayList<>();
        final List<String> opened = new ArrayList<>();
        boolean single;

        Host(boolean single) {
            this.single = single;
        }

        @Override
        public Subscription watch(String groupId, Consumer<BackgroundGroup> listener) {
            watches.add(listener);
            return Subscription.NONE;
        }

        @Override
        public Subscription whenFinished(String groupId, Consumer<BackgroundGroup> listener) {
            finished.add(listener);
            return Subscription.NONE;
        }

        @Override
        public void singleSlot(Consumer<Boolean> answer) {
            answer.accept(single);
        }

        @Override
        public void marks(Consumer<List<Long>> found) {
            found.accept(List.of());
        }

        @Override
        public void marksStored() {
        }

        @Override
        public void openJob(String jobId) {
            opened.add(jobId);
        }
    }
}
