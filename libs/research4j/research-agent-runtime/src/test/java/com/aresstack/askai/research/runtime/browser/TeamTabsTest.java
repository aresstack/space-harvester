package com.aresstack.askai.research.runtime.browser;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plan T5: one user context for the whole team, one tab per page worker, closed with the last tab. */
public class TeamTabsTest {

    @Test
    public void everyWorkerGetsItsOwnTabInOneUserContext() throws Exception {
        FakePool pool = new FakePool(3);
        TeamTabs tabs = new TeamTabs(pool);
        TeamTabs.Tab first = tabs.acquire("q1");
        TeamTabs.Tab second = tabs.acquire("q2");
        TeamTabs.Tab third = tabs.acquire("q3");
        assertEquals(1, pool.leases.size()); // one user context, not one browser per worker
        assertSame(pool.leases.get(0).firstTab, first.tools());
        assertNotSame(first.tools(), second.tools());
        assertNotSame(second.tools(), third.tools());
        assertEquals(2, pool.leases.get(0).extraTabs);
        assertEquals(3, tabs.openTabs());
        assertEquals("uc-1", second.userContextId());
        second.close();
        second.close(); // idempotent
        assertEquals(1, pool.leases.get(0).closedTabs);
        first.close();
        assertTrue(pool.leases.get(0).open); // a worker still holds a tab
        TeamTabs.Tab again = tabs.acquire("q4");
        assertSame(pool.leases.get(0).firstTab, again.tools()); // the free first tab is used again
        again.close();
        third.close();
        assertFalse(pool.leases.get(0).open); // the last tab closes the user context
        assertEquals(0, tabs.openTabs());
        tabs.acquire("q5");
        assertEquals(2, pool.leases.size()); // the next worker opens a fresh one
    }

    @Test
    public void theTabLimitFailsHonestlyAndKeepsTheCount() throws Exception {
        FakePool pool = new FakePool(1);
        TeamTabs tabs = new TeamTabs(pool);
        TeamTabs.Tab first = tabs.acquire("q1");
        try {
            tabs.acquire("q2");
            fail("the tab limit is reached");
        } catch (BrowserCapacityException expected) {
            // the plan keeps the query pending
        }
        assertEquals(1, tabs.openTabs());
        assertTrue(pool.leases.get(0).open);
        first.close();
        assertFalse(pool.leases.get(0).open);
    }

    @Test
    public void theFirstTabOfALostContextNeverFreesTheFirstTabOfTheNewOne() throws Exception {
        FakePool pool = new FakePool(3);
        TeamTabs tabs = new TeamTabs(pool);
        TeamTabs.Tab old = tabs.acquire("q1");
        pool.leases.get(0).open = false; // the worker browser is lost while q1 still holds its tab
        TeamTabs.Tab fresh = tabs.acquire("q2");
        assertEquals(2, pool.leases.size());
        assertSame(pool.leases.get(1).firstTab, fresh.tools());
        old.close();
        TeamTabs.Tab next = tabs.acquire("q3");
        assertNotSame("two queries never share one tab", fresh.tools(), next.tools());
    }

    @Test
    public void oneSearchTakesAsManyTabsAsAreFreeWithoutWaitingForMore() throws Exception {
        FakePool pool = new FakePool(3);
        TeamTabs tabs = new TeamTabs(pool);
        TeamTabs.Tab other = tabs.acquire("other search");
        List<TeamTabs.Tab> mine = tabs.acquireUpTo("q1", 4);
        assertEquals(2, mine.size()); // the limit is three tabs; the other search holds one
        assertEquals(1, pool.leases.size());
        other.close();
        for (TeamTabs.Tab tab : mine) {
            tab.close();
        }
        assertFalse(pool.leases.get(0).open);
    }

