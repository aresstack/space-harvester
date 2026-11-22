package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.event.ApplicationEvent;
import com.aresstack.spaceharvester.app.event.TimelineEvent;
import com.aresstack.spaceharvester.app.ui.controls.PodcastOrb;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.podcast.Podcast;
import com.aresstack.spaceharvester.application.podcast.UpdatePodcasts;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Erfundene Zusammenfassung über ein fiktives Projekt „Nordlicht“ im Space DEMO. */
class PodcastControlTest {

    private final FakeSource source = new FakeSource();
    private final List<ApplicationEvent<?>> events = new ArrayList<>();
    private PodcastControl<UpdateSummary> control;

    @Test
    void staysOffWithoutASummaryOrWithoutSpeech() throws Exception {
        onEdt(() -> {
            control = control();
            assertFalse(control.orb().isEnabled());
            source.available = false;
            control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
            assertFalse(control.orb().isEnabled());
            assertTrue(control.orb().getToolTipText().contains("Sprachausgabe"));
        });
    }

    @Test
    void playStartsTheJobWaitsAndShowsTheTimeline() throws Exception {
        onEdt(() -> {
            control = control();
            UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");
            control.summary(summary);
            assertTrue(control.orb().isEnabled());

            control.orb().doClick();

            assertEquals(1, source.starts);
            assertEquals(PodcastOrb.State.WAITING, control.orb().state());
            assertTrue(control.timelineShown());
            assertTrue(events.getLast() instanceof TimelineEvent shown && shown.getPayload().show()
                    && shown.getPayload().timeline() == control.timeline());
        });
    }

    @Test
    void aFailedStartTurnsTheButtonIntoRetryAndAFailedRetryFlashes() throws Exception {
        onEdt(() -> {
            control = control();
            source.failStart = true;
            control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));

            control.orb().doClick();
            assertEquals(PodcastOrb.State.FAILED, control.orb().state());
            assertFalse(control.orb().flashing());
            assertTrue(control.orb().getToolTipText().contains("erneut versuchen"));

            control.orb().doClick();
            assertEquals(2, source.starts);
            assertEquals(PodcastOrb.State.FAILED, control.orb().state());
            assertTrue(control.orb().flashing());
        });
    }

    @Test
    void anotherSummaryStopsPlaybackAndHidesTheTimeline() throws Exception {
        onEdt(() -> {
            control = control();
            control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
            control.orb().doClick();
            assertTrue(control.timelineShown());

            control.summary(summary("Das Projekt Nordlicht hat ein Glossar."));

            assertFalse(control.timelineShown());
            assertEquals(PodcastOrb.State.PLAY, control.orb().state());
            assertTrue(events.getLast() instanceof TimelineEvent hidden && !hidden.getPayload().show());
        });
    }

    @Test
    void namesTimelinePositionsAfterTopicAndSegment() {
        Podcast podcast = new Podcast("00112233445566778899aabbccddeeff", "Neues bei Nordlicht", List.of(
                new Podcast.Part(0, 0, "Einstieg", "Einstieg", Path.of("a.wav")),
                new Podcast.Part(1, 0, "Startseite", "Was neu ist", null),
                new Podcast.Part(2, -1, "Abschluss", null, null)), true);

        List<com.aresstack.spaceharvester.app.speech.PodcastPlayer.Track> tracks = PodcastControl.tracks(podcast);

        assertEquals(List.of("Einstieg", "Startseite · Was neu ist", "Abschluss"),
                tracks.stream().map(com.aresstack.spaceharvester.app.speech.PodcastPlayer.Track::title).toList());
        assertTrue(tracks.stream().allMatch(com.aresstack.spaceharvester.app.speech.PodcastPlayer.Track::startsTopic));
    }

    private PodcastControl<UpdateSummary> control() {
        PodcastControl<UpdateSummary> created = new PodcastControl<>(() -> source, new SourceUpdatesViewTest.SyncRunner());
        created.events(events::add);
        return created;
    }

    private static UpdateSummary summary(String text) {
        return new UpdateSummary("wiki", "u-1", "DEMO", text, List.of(new SummaryStatement(text, List.of())),
                "test", Instant.parse("2026-11-21T17:00:00Z"), 0, false);
    }

    private static void onEdt(Runnable test) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                test.run();
            } catch (Throwable e) {
                failure[0] = e;
            }
        });
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    /** Ein Podcast, der erst entsteht: bis zum Start gibt es nichts, danach einen laufenden Auftrag ohne Audio. */
    private static final class FakeSource implements PodcastControl.Source<UpdateSummary> {

        boolean available = true;
        boolean failStart;
        int starts;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String key(UpdateSummary summary) {
            return UpdatePodcasts.key(summary);
        }

        @Override
        public Optional<Podcast> podcast(UpdateSummary summary) {
            return starts == 0 || failStart ? Optional.empty()
                    : Optional.of(new Podcast(UpdatePodcasts.key(summary), null, List.of(), true));
        }

        @Override
        public JobStatus start(UpdateSummary summary) {
            starts++;
            if (failStart) {
                throw new IllegalStateException("Dienst nicht erreichbar");
            }
            return null;
        }

        @Override
        public Subscription subscribe(String key, Runnable listener) {
            return Subscription.NONE;
        }

        @Override
        public Subscription subscribeJobs(JobListener listener) {
            return Subscription.NONE;
        }
    }
}
