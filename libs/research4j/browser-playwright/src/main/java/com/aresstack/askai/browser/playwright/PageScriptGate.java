package com.aresstack.askai.browser.playwright;

import com.microsoft.playwright.Page;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one door through which AskAI runs a script in a page's main world ({@code page.evaluate}). Scripts in the
 * main world are what bot detection sees (issue #22: Bing keeps its results hidden for AskAI's Camoufox, but not for
 * Argos, which never evaluates in the main world), so every call names its purpose here, and in script-free mode
 * the door is shut: the call fails with {@link PageScriptForbiddenException} before anything reaches the browser.
 *
 * <p>Script-free mode applies to Camoufox only and is switched on with {@code -D}{@value #PROPERTY}{@code =true}.
 * It defaults to OFF while page reads (structured capture, fingerprint, consent and challenge detection, the
 * anchor list of the page state) still use {@code page.evaluate}; they move into the research add-on (issue #23,
 * stage 2), after which the default flips. Interactions (clicks, input) never use this door at all: they go
 * through locators, {@code boundingBox} and real mouse/keyboard input.</p>
 *
 * <p>Chrome/Edge are never script-free: there is no extension for them yet (stage 4), so the in-page HUD overlay
 * and the reads stay there.</p>
 */
final class PageScriptGate {

    /** System property: {@code true} makes every main-world script fail in Camoufox (default {@code false}). */
    static final String PROPERTY = "askai.browser.scriptFree";

    private final boolean scriptFree;
    /** Each refused purpose is logged once per driver, so a shut door is visible without flooding the log. */
    private final Set<String> refusedPurposes = new LinkedHashSet<String>();

    PageScriptGate(boolean scriptFree) {
        this.scriptFree = scriptFree;
    }

    /** The gate for a driver: script-free only for Camoufox and only when {@value #PROPERTY} is {@code true}. */
    static PageScriptGate forBrowser(boolean camoufox) {
        return new PageScriptGate(camoufox && Boolean.parseBoolean(System.getProperty(PROPERTY, "false")));
    }

    boolean scriptFree() {
        return scriptFree;
    }

    /** Throws when the door is shut; {@code purpose} names the caller for the exception and the log. */
    void check(String purpose) {
        if (!scriptFree) {
            return;
        }
        synchronized (refusedPurposes) {
            if (refusedPurposes.add(purpose)) {
                System.err.println("[browser-mcp] script-free mode: page script '" + purpose
                        + "' refused (nothing was run in the page)");
            }
        }
        throw new PageScriptForbiddenException(purpose);
    }

    /** {@code page.evaluate(script)} unless the door is shut. */
    Object evaluate(Page page, String script, String purpose) {
        check(purpose);
        return page.evaluate(script);
    }

    /** A main-world script was refused because the driver runs script-free. */
    static final class PageScriptForbiddenException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PageScriptForbiddenException(String purpose) {
            super("SCRIPT_FREE — page script '" + purpose + "' is not allowed (-D" + PROPERTY + "=true)");
        }
    }
}
