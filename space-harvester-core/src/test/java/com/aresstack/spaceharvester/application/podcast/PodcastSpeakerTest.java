package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Speaker;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Turn;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Jede Rolle behält ihre Stimme (Angelo 22.11.2026: Stimmen nicht auseinanderzuhalten), kein stiller Wechsel. */
class PodcastSpeakerTest {

    private static final String MODEL = "orpheus-3b-german";

    @Test
    void theHostIsAWomanAndTheCohostAClearlyDifferentMan() {
        PodcastVoices.Pair pair = PodcastVoices.bundled().pair(MODEL);

        assertThat(pair.host()).isEqualTo("Jana");
        assertThat(pair.cohost()).isEqualTo("Thomas");
        assertThat(PodcastVoices.bundled().pair("qwen3-tts-customvoice")).isEqualTo(new PodcastVoices.Pair("Vivian",
                "Ryan"));
    }

    @Test
    void eachRoleSpeaksOnlyWithItsOwnVoice() throws IOException {
        Recording speech = new Recording(call -> false);
        PodcastSpeaker speaker = new PodcastSpeaker(speech, MODEL, new PodcastVoices.Pair("Jana", "Thomas"));

        speaker.speak(List.of(new Turn(Speaker.HOST, "Hallo und willkommen."),
                new Turn(Speaker.COHOST, "Schön, dass ihr dabei seid."),
                new Turn(Speaker.HOST, "Los geht es.")), new ArrayList<>());

        assertThat(speech.calls).containsExactly("Jana|Hallo und willkommen.|absatzweise",
                "Thomas|Schön, dass ihr dabei seid.|absatzweise", "Jana|Los geht es.|absatzweise");
    }

    @Test
    void aFailingVoiceTriesSentenceBySentenceButNeverTheOtherVoice() {
        Recording speech = new Recording(call -> call.startsWith("Thomas|"));
        PodcastSpeaker speaker = new PodcastSpeaker(speech, MODEL, new PodcastVoices.Pair("Jana", "Thomas"));

        assertThatThrownBy(() -> speaker.speak(List.of(new Turn(Speaker.HOST, "Hallo."),
                new Turn(Speaker.COHOST, "Hallo zurück.")), new ArrayList<>())).isInstanceOf(IOException.class);

        assertThat(speech.calls).containsExactly("Jana|Hallo.|absatzweise", "Thomas|Hallo zurück.|absatzweise",
                "Thomas|Hallo zurück.|Satz für Satz");
    }

    @Test
    void sentenceBySentenceHealsAndIsNamedInTheRepairs() throws IOException {
        Recording speech = new Recording(call -> call.endsWith("|absatzweise") && call.startsWith("Thomas|"));
        PodcastSpeaker speaker = new PodcastSpeaker(speech, MODEL, new PodcastVoices.Pair("Jana", "Thomas"));
        List<String> repairs = new ArrayList<>();

        speaker.speak(List.of(new Turn(Speaker.COHOST, "Hallo zurück.")), repairs);

        assertThat(repairs).containsExactly("Beitrag Satz für Satz gesprochen");
        assertThat(speech.calls).allSatisfy(call -> assertThat(call).startsWith("Thomas|"));
    }

    @Test
    void bscwIsSpokenLetterByLetterButStaysInTheText() throws IOException {
        Recording speech = new Recording(call -> false);
        PodcastSpeaker speaker = new PodcastSpeaker(speech, MODEL, new PodcastVoices.Pair("Jana", "Thomas"));

        speaker.speak(List.of(new Turn(Speaker.HOST, "Willkommen zum BSCW-Update!")), new ArrayList<>());

        assertThat(speech.calls).containsExactly("Jana|Willkommen zum Be Es Ze We Update!|absatzweise");
    }

    @Test
    void measuresWhereEachTurnStartsInTheSectionForTheSubtitles() throws IOException {
        // Jedes Stück der Attrappe dauert 100 ms, dazu 350 ms Pause nach jedem Beitrag.
        PodcastSpeaker speaker = new PodcastSpeaker(new Recording(call -> false), MODEL,
                new PodcastVoices.Pair("Jana", "Thomas"));

        PodcastSpeaker.Section section = speaker.section(List.of(new Turn(Speaker.HOST, "Hallo."),
                new Turn(Speaker.COHOST, " "), new Turn(Speaker.COHOST, "Hallo zurück."),
                new Turn(Speaker.HOST, "Los geht es.")), new ArrayList<>());

        assertThat(section.starts()).containsExactly(0L, -1L, 450L, 900L);
    }

    /** Merkt sich jeden Aufruf als „Stimme|Text|Art“. */
    private static final class Recording implements PodcastSpeech {

        final List<String> calls = new ArrayList<>();
        private final Predicate<String> failing;

        Recording(Predicate<String> failing) {
            this.failing = failing;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<String> models() {
            return List.of(MODEL);
        }

        @Override
        public List<byte[]> speak(String model, String voice, String text, boolean sentences) throws IOException {
            assertThat(model).isEqualTo(MODEL);
            String call = voice + "|" + text + "|" + (sentences ? "Satz für Satz" : "absatzweise");
            calls.add(call);
            if (failing.test(call)) {
                throw new IOException("HTTP 503");
            }
            return List.of(silence());
        }

        private static byte[] silence() {
            AudioFormat format = new AudioFormat(24000f, 16, 1, true, false);
            byte[] pcm = new byte[2400 * 2];
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, 2400),
                        AudioFileFormat.Type.WAVE, out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
