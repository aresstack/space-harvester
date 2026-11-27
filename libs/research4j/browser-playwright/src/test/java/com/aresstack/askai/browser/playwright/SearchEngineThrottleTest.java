package com.aresstack.askai.browser.playwright;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchEngineThrottleTest {

    static final class VirtualClock implements SearchEngineThrottle.Clock {
        long now = 1_000_000L;
        final List<Long> sleeps = new ArrayList<Long>();

        public synchronized long nowMillis() {
            return now;
        }

        public synchronized void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }
    }

    private final VirtualClock clock = new VirtualClock();
    private final SearchEngineThrottle throttle = new SearchEngineThrottle(10_000L, 60_000L, 600_000L, clock);

    @Test
    public void everyEngineRequestKeepsTheMinimumDistance() throws Exception {
        throttle.awaitSlot();
        throttle.awaitSlot();
        clock.now += 4_000L;
        throttle.awaitSlot();
        assertEquals(Arrays.asList(10_000L, 6_000L), clock.sleeps);
    }

    @Test
    public void noWaitWhenTheLastRequestIsLongEnoughAgo() throws Exception {
        throttle.awaitSlot();
        clock.now += 30_000L;
        throttle.awaitSlot();
        assertEquals(Collections.<Long>emptyList(), clock.sleeps);
    }

    @Test
    public void challengesPauseExponentiallyAndASuccessResets() {
        assertEquals(60_000L, throttle.recordBlock("BLOCKED: google_obstacle"));
        assertTrue(throttle.isPaused());
        assertTrue(throttle.pauseReason().contains("google_obstacle"));
        clock.now += 60_000L;
        assertFalse(throttle.isPaused());
        assertEquals("", throttle.pauseReason());
        assertEquals(120_000L, throttle.recordBlock("again"));
        assertEquals(240_000L, throttle.recordBlock("again"));
        assertEquals(480_000L, throttle.recordBlock("again"));
        assertEquals("capped", 600_000L, throttle.recordBlock("again"));
        throttle.recordSuccess();
        assertEquals(60_000L, throttle.recordBlock("after a success"));
    }

    @Test
    public void aZeroPauseFromTheSettingsMeansNoPause() {
        throttle.setPause(0L, 0L);
        assertEquals(0L, throttle.recordBlock("challenge"));
        assertFalse(throttle.isPaused());
    }
}
