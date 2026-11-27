package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The worker user-context pool against a deterministic worker browser ({@link FakeWorkerBrowserBackend}) that
 * keeps one cookie jar per user context and whose navigations take real time on the worker browser's owner
 * thread. Proves isolation (worker vs worker, chat vs chat), real parallelism across worker browsers AND the
 * honest serialization inside one, tabs, limits, cancellation, hung calls and cleanup.
 */
public class PlaywrightUserContextPoolTest {

    private final FakeWorkerBrowserBackend.Shared world = new FakeWorkerBrowserBackend.Shared();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private PlaywrightUserContextPool pool;

    @After
    public void tearDown() {
        workers.shutdownNow();
        if (pool != null) {
            pool.close();
        }
        assertTrue("Playwright touched off its owner thread: " + world.ownerViolations,
                world.ownerViolations.isEmpty());
    }

    private PlaywrightUserContextPool pool(int processes, int perProcess, int perSite) {
        pool = new PlaywrightUserContextPool(new WorkerBrowserLimits(processes, perProcess, 3, perSite,
                2_000L, 5_000L, 60_000L));
        return pool;
    }

    private static Map<String, Object> url(String url) {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("url", url);
        return args;
    }

    @Test
    public void twoWorkersOfOneChatGetSeparateUserContextsAndDoNotSeeEachOthersSignIn() throws Exception {
        PlaywrightUserContextPool.Chat chat = pool(2, 4, 4).openChat("chat-a", world.factory());
        try (WorkerUserContext alice = chat.acquire("worker-1", null, 0);
             WorkerUserContext anonymous = chat.acquire("worker-2", null, 0)) {
            assertNotEquals(alice.id(), anonymous.id());
            String signedIn = alice.firstBrowsingContext().call("web_open", url("https://site.example/?login=alice"));
            assertTrue(signedIn, signedIn.contains("session=alice"));

            String other = anonymous.firstBrowsingContext().call("web_open", url("https://site.example/"));
            assertTrue("worker 2 must not inherit worker 1's sign-in: " + other, other.contains("cookies={}"));

            String bob = anonymous.firstBrowsingContext().call("web_open", url("https://site.example/?login=bob"));
            assertTrue(bob.contains("session=bob"));
            String aliceAgain = alice.firstBrowsingContext().call("web_open", url("https://site.example/"));
            assertTrue("each worker keeps its own account: " + aliceAgain, aliceAgain.contains("session=alice"));
            assertEquals("chat-a", alice.chatKey());
        }
    }

    @Test
    public void workersInDifferentWorkerBrowsersNavigateTrulyInParallel() throws Exception {
        final PlaywrightUserContextPool.Chat chat = pool(4, 1, 8).openChat("chat-a", world.factory());
        world.navigationMillis = 600L;
        final List<WorkerUserContext> held = new ArrayList<WorkerUserContext>();
        for (int i = 0; i < 4; i++) {
            held.add(chat.acquire("worker-" + i, null, 0));
        }
        Set<String> processes = new HashSet<String>();
        for (WorkerUserContext uc : held) {
            processes.add(uc.processId());
        }
        assertEquals("parallelism first: one worker browser per worker while the cap allows", 4, processes.size());

        long wall = timeNavigations(held, "https://host-%d.example/");
        assertEquals("four navigations must overlap in the browsers", 4, world.maxInFlight.get());
        assertTrue("4 x 600 ms in parallel must take far less than 2.4 s, took " + wall + " ms", wall < 1_500L);
        for (WorkerUserContext uc : held) {
            uc.close();
        }
    }

