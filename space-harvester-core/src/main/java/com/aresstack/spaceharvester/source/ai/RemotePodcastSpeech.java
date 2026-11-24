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
    public AiServiceSettings.Speech choice() {
        AiServiceSettings current = settings.get();
        return current == null ? AiServiceSettings.Speech.AUTOMATIC : current.speech();
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
        Played played = new Played(playback);
        ReadAloudService service = new ReadAloudService(port, model, voice, played,
                SpeechChunking.PARAGRAPHS, ReadAloudService.DEFAULT_SYNTHESIS_WORKERS,
                ReadAloudService.DEFAULT_SYNTHESIS_ATTEMPTS, analysis, null, true);
        return new ReadAloud() {
            @Override
            public boolean speak(String text, ReadAloud.Listener listener) {
                Progress progress = new Progress(text == null ? 0 : text.length(), listener);
                played.progress = progress;
                try {
                    return service.speak(text, new ReadAloudService.Listener() {
                        @Override
                        public void failed(String message) {
                            if (listener != null) {
                                listener.failed(message);
                            }
                        }

                        @Override
                        public void chunkRequested(ReadAloudService.ChunkTrace trace) {
                            progress.requested(trace.index(), trace.count(), trace.characters());
                        }
                    });
                } finally {
                    if (played.progress == progress) {
                        played.progress = null;
                    }
                }
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

    /**
     * Wo das Vorlesen ungefähr steht (Angelo 23.11.2026, grobe Hervorhebung im Chat): Der Ablauf meldet je Stück nur
     * dessen Länge, die Stücke folgen lückenlos in Lesereihenfolge und erklingen in dieser Reihenfolge. Das n-te
     * gespielte Stück deckt also ungefähr die Zeichen hinter den n-1 vorherigen ab (je Stückgrenze ein getilgter
     * Trenner).
     */
    static final class Progress {

        private final int length;
        private final ReadAloud.Listener listener;
        private int[] characters = new int[0];
        private int played;

        Progress(int length, ReadAloud.Listener listener) {
            this.length = length;
            this.listener = listener;
        }

        /** Stück {@code index} (ab 1) von {@code count} hat {@code characters} Zeichen. */
        synchronized void requested(int index, int count, int characters) {
            if (this.characters.length < count) {
                this.characters = java.util.Arrays.copyOf(this.characters, count);
            }
            if (index >= 1 && index <= this.characters.length) {
                this.characters[index - 1] = characters;
            }
        }

        /** Das nächste Stück beginnt zu erklingen und dauert etwa {@code millis}. */
        void playing(long millis) {
            int from;
            int to;
            synchronized (this) {
                int index = played++;
                int start = 0;
                for (int i = 0; i < index && i < characters.length; i++) {
                    start += characters[i] + 1;
                }
                int size = index < characters.length ? characters[index] : 0;
                from = Math.min(start, length);
                to = Math.min(start + size, length);
            }
            if (listener != null && to > from) {
                listener.playing(from, to, millis);
            }
        }
    }

    /**
     * Ungefähre Spieldauer eines WAV-Stücks in Millisekunden aus seinem Kopf ({@code fmt}: Bytes je Sekunde,
     * {@code data}: Länge); 0, wenn der Kopf nicht zu lesen ist.
     */
    static long wavMillis(byte[] wav, double rate) {
        if (wav == null || wav.length < 12 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
            return 0;
        }
        long byteRate = 0;
        long data = -1;
        int at = 12;
        while (at + 8 <= wav.length) {
            String id = new String(wav, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = littleEndian(wav, at + 4);
            int body = at + 8;
            if ("fmt ".equals(id) && body + 12 <= wav.length) {
                byteRate = littleEndian(wav, body + 8);
            } else if ("data".equals(id)) {
                data = Math.min(size, wav.length - body);
                break;
            }
            at = (int) Math.min(Integer.MAX_VALUE, body + size + (size & 1));
        }
        if (byteRate <= 0 || data < 0) {
            return 0;
        }
        return Math.round(data * 1000.0 / byteRate / (rate > 0 ? rate : 1));
    }

    private static long littleEndian(byte[] bytes, int at) {
        return (bytes[at] & 0xffL) | (bytes[at + 1] & 0xffL) << 8 | (bytes[at + 2] & 0xffL) << 16
                | (bytes[at + 3] & 0xffL) << 24;
    }

    /** Die Wiedergabe der App als {@link AudioPlayback} des Vorlese-Ablaufs; meldet, welches Stück gerade erklingt. */
    private static final class Played implements AudioPlayback {

        private final ReadAloud.Playback playback;
        /** Der Stand des laufenden Vorlesens; {@code null} dazwischen. */
        volatile Progress progress;
        private volatile double rate = 1;

        Played(ReadAloud.Playback playback) {
            this.playback = playback;
        }

        @Override
        public void begin() {
            playback.begin();
        }

        @Override
        public void play(byte[] wav) throws IOException {
            Progress current = progress;
            if (current != null) {
                current.playing(wavMillis(wav, rate));
            }
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
            this.rate = rate;
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
