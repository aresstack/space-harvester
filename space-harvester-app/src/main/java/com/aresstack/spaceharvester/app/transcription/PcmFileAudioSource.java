package com.aresstack.spaceharvester.app.transcription;

import com.aresstack.windirectml.inference.whisper.WhisperAudioSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Read bounded windows of a 16-kHz mono 16-bit little-endian PCM file without loading it.
 * Abort every read with a {@link CancellationException} once the transcription is cancelled.
 */
final class PcmFileAudioSource implements WhisperAudioSource, AutoCloseable {

    private final FileChannel channel;
    private final long sampleCount;
    private final BooleanSupplier cancelled;

    PcmFileAudioSource(Path pcmFile, BooleanSupplier cancelled) throws IOException {
        this.channel = FileChannel.open(pcmFile, StandardOpenOption.READ);
        this.sampleCount = channel.size() / 2;
        this.cancelled = cancelled;
    }

    @Override
    public long sampleCount() {
        return sampleCount;
    }

    @Override
    public float[] readSamples(long startSample, int count) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Transcription cancelled");
        }
        if (startSample < 0 || count < 0 || startSample > sampleCount - count) {
            throw new IllegalArgumentException("PCM window exceeds the recording");
        }
        ByteBuffer buffer = ByteBuffer.allocate(count * 2);
        long position = startSample * 2;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new IOException("PCM file ended before the requested window");
            }
        }
        return WhisperSpeechTranscriber.decode16BitMono(buffer.array(), buffer.capacity());
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
