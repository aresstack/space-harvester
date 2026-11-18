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
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expose one authenticated remote media resource to a local player without exposing source credentials.
 * Bind exclusively to 127.0.0.1 and require an unpredictable per-viewer URL token.
 */
public final class MediaStreamBridge implements AutoCloseable {

    public interface ChunkSource {
        byte[] read(long offset, int maximumBytes) throws Exception;
    }

    static final int CHUNK_BYTES = 256 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private final ChunkSource source;
    private final long length;
    private final String mediaType;
    private final HttpServer server;
    private final ExecutorService workers;
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
            int firstBytes = (int) Math.min(CHUNK_BYTES, requested.end - requested.begin + 1);
            byte[] first;
            try {
                first = source.read(requested.begin, firstBytes);
            } catch (Exception unavailable) {
                lastFailure = "BSCW hat den Video-Bereich nicht geliefert ("
                        + unavailable.getClass().getSimpleName() + ").";
                sendError(request, 502);
                return;
            }
            if (first == null || first.length != firstBytes) {
                lastFailure = "BSCW hat einen unvollständigen Video-Bereich geliefert.";
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
                    int count = (int) Math.min(CHUNK_BYTES, requested.end - position + 1);
                    byte[] next = source.read(position, count);
                    if (next == null || next.length != count) {
                        lastFailure = "BSCW hat einen unvollständigen Video-Bereich geliefert.";
                        break;
                    }
                    output.write(next);
                    position += next.length;
                }
            } catch (Exception disconnected) {
                // Release the request when the player stops or seeks to a different range.
            }
        }
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
