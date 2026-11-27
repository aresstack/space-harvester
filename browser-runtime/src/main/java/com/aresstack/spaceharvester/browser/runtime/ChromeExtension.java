package com.aresstack.spaceharvester.browser.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Chrome/Edge: how a browser gets an unpacked extension (moved from Ask AI, research4j docs/research-browser-addon.md).
 *
 * <ul>
 *   <li>The browser starts with a profile of its own. Playwright's {@code browser.newContext()} contexts are
 *   off-the-record profiles, in which an extension can list the tabs but not script them (measured with Chrome for
 *   Testing 153), so the extension needs the profile's own context.</li>
 *   <li>The extension is loaded over the DevTools protocol, {@code Extensions.loadUnpacked}, which needs the switch
 *   {@code --enable-unsafe-extension-debugging} and Playwright's pipe connection. The command-line switch
 *   {@code --load-extension} is ignored by branded Chrome since version 137, so it is not used.</li>
 *   <li>Its toolbar button is pinned through the profile's {@code Preferences} written before the first start, so
 *   its badge is visible without opening the extensions menu.</li>
 * </ul>
 */
final class ChromeExtension {

    /** The switch that lets the DevTools protocol load an unpacked extension. */
    static final String LOAD_SWITCH = "--enable-unsafe-extension-debugging";
    /** Playwright's default switch that would keep every extension off. */
    static final String DISABLE_EXTENSIONS = "--disable-extensions";

    private ChromeExtension() {
    }

    /** The persistent-context options of a chrome/msedge browser that loads an extension. */
    static BrowserType.LaunchPersistentContextOptions options(String channel, boolean headless) {
        return new BrowserType.LaunchPersistentContextOptions()
                .setChannel(channel)
                .setHeadless(headless)
                .setAcceptDownloads(false)
                .setArgs(List.of(LOAD_SWITCH))
                .setIgnoreDefaultArgs(List.of(DISABLE_EXTENSIONS));
    }

    /**
     * Pin the extension's toolbar button in a profile that has not started yet: {@code Default/Preferences} with
     * {@code extensions.pinned_extensions = [id]}. Chrome keeps the rest of the preferences at their defaults.
     */
    static void pinToolbarButton(Path profile, String extensionId) throws IOException {
        Path preferences = profile.resolve("Default").resolve("Preferences");
        if (Files.exists(preferences)) {
            return; // a profile that already ran keeps its own toolbar
        }
        JsonArray pinned = new JsonArray();
        pinned.add(extensionId);
        JsonObject extensions = new JsonObject();
        extensions.add("pinned_extensions", pinned);
        JsonObject root = new JsonObject();
        root.add("extensions", extensions);
        Files.createDirectories(preferences.getParent());
        Files.writeString(preferences, root.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Load the extension into the running browser. @return the extension id Chrome reports
     * @throws RuntimeException when the browser refuses (e.g. a build without the protocol command) or reports another
     *                          id than {@code expectedId}
     */
    static String load(Browser browser, Path extensionDirectory, String expectedId) {
        if (browser == null) {
            throw new IllegalStateException("no browser object for the DevTools protocol");
        }
        CDPSession session = browser.newBrowserCDPSession();
        try {
            JsonObject parameters = new JsonObject();
            parameters.addProperty("path", extensionDirectory.toAbsolutePath().toString());
            JsonObject answer = session.send("Extensions.loadUnpacked", parameters);
            String id = answer != null && answer.has("id") ? answer.get("id").getAsString() : "";
            if (!expectedId.equals(id)) {
                throw new IllegalStateException("the browser loaded the extension as '" + id + "', expected "
                        + expectedId);
            }
            return id;
        } finally {
            try {
                session.detach();
            } catch (RuntimeException ignored) {
                // the session is only needed for the one command
            }
        }
    }
}
