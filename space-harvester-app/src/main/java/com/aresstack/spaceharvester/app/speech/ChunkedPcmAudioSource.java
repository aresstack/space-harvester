package com.aresstack.spaceharvester.app.speech;

import com.aresstack.windirectml.inference.whisper.WhisperAudioSource;
import com.aresstack.windirectml.inference.whisper.audio.WhisperAudioResampler;
import com.aresstack.windirectml.inference.whisper.audio.WhisperLogMelSpectrogram;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Keep the current and preceding two-minute PCM chunks in RAM.
 * Spill the older full chunk only when the newer chunk becomes full.
 * Read arbitrary Whisper windows across chunk boundaries without gathering all audio.
 * Appends and reads are synchronized, so {@link #liveView()} can be read while recording continues.
 */
final class ChunkedPcmAudioSource implements WhisperAudioSource, AutoCloseable {
    private static final int CHUNK_SECONDS = 120;
    private static final int BYTES_PER_FRAME = 2;
    private static final int TARGET_RATE = WhisperLogMelSpectrogram.SAMPLE_RATE;
    private static final int ALIGNMENT_SAMPLES = TARGET_RATE / 100;
    private static final int RESAMPLING_MARGIN_SAMPLES = 2 * ALIGNMENT_SAMPLES;
    /** While recording, withhold the newest samples whose resampling would still change with more audio. */
    private static final int LIVE_MARGIN_SAMPLES = ALIGNMENT_SAMPLES + RESAMPLING_MARGIN_SAMPLES;

    private final int sourceRate;
    private final int chunkBytes;
    private final List<Path> spilledChunks = new ArrayList<>();
    private byte[] previousChunk;
    private byte[] currentChunk;
    private int currentLength;
    private long totalBytes;
    private Path temporaryDirectory;
    private boolean sealed;
    private boolean closed;

    ChunkedPcmAudioSource(int sourceRate) {
        if (sourceRate <= 0 || sourceRate % 100 != 0) {
            throw new IllegalArgumentException("Require a positive sample rate divisible by 100");
        }
        this.sourceRate = sourceRate;
        this.chunkBytes = Math.multiplyExact(Math.multiplyExact(sourceRate, CHUNK_SECONDS),
                BYTES_PER_FRAME);
        currentChunk = new byte[chunkBytes];
    }

    synchronized void append(byte[] pcm, int offset, int length) throws IOException {
        if (sealed || closed) {
            throw new IllegalStateException("The recording has already been stopped");
        }
        if (length < 0 || (length & 1) != 0 || offset < 0
                || offset > pcm.length - length) {
            throw new IllegalArgumentException("Require complete PCM16 frames");
        }
        int remaining = length;
        while (remaining > 0) {
            int copied = Math.min(remaining, chunkBytes - currentLength);
            System.arraycopy(pcm, offset, currentChunk, currentLength, copied);
            currentLength += copied;
            totalBytes += copied;
            remaining -= copied;
            offset += copied;
            if (currentLength == chunkBytes) {
                completeChunk();
            }
        }
    }

    synchronized void finish() {
        sealed = true;
    }

    synchronized boolean isEmpty() {
        return totalBytes == 0;
    }

    synchronized int spilledChunkCount() {
        return spilledChunks.size();
    }

    synchronized long residentAudioBytes() {
        return (previousChunk == null ? 0L : chunkBytes) + currentLength;
    }

    @Override
    public synchronized long sampleCount() {
        long sourceFrames = totalBytes / BYTES_PER_FRAME;
        return Math.round((double) sourceFrames * TARGET_RATE / sourceRate);
    }

    @Override
    public synchronized float[] readSamples(long startSample, int requestedCount) throws IOException {
        if (!sealed) {
            throw new IllegalStateException("Stop recording before reading its audio");
        }
        return read(startSample, requestedCount, sampleCount());
    }

    /**
     * A view for transcription while recording: it reports only samples that are final, that is
     * every sample once recording stopped, and while recording all but the newest few milliseconds
     * whose resampled values could still change. Reads return exactly what the stopped recording
     * returns for the same interval.
     */
    WhisperAudioSource liveView() {
        return new WhisperAudioSource() {
            @Override
            public long sampleCount() {
                synchronized (ChunkedPcmAudioSource.this) {
                    long recorded = ChunkedPcmAudioSource.this.sampleCount();
                    return sealed ? recorded : Math.max(0, recorded - LIVE_MARGIN_SAMPLES);
                }
            }

            @Override
            public float[] readSamples(long startSample, int sampleCount) throws IOException {
                synchronized (ChunkedPcmAudioSource.this) {
                    return read(startSample, sampleCount, sampleCount());
                }
            }
        };
    }

    private float[] read(long startSample, int requestedCount, long available) throws IOException {
        if (closed) {
            throw new IOException("The recording has been discarded");
        }
        if (startSample < 0 || requestedCount < 0
                || startSample > available - requestedCount) {
            throw new IllegalArgumentException("Requested audio interval is outside the recording");
        }
        if (requestedCount == 0) {
            return new float[0];
        }

        long alignedStart = Math.max(0,
                (startSample / ALIGNMENT_SAMPLES) * ALIGNMENT_SAMPLES - RESAMPLING_MARGIN_SAMPLES);
        long requestedEnd = startSample + requestedCount;
        long alignedEnd = Math.min(sampleCount(),
                ((requestedEnd + ALIGNMENT_SAMPLES - 1) / ALIGNMENT_SAMPLES)
                        * ALIGNMENT_SAMPLES + RESAMPLING_MARGIN_SAMPLES);

        long firstSourceFrame = alignedStart * sourceRate / TARGET_RATE;
        long finalSourceFrame = Math.min(totalBytes / BYTES_PER_FRAME,
                (alignedEnd * sourceRate + TARGET_RATE - 1) / TARGET_RATE);
        int sourceFrames = Math.toIntExact(finalSourceFrame - firstSourceFrame);
        float[] source = readPcmFrames(firstSourceFrame, sourceFrames);
        float[] converted = WhisperAudioResampler.resample(source, sourceRate, TARGET_RATE);
        int skip = Math.toIntExact(startSample - alignedStart);
        if (converted.length - skip < requestedCount) {
            throw new IOException("The recording ended before the requested audio window");
        }
        float[] result = new float[requestedCount];
        System.arraycopy(converted, skip, result, 0, requestedCount);
        return result;
    }

    private void completeChunk() throws IOException {
        if (previousChunk != null) {
            spill(previousChunk);
        }
        previousChunk = currentChunk;
        currentChunk = new byte[chunkBytes];
        currentLength = 0;
    }

    private void spill(byte[] chunk) throws IOException {
        if (temporaryDirectory == null) {
            temporaryDirectory = Files.createTempDirectory("whisper-pcm-");
        }
        Path file = temporaryDirectory.resolve(String.format(java.util.Locale.ROOT,
                "audio-%06d.pcm", spilledChunks.size() + 1));
        try {
            Files.write(file, chunk);
            spilledChunks.add(file);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private float[] readPcmFrames(long firstFrame, int count) throws IOException {
        byte[] raw = new byte[Math.multiplyExact(count, BYTES_PER_FRAME)];
        long bytePosition = firstFrame * BYTES_PER_FRAME;
        int filled = 0;
        while (filled < raw.length) {
            long chunkIndex = bytePosition / chunkBytes;
            int withinChunk = (int) (bytePosition % chunkBytes);
            int available = chunkIndex < spilledChunks.size()
                    ? chunkBytes
                    : chunkIndex == spilledChunks.size() && previousChunk != null
                    ? chunkBytes : currentLength;
            int length = Math.min(raw.length - filled, available - withinChunk);
            if (length <= 0) {
                throw new IOException("Missing recorded PCM samples");
            }
            readChunk(chunkIndex, withinChunk, raw, filled, length);
            bytePosition += length;
            filled += length;
        }

        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            int index = i * BYTES_PER_FRAME;
            short value = (short) ((raw[index] & 0xFF) | (raw[index + 1] << 8));
            result[i] = value / 32768.0f;
        }
        return result;
    }

    private void readChunk(long chunkIndex, int offset, byte[] target, int targetOffset, int length)
            throws IOException {
        if (chunkIndex < spilledChunks.size()) {
            try (RandomAccessFile file = new RandomAccessFile(
                    spilledChunks.get((int) chunkIndex).toFile(), "r")) {
                file.seek(offset);
                file.readFully(target, targetOffset, length);
            }
            return;
        }
        byte[] inMemory = previousChunk != null && chunkIndex == spilledChunks.size()
                ? previousChunk : currentChunk;
        System.arraycopy(inMemory, offset, target, targetOffset, length);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        sealed = true;
        previousChunk = null;
        currentChunk = null;
        for (Path path : spilledChunks) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                path.toFile().deleteOnExit();
            }
        }
        spilledChunks.clear();
        if (temporaryDirectory != null) {
            try {
                Files.deleteIfExists(temporaryDirectory);
            } catch (IOException ignored) {
                temporaryDirectory.toFile().deleteOnExit();
            }
            temporaryDirectory = null;
        }
    }
}
