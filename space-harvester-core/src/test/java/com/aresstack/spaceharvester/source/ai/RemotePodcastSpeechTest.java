package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.speech.api.SpeechSynthesisException;
import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;
import com.aresstack.enterpriseai.speech.api.SynthesizedSpeech;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Erfundener Text über ein fiktives Projekt „Nordlicht“. */
class RemotePodcastSpeechTest {

    @Test
    void cutsLongTurnsBeforeGenerativeVoicesDrift() throws Exception {
        List<String> pieces = Collections.synchronizedList(new ArrayList<>());
        String sentence = "Beim Nachtlauf Nordlicht wird jetzt vor dem Start geprüft, ob der Spool-Bereich voll ist. ";
        String text = sentence.repeat(20).trim();

        List<byte[]> wavs = RemotePodcastSpeech.speak(port(pieces, false), null, "orpheus-3b-german", "Jana", text,
                false);

        // Stimmstabilitäts-Fix des Enterprise AI Client: kein Stück erreicht die rund 1000 Zeichen, ab denen Orpheus
        // die Stimme wechselt
        assertThat(text.length()).isGreaterThan(1500);
        assertThat(pieces).hasSizeGreaterThan(2);
        assertThat(pieces).allSatisfy(piece -> assertThat(piece.length()).isLessThan(1000));
        assertThat(wavs).hasSameSizeAs(pieces);
    }

    @Test
    void reportsAFailureWithoutTheText() {
        assertThatThrownBy(() -> RemotePodcastSpeech.speak(port(new ArrayList<>(), true), null, "orpheus-3b-german",
                "Jana", "Das Projekt Nordlicht hat eine neue Startseite.", true))
                .isInstanceOf(IOException.class)
                .message().doesNotContain("Nordlicht");
    }

    @Test
    void readsAloudThroughThePlaybackOfTheAppInSpeakingOrder() {
        List<String> pieces = Collections.synchronizedList(new ArrayList<>());
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        String text = "Der Nachtlauf Nordlicht startet um zwei Uhr.\n\nVorher prüft er den Spool-Bereich.";
        com.aresstack.spaceharvester.application.podcast.ReadAloud reading = RemotePodcastSpeech.readAloud(
                port(pieces, false), null, "orpheus-3b-german", "Jana",
                new com.aresstack.spaceharvester.application.podcast.ReadAloud.Playback() {
                    @Override
                    public void begin() {
                        calls.add("begin");
                    }

                    @Override
                    public void play(byte[] wav) {
                        calls.add("play");
                    }

                    @Override
                    public void finish() {
                        calls.add("finish");
                    }

                    @Override
                    public void stop() {
                        calls.add("stop");
                    }
                });

        boolean spoken = reading.speak(text, null);

        // Absätze wie im Lesebereich des Enterprise AI Client, jeder ein Stück, in Sprechreihenfolge gespielt
        assertThat(spoken).isTrue();
        assertThat(pieces).containsExactlyInAnyOrder("Der Nachtlauf Nordlicht startet um zwei Uhr.",
                "Vorher prüft er den Spool-Bereich.");
        assertThat(calls).containsExactly("begin", "play", "play", "finish");
        assertThat(reading.model()).isEqualTo("orpheus-3b-german");
        assertThat(reading.voice()).isEqualTo("Jana");
    }

    @Test
    void aFailedPieceIsReportedWithoutTheText() {
        List<String> problems = new ArrayList<>();
        com.aresstack.spaceharvester.application.podcast.ReadAloud reading = RemotePodcastSpeech.readAloud(
                port(new ArrayList<>(), true), null, "orpheus-3b-german", "Jana",
                new com.aresstack.spaceharvester.application.podcast.ReadAloud.Playback() {
                    @Override
                    public void play(byte[] wav) {
                    }

                    @Override
                    public void finish() {
                    }

                    @Override
                    public void stop() {
                    }
                });

        reading.speak("Das Projekt Nordlicht hat eine neue Startseite.",
                new com.aresstack.spaceharvester.application.podcast.ReadAloud.Listener() {
                    @Override
                    public void failed(String message) {
                        problems.add(message);
                    }
                });

        assertThat(problems).isNotEmpty().allSatisfy(message -> assertThat(message).doesNotContain("Nordlicht"));
    }

    private static SpeechSynthesisPort port(List<String> pieces, boolean failing) {
        return new SpeechSynthesisPort() {
            @Override
            public String catalogId() {
                return "test";
            }

            @Override
            public SynthesizedSpeech synthesize(String modelId, String voice, String text)
                    throws SpeechSynthesisException {
                if (failing) {
                    throw new SpeechSynthesisException("HTTP 503");
                }
                pieces.add(text);
                return new SynthesizedSpeech(new byte[]{1});
            }
        };
    }
}
