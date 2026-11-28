package com.aresstack.askai.browser.playwright;

import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * The first step of a chat in the browser as the user's own (installed, possibly company-managed) browser starts it:
 * list the tabs and open a page in the first one. Each step must come back; the test names the step that does not.
 * The CI runs it under several Firefox enterprise policies (start page, blocked add-ons).
 */
public class ChatFirstTabLiveTest {

    /** Generous for a CI runner; a step that needs longer does not come back at all. */
    private static final long STEP_SECONDS = 90;

    @Test
    public void theFirstPageOpensInTheBrowsersFirstTab() throws Exception {
        TestBrowserLauncher launcher = TestBrowserLauncher.fromEnvironment();
        String readiness = launcher.readiness().render();
        assumeTrue("SKIPPED (environment-gated live test): " + readiness, readiness.startsWith("READY"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "<html><head><title>Erste Seite</title></head><body>Inhalt</body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Path profile = Files.createTempDirectory("chat-first-tab");
        ExecutorService steps = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-first-tab-step");
            thread.setDaemon(true);
            return thread;
        });
        BrowserInstance instance = step(steps, "start", () -> new PlaywrightChatEngine(launcher, false, 30000L)
                .start(profile));
        try {
            BrowserConnection connection = step(steps, "connect", instance::connect);
            String tab = step(steps, "tabs", () -> {
                var tabs = connection.tabs();
                System.out.println("[first-tab] tabs after start: " + tabs);
                return tabs.isEmpty() ? connection.createTab() : tabs.get(0).contextId();
            });
            step(steps, "navigate", () -> {
                connection.navigate(tab, url);
                return null;
            });
            assertEquals("Erste Seite", step(steps, "read", () -> connection.read(tab).title()));
        } finally {
            instance.stop();
            server.stop(0);
            steps.shutdownNow();
        }
    }

    private static <T> T step(ExecutorService steps, String name, Callable<T> call) throws Exception {
        Future<T> result = steps.submit(call);
        try {
            T value = result.get(STEP_SECONDS, TimeUnit.SECONDS);
            System.out.println("[first-tab] step " + name + " done");
            return value;
        } catch (TimeoutException hung) {
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                String threadName = entry.getKey().getName();
                if (threadName.contains("chat-first-tab-step") || threadName.contains("owner")
                        || threadName.contains("Browser")) {
                    StringBuilder trace = new StringBuilder("[first-tab] thread " + threadName + ":\n");
                    for (StackTraceElement element : entry.getValue()) {
                        trace.append("    at ").append(element).append('\n');
                    }
                    System.out.println(trace);
                }
            }
            throw new AssertionError("step " + name + " did not come back within " + STEP_SECONDS + " s");
        }
    }
}
