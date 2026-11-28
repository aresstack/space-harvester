package com.aresstack.spaceharvester.provingground;

import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntimeConfig;
import com.aresstack.spaceharvester.browser.runtime.Readiness;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestStep;
import com.aresstack.spaceharvester.provingground.player.PlaybackOptions;
import com.aresstack.spaceharvester.provingground.player.RunResult;
import com.aresstack.spaceharvester.provingground.player.TestPlayer;
import com.microsoft.playwright.Browser;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Screenshot comparison in a real browser, played as Playwright Java code: the first run writes the reference and
 * fails, the second passes, a changed page fails with a diff image; once on the whole page, once on one element.
 * Browser: PROVING_GROUND_TEST_CHANNEL (default firefox) and PROVING_GROUND_TEST_EXECUTABLE; skipped without it.
 * Only a local test server is visited.
 */
@Tag("live")
class ScreenshotLiveTest {

    private static final String PAGE = """
            <!doctype html><html lang="de"><head><meta charset="utf-8"><title>Konto</title>
            <style>body{margin:0;font:16px sans-serif} #box{width:120px;height:60px;margin:20px;background:%s}</style>
            </head><body><div id="box" role="img" aria-label="Kasten"></div></body></html>
            """;

    @TempDir
    Path data;

    private BrowserRuntime runtime;
    private HttpServer server;
    private String base;
    private volatile String colour = "#3060c0";

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
            byte[] body = PAGE.formatted(colour).getBytes(StandardCharsets.UTF_8);
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
    void firstRunWritesTheReferenceSecondPassesAChangedPageFails() throws Exception {
        TestCase test = new TestCase("Konto", List.of(
                TestStep.navigate("p", "{{start}}").withCode("Page page = context.newPage();\n"
                        + "page.navigate(\"{{start}}\");"),
                TestStep.assertScreenshot("p", null, "konto"),
                TestStep.assertScreenshot("p", "internal:role=img[name=\"Kasten\"i]", "kasten")));
        Path references = data.resolve("konto-snapshots");
        Path output = data.resolve("ergebnis");
        PlaybackOptions options = PlaybackOptions.defaults().withVariables(java.util.Map.of("start", base))
                .withScreenshotOnFailure(output.resolve("fehler.png")).withScreenshotReferences(references);
        Browser.NewContextOptions viewport = new Browser.NewContextOptions().setViewportSize(400, 200);
        TestPlayer player = new TestPlayer();

        RunResult first = player.playInFreshBrowser(runtime, true, viewport, test, options);
        assertFalse(first.passed(), first.render());
        assertTrue(first.failure().error().contains("Referenzbild fehlte"), first.render());
        // the element's reference is written on the next run (the first failing step ends the run)
        RunResult second = player.playInFreshBrowser(runtime, true, viewport, test, options);
        assertFalse(second.passed(), second.render());
        assertTrue(second.failure().description().contains("kasten"), second.render());
        RunResult green = player.playInFreshBrowser(runtime, true, viewport, test, options);
        assertTrue(green.passed(), green.render());
        try (var files = Files.list(references)) {
            assertTrue(files.allMatch(file -> file.getFileName().toString().matches("(konto|kasten)-\\w+-\\w+\\.png")),
                    "Playwrights Dateinamen <name>-<browser>-<platform>.png");
        }

        colour = "#c03030";
        RunResult changed = player.playInFreshBrowser(runtime, true, viewport, test, options);
        assertFalse(changed.passed(), changed.render());
        assertTrue(changed.failure().error().contains("Pixel weichen ab"), changed.render());
        try (var files = Files.list(output)) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().endsWith("-diff.png")));
        }
    }
}
