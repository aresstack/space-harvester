package com.aresstack.askai.browser.playwright;

import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for playwright4j#79: the installed Firefox starts without the back-forward cache, like Camoufox, so a
 * history traversal settles. Without the pref, back() timed out on standard Firefox (A/B test of 26 Nov 2026).
 */
class LocalFirefoxOptionsTest {

    @Test
    void theInstalledFirefoxStartsWithoutTheBackForwardCache() throws Exception {
        Path executable = Files.createTempFile("firefox", "");
        String before = BrowserExecutables.configured(BrowserExecutables.FIREFOX);
        BrowserExecutables.use(BrowserExecutables.FIREFOX, executable.toString());
        try {
            BrowserType.LaunchOptions options = Playwright4jDriver.localFirefoxOptions(true);
            assertEquals(0, options.firefoxUserPrefs.get(FirefoxSessionPrefs.NO_BFCACHE));
            assertEquals(Boolean.TRUE, options.firefoxUserPrefs.get(FirefoxSessionPrefs.ALWAYS_ACCEPT_REPOST));
            assertEquals(Playwright4jDriver.FIREFOX_BIDI_CHANNEL, options.channel);
        } finally {
            BrowserExecutables.use(BrowserExecutables.FIREFOX, before);
            Files.deleteIfExists(executable);
        }
    }
}
