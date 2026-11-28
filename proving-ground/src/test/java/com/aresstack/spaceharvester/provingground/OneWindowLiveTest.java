package com.aresstack.spaceharvester.provingground;

import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntimeConfig;
import com.aresstack.spaceharvester.browser.runtime.Readiness;
import com.aresstack.spaceharvester.browser.runtime.RunningBrowser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Firefox opens a window of its own at every start, and Playwright's {@code newPage()} opens another one: playback and
 * recording showed two windows. Started for a test run, the start window is a page Playwright sees, and it closes as
 * soon as the test's window is open.
 */
@Tag("live")
class OneWindowLiveTest {

    @TempDir
    Path data;

    @Test
    void theStartWindowClosesOnceTheTestsWindowIsOpenAndTheBrowserKeepsRunning() throws Exception {
        BrowserChannel channel = BrowserChannel.of(System.getenv().getOrDefault("PROVING_GROUND_TEST_CHANNEL",
                BrowserChannel.FIREFOX.id()));
        BrowserRuntime runtime = new BrowserRuntime(BrowserRuntimeConfig.of(channel,
                System.getenv().getOrDefault("PROVING_GROUND_TEST_EXECUTABLE", ""), data, Duration.ofMinutes(2)));
        Readiness readiness = runtime.readiness();
        assumeTrue(readiness.isReady(), "SKIPPED (environment-gated live test): " + readiness.render());

        try (RunningBrowser browser = runtime.forTestRun(false, false)) {
            assertTrue(browser.context().pages().size() >= 1, "the start window is a page Playwright sees");

            BrowserContext test = browser.newTestContext();
            Page page = test.newPage();
            page.navigate("data:text/html,<title>Test</title>");
            assertEquals(0, browser.context().pages().size(), "the start window is closed");
            assertEquals("Test", page.title(), "the browser keeps running");

            BrowserContext other = browser.newTestContext();
            Page second = other.newPage();
            second.navigate("data:text/html,<title>Anderer Benutzer</title>");
            assertEquals("Anderer Benutzer", second.title());
            assertEquals(1, test.pages().size(), "one window per user");
            assertEquals(1, other.pages().size(), "one window per user");
        }
    }
}
