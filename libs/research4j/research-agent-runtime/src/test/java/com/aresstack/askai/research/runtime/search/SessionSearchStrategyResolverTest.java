package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import org.junit.Test;

import java.util.function.LongSupplier;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** The strategy resolution shared by the loop and the manual search. */
public class SessionSearchStrategyResolverTest {

    private static final LongSupplier CLOCK = new LongSupplier() {
        public long getAsLong() {
            return 0L;
        }
    };

    @Test
    public void anApiProviderStrategyIsUsedDirectlyWithoutABrowser() {
        SearchStrategy provider = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                return InitialSearchResult.empty(java.util.Collections.<String>emptyList());
            }
        };
        SearchStrategy resolved = SessionSearchStrategyResolver.resolve(
                provider, false, null, LegacyBrowserSearchDefaults.create(), null, CLOCK);
        assertSame("the session API strategy is returned as-is", provider, resolved);
    }

    @Test
    public void withoutAnApiStrategyButWithABrowserTheLegacyDefaultIsBuilt() {
        ToolInvoker browser = new ToolInvoker() {
            public String call(String tool, java.util.Map<String, Object> args) {
                return "{}";
            }
        };
        SearchStrategy resolved = SessionSearchStrategyResolver.resolve(
                null, true, browser, LegacyBrowserSearchDefaults.create(), null, CLOCK);
        assertNotNull("a legacy-browser strategy is built when a browser exists", resolved);
    }

    @Test
    public void withNeitherApiNorBrowserItIsUnavailable() {
        assertNull(SessionSearchStrategyResolver.resolve(
                null, false, null, LegacyBrowserSearchDefaults.create(), null, CLOCK));
    }
}
