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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Erfundene Zusammenfassung über ein fiktives Projekt „Nordlicht“ im Space DEMO. */
class PodcastControlTest {

    private final FakeSource source = new FakeSource();
    private final List<ApplicationEvent<?>> events = new ArrayList<>();
    private PodcastControl<UpdateSummary> control;

    @Test
    void theShareOfAChapterDecidesWhenItsPictureOpensNotAFixedTime() {
        // Kapitel: 0–10 s, 10–30 s, 30–40 s
        List<Long> starts = List.of(10_000L, 30_000L);
        assertEquals(0.0, PodcastControl.share(0, starts, 40_000), 1e-9);
        assertEquals(0.2, PodcastControl.share(2_000, starts, 40_000), 1e-9);
        assertEquals(0.2, PodcastControl.share(14_000, starts, 40_000), 1e-9);
        assertEquals(0.4, PodcastControl.share(34_000, starts, 40_000), 1e-9);
        assertEquals(1.0, PodcastControl.share(40_000, starts, 40_000), 1e-9);
        assertEquals(0.0, PodcastControl.share(500, List.of(), 0), 1e-9);
        assertTrue(PodcastControl.OPEN_SHARE < PodcastControl.CLOSE_SHARE);
    }

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
    void renewingStartsAFreshPodcastAndPlaysItButNotWhileOneIsBeingMade() throws Exception {
        onEdt(() -> {
            control = control();
            control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
            assertTrue(control.renewable());

            control.renew();

            assertEquals(1, source.renews);
            assertEquals(PodcastOrb.State.WAITING, control.orb().state());
            assertTrue(control.timelineShown(), "spielt ab, sobald der erste Abschnitt hörbar ist");
            assertFalse(control.renewable(), "während der Podcast entsteht, geht neu erstellen nicht");

            source.failRenew = true;
            control.summary(summary("Das Projekt Nordlicht hat ein Glossar."));
            control.renew();
            assertEquals(PodcastOrb.State.FAILED, control.orb().state());
            assertTrue(control.orb().getToolTipText().startsWith("Podcast nicht neu erstellt"));
        });
    }

    @Test
    void aClickOnTheWaitingButtonDuringRenewalKeepsThePodcastSilent() throws Exception {
        onEdt(() -> {
            List<Runnable> pending = new java.util.ArrayList<>();
            control = new PodcastControl<>(() -> source, new SourceUpdatesView.Runner() {
                @Override
                public <T> void run(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> onSuccess,
                        java.util.function.Consumer<Throwable> onFailure) {
                    pending.add(() -> new SourceUpdatesViewTest.SyncRunner().run(work, onSuccess, onFailure));
                }
            });
            control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
            while (!pending.isEmpty()) {
                pending.remove(0).run();
            }

            control.renew();
            assertEquals(PodcastOrb.State.WAITING, control.orb().state());
            control.orb().doClick();
            assertEquals(PodcastOrb.State.PLAY, control.orb().state());
            List<Runnable> now = List.copyOf(pending);
            pending.clear();
            now.forEach(Runnable::run);

            assertEquals(1, source.renews);
            assertTrue(pending.isEmpty(), "nach dem Neuerstellen spielt nichts von selbst los");
            assertEquals(PodcastOrb.State.PLAY, control.orb().state());
        });
    }