    @Test
    public void userContextsInOneWorkerBrowserShareItsSingleCallLane() throws Exception {
        // The honest bottleneck, measured: Playwright Java runs one call at a time per instance, so user
        // contexts of ONE worker browser serialize. This is why the process cap is the parallelism knob.
        PlaywrightUserContextPool.Chat chat = pool(1, 4, 8).openChat("chat-a", world.factory());
        world.navigationMillis = 250L;
        List<WorkerUserContext> held = new ArrayList<WorkerUserContext>();
        for (int i = 0; i < 4; i++) {
            held.add(chat.acquire("worker-" + i, null, 0));
        }
        assertEquals(1, pool.processCount());
        long wall = timeNavigations(held, "https://host-%d.example/");
        assertEquals(1, world.maxInFlight.get());
        assertTrue("serialized: at least 4 x 250 ms, took " + wall + " ms", wall >= 950L);
        for (WorkerUserContext uc : held) {
            uc.close();
        }
    }

    private long timeNavigations(List<WorkerUserContext> held, final String urlPattern) throws Exception {
        final CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<Future<String>>();
        for (int i = 0; i < held.size(); i++) {
            final WorkerUserContext uc = held.get(i);
            final int n = i;
            results.add(workers.submit(new Callable<String>() {
                public String call() throws Exception {
                    go.await();
                    return uc.firstBrowsingContext().call("web_open", url(String.format(urlPattern, n)));
                }
            }));
        }
        long start = System.nanoTime();
        go.countDown();
        for (Future<String> result : results) {
            assertTrue(result.get(10, TimeUnit.SECONDS).startsWith("URL: "));
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Test
    public void tabsOfOneUserContextShareItsSessionAndCloseIndependently() throws Exception {
        PlaywrightUserContextPool.Chat chat = pool(1, 2, 4).openChat("chat-a", world.factory());
        try (WorkerUserContext worker = chat.acquire("worker-1", null, 0)) {
            worker.firstBrowsingContext().call("web_open", url("https://site.example/?login=alice"));
            WorkerBrowsingContext second = worker.openBrowsingContext();
            WorkerBrowsingContext third = worker.openBrowsingContext();
            assertEquals(3, worker.browsingContexts().size());
            assertTrue(second.call("web_open", url("https://site.example/")).contains("session=alice"));
            assertTrue("each tab has its own page", third.call("web_open", url("https://other.example/"))
                    .contains("TITLE: other.example"));
            assertTrue(worker.firstBrowsingContext().call("web_read", null).contains("URL: https://site.example/?login"));
            try {
                worker.openBrowsingContext();
                fail("the tab limit (3) must hold");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage().contains("Tab limit"));
            }
            second.close();
            try {
                second.call("web_read", null);
                fail("a closed tab must not answer");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage().startsWith(WorkerBrowserLane.LOST_MARKER));
            }
            assertTrue("closing one tab leaves the others", third.call("web_read", null).contains("other.example"));
            assertTrue("a closed tab frees its slot", worker.openBrowsingContext().isOpen());
        }
    }

