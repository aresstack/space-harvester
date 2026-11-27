package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Isolated browser user contexts for parallel research workers (WebDriver BiDi terms: one
 * {@code browser.UserContext} per worker, its tabs are {@code browsingContext.BrowsingContext}s).
 *
 * <p>The chat's own browser (the persistent Camoufox profile behind {@link PlaywrightBrowserRuntime}) stays
 * exactly as it is. Workers get their user contexts in separate WORKER BROWSERS ({@link WorkerBrowserLane}):
 * each one is one Playwright instance with its own owner thread and one non-persistent browser process,
 * hosting up to {@link WorkerBrowserLimits#maxUserContextsPerProcess} user contexts. Playwright Java runs one
 * call at a time per instance, so the number of worker browsers is the number of browser operations that run
 * in parallel; user contexts per process bound memory, not parallelism. A worker browser belongs to ONE chat
 * — two chats never share a browser process — and the process cap holds over all chats.</p>
 *
 * <p>Placement: a new user context goes to an idle worker browser of its chat; if every one is busy and the
 * process cap allows it, a new worker browser starts (parallelism first); otherwise the least-loaded one of
 * the chat takes it; at the cap, an idle worker browser of another chat is stopped to make room; otherwise
 * the worker waits up to the acquire timeout. Worker browsers without user contexts stop after the idle
 * time. Thread-safe.</p>
 */
public final class PlaywrightUserContextPool implements AutoCloseable {

    /**
     * Documented DEV/TEST hand-off: a browser executable for the chrome/chromium worker channel (e.g. a bundled
     * Chromium on a build machine). Never a persisted value; production uses Camoufox or an installed browser.
     */
    public static final String WORKER_EXECUTABLE_PROPERTY = "askai.research.browser.workerExecutable";

    /** Leads every error that means the worker's user context, tab or worker browser is gone. */
    public static final String USER_CONTEXT_LOST = WorkerBrowserLane.LOST_MARKER;

    /**
     * Where saved worker sign-in states belong: next to the chat browser's profile
     * ({@code <APPDATA>/.askai/browser-profiles/worker-state}), never in a research project folder.
     */
    public static Path defaultStateDirectory() {
        return CamoufoxProvisioning.appDirectory().resolve("browser-profiles").resolve("worker-state");
    }

    /** One worker browser and the user contexts it hosts (including reservations in flight). */
    static final class LaneSlot {
        final WorkerBrowserLane lane;
        final Chat chat;
        int userContexts;
        long idleToken;
        boolean stopping;

        LaneSlot(WorkerBrowserLane lane, Chat chat) {
            this.lane = lane;
            this.chat = chat;
        }
    }

    private volatile WorkerBrowserLimits limits;
    private final SiteConcurrencyGate sites;
    private final List<LaneSlot> lanes = new ArrayList<LaneSlot>();
    private ScheduledExecutorService idleTimer;
    private long nextLane;
    private long nextUserContext;
    private boolean closed;

    public PlaywrightUserContextPool(WorkerBrowserLimits limits) {
        this.limits = limits == null ? WorkerBrowserLimits.defaults() : limits;
        this.sites = new SiteConcurrencyGate(this.limits.maxConcurrentPerSite, this.limits.sites,
                SiteConcurrencyGate.SYSTEM_CLOCK);
    }

    /** Apply changed settings; running worker browsers and leases stay, new placements follow the new limits. */
    public void updateLimits(WorkerBrowserLimits changed) {
        if (changed == null) {
            return;
        }
        synchronized (this) {
            this.limits = changed;
            notifyAll();
        }
        sites.setLimit(changed.maxConcurrentPerSite);
        sites.setPolicy(changed.sites);
    }

    public WorkerBrowserLimits limits() {
        return limits;
    }

    /** How many user contexts may exist at the same time over all chats. */
    public int capacity() {
        return limits.capacity();
    }

    /** The scope of one chat: its worker browsers start with that chat's browser options. */
    public Chat openChat(String chatKey, PlaywrightBrowserRuntime.Options options) {
        String executable = System.getProperty(WORKER_EXECUTABLE_PROPERTY, "").trim();
        Path override = executable.isEmpty() ? null : Paths.get(executable);
        return openChat(chatKey, PlaywrightWorkerBrowserBackend.factory(options, override));
    }

    Chat openChat(String chatKey, WorkerBrowserBackend.Factory factory) {
        if (chatKey == null || chatKey.trim().isEmpty()) {
            throw new IllegalArgumentException("chatKey is required");
        }
        return new Chat(chatKey.trim(), factory);
    }

    /** Worker browser processes currently running or starting, over all chats. */
    public synchronized int processCount() {
        purgeDead();
        return lanes.size();
    }

    /** Worker browser processes of one chat. */
    public synchronized int processCount(Chat chat) {
        purgeDead();
        int count = 0;
        for (LaneSlot slot : lanes) {
            if (slot.chat == chat) {
                count++;
            }
        }
        return count;
    }

    /** User contexts held (or being created) over all chats. */
    public synchronized int userContextCount() {
        purgeDead();
        int count = 0;
        for (LaneSlot slot : lanes) {
            count += slot.userContexts;
        }
        return count;
    }

    /** Stop every worker browser of every chat. Idempotent. */
    @Override
    public void close() {
        List<LaneSlot> stopping;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stopping = new ArrayList<LaneSlot>(lanes);
            lanes.clear();
            notifyAll();
            if (idleTimer != null) {
                idleTimer.shutdownNow();
            }
        }
        for (LaneSlot slot : stopping) {
            slot.lane.close();
        }
    }

    // ------------------------------------------------------------------ placement (all under the monitor)

    private WorkerUserContext acquire(Chat chat, String label, String storageState, long timeoutMillis)
            throws BrowserException, InterruptedException {
        WorkerBrowserLimits current = limits;
        // A caller may wait LESS than the setting (e.g. a package's remaining time), never longer.
        long timeout = timeoutMillis > 0 ? Math.min(timeoutMillis, current.acquireTimeoutMillis)
                : current.acquireTimeoutMillis;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeout));
        LaneSlot chosen;
        boolean startIt = false;
        String id;
        synchronized (this) {
            while (true) {
                if (closed || chat.closed) {
                    throw new BrowserException(WorkerBrowserLane.LOST_MARKER + " — the worker browser pool of chat "
                            + chat.chatKey + " is closed.");
                }
                purgeDead();
                current = limits;
                chosen = leastLoaded(chat, current);
                boolean roomForProcess = lanes.size() < current.maxBrowserProcesses;
                if ((chosen == null || chosen.userContexts > 0) && roomForProcess) {
                    chosen = newSlot(chat);
                    startIt = true;
                } else if (chosen == null) {
                    // At the cap: stop an idle worker browser of another chat. Its process still counts until
                    // it is gone; its removal wakes this wait, which then starts the new one.
                    reclaimIdleLaneOfAnotherChat(chat);
                }
                if (chosen != null) {
                    break;
                }
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    throw new BrowserException("No free browser user context within " + (timeout / 1000L)
                            + " s (" + current.maxBrowserProcesses + " worker browsers x "
                            + current.maxUserContextsPerProcess + " user contexts are in use).");
                }
                wait(left);
            }
            chosen.userContexts++;
            chosen.idleToken++; // voids a pending idle stop
            id = chat.chatKey + "/" + chosen.lane.laneId() + "/uc" + (++nextUserContext);
        }
        if (startIt) {
            chosen.lane.start(chat.factory);
        }
        final String state = storageState;
        // Who owns a user context created for a caller that was interrupted meanwhile: the owner thread closes
        // it when it finishes after the cancel, the caller when the creation finished just before.
        final Object handoff = new Object();
        final boolean[] abandoned = {false};
        final String[][] made = {null};
        try {
            chosen.lane.awaitStarted();
            String[] created = chosen.lane.call(new WorkerBrowserLane.LaneTask<String[]>() {
                public String[] run(WorkerBrowserBackend backend) throws BrowserException {
                    String userContext = backend.newUserContext(state);
                    String tab;
                    try {
                        tab = backend.newBrowsingContext(userContext);
                    } catch (BrowserException tabFailed) {
                        backend.closeUserContext(userContext);
                        throw tabFailed;
                    }
                    synchronized (handoff) {
                        if (abandoned[0]) {
                            backend.closeUserContext(userContext);
                            throw new BrowserException("user context creation cancelled");
                        }
                        made[0] = new String[]{userContext, tab};
                        return made[0];
                    }
                }
            }, current.callTimeoutMillis);
            return new WorkerUserContext(this, chosen, id, label, created[0], created[1]);
        } catch (InterruptedException cancelled) {
            final String[] orphan;
            synchronized (handoff) {
                abandoned[0] = true;
                orphan = made[0];
            }
            if (orphan != null) {
                // FIFO lane: this close runs before any later creation on the same worker browser.
                chosen.lane.submit(new WorkerBrowserLane.LaneTask<Object>() {
                    public Object run(WorkerBrowserBackend backend) {
                        backend.closeUserContext(orphan[0]);
                        return null;
                    }
                });
            }
            failedCreation(chosen);
            throw cancelled;
        } catch (BrowserException | RuntimeException failed) {
            failedCreation(chosen);
            throw failed;
        }
    }

    private void failedCreation(LaneSlot chosen) {
        release(chosen);
        if (!chosen.lane.isAlive()) {
            synchronized (this) {
                lanes.remove(chosen);
                notifyAll();
            }
        }
    }

    /** The chat's live worker browser with the fewest user contexts and room for one more, or null. */
    private LaneSlot leastLoaded(Chat chat, WorkerBrowserLimits current) {
        LaneSlot best = null;
        for (LaneSlot slot : lanes) {
            if (slot.chat != chat || slot.stopping || slot.userContexts >= current.maxUserContextsPerProcess) {
                continue;
            }
            if (best == null || slot.userContexts < best.userContexts) {
                best = slot;
            }
        }
        return best;
    }

    private LaneSlot newSlot(Chat chat) {
        LaneSlot slot = new LaneSlot(new WorkerBrowserLane("w" + (++nextLane), chat.chatKey), chat);
        lanes.add(slot);
        return slot;
    }

    /**
     * At the process cap: stop one idle worker browser of ANOTHER chat to make room — unless one is already
     * stopping (its exit makes the room; stopping a second would only cost another chat its warm browser).
     */
    private void reclaimIdleLaneOfAnotherChat(Chat chat) {
        for (LaneSlot slot : lanes) {
            if (slot.stopping) {
                return;
            }
        }
        for (LaneSlot slot : lanes) {
            if (slot.chat != chat && slot.userContexts == 0) {
                beginStop(slot);
                return;
            }
        }
    }

    /**
     * Orderly stop of one worker browser. The lane stays in {@code lanes} (so it still counts against the process
     * cap) until its process is really gone; then it is removed and waiting acquisitions are woken.
     */
    private void beginStop(final LaneSlot slot) {
        if (slot.stopping) {
            return;
        }
        slot.stopping = true;
        slot.lane.close();
        slot.lane.stopped().whenComplete((done, failure) -> {
            synchronized (PlaywrightUserContextPool.this) {
                lanes.remove(slot);
                notifyAll();
            }
        });
    }

    private void purgeDead() {
        for (Iterator<LaneSlot> it = lanes.iterator(); it.hasNext(); ) {
            LaneSlot slot = it.next();
            if (!slot.lane.isAlive() && slot.lane.lostReason() != null) {
                it.remove();
            }
        }
    }

    /** A user context ended (or its creation failed): free the capacity, arm the idle stop. */
    void release(final LaneSlot slot) {
        synchronized (this) {
            slot.userContexts = Math.max(0, slot.userContexts - 1);
            if (slot.userContexts == 0 && !closed && !slot.stopping && lanes.contains(slot)) {
                final long token = ++slot.idleToken;
                long idle = limits.idleStopMillis;
                if (idle <= 0) {
                    stopIdle(slot, token);
                } else {
                    try {
                        idleTimer().schedule(new Runnable() {
                            public void run() {
                                stopIdle(slot, token);
                            }
                        }, idle, TimeUnit.MILLISECONDS);
                    } catch (RuntimeException timerGone) {
                        stopIdle(slot, token);
                    }
                }
            }
            notifyAll();
        }
    }

    private synchronized void stopIdle(LaneSlot slot, long token) {
        if (slot.idleToken != token || slot.userContexts > 0 || slot.stopping || !lanes.contains(slot)) {
            return; // reused meanwhile, or already gone
        }
        beginStop(slot);
    }

    private ScheduledExecutorService idleTimer() {
        if (idleTimer == null) {
            idleTimer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "worker-browser-idle-stop");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return idleTimer;
    }

    SiteConcurrencyGate sites() {
        return sites;
    }

    private synchronized void closeChat(Chat chat) {
        // The open user contexts of this chat end with their worker browsers; each still counts until it is gone.
        for (LaneSlot slot : new ArrayList<LaneSlot>(lanes)) {
            if (slot.chat == chat) {
                beginStop(slot);
            }
        }
        notifyAll();
    }

    /**
     * One chat's view of the pool. User contexts acquired here live only in worker browsers of this chat;
     * {@link #close()} ends all of them (the chat's research session ended).
     */
    public final class Chat implements AutoCloseable {
        private final String chatKey;
        private final WorkerBrowserBackend.Factory factory;
        private volatile boolean closed;

        private Chat(String chatKey, WorkerBrowserBackend.Factory factory) {
            this.chatKey = chatKey;
            this.factory = factory;
        }

        public String chatKey() {
            return chatKey;
        }

        /**
         * A fresh user context with one browsing context (tab) open.
         *
         * @param label         who holds it (worker/task), for diagnostics
         * @param storageState  a state exported by {@link WorkerUserContext#exportState()} to restore, or null
         * @param timeoutMillis how long to wait for capacity (0 = the configured acquire timeout)
         */
        public WorkerUserContext acquire(String label, String storageState, long timeoutMillis)
                throws BrowserException, InterruptedException {
            return PlaywrightUserContextPool.this.acquire(this, label, storageState, timeoutMillis);
        }

        /** The pool this chat belongs to. */
        public PlaywrightUserContextPool pool() {
            return PlaywrightUserContextPool.this;
        }

        @Override
        public void close() {
            synchronized (PlaywrightUserContextPool.this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            closeChat(this);
        }
    }
}