    @Test
    void aDeletedPodcastStopsAndTheButtonStandsAsWithoutPodcast() throws Exception {
        // ADR 0047 §5a: Löschen unter „Aus dieser Seite erzeugt“ meldet über die Ablage; nichts entsteht neu.
        onEdt(() -> {
            control = control();
            control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
            control.orb().doClick();
            assertTrue(control.timelineShown());
            source.deleted = true;
            source.stored.run();
        });
        onEdt(() -> {
            assertFalse(control.timelineShown());
            assertEquals(PodcastOrb.State.PLAY, control.orb().state());
            assertEquals(1, source.starts);
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
    void anotherSummaryStartsItsTimelineAtZeroNotAtTheEndOfThePreviousPodcast(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // Angelo 23.11.2026: nach dem Quellwechsel stand die Zeitleiste bis zum ersten Ton auf dem Ende des vorigen
        Path wav = dir.resolve("abschnitt.wav");
        javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(8000, 16, 1, true, false);
        byte[] silence = new byte[16000]; // 1 s
        javax.sound.sampled.AudioSystem.write(new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(silence), format, silence.length / 2),
                javax.sound.sampled.AudioFileFormat.Type.WAVE, wav.toFile());
        onEdt(() -> {
            control = control();
            control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
            control.player().tracks(List.of(
                    new com.aresstack.spaceharvester.app.speech.PodcastPlayer.Track(wav, true, "Einstieg"),
                    new com.aresstack.spaceharvester.app.speech.PodcastPlayer.Track(wav, true, "Startseite")), false);
            control.player().seek(2000);
            control.timeline().update(2000, 2000);
            assertEquals(2000, control.player().total());

            control.summary(summary("Das Projekt Nordlicht hat ein Glossar."));

            assertEquals(0, control.player().position());
            assertEquals(0, control.player().total());
            assertEquals(List.of(), control.player().topicStarts());
            assertFalse(control.timeline().isEnabled(), "Zeitleiste ohne Länge und Endstand des vorigen");

            // Der neue Podcast entsteht noch (kein Plan): ▶ zeigt die Zeitleiste, aber nicht am Ende des vorigen
            control.orb().doClick();
            assertTrue(control.timelineShown());
            assertEquals(0, control.player().position());
            assertFalse(control.timeline().isEnabled());
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
        {
            onEdt(() -> {
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
                // An der Zeitleiste steht kein Schalter für die Bilder mehr (Angelo 22.11.2026).
                assertTrue(java.util.Arrays.stream(((java.awt.Container) control.timelineRow()).getComponents())
                        .noneMatch(javax.swing.AbstractButton.class::isInstance));
                // „Esc“ auf der Bühne nimmt dieses Bild weg; das nächste erscheint wieder.
                control.pictureOf(0);
                assertTrue(control.stageShown());
                esc(control.stage()).click();
                assertFalse(control.stageShown());
                control.pictureOf(1);
                assertFalse(control.stageShown());
                control.pictureOf(2);
                assertTrue(control.stageShown());
                // Etwas anderes wird gezeigt: Wiedergabe und Bild enden.
                control.summary(null);
                assertFalse(control.stageShown());
            });
        }
    }

    private static com.aresstack.spaceharvester.app.ui.controls.EscCircle esc(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof com.aresstack.spaceharvester.app.ui.controls.EscCircle circle) {
                return circle;
            }
            if (child instanceof java.awt.Container inner) {
                com.aresstack.spaceharvester.app.ui.controls.EscCircle found = esc(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void offersTheExportOnlyWhenEveryPartIsVoiced(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // Angelo 27.11.2026: „Als WAV exportieren …“ und „Als Video exportieren …“ brauchen den ganzen Ton.
        Path wav = dir.resolve("abschnitt.wav");
        java.nio.file.Files.write(wav, new byte[0]);
        source.parts = List.of(new Podcast.Part(0, 0, "Startseite", "Was neu ist", wav),
                new Podcast.Part(1, -1, "Glossar", null, null));
        onEdt(() -> {
            control = control();
            control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
            control.orb().doClick();
            assertNull(control.exportable(), "ein Thema ist noch nicht vertont");
        });
        source.parts = List.of(new Podcast.Part(0, 0, "Startseite", "Was neu ist", wav),
                new Podcast.Part(1, 0, "Glossar", "Glossar", wav));
        onEdt(() -> {
            control = control();
            control.summary(summary("Das Projekt Nordlicht hat ein Glossar."));
            control.orb().doClick();
            assertNotNull(control.exportable());
            assertEquals(2, control.exportable().parts().size());
        });
    }

    @Test
    void offersTheTranscriptAsSoonAsThePodcastHasSegments() throws Exception {
        source.parts = List.of(new Podcast.Part(0, 0, "Startseite", "Was neu ist", null));
        source.transcript = "# Neues bei Nordlicht\n\n## Startseite\n\nGastgeber: Willkommen.\n";
        onEdt(() -> {
            control = control();
            control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
            // Ohne Podcast kein Knopf.
            assertFalse(control.transcriptCopy().isVisible());
            assertFalse(control.hasTranscript());
            control.orb().doClick();
            assertTrue(control.transcriptCopy().isVisible());
            assertEquals(PodcastControl.COPY_TRANSCRIPT, control.transcriptCopy().getToolTipText());
            List<String> read = new java.util.ArrayList<>();
            control.transcript(read::add);
            assertEquals(List.of(source.transcript), read);
            // Etwas anderes wird gezeigt: der Knopf geht mit.
            control.summary(null);
            assertFalse(control.transcriptCopy().isVisible());
        });
    }

    /** Angelo 22.11.2026: Untertitel unter der Zeitleiste, Schalter rechts unten, aus bleibt gemerkt. */
    @Test
    void subtitlesStandUnderTheTimelineAndTheSwitchIsRemembered() throws Exception {
        int before = com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(PodcastControl.SUBTITLES_KEY, 1);
        try {
            onEdt(() -> {
                com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(PodcastControl.SUBTITLES_KEY, 1);
                control = control();
                control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
                javax.swing.AbstractButton toggle = (javax.swing.AbstractButton) control.subtitleToggle();
                // Ohne Wiedergabe gibt es nichts zu schalten.
                assertFalse(toggle.isVisible());
                control.orb().doClick();
                assertTrue(toggle.isVisible());
                assertEquals(PodcastControl.SUBTITLES_ON, toggle.getToolTipText());
                javax.swing.JComponent row = control.timelineRow();
                assertTrue(SwingUtilities.isDescendingFrom(control.subtitle(), row));
                int timeline = control.timeline().getPreferredSize().height;
                int withSubtitles = row.getPreferredSize().height;
                assertTrue(withSubtitles > timeline);
                row.setSize(900, withSubtitles);
                row.doLayout();
                // Zwischen den Zeitangaben: links und rechts bleibt ihr Platz frei, die erste Zeile auf ihrer Höhe.
                assertEquals(PodcastControl.SUBTITLE_SIDE, control.subtitle().getX());
                assertEquals(900 - 2 * PodcastControl.SUBTITLE_SIDE, control.subtitle().getWidth());
                assertTrue(control.subtitle().getY() < timeline);
                String shot = System.getenv("SCREENSHOTS_DIR");
                if (shot != null && !shot.isBlank()) {
                    control.timeline().update(59_000, 1_035_000);
                    control.subtitle().show("Die neue Startseite von Nordlicht zeigt jetzt zuerst, was sich seit dem "
                            + "letzten Besuch geändert hat, und erst danach die Kacheln der einzelnen Bereiche.", 0.2);
                    try {
                        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(900,
                                withSubtitles, java.awt.image.BufferedImage.TYPE_INT_RGB);
                        java.awt.Graphics2D g = image.createGraphics();
                        g.setColor(com.aresstack.spaceharvester.app.ui.Comic.SURFACE);
                        g.fillRect(0, 0, 900, withSubtitles);
                        row.paint(g);
                        g.dispose();
                        javax.imageio.ImageIO.write(image, "png",
                                Path.of(shot).resolve("podcast-untertitel.png").toFile());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }

                toggle.doClick();
                assertEquals(PodcastControl.SUBTITLES_OFF, toggle.getToolTipText());
                assertFalse(control.subtitle().isVisible());
                assertEquals(timeline, row.getPreferredSize().height);
                assertEquals(0, com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(
                        PodcastControl.SUBTITLES_KEY, 1));
                // Ein anderer Podcast kennt die Einstellung ebenfalls.
                PodcastControl<UpdateSummary> other = control();
                assertEquals(PodcastControl.SUBTITLES_OFF, other.subtitleToggle().getToolTipText());
                toggle.doClick();
                assertTrue(control.subtitle().isVisible());
                assertEquals(1, com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(
                        PodcastControl.SUBTITLES_KEY, 0));
            });
        } finally {
            com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(PodcastControl.SUBTITLES_KEY, before);
        }
    }

    /** Angelo 27.11.2026: Player in die Leiste unten minimieren und wieder aufklappen; die Wiedergabe läuft weiter. */
    @Test
    void minimizesThePlayerIntoTheBarAndExpandsItAgain() throws Exception {
        int before = com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(PodcastControl.MINIMIZED_KEY, 0);
        try {
            onEdt(() -> {
                com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(PodcastControl.MINIMIZED_KEY, 0);
                control = control();
                control.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
                javax.swing.AbstractButton minimize = (javax.swing.AbstractButton) control.minimizeToggle();
                assertTrue(SwingUtilities.isDescendingFrom(minimize, control.footTools()));
                // Ohne Wiedergabe gibt es nichts zu minimieren.
                assertFalse(minimize.isVisible());
                control.orb().doClick();
                assertTrue(minimize.isVisible());
                assertEquals(PodcastControl.MINIMIZE, minimize.getToolTipText());
                PodcastOrb.State playing = control.orb().state();

                minimize.doClick();
                assertTrue(events.getLast() instanceof TimelineEvent hidden && !hidden.getPayload().show()
                        && hidden.getPayload().timeline() == control.timelineRow());
                assertEquals(PodcastControl.EXPAND, minimize.getToolTipText());
                assertTrue(minimize.isVisible());
                assertFalse(control.subtitleToggle().isVisible());
                assertEquals(playing, control.orb().state());
                assertEquals(1, com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(
                        PodcastControl.MINIMIZED_KEY, 0));

                // Ein anderer Podcast startet minimiert, bis der Benutzer aufklappt.
                PodcastControl<UpdateSummary> other = control();
                other.summary(summary("Das Projekt Nordlicht hat eine neue Startseite."));
                other.orb().doClick();
                assertTrue(events.getLast() instanceof TimelineEvent quiet && !quiet.getPayload().show());
                assertEquals(PodcastControl.EXPAND, other.minimizeToggle().getToolTipText());

                // Der andere, schon laufende Podcast zeigt den gemeinsamen Zustand ebenfalls (Codex #1617).
                assertEquals(PodcastControl.EXPAND, other.minimizeToggle().getToolTipText());
                ((javax.swing.AbstractButton) other.minimizeToggle()).doClick();
                assertEquals(PodcastControl.MINIMIZE, minimize.getToolTipText());
                assertTrue(events.getLast() instanceof TimelineEvent otherShown && otherShown.getPayload().show()
                        && otherShown.getPayload().timeline() == other.timelineRow());
                ((javax.swing.AbstractButton) other.minimizeToggle()).doClick();
                assertEquals(PodcastControl.EXPAND, minimize.getToolTipText());

                minimize.doClick();
                assertTrue(events.getLast() instanceof TimelineEvent shown && shown.getPayload().show()
                        && shown.getPayload().timeline() == control.timelineRow());
                assertEquals(PodcastControl.MINIMIZE, minimize.getToolTipText());
                assertTrue(control.subtitleToggle().isVisible());
                assertEquals(0, com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(
                        PodcastControl.MINIMIZED_KEY, 1));
            });
        } finally {
            com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(PodcastControl.MINIMIZED_KEY, before);
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

    @Test
    void theCoverageCollectsWhatAllTopicsTellAndGoesAwayWithThePodcast() throws Exception {
        // Angelo 27.11.2026: was der ganze Podcast nie erzählt, soll grau dastehen
        source.starts = 1;
        source.parts = List.of(new Podcast.Part(0, 0, "Einstieg", "Einstieg", null),
                new Podcast.Part(2, 0, "Archiv", "Was neu ist", null),
                new Podcast.Part(2, 1, "Archiv", "Warum", null));
        source.focus = java.util.Map.of(0, List.of(UpdatePodcasts.OVERVIEW), 1, List.of(5), 2, List.of(3, 4));
        List<Optional<List<Integer>>> covered = new ArrayList<>();
        onEdt(() -> {
            control = control();
            control.cover(covered::add);

            control.summary(summary("Das Projekt Nordlicht hat ein neues Archiv."));
            // Thema 1 hat keinen Abschnitt im Podcast: seine Stelle zählt nicht als erzählt
            assertEquals(List.of(Optional.of(List.of(UpdatePodcasts.OVERVIEW, 3, 4))), covered);

            control.summary(null);
            assertEquals(Optional.empty(), covered.get(covered.size() - 1));
        });
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
        boolean failRenew;
        int starts;
        int renews;
        boolean deleted;
        Runnable stored = () -> { };
        List<Podcast.Part> parts = List.of();
        String transcript;
        java.util.Map<Integer, List<Integer>> focus = java.util.Map.of();

        @Override
        public List<Integer> focus(UpdateSummary summary, int topic) {
            return focus.getOrDefault(topic, List.of());
        }

        @Override
        public Optional<String> transcript(UpdateSummary summary) {
            return Optional.ofNullable(transcript);
        }

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
            return starts == 0 || failStart || deleted ? Optional.empty()
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
        public JobStatus renew(UpdateSummary summary) {
            renews++;
            if (failRenew) {
                throw new IllegalStateException("Der Podcast entsteht gerade");
            }
            starts++;
            return null;
        }

        @Override
        public boolean renewable() {
            return true;
        }

        @Override
        public Subscription subscribe(String key, Runnable listener) {
            stored = listener;
            return Subscription.NONE;
        }

        @Override
        public Subscription subscribeJobs(JobListener listener) {
            return Subscription.NONE;
        }
    }
}
