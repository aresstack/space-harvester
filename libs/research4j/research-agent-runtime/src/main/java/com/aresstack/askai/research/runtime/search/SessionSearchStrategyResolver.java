package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.function.LongSupplier;

/**
 * Resolves the SAME productive {@link SearchStrategy} the research loop uses, so a user-triggered manual search
 * and the autonomous loop share ONE strategy resolution instead of the strategy being trapped inside the loop:
 * an API-provider strategy chosen at session start (browser-free), else the default legacy-browser SERP
 * strategy built over the given browser {@link ToolInvoker}. Returns {@code null} only when neither is
 * available (no API provider configured AND no browser) — the caller then surfaces an honest failure, never a
 * silent no-op. This is a pure resolver: it is phase-independent and never enforces any MCP tool policy.
 */
public final class SessionSearchStrategyResolver {

    private SessionSearchStrategyResolver() {
    }

    public static SearchStrategy resolve(SearchStrategy apiProviderStrategy, boolean hasBrowser,
                                         ToolInvoker browser, LegacyBrowserSearchSettings settings,
                                         StructuredInferencePort inference, LongSupplier clock) {
        if (apiProviderStrategy != null) {
            // An API_PROVIDER session strategy needs no browser and no layout repair.
            return apiProviderStrategy;
        }
        if (hasBrowser && browser != null) {
            return inference != null
                    ? LegacyBrowserSearchStrategyFactory.createDefault(browser, settings, clock, inference)
                    : LegacyBrowserSearchStrategyFactory.createDefault(browser, settings, clock);
        }
        return null;
    }
}
