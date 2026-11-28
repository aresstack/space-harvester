package com.aresstack.spaceharvester.provingground;

import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntimeConfig;
import com.aresstack.spaceharvester.browser.runtime.Readiness;
import com.aresstack.spaceharvester.browser.runtime.RunningBrowser;
import com.aresstack.spaceharvester.provingground.model.Check;
import com.aresstack.spaceharvester.provingground.model.Scope;
import com.aresstack.spaceharvester.provingground.model.ScopeEntry;
import com.aresstack.spaceharvester.provingground.model.StepBinding;
import com.aresstack.spaceharvester.provingground.model.StepRole;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestCaseEntry;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.model.TestSuite;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import com.aresstack.spaceharvester.provingground.run.CaseRunResult;
import com.aresstack.spaceharvester.provingground.run.Credentials;
import com.aresstack.spaceharvester.provingground.run.ProjectRunResult;
import com.aresstack.spaceharvester.provingground.run.ProjectRunner;
import com.aresstack.spaceharvester.provingground.run.RunOptions;
import com.aresstack.spaceharvester.provingground.run.Totp;
import com.aresstack.spaceharvester.provingground.run.UserCredentials;
import com.aresstack.spaceharvester.provingground.store.ProjectFile;
import com.aresstack.spaceharvester.provingground.store.TestCaseFile;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The test model with a real browser: a project with two users from a (test) vault, a login with password and
 * one-time code as a precondition, a test case that switches between the users (each in its own browser context, so
 * the second one is not logged in), Given/When/Then descriptions, after checks over the page, and a failing test case
 * whose message never shows the password. Project and test cases go through their files. Browser as in
 * {@link RecordAndPlayLiveTest}; only a local test server is visited; all data invented.
 */
@Tag("live")
class ProjectRunLiveTest {

    private static final String SB_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static final String LOGIN = """
            <!doctype html><html lang="de"><head><meta charset="utf-8"><title>Anmeldung</title></head><body>
            <form method="post" action="/login">
              <label>Benutzer <input id="user" name="user"></label>
              <label>Passwort <input id="pw" name="pw" type="password"></label>
              <label>Code <input id="code" name="code"></label>
              <button type="submit">Anmelden</button>
            </form></body></html>
            """;

    @TempDir
    Path data;

    private BrowserRuntime runtime;
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        BrowserChannel channel = BrowserChannel.of(System.getenv().getOrDefault("PROVING_GROUND_TEST_CHANNEL",
                BrowserChannel.FIREFOX.id()));
        String executable = System.getenv().getOrDefault("PROVING_GROUND_TEST_EXECUTABLE", "");
        runtime = new BrowserRuntime(BrowserRuntimeConfig.of(channel, executable, data.resolve("browser"),
                Duration.ofMinutes(2)));
        Readiness readiness = runtime.readiness();
        assumeTrue(readiness.isReady(), "SKIPPED (environment-gated live test): " + readiness.render());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/login", this::login);
        server.createContext("/konto", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            String user = cookie != null && cookie.startsWith("session=") ? cookie.substring("session=".length()) : null;
            send(exchange, 200, "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>Konto</title>"
                    + "</head><body><h1 id=\"status\">" + (user == null ? "Nicht angemeldet" : "Angemeldet als " + user)
                    + "</h1></body></html>", null);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private void login(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 200, LOGIN, null);
            return;
        }
        Map<String, String> form = new HashMap<>();
        for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                form.put(pair.substring(0, equals), URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        Instant now = Instant.now();
        String code = form.getOrDefault("code", "");
        boolean codeOk = code.equals(Totp.code(SB_SECRET, now))
                || code.equals(Totp.code(SB_SECRET, now.minusSeconds(Totp.DEFAULT_PERIOD_SECONDS)));
        if ("sb.login".equals(form.get("user")) && "pw-sb".equals(form.get("pw")) && codeOk) {
            exchange.getResponseHeaders().set("Location", "/konto");
            send(exchange, 303, "", "session=sb.login; Path=/");
        } else {
            send(exchange, 200, "<!doctype html><title>Fehler</title><h1 id=\"status\">Anmeldung fehlgeschlagen</h1>",
                    null);
        }
    }

