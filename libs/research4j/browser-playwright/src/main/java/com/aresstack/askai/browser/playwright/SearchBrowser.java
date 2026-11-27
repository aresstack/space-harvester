package com.aresstack.askai.browser.playwright;

import com.aresstack.serp4j.core.error.SearchErrorCategory;
import com.aresstack.serp4j.core.error.SearchProviderException;
import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.model.id.ProviderId;
import com.aresstack.serp4j.core.port.ProviderDescriptor;
import com.aresstack.serp4j.core.port.SearchContext;
import com.aresstack.serp4j.core.port.SearchProvider;
import com.aresstack.serp4j.playwright.PlaywrightSearchProvider;
import com.aresstack.askai.browser.search.engine.SearchEngineHosts;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;

/**
 * AskAI's search browser (askai #31, Serp4J ADR-020): a headless browser, separate from the research browser,
 * that AskAI starts once, keeps running, starts again after a crash and passes to a Serp4J
 * {@link PlaywrightSearchProvider} with every search. Serp4J never starts or stops it.
 *
 * <ul>
 *   <li>The host's {@link BrowserLauncher} starts it with a throw-away profile ({@link BrowserLauncher#ephemeral}):
 *       Playwright only returns a {@link Browser} object for a launched browser. One browser context is created
 *       after the start and lives as long as the browser, so cookies and consent answers survive between
 *       searches.</li>
 *   <li>All Playwright calls run on one owner thread (Playwright objects are not thread-safe); searches are
 *       serialised. The browser starts on the first search.</li>
 *   <li>A disconnected browser is started again before the next search; a browser that dies during a search
 *       is started again and the search is repeated once.</li>
 *   <li>It is the ONE client that talks to the search engines, for every chat: searches and redirect hops keep a
 *       minimum distance to each other ({@link #setEngineSpacingMillis}); a challenge pauses the engines with an
 *       exponentially growing pause ({@link #setEnginePause}) and every request during the pause fails at once as
 *       {@code BLOCKED} with the reason, without contacting the engine.</li>
 * </ul>
 */
public final class SearchBrowser implements AutoCloseable {

