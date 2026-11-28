package com.aresstack.spaceharvester.browser.runtime;

import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression for playwright4j#79: the installed Firefox starts without the back-forward cache, like Camoufox, so a
 * history traversal settles. Without the pref, back() timed out on standard Firefox (A/B test of 26 Nov 2026). The
 * prefs go into the runtime's own profile only, never into the user's Firefox profile.
 */
class BrowserLaunchOptionsTest {

    @TempDir
    Path temp;

    @Test
    void theInstalledFirefoxStartsWithoutTheBackForwardCache() {
        Path executable = temp.resolve("firefox");
        BrowserType.LaunchOptions launch = BrowserRuntime.firefoxLaunchOptions(executable, true);
        assertEquals(0, launch.firefoxUserPrefs.get(FirefoxSessionPrefs.NO_BFCACHE));
        assertEquals(Boolean.TRUE, launch.firefoxUserPrefs.get(FirefoxSessionPrefs.ALWAYS_ACCEPT_REPOST));
        assertEquals(BrowserRuntime.FIREFOX_BIDI_CHANNEL, launch.channel);

        BrowserType.LaunchPersistentContextOptions persistent = BrowserRuntime.firefoxPersistentOptions(executable,
                false);
        assertEquals(0, persistent.firefoxUserPrefs.get(FirefoxSessionPrefs.NO_BFCACHE));
        assertEquals(Boolean.TRUE, persistent.firefoxUserPrefs.get(FirefoxSessionPrefs.ALWAYS_ACCEPT_REPOST));
        assertEquals(BrowserRuntime.FIREFOX_BIDI_CHANNEL, persistent.channel);
        assertEquals(executable, persistent.executablePath);
        assertEquals(Boolean.FALSE, persistent.acceptDownloads);
        assertNull(persistent.viewportSize.orElse(null), "a real window, no viewport emulation");
    }

    /** A test run (Proving Ground) never re-submits a form unasked: the "resend data" pref stays out. */
    @Test
    void aTestRunStartsWithoutAcceptingTheResendPrompt() {
        BrowserType.LaunchOptions launch = BrowserRuntime.firefoxLaunchOptions(temp.resolve("firefox"), true, false);
        assertEquals(0, launch.firefoxUserPrefs.get(FirefoxSessionPrefs.NO_BFCACHE));
        assertFalse(launch.firefoxUserPrefs.containsKey(FirefoxSessionPrefs.ALWAYS_ACCEPT_REPOST));
    }
}
