package com.aresstack.spaceharvester.app.speech;

import com.aresstack.windirectml.inference.whisper.WhisperAudioSource;

import java.io.IOException;
import java.util.Objects;

/**
 * Own a sealed disk-backed microphone recording for transcription and retry.
 * Expose bounded 16-kHz mono windows without materializing the full recording.
 */
public final class RecordedSpeech implements WhisperAudioSource, AutoCloseable {
    public static final int SAMPLE_RATE = 16000;

    private final ChunkedPcmAudioSource audio;

    RecordedSpeech(ChunkedPcmAudioSource audio) {
        this.audio = Objects.requireNonNull(audio, "audio");
    }

    @Override
    public long sampleCount() {
        return audio.sampleCount();
    }

    @Override
    public float[] readSamples(long startSample, int sampleCount) throws IOException {
        return audio.readSamples(startSample, sampleCount);
    }

    public long durationMillis() {
        return sampleCount() * 1000L / SAMPLE_RATE;
    }

    @Override
    public void close() {
        audio.close();
    }
}
