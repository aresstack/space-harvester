package com.aresstack.spaceharvester.app.transcription;

import com.aresstack.windirectml.inference.whisper.WhisperTranscriber;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Apply the published Whisper engine to bounded, thirty-second windows of a (possibly still growing) PCM stream. */
public final class WhisperSpeechTranscriber implements SpeechTranscriber {

    private static final int SAMPLE_RATE = 16_000;
    private static final int SAMPLES_PER_WINDOW = SAMPLE_RATE * 30;
    private static final int MAXIMUM_TOKENS = 256;

    @Override
    public void transcribe(InputStream pcmStream, Path modelDirectory, BooleanSupplier cancelled,
                           Consumer<TranscriptSegment> onSegment) throws Exception {
        requireModel(modelDirectory);
        WhisperTranscriber whisper = new WhisperTranscriber(modelDirectory);
        byte[] pcm = new byte[SAMPLES_PER_WINDOW * 2];
        long sampleOffset = 0;
        // Each window waits only for its own thirty seconds of audio, then its text is reported at once.
        try (InputStream source = new BufferedInputStream(pcmStream)) {
            int bytesRead;
            while (!cancelled.getAsBoolean() && (bytesRead = fill(source, pcm)) > 0) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                float[] samples = decode16BitMono(pcm, bytesRead);
                String recognized = whisper.transcribe(samples, MAXIMUM_TOKENS);
                if (recognized != null && !recognized.trim().isEmpty()) {
                    onSegment.accept(new TranscriptSegment(
                            sampleOffset * 1000 / SAMPLE_RATE, recognized.trim()));
                }
                sampleOffset += samples.length;
            }
        }
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

    private static int fill(InputStream input, byte[] target) throws IOException {
        int total = 0;
        while (total < target.length) {
            int received = input.read(target, total, target.length - total);
            if (received < 0) {
                break;
            }
            if (received == 0) {
                continue;
            }
            total += received;
        }
        return total;
    }

    private static void requireModel(Path modelDirectory) throws IOException {
        if (!isModelAvailable(modelDirectory)) {
            throw new IOException("Whisper-Tiny-Modell fehlt: config.json, model.safetensors, "
                    + "tokenizer.json oder vocab.json");
        }
    }
}
