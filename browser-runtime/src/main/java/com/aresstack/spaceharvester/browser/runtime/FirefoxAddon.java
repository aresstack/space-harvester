package com.aresstack.spaceharvester.browser.runtime;

import com.aresstack.playwright4j.bidi.FirefoxExtensions;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.nio.file.Path;

/**
 * The add-on in the installed Firefox: Firefox installs it after the start as a temporary add-on over Playwright's
 * WebDriver BiDi session ({@link FirefoxExtensions}), unsigned and for every tab of the browser. A temporary add-on
 * ends with the browser, so every start installs it again; nothing is written into the user's Firefox.
 */
final class FirefoxAddon {

    private FirefoxAddon() {
    }

    /**
     * Installs the add-on at {@code location} into the running browser: through the first tab of a persistent
     * {@code context}, or for an ephemeral {@code browser} through a short-lived context of its own (the add-on belongs
     * to the browser, not to a context). Answers the add-on id.
     */
    static String install(BrowserContext context, Browser browser, Path location) {
        if (context != null) {
            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            return FirefoxExtensions.install(page, location);
        }
        BrowserContext helper = browser.newContext();
        try {
            return FirefoxExtensions.install(helper.newPage(), location);
        } finally {
            helper.close();
        }
    }
}
