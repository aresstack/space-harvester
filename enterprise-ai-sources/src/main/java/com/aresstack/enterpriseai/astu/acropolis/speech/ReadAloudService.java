package com.aresstack.enterpriseai.astu.acropolis.speech;

import com.aresstack.enterpriseai.speech.api.SpeechSynthesisException;
import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vorlese-Ablauf aus askai-java8 arch ({@code PiperReadAloudService}, Batch-Pipeline): die Antwort wird in der
 * gewählten {@link SpeechChunking} geteilt, JEDES Stück geht sofort an einen kleinen Worker-Pool, die Wiedergabe läuft
 * strikt in Lesereihenfolge über eine durchgehende Ausgabe; kein Stück wartet mit seiner Synthese auf ein anderes
 * (wie arch). Ein neues {@link #speak} oder {@link #stop()} beendet das laufende (Generationszähler wie in arch).
 * Absätze, Sätze und Sprachabschnitte kommen wie in arch aus der {@link TextAnalysisService} (Apache OpenNLP). Ohne
 * piper.exe und ohne Windows-Stimme: es spricht die eine Stimme des gewählten TTS-Modells, auch für Abschnitte, die
 * die Spracherkennung als Englisch erkennt (je Kategorie gibt es genau ein Modell).
 *
 * <p>Ein Vorlesevorgang spricht mit genau einem Modell und einer Stimme: {@code modelId} und {@code voice} sind final,
 * und jedes Stück eines Vorgangs geht mit beiden an den Port (ohne feste Stimme wählen Modelle mit mehreren Sprechern
 * je Absatz selbst). Wechselt der Benutzer das TTS-Modell, entsteht ein neuer Dienst; der alte endet über
 * {@link #stop()} und spielt danach kein Stück mehr ab. Welches Modell je Stück angefragt wurde, meldet der
 * {@link Listener} (Diagnose ohne Text und ohne Secrets; geloggt wird außerhalb des Kerns).
 */
public final class ReadAloudService {

    /** Parallele Synthese wie {@code TextToSpeechSettings.DEFAULT_SYNTHESIS_WORKERS} in arch. */
    public static final int DEFAULT_SYNTHESIS_WORKERS = 2;

    /** Obergrenze der Worker wie der Regler in arch (1 bis 8). */
    public static final int MAX_SYNTHESIS_WORKERS = 8;

    /** Versuche je Stück: ein fehlgeschlagenes Stück wird einmal wiederholt, danach bricht das Vorlesen ab. */
    public static final int DEFAULT_SYNTHESIS_ATTEMPTS = 2;

    /** Obergrenze der Versuche je Stück. */
    public static final int MAX_SYNTHESIS_ATTEMPTS = 5;

    /** Sprache der Antworten, wenn die Spracherkennung nichts erkennt (die Oberfläche ist deutsch). */
    static final String FALLBACK_LANGUAGE = "de";

    /** Rückmeldung eines Vorlesevorgangs; läuft im Vorlese-Thread. */
    public interface Listener {

        /** Ein Stück konnte nicht gesprochen werden; {@code message} ohne Secrets. */
        void failed(String message);

        /** Ein Worker fragt das Stück jetzt beim Port an (läuft im Worker-Thread). */
        default void chunkRequested(ChunkTrace trace) {
        }

        /** Das Audio des Stücks ist da (läuft im Worker-Thread); {@code wav} beschreibt nur das Format. */
        default void chunkSynthesized(ChunkTrace trace, WavFormat wav) {
        }
    }

    /**
     * Wer welches Stück mit welchem Modell anfragt: Vorgang ({@code generation}), Stück {@code index} von
     * {@code count} (ab 1), Modell, Worker-Thread und Länge des Stücks. Nie der Text selbst.
     */
    public static final class ChunkTrace {

        private final long generation;
        private final int index;
        private final int count;
        private final String modelId;
        private final String voice;
        private final String worker;
        private final int characters;

        ChunkTrace(long generation, int index, int count, String modelId, String voice, String worker,
                   int characters) {
            this.generation = generation;
            this.index = index;
            this.count = count;
            this.modelId = modelId;
            this.voice = voice;
            this.worker = worker;
            this.characters = characters;
        }

        public long generation() {
            return generation;
        }

        public int index() {
            return index;
        }

        public int count() {
            return count;
        }

        public String modelId() {
            return modelId;
        }

        /** Die Stimme des Vorgangs oder {@code null} (Standard des Ports). */
        public String voice() {
            return voice;
        }

        public String worker() {
            return worker;
        }

        public int characters() {
            return characters;
        }

        @Override
        public String toString() {
            return "generation=" + generation + " chunk=" + index + "/" + count + " model=" + modelId + " voice="
                    + (voice == null ? "none" : voice) + " worker=" + worker + " chars=" + characters;
        }
    }

    /** Das Format eines WAV-Stücks aus dem RIFF-Kopf (Abtastrate, Kanäle, Bits); unbekannt = 0. */
    public static final class WavFormat {

        private final int bytes;
        private final int sampleRate;
        private final int channels;
        private final int bitsPerSample;

        WavFormat(int bytes, int sampleRate, int channels, int bitsPerSample) {
            this.bytes = bytes;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
        }

        /** Liest den Kopf einer kanonischen RIFF/WAVE-Datei ({@code fmt }-Block); anderes Format = Werte 0. */
        public static WavFormat of(byte[] wav) {
            if (wav == null) {
                return new WavFormat(0, 0, 0, 0);
            }
            if (wav.length < 12 || !ascii(wav, 0, "RIFF") || !ascii(wav, 8, "WAVE")) {
                return new WavFormat(wav.length, 0, 0, 0);
            }
            int offset = 12;
            while (offset + 8 <= wav.length) {
                int size = littleEndian(wav, offset + 4, 4);
                if (ascii(wav, offset, "fmt ") && offset + 24 <= wav.length) {
                    return new WavFormat(wav.length, littleEndian(wav, offset + 12, 4),
                            littleEndian(wav, offset + 10, 2), littleEndian(wav, offset + 22, 2));
                }
                if (size < 0) {
                    break;
                }
                offset += 8 + size + (size & 1);
            }
            return new WavFormat(wav.length, 0, 0, 0);
        }

        public int bytes() {
            return bytes;
        }

        public int sampleRate() {
            return sampleRate;
        }

        public int channels() {
            return channels;
        }

        public int bitsPerSample() {
            return bitsPerSample;
        }

        private static boolean ascii(byte[] data, int offset, String expected) {
            for (int i = 0; i < expected.length(); i++) {
                if (data[offset + i] != expected.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private static int littleEndian(byte[] data, int offset, int length) {
            int value = 0;
            for (int i = length - 1; i >= 0; i--) {
                value = (value << 8) | (data[offset + i] & 0xff);
            }
            return value;
        }

        @Override
        public String toString() {
            return bytes + " bytes, " + sampleRate + " Hz, " + channels + " ch, " + bitsPerSample + " bit";
        }
    }

    private final SpeechSynthesisPort synthesis;
    private final String modelId;
    private final String voice;
    private final AudioPlayback playback;
    private final SpeechChunking chunking;
    private final int workers;
    private final int attempts;
    private final TextAnalysisService analysis;
    private final SpeechTextPreparation preparation;
    private final boolean voiceStabilityFix;
    private final Object lock = new Object();
    private long generation;
    private boolean paused;
    private double rate = SpeechRate.NORMAL;

    /**
     * @param synthesis die Sprachausgabe des Katalogs, aus dem {@code modelId} stammt
     * @param modelId   das gewählte TTS-Modell (ohne Katalogpräfix)
     * @param playback  die Wiedergabe
     */
    public ReadAloudService(SpeechSynthesisPort synthesis, String modelId, AudioPlayback playback) {
        this(synthesis, modelId, playback, SpeechChunking.PARAGRAPHS, DEFAULT_SYNTHESIS_WORKERS);
    }

    /**
     * @param chunking Einheit je Synthese (Standard Absätze)
     * @param workers  parallele Synthesen, 1 bis {@link #MAX_SYNTHESIS_WORKERS}
     */
    public ReadAloudService(SpeechSynthesisPort synthesis, String modelId, AudioPlayback playback,
                            SpeechChunking chunking, int workers) {
        this(synthesis, modelId, playback, chunking, workers, null);
    }

    /**
     * @param analysis Satz- und Spracherkennung (OpenNLP wie arch); {@code null} = einfacher Schnitt an Satzzeichen
     */
    public ReadAloudService(SpeechSynthesisPort synthesis, String modelId, AudioPlayback playback,
                            SpeechChunking chunking, int workers, TextAnalysisService analysis) {
        this(synthesis, modelId, null, playback, chunking, workers, analysis);
    }

    /**
     * @param voice die feste Stimme des Modells für jedes Stück; {@code null} = Standard des Ports
     */
    public ReadAloudService(SpeechSynthesisPort synthesis, String modelId, String voice, AudioPlayback playback,
                            SpeechChunking chunking, int workers, TextAnalysisService analysis) {
        this(synthesis, modelId, voice, playback, chunking, workers, DEFAULT_SYNTHESIS_ATTEMPTS, analysis);
    }

    /**
     * @param attempts Versuche je Stück, 1 bis {@link #MAX_SYNTHESIS_ATTEMPTS}; scheitert ein Stück auch beim letzten,
     *                 endet das Vorlesen dort mit einer Meldung statt das Stück still zu überspringen
     */
    public ReadAloudService(SpeechSynthesisPort synthesis, String modelId, String voice, AudioPlayback playback,
                            SpeechChunking chunking, int workers, int attempts, TextAnalysisService analysis) {
        this(synthesis, modelId, voice, playback, chunking, workers, attempts, analysis, null);
    }

    /**
     * @param preparation wie das Markdown zum gesprochenen Text wird (dieselbe Deutung wie die Anzeige);
     *                    {@code null} = {@link SpeechText#plainText}
     */
    public ReadAloudService(SpeechSynthesisPort synthesis, String modelId, String voice, AudioPlayback playback,
                            SpeechChunking chunking, int workers, int attempts, TextAnalysisService analysis,
                            SpeechTextPreparation preparation) {
        this(synthesis, modelId, voice, playback, chunking, workers, attempts, analysis, preparation, false);
    }

    /**
     * @param voiceStabilityFix {@code true}: lange Absatz-/Antwort-Stücke nach etwa 500 Zeichen an Satzgrenzen
     *                          beenden, um beobachtete Voice-Drifts generativer TTS-Modelle zu umgehen
     */
    public ReadAloudService(SpeechSynthesisPort synthesis, String modelId, String voice, AudioPlayback playback,
                            SpeechChunking chunking, int workers, int attempts, TextAnalysisService analysis,
                            SpeechTextPreparation preparation, boolean voiceStabilityFix) {
        if (synthesis == null || modelId == null || modelId.trim().isEmpty() || playback == null) {
            throw new IllegalArgumentException("synthesis, modelId and playback must be set");
        }
        this.synthesis = synthesis;
        this.modelId = modelId;
        this.voice = voice == null || voice.trim().isEmpty() ? null : voice.trim();
        this.playback = playback;
        this.chunking = chunking == null ? SpeechChunking.PARAGRAPHS : chunking;
        this.workers = Math.max(1, Math.min(MAX_SYNTHESIS_WORKERS, workers));
        this.attempts = Math.max(1, Math.min(MAX_SYNTHESIS_ATTEMPTS, attempts));
        this.analysis = analysis;
        this.preparation = preparation;
        this.voiceStabilityFix = voiceStabilityFix;
    }

    public String modelId() {
        return modelId;
    }

    /** Die feste Stimme oder {@code null}. */
    public String voice() {
        return voice;
    }

    /**
     * Liest eine Markdown-Antwort vor; blockiert bis zum Ende oder bis {@link #stop()} (nie auf dem EDT rufen).
     *
     * @return {@code true}, wenn mindestens ein Stück erklungen ist (auch wenn danach gestoppt wurde)
     */
    public boolean speak(String markdown, Listener listener) {
        final long myGeneration;
        synchronized (lock) {
            myGeneration = ++generation;
            paused = false;
        }
        List<String> chunks = chunks(SpeechText.speakableCharacters(preparation == null
                ? SpeechText.plainText(markdown) : preparation.spokenText(markdown)));
        if (chunks.isEmpty()) {
            return false;
        }
        playback.begin();
        playback.setRate(rate());
        if (isPaused()) {
            playback.pause(); // paused while the text was being split
        }
        final AtomicInteger workerNumber = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                // Vorgang und Worker im Namen: die Diagnose des Adapters (gleicher Thread) lässt sich zuordnen.
                Thread thread = new Thread(runnable, "enterprise-ai-tts-worker-" + myGeneration + "-"
                        + workerNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        List<Future<byte[]>> prepared = new ArrayList<Future<byte[]>>();
        try {
            for (int i = 0; i < chunks.size(); i++) {
                prepared.add(submit(pool, myGeneration, i + 1, chunks.size(), chunks.get(i), listener));
            }
            boolean anySpoken = false;
            String reported = null;
            for (int i = 0; i < chunks.size(); i++) {
                if (!isCurrent(myGeneration)) {
                    return anySpoken;
                }
                Future<byte[]> future = prepared.get(i);
                byte[] wav;
                try {
                    wav = future.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return anySpoken;
                } catch (ExecutionException failed) {
                    // Stop here: skipping a missing chunk silently makes the answer jump ("third" → "fifth").
                    report(listener, reported, "Vorlesen abgebrochen: Abschnitt " + (i + 1) + " von "
                            + chunks.size() + " fehlt nach " + attempts + " Versuch" + (attempts == 1 ? "" : "en")
                            + " (" + message(failed.getCause()) + ")");
                    return anySpoken;
                }
                if (wav == null) {
                    return anySpoken; // superseded before the request
                }
                if (!isCurrent(myGeneration)) {
                    return anySpoken;
                }
                try {
                    playback.play(wav);
                    anySpoken = true;
                } catch (IOException failedPlayback) {
                    reported = report(listener, reported, "Wiedergabe fehlgeschlagen: " + message(failedPlayback));
                }
            }
            return anySpoken;
        } finally {
            pool.shutdownNow();
            playback.finish();
        }
    }

    /**
     * Die Stücke wie arch {@code PiperReadAloudService.segments}: die Textanalyse wird für jede Antwort angemeldet
     * (lädt bei Bedarf die Modelle) und danach wieder freigegeben; die Sprachabschnitte folgen in Lesereihenfolge.
     */
    private List<String> chunks(String plainText) {
        if (analysis == null) {
            return SpeechText.chunks(plainText, chunking, voiceStabilityFix);
        }
        Object token = new Object();
        analysis.acquire(token);
        try {
            List<String> chunks = new ArrayList<String>();
            for (TextAnalysisService.Segment segment : analysis.segment(plainText, FALLBACK_LANGUAGE, true,
                    chunking)) {
                for (String chunk : segment.chunks()) {
                    chunks.addAll(SpeechText.bounded(chunk, voiceStabilityFix));
                }
            }
            return chunks;
        } finally {
            analysis.release(token);
        }
    }

    private Future<byte[]> submit(ExecutorService pool, final long myGeneration, final int index, final int count,
                                  final String chunk, final Listener listener) {
        return pool.submit(new Callable<byte[]>() {
            @Override
            public byte[] call() throws SpeechSynthesisException {
                if (!isCurrent(myGeneration)) {
                    return null; // abgelöst: kein Request mehr für einen beendeten Vorgang
                }
                ChunkTrace trace = new ChunkTrace(myGeneration, index, count, modelId, voice,
                        Thread.currentThread().getName(), chunk.length());
                if (listener != null) {
                    listener.chunkRequested(trace);
                }
                byte[] wav = null;
                for (int attempt = 1; wav == null; attempt++) {
                    try {
                        wav = synthesis.synthesize(modelId, voice, chunk).wav();
                    } catch (SpeechSynthesisException | RuntimeException failed) {
                        if (attempt >= attempts || !isCurrent(myGeneration)) {
                            throw failed;
                        }
                    }
                }
                if (listener != null) {
                    listener.chunkSynthesized(trace, WavFormat.of(wav));
                }
                return wav;
            }
        });
    }

    /** Beendet das laufende Vorlesen sofort (die laufende Wiedergabe bricht ab, weitere Stücke entfallen). */
    public void stop() {
        synchronized (lock) {
            generation++;
            paused = false;
        }
        playback.stop();
    }

    /**
     * Holds the running read-aloud at the current position: playback stops mid-chunk, synthesis of the following
     * chunks goes on, and {@link #resume()} continues exactly there. {@link #stop()} or a new {@link #speak} discards
     * the paused position.
     */
    public void pause() {
        synchronized (lock) {
            paused = true;
        }
        playback.pause();
    }

    /** Continues a paused read-aloud where it stopped; no effect when not paused. */
    public void resume() {
        synchronized (lock) {
            if (!paused) {
                return;
            }
            paused = false;
        }
        playback.resume();
    }

    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }

    /** Speaking rate ({@link SpeechRate}); applies to the running read-aloud from the next output block. */
    public void setRate(double rate) {
        double clamped = SpeechRate.clamp(rate);
        synchronized (lock) {
            this.rate = clamped;
        }
        playback.setRate(clamped);
    }

    public double rate() {
        synchronized (lock) {
            return rate;
        }
    }

    private boolean isCurrent(long myGeneration) {
        synchronized (lock) {
            return generation == myGeneration;
        }
    }

    /** Meldet jede Fehlermeldung eines Vorgangs nur einmal hintereinander. */
    private static String report(Listener listener, String previous, String message) {
        if (listener != null && !message.equals(previous)) {
            listener.failed(message);
        }
        return message;
    }

    private static String message(Throwable failure) {
        if (failure == null) {
            return "unbekannter Fehler";
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
