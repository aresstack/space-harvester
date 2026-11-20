package com.aresstack.spaceharvester.app.transcription;

import com.aresstack.windirectml.inference.whisper.WhisperAudioSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Collect a growing 16-kHz mono 16-bit little-endian PCM stream in a temporary file and read bounded windows of it,
 * so Whisper can decode completed windows while the decoder still delivers audio. Every read is aborted with a
 * {@link CancellationException} once the transcription is cancelled; the file is deleted on close.
 */
final class GrowingPcmAudioSource implements WhisperAudioSource, AutoCloseable {

    private final Path file;
    private final FileChannel channel;
    private final BooleanSupplier cancelled;
    private long bytes;

    GrowingPcmAudioSource(BooleanSupplier cancelled) throws IOException {
        this.file = Files.createTempFile("space-harvester-transcription-", ".pcm");
        this.channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.cancelled = cancelled;
    }

    /** Append PCM bytes; an odd trailing byte becomes a sample once its second byte arrives. */
    void append(byte[] pcm, int offset, int length) throws IOException {
        ByteBuffer data = ByteBuffer.wrap(pcm, offset, length);
        while (data.hasRemaining()) {
            bytes += channel.write(data, bytes);
        }
    }

    @Override
    public long sampleCount() {
        return bytes / 2;
    }

    @Override
    public float[] readSamples(long startSample, int count) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Transcription cancelled");
        }
        if (startSample < 0 || count < 0 || startSample > sampleCount() - count) {
            throw new IllegalArgumentException("PCM window exceeds the recorded audio");
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
        try {
            channel.close();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
