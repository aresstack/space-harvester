package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.runtime.browser.BrowserCapacityException;
import com.aresstack.askai.research.runtime.browser.BrowserUserContextLease;
import com.aresstack.askai.research.runtime.browser.BrowserUserContextPool;
import com.aresstack.askai.research.runtime.browser.BrowserUserContextRequest;
import com.aresstack.askai.research.runtime.browser.BrowsingContextLease;
import com.aresstack.askai.research.runtime.browser.UserContextWebAcquisition;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The task executor's web research workers on the chat's worker user contexts: one user context per search
 * run, the pool's capacity as the executor's browser capacity, every lease given back, and the chat browser
 * only without worker browsers.
 */
public class WorkerUserContextExecutionTest {

    /** A pool that hands out numbered user contexts and counts what is open at the same time. */
    static final class FakePool implements BrowserUserContextPool {
        final int capacity;
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger maxOpen = new AtomicInteger();
        final AtomicInteger issued = new AtomicInteger();
        final List<FakeLease> leases = new CopyOnWriteArrayList<FakeLease>();
        volatile BrowserCapacityException refuse;
        volatile boolean interrupt;

        FakePool(int capacity) {
            this.capacity = capacity;
        }

        public BrowserUserContextLease acquire(BrowserUserContextRequest request)
                throws BrowserCapacityException, InterruptedException {
            if (interrupt) {
                throw new InterruptedException("cancelled");
            }
            if (refuse != null) {
                throw refuse;
            }
            int now = open.incrementAndGet();
            maxOpen.accumulateAndGet(now, Math::max);
            FakeLease lease = new FakeLease(this, "uc" + issued.incrementAndGet(), request);
            leases.add(lease);
            return lease;
        }

        public int capacity() {
            return capacity;
        }
    }

    static final class FakeLease implements BrowserUserContextLease {
        final FakePool pool;
        final String id;
        final BrowserUserContextRequest request;
        final AtomicBoolean closed = new AtomicBoolean();
        final ToolInvoker browser = (tool, args) -> "ok from " + userContextId();

        FakeLease(FakePool pool, String id, BrowserUserContextRequest request) {
            this.pool = pool;
            this.id = id;
            this.request = request;
        }

        public String userContextId() {
            return id;
        }

        public BrowserUserContextRequest request() {
            return request;
        }

        public ToolInvoker browser() {
            return browser;
        }

        public BrowsingContextLease openBrowsingContext() throws BrowserCapacityException {
            throw new BrowserCapacityException("not needed here");
        }

        public boolean isOpen() {
            return !closed.get();
        }

        public void close() {
            if (closed.compareAndSet(false, true)) {
                pool.open.decrementAndGet();
            }
        }
    }

    private final ToolInvoker chatBrowser = (tool, args) -> "chat";
    private final List<ToolInvoker> lanesUsed = new CopyOnWriteArrayList<ToolInvoker>();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger maxRunning = new AtomicInteger();

    /** The session's funnel stand-in: records which browser lane it drove and how many ran at once. */
    private UserContextWebAcquisition.SearchRun searchRun(final long millis) {
        return (browser, query, research, accepted, maxDurationMillis, tokens, exhausted) -> {
            lanesUsed.add(browser);
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return new WebAcquisition.Result(ResearchStopReason.SUFFICIENT_EVIDENCE, "pages 1, hosts 1");
        };
    }

    private TaskExecutor executor(InMemoryTaskBoard board, WebAcquisition acquisition, final int browserSlots) {
        final WebResearchWorker worker = new WebResearchWorker(acquisition, (tool, args) -> "{}");
        return new TaskExecutor(board, Collections.<TaskWorker>singletonList(worker), new ScriptedChats(0),
                new ExecutorCapacity() {
                    public List<Resource> resourcesOf(TaskWorker w) {
                        // A generous model capacity, so only the browser capacity limits the run.
                        List<Resource> resources = new ArrayList<Resource>();
                        resources.add(new Resource("model:local/m", 16));
                        resources.add(new Resource("browser", browserSlots > 0 ? browserSlots
                                : OrchestratorSettings.DEFAULT_BROWSER_SLOTS));
                        return resources;
                    }

                    public String endpointOf(TaskWorker w) {
                        return "local";
                    }
                }, new AtomicLong(1_000L)::incrementAndGet, new TaskExecutor.Listener() {
            public void taskStarted(ResearchTask task) {
            }

            public void taskFinished(ResearchTask task, WorkerResult result) {
            }

            public void tokensSpent(long tokens) {
            }

            public void log(String line) {
            }
        });
    }

    private static OrchestratorRun run() {
        return OrchestratorRun.builder("run-1", "q", 1_000L).state(OrchestratorRun.State.RUNNING)
                .sections(ResearchOutline.ofQuestion("q").getSections()).envelope(8, 12, 0, 0).build();
    }

