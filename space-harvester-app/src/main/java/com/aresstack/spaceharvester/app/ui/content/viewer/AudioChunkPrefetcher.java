package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetch the indexed audio chunks of a remote MP4 ahead of the reader, several requests at a time. Every audio
 * chunk sits between video data; one range request after another made the transcription wait for each round trip
 * (Angelo, 19.11.2026: "viel zu langsam").
 *
 * <p>Neighbouring chunks are fetched in one request when only a little video lies between them
 * ({@link #MAXIMUM_GAP}): files with many tiny audio chunks then need a fraction of the requests, while files with
 * large video gaps still transfer only their audio. Chunks larger than one remote read are read directly.</p>
 */
final class AudioChunkPrefetcher {

    /** Parallel range requests for all transcriptions together; the source sees at most this many at once. */
    static final int PARALLEL_REQUESTS = 4;
    /** Requests fetched ahead of the one being read. */
    static final int AHEAD_GROUPS = 24;
    /** Upper bound of data held ahead of the reader. */
    static final long AHEAD_BYTES = 6L * 1024 * 1024;
    /** Video bytes between two audio chunks that are still fetched along to save a request. */
    static final int MAXIMUM_GAP = 32 * 1024;

    private static final ExecutorService FETCHERS = Executors.newFixedThreadPool(PARALLEL_REQUESTS, task -> {
        Thread thread = new Thread(task, "space-harvester-audio-prefetch");
        thread.setDaemon(true);
        return thread;
    });

    private final MediaStreamBridge.ChunkSource remote;
    private final long[] offsets;
    private final int[] lengths;
    private final int maximumRequest;
    /** The request each chunk belongs to; -1 for a chunk larger than one request. */
    private final int[] groupOf;
    private final long[] groupStart;
    private final int[] groupLength;
    private final Map<Integer, CompletableFuture<byte[]>> fetched = new ConcurrentHashMap<>();

    AudioChunkPrefetcher(MediaStreamBridge.ChunkSource remote, long[] offsets, int[] lengths, int maximumRequest) {
        this.remote = remote;
        this.offsets = offsets;
        this.lengths = lengths;
        this.maximumRequest = maximumRequest;
        this.groupOf = new int[lengths.length];
        long[] starts = new long[lengths.length];
        int[] spans = new int[lengths.length];
        int groups = 0;
        for (int chunk = 0; chunk < lengths.length; chunk++) {
            if (lengths[chunk] > maximumRequest) {
                groupOf[chunk] = -1;
                continue;
            }
            if (groups > 0 && chunk > 0 && groupOf[chunk - 1] == groups - 1) {
                long previousEnd = starts[groups - 1] + spans[groups - 1];
                long gap = offsets[chunk] - previousEnd;
                long span = offsets[chunk] + lengths[chunk] - starts[groups - 1];
                if (gap >= 0 && gap <= MAXIMUM_GAP && span <= maximumRequest) {
                    spans[groups - 1] = (int) span;
                    groupOf[chunk] = groups - 1;
                    continue;
                }
            }
            starts[groups] = offsets[chunk];
            spans[groups] = lengths[chunk];
            groupOf[chunk] = groups++;
        }
        this.groupStart = java.util.Arrays.copyOf(starts, groups);
        this.groupLength = java.util.Arrays.copyOf(spans, groups);
    }

    /** {@code count} bytes of chunk {@code chunk}, starting {@code inside} bytes into it. */
    byte[] read(int chunk, long inside, int count) throws IOException {
        int group = groupOf[chunk];
        if (group < 0) {
            return Mp4AudioOnlySource.fetch(remote, offsets[chunk] + inside, count);
        }
        CompletableFuture<byte[]> whole = request(group);
        prefetchAfter(group);
        byte[] data;
        try {
            data = whole.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Audio chunk fetch interrupted");
        } catch (ExecutionException failed) {
            // A failed request is sent again on the next read instead of failing forever.
            fetched.remove(group, whole);
            Throwable cause = failed.getCause() instanceof CompletionException wrapped && wrapped.getCause() != null
                    ? wrapped.getCause() : failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Audio chunk unavailable", cause);
        }
        byte[] slice = new byte[count];
        long start = offsets[chunk] - groupStart[group] + inside;
        System.arraycopy(data, (int) start, slice, 0, count);
        return slice;
    }

    /** Remote requests needed for all audio (for tests). */
    int requests() {
        return groupStart.length;
    }

    /** Requests currently held or in flight (for tests). */
    int held() {
        return fetched.size();
    }

    private CompletableFuture<byte[]> request(int group) {
        return fetched.computeIfAbsent(group, index -> CompletableFuture.supplyAsync(() -> {
            try {
                return Mp4AudioOnlySource.fetch(remote, groupStart[index], groupLength[index]);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, FETCHERS));
    }

    private void prefetchAfter(int group) {
        // Keep the current and the previous request (a short step back); forget the rest of a seek's leftovers.
        fetched.keySet().removeIf(index -> index < group - 1 || index > group + AHEAD_GROUPS);
        long ahead = 0;
        for (int next = group + 1; next < groupStart.length && next <= group + AHEAD_GROUPS; next++) {
            ahead += groupLength[next];
            if (ahead > AHEAD_BYTES) {
                break;
            }
            request(next);
        }
    }
}
