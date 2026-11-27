package com.aresstack.askai.research.runtime.browser;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * An additional browsing context (WebDriver BiDi {@code browsingContext.BrowsingContext}: a tab) inside a
 * worker's user context. Closing it closes only this tab; closing the user context closes all its tabs.
 */
public interface BrowsingContextLease extends AutoCloseable {

    /** The pool's id of this browsing context. */
    String browsingContextId();

    /** The {@code web_*} tools of this tab (same contract as {@link BrowserUserContextLease#browser()}). */
    ToolInvoker tools();

    @Override
    void close();
}