    private static List<PlannedTask> packages(String... ids) {
        List<PlannedTask> planned = new ArrayList<PlannedTask>();
        for (String id : ids) {
            planned.add(new PlannedTask(id, "web-researcher", "query " + id, "",
                    Collections.singletonList("question"), Collections.<String>emptyList()));
        }
        return planned;
    }

    @Test
    public void eachSearchRunBrowsesInItsOwnUserContextAndThePoolSetsTheParallelism() {
        FakePool pool = new FakePool(3);
        InMemoryTaskBoard board = new InMemoryTaskBoard();
        WebAcquisition acquisition = UserContextWebAcquisition.over(pool, chatBrowser, searchRun(150));
        TaskExecutor executor = executor(board, acquisition, UserContextWebAcquisition.browserSlots(pool));
        OrchestratorRun run = run();
        executor.submit(run, packages("a", "b", "c", "d", "e", "f"), OrchestratorSettings.defaults());

        executor.runReady(run, new AtomicBoolean(), () -> true);

        for (ResearchTask task : board.tasks()) {
            assertEquals(task.getTaskId(), TaskState.COMPLETED, task.getState());
        }
        assertEquals("one user context per search run", 6, pool.leases.size());
        Set<ToolInvoker> distinct = new HashSet<ToolInvoker>(lanesUsed);
        assertEquals("every run drove its own user context", 6, distinct.size());
        assertFalse("the chat browser is not used by workers", lanesUsed.contains(chatBrowser));
        assertEquals("three packages browsed at the same time", 3, maxRunning.get());
        assertTrue("never more user contexts than the pool's capacity", pool.maxOpen.get() <= 3);
        for (FakeLease lease : pool.leases) {
            assertFalse("lease " + lease.id + " given back", lease.isOpen());
            assertEquals(UserContextWebAcquisition.WORKER_ID, lease.request.workerId());
        }
        assertEquals(0, pool.open.get());
    }

    @Test
    public void withoutWorkerBrowsersTheRunsShareTheChatBrowserOneAfterAnother() {
        for (BrowserUserContextPool none : Arrays.asList(null, BrowserUserContextPool.unavailable("no browser"))) {
            lanesUsed.clear();
            maxRunning.set(0);
            InMemoryTaskBoard board = new InMemoryTaskBoard();
            WebAcquisition acquisition = UserContextWebAcquisition.over(none, chatBrowser, searchRun(40));
            assertEquals(0, UserContextWebAcquisition.browserSlots(none));
            TaskExecutor executor = executor(board, acquisition, UserContextWebAcquisition.browserSlots(none));
            OrchestratorRun run = run();
            executor.submit(run, packages("a", "b", "c"), OrchestratorSettings.defaults());

            executor.runReady(run, new AtomicBoolean(), () -> true);

            assertEquals(3, lanesUsed.size());
            for (ToolInvoker lane : lanesUsed) {
                assertSame(chatBrowser, lane);
            }
            assertEquals("the browser-slot setting (1) bounds the shared browser", 1, maxRunning.get());
        }
    }

    @Test
    public void theUserContextIsGivenBackWhenTheSearchRunFails() {
        FakePool pool = new FakePool(2);
        WebAcquisition acquisition = UserContextWebAcquisition.over(pool, chatBrowser,
                (browser, query, research, accepted, maxDurationMillis, tokens, exhausted) -> {
                    throw new IllegalStateException("loop crashed");
                });
        try {
            acquisition.acquire("q", null, source -> { }, 0L, new AtomicLong(), () -> false);
            fail("the failure propagates");
        } catch (IllegalStateException expected) {
            assertEquals("loop crashed", expected.getMessage());
        }
        assertEquals(1, pool.leases.size());
        assertFalse(pool.leases.get(0).isOpen());
    }

    @Test
    public void noFreeUserContextIsARetryableStopAndACancelledWaitIsACancel() {
        FakePool pool = new FakePool(1);
        pool.refuse = new BrowserCapacityException("all user contexts busy");
        WebAcquisition acquisition = UserContextWebAcquisition.over(pool, chatBrowser, searchRun(1));
        WebAcquisition.Result busy = acquisition.acquire("q", null, source -> { }, 0L, new AtomicLong(), () -> false);
        assertEquals(ResearchStopReason.MCP_UNAVAILABLE, busy.getStopReason());
        assertTrue(busy.getSummary(), busy.getSummary().contains("all user contexts busy"));
        assertEquals(WorkerResult.Kind.RETRYABLE, WebResearchWorker.map(busy.getStopReason(), "").getKind());

        pool.refuse = null;
        pool.interrupt = true;
        WebAcquisition.Result cancelled = acquisition.acquire("q", null, source -> { }, 0L, new AtomicLong(), () -> false);
        assertTrue("interrupt status kept", Thread.interrupted());
        assertEquals(ResearchStopReason.USER_CANCELLED, cancelled.getStopReason());
        assertTrue("nothing ran", lanesUsed.isEmpty());
    }

