package com.aresstack.spaceharvester.app.speech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Erfundene Abschnitte eines Podcasts über ein fiktives Projekt „Nordlicht“. */
class PodcastPlayerTest {

    @TempDir
    Path dir;

    @Test
    void estimatesMissingSegmentsWithTheMeanOfTheFinishedOnes() {
        assertEquals(PodcastPlayer.DEFAULT_TRACK_MILLIS, PodcastPlayer.estimate(new long[]{-1, -1}));
        assertEquals(1500, PodcastPlayer.estimate(new long[]{1000, 2000, -1}));
        assertEquals(4500, PodcastPlayer.total(new long[]{1000, 2000, -1}));
    }

    @Test
    void marksTopicStartsAndNamesTheSegmentAtAPosition() throws Exception {
        PodcastPlayer player = new PodcastPlayer(new Recorder());
        player.tracks(List.of(new PodcastPlayer.Track(wav("a", 1000), true, "Einstieg"),
                new PodcastPlayer.Track(wav("b", 2000), true, "Nordlicht"),
                new PodcastPlayer.Track(null, false, "Nordlicht · Was es bringt"),
                new PodcastPlayer.Track(null, true, "Abschluss")), true);

        assertEquals(1000 + 2000 + 1500 + 1500, player.total());
        // die erste Marke stünde bei 0 und entfällt; der dritte Abschnitt beginnt kein Thema
        assertEquals(List.of(1000L, 4500L), player.topicStarts());
        assertEquals("Einstieg", player.titleAt(500));
        assertEquals("Nordlicht · Was es bringt", player.titleAt(3200));
        assertNull(player.titleAt(10_000));
    }

    @Test
    void resetForgetsTheSegmentsAndStartsAtZero() throws Exception {
        PodcastPlayer player = new PodcastPlayer(new Recorder());
        player.tracks(List.of(new PodcastPlayer.Track(wav("a", 1000), true, "Einstieg"),
                new PodcastPlayer.Track(wav("b", 2000), true, "Nordlicht")), false);
        player.seek(3000);

        player.reset();

        assertEquals(0, player.position());
        assertEquals(0, player.total());
        assertEquals(List.of(), player.topicStarts());
        assertEquals(-1, player.trackAt(0));
        assertTrue(!player.playing() && !player.paused());
    }

    @Test
    void skipsSegmentsThatWillNotComeAndReportsWhereToResume() throws Exception {
        Recorder recorder = new Recorder();
        PodcastPlayer player = new PodcastPlayer(recorder);
        // kein Auftrag mehr: nichts kommt nach, die Wiedergabe darf nicht stumm hängen bleiben
        player.tracks(List.of(new PodcastPlayer.Track(null, true, "Einstieg"),
                new PodcastPlayer.Track(null, true, "Abschluss")), false);

        player.play(0);

        assertTrue(recorder.done.await(5, TimeUnit.SECONDS));
        assertEquals("2 Abschnitte fehlen noch", recorder.failure.get());
        assertEquals(0, recorder.resumeAt.get());
    }

    @Test
    void waitsForASegmentThatIsStillBeingProduced() throws Exception {
        Recorder recorder = new Recorder();
        PodcastPlayer player = new PodcastPlayer(recorder);
        player.tracks(List.of(new PodcastPlayer.Track(null, true, "Einstieg")), true);

        player.play(0);

        assertTrue(recorder.waiting.await(5, TimeUnit.SECONDS));
        // der Auftrag endet ohne den Abschnitt: überspringen und melden statt warten
        player.tracks(List.of(new PodcastPlayer.Track(null, true, "Einstieg")), false);
        assertTrue(recorder.done.await(5, TimeUnit.SECONDS));
        assertEquals("Ein Abschnitt fehlt noch", recorder.failure.get());
        player.stop();
    }

    private Path wav(String name, int millis) throws Exception {
        AudioFormat format = new AudioFormat(8000f, 16, 1, true, false);
        int frames = 8 * millis;
        Path file = dir.resolve(name + ".wav");
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(new byte[frames * 2]), format, frames),
                AudioFileFormat.Type.WAVE, file.toFile());
        return file;
    }

    private static final class Recorder implements PodcastPlayer.Listener {

        final CountDownLatch done = new CountDownLatch(1);
        final CountDownLatch waiting = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();
        final AtomicLong resumeAt = new AtomicLong(-1);

        @Override
        public void position(long millis, long total) {
        }

        @Override
        public void waiting(boolean waiting) {
            if (waiting) {
                this.waiting.countDown();
            }
        }

        @Override
        public void ended() {
            done.countDown();
        }

        @Override
        public void failed(String message, long resumeAt) {
            failure.set(message);
            this.resumeAt.set(resumeAt);
            done.countDown();
        }
    }
}
