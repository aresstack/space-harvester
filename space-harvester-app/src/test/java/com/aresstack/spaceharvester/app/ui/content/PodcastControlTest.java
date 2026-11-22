package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.event.ApplicationEvent;
import com.aresstack.spaceharvester.app.event.ContentOverlayEvent;
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
                    && shown.getPayload().timeline() == control.timelineRow());
            assertTrue(SwingUtilities.isDescendingFrom(control.timeline(), control.timelineRow()));
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
    void showsThePictureOfTheRunningTopicOverTheContentAndHidesItWithoutOne(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path raster = dir.resolve("startseite.png");
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(320, 180,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(image, "png", raster.toFile());
        Path drawing = dir.resolve("glossar.svg");
        java.nio.file.Files.writeString(drawing, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1280\" "
                + "height=\"720\" viewBox=\"0 0 1280 720\"><rect width=\"1280\" height=\"720\" fill=\"#fff\"/></svg>");
        Podcast.Picture first = new Podcast.Picture(raster, "image/png", "Die neue Startseite von Nordlicht");
        Podcast.Picture second = new Podcast.Picture(drawing, "image/svg+xml", "");
        source.parts = List.of(
                new Podcast.Part(0, 0, "Startseite", "Was neu ist", null, first),
                new Podcast.Part(0, 1, "Startseite", "Warum", null, first),
                new Podcast.Part(1, 0, "Glossar", "Glossar", null, second),
                new Podcast.Part(2, 0, "Abschluss", "Abschluss", null, null));
        int before = com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(PodcastControl.PICTURES_KEY, 1);
        try {
            onEdt(() -> {
                com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(PodcastControl.PICTURES_KEY, 1);
                control = control();
                control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
                // Ohne Wiedergabe liegt nichts über dem Inhalt.
                control.pictureOf(0);
                assertFalse(control.stageShown());
                control.orb().doClick();

                // Mit der Wiedergabe liegt das Bild des laufenden Themas über dem Inhalt.
                control.pictureOf(0);
                assertTrue(control.stageShown());
                assertTrue(events.getLast() instanceof ContentOverlayEvent shown && shown.getPayload().show()
                        && shown.getPayload().overlay() == control.stage());
                assertEquals("Die neue Startseite von Nordlicht", control.stage().caption());
                com.aresstack.spaceharvester.app.ui.controls.PictureStage.Picture shownFirst = control.stage().shown();
                // Derselbe Abschnitt und der nächste desselben Themas: dasselbe Bild, keine neue Meldung.
                int count = events.size();
                control.pictureOf(1);
                assertEquals(count, events.size());
                assertTrue(control.stage().shown() == shownFirst);
                // Nächstes Thema: das Schaubild, ohne Unterschrift.
                control.pictureOf(2);
                assertTrue(control.stageShown());
                assertTrue(control.stage().shown().vector() != null);
                assertEquals("", control.stage().caption());
                // Ein Thema ohne Bild gibt den Inhalt frei.
                control.pictureOf(3);
                assertFalse(control.stageShown());
                assertTrue(events.getLast() instanceof ContentOverlayEvent hidden && !hidden.getPayload().show());
                // „Esc“ bzw. der Schalter an der Zeitleiste blendet die Bilder aus, bis man sie wieder einschaltet.
                control.pictureOf(0);
                assertTrue(control.stageShown());
                javax.swing.AbstractButton toggle = (javax.swing.AbstractButton)
                        ((java.awt.Container) control.timelineRow()).getComponent(1);
                assertEquals(PodcastControl.PICTURES_ON, toggle.getToolTipText());
                toggle.doClick();
                assertFalse(control.stageShown());
                assertFalse(control.pictures());
                assertEquals(PodcastControl.PICTURES_OFF, toggle.getToolTipText());
                assertEquals(0, com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(PodcastControl.PICTURES_KEY,
                        1));
                control.pictureOf(2);
                assertFalse(control.stageShown());
                toggle.doClick();
                assertTrue(control.stageShown());
                // Etwas anderes wird gezeigt: Wiedergabe und Bild enden.
                control.summary(null);
                assertFalse(control.stageShown());
            });
        } finally {
            com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(PodcastControl.PICTURES_KEY, before);
        }
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
        List<Podcast.Part> parts = List.of();

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
                    : Optional.of(new Podcast(UpdatePodcasts.key(summary), null, parts, true));
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
