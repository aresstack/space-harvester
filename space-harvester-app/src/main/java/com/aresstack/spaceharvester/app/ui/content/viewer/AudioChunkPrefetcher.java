package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.settings.VideoSettings;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Fetch the indexed audio chunks of a remote MP4 ahead of the reader, several requests at a time. Every audio
 * chunk sits between video data; one range request after another made the transcription wait for each round trip
 * (Angelo, 19.11.2026: "viel zu langsam").
 *
 * <p>Neighbouring chunks are fetched in one request when only a little video lies between them
 * ({@link VideoSettings.Value#AUDIO_MAX_GAP}): files with many tiny audio chunks then need a fraction of the requests, while files with
 * large video gaps still transfer only their audio. Chunks larger than one remote read are read directly.</p>
 */
final class AudioChunkPrefetcher {

    /**
     * Parallel range requests for all transcriptions together; the source sees at most so many at once
     * ({@link VideoSettings.Value#AUDIO_PARALLEL}, applied whenever a prefetcher is made).
     */
    private static final java.util.concurrent.ThreadPoolExecutor FETCHERS = pool(
            (int) VideoSettings.Value.AUDIO_PARALLEL.defaultValue(), "space-harvester-audio-prefetch");

    /** Requests fetched ahead of the one being read. */
    private final int aheadGroups;
    /** Upper bound of data held ahead of the reader. */
    private final long aheadBytes;

    private final MediaStreamBridge.ChunkSource remote;
    private final long[] offsets;
    private final int[] lengths;
    private final int maximumRequest;
    /** The request each chunk belongs to; -1 for a chunk larger than one request. */
    private final int[] groupOf;
    private final long[] groupStart;
    private final int[] groupLength;
    private final Map<Integer, CompletableFuture<byte[]>> fetched = new ConcurrentHashMap<>();
    /**
     * Die Requests, die gerade gelesen werden, mit der Zahl ihrer Leser: Die Brücke liest mit mehreren Abrufen an
     * verschiedenen Stellen zugleich; keiner darf wegräumen, was ein anderer noch braucht.
     */
    private final java.util.concurrent.ConcurrentSkipListMap<Integer, Integer> reading =
            new java.util.concurrent.ConcurrentSkipListMap<>();

    AudioChunkPrefetcher(MediaStreamBridge.ChunkSource remote, long[] offsets, int[] lengths, int maximumRequest) {
        this(remote, offsets, lengths, maximumRequest, VideoSettings.DEFAULT);
    }

    /** @param settings parallel requests, lookahead and the video gap fetched along */
    AudioChunkPrefetcher(MediaStreamBridge.ChunkSource remote, long[] offsets, int[] lengths, int maximumRequest,
                         VideoSettings settings) {
        resize(FETCHERS, settings.number(VideoSettings.Value.AUDIO_PARALLEL));
        this.aheadGroups = settings.number(VideoSettings.Value.AUDIO_AHEAD_REQUESTS);
        this.aheadBytes = settings.get(VideoSettings.Value.AUDIO_AHEAD_BYTES);
        long maximumGap = settings.get(VideoSettings.Value.AUDIO_MAX_GAP);
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
                if (gap >= 0 && gap <= maximumGap && span <= maximumRequest) {
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
        reading.merge(group, 1, Integer::sum);
        byte[] data;
        try {
            CompletableFuture<byte[]> whole = request(group);
            prefetchAfter(group);
            data = read(group, whole);
        } finally {
            reading.computeIfPresent(group, (key, readers) -> readers > 1 ? readers - 1 : null);
        }
        byte[] slice = new byte[count];
        long start = offsets[chunk] - groupStart[group] + inside;
        System.arraycopy(data, (int) start, slice, 0, count);
        return slice;
    }

    private byte[] read(int group, CompletableFuture<byte[]> whole) throws IOException {
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
        return data;
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
        // Keep everything from just before the earliest reader to the lookahead of the latest one (a short step back
        // stays); forget the rest of a seek's leftovers. Evicting by this reader alone threw away what the others
        // were about to read, and over a slow source every request was fetched again and again.
        int low = Math.min(group, reading.isEmpty() ? group : reading.firstKey());
        int high = Math.max(group, reading.isEmpty() ? group : reading.lastKey());
        fetched.keySet().removeIf(index -> index < low - 1 || index > high + aheadGroups);
        long ahead = 0;
        for (int next = group + 1; next < groupStart.length && next <= group + aheadGroups; next++) {
            ahead += groupLength[next];
            if (ahead > aheadBytes) {
                break;
            }
            request(next);
        }
    }

    /** A pool of daemon threads whose size {@link #resize} can change. */
    static java.util.concurrent.ThreadPoolExecutor pool(int threads, String name) {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(threads, threads,
                0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                });
        return pool;
    }

    /** Sets the number of threads of {@code pool}; a changed setting applies to the next tasks. */
    static synchronized void resize(java.util.concurrent.ThreadPoolExecutor pool, int threads) {
        if (threads > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(threads);
            pool.setCorePoolSize(threads);
        } else if (threads < pool.getMaximumPoolSize()) {
            pool.setCorePoolSize(threads);
            pool.setMaximumPoolSize(threads);
        }
    }
}