    private static void send(HttpExchange exchange, int status, String html, String cookie) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        if (cookie != null) {
            exchange.getResponseHeaders().set("Set-Cookie", cookie);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static TestStep step(String page, String name, String selector, String field, String value) {
        JsonObject action = new JsonObject();
        action.addProperty("name", name);
        if (selector != null) {
            action.addProperty("selector", selector);
        }
        if (field != null) {
            action.addProperty(field, value);
        }
        return TestStep.action(page, action);
    }

    @Test
    void usersPreconditionsHooksAndSecretsInARealBrowser() throws Exception {
        Path project = data.resolve("tests");
        TestCase login = new TestCase("Anmelden", List.of(
                step("page@login", "openPage", null, "url", "{{start}}login"),
                step("page@login", "fill", "#user", "text", "{{user.login}}"),
                step("page@login", "fill", "#pw", "text", "{{secret.Passwort}}"),
                step("page@login", "fill", "#code", "text", "{{otp()}}"),
                step("page@login", "click", "internal:role=button[name=\"Anmelden\"i]", null, null),
                TestStep.assertText("page@login", "#status", "Angemeldet als {{user.login}}", false)));
        TestCase twoUsers = new TestCase("Zwei Benutzer", List.of(
                step("page@sb", "openPage", null, "url", "{{start}}konto"),
                TestStep.assertText("page@sb", "#status", "Angemeldet als sb.login", false),
                step("page@pr", "openPage", null, "url", "{{start}}konto"),
                TestStep.assertText("page@pr", "#status", "Nicht angemeldet", false)));
        TestCase wrong = new TestCase("Falscher Wert", List.of(
                step("page@w", "openPage", null, "url", "{{start}}login"),
                step("page@w", "fill", "#pw", "text", "{{secret.Passwort}}"),
                TestStep.assertValue("page@w", "#pw", "{{secret.Passwort}}x")));
        TestCaseFile.write(login, project.resolve("anmeldung/login.jsonl"));
        TestCaseFile.write(twoUsers, project.resolve("konto/zwei.jsonl"));
        TestCaseFile.write(wrong, project.resolve("konto/falsch.jsonl"));

        String switchStep = twoUsers.step(2).id();
        String checkStep = twoUsers.step(3).id();
        TestCaseEntry loginEntry = TestCaseEntry.of("login", "Anmelden", "anmeldung/login.jsonl")
                .withBinding(login.step(0).id(), StepBinding.NONE.withRole(StepRole.GIVEN)
                        .withDescription("Anmeldeseite öffnen"));
        TestCaseEntry twoUsersEntry = TestCaseEntry.of("zwei", "Zwei Benutzer", "konto/zwei.jsonl")
                .withPreconditions(List.of("anmeldung/login"))
                .withBinding(switchStep, StepBinding.NONE.withUser("pruefer").withDescription("Prüfer öffnet sein Konto"))
                .withBinding(checkStep, StepBinding.NONE.withUser("pruefer").withRole(StepRole.THEN)
                        .withDescription("Prüfer ist nicht angemeldet"))
                .withScope(Scope.EMPTY.withAfterEach(List.of(
                        ScopeEntry.check("Titel", "{{title()}}", new Check("equals", "Konto")),
                        ScopeEntry.check("Status", "{{text('#status')}}", new Check("contains", "Nicht||Fehler")),
                        ScopeEntry.check("Adresse", "{{url()}}", new Check("ends", "/konto")))));
        TestCaseEntry wrongEntry = TestCaseEntry.of("falsch", "Falscher Wert", "konto/falsch.jsonl");
        TestProject model = new TestProject("Live", Scope.EMPTY.withBeforeAll(List.of(
                ScopeEntry.variable("start", base))), List.of(),
                List.of(new TestUser("sb", "Test/SB", ""), new TestUser("pruefer", "Test/Pruefer", "")), "sb",
                List.of(TestSuite.of("anmeldung", "Anmeldung", List.of(loginEntry)),
                        TestSuite.of("konto", "Konto", List.of(twoUsersEntry, wrongEntry))));
        ProjectFile.write(model, project);
        TestProject read = ProjectFile.read(project);

        Credentials vault = user -> switch (user.name()) {
            case "sb" -> new UserCredentials("sb.login", () -> "pw-sb", () -> SB_SECRET);
            case "pruefer" -> new UserCredentials("pruefer.login", () -> "pw-pruefer", () -> null);
            default -> null;
        };
        Path output = data.resolve("ergebnisse");
        ProjectRunResult result;
        try (RunningBrowser browser = runtime.ephemeral(true, null, false)) {
            result = new ProjectRunner().run(read, project, user -> browser.browser().newContext(), vault,
                    RunOptions.defaults().withActionTimeout(Duration.ofSeconds(10)).withOutputDirectory(output));
        }
        String report = result.render();
        System.out.println(report);

        CaseRunResult first = result.cases().get(0);
        assertTrue(first.passed(), report);
        assertTrue(first.steps().steps().get(0).description().equals("Given Anmeldeseite öffnen"), report);

        CaseRunResult second = result.cases().get(1);
        assertTrue(second.passed(), report);
        assertEquals(1, second.preconditions().size(), "the login ran first");
        assertEquals("Then Prüfer ist nicht angemeldet [pruefer]", second.steps().steps().get(3).description());
        assertEquals("Nicht angemeldet", second.checks().get(1).value());
        assertEquals(2, second.traces().size(), "one trace per user");
        for (Path trace : second.traces()) {
            assertTrue(Files.size(trace) > 0, trace.toString());
        }

        CaseRunResult third = result.cases().get(2);
        assertFalse(third.passed(), report);
        String error = third.steps().failure().error();
        assertTrue(error.contains("{{secret}}x"), error);
        assertFalse(report.contains("pw-sb"), "the password never shows: " + report);
        assertTrue(Files.exists(output.resolve("konto/falsch/fehler.png")), "screenshot of the failing page");
    }
}