    @Test
    public void theProductiveCapacityUsesThePoolsCapacityForBrowserBoundWorkers() {
        FakePool pool = new FakePool(4);
        WebResearchWorker worker = new WebResearchWorker(
                UserContextWebAcquisition.over(pool, chatBrowser, searchRun(1)), (tool, args) -> "{}");
        int browser = -1;
        for (ExecutorCapacity.Resource resource : ExecutorCapacity.fromPort(null,
                UserContextWebAcquisition.browserSlots(pool)).resourcesOf(worker)) {
            if ("browser".equals(resource.getKey())) {
                browser = resource.getCapacity();
            }
        }
        assertEquals("the pool's 4, not the setting's 1", 4, browser);
    }

    /** A pool whose acquire blocks until it is interrupted, or hands out a lease after {@code delayMillis}. */
    static final class SlowPool implements BrowserUserContextPool {
        final FakePool inner = new FakePool(2);
        volatile long delayMillis = -1;
        volatile long requestedTimeout;

        public BrowserUserContextLease acquire(BrowserUserContextRequest request)
                throws BrowserCapacityException, InterruptedException {
            requestedTimeout = request.acquireTimeoutMillis();
            if (delayMillis < 0) {
                Thread.sleep(60_000L); // "all user contexts busy" — only an interrupt ends this wait
            } else {
                Thread.sleep(delayMillis);
            }
            return inner.acquire(request);
        }

        public int capacity() {
            return 2;
        }
    }

    @Test(timeout = 10_000L)
    public void aCancelledTurnEndsTheWaitForAUserContextAtOnce() throws Exception {
        SlowPool pool = new SlowPool();
        final UserContextWebAcquisition acquisition = (UserContextWebAcquisition) UserContextWebAcquisition.over(
                pool, chatBrowser, searchRun(1));
        final java.util.concurrent.atomic.AtomicReference<WebAcquisition.Result> result =
                new java.util.concurrent.atomic.AtomicReference<WebAcquisition.Result>();
        Thread worker = new Thread(() -> result.set(
                acquisition.acquire("q", null, source -> { }, 0L, new AtomicLong(), () -> false)));
        worker.start();
        Thread.sleep(200L);
        long start = System.nanoTime();
        acquisition.cancelWaits();
        worker.join(5_000L);
        assertFalse(worker.isAlive());
        assertTrue("ended at once", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                < 2_000L);
        assertEquals(ResearchStopReason.USER_CANCELLED, result.get().getStopReason());
        assertTrue("nothing ran", lanesUsed.isEmpty());
    }

    @Test
    public void cancelWaitsNeverInterruptsARunningSearchRun() throws Exception {
        FakePool pool = new FakePool(1);
        final UserContextWebAcquisition acquisition = (UserContextWebAcquisition) UserContextWebAcquisition.over(
                pool, chatBrowser, searchRun(300));
        Thread worker = new Thread(() -> acquisition.acquire("q", null, source -> { }, 0L, new AtomicLong(), () -> false));
        worker.start();
        Thread.sleep(100L); // the search run is running now
        acquisition.cancelWaits();
        worker.join(5_000L);
        assertEquals(1, lanesUsed.size());
        assertFalse(pool.leases.get(0).isOpen());
    }

    @Test
    public void theWaitIsBoundedByAndTakenOffThePackagesTime() {
        SlowPool pool = new SlowPool();
        pool.delayMillis = 200L;
        final AtomicLong runGot = new AtomicLong(-1);
        WebAcquisition acquisition = UserContextWebAcquisition.over(pool, chatBrowser,
                (browser, query, research, accepted, maxDurationMillis, tokens, exhausted) -> {
                    runGot.set(maxDurationMillis);
                    return new WebAcquisition.Result(ResearchStopReason.SUFFICIENT_EVIDENCE, "");
                });
        acquisition.acquire("q", null, source -> { }, 1_000L, new AtomicLong(), () -> false);
        assertEquals("the wait may not exceed the package's time", 1_000L, pool.requestedTimeout);
        assertTrue("the search run gets the time that is left: " + runGot.get(),
                runGot.get() > 0 && runGot.get() <= 800L);

        WebAcquisition.Result late = acquisition.acquire("q", null, source -> { }, 100L, new AtomicLong(), () -> false);
        assertEquals(ResearchStopReason.TIME_BUDGET_EXHAUSTED, late.getStopReason());
        for (FakeLease lease : pool.inner.leases) {
            assertFalse("given back", lease.isOpen());
        }
    }
}
