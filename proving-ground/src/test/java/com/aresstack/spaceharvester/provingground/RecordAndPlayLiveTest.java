package com.aresstack.spaceharvester.provingground;

import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntimeConfig;
import com.aresstack.spaceharvester.browser.runtime.Readiness;
import com.aresstack.spaceharvester.browser.runtime.RunningBrowser;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.player.PlaybackOptions;
import com.aresstack.spaceharvester.provingground.player.RunResult;
import com.aresstack.spaceharvester.provingground.player.StepResult;
import com.aresstack.spaceharvester.provingground.player.TestPlayer;
import com.aresstack.spaceharvester.provingground.recorder.Recorder;
import com.aresstack.spaceharvester.provingground.recorder.RecorderOptions;
import com.aresstack.spaceharvester.provingground.store.TestCaseFile;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The vertical prototype of Proving Ground (#1153) with a real browser: the browser runtime starts it, the ORIGINAL
 * Playwright recorder records a form flow on a page that renders asynchronously (like a React app), the test case is
 * saved, loaded and edited, then played back over the Playwright Java API with assertions and a trace, once green
 * and once with a failing assertion. Browser: PROVING_GROUND_TEST_CHANNEL (default firefox) and
 * PROVING_GROUND_TEST_EXECUTABLE; skipped without it. Only a local test server is visited; all data invented.
 */
@Tag("live")
class RecordAndPlayLiveTest {

    private static final String FORM = """
            <!doctype html><html lang="de"><head><meta charset="utf-8"><title>Anmeldung</title></head><body>
            <h1>Anmeldung</h1>
            <iframe title="Rahmen" src="/rahmen"></iframe>
            <div id="app"></div>
            <script>
              // Renders like a small React app: the form appears after the page loaded, the result after a delay.
              setTimeout(() => {
                document.getElementById('app').innerHTML = `
                  <label>Name <input name="name"></label>
                  <label>Passwort <input type="password" name="pw"></label>
                  <label>Land <select name="land"><option value="">-</option><option value="de">Deutschland</option>
                    <option value="at">Österreich</option></select></label>
                  <label><input type="checkbox" name="agb"> AGB gelesen</label>
                  <button type="button" id="save">Speichern</button>
                  <p role="status" id="status"></p>
                  <a href="/hilfe" target="_blank">Hilfe</a>`;
                document.getElementById('save').addEventListener('click', () => {
                  const name = document.querySelector('[name=name]').value;
                  const ok = document.querySelector('[name=pw]').value === 'geheim'
                      && document.querySelector('[name=land]').value === 'at'
                      && document.querySelector('[name=agb]').checked;
                  setTimeout(() => {
                    document.getElementById('status').textContent = ok ? 'Gespeichert: ' + name : 'Fehler';
                  }, 400);
                });
              }, 300);
            </script></body></html>
            """;
    private static final String HELP = """
            <!doctype html><html lang="de"><head><meta charset="utf-8"><title>Hilfe</title></head>
            <body><h1>Hilfe zur Anmeldung</h1></body></html>
            """;

    private static final String FRAME = """
            <!doctype html><html lang="de"><head><meta charset="utf-8"><title>Rahmen</title></head><body>
            <button type="button" onclick="this.textContent='Bestätigt'">Bestätigen</button></body></html>
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
        runtime = new BrowserRuntime(BrowserRuntimeConfig.of(channel, executable, data, Duration.ofMinutes(2)));
        Readiness readiness = runtime.readiness();
        assumeTrue(readiness.isReady(), "SKIPPED (environment-gated live test): " + readiness.render());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.startsWith("/hilfe") ? HELP : path.startsWith("/rahmen") ? FRAME : FORM)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void recordSaveLoadEditAndPlayBack() throws Exception {
        // 1-4: start the browser through the runtime, record with Playwright's recorder, take its actions over.
        TestCase recorded = new TestCase("Anmeldung");
        try (RunningBrowser browser = runtime.ephemeral(true, null, false);
             BrowserContext context = browser.browser().newContext();
             Recorder recorder = Recorder.start(context, recorded, RecorderOptions.defaults())) {
            Page page = context.newPage();
            page.navigate(base);
            recorder.processPending();
            page.getByLabel("Name").click();
            page.keyboard().type("Angelo");
            recorder.processPending();
            page.getByLabel("Passwort").click();
            page.keyboard().type("geheim");
            recorder.processPending();
            page.frameLocator("iframe").getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new com.microsoft.playwright.FrameLocator.GetByRoleOptions().setName("Bestätigen")).click();
            recorder.processPending();
            page.getByLabel("Land").selectOption("at");
            page.getByLabel("AGB gelesen").check();
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Speichern")).click();
            page.getByRole(com.microsoft.playwright.options.AriaRole.STATUS).waitFor();
            Page help = page.waitForPopup(() -> page.getByText("Hilfe").click());
            help.waitForLoadState();
            recorder.processPending();
            recorder.stop();
        }
        List<String> names = recorded.steps().stream().map(TestStep::name).toList();
        System.out.println("RECORDED " + names);
        recorded.steps().forEach(step -> System.out.println("  " + step.describe()));
        assertTrue(names.containsAll(List.of("navigate", "fill", "select", "check", "click")), names.toString());
        // Every recorded step carries the Java code Playwright's generator wrote for it: that is what is played back.
        recorded.steps().forEach(step -> System.out.println("  CODE " + step.code()));
        assertTrue(recorded.steps().stream().allMatch(step -> step.code() != null && !step.code().isBlank()), "Playwright's code for each step");
        TestStep password = recorded.steps().stream()
                .filter(step -> "fill".equals(step.name()) && step.selector().contains("Passwort")).findFirst()
                .orElseThrow();
        assertEquals("{{secret.Passwort}}", password.text(), "the password is not in the recording");
        assertTrue(password.code().contains("{{secret.Passwort}}"), password.code());
        assertEquals(2, recorded.steps().stream().filter(step -> "fill".equals(step.name())).count(),
                "one fill per field, both fields kept");
        assertTrue(recorded.steps().stream().anyMatch(step -> step.signal("popup") != null), "popup noticed");
        // Playwright 1.62 folds the frame path into the selector (microsoft/playwright#34157 is not an issue here).
        TestStep inFrame = recorded.steps().stream()
                .filter(step -> step.selector() != null && step.selector().contains("Bestätigen")).findFirst()
                .orElseThrow();
        assertTrue(inFrame.selector().contains("internal:control=enter-frame"), inFrame.selector());
        String nameStepId = recorded.steps().stream()
                .filter(step -> "fill".equals(step.name()) && step.selector().contains("Name")).findFirst()
                .orElseThrow().id();

        // 5: save and load again.
        Path file = data.resolve("tests").resolve("anmeldung" + TestCaseFile.EXTENSION);
        TestCaseFile.write(recorded, file);
        assertFalse(Files.readString(file).contains("geheim"), "no password in the file");
        TestCase loaded = TestCaseFile.read(file);
        assertEquals(recorded.size(), loaded.size());

        // 6: edit single steps: another name, and an assertion on the asynchronous result after the click.
        int nameStep = indexOf(loaded, "fill", "Name");
        loaded.replace(nameStep, loaded.step(nameStep).withText("Bea"));
        int save = indexOf(loaded, "click", "Speichern");
        String pageGuid = loaded.step(save).pageGuid();
        loaded.insert(save + 1, TestStep.assertText(pageGuid, "internal:role=status", "Gespeichert: Bea", false)
                .withCode("assertThat(page.getByRole(AriaRole.STATUS)).hasText(\"Gespeichert: Bea\");"));
        loaded.insert(save + 2, TestStep.assertChecked(pageGuid, "internal:label=\"AGB gelesen\"i", true)
                .withCode("assertThat(page.getByLabel(\"AGB gelesen\")).isChecked();"));
        // The new tab: a check on the page the click opened (the popup's page id from Playwright's signal).
        String helpPage = loaded.steps().stream().filter(step -> step.signal("popup") != null).findFirst()
                .orElseThrow().signal("popup").get("popupPageGuid").getAsString();
        loaded.add(TestStep.assertText(helpPage, "internal:role=heading", "Hilfe zur Anmeldung", false)
                .withCode("assertThat(page1.getByRole(AriaRole.HEADING)).hasText(\"Hilfe zur Anmeldung\");"));
        TestCaseFile.write(loaded, file);
        TestCase edited = TestCaseFile.read(file);
        // The step ids survive editing, inserting and saving: subject matter keeps its link to the action.
        assertEquals(nameStep, edited.indexOf(nameStepId));
        assertEquals("Bea", edited.step(edited.indexOf(nameStepId)).text());

        // 7-8: play back over the Playwright Java API, with trace; green.
        Path trace = data.resolve("runs").resolve("trace.zip");
        PlaybackOptions options = PlaybackOptions.defaults()
                .withVariables(Map.of("secret.Passwort", "geheim"))
                .withTrace(trace)
                .withScreenshotOnFailure(data.resolve("runs").resolve("fehler.png"));
        RunResult green = new TestPlayer().playInFreshBrowser(runtime, true, null, edited, options);
        System.out.println(green.render());
        assertTrue(green.passed(), green.render());
        assertTrue(Files.size(trace) > 0, "trace written");
        assertFalse(green.render().contains("geheim"), "no password in the result");

        // A wrong expectation fails at exactly that step, the rest is skipped, screenshot and trace are there.
        TestCase wrong = TestCaseFile.read(file);
        int assertion = indexOf(wrong, "assertText", "status");
        wrong.replace(assertion, wrong.step(assertion).withText("Gespeichert: Carl"));
        Path failedTrace = data.resolve("runs").resolve("trace-fehler.zip");
        RunResult red = new TestPlayer().playInFreshBrowser(runtime, true, null, wrong,
                options.withTrace(failedTrace).withActionTimeout(Duration.ofSeconds(5)));
        System.out.println(red.render());
        assertFalse(red.passed());
        StepResult failure = red.failure();
        assertNotNull(failure);
        assertEquals(assertion, failure.index());
        assertTrue(red.steps().subList(assertion + 1, red.steps().size()).stream()
                .allMatch(step -> step.status() == StepResult.Status.SKIPPED));
        assertNotNull(red.screenshot());
        assertTrue(Files.size(red.screenshot()) > 0);
        assertTrue(Files.size(failedTrace) > 0);
    }

    private static int indexOf(TestCase test, String name, String selectorPart) {
        for (int i = 0; i < test.size(); i++) {
            TestStep step = test.step(i);
            if (name.equals(step.name()) && step.selector() != null && step.selector().contains(selectorPart)) {
                return i;
            }
        }
        throw new AssertionError("no " + name + " on " + selectorPart + " in " + test.steps());
    }
}
