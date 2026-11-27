package com.aresstack.askai.browser.playwright;

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
 * Chrome/Edge (issue #23, stage 4): how the chat browser gets the AskAI Research extension
 * (docs/research-browser-addon.md).
 *
 * <ul>
 *   <li>The browser starts with a persistent profile of its own (a temporary directory per start). Playwright's
 *   {@code browser.newContext()} contexts are off-the-record profiles, in which an extension can list the tabs but
 *   not script them (measured with Chrome for Testing 153), so the HUD and the page reads need the profile's own
 *   context.</li>
 *   <li>The extension is loaded over the DevTools protocol, {@code Extensions.loadUnpacked}, which needs the switch
 *   {@code --enable-unsafe-extension-debugging} and Playwright's pipe connection. The command-line switch
 *   {@code --load-extension} is ignored by branded Chrome since version 137, so it is not used.</li>
 *   <li>Its toolbar button is pinned through the profile's {@code Preferences} written before the start, so the badge
 *   (countdown, pause mark) is visible without opening the extensions menu.</li>
 * </ul>
 */
final class ChromeResearchExtension {

    /** The switch that lets the DevTools protocol load an unpacked extension. */
    static final String LOAD_SWITCH = "--enable-unsafe-extension-debugging";
    /** Playwright's default switch that would keep every extension off. */
    static final String DISABLE_EXTENSIONS = "--disable-extensions";
    /** The profile directory inside the per-start root (the extension lies next to it). */
    static final String PROFILE = "profile";

    private ChromeResearchExtension() {
    }

    /** The persistent-context options of the chat browser on chrome/msedge with the extension. */
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
    static void pinToolbarButton(Path profile) throws IOException {
        Path preferences = profile.resolve("Default").resolve("Preferences");
        if (Files.exists(preferences)) {
            return; // a profile that already ran keeps its own toolbar
        }
        JsonArray pinned = new JsonArray();
        pinned.add(ResearchAddon.CHROME_ID);
        JsonObject extensions = new JsonObject();
        extensions.add("pinned_extensions", pinned);
        JsonObject root = new JsonObject();
        root.add("extensions", extensions);
        Files.createDirectories(preferences.getParent());
        Files.writeString(preferences, root.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Load the extension into the running browser. @return the extension id Chrome reports (always
     * {@link ResearchAddon#CHROME_ID}, fixed by the manifest key)
     * @throws RuntimeException when the browser refuses (e.g. a build without the protocol command)
     */
    static String load(Browser browser, Path extensionDirectory) {
        if (browser == null) {
            throw new IllegalStateException("no browser object for the DevTools protocol");
        }
        CDPSession session = browser.newBrowserCDPSession();
        try {
            JsonObject parameters = new JsonObject();
            parameters.addProperty("path", extensionDirectory.toAbsolutePath().toString());
            JsonObject answer = session.send("Extensions.loadUnpacked", parameters);
            String id = answer != null && answer.has("id") ? answer.get("id").getAsString() : "";
            if (!ResearchAddon.CHROME_ID.equals(id)) {
                throw new IllegalStateException("the browser loaded the extension as '" + id + "', expected "
                        + ResearchAddon.CHROME_ID);
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