    @Test
    public void chatsNeverShareAWorkerBrowserOrCookies() throws Exception {
        PlaywrightUserContextPool shared = pool(2, 4, 4);
        PlaywrightUserContextPool.Chat chatA = shared.openChat("chat-a", world.factory());
        PlaywrightUserContextPool.Chat chatB = shared.openChat("chat-b", world.factory());
        try (WorkerUserContext a = chatA.acquire("worker-1", null, 0);
             WorkerUserContext b = chatB.acquire("worker-1", null, 0)) {
            assertNotEquals("chat B must get its own process", a.processId(), b.processId());
            a.firstBrowsingContext().call("web_open", url("https://site.example/?login=alice"));
            assertTrue(b.firstBrowsingContext().call("web_open", url("https://site.example/")).contains("cookies={}"));
            assertEquals(1, shared.processCount(chatA));
            assertEquals(1, shared.processCount(chatB));

            chatB.close();
            assertTrue("chat A is untouched by chat B's close",
                    a.firstBrowsingContext().call("web_read", null).contains("session=alice"));
            try {
                b.firstBrowsingContext().call("web_read", null);
                fail("chat B's user contexts end with chat B");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith(WorkerBrowserLane.LOST_MARKER));
            }
            try {
                chatB.acquire("worker-2", null, 0);
                fail("a closed chat hands out nothing");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage().contains("closed"));
            }
        }
    }

    @Test
    public void capacityIsBoundedWaitsAndIsFreedOnClose() throws Exception {
        final PlaywrightUserContextPool.Chat chat = pool(1, 1, 4).openChat("chat-a", world.factory());
        WorkerUserContext first = chat.acquire("worker-1", null, 0);
        long start = System.nanoTime();
        try {
            chat.acquire("worker-2", null, 200);
            fail("no second user context while the only one is held");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("No free browser user context"));
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 190L);
        assertEquals("waiting never starts another process", 1, world.launched.get());

        Future<WorkerUserContext> waiting = workers.submit(new Callable<WorkerUserContext>() {
            public WorkerUserContext call() throws Exception {
                return chat.acquire("worker-2", null, 3_000);
            }
        });
        Thread.sleep(150);
        assertFalse(waiting.isDone());
        first.close();
        WorkerUserContext second = waiting.get(3, TimeUnit.SECONDS);
        assertTrue(second.isOpen());
        assertEquals("the freed slot is reused in the same worker browser", 1, world.launched.get());
        second.close();
        assertEquals(0, pool.userContextCount());
    }

    @Test
    public void aCallerMayWaitLessThanTheSettingButNeverLonger() throws Exception {
        PlaywrightUserContextPool.Chat chat = pool(1, 1, 4).openChat("chat-a", world.factory()); // wait 2 s
        WorkerUserContext holder = chat.acquire("worker-1", null, 0);
        long start = System.nanoTime();
        try {
            chat.acquire("worker-2", null, 60_000);
            fail("still no free user context");
        } catch (BrowserException expected) {
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("capped at the setting: " + waited, waited >= 1_900L && waited < 5_000L);
        }
        holder.close();
    }

    @Test
    public void aCancelledWaitingWorkerLeavesNoTrace() throws Exception {
        final PlaywrightUserContextPool.Chat chat = pool(1, 1, 4).openChat("chat-a", world.factory());
        WorkerUserContext holder = chat.acquire("worker-1", null, 0);
        final AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
        Thread waiter = new Thread(new Runnable() {
            public void run() {
                try {
                    chat.acquire("worker-2", null, 10_000);
                } catch (Throwable t) {
                    outcome.set(t);
                }
            }
        });
        waiter.start();
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(2_000);
        assertTrue(String.valueOf(outcome.get()), outcome.get() instanceof InterruptedException);
        assertEquals(1, pool.userContextCount());
        holder.close();
        assertEquals(0, pool.userContextCount());
    }

    @Test
    public void aHungBrowserCallTerminatesOnlyItsWorkerBrowserAndTheSlotIsReplaced() throws Exception {
        pool = new PlaywrightUserContextPool(new WorkerBrowserLimits(2, 1, 2, 4, 2_000L, 300L, 60_000L));
        PlaywrightUserContextPool.Chat chat = pool.openChat("chat-a", world.factory());
        world.navigationMillis = 30L;
        WorkerUserContext stuck = chat.acquire("worker-1", null, 0);
        WorkerUserContext healthy = chat.acquire("worker-2", null, 0);
        world.hangOnUrl = "https://hang.example/";
        long start = System.nanoTime();
        try {
            stuck.firstBrowsingContext().call("web_open", url("https://hang.example/"));
            fail("a hung call must fail after the call timeout");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith(WorkerBrowserLane.LOST_MARKER));
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000L);
        assertFalse(stuck.isOpen());
        assertTrue("the other worker browser is untouched",
                healthy.firstBrowsingContext().call("web_open", url("https://ok.example/")).startsWith("URL: "));
        stuck.close();
        WorkerUserContext replacement = chat.acquire("worker-1-retry", null, 0);
        assertTrue(replacement.firstBrowsingContext().call("web_open", url("https://ok.example/")).startsWith("URL:"));
        assertEquals("a fresh worker browser replaced the terminated one", 3, world.launched.get());
        assertEquals(2, pool.processCount());
        replacement.close();
        healthy.close();
    }

    @Test
    public void theSiteLimitBoundsOneHostButNotOthers() throws Exception {
        PlaywrightUserContextPool.Chat chat = pool(4, 1, 1).openChat("chat-a", world.factory());
        world.navigationMillis = 300L;
        List<WorkerUserContext> held = new ArrayList<WorkerUserContext>();
        for (int i = 0; i < 3; i++) {
            held.add(chat.acquire("worker-" + i, null, 0));
        }
        timeNavigations(held, "https://same.example/page-%d");
        assertEquals("one at a time on the same site", Integer.valueOf(1), world.maxPerHost.get("same.example"));
        world.maxInFlight.set(0);
        timeNavigations(held, "https://host-%d.example/");
        assertEquals("different sites still run in parallel", 3, world.maxInFlight.get());
        for (WorkerUserContext uc : held) {
            uc.close();
        }
    }

    @Test
    public void anExportedSignInComesBackInALaterUserContext() throws Exception {
        PlaywrightUserContextPool.Chat chat = pool(1, 2, 4).openChat("chat-a", world.factory());
        String state;
        try (WorkerUserContext first = chat.acquire("worker-1", null, 0)) {
            first.firstBrowsingContext().call("web_open", url("https://site.example/?login=alice"));
            state = first.exportState();
        }
        try (WorkerUserContext resumed = chat.acquire("worker-1", state, 0);
             WorkerUserContext fresh = chat.acquire("worker-2", null, 0)) {
            assertTrue(resumed.firstBrowsingContext().call("web_open", url("https://site.example/"))
                    .contains("session=alice"));
            assertTrue(fresh.firstBrowsingContext().call("web_open", url("https://site.example/"))
                    .contains("cookies={}"));
        }
    }

    @Test
    public void idleWorkerBrowsersStopAndAnIdleOneOfAnotherChatMakesRoomAtTheCap() throws Exception {
        pool = new PlaywrightUserContextPool(new WorkerBrowserLimits(1, 2, 2, 4, 1_000L, 5_000L, 60_000L));
        PlaywrightUserContextPool.Chat chatA = pool.openChat("chat-a", world.factory());
        PlaywrightUserContextPool.Chat chatB = pool.openChat("chat-b", world.factory());
        chatA.acquire("worker-1", null, 0).close(); // chat A's process is idle (but warm for 60 s)
        assertEquals(1, pool.processCount());
        try (WorkerUserContext b = chatB.acquire("worker-1", null, 0)) {
            assertEquals("chat A's idle process was stopped for chat B", 1, pool.processCount());
            assertEquals(1, pool.processCount(chatB));
            assertEquals(2, world.launched.get());
        }
        pool.updateLimits(new WorkerBrowserLimits(1, 2, 2, 4, 1_000L, 5_000L, 50L));
        chatB.acquire("worker-2", null, 0).close();
        long deadline = System.currentTimeMillis() + 2_000L;
        while (pool.processCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("an idle worker browser stops after the idle time", 0, pool.processCount());
        Thread.sleep(100);
        assertEquals("every launched process was closed", world.launched.get(), world.closed.get());
    }

    @Test
    public void aStoppingWorkerBrowserCountsAgainstTheCapUntilItsProcessIsGone() throws Exception {
        pool = new PlaywrightUserContextPool(new WorkerBrowserLimits(1, 2, 2, 4, 5_000L, 5_000L, 60_000L));
        world.closeMillis = 400L; // a slow browser exit
        PlaywrightUserContextPool.Chat chatA = pool.openChat("chat-a", world.factory());
        PlaywrightUserContextPool.Chat chatB = pool.openChat("chat-b", world.factory());
        chatA.acquire("worker-1", null, 0).close(); // idle, warm
        long start = System.nanoTime();
        try (WorkerUserContext b = chatB.acquire("worker-1", null, 0)) {
            assertTrue("chat B waited for chat A's browser to exit",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 350L);
            assertEquals("never more browser processes than the cap", 1, world.maxLive.get());
            assertEquals(1, pool.processCount());
        }
        chatB.close();
        assertEquals("a stopping browser is still counted", 1, pool.processCount());
        long deadline = System.currentTimeMillis() + 3_000L;
        while (pool.processCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, pool.processCount());
        assertEquals(1, world.maxLive.get());
    }

    @Test
    public void aUserContextCreatedForACancelledWorkerIsClosedAndNotCounted() throws Exception {
        pool = new PlaywrightUserContextPool(new WorkerBrowserLimits(1, 1, 2, 4, 5_000L, 5_000L, 60_000L));
        final PlaywrightUserContextPool.Chat chat = pool.openChat("chat-a", world.factory());
        chat.acquire("warm-up", null, 0).close(); // the worker browser is up
        world.newUserContextMillis = 300L;
        final AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                chat.acquire("worker-1", null, 0);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        worker.start();
        Thread.sleep(100); // the creation runs on the owner thread now
        worker.interrupt();
        worker.join(2_000);
        assertTrue(String.valueOf(outcome.get()), outcome.get() instanceof InterruptedException);
        world.newUserContextMillis = 0L;
        try (WorkerUserContext next = chat.acquire("worker-2", null, 0)) {
            assertTrue(next.isOpen());
            assertEquals("the abandoned user context was closed: only the new one lives (limit 1)",
                    1, world.liveUserContexts.get());
        }
        assertEquals(0, pool.userContextCount());
    }

    @Test
    public void aFailedStartReleasesItsSlot() throws Exception {
        PlaywrightUserContextPool.Chat chat = pool(1, 1, 4).openChat("chat-a", world.factory());
        world.failLaunch = true;
        try {
            chat.acquire("worker-1", null, 0);
            fail("start failure must surface");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("start failed"));
        }
        assertEquals(0, pool.processCount());
        world.failLaunch = false;
        try (WorkerUserContext worker = chat.acquire("worker-1", null, 0)) {
            assertTrue(worker.isOpen());
        }
    }

    @Test
    public void closingThePoolEndsEveryWorkerBrowser() throws Exception {
        PlaywrightUserContextPool.Chat chat = pool(2, 1, 4).openChat("chat-a", world.factory());
        WorkerUserContext one = chat.acquire("worker-1", null, 0);
        chat.acquire("worker-2", null, 0);
        pool.close();
        long deadline = System.currentTimeMillis() + 2_000L;
        while (world.closed.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, world.closed.get());
        try {
            one.firstBrowsingContext().call("web_read", Collections.<String, Object>emptyMap());
            fail();
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().startsWith(WorkerBrowserLane.LOST_MARKER));
        }
        one.close(); // idempotent after the pool is gone
    }

    @Test
    public void aCancelledWorkerStillGetsItsStuckCallTerminatedAfterTheTimeout() throws Exception {
        pool = new PlaywrightUserContextPool(new WorkerBrowserLimits(1, 1, 2, 4, 2_000L, 400L, 60_000L));
        final WorkerUserContext stuck = pool.openChat("chat-a", world.factory()).acquire("worker-1", null, 0);
        world.hangOnUrl = "https://hang.example/";
        final AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    stuck.firstBrowsingContext().call("web_open", url("https://hang.example/"));
                } catch (Throwable t) {
                    outcome.set(t);
                }
            }
        });
        worker.start();
        Thread.sleep(100);
        worker.interrupt(); // the orchestrator cancels the task while the browser call hangs
        worker.join(2_000);
        assertTrue(String.valueOf(outcome.get()), outcome.get() instanceof InterruptedException);
        stuck.close();
        long deadline = System.currentTimeMillis() + 3_000L;
        while (world.closed.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("the call's deadline holds after the cancel: the process is terminated", 1, world.closed.get());
        assertEquals(0, pool.processCount());
    }

    @Test
    public void concurrentTabOpensRespectTheLimitAndGetDistinctIds() throws Exception {
        pool = new PlaywrightUserContextPool(new WorkerBrowserLimits(1, 1, 2, 4, 2_000L, 5_000L, 60_000L));
        final WorkerUserContext worker = pool.openChat("chat-a", world.factory()).acquire("worker-1", null, 0);
        final CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> opens = new ArrayList<Future<String>>();
        for (int i = 0; i < 4; i++) {
            opens.add(workers.submit(new Callable<String>() {
                public String call() throws Exception {
                    go.await();
                    try {
                        return worker.openBrowsingContext().id();
                    } catch (BrowserException refused) {
                        return "refused";
                    }
                }
            }));
        }
        go.countDown();
        Set<String> ids = new HashSet<String>();
        int refused = 0;
        for (Future<String> open : opens) {
            String id = open.get(5, TimeUnit.SECONDS);
            if ("refused".equals(id)) {
                refused++;
            } else {
                assertTrue("tab ids must be unique: " + id, ids.add(id));
            }
        }
        assertEquals("limit 2 = the first tab plus one more", 1, ids.size());
        assertEquals(3, refused);
        assertEquals(2, worker.browsingContexts().size());
        worker.close();
    }

    @Test
    public void aProbedSiteIsTheGateForTheFollowUpNavigation() throws Exception {
        PlaywrightUserContextPool.Chat chat = pool(3, 1, 1).openChat("chat-a", world.factory());
        world.navigationMillis = 250L;
        final List<WorkerUserContext> held = new ArrayList<WorkerUserContext>();
        for (int i = 0; i < 3; i++) {
            held.add(chat.acquire("worker-" + i, null, 0));
            held.get(i).firstBrowsingContext().call("web_probe", url("https://same.example/start-" + i));
        }
        world.maxPerHost.clear();
        final CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> follows = new ArrayList<Future<String>>();
        for (final WorkerUserContext uc : held) {
            follows.add(workers.submit(new Callable<String>() {
                public String call() throws Exception {
                    go.await();
                    Map<String, Object> link = new HashMap<String, Object>();
                    link.put("link_id", "link-1");
                    return uc.firstBrowsingContext().call("web_follow", link);
                }
            }));
        }
        go.countDown();
        for (Future<String> follow : follows) {
            follow.get(10, TimeUnit.SECONDS);
        }
        assertEquals("the per-site limit also holds for follows after a probe", Integer.valueOf(1),
                world.maxPerHost.get("same.example"));
        for (WorkerUserContext uc : held) {
            uc.close();
        }
    }

    @Test
    public void aClosedWorkerWindowIsNeverReportedAsTheUsersStop() throws Exception {
        PlaywrightUserContextPool.Chat chat = pool(1, 1, 4).openChat("chat-a", world.factory());
        try (WorkerUserContext worker = chat.acquire("worker-1", null, 0)) {
            world.challengeStatus = "BROWSER_CLOSED";
            try {
                worker.firstBrowsingContext().call("web_challenge_status", null);
                fail("the typed status line must not reach the run");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith(WorkerBrowserLane.LOST_MARKER));
            }
            world.challengeStatus = "NONE";
            assertEquals("NONE", worker.firstBrowsingContext().call("web_challenge_status", null));
            world.prepareSeesClosedWindow = true;
            Map<String, Object> query = new HashMap<String, Object>();
            query.put("query", "q");
            try {
                worker.firstBrowsingContext().call("web_search_prepare", query);
                fail("the runtime-exception path must be normalized too");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith(WorkerBrowserLane.LOST_MARKER));
                assertFalse(expected.getMessage().contains("BROWSER_CLOSED —"));
            }
        }
    }
}
