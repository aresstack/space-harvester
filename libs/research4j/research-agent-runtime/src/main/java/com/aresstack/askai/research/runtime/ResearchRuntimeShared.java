package com.aresstack.askai.research.runtime;

import com.aresstack.askai.browser.playwright.BrowserLauncher;
import com.aresstack.askai.browser.playwright.SearchBrowser;

/**
 * What all research sessions of one AskAI JVM share (askai #48, ADR-020): the headless search browser, one per
 * AskAI, reused across searches and sessions and restarted after a crash by {@link SearchBrowser} itself. The
 * research browser (one per chat, worker user contexts) stays separate and is not held here. Which browser runs is
 * the host's browser runtime, handed over with {@link #configure(BrowserLauncher)} before the first search.
 */
public final class ResearchRuntimeShared implements AutoCloseable {

    private static ResearchRuntimeShared process;
    private static BrowserLauncher processLauncher;

    private final BrowserLauncher searchBrowserLauncher;
    private SearchBrowser searchBrowser;
    /** The search browser's window setting (default headless); the next search follows a change. */
    private volatile boolean searchBrowserHeadless = true;
    /** The engine pause after a challenge (settings), applied to the search browser when it is created. */
    private long enginePauseMillis;
    private long enginePauseCapMillis;
    private boolean closed;

    /** @param searchBrowserLauncher the host's browser runtime the search browser starts from */
    public ResearchRuntimeShared(BrowserLauncher searchBrowserLauncher) {
        if (searchBrowserLauncher == null) {
            throw new IllegalArgumentException("a browser launcher is required");
        }
        this.searchBrowserLauncher = searchBrowserLauncher;
    }

    /** The host's browser runtime for {@link #process()}; called once by the host before the first search. */
    public static synchronized void configure(BrowserLauncher launcher) {
        if (launcher == null) {
            throw new IllegalArgumentException("a browser launcher is required");
        }
        processLauncher = launcher;
    }

    /** The one instance of this JVM; created on first use, closed when the JVM ends. */
    public static synchronized ResearchRuntimeShared process() {
        if (process == null) {
            if (processLauncher == null) {
                throw new IllegalStateException("no browser launcher configured: the host calls configure() first");
            }
            final ResearchRuntimeShared shared = new ResearchRuntimeShared(processLauncher);
            Runtime.getRuntime().addShutdownHook(new Thread(shared::close, "research-search-browser-close"));
            process = shared;
        }
        return process;
    }

    /** The search browser; created on first use, started on the first search. */
    public synchronized SearchBrowser searchBrowser() {
        if (closed) {
            throw new IllegalStateException("research runtime resources are closed");
        }
        if (searchBrowser == null) {
            searchBrowser = new SearchBrowser(searchBrowserLauncher, () -> searchBrowserHeadless);
            searchBrowser.setEnginePause(enginePauseMillis, enginePauseCapMillis);
        }
        return searchBrowser;
    }

    /** Whether the search browser runs headless; a running browser in the other mode restarts at the next search. */
    public void setSearchBrowserHeadless(boolean headless) {
        this.searchBrowserHeadless = headless;
    }

    /**
     * The search engines' pause after a challenge (Search → Browser (SERP)): {@code pauseMillis} the first time,
     * doubled with every further challenge in a row up to {@code capMillis}. Applies to the next request.
     */
    public synchronized void setEnginePause(long pauseMillis, long capMillis) {
        this.enginePauseMillis = pauseMillis;
        this.enginePauseCapMillis = capMillis;
        if (searchBrowser != null) {
            searchBrowser.setEnginePause(pauseMillis, capMillis);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (searchBrowser != null) {
            searchBrowser.close();
            searchBrowser = null;
        }
    }
}
