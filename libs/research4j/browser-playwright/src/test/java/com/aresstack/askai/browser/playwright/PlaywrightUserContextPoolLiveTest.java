package com.aresstack.askai.browser.playwright;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The worker user contexts in a REAL browser through the productive {@link PlaywrightWorkerBrowserBackend}
 * (official Playwright Java API on playwright4j's GraalJS driver child), against a local server: sign-in
 * cookies set by the server AND local storage written by page JavaScript must stay inside their user
 * context; navigations of workers in different worker browsers must overlap on the server.
 *
 * <p>ENVIRONMENT-GATED: needs a Chromium executable — {@code -Daskai.test.workerBrowser=<path>} / env
 * {@code ASKAI_TEST_WORKER_BROWSER}, or the Playwright-bundled Chromium under {@code /opt/pw-browsers}. Without
 * one it SKIPS with a readable message. It never downloads a browser. Chromium speaks CDP here; the
 * Camoufox path maps the same Playwright calls to WebDriver BiDi {@code browser.createUserContext} /
 * {@code browsingContext.create} — that variant needs the managed Camoufox and runs on a desktop.</p>
 */
public class PlaywrightUserContextPoolLiveTest {

    private static final long SLOW_MILLIS = 1_500L;

    private static HttpServer server;
    private static String base;
    private static final AtomicInteger slowInFlight = new AtomicInteger();
    private static final AtomicInteger slowMaxInFlight = new AtomicInteger();

    @BeforeClass
    public static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            String user = query(exchange, "user");
            exchange.getResponseHeaders().add("Set-Cookie", "session=" + user + "; Path=/");
            respond(exchange, page("Signed in", "localStorage.setItem('account','" + user + "');"
                    + "document.getElementById('c').textContent='signed in as " + user + "';"));
        });
        server.createContext("/whoami", exchange -> respond(exchange, page("Who am I",
                "document.getElementById('c').textContent='cookie=[' + document.cookie + '] storage=['"
                        + " + (localStorage.getItem('account') || '') + ']';")));
        server.createContext("/slow", exchange -> {
            int now = slowInFlight.incrementAndGet();
            slowMaxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(SLOW_MILLIS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                slowInFlight.decrementAndGet();
            }
            respond(exchange, page("Slow " + query(exchange, "n"), "document.getElementById('c').textContent='slow';"));
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String page(String title, String script) {
        return "<!doctype html><html><head><title>" + title + "</title></head><body><div id='c'></div>"
                + "<script>" + script + "</script></body></html>";
    }

    private static String query(HttpExchange exchange, String key) {
        String q = exchange.getRequestURI().getQuery();
        if (q != null) {
            for (String part : q.split("&")) {
                if (part.startsWith(key + "=")) {
                    return part.substring(key.length() + 1);
                }
            }
        }
        return "";
    }

    private static void respond(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Path chromium() {
        String configured = System.getProperty("askai.test.workerBrowser", System.getenv("ASKAI_TEST_WORKER_BROWSER"));
        if (configured != null && !configured.trim().isEmpty()) {
            Path path = Paths.get(configured.trim());
            return Files.isExecutable(path) ? path : null;
        }
        Path root = Paths.get("/opt/pw-browsers");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (java.util.stream.Stream<Path> found = Files.walk(root, 3)) {
            return found.filter(p -> p.getFileName().toString().equals("chrome") && Files.isExecutable(p)
                    && p.toString().contains("chromium-")).findFirst().orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private static Map<String, Object> url(String url) {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("url", url);
        return args;
    }

    @Test
    public void realUserContextsIsolateSignInsRunInParallelAndCleanUp() throws Exception {
        Path executable = chromium();
        assumeTrue("SKIPPED (environment-gated live test): no Chromium executable found", executable != null);
        PlaywrightBrowserRuntime.Options options = new PlaywrightBrowserRuntime.Options("chrome", true, true,
                null, null, true);
        PlaywrightUserContextPool pool = new PlaywrightUserContextPool(new WorkerBrowserLimits(3, 2, 3, 8,
                120_000L, 60_000L, 0L));
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            WorkerBrowserBackend.Factory factory = PlaywrightWorkerBrowserBackend.factory(options, executable);
            PlaywrightUserContextPool.Chat chatA = pool.openChat("chat-a", factory);
            PlaywrightUserContextPool.Chat chatB = pool.openChat("chat-b", factory);

            // 1. Two workers of chat A: separate user contexts, separate sign-ins (cookie + local storage).
            WorkerUserContext alice = chatA.acquire("worker-1", null, 0);
            WorkerUserContext anonymous = chatA.acquire("worker-2", null, 0);
            String signedIn = alice.firstBrowsingContext().call("web_open", url(base + "/login?user=alice"));
            assertTrue(signedIn, signedIn.contains("signed in as alice"));
            String aliceView = alice.firstBrowsingContext().call("web_open", url(base + "/whoami"));
            assertTrue(aliceView, aliceView.contains("cookie=[session=alice] storage=[alice]"));
            String otherView = anonymous.firstBrowsingContext().call("web_open", url(base + "/whoami"));
            assertTrue("worker 2 must see neither cookie nor local storage: " + otherView,
                    otherView.contains("cookie=[] storage=[]"));

            // 2. A second tab of alice's user context shares her sign-in; it is a separate page.
            WorkerBrowsingContext aliceTab2 = alice.openBrowsingContext();
            assertTrue(aliceTab2.call("web_open", url(base + "/whoami")).contains("session=alice"));
            assertTrue(alice.firstBrowsingContext().call("web_read", null).contains("TITLE: Who am I"));

            // 3. Chat B: its own worker browser, nothing of chat A visible.
            WorkerUserContext chatBWorker = chatB.acquire("worker-1", null, 0);
            assertNotEquals(alice.processId(), chatBWorker.processId());
            assertTrue(chatBWorker.firstBrowsingContext().call("web_open", url(base + "/whoami"))
                    .contains("cookie=[] storage=[]"));

            // 4. A saved sign-in comes back in a later user context (storage state export/import).
            String state = alice.exportState();
            alice.close();
            WorkerUserContext resumed = chatA.acquire("worker-1-resumed", state, 0);
            assertTrue(resumed.firstBrowsingContext().call("web_open", url(base + "/whoami"))
                    .contains("cookie=[session=alice] storage=[alice]"));
            resumed.close();
            anonymous.close();
            chatBWorker.close();

            // 5. Real parallelism: three workers in three worker browsers hit a slow page at once.
            final List<WorkerUserContext> parallel = new ArrayList<WorkerUserContext>();
            for (int i = 0; i < 3; i++) {
                parallel.add(chatA.acquire("parallel-" + i, null, 0));
            }
            final CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<Future<String>>();
            for (int i = 0; i < parallel.size(); i++) {
                final WorkerUserContext uc = parallel.get(i);
                final int n = i;
                results.add(workers.submit(new Callable<String>() {
                    public String call() throws Exception {
                        go.await();
                        return uc.firstBrowsingContext().call("web_open", url(base + "/slow?n=" + n));
                    }
                }));
            }
            slowMaxInFlight.set(0);
            long start = System.nanoTime();
            go.countDown();
            for (Future<String> result : results) {
                assertTrue(result.get(60, TimeUnit.SECONDS).contains("TITLE: Slow"));
            }
            long wall = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            System.err.println("[live] 3 parallel slow navigations: wall=" + wall + " ms, server saw max "
                    + slowMaxInFlight.get() + " in flight, processes=" + pool.processCount(chatA));
            assertEquals("the server must see the three navigations at the same time", 3, slowMaxInFlight.get());
            assertTrue("3 x " + SLOW_MILLIS + " ms in parallel must take well under the serial "
                    + (3 * SLOW_MILLIS) + " ms, took " + wall + " ms", wall < 2 * SLOW_MILLIS + 500L);
            for (WorkerUserContext uc : parallel) {
                uc.close();
            }

            // 6. Cleanup: with idle stop 0 every worker browser of a chat without user contexts ends.
            chatB.close();
            long deadline = System.currentTimeMillis() + 15_000L;
            while (pool.processCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals("no worker browser may outlive its user contexts", 0, pool.processCount());
        } finally {
            workers.shutdownNow();
            pool.close();
        }
    }
}
