package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * One worker browser process as the {@link WorkerBrowserLane} drives it: user contexts (WebDriver BiDi
 * {@code browser.createUserContext}) and their browsing contexts (tabs, {@code browsingContext.create}).
 * Every method except {@link #terminate()} is called ONLY on the lane's owner thread — the thread that ran
 * {@link Factory#launch()} — because Playwright Java is not thread-safe. Abstracted so the lane and pool
 * scheduling is testable without a browser.
 */
interface WorkerBrowserBackend {

    /** Launches one worker browser; runs on the lane's owner thread. */
    interface Factory {
        WorkerBrowserBackend launch() throws BrowserException;
    }

    /**
     * Create an isolated user context. @param storageState a state exported by {@link #storageState} (cookies,
     * local storage) to restore, or null for a fresh one. @return the backend's id of the user context
     */
    String newUserContext(String storageState) throws BrowserException;

    /** Open a browsing context (tab) in the user context. @return the backend's id of the tab */
    String newBrowsingContext(String userContextId) throws BrowserException;

    /** Run one {@code web_*} tool in the tab and return its text result (the session browser's contract). */
    String call(String browsingContextId, String tool, Map<String, Object> arguments) throws BrowserException;

    /** The user context's cookies and local storage as an opaque JSON document. */
    String storageState(String userContextId) throws BrowserException;

    /** Close one tab. Idempotent, never throws. */
    void closeBrowsingContext(String browsingContextId);

    /** Close the user context with all its tabs; its state is gone. Idempotent, never throws. */
    void closeUserContext(String userContextId);

    /**
     * Dispatch browser events while idle (route interception, popups) until {@code wake} holds or the slice
     * ends. @return false when there is nothing to pump — the lane then waits on its queue instead
     */
    boolean pumpEvents(BooleanSupplier wake, long timeoutMillis);

    /** False once the browser process is gone (crash, the user closed it). */
    boolean isConnected();

    /** Orderly teardown: every user context, the browser and the driver child. Idempotent. */
    void close();

    /** Emergency teardown from a FOREIGN thread while the owner is stuck; must never block on the owner. */
    void terminate();
}
