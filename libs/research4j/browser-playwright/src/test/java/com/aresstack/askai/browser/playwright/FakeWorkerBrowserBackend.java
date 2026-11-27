package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A deterministic worker browser for the pool tests: per user context a cookie jar (as BiDi keeps it per
 * {@code userContext} partition), tabs with a current URL, navigation that takes {@code navigationMillis}
 * of real time, and a strict owner-thread check like Playwright's. Counters observe how many navigations
 * really overlap — per process and over all processes of one {@link Shared} world.
 */
final class FakeWorkerBrowserBackend implements WorkerBrowserBackend {

    /** The world all fake processes of one test share: counters and knobs. */
    static final class Shared {
        final AtomicInteger launched = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final Map<String, AtomicInteger> inFlightPerHost = Collections.synchronizedMap(
                new LinkedHashMap<String, AtomicInteger>());
        final Map<String, Integer> maxPerHost = Collections.synchronizedMap(new LinkedHashMap<String, Integer>());
        final List<String> ownerViolations = Collections.synchronizedList(new ArrayList<String>());
        volatile long navigationMillis = 300L;
        /** A navigation to this URL never returns (until the process is terminated). */
        volatile String hangOnUrl;
        volatile boolean failLaunch;
        /** What web_challenge_status answers (the chat browser's typed status lines). */
        volatile String challengeStatus = "NONE";
        /** web_search_prepare fails like the real capture does when the window is gone. */
        volatile boolean prepareSeesClosedWindow;
        /** Browser processes alive right now, and the most at any moment. */
        final AtomicInteger live = new AtomicInteger();
        final AtomicInteger maxLive = new AtomicInteger();
        /** User contexts alive over all backends. */
        final AtomicInteger liveUserContexts = new AtomicInteger();
        /** A slow browser exit (orderly close). */
        volatile long closeMillis;
        /** A slow user context creation. */
        volatile long newUserContextMillis;

        WorkerBrowserBackend.Factory factory() {
            return new WorkerBrowserBackend.Factory() {
                public WorkerBrowserBackend launch() throws BrowserException {
                    if (failLaunch) {
                        throw new BrowserException("Worker browser start failed (fake)");
                    }
                    launched.incrementAndGet();
                    maxLive.accumulateAndGet(live.incrementAndGet(), Math::max);
                    return new FakeWorkerBrowserBackend(Shared.this);
                }
            };
        }
    }

    private final Shared shared;
    private final Thread owner = Thread.currentThread();
    /** user context id -> cookie jar (host -> name -> value). */
    private final Map<String, Map<String, Map<String, String>>> jars =
            new LinkedHashMap<String, Map<String, Map<String, String>>>();
    private final Map<String, String> tabUserContext = new LinkedHashMap<String, String>();
    private final Map<String, String> tabUrl = new LinkedHashMap<String, String>();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private long ids;
    private volatile boolean open = true;

    private FakeWorkerBrowserBackend(Shared shared) {
        this.shared = shared;
    }

    private void checkOwner(String what) {
        if (Thread.currentThread() != owner) {
            shared.ownerViolations.add(what + " on " + Thread.currentThread().getName());
        }
    }

    public String newUserContext(String storageState) {
        checkOwner("newUserContext");
        sleepQuietly(shared.newUserContextMillis);
        shared.liveUserContexts.incrementAndGet();
        String id = "uc" + (++ids);
        Map<String, Map<String, String>> jar = new TreeMap<String, Map<String, String>>();
        if (storageState != null) {
            // format: host|name=value;host|name=value
            for (String entry : storageState.split(";")) {
                int bar = entry.indexOf('|');
                int eq = entry.indexOf('=');
                if (bar > 0 && eq > bar) {
                    cookies(jar, entry.substring(0, bar)).put(entry.substring(bar + 1, eq), entry.substring(eq + 1));
                }
            }
        }
        jars.put(id, jar);
        return id;
    }

    public String newBrowsingContext(String userContextId) throws BrowserException {
        checkOwner("newBrowsingContext");
        if (!jars.containsKey(userContextId)) {
            throw new BrowserException("Unknown user context " + userContextId);
        }
        String id = "tab" + (++ids);
        tabUserContext.put(id, userContextId);
        return id;
    }

