package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One worker's user context (WebDriver BiDi {@code browser.UserContext}) in a worker browser of its chat:
 * cookies, local storage and sign-ins of its own. It opens with one browsing context (tab); more tabs share
 * its state. Close it in a {@code finally}: that closes every tab, removes the user context and frees the
 * capacity. Thread-safe; {@link #close()} is idempotent and may come from any thread (cancellation).
 */
public final class WorkerUserContext implements AutoCloseable {

    private final PlaywrightUserContextPool pool;
    private final PlaywrightUserContextPool.LaneSlot slot;
    private final String id;
    private final String label;
    private final String backendId;
    private final List<WorkerBrowsingContext> tabs = new ArrayList<WorkerBrowsingContext>();
    private final WorkerBrowsingContext first;
    /** Tabs being created right now (counted against the limit before the browser call). */
    private int reservedTabs;
    private int tabNumbers = 1;
    private volatile boolean closed;

    WorkerUserContext(PlaywrightUserContextPool pool, PlaywrightUserContextPool.LaneSlot slot, String id,
                      String label, String backendId, String firstTabId) {
        this.pool = pool;
        this.slot = slot;
        this.id = id;
        this.label = label == null ? "" : label;
        this.backendId = backendId;
        this.first = new WorkerBrowsingContext(this, firstTabId, id + "/bc1");
        tabs.add(first);
    }

    /** {@code <chat>/<worker browser>/uc<n>}: unique per pool, names chat and process. */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String chatKey() {
        return slot.chat.chatKey();
    }

    /** The worker browser process this user context lives in (diagnostics, tests). */
    public String processId() {
        return slot.lane.laneId();
    }

    /** The tab the user context opened with. */
    public WorkerBrowsingContext firstBrowsingContext() {
        return first;
    }

    public synchronized List<WorkerBrowsingContext> browsingContexts() {
        return Collections.unmodifiableList(new ArrayList<WorkerBrowsingContext>(tabs));
    }

    /** Open another tab in this user context (same cookies and sign-in). */
    public WorkerBrowsingContext openBrowsingContext() throws BrowserException, InterruptedException {
        int number;
        synchronized (this) {
            requireOpen();
            int open = 0;
            for (WorkerBrowsingContext tab : tabs) {
                if (tab.isOpen()) {
                    open++;
                }
            }
            int limit = pool.limits().maxBrowsingContextsPerUserContext;
            if (open + reservedTabs >= limit) {
                throw new BrowserException("Tab limit reached: a worker user context may hold " + limit
                        + " browsing contexts.");
            }
            reservedTabs++;
            number = ++tabNumbers;
        }
        String tabId;
        try {
            tabId = lane().call(new WorkerBrowserLane.LaneTask<String>() {
                public String run(WorkerBrowserBackend backend) throws BrowserException {
                    return backend.newBrowsingContext(backendId);
                }
            }, pool.limits().callTimeoutMillis);
        } catch (BrowserException | InterruptedException | RuntimeException failed) {
            synchronized (this) {
                reservedTabs--;
            }
            throw failed;
        }
        WorkerBrowsingContext tab = new WorkerBrowsingContext(this, tabId, id + "/bc" + number);
        synchronized (this) {
            reservedTabs--;
            if (closed) {
                closeTab(tabId);
                requireOpen();
            }
            tabs.add(tab);
        }
        return tab;
    }

    /** Cookies and local storage as an opaque JSON document, for {@code acquire(..., storageState, ...)}. */
    public String exportState() throws BrowserException, InterruptedException {
        requireOpen();
        return lane().call(new WorkerBrowserLane.LaneTask<String>() {
            public String run(WorkerBrowserBackend backend) throws BrowserException {
                return backend.storageState(backendId);
            }
        }, pool.limits().callTimeoutMillis);
    }

    public boolean isOpen() {
        return !closed && slot.lane.isAlive();
    }

    /** Close every tab and the user context; its state is gone (export it first to keep it). */
    @Override
    public void close() {
        List<WorkerBrowsingContext> open;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            open = new ArrayList<WorkerBrowsingContext>(tabs);
        }
        for (WorkerBrowsingContext tab : open) {
            tab.markClosed();
        }
        // Queued behind a call in flight of this user context; the capacity is free at once, a following
        // acquire on the same worker browser runs after this teardown (one FIFO queue per process).
        lane().submit(new WorkerBrowserLane.LaneTask<Object>() {
            public Object run(WorkerBrowserBackend backend) {
                backend.closeUserContext(backendId);
                return null;
            }
        });
        pool.release(slot);
    }

    void closeTab(final String tabId) {
        lane().submit(new WorkerBrowserLane.LaneTask<Object>() {
            public Object run(WorkerBrowserBackend backend) {
                backend.closeBrowsingContext(tabId);
                return null;
            }
        });
    }

    WorkerBrowserLane lane() {
        return slot.lane;
    }

    PlaywrightUserContextPool pool() {
        return pool;
    }

    boolean isClosed() {
        return closed;
    }

    private void requireOpen() throws BrowserException {
        if (closed) {
            throw new BrowserException(WorkerBrowserLane.LOST_MARKER + " — user context " + id + " is closed.");
        }
        if (!slot.lane.isAlive()) {
            String reason = slot.lane.lostReason();
            throw new BrowserException(WorkerBrowserLane.LOST_MARKER + " — the worker browser of " + id
                    + " is gone" + (reason == null ? "." : ": " + reason));
        }
    }

    @Override
    public String toString() {
        return id + (label.isEmpty() ? "" : " (" + label + ")");
    }
}
