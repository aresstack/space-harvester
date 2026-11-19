package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expose one authenticated remote media resource to a local player without exposing source credentials.
 * Bind exclusively to 127.0.0.1 and require an unpredictable per-viewer URL token.
 *
 * <p>Every source read costs a full round trip (about 0.7 s per 256 KiB from BSCW through the proxy). MP4 files
 * whose audio and video samples are not interleaved make VLC jump between two regions of the file every few
 * seconds; with one blocking read per jump the audio arrived too late and VLC dropped it while the picture went
 * on. The bridge therefore reads aligned chunks into a bounded cache and reads ahead behind every delivered chunk
 * with several parallel source reads, so a jump back into the other region is served from memory.</p>
 */
public final class MediaStreamBridge implements AutoCloseable {

    public interface ChunkSource {
        byte[] read(long offset, int maximumBytes) throws Exception;
    }

    static final int CHUNK_BYTES = 256 * 1024;
    /** Chunks read ahead behind every delivered chunk: 2 MiB, about 15 s of a 1-Mbit/s video. */
    static final int READ_AHEAD_CHUNKS = 8;
    /** Parallel source reads; each BSCW range request is latency-bound, not bandwidth-bound. */
    private static final int FETCH_THREADS = 4;
    /** Cached chunks (32 MiB), enough for the read-ahead of both regions of a non-interleaved MP4. */
    private static final int CACHED_CHUNKS = 128;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private final ChunkSource source;
    private final long length;
    private final String mediaType;
    private final HttpServer server;
    private final ExecutorService workers;
    private final ExecutorService fetchers;
    private final Map<Long, CompletableFuture<byte[]>> chunks =
            new LinkedHashMap<Long, CompletableFuture<byte[]>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<byte[]>> eldest) {
                    return size() > CACHED_CHUNKS;
                }
            };
    private final URI address;
    private volatile String lastFailure;

    MediaStreamBridge(ChunkSource source, long length, String mediaType) throws IOException {
        if (source == null || length <= 0) {
            throw new IllegalArgumentException("A media source and a positive length are required");
        }
        this.source = source;
        this.length = length;
        this.mediaType = mediaType == null || !mediaType.startsWith("video/") ? "application/octet-stream" : mediaType;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        this.workers = Executors.newFixedThreadPool(3, task -> {
            Thread worker = new Thread(task, "space-harvester-media");
            worker.setDaemon(true);
            return worker;
        });
        this.fetchers = Executors.newFixedThreadPool(FETCH_THREADS, task -> {
            Thread fetcher = new Thread(task, "space-harvester-media-fetch");
            fetcher.setDaemon(true);
            return fetcher;
        });
        server.setExecutor(workers);
        server.createContext("/stream/" + token, this::handle);
        this.address = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stream/" + token);
        server.start();
    }

    URI address() {
        return address;
    }

    /** Return a credential-free reason for the most recent failed source read. */
    String lastFailure() {
        return lastFailure;
    }

    private void handle(HttpExchange request) throws IOException {
        try (HttpExchange ignored = request) {
            Headers headers = request.getResponseHeaders();
            headers.set("Accept-Ranges", "bytes");
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Content-Type", mediaType);
            if (!request.getRequestURI().getRawPath().equals(address.getRawPath())) {
                sendError(request, 404);
                return;
            }
            String method = request.getRequestMethod();
            if ("HEAD".equals(method)) {
                headers.set("Content-Length", Long.toString(length));
                request.sendResponseHeaders(200, -1);
                return;
            }
            if (!"GET".equals(method)) {
                sendError(request, 405);
                return;
            }
            Range requested = parse(request.getRequestHeaders().getFirst("Range"));
            if (requested == null) {
                headers.set("Content-Range", "bytes */" + length);
                sendError(request, 416);
                return;
            }
            byte[] first;
            try {
                first = slice(requested.begin, requested.end);
            } catch (Exception unavailable) {
                lastFailure = failure(unavailable);
                sendError(request, 502);
                return;
            }
            if (requested.partial) {
                headers.set("Content-Range", "bytes " + requested.begin + "-" + requested.end + "/" + length);
            }
            long total = requested.end - requested.begin + 1;
            headers.set("Content-Length", Long.toString(total));
            request.sendResponseHeaders(requested.partial ? 206 : 200, total);
            try (OutputStream output = request.getResponseBody()) {
                output.write(first);
                long position = requested.begin + first.length;
                while (position <= requested.end && !Thread.currentThread().isInterrupted()) {
                    byte[] next;
                    try {
                        next = slice(position, requested.end);
                    } catch (Exception unavailable) {
                        lastFailure = failure(unavailable);
                        break;
                    }
                    output.write(next);
                    position += next.length;
                }
            } catch (IOException disconnected) {
                // Release the request when the player stops or seeks to a different range.
            }
        }
    }

    /**
     * Return the bytes from {@code position} to the end of its chunk (at most {@code end}) and read the following
     * chunks ahead. Block only when the chunk is not cached yet.
     */
    private byte[] slice(long position, long end) throws Exception {
        long index = position / CHUNK_BYTES;
        CompletableFuture<byte[]> pending = chunk(index);
        for (int ahead = 1; ahead <= READ_AHEAD_CHUNKS && (index + ahead) * CHUNK_BYTES < length; ahead++) {
            chunk(index + ahead);
        }
        byte[] data;
        try {
            data = pending.get();
        } catch (ExecutionException failed) {
            throw failed.getCause() instanceof Exception ? (Exception) failed.getCause() : failed;
        }
        int from = (int) (position - index * CHUNK_BYTES);
        int to = (int) Math.min(data.length, end - index * CHUNK_BYTES + 1);
        return Arrays.copyOfRange(data, from, to);
    }

    /** The cached or newly requested chunk; a failed read is forgotten so that the next request retries it. */
    private CompletableFuture<byte[]> chunk(long index) {
        synchronized (chunks) {
            CompletableFuture<byte[]> cached = chunks.get(index);
            if (cached != null) {
                return cached;
            }
            CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> fetch(index), fetchers);
            chunks.put(index, read);
            read.whenComplete((data, failure) -> {
                if (failure != null) {
                    synchronized (chunks) {
                        chunks.remove(index, read);
                    }
                }
            });
            return read;
        }
    }

    private byte[] fetch(long index) {
        long start = index * CHUNK_BYTES;
        int count = (int) Math.min(CHUNK_BYTES, length - start);
        byte[] data;
        try {
            data = source.read(start, count);
        } catch (Exception unavailable) {
            throw new CompletionException(unavailable);
        }
        if (data == null || data.length != count) {
            throw new CompletionException(new IncompleteRange());
        }
        return data;
    }

    private static String failure(Exception unavailable) {
        Throwable cause = unavailable instanceof CompletionException && unavailable.getCause() != null
                ? unavailable.getCause() : unavailable;
        return cause instanceof IncompleteRange
                ? "BSCW hat einen unvollständigen Video-Bereich geliefert."
                : "BSCW hat den Video-Bereich nicht geliefert (" + cause.getClass().getSimpleName() + ").";
    }

    /** A source read returned fewer bytes than requested. */
    private static final class IncompleteRange extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private Range parse(String header) {
        if (header == null) {
            return new Range(0, length - 1, false);
        }
        Matcher matched = RANGE.matcher(header.trim());
        if (!matched.matches() || matched.group(1).isEmpty() && matched.group(2).isEmpty()) {
            return null;
        }
        try {
            long start;
            long end;
            if (matched.group(1).isEmpty()) {
                long suffixLength = Long.parseLong(matched.group(2));
                if (suffixLength <= 0) {
                    return null;
                }
                start = Math.max(0, length - suffixLength);
                end = length - 1;
            } else {
                start = Long.parseLong(matched.group(1));
                end = matched.group(2).isEmpty() ? length - 1 : Long.parseLong(matched.group(2));
                if (start >= length || end < start) {
                    return null;
                }
                end = Math.min(end, length - 1);
            }
            return new Range(start, end, true);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static void sendError(HttpExchange request, int status) throws IOException {
        byte[] error = ("HTTP " + status).getBytes(StandardCharsets.US_ASCII);
        request.sendResponseHeaders(status, error.length);
        try (OutputStream output = request.getResponseBody()) {
            output.write(error);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
        fetchers.shutdownNow();
        synchronized (chunks) {
            chunks.clear();
        }
    }

    private static final class Range {
        private final long begin;
        private final long end;
        private final boolean partial;

        private Range(long begin, long end, boolean partial) {
            this.begin = begin;
            this.end = end;
            this.partial = partial;
        }
    }
}
