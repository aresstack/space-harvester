package com.aresstack.askai.browser.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Playwright;

/**
 * One started browser, as the original Playwright objects. Whoever launched it owns it and ends it with
 * {@link #close()}; nothing else closes the browser, the profile or the driver process.
 */
public interface LaunchedBrowser {

    Playwright playwright();

    /** The browser process; for a persistent context Playwright may report none ({@code null}). */
    Browser browser();

    /** The profile's context of a persistent launch, {@code null} for an ephemeral one. */
    BrowserContext context();

    /**
     * Whether the browser is Camoufox: page reads then run script-free and contexts take no emulation, because the
     * fingerprint owns window and screen (as in Ask AI).
     */
    boolean camoufox();

    /**
     * Whether the research add-on runs as a Chromium extension: its service worker's requests to the bridge pass
     * through context routing (Firefox does not route them).
     */
    boolean chromiumExtension();

    /**
     * Whether the add-on handed to the launch is loaded. {@code false} with an add-on means it could not be
     * installed; {@link #addonFailure()} says why.
     */
    boolean addonLoaded();

    /** Why the add-on is not loaded, or {@code null}. */
    String addonFailure();

    /** Orderly end: context, browser, driver process, then the profile (a temporary one is deleted). Idempotent. */
    void close();

    /**
     * Emergency end from a stuck owner: cut the driver connection and release the profile without closing context or
     * browser over the stuck connection. Never blocks the caller for long.
     */
    void abandon();
}
