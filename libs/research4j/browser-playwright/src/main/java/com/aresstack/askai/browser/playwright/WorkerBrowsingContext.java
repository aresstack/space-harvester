package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

import java.util.Map;

/**
 * One tab (WebDriver BiDi {@code browsingContext.BrowsingContext}) of a worker user context. {@link #call}
 * runs a {@code web_*} tool with exactly the text contract of the chat browser ({@link BrowserToolSet}), on
 * the owner thread of the worker browser, bounded by the per-call timeout and by the per-site limit.
 */
public final class WorkerBrowsingContext implements AutoCloseable {

    private final WorkerUserContext userContext;
    private final String backendId;
    private final String id;
    /** The site of the page this tab shows (gate key for follow/back), or null before the first page. */
    private volatile String currentSite;
    private volatile boolean closed;
    /** The site whose lease this tab lost (deadline or a waiter took over); its next call there fails typed. */
    private volatile String expiredSite;

    WorkerBrowsingContext(WorkerUserContext userContext, String backendId, String id) {
        this.userContext = userContext;
        this.backendId = backendId;
        this.id = id;
    }

    /** {@code <user context id>/bc<n>}. */
    public String id() {
        return id;
    }

    public WorkerUserContext userContext() {
        return userContext;
    }

    public boolean isOpen() {
        return !closed && userContext.isOpen();
    }

    /**
     * Run one tool in this tab. Failures that mean the tab, its user context or its worker browser is gone
     * start with {@code USER_CONTEXT_LOST}; a timed-out call terminates the worker browser.
     */
    public String call(final String tool, final Map<String, Object> arguments)
            throws BrowserException, InterruptedException {
        if (closed || userContext.isClosed()) {
            throw new BrowserException(WorkerBrowserLane.LOST_MARKER + " — browsing context " + id + " is closed.");
        }
        PlaywrightUserContextPool pool = userContext.pool();
        long timeout = pool.limits().callTimeoutMillis;
        String site = siteKey(tool, arguments);
        if (site != null && site.equals(expiredSite)) {
            expiredSite = null;
            throw new BrowserException(SiteConcurrencyGate.SITE_LEASE_EXPIRED + " — " + id + " lost its place on "
                    + site + "; another worker gets the site. Retry this page later.");
        }
        SiteConcurrencyGate gate = pool.sites();
        SiteConcurrencyGate.Lease lease = gate.enter(site, timeout);
        try {
            String result = userContext.lane().call(new WorkerBrowserLane.LaneTask<String>() {
                public String run(WorkerBrowserBackend backend) throws BrowserException {
                    return backend.call(backendId, tool, arguments);
                }
            }, timeout);
            if (reportsClosedBrowser(result)) {
                throw lost();
            }
            if (reportsChallenge(result)) {
                gate.coolDown(site);
            } else {
                gate.healthy(site);
            }
            rememberSite(result);
            return result;
        } catch (BrowserException failed) {
            if (String.valueOf(failed.getMessage()).contains("BROWSER_CLOSED")) {
                throw lost();
            }
            throw failed;
        } catch (RuntimeException failed) {
            // e.g. web_search_prepare surfaces the closed window as an IllegalStateException
            if (String.valueOf(failed.getMessage()).contains("BROWSER_CLOSED")) {
                throw lost();
            }
            throw failed;
        } finally {
            if (gate.leave(lease)) {
                expiredSite = site;
            }
        }
    }

    /** Close this tab only. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!userContext.isClosed()) {
            userContext.closeTab(backendId);
        }
    }

    void markClosed() {
        closed = true;
    }

    private String siteKey(String tool, Map<String, Object> arguments) {
        if (tool == null) {
            return null;
        }
        switch (tool) {
            case "web_search":
            case "web_search_prepare":
                return SiteConcurrencyGate.SEARCH_KEY;
            case "web_open":
            case "web_probe":
                Object url = arguments == null ? null : arguments.get("url");
                return url == null ? null : SiteConcurrencyGate.siteOf(String.valueOf(url));
            case "web_follow":
            case "web_back":
                return currentSite;
            default:
                return null; // reads of the current page, HUD, challenge poll, cached-snapshot repair
        }
    }

    /**
     * In a worker the closed window is not the user's stop of the research: only this worker lost its user
     * context. The chat-level {@code BROWSER_CLOSED} marker must never reach the run's fate — not as an
     * exception and not as a typed result line (the challenge poll reports it as one).
     */
    private BrowserException lost() {
        return new BrowserException(WorkerBrowserLane.LOST_MARKER + " — the worker tab or its browser "
                + "window was closed (" + id + ").");
    }

    private static boolean reportsClosedBrowser(String result) {
        if (result == null) {
            return false;
        }
        for (String line : result.split("\n")) {
            if (line.startsWith("BROWSER_CLOSED")) {
                return true;
            }
            if (line.startsWith("URL: ") || line.startsWith("url: ")) {
                return false; // a page text follows; its content is not a typed status line
            }
        }
        return false;
    }

    private static boolean reportsChallenge(String result) {
        if (result == null) {
            return false;
        }
        for (String line : result.split("\n")) {
            if (line.startsWith("CHALLENGE: ") || line.trim().equals("challenge_visible: true")) {
                return true; // search/poll contract, or the probe render's structured header line
            }
            if (line.startsWith("URL: ") || line.trim().isEmpty()) {
                return false; // a page text follows; its content is not a typed status line
            }
        }
        return false;
    }

    /** The full-page render ({@code URL: …}) and the probe render ({@code url: …}) both name the final URL. */
    private void rememberSite(String result) {
        if (result != null && (result.startsWith("URL: ") || result.startsWith("url: "))) {
            int end = result.indexOf('\n');
            String site = SiteConcurrencyGate.siteOf(end > 0 ? result.substring(5, end) : result.substring(5));
            if (site != null) {
                currentSite = site;
            }
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
