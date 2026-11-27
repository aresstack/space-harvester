package com.aresstack.spaceharvester.browser.runtime;

import com.google.gson.JsonParser;
import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Chrome/Edge with an unpacked extension: launch options and toolbar pin (moved from Ask AI). */
class ChromeExtensionTest {

    private static final String EXTENSION_ID = "abcdefghijklmnopabcdefghijklmnop";

    @TempDir
    Path temp;

    @Test
    void theBrowserStartsWithTheProtocolLoaderAndExtensionsOn() {
        BrowserType.LaunchPersistentContextOptions options = ChromeExtension.options("msedge", true);
        assertEquals("msedge", options.channel);
        assertEquals(List.of("--enable-unsafe-extension-debugging"), options.args);
        assertEquals(List.of("--disable-extensions"), options.ignoreDefaultArgs);
        assertEquals(Boolean.FALSE, options.acceptDownloads);
        assertFalse(options.args.toString().contains("--load-extension"),
                "--load-extension is ignored by branded Chrome since 137");
    }

    @Test
    void aFreshProfilePinsTheToolbarButton() throws Exception {
        Path profile = temp.resolve("profile");
        ChromeExtension.pinToolbarButton(profile, EXTENSION_ID);
        Path preferences = profile.resolve("Default").resolve("Preferences");
        assertEquals("[\"" + EXTENSION_ID + "\"]", JsonParser.parseString(Files.readString(preferences,
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("extensions").get("pinned_extensions")
                .toString());
        // A profile that already ran keeps its own preferences.
        Files.writeString(preferences, "{\"mine\":true}");
        ChromeExtension.pinToolbarButton(profile, EXTENSION_ID);
        assertEquals("{\"mine\":true}", Files.readString(preferences));
    }
}
