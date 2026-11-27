package com.aresstack.askai.research.runtime.browser;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * One worker's isolated browser user context (WebDriver BiDi {@code browser.UserContext}): its own cookies,
 * local storage and sign-ins, invisible to every other worker and every other chat. It opens with one
 * browsing context (tab), {@link #browser()}; further tabs in the SAME user context share its sign-in.
 *
 * <p>Every browsing context speaks the unchanged {@code web_*} tool contract of the session browser, including
 * the {@code capture_id} convention: pages a worker reads land in the session's capture store, so source
 * acceptance, scope checks, duplicate detection and the user's approvals stay the session's one process.</p>
 *
 * <p>{@link #close()} closes every tab and the user context, saves its state when the request carried a state
 * key, and frees the capacity. It is idempotent and may be called from any thread (cancellation); calls in
 * flight then fail with {@link ToolInvoker.EndpointUnavailable}.</p>
 */
public interface BrowserUserContextLease extends AutoCloseable {

    /** The pool's id of this user context (unique per pool; diagnostics and attribution). */
    String userContextId();

    /** The request this lease was acquired for. */
    BrowserUserContextRequest request();

    /** The first browsing context (tab) of this user context; the lane the acquisition drives. */
    ToolInvoker browser();

    /**
     * Open another browsing context (tab) in this user context. Bounded by the tabs-per-user-context setting.
     *
     * @throws BrowserCapacityException the tab limit is reached or the user context is gone
     */
    BrowsingContextLease openBrowsingContext() throws BrowserCapacityException;

    /** Whether the lease still holds a live user context (false after close or a lost browser). */
    boolean isOpen();

    @Override
    void close();
}
