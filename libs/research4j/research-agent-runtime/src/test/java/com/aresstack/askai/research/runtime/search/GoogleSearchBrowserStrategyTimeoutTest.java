package com.aresstack.askai.research.runtime.search;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GoogleSearchBrowserStrategyTimeoutTest {

    @Test
    public void withoutDelayTheCallKeepsTheSearchTimeout() {
        assertEquals(GoogleSearchBrowserStrategy.SEARCH_TIMEOUT_MILLIS,
                GoogleSearchBrowserStrategy.callTimeoutMillis(0L));
        assertEquals(GoogleSearchBrowserStrategy.SEARCH_TIMEOUT_MILLIS,
                GoogleSearchBrowserStrategy.callTimeoutMillis(-5L));
    }

    @Test
    public void aDelayLongerThanTheSearchTimeoutStillLeavesTheSearchItsFullTime() {
        long delay = 120_000L;
        long timeout = GoogleSearchBrowserStrategy.callTimeoutMillis(delay);
        assertEquals(GoogleSearchBrowserStrategy.SEARCH_TIMEOUT_MILLIS
                + GoogleSearchBrowserStrategy.SPACING_WAITS_PER_CALL * delay, timeout);
        assertTrue(timeout - GoogleSearchBrowserStrategy.SPACING_WAITS_PER_CALL * delay
                >= GoogleSearchBrowserStrategy.SEARCH_TIMEOUT_MILLIS);
    }
}
