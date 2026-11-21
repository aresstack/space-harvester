package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.enterpriseai.astu.acropolis.speech.AudioPlayback;
import com.aresstack.enterpriseai.astu.acropolis.speech.ReadAloudService;
import com.aresstack.enterpriseai.astu.acropolis.speech.SpeechChunking;
import com.aresstack.enterpriseai.astu.acropolis.speech.TextAnalysisService;
import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spricht den Text eines Abschnitts über den Vorlese-Ablauf des Enterprise AI Client ({@link ReadAloudService}, unverändert
 * kopiert): Aufbereitung zu sprechbaren Zeichen, Satzanalyse über OpenNLP ({@link TextAnalysisService}) und der
 * Stimmstabilitäts-Fix, der lange Stücke nach etwa 500 Zeichen an der nächsten Satzgrenze beendet, weil generative
 * Stimmen wie Orpheus über rund 1000 Zeichen ihre Stimme wechseln. Statt abzuspielen sammelt eine eigene
 * {@link AudioPlayback} die WAV-Stücke; die Beiträge des Abschnitts werden mit kurzen Pausen zu einer WAV-Datei.
 *
 * <p>Selbstheilend (Angelo 21.11.2026): Scheitert ein Beitrag auch nach den Wiederholungen des Ablaufs, versucht der
 * Sprecher ihn Satz für Satz, dann mit der anderen Stimme, dann mit dem nächsten Sprachmodell des Dienstes. Erst wenn
 * alles scheitert, scheitert der Abschnitt; der Auftrag versucht ihn später noch einmal.
 */
public final class PodcastSpeaker {

    /** Versuche je Stück im Vorlese-Ablauf (wie dessen Regler: 1 bis 5). */
    static final int ATTEMPTS = 3;
    /** Parallele Synthesen je Beitrag. */
    static final int WORKERS = 2;
    /** Pause zwischen zwei Beiträgen. */
    static final int PAUSE_MILLIS = 350;
    /** Höchstens so viele Sprachmodelle werden versucht. */
    static final int MAX_MODELS = 2;

    private final SpeechSynthesisPort port;
    private final List<String> models;
    private final PodcastVoices voices;
    private final TextAnalysisService analysis;

    /**
     * @param models die Sprachmodelle des Dienstes, bevorzugtes zuerst ({@link PodcastVoices#ranked})
     * @param analysis Satzanalyse; {@code null} = Schnitt an Satzzeichen
     */
    public PodcastSpeaker(SpeechSynthesisPort port, List<String> models, PodcastVoices voices,
                          TextAnalysisService analysis) {
        this.port = Objects.requireNonNull(port, "port");
        if (models.isEmpty()) {
            throw new IllegalArgumentException("models must not be empty");
        }
        this.models = List.copyOf(models.subList(0, Math.min(MAX_MODELS, models.size())));
        this.voices = Objects.requireNonNull(voices, "voices");
        this.analysis = analysis;
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
            attempts.add(new Attempt(voice, SpeechChunking.PARAGRAPHS, null));
            attempts.add(new Attempt(voice, SpeechChunking.SENTENCES, "Satz für Satz"));
            if (other != null && !other.equals(voice)) {
                attempts.add(new Attempt(other, SpeechChunking.SENTENCES, "mit der anderen Stimme"));
            }
            for (Attempt attempt : attempts) {
                AtomicReference<String> problem = new AtomicReference<>();
                List<byte[]> wavs = synthesize(model, attempt.voice(), attempt.chunking(), turn.text(), problem);
                if (problem.get() == null && !wavs.isEmpty()) {
                    if (attempt.note() != null || m > 0) {
                        repairs.add("Beitrag " + (attempt.note() == null ? "" : attempt.note() + " ")
                                + (m > 0 ? "mit dem Sprachmodell " + model : "") + "gesprochen");
                    }
                    return wavs;
                }
                lastProblem = problem.get() == null ? "kein Audio" : problem.get();
            }
        }
        throw new IOException("Sprachausgabe gescheitert (" + lastProblem + ")");
    }

    private record Attempt(String voice, SpeechChunking chunking, String note) {
    }

    private List<byte[]> synthesize(String model, String voice, SpeechChunking chunking, String text,
                                    AtomicReference<String> problem) {
        Collector collector = new Collector();
        ReadAloudService service = new ReadAloudService(port, model, voice, collector, chunking, WORKERS, ATTEMPTS,
                analysis, null, true);
        service.speak(text, new ReadAloudService.Listener() {
            @Override
            public void failed(String message) {
                problem.compareAndSet(null, message);
            }
        });
        return collector.wavs;
    }

    /** Sammelt die Stücke statt sie abzuspielen. */
    private static final class Collector implements AudioPlayback {

        final List<byte[]> wavs = new ArrayList<>();

        @Override
        public void play(byte[] wav) {
            wavs.add(wav);
        }

        @Override
        public void finish() {
        }

        @Override
        public void stop() {
        }
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
