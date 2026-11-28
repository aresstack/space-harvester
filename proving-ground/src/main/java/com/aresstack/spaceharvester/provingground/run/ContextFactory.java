package com.aresstack.spaceharvester.provingground.run;

import com.aresstack.spaceharvester.provingground.model.TestUser;
import com.microsoft.playwright.BrowserContext;

/**
 * Opens the browser context of a user: one per user and test case, so users never share cookies and every test case
 * starts from a defined state. The caller decides the options (viewport, locale, a stored login state as Playwright's
 * storage state ...).
 */
@FunctionalInterface
public interface ContextFactory {

    /** A new context for {@code user}; null = a context without a user. */
    BrowserContext open(TestUser user);
}
