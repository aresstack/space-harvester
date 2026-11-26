package com.aresstack.spaceharvester.app.speech;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Gleitendes Fenster mit Übereinstimmung und Deduplizierung: kein Text doppelt, das Fenster bleibt unter 30 s. */
class LiveTranscriptTest {

    private static final int R = LiveTranscript.RATE;
    private static final LiveDictationSettings.Tuning TUNING = new LiveDictationSettings.Tuning(1000, 10, 1000);

    private static LiveTranscript.Segment seg(double from, double to, String text) {
        return new LiveTranscript.Segment((long) (from * R), (long) (to * R), text);
    }

    @Test
    void waitsForTheFirstSecondAndForNewAudio() {
        LiveTranscript live = new LiveTranscript(0, TUNING);
        assertThat(live.next(R / 2)).isNull();
        LiveTranscript.Window window = live.next(2 * R);
        assertThat(window).isEqualTo(new LiveTranscript.Window(0, 2 * R, false));
        live.accept(window, List.of(seg(0, 2, "Hallo")));
        assertThat(live.next(2 * R)).as("kein neuer Ton, keine neue Erkennung").isNull();
    }

    @Test
    void commitsOnlySegmentsThatTwoRecognitionsAgreeOn() {
        LiveTranscript live = new LiveTranscript(0, TUNING);
        live.accept(live.next(2 * R), List.of(seg(0, 2, "Hallo Welt")));
        assertThat(live.committed()).isEmpty();
        assertThat(live.provisional()).isEqualTo("Hallo Welt");

        live.accept(live.next(4 * R), List.of(seg(0, 2.2, "Hallo, Welt."), seg(2.2, 4, "wie geht")));
        assertThat(live.committed()).isEqualTo("Hallo, Welt.");
        assertThat(live.provisional()).isEqualTo("wie geht");
        assertThat(live.start()).isEqualTo((long) (2.2 * R));

        LiveTranscript.Window next = live.next(6 * R);
        assertThat(next.start()).as("übernommener Ton wird nie wieder erkannt").isEqualTo((long) (2.2 * R));
        live.accept(next, List.of(seg(2.2, 5, "wie geht es dir")));
        assertThat(live.committed()).isEqualTo("Hallo, Welt.");
        assertThat(live.provisional()).isEqualTo("wie geht es dir");
    }

    @Test
    void dropsWordsThatRepeatTheEndOfTheCommittedText() {
        LiveTranscript live = new LiveTranscript(0, TUNING);
        live.accept(live.next(2 * R), List.of(seg(0, 2, "Guten Morgen")));
        live.accept(live.next(3 * R), List.of(seg(0, 2, "Guten Morgen"), seg(2, 3, "zusammen")));
        assertThat(live.committed()).isEqualTo("Guten Morgen");

        // Zeitstempel etwas zu früh: die neue Erkennung beginnt noch einmal mit „Morgen“.
        live.accept(live.next(5 * R), List.of(seg(2, 5, "Morgen zusammen heute")));
        assertThat(live.provisional()).isEqualTo("zusammen heute");
        assertThat(live.finish(List.of(seg(2, 6, "morgen zusammen heute"))))
                .isEqualTo("Guten Morgen zusammen heute");
    }

    @Test
    void aFullWindowCommitsAllButTheLastSegmentSoItNeverReachesThirtySeconds() {
        LiveTranscript live = new LiveTranscript(0, TUNING);
        live.accept(live.next(5 * R), List.of(seg(0, 5, "eins zwei")));
        LiveTranscript.Window full = live.next(20 * R);
        assertThat(full).isEqualTo(new LiveTranscript.Window(0, 10 * R, true));
        live.accept(full, List.of(seg(0, 4, "anders eins"), seg(4, 8, "drei vier"), seg(8, 10, "fünf")));
        assertThat(live.committed()).isEqualTo("anders eins drei vier");
        assertThat(live.provisional()).isEqualTo("fünf");
        assertThat(live.start()).isEqualTo(8L * R);
        assertThat(live.next(30 * R).end() - live.next(30 * R).start()).isLessThanOrEqualTo(10L * R);
    }

    @Test
    void silenceInAFullWindowMovesOnButKeepsTheLastMoment() {
        LiveTranscript live = new LiveTranscript(0, TUNING);
        live.accept(live.next(12 * R), List.of());
        assertThat(live.start()).isEqualTo(9L * R);
        assertThat(live.committed()).isEmpty();
    }

    @Test
    void finishAppendsTheRestOnce() {
        LiveTranscript live = new LiveTranscript(0, TUNING);
        live.accept(live.next(2 * R), List.of(seg(0, 1, "Eins"), seg(1, 2, "zwei")));
        live.accept(live.next(3 * R), List.of(seg(0, 1, "Eins"), seg(1, 3, "zwei drei")));
        assertThat(live.committed()).isEqualTo("Eins");
        assertThat(live.finish(List.of(seg(1, 4, "zwei drei vier")))).isEqualTo("Eins zwei drei vier");
        assertThat(live.provisional()).isEmpty();
    }
}