    /** Completes the futures of {@link #asSearchProvider}; the browser work itself runs on the owner thread. */
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "search-browser-caller");
        t.setDaemon(true);
        return t;
    });

    /** The engine pace; spacing and pause come from the settings ({@link #setEngineSpacingMillis}, {@link #setEnginePause}). */
    private final SearchEngineThrottle throttle = new SearchEngineThrottle(0L, 0L, 0L, SearchEngineThrottle.SYSTEM);

    private final BrowserLauncher launcher;
    /** Read before every (re)start: a changed setting takes effect with the next search. */
    private final java.util.function.BooleanSupplier headless;
    private final ExecutorService owner;
    private volatile boolean closed;
    // Touched only on the owner thread.
    private LaunchedBrowser launched;
    private Browser browser;
    private boolean browserHeadless;
    private int launches;

    /** @param launcher the host's browser runtime, which decides the browser */
    public SearchBrowser(BrowserLauncher launcher) {
        this(launcher, () -> true);
    }

    /**
     * @param launcher the host's browser runtime, which decides the browser
     * @param headless whether the browser runs without a window; asked before every search, and a browser that
     *                 runs in the other mode is started again
     */
    public SearchBrowser(BrowserLauncher launcher, java.util.function.BooleanSupplier headless) {
        this.headless = headless == null ? () -> true : headless;
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.owner = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "search-browser-owner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Runs one search with the current search browser and waits for it.
     *
     * @throws SearchProviderException the provider's failure, or {@code UNAVAILABLE} when the browser does not
     *                                 start
     */
    public SearchResponse search(PlaywrightSearchProvider provider, SearchRequest request, SearchContext context) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        final ProviderId id = provider.getDescriptor().getId();
        return onOwner(provider, () -> {
            refuseWhilePaused(id);
            awaitEngineSlot(id);
            Browser current = ensureBrowser(provider);
            SearchResponse response;
            try {
                response = provider.search(request, context, current);
            } catch (SearchProviderException e) {
                if (e.getCategory() == SearchErrorCategory.UNAVAILABLE && !current.isConnected()
                        && !context.getCancellation().isCancelled()) {
                    System.err.println("[search-browser] browser died during the search; starting it again");
                    awaitEngineSlot(id);
                    try {
                        response = provider.search(request, context, ensureBrowser(provider));
                    } catch (SearchProviderException again) {
                        throw noteBlock(again);
                    }
                } else {
                    throw noteBlock(e);
                }
            }
            throttle.recordSuccess();
            return response;
        });
    }

    /** The minimum distance between two engine requests (searches, result pages and redirect hops), from now on. */
    public void setEngineSpacingMillis(long millis) {
        throttle.setMinSpacingMillis(millis);
    }

    /**
     * The pause after a challenge or rate limit: {@code baseMillis} the first time (0 = no pause), doubled with every
     * further challenge in a row up to {@code capMillis}; a normal answer starts again at the base.
     */
    public void setEnginePause(long baseMillis, long capMillis) {
        throttle.setPause(baseMillis, capMillis);
    }

    /** Why the engines are paused after a challenge, or empty when they are not. */
    public String enginePauseReason() {
        return throttle.pauseReason();
    }

    /** Until when the engines are paused (epoch millis), 0 or past when they are not. */
    public long enginePausedUntil() {
        return throttle.pausedUntil();
    }

    private void refuseWhilePaused(ProviderId id) {
        if (throttle.isPaused()) {
            throw new SearchProviderException(id, SearchErrorCategory.BLOCKED, "search engines paused until "
                    + new java.util.Date(throttle.pausedUntil()) + " after: " + throttle.pauseReason());
        }
    }

    private void awaitEngineSlot(ProviderId id) {
        try {
            throttle.awaitSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchProviderException(id, SearchErrorCategory.CANCELLED,
                    "interrupted while keeping the engine spacing");
        }
    }

    /** A challenge or rate limit pauses every engine request; the failure itself is passed on unchanged. */
    private SearchProviderException noteBlock(SearchProviderException e) {
        if (e.getCategory() == SearchErrorCategory.BLOCKED || e.getCategory() == SearchErrorCategory.RATE_LIMITED) {
            long pause = throttle.recordBlock(e.getCategory() + ": " + firstLine(String.valueOf(e.getMessage())));
            System.err.println("[search-browser] engine challenge; engines paused for " + (pause / 1000L) + " s");
        }
        return e;
    }

    /**
     * The given provider as an ordinary Serp4J {@link SearchProvider} bound to this search browser, so AskAI's
     * Serp4J search path (the same one Brave, DataForSEO and SerpApi use) can run it. The search itself runs on
     * the owner thread; the future completes on a worker thread. Closing the returned provider does not close
     * the search browser, which belongs to AskAI.
     */
    public SearchProvider asSearchProvider(final PlaywrightSearchProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return new SearchProvider() {
            public ProviderDescriptor getDescriptor() {
                return provider.getDescriptor();
            }

            public CompletableFuture<SearchResponse> search(SearchRequest request, SearchContext context) {
                Objects.requireNonNull(request, "request");
                Objects.requireNonNull(context, "context");
                return CompletableFuture.supplyAsync(() -> SearchBrowser.this.search(provider, request, context),
                        WORKERS);
            }

            public void close() {
                // the search browser belongs to AskAI and outlives this view
            }
        };
    }

    /** The outcome of resolving an engine's redirect wrapper in the search browser. */
    public static final class RedirectResolution {
        public enum Status { RESOLVED, BLOCKED, UNRESOLVED }

        public final Status status;
        /** The page the wrapper points at (RESOLVED), else "". */
        public final String targetUrl;
        /** Where the hop ended or why nothing was resolved — diagnostics. */
        public final String detail;

        RedirectResolution(Status status, String targetUrl, String detail) {
            this.status = status;
            this.targetUrl = targetUrl == null ? "" : targetUrl;
            this.detail = detail == null ? "" : detail;
        }

        static RedirectResolution resolved(String target) {
            return new RedirectResolution(Status.RESOLVED, target, "");
        }

        static RedirectResolution blocked(String where) {
            return new RedirectResolution(Status.BLOCKED, "", where);
        }

        static RedirectResolution unresolved(String why) {
            return new RedirectResolution(Status.UNRESOLVED, "", why);
        }
    }

    /**
     * Resolve a search engine's redirect wrapper to its target with THIS browser (its cookies, its identity), on
     * the owner thread like a search. Never throws, never waits for a person.
     *
     * @param wrapperUrl    the engine address a hit points at (e.g. {@code https://www.google.com/goto?url=…})
     * @param timeoutMillis the longest the hop may take
     */
    public RedirectResolution resolveRedirect(String wrapperUrl, long timeoutMillis) {
        Objects.requireNonNull(wrapperUrl, "wrapperUrl");
        if (closed) {
            return RedirectResolution.unresolved("search browser is closed");
        }
        final long budget = Math.max(1_000L, timeoutMillis);
        Future<RedirectResolution> future;
        try {
            future = owner.submit(() -> resolveOnOwner(wrapperUrl, budget));
        } catch (RejectedExecutionException e) {
            return RedirectResolution.unresolved("search browser is closed");
        }
        try {
            // the hop waits its turn behind the searches and keeps the engine spacing before it runs
            return future.get(budget * 2 + 5_000L + 3 * throttle.minSpacingMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return RedirectResolution.unresolved("interrupted");
        } catch (TimeoutException e) {
            future.cancel(true);
            return RedirectResolution.unresolved("timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return RedirectResolution.unresolved(cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + firstLine(cause.getMessage())));
        }
    }

    /** One hop, under the same pace as the searches: refused while paused, spaced, a challenge pauses the engines. */
    private RedirectResolution resolveOnOwner(String wrapperUrl, long timeoutMillis) {
        if (throttle.isPaused()) {
            return RedirectResolution.blocked("search engines paused after: " + throttle.pauseReason());
        }
        try {
            throttle.awaitSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RedirectResolution.unresolved("interrupted while keeping the engine spacing");
        }
        RedirectResolution hop = hopOnOwner(wrapperUrl, timeoutMillis);
        if (hop.status == RedirectResolution.Status.BLOCKED) {
            long pause = throttle.recordBlock("challenge on redirect hop: " + hop.detail);
            System.err.println("[search-browser] challenge on a redirect hop; engines paused for "
                    + (pause / 1000L) + " s");
        } else if (hop.status == RedirectResolution.Status.RESOLVED) {
            throttle.recordSuccess();
        }
        return hop;
    }

    private RedirectResolution hopOnOwner(String wrapperUrl, long timeoutMillis) {
        Browser current;
        try {
            current = ensureBrowser(ProviderId.of("redirect-resolver"));
        } catch (SearchProviderException e) {
            return RedirectResolution.unresolved("search browser could not be started");
        }
        BrowserContext context = current.contexts().isEmpty() ? current.newContext() : current.contexts().get(0);
        // Step 1: the hop as ONE request with the context's cookies, redirects not followed — the usual answer
        // is a 3xx whose Location is the target; nothing of the target is fetched.
        String step1 = "";
        try {
            APIResponse response = context.request().get(wrapperUrl,
                    RequestOptions.create().setMaxRedirects(0).setTimeout((double) timeoutMillis));
            int status = response.status();
            String location = response.headers().get("location");
            if (status >= 300 && status < 400 && location != null && !location.trim().isEmpty()) {
                String target = absolute(wrapperUrl, location.trim());
                if (SearchEngineHosts.isGoogleObstacleUrl(target)) {
                    return RedirectResolution.blocked(target);
                }
                if (!SearchEngineHosts.isEngineHost(target)) {
                    return RedirectResolution.resolved(target);
                }
                step1 = "engine-internal hop " + status;
            } else if (status == 429 || status == 403) {
                return RedirectResolution.blocked(wrapperUrl + " -> HTTP " + status);
            } else if (status == 200) {
                String body = response.text();
                String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
                if (lower.contains("/sorry/index") || lower.contains("id=\"captcha-form\"")
                        || lower.contains("g-recaptcha")) {
                    return RedirectResolution.blocked(wrapperUrl + " -> challenge page");
                }
                String meta = metaRefreshTarget(body);
                if (meta != null) {
                    // a relative refresh target (/url?…, /sorry/index?…) is resolved first, then judged
                    // exactly like a Location header
                    String target = absolute(wrapperUrl, meta);
                    if (SearchEngineHosts.isGoogleObstacleUrl(target)) {
                        return RedirectResolution.blocked(target);
                    }
                    if (!SearchEngineHosts.isEngineHost(target)) {
                        return RedirectResolution.resolved(target);
                    }
                    step1 = "engine-internal meta refresh";
                } else {
                    step1 = "HTTP 200 without a usable target";
                }
            } else {
                step1 = "HTTP " + status;
            }
        } catch (PlaywrightException e) {
            step1 = "request failed: " + firstLine(e.getMessage());
        }
        // Step 2: the hop as a navigation; the first document request that leaves the engine names the
        // target and is aborted (a redirect hop is observed, not intercepted — the page is closed at once).
        final AtomicReference<String> target = new AtomicReference<>();
        final AtomicBoolean obstacle = new AtomicBoolean();
        final boolean ownPage = context.pages().isEmpty();
        Page page = ownPage ? context.newPage() : context.pages().get(0);
        String landed = "";
        java.util.function.Consumer<Request> watcher = request -> {
            String url = request.url();
            if (SearchEngineHosts.isGoogleObstacleUrl(url)) {
                obstacle.set(true);
            } else if ("document".equals(request.resourceType()) && !SearchEngineHosts.isEngineHost(url)) {
                target.compareAndSet(null, url);
            }
        };
        try {
            page.setDefaultTimeout(timeoutMillis);
            page.route("**/*", route -> {
                Request request = route.request();
                String url = request.url();
                if ("document".equals(request.resourceType())) {
                    if (SearchEngineHosts.isEngineHost(url)) {
                        if (SearchEngineHosts.isGoogleObstacleUrl(url)) {
                            obstacle.set(true);
                        }
                        route.resume();
                        return;
                    }
                    target.compareAndSet(null, url);
                }
                route.abort();
            });
            page.onRequest(watcher);
            try {
                page.navigate(wrapperUrl, new Page.NavigateOptions().setTimeout((double) timeoutMillis)
                        .setWaitUntil(WaitUntilState.COMMIT));
            } catch (PlaywrightException ignored) {
                // an aborted target request ends the navigation with an error: expected
            }
            long deadline = System.currentTimeMillis() + Math.min(timeoutMillis, 4_000L);
            while (target.get() == null && !obstacle.get() && System.currentTimeMillis() < deadline) {
                page.waitForTimeout(100);
            }
            landed = page.url() == null ? "" : page.url();
            if (SearchEngineHosts.isGoogleObstacleUrl(landed)) {
                obstacle.set(true);
            } else if (target.get() == null && landed.startsWith("http") && !SearchEngineHosts.isEngineHost(landed)) {
                target.set(landed);
            }
        } catch (PlaywrightException e) {
            return RedirectResolution.unresolved(step1 + "; navigation failed: " + firstLine(e.getMessage()));
        } finally {
            releaseHopPage(page, ownPage, watcher);
        }
        if (target.get() != null) {
            return RedirectResolution.resolved(target.get());
        }
        if (obstacle.get()) {
            return RedirectResolution.blocked(landed.isEmpty() ? wrapperUrl : landed);
        }
        return RedirectResolution.unresolved(step1 + "; navigation stayed on " + (landed.isEmpty() ? "?" : landed));
    }

    /** {@code <meta http-equiv="refresh" content="0; url=…">} of a 200 interstitial, or null. */
    static String metaRefreshTarget(String html) {
        if (html == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*?content\\s*=\\s*[\"']\\s*\\d+\\s*;\\s*url\\s*=\\s*([^\"'>\\s]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** A Location that is relative is resolved against the wrapper URL. */
    static String absolute(String base, String location) {
        try {
            return java.net.URI.create(base).resolve(location).toString();
        } catch (RuntimeException e) {
            return location;
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        String line = nl < 0 ? text : text.substring(0, nl);
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    /** How often the browser was started (1 after the first search, +1 per restart). */
    /**
     * Ends a redirect hop: a page opened for the hop is closed; the search tab stays open and only loses the hop's
     * route and request watcher, so the next search finds it unchanged.
     */
    private static void releaseHopPage(Page page, boolean ownPage, java.util.function.Consumer<Request> watcher) {
        try {
            if (ownPage) {
                page.close();
            } else {
                page.offRequest(watcher);
                page.unroute("**/*");
            }
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    public int getLaunchCount() {
        try {
            return owner.submit(() -> launches).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return launches;
        } catch (ExecutionException | RejectedExecutionException e) {
            return launches;
        }
    }

    private Browser ensureBrowser(PlaywrightSearchProvider provider) {
        return ensureBrowser(provider.getDescriptor().getId());
    }

    private Browser ensureBrowser(ProviderId providerId) {
        boolean wantHeadless = headless.getAsBoolean();
        if (browser != null && browser.isConnected() && browserHeadless == wantHeadless) {
            return browser;
        }
        closeBrowserQuietly();
        try {
            launched = launcher.ephemeral(wantHeadless, null);
            browser = launched.browser();
            boolean camoufox = launched.camoufox();
            browserHeadless = wantHeadless;
            // The one context of this browser with its one search tab: every search and every redirect hop runs
            // in this tab (one window, also when visible), cookies stay between searches.
            BrowserContext searchContext = browser.newContext(camoufox
                    ? new Browser.NewContextOptions().setAcceptDownloads(false).setViewportSize(null)
                            .setColorScheme(null).setReducedMotion(null).setForcedColors(null).setContrast(null)
                    : new Browser.NewContextOptions().setAcceptDownloads(false));
            searchContext.newPage();
            launches++;
            System.err.println("[search-browser] started" + (wantHeadless ? " headless" : " visible")
                    + " (start " + launches + ")");
            return browser;
        } catch (com.aresstack.askai.browser.BrowserException | RuntimeException e) {
            closeBrowserQuietly();
            throw SearchProviderException.builder(providerId, SearchErrorCategory.UNAVAILABLE,
                            "search browser could not be started: " + e.getClass().getSimpleName())
                    .retryable(true).cause(e).build();
        }
    }

    private SearchResponse onOwner(PlaywrightSearchProvider provider, Callable<SearchResponse> work) {
        if (closed) {
            throw new SearchProviderException(provider.getDescriptor().getId(), SearchErrorCategory.CANCELLED,
                    "search browser is closed");
        }
        Future<SearchResponse> future;
        try {
            future = owner.submit(work);
        } catch (RejectedExecutionException e) {
            throw new SearchProviderException(provider.getDescriptor().getId(), SearchErrorCategory.CANCELLED,
                    "search browser is closed");
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new SearchProviderException(provider.getDescriptor().getId(), SearchErrorCategory.CANCELLED,
                    "interrupted while waiting for the search browser");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SearchProviderException) {
                throw (SearchProviderException) cause;
            }
            throw SearchProviderException.builder(provider.getDescriptor().getId(), SearchErrorCategory.INTERNAL,
                    "search failed: " + cause.getClass().getSimpleName()).cause(cause).build();
        }
    }

    private void closeBrowserQuietly() {
        if (launched != null) {
            launched.close();
            launched = null;
        }
        browser = null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            owner.submit(this::closeBrowserQuietly).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RejectedExecutionException ignored) {
            // best effort
        } finally {
            owner.shutdown();
        }
    }
}
