package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.hud.ResearchHudState;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The local channel between ONE browser's AskAI Research add-on and this runtime (docs/research-browser-addon.md).
 * A loopback-only HTTP server on a random port; every request must carry the random bearer token AskAI wrote into the
 * add-on's {@code bridge.json} and come from an extension origin, so a visited page can neither read the HUD state nor
 * send commands. The add-on long-polls {@code GET /v1/state?after=<version>} and posts the user's clicks to
 * {@code POST /v1/command}; commands land in the same {@link HudCommandInbox} the in-page overlay fed, so the runtime
 * cannot tell (and need not care) where a Skip came from.
 *
 * <p>Page reads go the same way: {@link #submitRead} queues a read-only script for the tab showing a URL, the add-on
 * long-polls {@code GET /v1/read}, runs the script in that page's tab's isolated content-script world (the page's own
 * scripts never see it) and posts the structured-clone result to {@code POST /v1/read-result}.</p>
 */
final class AddonBridge implements AutoCloseable {

    static final int PROTOCOL = 1;
    /** How long a state poll is held open when nothing changed. */
    static final long POLL_HOLD_MILLIS = 20_000L;
    /** The add-on counts as connected while its last request is at most this old (a poll returns every 20 s). */
    static final long CONNECTED_WINDOW_MILLIS = 30_000L;
    /** The HUD commands the overlay sends; anything else is refused before it reaches the inbox. */
    private static final Pattern COMMAND = Pattern.compile(
            "PAUSE|RESUME|SKIP|NEXT|SET_DELAY:[0-9]{1,3}|SET_RELEVANCE:(on|off)");
    private static final Gson GSON = new Gson();
    /** A capture of a large page is a few MB of JSON; anything beyond this is refused. */
    static final int MAXIMUM_READ_RESULT_BYTES = 64 * 1024 * 1024;
    /** Chrome/Edge: where the read script files live inside the extension directory. */
    static final String READ_SCRIPT_DIRECTORY = "reads";

    /** Set by the driver once it exists; the browser (and with it the add-on) starts only after that. */
    private volatile HudCommandInbox inbox;
    private final String token;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Object lock = new Object();
    private long version;
    private ResearchHudState state;
    private String host = "";
    private volatile long lastContactMillis;
    private volatile String addonVersion;
    private volatile long servedVersion;
    private volatile boolean closed;
    /** Page reads not yet fetched by the add-on, in submit order. Guarded by {@link #lock}. */
    private final ArrayDeque<PageRead> queuedReads = new ArrayDeque<PageRead>();
    /** Every read whose result is still open (queued or fetched), by id. Guarded by {@link #lock}. */
    private final Map<String, PageRead> openReads = new LinkedHashMap<String, PageRead>();
    private final AtomicLong readIds = new AtomicLong();
    /**
     * Chrome/Edge (Manifest V3) only: the extension directory into whose {@code reads/} AskAI writes each read script
     * as a file, because an MV3 extension cannot execute a script given as text. Null for Firefox, which runs the
     * script text itself.
     */
    private volatile Path readScriptDirectory;

    private AddonBridge(String token, HttpServer server, ExecutorService executor) {
        this.token = token;
        this.server = server;
        this.executor = executor;
    }

    /** Start the bridge on a random loopback port; commands from the add-on go to the inbox of {@link #deliverTo}. */
    static AddonBridge start() throws IOException {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        // Explicitly IPv4: the add-on connects to 127.0.0.1 (and only has that host permission), while the loopback
        // address of a JVM with java.net.preferIPv6Addresses=true would be ::1.
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1}), 0), 0);
        AtomicInteger threads = new AtomicInteger();
        // A few threads: a held state poll and a held read poll, plus a command, a read result and a hello may overlap.
        ExecutorService executor = Executors.newFixedThreadPool(6, runnable -> {
            Thread thread = new Thread(runnable, "askai-addon-bridge-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        AddonBridge bridge = new AddonBridge(HexFormat.of().formatHex(secret), server, executor);
        server.createContext("/v1/", bridge::handle);
        server.setExecutor(executor);
        server.start();
        return bridge;
    }

    /**
     * Chrome/Edge: write every read script into {@code <extensionDirectory>/reads/} and name that file in the read
     * (docs/research-browser-addon.md, stage 4). Files are named by the SHA-256 of their content, so a script that is
     * read again reuses its file and a file never changes once written.
     */
    void readScriptsAsFilesIn(Path extensionDirectory) {
        this.readScriptDirectory = extensionDirectory;
    }

    /** Where the add-on's commands go: the driver's HUD inbox. */
    void deliverTo(HudCommandInbox target) {
        this.inbox = target;
    }

    /** The bound address: always 127.0.0.1, the only host the add-on may reach. */
    InetAddress address() {
        return server.getAddress().getAddress();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String token() {
        return token;
    }

    /** The {@code bridge.json} the add-on reads at its start. */
    String clientConfig() {
        JsonObject config = new JsonObject();
        config.addProperty("protocol", PROTOCOL);
        config.addProperty("port", port());
        config.addProperty("token", token);
        return GSON.toJson(config);
    }

    /** Publish a new HUD state; a held poll returns it at once. */
    void publish(ResearchHudState next) {
        publish(next, "");
    }

    /** Publish a new HUD state together with the host of the page AskAI is on (shown in the panel). */
    void publish(ResearchHudState next, String pageHost) {
        synchronized (lock) {
            state = next;
            host = pageHost == null ? "" : pageHost;
            version++;
            lock.notifyAll();
        }
    }

    /** True while the add-on is polling: the HUD is visible in the browser UI. */
    boolean addonConnected() {
        return !closed && lastContactMillis != 0L
                && System.currentTimeMillis() - lastContactMillis <= CONNECTED_WINDOW_MILLIS;
    }

    /** The add-on's manifest version from its hello, or null before the first hello. */
    String addonVersion() {
        return addonVersion;
    }

    /** The newest HUD state version the add-on has fetched (0 = none yet). */
    long servedVersion() {
        return servedVersion;
    }

    /** The version of the newest published state. */
    long publishedVersion() {
        synchronized (lock) {
            return version;
        }
    }

    /**
     * Queue a read-only page script for the add-on. It runs in the isolated content-script world of the tab of
     * {@code tabKey} (a stable key per Playwright page: its first read binds it to the one unbound tab whose URL
     * equals {@code tabUrl}, later reads go to that tab only), as {@code (<script>)()}; its structured-clone result arrives as Gson values (Map,
     * List, Double, Boolean, String). The caller waits on {@link PageRead#isDone()} and must {@link PageRead#cancel()}
     * a read it stops waiting for.
     */
    PageRead submitRead(String tabKey, String tabUrl, String script) {
        String file = null;
        Path directory = readScriptDirectory;
        if (directory != null) {
            try {
                file = writeScriptFile(directory, script);
            } catch (IOException | RuntimeException unwritable) {
                PageRead failed = new PageRead("read-" + readIds.incrementAndGet(), tabKey, tabUrl, script, null);
                failed.result.completeExceptionally(new PageReadFailed(
                        "the read script could not be written for the extension: " + unwritable.getMessage()));
                return failed;
            }
        }
        PageRead read = new PageRead("read-" + readIds.incrementAndGet(), tabKey, tabUrl, script, file);
        synchronized (lock) {
            if (closed) {
                read.result.completeExceptionally(new PageReadFailed("the research add-on bridge is closed"));
                return read;
            }
            openReads.put(read.id, read);
            queuedReads.add(read);
            lock.notifyAll();
        }
        return read;
    }

    /**
     * {@code reads/<sha256>.js} with {@code (<script>)();} under the extension directory, written once (to a temporary
     * name, then moved), so the extension never sees a half-written file. @return the path relative to the extension
     */
    static String writeScriptFile(Path extensionDirectory, String script) throws IOException {
        String code = "(" + script + ")();\n";
        byte[] bytes = code.getBytes(StandardCharsets.UTF_8);
        String name;
        try {
            name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ".js";
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        Path reads = extensionDirectory.resolve(READ_SCRIPT_DIRECTORY);
        Path target = reads.resolve(name);
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(reads);
            Path temporary = Files.createTempFile(reads, "write-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return READ_SCRIPT_DIRECTORY + "/" + name;
    }

    /** How many reads are queued or running (diagnostics and tests). */
    int openReadCount() {
        synchronized (lock) {
            return openReads.size();
        }
    }

    /** A failed or unanswered page read; a RuntimeException so the driver's existing guard handling applies. */
    static final class PageReadFailed extends RuntimeException {
        PageReadFailed(String message) {
            super(message);
        }
    }

    /** One page read handed to the add-on. */
    final class PageRead {
        final String id;
        final String tabKey;
        final String tabUrl;
        final String script;
        /** Chrome/Edge: the script's file relative to the extension directory; null for Firefox. */
        final String file;
        final CompletableFuture<Object> result = new CompletableFuture<Object>();

        private PageRead(String id, String tabKey, String tabUrl, String script, String file) {
            this.id = id;
            this.tabKey = tabKey;
            this.tabUrl = tabUrl;
            this.script = script;
            this.file = file;
        }

        boolean isDone() {
            return result.isDone();
        }

        /** The script's value; throws {@link PageReadFailed} when the add-on reported an error. */
        Object value() {
            try {
                return result.get();
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                throw cause instanceof PageReadFailed ? (PageReadFailed) cause
                        : new PageReadFailed(String.valueOf(cause));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PageReadFailed("interrupted while waiting for the page read");
            }
        }

        /** Stop waiting: a queued read is never handed out, a late result is dropped. */
        void cancel() {
            synchronized (lock) {
                openReads.remove(id);
                queuedReads.remove(this);
            }
            result.completeExceptionally(new PageReadFailed("page read cancelled"));
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (lock) {
            for (PageRead read : openReads.values()) {
                read.result.completeExceptionally(new PageReadFailed("the research add-on bridge was closed"));
            }
            openReads.clear();
            queuedReads.clear();
            lock.notifyAll();
        }
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!authorized(exchange)) {
                respond(exchange, 403, null);
                return;
            }
            lastContactMillis = System.currentTimeMillis();
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/v1/state".equals(path)) {
                state(exchange);
            } else if ("POST".equals(method) && "/v1/command".equals(path)) {
                String command = body(exchange, 64).trim();
                if (!COMMAND.matcher(command).matches()) {
                    respond(exchange, 400, null);
                    return;
                }
                HudCommandInbox target = inbox;
                if (target != null) {
                    target.add(command);
                }
                respond(exchange, 204, null);
            } else if ("GET".equals(method) && "/v1/read".equals(path)) {
                nextRead(exchange);
            } else if ("POST".equals(method) && "/v1/read-result".equals(path)) {
                String body = body(exchange, MAXIMUM_READ_RESULT_BYTES);
                respond(exchange, readResult(body) ? 204 : 400, null);
            } else if ("POST".equals(method) && "/v1/hello".equals(path)) {
                hello(body(exchange, 1024));
                respond(exchange, 204, null);
            } else {
                respond(exchange, 404, null);
            }
        }
    }

    /**
     * The bearer token, compared in constant time. A page cannot send it: an Authorization header forces a CORS
     * request, which carries the page's own origin; such a request is refused even before the token is checked.
     * Extension origins are the Firefox add-on's {@code moz-extension://} and the Chrome/Edge extension's
     * {@code chrome-extension://<its fixed id>}.
     */
    private boolean authorized(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !extensionOrigin(origin)) {
            return false;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + token;
        return header != null && MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    static boolean extensionOrigin(String origin) {
        return origin.startsWith("moz-extension://") || origin.equals("chrome-extension://" + ResearchAddon.CHROME_ID);
    }

    private void state(HttpExchange exchange) throws IOException {
        long after = afterParameter(exchange.getRequestURI());
        long deadline = System.currentTimeMillis() + POLL_HOLD_MILLIS;
        long currentVersion;
        ResearchHudState current;
        String currentHost;
        synchronized (lock) {
            while (!closed && (state == null || version <= after)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            currentVersion = version;
            current = state;
            currentHost = host;
        }
        lastContactMillis = System.currentTimeMillis();
        if (current == null || currentVersion <= after) {
            respond(exchange, 204, null);
            return;
        }
        servedVersion = currentVersion;
        JsonObject body = new JsonObject();
        body.addProperty("version", currentVersion);
        JsonObject json = toJson(current);
        json.addProperty("host", currentHost);
        body.add("state", json);
        respond(exchange, 200, GSON.toJson(body));
    }

    /** The add-on's held read poll: the oldest queued read, or 204 after {@link #POLL_HOLD_MILLIS}. */
    private void nextRead(HttpExchange exchange) throws IOException {
        long deadline = System.currentTimeMillis() + POLL_HOLD_MILLIS;
        PageRead read;
        synchronized (lock) {
            while (!closed && queuedReads.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            read = closed ? null : queuedReads.poll();
        }
        lastContactMillis = System.currentTimeMillis();
        if (read == null) {
            respond(exchange, 204, null);
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("id", read.id);
        body.addProperty("tab", read.tabKey);
        body.addProperty("url", read.tabUrl);
        if (read.file != null) {
            body.addProperty("file", read.file); // Chrome/Edge: MV3 runs files only, never script text
        } else {
            body.addProperty("script", read.script);
        }
        respond(exchange, 200, GSON.toJson(body));
    }

    /** {@code {id, ok, value}} or {@code {id, ok:false, error}}; false for a body that is no read result. */
    private boolean readResult(String body) {
        JsonObject result;
        try {
            result = GSON.fromJson(body, JsonObject.class);
        } catch (RuntimeException unreadable) {
            return false;
        }
        if (result == null || !result.has("id") || !result.get("id").isJsonPrimitive()) {
            return false;
        }
        PageRead read;
        synchronized (lock) {
            read = openReads.remove(result.get("id").getAsString());
        }
        if (read == null) {
            return true; // cancelled or timed out on the Java side: the late result is dropped
        }
        boolean ok = result.has("ok") && result.get("ok").isJsonPrimitive() && result.get("ok").getAsBoolean();
        if (ok) {
            read.result.complete(result.has("value") ? GSON.fromJson(result.get("value"), Object.class) : null);
        } else {
            String error = result.has("error") && result.get("error").isJsonPrimitive()
                    ? result.get("error").getAsString() : "unknown error";
            read.result.completeExceptionally(new PageReadFailed("the research add-on could not read the page: "
                    + error));
        }
        return true;
    }

    private void hello(String body) {
        try {
            JsonObject hello = GSON.fromJson(body, JsonObject.class);
            if (hello != null && hello.has("addonVersion")) {
                addonVersion = hello.get("addonVersion").getAsString();
            }
        } catch (RuntimeException ignored) {
            // The version is informational; an unreadable hello still counts as contact.
        }
    }

    static JsonObject toJson(ResearchHudState state) {
        JsonObject json = new JsonObject();
        json.addProperty("phase", state.phase);
        json.addProperty("status", state.statusText);
        json.addProperty("waiting", state.waitingForUser);
        json.addProperty("countdown", state.countdownSeconds);
        json.addProperty("paused", state.paused);
        json.addProperty("delay", state.delaySeconds);
        json.addProperty("relevant", state.relevant);
        json.addProperty("terminal", state.terminal);
        return json;
    }

    private static long afterParameter(URI uri) {
        String query = uri.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("after=")) {
                    try {
                        return Long.parseLong(part.substring("after=".length()));
                    } catch (NumberFormatException ignored) {
                        return 0L;
                    }
                }
            }
        }
        return 0L;
    }

    private static String body(HttpExchange exchange, int limit) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                return "";
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (json == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
