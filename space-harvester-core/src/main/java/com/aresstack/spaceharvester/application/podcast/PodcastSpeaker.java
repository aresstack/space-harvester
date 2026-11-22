package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Speaker;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Turn;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Spricht die Beiträge eines Abschnitts über {@link PodcastSpeech} und fügt sie mit kurzen Pausen zu einer WAV-Datei.
 * Die Sprachausgabe nimmt den Vorlese-Ablauf des Enterprise AI Client: Aufbereitung zu sprechbaren Zeichen,
 * Satzanalyse und der Stimmstabilitäts-Fix, der lange Stücke nach etwa 500 Zeichen an der nächsten Satzgrenze beendet,
 * weil generative Stimmen wie Orpheus über rund 1000 Zeichen ihre Stimme wechseln.
 *
 * <p>Jede Rolle behält ihre Stimme (Angelo 22.11.2026: die Stimmen waren nicht auseinanderzuhalten): Jeder Beitrag
 * spricht mit der Stimme seiner Rolle im Sprachmodell des Podcasts, nie still mit der Stimme der anderen Rolle oder mit
 * einem anderen Modell, dessen Stimmen anders klängen. Welche Stimmen und Modelle es gibt, bestimmt weiter
 * {@link PodcastVoices}. Selbstheilend (Angelo 21.11.2026): Scheitert ein Beitrag auch nach den Wiederholungen des
 * Ablaufs, versucht der Sprecher ihn Satz für Satz; scheitert auch das, scheitert der Abschnitt und der Auftrag versucht
 * ihn später noch einmal (Diagnose {@code PODCAST_AUDIO_MISSING}).
 */
public final class PodcastSpeaker {

    /** Pause zwischen zwei Beiträgen. */
    static final int PAUSE_MILLIS = 350;
    /**
     * Kürzel, die die Sprachausgabe als Wort statt Buchstabe für Buchstabe läse (Angelo 22.11.2026, „BSCW-Update“); nur
     * für das Audio, der Text im Bericht bleibt, wie er ist.
     */
    private static final java.util.regex.Pattern BSCW = java.util.regex.Pattern.compile("\\bBSCW\\b");

    private final PodcastSpeech speech;
    private final String model;
    private final PodcastVoices.Pair pair;

    /**
     * @param model das Sprachmodell des Podcasts
     * @param pair  die Stimmen von Gastgeber und Co-Host, für den ganzen Podcast fest
     */
    public PodcastSpeaker(PodcastSpeech speech, String model, PodcastVoices.Pair pair) {
        this.speech = Objects.requireNonNull(speech, "speech");
        this.model = Objects.requireNonNull(model, "model");
        this.pair = Objects.requireNonNull(pair, "pair");
    }

    /** Das Sprachmodell, dessen Stimmen der Podcast trägt. */
    public String model() {
        return model;
    }

    /** Die Stimmen des Podcasts. */
    public PodcastVoices.Pair pair() {
        return pair;
    }

    /**
     * Die WAV-Datei eines Abschnitts.
     *
     * @param repairs nimmt auf, welcher Ausweg gebraucht wurde, eine Zeile je Ausweg
     * @throws IOException wenn ein Beitrag mit keinem Ausweg zu sprechen war; die Meldung nennt nie Text
     */
    public byte[] speak(List<Turn> turns, List<String> repairs) throws IOException {
        List<AudioInputStream> parts = new ArrayList<>();
        AudioFormat format = null;
        for (Turn turn : turns) {
            if (turn.text().isBlank()) {
                continue;
            }
            for (byte[] wav : turn(turn, repairs)) {
                AudioInputStream stream = stream(wav);
                if (format == null) {
                    format = stream.getFormat();
                } else if (!format.matches(stream.getFormat())) {
                    stream = convert(stream, format);
                }
                parts.add(stream);
            }
            parts.add(silence(format));
        }
        if (parts.isEmpty() || format == null) {
            throw new IOException("Abschnitt ohne sprechbaren Text");
        }
        return wav(parts, format);
    }

    /** Der Text, wie ihn die Sprachausgabe bekommt: Kürzel Buchstabe für Buchstabe. */
    static String pronounced(String text) {
        return BSCW.matcher(text).replaceAll("Be Es Ze We");
    }

    /** Die WAV-Stücke eines Beitrags: mit der Stimme seiner Rolle, absatzweise, sonst Satz für Satz. */
    private List<byte[]> turn(Turn turn, List<String> repairs) throws IOException {
        String voice = turn.speaker() == Speaker.HOST ? pair.host() : pair.cohost();
        String lastProblem = "unbekannt";
        for (boolean sentences : new boolean[] {false, true}) {
            try {
                List<byte[]> wavs = speech.speak(model, voice, pronounced(turn.text()), sentences);
                if (!wavs.isEmpty()) {
                    if (sentences) {
                        repairs.add("Beitrag Satz für Satz gesprochen");
                    }
                    return wavs;
                }
                lastProblem = "kein Audio";
            } catch (IOException | RuntimeException e) {
                lastProblem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        throw new IOException("Sprachausgabe gescheitert (" + lastProblem + ")");
    }

    private static AudioInputStream stream(byte[] wav) throws IOException {
        try {
            return AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Sprachausgabe lieferte kein lesbares WAV", e);
        }
    }

    private static AudioInputStream convert(AudioInputStream stream, AudioFormat target) throws IOException {
        try {
            return AudioSystem.getAudioInputStream(target, stream);
        } catch (IllegalArgumentException e) {
            throw new IOException("Audioformat nicht umwandelbar", e);
        }
    }

    private static AudioInputStream silence(AudioFormat format) {
        int frames = Math.round(format.getFrameRate() * PAUSE_MILLIS / 1000f);
        byte[] zeros = new byte[Math.max(0, frames * format.getFrameSize())];
        return new AudioInputStream(new ByteArrayInputStream(zeros), format, frames);
    }

    private static byte[] wav(List<AudioInputStream> parts, AudioFormat format) throws IOException {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (AudioInputStream part : parts) {
            part.transferTo(pcm);
        }
        byte[] bytes = pcm.toByteArray();
        AudioInputStream all = new AudioInputStream(new ByteArrayInputStream(bytes), format,
                bytes.length / Math.max(1, format.getFrameSize()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(all, AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }
}
