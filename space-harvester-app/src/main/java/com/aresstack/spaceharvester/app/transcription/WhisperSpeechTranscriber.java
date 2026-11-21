package com.aresstack.spaceharvester.app.transcription;

import com.aresstack.windirectml.inference.whisper.TranscriptionResult;
import com.aresstack.windirectml.inference.whisper.TranscriptionSegment;
import com.aresstack.windirectml.inference.whisper.WhisperIncrementalTranscription;
import com.aresstack.windirectml.inference.whisper.WhisperTranscriber;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Transcribe a (possibly still growing) 16-kHz mono PCM stream with the published Ask AI speech profile and the
 * guarded Whisper decoder: every completed thirty-second window is decoded and reported while the decoder still
 * delivers audio, and windows are joined at segment boundaries instead of fixed cuts.
 */
public final class WhisperSpeechTranscriber implements SpeechTranscriber {

    /** Read the stream in five-second steps; a window is decoded as soon as its thirty seconds are complete. */
    private static final int READ_BYTES = 16_000 * 2 * 5;

    @Override
    public void transcribe(InputStream pcmStream, Path modelDirectory, BooleanSupplier cancelled,
                           Consumer<TranscriptSegment> onSegment) throws Exception {
        requireModel(modelDirectory);
        WhisperTranscriber whisper = new WhisperTranscriber(modelDirectory);
        long[] lastPosition = {0};
        try (GrowingPcmAudioSource audio = new GrowingPcmAudioSource(cancelled);
             InputStream source = new BufferedInputStream(pcmStream)) {
            WhisperIncrementalTranscription live = WhisperPipelines.create().transcribeIncrementally(
                    audio, whisper, window -> publish(window, lastPosition, onSegment));
            byte[] buffer = new byte[READ_BYTES];
            int read;
            while (!cancelled.getAsBoolean() && (read = source.read(buffer)) > 0) {
                audio.append(buffer, 0, read);
                live.decodeAvailable();
            }
            if (!cancelled.getAsBoolean()) {
                live.finish();
            }
        } catch (CancellationException | InterruptedIOException stopped) {
            // Cancelled by the user; segments recognized so far have already been published.
        }
    }

    /**
     * Publish each committed window at the start of its first model-confirmed segment;
     * a window without timestamps follows the end of the previous one.
     */
    private static void publish(TranscriptionResult window, long[] lastPosition,
                                Consumer<TranscriptSegment> onSegment) {
        String text = window.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        List<TranscriptionSegment> segments = window.getSegments();
        long start = segments.isEmpty() ? lastPosition[0] : segments.get(0).getStartMillis();
        if (!segments.isEmpty()) {
            lastPosition[0] = Math.max(start, segments.get(segments.size() - 1).getEndMillis());
        }
        // The end of the window is where the next one starts, and where a stopped transcription resumes.
        onSegment.accept(new TranscriptSegment(start, Math.max(start, lastPosition[0]), text));
    }

    public static boolean isModelAvailable(Path modelDirectory) {
        return modelDirectory != null
                && Files.isRegularFile(modelDirectory.resolve("config.json"))
                && Files.isRegularFile(modelDirectory.resolve("model.safetensors"))
                && Files.isRegularFile(modelDirectory.resolve("tokenizer.json"))
                && Files.isRegularFile(modelDirectory.resolve("vocab.json"));
    }

    static float[] decode16BitMono(byte[] pcm, int length) throws IOException {
        if (length < 0 || length > pcm.length || (length & 1) != 0) {
            throw new IOException("Incomplete 16-bit PCM sample");
        }
        float[] audio = new float[length / 2];
        for (int i = 0; i < audio.length; i++) {
            int low = pcm[i * 2] & 0xff;
            int high = pcm[i * 2 + 1];
            short sample = (short) ((high << 8) | low);
            audio[i] = sample / 32768.0f;
        }
        return audio;
    }

    private static void requireModel(Path modelDirectory) throws IOException {
        if (!isModelAvailable(modelDirectory)) {
            throw new IOException("Whisper-Modell unvollständig: config.json, model.safetensors, "
                    + "tokenizer.json oder vocab.json");
        }
    }
}
