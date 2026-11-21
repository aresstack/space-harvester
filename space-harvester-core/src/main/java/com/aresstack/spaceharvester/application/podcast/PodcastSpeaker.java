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
 * <p>Selbstheilend (Angelo 21.11.2026): Scheitert ein Beitrag auch nach den Wiederholungen des Ablaufs, versucht der
 * Sprecher ihn Satz für Satz, dann mit der anderen Stimme, dann mit dem nächsten Sprachmodell des Dienstes. Erst wenn
 * alles scheitert, scheitert der Abschnitt; der Auftrag versucht ihn später noch einmal.
 */
public final class PodcastSpeaker {

    /** Pause zwischen zwei Beiträgen. */
    static final int PAUSE_MILLIS = 350;
    /** Höchstens so viele Sprachmodelle werden versucht. */
    static final int MAX_MODELS = 2;

    private final PodcastSpeech speech;
    private final List<String> models;
    private final PodcastVoices voices;

    /** @param models die Sprachmodelle des Dienstes, bevorzugtes zuerst ({@link PodcastVoices#ranked}) */
    public PodcastSpeaker(PodcastSpeech speech, List<String> models, PodcastVoices voices) {
        this.speech = Objects.requireNonNull(speech, "speech");
        if (models.isEmpty()) {
            throw new IllegalArgumentException("models must not be empty");
        }
        this.models = List.copyOf(models.subList(0, Math.min(MAX_MODELS, models.size())));
        this.voices = Objects.requireNonNull(voices, "voices");
    }

    /** Das bevorzugte Modell, dessen Stimmen der Podcast trägt. */
    public String model() {
        return models.get(0);
    }

    /** Die Stimmen des bevorzugten Modells. */
    public PodcastVoices.Pair pair() {
        return voices.pair(model());
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

    /** Die WAV-Stücke eines Beitrags, mit allen Auswegen. */
    private List<byte[]> turn(Turn turn, List<String> repairs) throws IOException {
        String lastProblem = "unbekannt";
        for (int m = 0; m < models.size(); m++) {
            String model = models.get(m);
            PodcastVoices.Pair pair = voices.pair(model);
            String voice = turn.speaker() == Speaker.HOST ? pair.host() : pair.cohost();
            String other = turn.speaker() == Speaker.HOST ? pair.cohost() : pair.host();
            List<Attempt> attempts = new ArrayList<>();
            attempts.add(new Attempt(voice, false, null));
            attempts.add(new Attempt(voice, true, "Satz für Satz"));
            if (other != null && !other.equals(voice)) {
                attempts.add(new Attempt(other, true, "mit der anderen Stimme"));
            }
            for (Attempt attempt : attempts) {
                try {
                    List<byte[]> wavs = speech.speak(model, attempt.voice(), turn.text(), attempt.sentences());
                    if (!wavs.isEmpty()) {
                        if (attempt.note() != null || m > 0) {
                            repairs.add("Beitrag " + (attempt.note() == null ? "" : attempt.note() + " ")
                                    + (m > 0 ? "mit dem Sprachmodell " + model + " " : "") + "gesprochen");
                        }
                        return wavs;
                    }
                    lastProblem = "kein Audio";
                } catch (IOException | RuntimeException e) {
                    lastProblem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
        }
        throw new IOException("Sprachausgabe gescheitert (" + lastProblem + ")");
    }

    private record Attempt(String voice, boolean sentences, String note) {
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