    /**
     * Live run 4 (2026-11-10): the first planned search held every tab until its end and the next search failed at
     * once. A worker at the tab limit now waits for a tab another search gives back, within the wait setting.
     */
    @Test(timeout = 10_000)
    public void aWorkerAtTheTabLimitWaitsForATabThatAnotherSearchGivesBack() throws Exception {
        FakePool pool = new FakePool(2, 5_000L);
        final TeamTabs tabs = new TeamTabs(pool);
        TeamTabs.Tab first = tabs.acquire("q1");
        TeamTabs.Tab second = tabs.acquire("q2");
        final TeamTabs.Tab[] got = new TeamTabs.Tab[1];
        final Exception[] failed = new Exception[1];
        Thread waiting = new Thread(new Runnable() {
            public void run() {
                try {
                    got[0] = tabs.acquire("q3");
                } catch (Exception e) {
                    failed[0] = e;
                }
            }
        }, "q3");
        waiting.start();
        while (waiting.getState() != Thread.State.TIMED_WAITING) {
            assertTrue("the worker must wait for a tab, not fail", waiting.isAlive());
            Thread.sleep(5L);
        }
        assertEquals(2, tabs.openTabs()); // the waiting worker holds no tab yet
        second.close(); // the other search ends and gives its tab back
        waiting.join();
        assertEquals(null, failed[0]);
        assertTrue(got[0] != null);
        assertEquals(1, pool.leases.size()); // the freed slot in the same user context
        assertEquals(2, tabs.openTabs());
        got[0].close();
        first.close();
        assertFalse(pool.leases.get(0).open);
    }

