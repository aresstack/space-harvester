package com.aresstack.spaceharvester.app.transcription;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Buffer decoded PCM in a temporary file while one reader consumes it, so speech recognition starts with the first
 * window instead of after the whole download. The reader blocks until more audio arrives or the writer finished.
 * Temporary PCM never outlives {@link #close()}.
 */
final class PcmSpool implements PcmSink, AutoCloseable {

    private final Path file;
    private final OutputStream output;
    private final RandomAccessFile input;
    private long written;
    private boolean finished;
    private boolean closed;

    PcmSpool() throws IOException {
        file = Files.createTempFile("space-harvester-whisper-", ".pcm");
        boolean ready = false;
        try {
            output = Files.newOutputStream(file);
            input = new RandomAccessFile(file.toFile(), "r");
            ready = true;
        } finally {
            if (!ready) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Override
    public synchronized void write(byte[] samples, int offset, int length) throws IOException {
        if (finished || closed) {
            throw new IOException("PCM spool no longer accepts audio");
        }
        output.write(samples, offset, length);
        output.flush();
        written += length;
        notifyAll();
    }

    /** No more audio follows; the reader drains what is there and then sees the end. */
    synchronized void finish() {
        finished = true;
        notifyAll();
    }

    /** Bytes of PCM written so far. */
    synchronized long written() {
        return written;
    }

    /** A reader that blocks for more audio and ends when the writer finished; cancellation interrupts the wait. */
    InputStream reader(BooleanSupplier cancelled) {
        return new InputStream() {
            private long position;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                long available = awaitData(position, cancelled);
                if (available <= 0) {
                    return -1;
                }
                int count = (int) Math.min(length, available);
                synchronized (PcmSpool.this) {
                    if (closed) {
                        throw new IOException("PCM spool closed");
                    }
                    input.seek(position);
                    input.readFully(target, offset, count);
                }
                position += count;
                return count;
            }
        };
    }

    private synchronized long awaitData(long position, BooleanSupplier cancelled) throws IOException {
        while (written <= position && !finished && !closed) {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("Transcription cancelled");
            }
            try {
                wait(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Transcription cancelled");
            }
        }
        if (closed) {
            throw new IOException("PCM spool closed");
        }
        return written - position;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        finished = true;
        notifyAll();
        try {
            output.close();
        } finally {
            try {
                input.close();
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }
}
