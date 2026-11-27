package com.aresstack.spaceharvester.browser.runtime;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real browser starts through the runtime. In CI it runs against the installed standard Firefox (acceptance of
 * ADR 0046: no additional browser install), locally against whatever {@code BROWSER_RUNTIME_TEST_CHANNEL}
 * (firefox, chrome, msedge, camoufox; default firefox) and {@code BROWSER_RUNTIME_TEST_EXECUTABLE} name. Skips when
 * that browser is not there. Only a local test server is visited.
 */
@Tag("live")
class BrowserRuntimeLiveTest {

    /** The id the manifest key of the test extension fixes (test resources, test-extension/manifest.json). */
    private static final String TEST_EXTENSION_ID = "ofpaeodcbjebddnanmlejfdbngidmjlg";
    private static final double NAVIGATION_MILLIS = Duration.ofSeconds(30).toMillis();

    @TempDir
    Path data;

    private BrowserRuntime runtime;
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        BrowserChannel channel = BrowserChannel.of(System.getenv().getOrDefault("BROWSER_RUNTIME_TEST_CHANNEL",
                BrowserChannel.FIREFOX.id()));
        String executable = System.getenv().getOrDefault("BROWSER_RUNTIME_TEST_EXECUTABLE", "");
        runtime = new BrowserRuntime(BrowserRuntimeConfig.of(channel, executable, data, Duration.ofMinutes(2)));
        Readiness readiness = runtime.readiness();
        assumeTrue(readiness.isReady(), "SKIPPED (environment-gated live test): " + readiness.render());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String name = exchange.getRequestURI().getPath().replace("/", "");
            byte[] body = ("<!doctype html><html><head><title>" + name + "</title></head><body><p>page " + name
                    + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
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

    /** playwright4j#79: without the back-forward cache a history traversal settles and page.url() follows it. */
    @Test
    void backNavigationSettlesOnAPersistentProfile() throws Exception {
        try (RunningBrowser browser = runtime.persistent("chat-back", true, null)) {
            BrowserContext context = browser.context();
            assertNotNull(context);
            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            assertBackSettles(page);
        }
    }

    @Test
    void backNavigationSettlesOnAnEphemeralBrowser() throws Exception {
        try (RunningBrowser browser = runtime.ephemeral(true, null)) {
            assertNull(browser.context(), "contexts of an ephemeral browser come from the caller");
            BrowserContext context = browser.browser().newContext();
            assertBackSettles(context.newPage());
            context.close();
        }
    }

    private void assertBackSettles(Page page) {
        page.setDefaultTimeout(NAVIGATION_MILLIS);
        page.navigate(base + "first");
        page.navigate(base + "second");
        page.goBack();
        assertEquals(base + "first", page.url());
        assertEquals("first", page.title());
        page.goForward();
        assertEquals(base + "second", page.url());
    }

    /** research4j #1: a lasting profile of its own per key; sign-ins (cookies) survive a restart. */
    @Test
    void theProfileOfAKeyLastsAcrossRestartsAndIsLockedWhileItRuns() throws Exception {
        Path profile;
        try (RunningBrowser first = runtime.persistent("chat-a", true, null)) {
            profile = first.profileDirectory();
            assertTrue(profile.startsWith(data.resolve("browser-profiles").resolve(runtime.config().channel().id())),
                    "the profile lies in the host's data folder, never in the user's browser profile: " + profile);
            Page page = first.context().pages().isEmpty() ? first.context().newPage() : first.context().pages().get(0);
            page.navigate(base + "login");
            page.evaluate("() => { document.cookie = 'signedIn=yes; max-age=86400; path=/'; }");
            try (RunningBrowser overlapping = runtime.persistent("chat-a", true, null)) {
                assertFalse(profile.equals(overlapping.profileDirectory()),
                        "a second browser of the same profile never shares it");
            }
        }
        try (RunningBrowser again = runtime.persistent("chat-a", true, null)) {
            assertEquals(profile, again.profileDirectory());
            // Chromium writes its cookie store only every 30 s and not on close (measured 27.11.2026 with Chromium:
            // the cookie survived only after a 35 s wait), so the sign-in check holds for Firefox and Camoufox.
            assumeTrue(runtime.config().channel().firefoxFamily(),
                    "Chromium does not flush its cookie store on close");
            Page page = again.context().pages().isEmpty() ? again.context().newPage() : again.context().pages().get(0);
            page.navigate(base + "again");
            assertTrue(String.valueOf(page.evaluate("() => document.cookie")).contains("signedIn=yes"),
                    "the sign-in survived the restart");
        }
    }

    /** Chrome/Edge: an unpacked extension is loaded over the DevTools protocol and runs in the pages. */
    @Test
    void chromeAndEdgeLoadTheExtensionOnAPersistentProfile() throws Exception {
        assumeTrue(!runtime.config().channel().firefoxFamily(), "the extension path is for Chrome/Edge");
        try (RunningBrowser browser = runtime.persistent("chat-extension", true, testExtension())) {
            assertTrue(browser.addonLoaded(), "extension not loaded: " + browser.addonFailure());
            Page page = browser.context().pages().isEmpty() ? browser.context().newPage()
                    : browser.context().pages().get(0);
            page.navigate(base + "extension");
            page.waitForSelector("html[data-runtime-test-extension=loaded]");
        }
    }

    /** An add-on that cannot be loaded is reported, never dropped silently; the browser still runs. */
    @Test
    void anAddonTheBrowserCannotLoadIsReported() throws Exception {
        try (RunningBrowser browser = runtime.ephemeral(true, testExtension())) {
            if (runtime.config().channel() == BrowserChannel.CAMOUFOX) {
                return; // Camoufox loads its add-ons at startup; the ephemeral path supports it
            }
            assertFalse(browser.addonLoaded());
            assertNotNull(browser.addonFailure());
            assertTrue(browser.browser().isConnected());
        }
    }

    private static BrowserAddon testExtension() {
        return new BrowserAddon() {
            @Override
            public Path prepare(Path parent, boolean chromium) throws IOException {
                Path directory = Files.createDirectories(parent.resolve("test-extension"));
                for (String file : new String[]{"manifest.json", "mark.js"}) {
                    try (InputStream in = BrowserRuntimeLiveTest.class.getResourceAsStream("/test-extension/" + file)) {
                        Files.copy(in, directory.resolve(file));
                    }
                }
                return directory;
            }

            @Override
            public String chromiumExtensionId() {
                return TEST_EXTENSION_ID;
            }
        };
    }
}
