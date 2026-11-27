package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

/**
 * Where Research4j gets its browsers from. The host implements this port over its browser runtime: which browser
 * (channel), which program file, where the profiles live, how the driver process starts and which workarounds a
 * browser needs are the runtime's configuration, never decided here. Research4j only says what it needs: a browser
 * with a lasting profile of its own for one key (a chat), or a throw-away one (search, workers), with or without a
 * window, and the research add-on it packages itself.
 *
 * <p>Every call runs on the thread that will own the returned objects; Playwright Java is not thread-safe.</p>
 */
public interface BrowserLauncher {

    /**
     * Whether a browser could start here, without starting one: {@code READY …} or the specific reason why not
     * ({@code INCOMPATIBLE_DRIVER}, {@code DRIVER_BUNDLE_NOT_FOUND}, {@code BROWSER_NOT_INSTALLED}).
     */
    PlaywrightReadiness readiness();

    /**
     * A browser on a lasting profile of its own for {@code profileKey}: sign-ins of one key never show up under
     * another. The returned context is that profile's; it has its first tab already.
     *
     * @param profileKey the owner of the profile (a chat); {@code null} = a temporary profile, deleted on close
     * @param headless   without a window
     * @param addon      the research add-on to load, or {@code null}; see {@link LaunchedBrowser#addonLoaded()}
     */
    LaunchedBrowser persistent(String profileKey, boolean headless, AddonPackager addon) throws BrowserException;

    /**
     * A browser process with a throw-away profile; the caller creates its contexts with
     * {@link LaunchedBrowser#browser()}. {@link LaunchedBrowser#context()} is {@code null}.
     */
    LaunchedBrowser ephemeral(boolean headless, AddonPackager addon) throws BrowserException;
}
