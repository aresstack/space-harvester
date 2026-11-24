package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.speech.api.SpeechSynthesisException;
import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;
import com.aresstack.enterpriseai.speech.api.SynthesizedSpeech;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
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
                false, AiServiceSettings.Speech.DEFAULT_PODCAST_WORKERS, AiServiceSettings.Speech.DEFAULT_PODCAST_ATTEMPTS);

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
                "Jana", "Das Projekt Nordlicht hat eine neue Startseite.", true,
                AiServiceSettings.Speech.DEFAULT_PODCAST_WORKERS, AiServiceSettings.Speech.DEFAULT_PODCAST_ATTEMPTS))
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
    void reportsWhichParagraphIsPlayingForARoughHighlight() {
        List<int[]> playing = Collections.synchronizedList(new ArrayList<>());
        String text = "Der Nachtlauf Nordlicht startet um zwei Uhr.\n\nVorher prüft er den Spool-Bereich.";
        com.aresstack.spaceharvester.application.podcast.ReadAloud reading = RemotePodcastSpeech.readAloud(
                port(new ArrayList<>(), false), null, "orpheus-3b-german", "Jana",
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

        reading.speak(text, new com.aresstack.spaceharvester.application.podcast.ReadAloud.Listener() {
            @Override
            public void playing(int from, int to, long millis) {
                playing.add(new int[] {from, to});
            }
        });

        // „es muss nicht unbedingt Wort für Wort sein“: je Absatz einmal, in Sprechreihenfolge, ungefähr an seiner Stelle
        assertThat(playing).hasSize(2);
        assertThat(text.substring(playing.get(0)[0], playing.get(0)[1]))
                .isEqualTo("Der Nachtlauf Nordlicht startet um zwei Uhr.");
        assertThat(text.substring(playing.get(1)[0], playing.get(1)[1])).contains("Vorher prüft er");
    }

    @Test
    void theDurationOfAPieceComesFromItsWavHeader() {
        // 16 kHz, mono, 16 Bit: 32000 Bytes je Sekunde, 16000 Bytes Daten = eine halbe Sekunde
        byte[] wav = new byte[44 + 16000];
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + 16000)
                .put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16000);

        assertThat(RemotePodcastSpeech.wavMillis(wav, 1)).isEqualTo(500);
        assertThat(RemotePodcastSpeech.wavMillis(wav, 2)).isEqualTo(250);
        assertThat(RemotePodcastSpeech.wavMillis(new byte[] {1}, 1)).isZero();
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
