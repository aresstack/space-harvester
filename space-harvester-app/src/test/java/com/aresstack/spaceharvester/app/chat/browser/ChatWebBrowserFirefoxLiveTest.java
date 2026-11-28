package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.astu.approval.ApprovalDecision;
import com.aresstack.enterpriseai.astu.approval.ApprovalPromptPort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Der erste Browser-Schritt eines Chats im Profil Intern so, wie die App ihn ausführt: {@link ChatWebBrowser#start} mit
 * der Browser-Laufzeit und dem installierten Browser, die Werkzeuge über {@link BrowserChatTools} und, wie der Chat
 * mit mehreren Werkzeugen einer Runde, Seite öffnen und Websuche gleichzeitig. Jeder Aufruf muss zurückkommen; sonst
 * nennt der Test den Aufruf und die Stacks der wartenden Threads. Nur mit {@value #CHANNEL_ENV} (CI: Firefox, auch unter
 * Unternehmensrichtlinien).
 */
class ChatWebBrowserFirefoxLiveTest {

    static final String CHANNEL_ENV = "CHAT_BROWSER_TEST_CHANNEL";
    static final String EXECUTABLE_ENV = "CHAT_BROWSER_TEST_EXECUTABLE";
    /** Großzügig für einen CI-Runner; ein Aufruf, der länger braucht, kommt nicht mehr zurück. */
    private static final long CALL_SECONDS = 120;

    @TempDir
    Path data;

    @Test
    void openingAPageAndSearchingInternallyInOneRoundBothComeBack() throws Exception {
        String channel = System.getenv(CHANNEL_ENV);
        assumeTrue(channel != null && !channel.isBlank(), "ohne " + CHANNEL_ENV + " übersprungen");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/find")) {
                respond(exchange, "<!doctype html><html><head><title>Treffer</title></head><body>"
                        + "<nav><a href='/'>Start</a><a href='/hilfe'>Hilfe</a></nav><main><ul>"
                        + "<li><h2><a href='http://localhost:" + server.getAddress().getPort()
                        + "/a'>Reisekosten Merkblatt</a></h2><p>Wann Reisekosten erstattet werden.</p></li>"
                        + "<li><h2><a href='http://localhost:" + server.getAddress().getPort()
                        + "/b'>Trennungsgeld Merkblatt</a></h2><p>Wann Trennungsgeld gezahlt wird.</p></li>"
                        + "<li><h2><a href='http://localhost:" + server.getAddress().getPort()
                        + "/c'>Umzugskosten Merkblatt</a></h2><p>Welche Umzugskosten erstattet werden.</p></li>"
                        + "</ul></main></body></html>");
            } else if (path.equals("/")) {
                respond(exchange, "<!doctype html><html><head><title>Portal</title></head><body><form action='/find'>"
                        + "<input type='search' name='q' aria-label='Suche'><button>Suchen</button></form>"
                        + "<p>Willkommen im Portal</p></body></html>");
            } else {
                respond(exchange, "<html><head><title>Seite</title></head><body><p>Inhalt " + path
                        + "</p></body></html>");
            }
        });
        server.start();
        Map<String, String> values = new HashMap<>();
        BrowserSettings settings = new BrowserSettings(new BrowserSettings.Store() {
            @Override
            public synchronized String get(String key) {
                return values.get(key);
            }

            @Override
            public synchronized void putAll(Map<String, String> changes) {
                values.putAll(changes);
            }
        });
        settings.saveChannel(channel);
        String executable = System.getenv(EXECUTABLE_ENV);
        if (executable != null && !executable.isBlank()) {
            settings.saveExecutable(channel, executable);
        }
        ChatWebBrowser browser = ChatWebBrowser.start(data, settings);
        assertThat(browser).as(ChatWebBrowser.unavailable()).isNotNull();
        List<String> asked = new ArrayList<>();
        browser.searchPageQuestion(() -> {
            asked.add("gefragt");
            return base + "/find?q={query}";
        });
        browser.profile("chat-1", true);
        ApprovalPromptPort approve = (request, timeout) -> ApprovalDecision.of(request,
                ApprovalDecision.Outcome.APPROVED);
        BrowserChatTools tools = new BrowserChatTools(browser.tools("chat-1"), call -> browser.asking(approve, call));
        JsonMapper json = JsonMapper.builder().build();
        ExecutorService round = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "chat-tool");
            thread.setDaemon(true);
            return thread;
        });
        long turn = browser.beginTurn("chat-1", progress -> { });
        try {
            Future<String> opened = round.submit(() -> tools.run("browser_navigate",
                    json.readTree("{\"url\":\"" + base + "/start\"}")));
            Future<String> searched = round.submit(() -> tools.run("browser_search",
                    json.readTree("{\"query\":\"Reisekosten\"}")));

            String page = await("browser_navigate", opened);
            String hits = await("browser_search", searched);

            assertThat(page).contains("Inhalt /start");
            assertThat(hits).contains("Reisekosten Merkblatt");
            assertThat(asked).hasSize(1);
        } finally {
            browser.endTurn("chat-1", turn);
            browser.shutdown();
            round.shutdownNow();
            server.stop(0);
        }
    }

    private static String await(String call, Future<String> result) throws Exception {
        try {
            String text = result.get(CALL_SECONDS, TimeUnit.SECONDS);
            System.out.println("[chat-browser] " + call + " kam zurück: " + text.lines().findFirst().orElse(""));
            return text;
        } catch (TimeoutException hung) {
            StringBuilder dump = new StringBuilder();
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                dump.append("[chat-browser] Thread ").append(entry.getKey().getName()).append(" (")
                        .append(entry.getKey().getState()).append("):\n");
                for (StackTraceElement element : entry.getValue()) {
                    dump.append("    at ").append(element).append('\n');
                }
            }
            System.out.println(dump);
            throw new AssertionError(call + " kam nicht innerhalb von " + CALL_SECONDS + " s zurück");
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