    public String call(String tabId, String tool, Map<String, Object> arguments) throws BrowserException {
        checkOwner("call " + tool);
        String userContext = tabUserContext.get(tabId);
        if (userContext == null) {
            throw new BrowserException("Unknown or closed browsing context: " + tabId);
        }
        Map<String, Map<String, String>> jar = jars.get(userContext);
        if ("web_open".equals(tool)) {
            String url = String.valueOf(arguments.get("url"));
            String host = SiteConcurrencyGate.siteOf(url);
            navigate(url, host);
            int query = url.indexOf("?login=");
            if (query > 0) {
                cookies(jar, host).put("session", url.substring(query + 7)); // the site's Set-Cookie
            }
            tabUrl.put(tabId, url);
            return render(url, jar, host);
        }
        if ("web_probe".equals(tool)) {
            String url = String.valueOf(arguments.get("url"));
            navigate(url, SiteConcurrencyGate.siteOf(url));
            tabUrl.put(tabId, url);
            return "url: " + url + "\ntitle: probe\ntext_length: 1";
        }
        if ("web_follow".equals(tool)) {
            String url = tabUrl.get(tabId) + "/next";
            navigate(url, SiteConcurrencyGate.siteOf(url));
            tabUrl.put(tabId, url);
            return render(url, jar, SiteConcurrencyGate.siteOf(url));
        }
        if ("web_challenge_status".equals(tool)) {
            return shared.challengeStatus;
        }
        if ("web_search_prepare".equals(tool) && shared.prepareSeesClosedWindow) {
            throw new IllegalStateException(Playwright4jDriver.BROWSER_CLOSED_MESSAGE);
        }
        if ("web_read".equals(tool)) {
            String url = tabUrl.get(tabId);
            if (url == null) {
                throw new BrowserException("No page open.");
            }
            return render(url, jar, SiteConcurrencyGate.siteOf(url));
        }
        throw new BrowserException("Unknown tool: " + tool);
    }

    private void navigate(String url, String host) throws BrowserException {
        int now = shared.inFlight.incrementAndGet();
        shared.maxInFlight.accumulateAndGet(now, Math::max);
        AtomicInteger perHost;
        synchronized (shared.inFlightPerHost) {
            perHost = shared.inFlightPerHost.computeIfAbsent(host, h -> new AtomicInteger());
        }
        int hostNow = perHost.incrementAndGet();
        shared.maxPerHost.merge(host, hostNow, Math::max);
        try {
            if (url.equals(shared.hangOnUrl)) {
                terminated.await(); // the "stuck Playwright call": only the cut connection ends it
                throw new BrowserException("connection closed");
            }
            Thread.sleep(shared.navigationMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrowserException("interrupted");
        } finally {
            perHost.decrementAndGet();
            shared.inFlight.decrementAndGet();
        }
    }

    private static String render(String url, Map<String, Map<String, String>> jar, String host) {
        Map<String, String> visible = jar.get(host);
        return "URL: " + url + "\nTITLE: " + host + "\n\ncookies=" + (visible == null ? "{}" : visible);
    }

    private static Map<String, String> cookies(Map<String, Map<String, String>> jar, String host) {
        Map<String, String> c = jar.get(host);
        if (c == null) {
            c = new TreeMap<String, String>();
            jar.put(host, c);
        }
        return c;
    }

    public String storageState(String userContextId) throws BrowserException {
        checkOwner("storageState");
        Map<String, Map<String, String>> jar = jars.get(userContextId);
        if (jar == null) {
            throw new BrowserException("Unknown user context");
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> host : jar.entrySet()) {
            for (Map.Entry<String, String> cookie : host.getValue().entrySet()) {
                sb.append(host.getKey()).append('|').append(cookie.getKey()).append('=')
                        .append(cookie.getValue()).append(';');
            }
        }
        return sb.toString();
    }

    public void closeBrowsingContext(String tabId) {
        checkOwner("closeBrowsingContext");
        tabUserContext.remove(tabId);
        tabUrl.remove(tabId);
    }

    public void closeUserContext(String userContextId) {
        checkOwner("closeUserContext");
        if (jars.remove(userContextId) != null) {
            shared.liveUserContexts.decrementAndGet();
        }
        tabUserContext.values().removeIf(userContextId::equals);
    }

    int userContexts() {
        return jars.size();
    }

    public boolean pumpEvents(BooleanSupplier wake, long timeoutMillis) {
        return false;
    }

    public boolean isConnected() {
        return open;
    }

    public void close() {
        checkOwner("close");
        if (open) {
            sleepQuietly(shared.closeMillis);
            open = false;
            shared.liveUserContexts.addAndGet(-jars.size());
            jars.clear();
            shared.closed.incrementAndGet();
            shared.live.decrementAndGet();
        }
    }

    public void terminate() {
        boolean wasOpen = open;
        open = false;
        terminated.countDown();
        shared.closed.incrementAndGet();
        if (wasOpen) {
            shared.live.decrementAndGet();
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
