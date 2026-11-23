package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.astu.acropolis.speech.AudioPlayback;
import com.aresstack.enterpriseai.astu.acropolis.speech.ReadAloudService;
import com.aresstack.enterpriseai.astu.acropolis.speech.SpeechChunking;
import com.aresstack.enterpriseai.astu.acropolis.speech.TextAnalysisService;
import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.podcast.PodcastSpeech;
import com.aresstack.spaceharvester.application.podcast.ReadAloud;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Die Sprachausgabe des KI-Dienstes für den Podcast: TTS-Modelle aus dem Modellkatalog ({@code GET /models}, Kategorie
 * {@code tts} wie im Enterprise AI Client) und {@link RemoteAi#speech()} für {@code POST /audio/speech}. Folgt der
 * wirksamen Konfiguration bei jedem Aufruf wie {@link ChatStepModel}; einstellen muss man nichts.
 *
 * <p>Ein Beitrag läuft durch den Vorlese-Ablauf des Enterprise AI Client ({@link ReadAloudService}, unverändert kopiert):
 * Aufbereitung zu sprechbaren Zeichen, Satzanalyse über die OpenNLP-Satzgrenzen des Core ({@link TextAnalysisService})
 * und der Stimmstabilitäts-Fix, der lange Stücke nach etwa 500 Zeichen an der nächsten Satzgrenze beendet, weil
 * generative Stimmen wie Orpheus über rund 1000 Zeichen ihre Stimme wechseln. Statt abzuspielen sammelt eine eigene
 * {@link AudioPlayback} die WAV-Stücke.
 */
public final class RemotePodcastSpeech implements PodcastSpeech {

    /** Versuche je Stück im Vorlese-Ablauf (wie dessen Regler: 1 bis 5). */
    static final int ATTEMPTS = 3;
    /** Parallele Synthesen je Beitrag. */
    static final int WORKERS = 2;

    private final Supplier<AiServiceSettings> settings;
    private final Function<AiServiceSettings, RemoteAi> remote;
    private final TextAnalysisService analysis;

    /** @param sentences die Satzgrenzen des Core; {@code null} = Schnitt an Satzzeichen */
    public RemotePodcastSpeech(Supplier<AiServiceSettings> settings, ConfiguredSourceCatalog catalog,
                               TrustBundles trustBundles, String userAgent, SentenceSegmenter sentences) {
        this(settings, current -> RemoteAi.of(current, catalog, trustBundles, userAgent), analysis(sentences));
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(trustBundles, "trustBundles");
        Objects.requireNonNull(userAgent, "userAgent");
    }

    RemotePodcastSpeech(Supplier<AiServiceSettings> settings, Function<AiServiceSettings, RemoteAi> remote,
                        TextAnalysisService analysis) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.remote = Objects.requireNonNull(remote, "remote");
        this.analysis = analysis;
    }

    static TextAnalysisService analysis(SentenceSegmenter sentences) {
        return sentences == null ? null : new TextAnalysisService(() -> SentenceAnalysis.engine(sentences),
                TextAnalysisService.DEFAULT_STOP_GRACE_MILLIS);
    }

    @Override
    public boolean available() {
        AiServiceSettings current = settings.get();
        return current != null && current.configured() && current.provider() != AiProvider.OLLAMA;
    }

    @Override
    public List<String> models() {
        AiServiceSettings current = settings.get();
        if (!available()) {
            return List.of();
        }
        return remote.apply(current).models().stream()
                .filter(model -> model.categories().contains(AiModel.TTS))
                .map(AiModel::id).toList();
    }

    @Override
    public int workers() {
        return WORKERS;
    }

    @Override
    public List<byte[]> speak(String model, String voice, String text, boolean sentences) throws IOException {
        SpeechSynthesisPort port;
        try {
            port = remote.apply(settings.get()).speech();
        } catch (RuntimeException e) {
            throw new IOException("Sprachausgabe nicht eingerichtet (" + e.getClass().getSimpleName() + ")");
        }
        return speak(port, analysis, model, voice, text, sentences);
    }

    /**
     * Vorlesen in der Leseansicht (Angelo 23.11.2026): der Vorlese-Ablauf des Enterprise AI Client mit den Vorgaben
     * seiner Sprachausgabe (Absätze, Stimmstabilitäts-Fix, Worker und Versuche wie {@code ReadAloudService}), gespielt
     * über die Wiedergabe der App.
     */
    @Override
    public ReadAloud readAloud(String model, String voice, ReadAloud.Playback playback) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(playback, "playback");
        SpeechSynthesisPort port;
        try {
            port = remote.apply(settings.get()).speech();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Sprachausgabe nicht eingerichtet (" + e.getClass().getSimpleName() + ")");
        }
        return readAloud(port, analysis, model, voice, playback);
    }

    static ReadAloud readAloud(SpeechSynthesisPort port, TextAnalysisService analysis, String model, String voice,
                               ReadAloud.Playback playback) {
        ReadAloudService service = new ReadAloudService(port, model, voice, new Played(playback),
                SpeechChunking.PARAGRAPHS, ReadAloudService.DEFAULT_SYNTHESIS_WORKERS,
                ReadAloudService.DEFAULT_SYNTHESIS_ATTEMPTS, analysis, null, true);
        return new ReadAloud() {
            @Override
            public boolean speak(String text, ReadAloud.Listener listener) {
                return service.speak(text, new ReadAloudService.Listener() {
                    @Override
                    public void failed(String message) {
                        if (listener != null) {
                            listener.failed(message);
                        }
                    }
                });
            }

            @Override
            public void stop() {
                service.stop();
            }

            @Override
            public void pause() {
                service.pause();
            }

            @Override
            public void resume() {
                service.resume();
            }

            @Override
            public boolean isPaused() {
                return service.isPaused();
            }

            @Override
            public void setRate(double rate) {
                service.setRate(rate);
            }

            @Override
            public double rate() {
                return service.rate();
            }

            @Override
            public String model() {
                return service.modelId();
            }

            @Override
            public String voice() {
                return service.voice();
            }
        };
    }

    /** Die Wiedergabe der App als {@link AudioPlayback} des Vorlese-Ablaufs. */
    private record Played(ReadAloud.Playback playback) implements AudioPlayback {

        @Override
        public void begin() {
            playback.begin();
        }

        @Override
        public void play(byte[] wav) throws IOException {
            playback.play(wav);
        }

        @Override
        public void finish() {
            playback.finish();
        }

        @Override
        public void stop() {
            playback.stop();
        }

        @Override
        public void pause() {
            playback.pause();
        }

        @Override
        public void resume() {
            playback.resume();
        }

        @Override
        public void setRate(double rate) {
            playback.setRate(rate);
        }
    }

    /** Ein Beitrag durch den Vorlese-Ablauf, mit gesammelten statt gespielten Stücken. */
    static List<byte[]> speak(SpeechSynthesisPort port, TextAnalysisService analysis, String model, String voice,
                              String text, boolean sentences) throws IOException {
        Collector collector = new Collector();
        AtomicReference<String> problem = new AtomicReference<>();
        ReadAloudService service = new ReadAloudService(port, model, voice, collector,
                sentences ? SpeechChunking.SENTENCES : SpeechChunking.PARAGRAPHS, WORKERS, ATTEMPTS, analysis, null,
                true);
        service.speak(text, new ReadAloudService.Listener() {
            @Override
            public void failed(String message) {
                problem.compareAndSet(null, message);
            }
        });
        if (problem.get() != null) {
            throw new IOException(problem.get());
        }
        if (collector.wavs.isEmpty()) {
            throw new IOException("kein Audio");
        }
        return collector.wavs;
    }

    /** Sammelt die Stücke statt sie abzuspielen. */
    private static final class Collector implements AudioPlayback {

        final List<byte[]> wavs = new ArrayList<>();

        @Override
        public synchronized void play(byte[] wav) {
            wavs.add(wav);
        }

        @Override
        public void finish() {
        }

        @Override
        public void stop() {
        }
    }
}
