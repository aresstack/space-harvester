package com.aresstack.askai.browser.playwright;

/**
 * The resource limits of the worker browsers — every value comes from the research settings; the defaults
 * here are only the named fallbacks. Browser capacity is configured independently of the model capacity.
 *
 * <ul>
 *   <li>{@code maxBrowserProcesses}: worker browser processes over ALL chats (each one Playwright driver child
 *       plus one browser). This is also the number of browser operations that run truly in parallel.</li>
 *   <li>{@code maxUserContextsPerProcess}: user contexts one worker browser hosts; their commands share
 *       the process's single Playwright call lane.</li>
 *   <li>{@code maxBrowsingContextsPerUserContext}: tabs one worker may hold open.</li>
 *   <li>{@code maxConcurrentPerSite}: browser operations against the same host at the same time, over all
 *       workers and chats (search engines count as one site).</li>
 *   <li>{@code acquireTimeoutMillis}: how long a worker waits for a free user context.</li>
 *   <li>{@code callTimeoutMillis}: bound of one browser call (0 = unbounded); a hung call terminates its
 *       worker browser.</li>
 *   <li>{@code idleStopMillis}: a worker browser without user contexts is stopped after this idle time.</li>
 * </ul>
 */
public final class WorkerBrowserLimits {

    public static final int DEFAULT_MAX_BROWSER_PROCESSES = 3;
    public static final int DEFAULT_MAX_USER_CONTEXTS_PER_PROCESS = 4;
    public static final int DEFAULT_MAX_BROWSING_CONTEXTS_PER_USER_CONTEXT = 4;
    public static final int DEFAULT_MAX_CONCURRENT_PER_SITE = 2;
    public static final int DEFAULT_ACQUIRE_TIMEOUT_SECONDS = 120;

    public final int maxBrowserProcesses;
    public final int maxUserContextsPerProcess;
    public final int maxBrowsingContextsPerUserContext;
    public final int maxConcurrentPerSite;
    public final long acquireTimeoutMillis;
    public final long callTimeoutMillis;
    public final long idleStopMillis;
    /** Site lease rules: hold and wait deadlines, per-site overrides. */
    public final SiteLeasePolicy sites;

    public WorkerBrowserLimits(int maxBrowserProcesses, int maxUserContextsPerProcess,
                               int maxBrowsingContextsPerUserContext, int maxConcurrentPerSite,
                               long acquireTimeoutMillis, long callTimeoutMillis, long idleStopMillis) {
        this(maxBrowserProcesses, maxUserContextsPerProcess, maxBrowsingContextsPerUserContext, maxConcurrentPerSite,
                acquireTimeoutMillis, callTimeoutMillis, idleStopMillis, SiteLeasePolicy.defaults());
    }

    public WorkerBrowserLimits(int maxBrowserProcesses, int maxUserContextsPerProcess,
                               int maxBrowsingContextsPerUserContext, int maxConcurrentPerSite,
                               long acquireTimeoutMillis, long callTimeoutMillis, long idleStopMillis,
                               SiteLeasePolicy sites) {
        this.sites = sites == null ? SiteLeasePolicy.defaults() : sites;
        this.maxBrowserProcesses = Math.max(1, maxBrowserProcesses);
        this.maxUserContextsPerProcess = Math.max(1, maxUserContextsPerProcess);
        this.maxBrowsingContextsPerUserContext = Math.max(1, maxBrowsingContextsPerUserContext);
        this.maxConcurrentPerSite = Math.max(1, maxConcurrentPerSite);
        this.acquireTimeoutMillis = Math.max(0L, acquireTimeoutMillis);
        this.callTimeoutMillis = Math.max(0L, callTimeoutMillis);
        this.idleStopMillis = Math.max(0L, idleStopMillis);
    }

    public static WorkerBrowserLimits defaults() {
        return new WorkerBrowserLimits(DEFAULT_MAX_BROWSER_PROCESSES, DEFAULT_MAX_USER_CONTEXTS_PER_PROCESS,
                DEFAULT_MAX_BROWSING_CONTEXTS_PER_USER_CONTEXT, DEFAULT_MAX_CONCURRENT_PER_SITE,
                DEFAULT_ACQUIRE_TIMEOUT_SECONDS * 1000L, 300_000L, 300_000L);
    }

    /** How many user contexts may exist at the same time over all chats. */
    public int capacity() {
        return maxBrowserProcesses * maxUserContextsPerProcess;
    }

    @Override
    public String toString() {
        return "processes=" + maxBrowserProcesses + " userContexts/process=" + maxUserContextsPerProcess
                + " tabs/userContext=" + maxBrowsingContextsPerUserContext + " perSite=" + maxConcurrentPerSite
                + " acquire=" + acquireTimeoutMillis + "ms call=" + callTimeoutMillis + "ms idleStop="
                + idleStopMillis + "ms sites[" + sites + "]";
    }
}
