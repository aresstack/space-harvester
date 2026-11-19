package com.aresstack.spaceharvester.app.transcription;

import com.aresstack.windirectml.inference.whisper.TranscriptionResult;
import com.aresstack.windirectml.inference.whisper.TranscriptionSegment;
import com.aresstack.windirectml.inference.whisper.WhisperTranscriber;
import com.aresstack.windirectml.whisper.audio.AskAiWhisperPipeline;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Transcribe 16-kHz mono PCM with the published Ask AI pipeline: 80-Hz high-pass, VAD and the
 * guarded Whisper decoder, which joins windows of at most thirty seconds at segment boundaries.
 */
public final class WhisperSpeechTranscriber implements SpeechTranscriber {

    @Override
    public void transcribe(Path pcmFile, Path modelDirectory, BooleanSupplier cancelled,
                           Consumer<TranscriptSegment> onSegment) throws Exception {
        requireModel(modelDirectory);
        WhisperTranscriber whisper = new WhisperTranscriber(modelDirectory);
        long[] lastPosition = {0};
        try (PcmFileAudioSource source = new PcmFileAudioSource(pcmFile, cancelled)) {
            new AskAiWhisperPipeline().transcribe(source, whisper,
                    window -> publish(window, lastPosition, onSegment));
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
            lastPosition[0] = segments.get(segments.size() - 1).getEndMillis();
        }
        onSegment.accept(new TranscriptSegment(start, text));
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
            throw new IOException("Whisper-Tiny-Modell fehlt: config.json, model.safetensors, "
                    + "tokenizer.json oder vocab.json");
        }
    }
}