    @Test(timeout = 10_000)
    public void noTabWithinTheWaitSettingFailsHonestlyAndKeepsTheCount() throws Exception {
        FakePool pool = new FakePool(1, 50L);
        TeamTabs tabs = new TeamTabs(pool);
        TeamTabs.Tab first = tabs.acquire("q1");
        try {
            tabs.acquire("q2");
            fail("no tab came free within the wait setting");
        } catch (BrowserCapacityException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("No tab came free"));
        }
        assertEquals(1, tabs.openTabs());
        first.close();
        assertFalse(pool.leases.get(0).open);
    }

    @Test(timeout = 2_000)
    public void theExtraTabsOfOneSearchNeverWaitForTheWaitSetting() throws Exception {
        FakePool pool = new FakePool(3, 60_000L);
        TeamTabs tabs = new TeamTabs(pool);
        TeamTabs.Tab other = tabs.acquire("other search");
        List<TeamTabs.Tab> mine = tabs.acquireUpTo("q1", 4);
        assertEquals(2, mine.size()); // what is free right now; the search reads with it at once
        other.close();
        for (TeamTabs.Tab tab : mine) {
            tab.close();
        }
    }

    /**
     * Live run 4 (2026-11-12): a search at the tab limit was refused while the running search gave its tabs back; the
     * last one closed the user context, so "nothing of ours can come free" looked true and the search failed instead
     * of opening the context again. A try that misses tabs coming back tries again.
     */
    @Test(timeout = 10_000)
    public void aTryThatMissesTheLastTabsComingBackTriesAgainInsteadOfFailing() throws Exception {
        FakePool pool = new FakePool(2, 5_000L);
        TeamTabs tabs = new TeamTabs(pool);
        final TeamTabs.Tab first = tabs.acquire("q1");
        final TeamTabs.Tab second = tabs.acquire("q2");
        pool.leases.get(0).beforeRefusal = new Runnable() {
            public void run() { // the other search ends right while this one is refused at the limit
                second.close();
                first.close();
            }
        };
        TeamTabs.Tab mine = tabs.acquire("q3");
        assertEquals(1, tabs.openTabs());
        assertFalse(pool.leases.get(0).open); // the last tab closed the old context
        assertEquals(2, pool.leases.size()); // the search opened a fresh one instead of failing
        assertSame(pool.leases.get(1).firstTab, mine.tools());
        mine.close();
        assertFalse(pool.leases.get(1).open);
    }

    /**
     * Live run 4 (2026-11-12): a search that waited at the tab limit woke when the running search gave its tabs back,
     * found the user context still open and a moment later closed by the last tab: "the team's user context is not
     * open", and the plan went on without the team. Searches that come and go never fail while tabs come back.
     */
    @Test(timeout = 60_000)
    public void searchesThatComeAndGoNeverFailWhileTabsComeBack() throws Exception {
        final FakePool pool = new FakePool(2, 30_000L);
        final TeamTabs tabs = new TeamTabs(pool);
        final int searches = 4;
        final int rounds = 300;
        final AtomicReference<Throwable> failed = new AtomicReference<Throwable>();
        final CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<Thread>();
        for (int s = 0; s < searches; s++) {
            final String name = "search-" + s;
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        go.await();
                        for (int round = 0; round < rounds && failed.get() == null; round++) {
                            List<TeamTabs.Tab> mine = tabs.acquireUpTo(name + "-" + round, 2);
                            Thread.yield();
                            for (TeamTabs.Tab tab : mine) {
                                tab.close();
                            }
                        }
                    } catch (Throwable e) {
                        failed.compareAndSet(null, e);
                    }
                }
            }, name);
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        if (failed.get() != null) {
            throw new AssertionError("a search failed while tabs came back: " + failed.get(), failed.get());
        }
        assertEquals(0, tabs.openTabs());
        synchronized (pool.leases) {
            for (FakeLease lease : pool.leases) {
                assertFalse("every user context is closed with its last tab", lease.open);
            }
        }
    }

    @Test
    public void withoutWorkerBrowsersTheTeamIsNotAvailable() {
        assertFalse(new TeamTabs(BrowserUserContextPool.unavailable("none")).available());
        assertTrue(new TeamTabs(new FakePool(2)).available());
    }

    private static ToolInvoker tools() {
        return new ToolInvoker() {
            public String call(String toolName, Map<String, Object> args) {
                return "";
            }
        };
    }

    /** Thread-safe: the stress test calls it from several searches at once. */
    private static final class FakePool implements BrowserUserContextPool {
        final List<FakeLease> leases = Collections.synchronizedList(new ArrayList<FakeLease>());
        final int tabsPerContext;
        final long waitMillis;

        FakePool(int tabsPerContext) {
            this(tabsPerContext, 0L);
        }

        FakePool(int tabsPerContext, long waitMillis) {
            this.tabsPerContext = tabsPerContext;
            this.waitMillis = waitMillis;
        }

        @Override
        public long acquireTimeoutMillis() {
            return waitMillis;
        }

        public synchronized BrowserUserContextLease acquire(BrowserUserContextRequest request) {
            FakeLease lease = new FakeLease("uc-" + (leases.size() + 1), request, tabsPerContext);
            leases.add(lease);
            return lease;
        }

        public int capacity() {
            return 2;
        }
    }

    /** Thread-safe like the worker browsers' user context it stands for. */
    private static final class FakeLease implements BrowserUserContextLease {
        final String id;
        final BrowserUserContextRequest request;
        final int limit;
        final ToolInvoker firstTab = tools();
        volatile int extraTabs;
        volatile int closedTabs;
        volatile boolean open = true;
        /** Runs once when the tab limit refuses a tab, before the refusal is thrown. */
        volatile Runnable beforeRefusal;

        FakeLease(String id, BrowserUserContextRequest request, int limit) {
            this.id = id;
            this.request = request;
            this.limit = limit;
        }

        public String userContextId() {
            return id;
        }

        public BrowserUserContextRequest request() {
            return request;
        }

        public ToolInvoker browser() {
            return firstTab;
        }

        public synchronized BrowsingContextLease openBrowsingContext() throws BrowserCapacityException {
            if (1 + extraTabs - closedTabs >= limit) {
                Runnable hook = beforeRefusal;
                beforeRefusal = null;
                if (hook != null) {
                    hook.run();
                }
                throw new BrowserCapacityException("tab limit reached");
            }
            extraTabs++;
            final ToolInvoker tab = tools();
            final String tabId = id + "-tab-" + extraTabs;
            return new BrowsingContextLease() {
                public String browsingContextId() {
                    return tabId;
                }

                public ToolInvoker tools() {
                    return tab;
                }

                public void close() {
                    synchronized (FakeLease.this) {
                        closedTabs++;
                    }
                }
            };
        }

        public boolean isOpen() {
            return open;
        }

        public synchronized void close() {
            open = false;
        }
    }
}
