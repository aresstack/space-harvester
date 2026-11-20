package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Save a selected remote video only when explicitly requested.
 * Download bounded chunks to a temporary file, then publish the complete result atomically if possible.
 */
final class VideoFileDownloader {

    private VideoFileDownloader() {
    }

    static void download(MediaStreamBridge.ChunkSource reader, long size, Path destination,
                         BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(progress, "progress");
        if (size <= 0 || destination.getFileName() == null) {
            throw new IllegalArgumentException("A known file size and a target filename are required");
        }

        Path file = destination.toAbsolutePath().normalize();
        Path folder = file.getParent();
        if (folder == null) {
            throw new IOException("No destination directory available");
        }
        Path temporary = Files.createTempFile(folder, ".space-harvester-video-", ".part");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                long offset = 0;
                while (offset < size) {
                    checkCancelled(cancelled);
                    int requested = (int) Math.min(MediaStreamBridge.CHUNK_BYTES, size - offset);
                    byte[] bytes;
                    try {
                        bytes = reader.read(offset, requested);
                    } catch (Exception error) {
                        if (error instanceof IOException) {
                            throw (IOException) error;
                        }
                        throw new IOException("Cannot read video from source", error);
                    }
                    if (bytes == null || bytes.length != requested) {
                        throw new IOException("Incomplete media range at byte " + offset);
                    }
                    output.write(bytes);
                    offset += bytes.length;
                    progress.accept(offset);
                }
            }
            checkCancelled(cancelled);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Video download cancelled");
        }
    }
}
