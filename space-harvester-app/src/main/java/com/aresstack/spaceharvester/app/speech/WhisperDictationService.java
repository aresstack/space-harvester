package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.transcription.WhisperModelLocation;
import com.aresstack.spaceharvester.app.transcription.WhisperPipelines;
import com.aresstack.windirectml.inference.whisper.TranscriptionResult;
import com.aresstack.windirectml.inference.whisper.TranscriptionSegment;
import com.aresstack.windirectml.inference.whisper.WhisperAudioSource;
import com.aresstack.windirectml.inference.whisper.WhisperIncrementalTranscription;
import com.aresstack.windirectml.inference.whisper.WhisperTranscriber;
import com.aresstack.windirectml.whisper.audio.AskAiWhisperPipeline;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Transcribe dictation with the published Ask AI pipeline: 80-Hz high-pass and VAD, then the
 * guarded Whisper decoder with 30-second windows, using the bundled local checkpoint.
 *
 * <p>Every committed window is logged with its time range, so gaps in a transcript are visible in
 * the log. With {@code -Dspace-harvester.dictation.keep-recording=true} the last recording is also
 * written to {@code ~/.space-harvester/logs/last-dictation.wav} (16 kHz mono, overwritten each time).
 */
final class WhisperDictationService {
    static final String KEEP_RECORDING = "space-harvester.dictation.keep-recording";
    private static final Logger LOG = Logger.getLogger(WhisperDictationService.class.getName());
    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW_SAMPLES = SAMPLE_RATE * 30;

    private AskAiWhisperPipeline audioPipeline;
    private WhisperTranscriber transcriber;
    private java.nio.file.Path loadedModel;

    /**
     * Start transcribing the growing recording; {@code decodeAvailable()} decodes completed windows
     * while recording continues and {@link #finishLive} the rest after stop.
     */
    synchronized WhisperIncrementalTranscription startLive(WhisperAudioSource growing,
                                                           java.util.function.Consumer<String> onWindow)
            throws IOException {
        load();
        LOG.info("Spracheingabe: Erkennung läuft während der Aufnahme");
        return audioPipeline.transcribeIncrementally(growing, transcriber, window -> {
            logWindow(window);
            if (!window.getText().isBlank()) {
                onWindow.accept(window.getText());
            }
        });
    }

    /** Decode the rest of a live transcription after the recording has stopped. */
    String finishLive(WhisperIncrementalTranscription live, RecordedSpeech speech) throws IOException {
        if (Boolean.getBoolean(KEEP_RECORDING)) {
            keep(speech);
        }
        LOG.info("Spracheingabe: " + speech.durationMillis() + " ms Aufnahme, Rest wird erkannt");
        return live.finish().getText();
    }

    /**
     * Erkennt den Ausschnitt {@code [start, end)} der Aufnahme mit derselben Pipeline (Hochpass, VAD, geschützter
     * Decoder) und liefert die Segmente mit Positionen in Abtastwerten der Aufnahme; für {@link LiveTranscript}.
     */
    synchronized List<LiveTranscript.Segment> decode(WhisperAudioSource recording, long start, long end)
            throws IOException {
        load();
        TranscriptionResult result = audioPipeline.transcribe(AudioWindow.capture(recording, start, end), transcriber,
                ignored -> { });
        List<LiveTranscript.Segment> segments = new java.util.ArrayList<>();
        for (TranscriptionSegment segment : result.getSegments()) {
            segments.add(new LiveTranscript.Segment(start + segment.getStartMillis() * SAMPLE_RATE / 1000,
                    start + segment.getEndMillis() * SAMPLE_RATE / 1000, segment.getText()));
        }
        if (segments.isEmpty() && !result.getText().isBlank()) {
            segments.add(new LiveTranscript.Segment(start, end, result.getText()));
        }
        return segments;
    }

