package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SiteConcurrencyGateTest {

    /** Injected clock: time moves only when the test says so. */
    private static final class TestClock implements SiteConcurrencyGate.Clock {
        final AtomicLong now = new AtomicLong(1_000_000L);

        public long nowMillis() {
            return now.get();
        }
    }

    private final TestClock clock = new TestClock();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @After
    public void stop() {
        pool.shutdownNow();
    }

    private SiteConcurrencyGate gate(int limit, String overrides) {
        return new SiteConcurrencyGate(limit, new SiteLeasePolicy(90_000L, 60_000L,
                SiteLeasePolicy.parseOverrides(overrides)), clock);
    }

    private void advance(SiteConcurrencyGate gate, long millis) throws InterruptedException {
        clock.now.addAndGet(millis);
        gate.wakeUp();
        Thread.sleep(30L);
    }

    private static void awaitWaiting(SiteConcurrencyGate gate, String key, int count) throws InterruptedException {
        for (int i = 0; i < 200 && gate.waiting(key) < count; i++) {
            Thread.sleep(5L);
        }
        assertEquals(count, gate.waiting(key));
    }

    @Test
    public void parsesOverrides() {
        SiteLeasePolicy policy = new SiteLeasePolicy(1L, 1L,
                SiteLeasePolicy.parseOverrides("researchgate.net=1@10s; *.sciencedirect.com=2@500ms\nfoo.org=3"));
        assertEquals(1, policy.overrideFor("researchgate.net").permits);
        assertEquals(10_000L, policy.overrideFor("de.researchgate.net").spacingMillis);
        assertEquals(500L, policy.overrideFor("www2.sciencedirect.com").spacingMillis);
        assertEquals(3, policy.overrideFor("foo.org").permits);
        assertEquals(null, policy.overrideFor("notresearchgate.net"));
        try {
            SiteLeasePolicy.parseOverrides("broken");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // named entry
        }
    }

    @Test
    public void withoutOverridesTheLimitBehavesAsBefore() throws Exception {
        SiteConcurrencyGate gate = gate(2, "");
        SiteConcurrencyGate.Lease a = gate.enter("example.org", 1_000L);
        SiteConcurrencyGate.Lease b = gate.enter("example.org", 1_000L);
        assertEquals(2, gate.active("example.org"));
        Future<SiteConcurrencyGate.Lease> c = pool.submit(() -> gate.enter("example.org", 0L));
        awaitWaiting(gate, "example.org", 1);
        assertFalse(gate.leave(a));
        assertNotNull(c.get(2, TimeUnit.SECONDS));
        assertFalse(gate.leave(b));
        assertNull(gate.enter(null, 0L));
    }

    @Test
    public void defaultSpacingStaggersTwoTabsOnTheSameSiteButNotOtherSitesOrEngines() throws Exception {
        SiteConcurrencyGate gate = new SiteConcurrencyGate(2, new SiteLeasePolicy(90_000L, 60_000L,
                SiteLeasePolicy.parseOverrides(""), 3_000L), clock);
        SiteConcurrencyGate.Lease first = gate.enter("example.org", 0L);
        Future<SiteConcurrencyGate.Lease> second = pool.submit(() -> gate.enter("example.org", 0L));
        awaitWaiting(gate, "example.org", 1);
        assertEquals(1, gate.active("example.org")); // a free permit, but the gap holds the second tab
        assertTrue(gate.enter("other.org", 1_000L) != null);
        assertTrue(gate.enter(SiteConcurrencyGate.SEARCH_KEY, 1_000L) != null);
        assertTrue(gate.enter(SiteConcurrencyGate.SEARCH_KEY, 1_000L) != null);
        advance(gate, 3_000L);
        SiteConcurrencyGate.Lease lease = second.get(2, TimeUnit.SECONDS);
        assertTrue(lease.acquiredAt - first.acquiredAt >= 3_000L);
    }

    @Test
    public void theSameSiteGapEndsTypedAtTheCallersTimeout() throws Exception {
        SiteConcurrencyGate gate = new SiteConcurrencyGate(2, new SiteLeasePolicy(90_000L, 60_000L,
                SiteLeasePolicy.parseOverrides(""), 10_000L), clock);
        gate.enter("example.org", 0L);
        Future<SiteConcurrencyGate.Lease> second = pool.submit(() -> gate.enter("example.org", 1_000L));
        awaitWaiting(gate, "example.org", 1);
        advance(gate, 1_000L);
        try {
            second.get(2, TimeUnit.SECONDS);
            fail("expected SITE_BUSY");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause().getMessage().startsWith(SiteConcurrencyGate.SITE_BUSY));
        }
    }

    @Test
    public void strictSiteNeverOverlapsAndKeepsSpacing() throws Exception {
        SiteConcurrencyGate gate = gate(2, "researchgate.net=1@10s");
        SiteConcurrencyGate.Lease first = gate.enter("researchgate.net", 0L);
        long firstStart = first.acquiredAt;
        Future<SiteConcurrencyGate.Lease> second = pool.submit(() -> gate.enter("researchgate.net", 0L));
        awaitWaiting(gate, "researchgate.net", 1);
        advance(gate, 2_000L);
        gate.leave(first); // released after 2 s; spacing still holds the waiter until 10 s
        Thread.sleep(30L);
        assertFalse(second.isDone());
        advance(gate, 7_000L);
        assertFalse(second.isDone());
        advance(gate, 1_000L);
        SiteConcurrencyGate.Lease lease = second.get(2, TimeUnit.SECONDS);
        assertTrue(lease.acquiredAt - firstStart >= 10_000L);
        assertEquals(1, gate.active("researchgate.net"));
    }

    @Test
    public void waiterTakesOverOnlyAfterTheRunningOperationReturned() throws Exception {
        SiteConcurrencyGate gate = gate(2, "researchgate.net=1");
        SiteConcurrencyGate.Lease holder = gate.enter("researchgate.net", 0L);
        Future<SiteConcurrencyGate.Lease> waiter = pool.submit(() -> gate.enter("researchgate.net", 120_000L));
        awaitWaiting(gate, "researchgate.net", 1);
        advance(gate, 61_000L); // wait deadline passed: holder is expired, but its operation still runs
        assertFalse(waiter.isDone());
        assertEquals(1, gate.active("researchgate.net"));
        assertTrue("the holder learns it lost the site", gate.leave(holder));
        assertNotNull(gate.expiredBecause(holder));
        assertNotNull(waiter.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void hungHolderNeverBlocksForever() throws Exception {
        SiteConcurrencyGate gate = gate(1, "");
        gate.enter("example.org", 0L); // never leaves
        Future<SiteConcurrencyGate.Lease> waiter = pool.submit(() -> gate.enter("example.org", 100_000L));
        awaitWaiting(gate, "example.org", 1);
        advance(gate, 100_001L);
        try {
            waiter.get(2, TimeUnit.SECONDS);
            fail("expected SITE_BUSY");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof BrowserException);
            assertTrue(expected.getCause().getMessage().startsWith(SiteConcurrencyGate.SITE_BUSY));
        }
        assertEquals(0, gate.waiting("example.org"));
    }

    @Test
    public void holdDeadlineExpiresTheLease() throws Exception {
        SiteConcurrencyGate gate = gate(2, "");
        SiteConcurrencyGate.Lease lease = gate.enter("example.org", 0L);
        clock.now.addAndGet(91_000L);
        assertTrue(gate.leave(lease));
    }

    @Test
    public void waitersAreServedInOrder() throws Exception {
        SiteConcurrencyGate gate = gate(1, "");
        SiteConcurrencyGate.Lease holder = gate.enter("example.org", 0L);
        final StringBuilder order = new StringBuilder();
        Future<?> a = pool.submit(() -> take(gate, order, "a"));
        awaitWaiting(gate, "example.org", 1);
        Future<?> b = pool.submit(() -> take(gate, order, "b"));
        awaitWaiting(gate, "example.org", 2);
        Future<?> c = pool.submit(() -> take(gate, order, "c"));
        awaitWaiting(gate, "example.org", 3);
        gate.leave(holder);
        a.get(2, TimeUnit.SECONDS);
        b.get(2, TimeUnit.SECONDS);
        c.get(2, TimeUnit.SECONDS);
        assertEquals("abc", order.toString());
    }

    private static Void take(SiteConcurrencyGate gate, StringBuilder order, String name) throws Exception {
        SiteConcurrencyGate.Lease lease = gate.enter("example.org", 0L);
        synchronized (order) {
            order.append(name);
        }
        Thread.sleep(10L);
        gate.leave(lease);
        return null;
    }

    @Test
    public void coolDownParksTheSiteForEveryoneAndReleases() throws Exception {
        SiteConcurrencyGate gate = gate(2, "");
        gate.coolDown("example.org");
        assertTrue(gate.coolingDown("example.org"));
        Future<SiteConcurrencyGate.Lease> waiter = pool.submit(() -> gate.enter("example.org", 0L));
        awaitWaiting(gate, "example.org", 1);
        advance(gate, 29_000L);
        assertFalse(waiter.isDone());
        advance(gate, 1_000L);
        gate.leave(waiter.get(2, TimeUnit.SECONDS));
        gate.coolDown("example.org"); // second strike doubles
        advance(gate, 59_000L);
        assertTrue(gate.coolingDown("example.org"));
        advance(gate, 1_000L);
        assertFalse(gate.coolingDown("example.org"));
        assertTrue(gate.snapshot().contains("example.org"));
    }

    private static void assertNull(Object value) {
        assertEquals(null, value);
    }
}