    private void load() throws IOException {
        // Die Spracheingabe hat ihr eigenes Modell (Einstellungen › Modelle, Vorgabe Tiny); eine andere Wahl gilt ab
        // der nächsten Aufnahme.
        java.nio.file.Path model = WhisperModelLocation.resolve(WhisperModelLocation.dictation());
        if (transcriber == null || !model.equals(loadedModel)) {
            transcriber = new WhisperTranscriber(model);
            loadedModel = model;
        }
        if (audioPipeline == null) {
            audioPipeline = WhisperPipelines.create();
        }
    }

    synchronized String transcribe(RecordedSpeech speech) throws IOException {
        return transcribe(speech, ignored -> { });
    }

    /** Wie {@link #transcribe(RecordedSpeech, java.util.function.Consumer)}, aber erst ab Abtastwert {@code from}. */
    synchronized String transcribe(RecordedSpeech speech, long from, java.util.function.Consumer<String> onWindow)
            throws IOException {
        if (from <= 0) {
            return transcribe(speech, onWindow);
        }
        load();
        LOG.info("Spracheingabe: Rest ab " + seconds(from * 1000L / SAMPLE_RATE) + " wird vollständig erkannt");
        return audioPipeline.transcribe(AudioWindow.capture(speech, from, speech.sampleCount()), transcriber, window -> {
            logWindow(window);
            if (!window.getText().isBlank()) {
                onWindow.accept(window.getText());
            }
        }).getText();
    }

    /** Erkennt die Aufnahme und meldet den Text jedes fertigen Fensters, sobald es erkannt ist. */
    synchronized String transcribe(RecordedSpeech speech, java.util.function.Consumer<String> onWindow)
            throws IOException {
        load();
        if (Boolean.getBoolean(KEEP_RECORDING)) {
            keep(speech);
        }
        LOG.info("Spracheingabe: " + speech.durationMillis() + " ms Aufnahme, Erkennung beginnt");
        TranscriptionResult result = audioPipeline.transcribe(speech, transcriber, window -> {
            logWindow(window);
            if (!window.getText().isBlank()) {
                onWindow.accept(window.getText());
            }
        });
        return result.getText();
    }

    private void logWindow(TranscriptionResult window) {
        List<TranscriptionSegment> segments = window.getSegments();
        String range = segments.isEmpty() ? "ohne Zeitstempel"
                : seconds(segments.get(0).getStartMillis()) + "–"
                + seconds(segments.get(segments.size() - 1).getEndMillis());
        LOG.info("Spracheingabe: Fenster " + range + ", " + segments.size() + " Segmente, "
                + window.getText().length() + " Zeichen");
    }

    private static String seconds(long millis) {
        return String.format(java.util.Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    /** Write the recording window by window as a 16-bit WAV file, never holding it in memory. */
    private static void keep(RecordedSpeech speech) {
        Path file = Path.of(System.getProperty("user.home"), ".space-harvester", "logs", "last-dictation.wav");
        try {
            Files.createDirectories(file.getParent());
            long samples = speech.sampleCount();
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
                out.write(header(samples));
                for (long start = 0; start < samples; start += WINDOW_SAMPLES) {
                    int count = (int) Math.min(WINDOW_SAMPLES, samples - start);
                    float[] window = speech.readSamples(start, count);
                    ByteBuffer pcm = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (float sample : window) {
                        pcm.putShort((short) Math.max(Short.MIN_VALUE,
                                Math.min(Short.MAX_VALUE, Math.round(sample * 32768f))));
                    }
                    out.write(pcm.array());
                }
            }
            LOG.info("Spracheingabe: Aufnahme gespeichert unter " + file);
        } catch (IOException | RuntimeException failed) {
            LOG.log(Level.WARNING, "Spracheingabe: Aufnahme konnte nicht gespeichert werden", failed);
        }
    }

    static byte[] header(long samples) {
        long dataBytes = samples * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (36 + dataBytes));
        header.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16);
        header.putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2);
        header.putShort((short) 2).putShort((short) 16);
        header.put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) dataBytes);
        return header.array();
    }
}
