package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;


import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link PlaywrightDriver} over the OFFICIAL Playwright Java API, executed by playwright4j: the Driver
 * replacement spawns a second Java process ({@code GraalDriverMain}) hosting the Playwright Core JS driver on
 * GraalJS. Which browser runs and how it starts is the host's browser runtime ({@link BrowserLauncher}); this
 * driver only operates it.
 *
 * <p>One research session per runtime instance: one BrowserContext → one Page + history. Popups are closed
 * immediately, and when a request filter is given, browser-side requests to disallowed targets are aborted via
 * route interception. {@link #close()} closes the page and hands the launched browser back to the launcher's
 * {@link LaunchedBrowser#close()}; it is idempotent.</p>
 *
 * <p>THREADING CONTRACT: every instance is created and used on exactly ONE thread (the
 * {@link BrowserSessionActor} owner). Playwright Java is not thread-safe, and its events (route
 * interception, exposeBinding, popups) are only dispatched while that thread runs a Playwright call —
 * which is why the actor keeps {@link #pumpEvents} running whenever no command is queued. Every
 * Playwright-touching method asserts the owner via {@link ThreadOwnershipGuard}.</p>
 */
final class Playwright4jDriver implements PlaywrightDriver {

    /** Created in the constructor — the launching thread (the actor owner) is the only legal caller. */
    private final ThreadOwnershipGuard owner = new ThreadOwnershipGuard();
    /** Null for a persistent context whose browser Playwright does not expose (Camoufox). */
    private final Browser browser;
    private final BrowserContext context;
    /** The chat browser this driver owns (closed with it), else null (worker and chat tabs own nothing). */
    private LaunchedBrowser launched;
    /** Set by BrowserContext.onClose: a persistent context ends with its browser window. */
    private volatile boolean contextClosed;
    /** SERP guard behaviour comes exclusively from the settings — no local constants. */
    private final com.aresstack.askai.browser.search.ConsentHandlingSettings consent;
    private final com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha;
    /** The structured-page capture is a separate collaborator; this driver only delegates to it. */
    private RenderedPageDocumentCapture renderedCapture;
    /**
     * The PDF document the tab shows in the browser's viewer, or null. The viewer runs no page script (and no
     * add-on read), so {@link #current()} reports it as a document for the session to read instead of waiting for
     * a page read that never answers.
     */
    /** The PDF a tab shows in the browser's viewer, per tab: a tab switch keeps each tab's document. */
    private final java.util.Map<Page, PlaywrightPageState> viewedDocuments =
            new java.util.IdentityHashMap<Page, PlaywrightPageState>();
    /** See {@link #pageGeneration()}; owner thread only. */
    private long pageGeneration;
    private Page page;
    /** The parked manual-challenge page (kept open for the user), or null. At most one at a time. */
    private Page challengePage;
    /** Chrome/Edge HUD overlay: the command binding is registered ONCE on the context (survives navigation). */
    private boolean hudBindingRegistered;
    /**
     * Control plane: filled by the research add-on's bridge thread (Camoufox, Chrome/Edge with the extension) or the
     * overlay binding (Chrome/Edge without it),
     * drained on any thread (never via the actor).
     */
    private final HudCommandInbox hudCommands = new HudCommandInbox();
    /**
     * The bridge to the AskAI Research add-on (Camoufox) or extension (Chrome/Edge, stage 4), which shows the HUD in
     * the browser UI instead of the page (docs/research-browser-addon.md); null when it is off or could not be
     * prepared or loaded.
     */
    private AddonBridge addonBridge;
    /**
     * With the add-on (Camoufox) or extension (Chrome/Edge chat browser): where page reads run (the chat browser's
     * {@link #addonBridge}, or the Camoufox worker
     * browser's bridge for a worker tab). Null = no add-on, reads fall back to {@code page.evaluate}.
     */
    private AddonBridge readBridge;
    /**
     * The add-on's stable key per page (main page, parked challenge page): it binds each key to exactly one tab, so
     * a read never lands in another tab that shows the same URL. Unique per JVM, because worker tabs of several
     * drivers share one worker bridge.
     */
    private final Map<Page, String> pageKeys = new java.util.IdentityHashMap<Page, String>();
    private static final java.util.concurrent.atomic.AtomicLong PAGE_KEYS = new java.util.concurrent.atomic.AtomicLong();
    /** When the browser was started: the add-on gets {@link #ADDON_GRACE_MILLIS} before its absence is logged. */
    private long launchedMillis;
    private boolean addonConnectedLogged;
    private boolean addonMissingLogged;

    /** System property: {@code false} starts Camoufox without the AskAI Research add-on. */
    static final String ADDON_PROPERTY = "askai.browser.researchAddon";
    /**
     * System property: {@code false} keeps the HUD overlay out of Chrome/Edge pages too (no HUD in the browser there).
     * Camoufox never gets the overlay, whatever this says.
     */
    static final String IN_PAGE_FALLBACK_PROPERTY = "askai.browser.hud.inPageFallback";
    /** How long a fresh browser's add-on may take to connect before its absence is logged. */
    static final long ADDON_GRACE_MILLIS = 15_000L;
    /**
     * How long one page read may take through the add-on. Covers a fresh browser whose add-on is still connecting
     * (it fetches the queued read once it polls) and a full structured capture of a large page.
     */
    static final long PAGE_READ_TIMEOUT_MILLIS = 30_000L;
    /** How many popups the close-immediately policy handled — observable proof the event dispatched. */
    private final java.util.concurrent.atomic.AtomicInteger popupsClosed =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean closed;
    /** Set by Browser.onDisconnected (dispatched during the idle pump): the USER closed the window. */
    private volatile boolean browserDisconnected;

    /**
     * The typed marker for "the user closed the browser window". It leads every resulting tool error, so
     * the research runtime can recognize the USER's stop and end the run as their decision — instead of
     * the historic behaviour, where 'Target closed' matched no classifier and the run kept polling a dead
     * browser until some budget gave out.
     */
    static final String BROWSER_CLOSED_MESSAGE =
            "BROWSER_CLOSED — the browser window was closed by the user.";

    /** The per-navigation deadline ({@code navigationCommitTimeoutMillis}) — explicit on every navigate. */
    private final int navigationTimeoutMillis;
    /**
     * False for a worker browsing context ({@link #inBrowsingContext}): the driver then owns only its page;
     * the user context, the browser and Playwright belong to the worker browser that created it.
     */
    private final boolean ownsGeneration;
    /**
     * Camoufox: nothing of AskAI is put into the page — no HUD overlay, no binding; the HUD lives only in the research
     * add-on. Chrome/Edge keep the overlay until their extension exists (issue #23, stage 4).
     */
    private final boolean camoufox;
    /** Every main-world script passes this gate; in script-free mode it refuses them all (see PageScriptGate). */
    private final PageScriptGate pageScripts;
    private Playwright4jDriver(Browser browser, BrowserContext context, Page page,
                               com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                               com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha,
                               int navigationTimeoutMillis, boolean camoufox) {
        this(browser, context, page, consent, captcha, navigationTimeoutMillis, true, camoufox);
    }

    private Playwright4jDriver(Browser browser, BrowserContext context, Page page,
                               com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                               com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha,
                               int navigationTimeoutMillis, boolean ownsGeneration, boolean camoufox) {
        this.ownsGeneration = ownsGeneration;
        this.camoufox = camoufox;
        this.pageScripts = PageScriptGate.forBrowser(camoufox);
        this.browser = browser;
        this.context = context;
        this.page = page;
        this.consent = consent;
        this.captcha = captcha;
        this.navigationTimeoutMillis = navigationTimeoutMillis;
    }

    /**
     * Get the chat browser from the host's launcher and wire it. @param requestAllowed null = no interception;
     * otherwise every browser-side request URL is checked and aborted when not allowed (cheap best-effort SSRF net
     * below the authoritative post-navigation URL-policy check in the session). @param profileKey the chat whose
     * lasting profile this browser uses (null = a temporary profile).
     */
    static Playwright4jDriver launch(BrowserLauncher launcher, boolean headless, int timeoutMillis, String profileKey,
                                     final Predicate<String> requestAllowed,
                                     com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                                     com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha)
            throws BrowserException {
        return launch(launcher, headless, timeoutMillis, profileKey, requestAllowed, consent, captcha, false);
    }

    /**
     * Get the chat browser of the chat tools ({@link PlaywrightChatEngine}) from the host's launcher: like
     * {@link #launch}, but new tabs of a page stay open (they are the chat's tabs, {@link ChatTabs}), the research
     * add-on is not loaded (the chat reads with its own page scripts) and closing the first tab moves the driver to
     * another open tab instead of ending the session.
     */
    static Playwright4jDriver launchForChat(BrowserLauncher launcher, boolean headless, int timeoutMillis,
                                            String profileKey,
                                            com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                                            com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha)
            throws BrowserException {
        return launch(launcher, headless, timeoutMillis, profileKey, null, consent, captcha, true);
    }

    private static Playwright4jDriver launch(BrowserLauncher launcher, boolean headless, int timeoutMillis,
                                             String profileKey, final Predicate<String> requestAllowed,
                                             com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                                             com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha,
                                             boolean chat)
            throws BrowserException {
        AddonBridge bridge = null;
        LaunchedBrowser launched = null;
        try {
            final AddonBridge addonBridge = chat ? null : startAddonBridge("research add-on");
            bridge = addonBridge;
            AddonPackager addon = addonBridge == null ? null : AddonPackager.research(addonBridge, true);
            launched = launcher.persistent(profileKey, headless, addon);
            if (bridge != null && !launched.addonLoaded()) {
                // The browser runs on without the add-on: HUD overlay and page reads stay in the page.
                System.err.println("[browser-mcp] research add-on not loaded: " + launched.addonFailure());
                bridge.close();
                bridge = null;
            }
            BrowserContext context = launched.context();
            Browser browser = launched.browser();
            context.setDefaultNavigationTimeout(timeoutMillis);
            context.setDefaultTimeout(timeoutMillis);
            if (requestAllowed != null) {
                // Chrome/Edge: Playwright's interception also sees the extension service worker's requests (Firefox
                // does not), so the bridge of THIS start stays reachable for requests with its token; everything
                // else to loopback is still refused, and the bridge itself checks token and origin again.
                final String extensionBridge = launched.chromiumExtension() && bridge != null
                        ? "http://127.0.0.1:" + bridge.port() + "/v1/" : null;
                final String extensionBearer = bridge != null ? "Bearer " + bridge.token() : null;
                context.route("**/*", route -> {
                    if (requestAllowed.test(route.request().url())
                            || extensionBridge != null && fromExtensionWorker(route.request(), extensionBridge,
                            extensionBearer)) {
                        route.resume();
                    } else {
                        route.abort();
                    }
                });
            }
            // A persistent context opens with its first tab already; use it instead of a second one.
            Page page = !context.pages().isEmpty() ? context.pages().get(0) : context.newPage();
            final Playwright4jDriver driver = new Playwright4jDriver(browser, context, page,
                    consent, captcha, timeoutMillis, launched.camoufox());
            driver.launched = launched;
            if (bridge != null) {
                bridge.deliverTo(driver.hudCommands);
                driver.addonBridge = bridge;
                driver.readBridge = bridge;
            }
            driver.launchedMillis = System.currentTimeMillis();
            if (chat) {
                driver.watchChatTab(page);
            } else {
                page.onPopup(driver.popupCloser());
            }
            // Dispatched during the idle pump: the user closing the window becomes a TYPED condition
            // instead of an anonymous 'Target closed' that no classifier recognized.
            if (browser != null) {
                browser.onDisconnected(new Consumer<Browser>() {
                    public void accept(Browser gone) {
                        driver.browserDisconnected = true;
                    }
                });
            }
            context.onClose(new Consumer<BrowserContext>() {
                public void accept(BrowserContext gone) {
                    driver.contextClosed = true;
                    driver.browserDisconnected = true;
                }
            });
            return driver;
        } catch (RuntimeException ex) {
            if (launched != null) {
                launched.close();
            }
            if (bridge != null) {
                bridge.close();
            }
            throw new BrowserException("Browser start failed: " + firstLine(ex));
        } catch (BrowserException ex) {
            if (bridge != null) {
                bridge.close();
            }
            throw ex;
        }
    }

    /**
     * The bridge to the research add-on, unless it is switched off ({@value #ADDON_PROPERTY}{@code =false});
     * null when it could not start (the browser then starts without the add-on, which is logged).
     */
    static AddonBridge startAddonBridge(String what) {
        if ("false".equalsIgnoreCase(System.getProperty(ADDON_PROPERTY))) {
            return null;
        }
        try {
            return AddonBridge.start();
        } catch (java.io.IOException | RuntimeException failed) {
            System.err.println("[browser-mcp] " + what + " not prepared: " + firstLine(failed));
            return null;
        }
    }

    /**
     * A driver for ONE browsing context (tab) of a worker user context, created by the worker browser on its
     * owner thread (which becomes this driver's owner). It owns only {@code page}: {@link #close()} closes the
     * page (and a parked challenge tab), never the user context, the browser or Playwright. The Research HUD
     * belongs to the chat's own browser and is not rendered in worker tabs. @param camoufox whether the worker
     * browser is Camoufox (script-free mode applies to it).
     */
    static Playwright4jDriver inBrowsingContext(Browser browser, BrowserContext userContext, Page page,
                                                int timeoutMillis,
                                                com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                                                com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha,
                                                boolean camoufox) {
        final Playwright4jDriver driver = new Playwright4jDriver(browser, userContext, page, consent,
                captcha, timeoutMillis, false, camoufox);
        page.onPopup(driver.popupCloser());
        if (browser != null) {
            browser.onDisconnected(new Consumer<Browser>() {
                public void accept(Browser gone) {
                    driver.browserDisconnected = true;
                }
            });
        }
        page.onClose(driver.activeTabCloseWatcher());
        return driver;
    }

    @Override
    public PlaywrightPageState open(String url) throws BrowserException {
        owner.check();
        requireOpen();
        try {
            // DOMCONTENTLOADED, not the default 'load': the navigation's job is to deliver the
            // document — whether the page is actually usable is decided by the session's OWN readiness
            // machinery (probe/consent/challenge/read) afterwards. Waiting for 'load' let one hanging
            // ad/tracking subresource stall the whole visit for the full navigation timeout. Never
            // NETWORKIDLE. With this, navigationCommitTimeoutMillis finally bounds roughly what its
            // name promises.
            return pageOrDocument(page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(navigationTimeoutMillis)));
        } catch (PlaywrightException ex) {
            if (startedDownload(ex)) {
                // The tab keeps its page; the session reads the document (a PDF, or it says what it is instead).
                return PlaywrightPageState.downloadedDocument(url, page.url());
            }
            throw asBrowserException("Navigation failed", ex);
        }
    }

    @Override
    public PlaywrightPageState current() throws BrowserException {
        owner.check();
        requireOpen();
        try {
            PlaywrightPageState viewedDocument = viewedDocuments.get(page);
            if (viewedDocument != null && viewedDocument.url.equals(page.url())) {
                return viewedDocument;
            }
            return state();
        } catch (PlaywrightException ex) {
            throw asBrowserException("Reading the current page failed", ex);
        }
    }

    @Override
    public String currentUrl() throws BrowserException {
        owner.check();
        requireOpen();
        try {
            return page.url();
        } catch (PlaywrightException ex) {
            throw asBrowserException("Reading the current URL failed", ex);
        }
    }

    @Override
    public PlaywrightPageState back() throws BrowserException {
        owner.check();
        requireOpen();
        try {
            // Same navigation semantics as open(): the document suffices, readiness judges the rest.
            com.microsoft.playwright.Response response = page.goBack(new Page.GoBackOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(navigationTimeoutMillis));
            if (response == null) {
                throw new BrowserException("No previous page in history.");
            }
            return pageOrDocument(response);
        } catch (PlaywrightException ex) {
            throw asBrowserException("Going back failed", ex);
        }
    }

    /**
     * Playwright's navigation error when the URL answers with a download instead of a page (Firefox and so
     * Camoufox, WebKit). Chromium reports {@code net::ERR_ABORTED}, which other aborted navigations share, so it
     * is not taken as a download.
     */
    static final String DOWNLOAD_STARTED_MESSAGE = "Download is starting";
    /** The Content-Types of a PDF response; the second is the legacy one some servers still send. */
    private static final java.util.Set<String> PDF_CONTENT_TYPES = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<String>(java.util.Arrays.asList("application/pdf", "application/x-pdf")));

    /**
     * The tab after a navigation: a PDF response is shown in the browser's document viewer, where no page script
     * and no add-on read runs, so it is reported as a document the session reads itself; anything else is read
     * as a page.
     */
    private PlaywrightPageState pageOrDocument(com.microsoft.playwright.Response response) {
        String contentType = response == null ? null : response.headers().get("content-type");
        if (isPdf(contentType)) {
            PlaywrightPageState viewedDocument = PlaywrightPageState.viewedDocument(response.url(), contentType);
            viewedDocuments.put(page, viewedDocument);
            return viewedDocument;
        }
        viewedDocuments.remove(page);
        return state();
    }

    static boolean isPdf(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String base = (parameters >= 0 ? contentType.substring(0, parameters) : contentType).trim();
        return PDF_CONTENT_TYPES.contains(base.toLowerCase(java.util.Locale.ROOT));
    }

    static boolean startedDownload(PlaywrightException ex) {
        String message = ex.getMessage();
        return message != null && message.contains(DOWNLOAD_STARTED_MESSAGE);
    }

    /** Hand in the capture collaborator (built from the analysis settings by the factory). */
    void setRenderedCapture(RenderedPageDocumentCapture capture) {
        this.renderedCapture = capture;
    }

    @Override
    public com.aresstack.askai.browser.render.RenderedPageDocument captureRenderedPage(
            com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys,
            long snapshotGeneration) throws BrowserException {
        owner.check();
        requireOpen();
        if (renderedCapture == null) {
            return null;
        }
        try {
            final Page target = page;
            com.aresstack.askai.browser.render.RenderedPageDocument document =
                    renderedCapture.capture(new RenderedPageDocumentCapture.PageScriptRunner() {
                public Object evaluate(String script) {
                    return readPage(target, script);
                }

                public String url() {
                    return target.url();
                }

                public String title() {
                    return target.title();
                }
            }, domainKeys, snapshotGeneration);
            System.err.println(SerpCaptureDiagnostics.summary(document));
            java.nio.file.Path dumpDirectory = SerpCaptureDiagnostics.snapshotDirectory();
            if (dumpDirectory != null) {
                try {
                    String html = SerpCaptureDiagnostics.sanitizedHtml(target.content());
                    System.err.println("[serp-capture] snapshot written to " + SerpCaptureDiagnostics
                            .dump(dumpDirectory, document, html));
                } catch (java.io.IOException | PlaywrightException dumpFailed) {
                    // Diagnostics only: a failed dump never fails the search.
                    System.err.println("[serp-capture] snapshot dump failed: " + dumpFailed);
                }
            }
            return document;
        } catch (PlaywrightException ex) {
            throw asBrowserException("Structured page capture failed", ex);
        }
    }

    // ------------------------------------------------------------------ SERP guards

    // #44: the ONE human-input policy of this driver — every pointer path/pause comes from it.
    private final com.aresstack.askai.browser.input.HumanInteractionPolicy humanPolicy =
            new com.aresstack.askai.browser.input.HumanInteractionPolicy(
                    com.aresstack.askai.browser.input.HumanInteractionPolicy.Config
                            .fromEnvironment(System.getenv()),
                    new java.util.Random());
    private int lastPointerX = 8;
    private int lastPointerY = 8;

    /** Human-paced pointer click via Playwright's mouse (many timed moves, exact target). */
    private void humanPointerClick(final int targetX, final int targetY) {
        humanPointer().moveAndClick(lastPointerX, lastPointerY, targetX, targetY);
        lastPointerX = targetX;
        lastPointerY = targetY;
    }

    /** The human-input policy over this page's REAL mouse (WebDriver BiDi input actions, trusted events only). */
    private com.aresstack.askai.browser.input.HumanPointer humanPointer() {
        return new com.aresstack.askai.browser.input.HumanPointer(humanPolicy,
                        new com.aresstack.askai.browser.input.HumanPointer.PointerDevice() {
                            public void moveTo(int x, int y) {
                                page.mouse().move(x, y);
                            }

                            public void click(int x, int y) {
                                page.mouse().click(x, y);
                            }

                            public void wheel(int deltaY) {
                                page.mouse().wheel(0, deltaY);
                            }

                            public void sleep(long millis) {
                                page.waitForTimeout(millis);
                            }
                        });
    }

    /** At most this many matches of one consent target are measured (the old in-page scan stopped at 120 buttons). */
    private static final int CONSENT_MATCHES_PER_TARGET = 20;
    /** A consent probe only measures what is already there: it never waits for a control to appear. */
    private static final double CONSENT_MEASURE_TIMEOUT_MILLIS = 1_000d;

    /**
     * A located consent control: the target it matched, the ELEMENT itself (a handle, so stacked banners with an equal
     * control at the same spot cannot be mistaken for the clicked one) and its box when it was measured.
     */
    private static final class ConsentHit {
        final SearchPageGuards.ConsentTarget target;
        final ElementHandle control;
        final BoundingBox box;
        /** All matches of the target and how many there were when measured: a click on a covering banner counts. */
        final Locator matches;
        final int matchCount;

        ConsentHit(SearchPageGuards.ConsentTarget target, ElementHandle control, BoundingBox box, Locator matches,
                   int matchCount) {
            this.target = target;
            this.control = control;
            this.box = box;
            this.matches = matches;
            this.matchCount = matchCount;
        }
    }

    @Override
    public String tryDismissConsent() {
        owner.check(); // BEFORE the guard-try: an ownership violation must never be swallowed as "none"
        if (closed || !consent.enabled) {
            return "none";
        }
        try {
            // Up to maximumDismissAttempts stacked banners; each successful click settles before the
            // next probe, and the caller re-reads the page when anything was clicked at all.
            // Issue #22: the control is located with Playwright locators (its own selector engine, no page script),
            // measured with boundingBox and clicked with the human-paced REAL mouse. There is no script fallback:
            // a JS click is exactly what bot detection looks for, so a click that does not land ends the attempt.
            List<SearchPageGuards.ConsentTarget> targets = SearchPageGuards.consentTargets(consent);
            String lastClicked = "none";
            for (int attempt = 0; attempt < consent.maximumDismissAttempts; attempt++) {
                ConsentHit hit = locateConsentControl(targets);
                if (hit == null) {
                    break;
                }
                try {
                    if (!pointerClickInView(hit.control, hit.box)) {
                        System.err.println("[browser-mcp] consent control " + hit.target.action
                                + " could not be clicked with the pointer; no script fallback");
                        break;
                    }
                    page.waitForTimeout(consent.postClickSettleMillis);
                    if (!controlReacted(hit)) {
                        System.err.println("[browser-mcp] consent control " + hit.target.action
                                + " did not react to the pointer click; no script fallback");
                        break;
                    }
                } finally {
                    disposeQuietly(hit.control);
                }
                lastClicked = "clicked:" + hit.target.action + ":pointer";
            }
            return lastClicked;
        } catch (RuntimeException ex) {
            return "none"; // a broken CMP must never take the search down
        }
    }

    /** The first visible control in minimal-consent priority order, or null. Reads nothing with a page script. */
    private ConsentHit locateConsentControl(List<SearchPageGuards.ConsentTarget> targets) {
        for (SearchPageGuards.ConsentTarget target : targets) {
            try {
                Locator matches = page.locator(target.selector);
                if (target.text != null) {
                    matches = matches.filter(new Locator.FilterOptions().setHasText(target.text));
                }
                int total = matches.count();
                int count = Math.min(total, CONSENT_MATCHES_PER_TARGET);
                for (int i = 0; i < count; i++) {
                    ElementHandle control = matches.nth(i).elementHandle(
                            new Locator.ElementHandleOptions().setTimeout(CONSENT_MEASURE_TIMEOUT_MILLIS));
                    BoundingBox box = control.boundingBox();
                    if (box != null && box.width > 0 && box.height > 0) {
                        return new ConsentHit(target, control, box, matches, total);
                    }
                    disposeQuietly(control);
                }
            } catch (PlaywrightException unusable) {
                // An invalid configured selector or a control that went away while measured: try the next target.
            }
        }
        return null;
    }

    /**
     * When the window height is unknown (Camoufox runs without viewport emulation, so {@code page.viewportSize()} is
     * null, and the fingerprint only knows the outer window), a control whose center lies below this line is first
     * wheeled up to {@link #UNKNOWN_WINDOW_ANCHOR}: every real window is at least this tall, so the click target is
     * then inside it. Asking the page for {@code innerHeight} would be a page script.
     */
    private static final int UNKNOWN_WINDOW_SAFE_Y = 360;
    private static final int UNKNOWN_WINDOW_ANCHOR = 200;
    private static final int MAX_SCROLL_ROUNDS = 6;
    /** A box is settled when two measurements this far apart agree (Argos RealInput#stableBox: 50 ms, 10 tries). */
    private static final int SETTLE_PROBE_MILLIS = 50;
    private static final int SETTLE_PROBES = 10;

    /** The control's box once it stops moving (smooth scrolling, late layout); null when it is gone. */
    private BoundingBox settledBox(ElementHandle control) {
        BoundingBox box = control.boundingBox();
        for (int probe = 0; probe < SETTLE_PROBES && box != null; probe++) {
            page.waitForTimeout(SETTLE_PROBE_MILLIS);
            BoundingBox again = control.boundingBox();
            if (again == null || sameBox(box, again)) {
                return again;
            }
            box = again;
        }
        return box;
    }

    private static boolean sameBox(BoundingBox a, BoundingBox b) {
        return Math.abs(a.x - b.x) < 0.5 && Math.abs(a.y - b.y) < 0.5
                && Math.abs(a.width - b.width) < 0.5 && Math.abs(a.height - b.height) < 0.5;
    }

    /**
     * Brings the control into the window with real wheel input when it lies (or may lie) outside, then clicks its
     * center with the human-paced mouse. False when it cannot be brought into view or the click is refused.
     */
    private boolean pointerClickInView(ElementHandle control, BoundingBox box) {
        try {
            BoundingBox target = box;
            com.microsoft.playwright.options.ViewportSize viewport = page.viewportSize();
            int height = viewport != null && viewport.height > 0 ? viewport.height : 0;
            int safeBottom = height > 0 ? height : UNKNOWN_WINDOW_SAFE_Y;
            int anchor = height > 0 ? height / 3 : UNKNOWN_WINDOW_ANCHOR;
            for (int round = 0; ; round++) {
                double centerY = target.y + target.height / 2;
                if (centerY >= 0 && centerY < safeBottom) {
                    break;
                }
                if (round == MAX_SCROLL_ROUNDS) {
                    // The box was measured after the last wheel turn settled (never a pre-scroll position). It is still
                    // below the safe line on a page that keeps growing (DuckDuckGo's late favicons) or ends there; the
                    // click goes to that current box, and a point outside the window is refused by the browser.
                    break;
                }
                // The wheel turns where the pointer rests, which must be inside the window: its last landed spot.
                page.mouse().move(lastPointerX, lastPointerY);
                humanPointer().scrollBy((int) Math.round(centerY - anchor));
                // Firefox scrolls smoothly: a box read right after the wheel lies mid-animation (Argos RealInput
                // stableBox), and a click there lands beside the control.
                BoundingBox moved = settledBox(control);
                if (moved == null || moved.width <= 0 || moved.height <= 0) {
                    return false;
                }
                if (Math.abs(moved.y - target.y) < 1) {
                    // Nothing scrolls (a fixed banner): it is where the page shows it, inside a known window or not.
                    if (height > 0 && (centerY < 0 || centerY >= height)) {
                        return false;
                    }
                    break;
                }
                target = moved;
            }
            humanPointerClick((int) Math.round(target.x + target.width / 2),
                    (int) Math.round(target.y + target.height / 2));
            return true;
        } catch (RuntimeException refused) {
            // e.g. "move target out of bounds": the pointer goes back to its last spot inside the window
            try {
                page.mouse().move(lastPointerX, lastPointerY);
            } catch (RuntimeException ignored) {
                // the page is gone
            }
            return false;
        }
    }

    /**
     * Whether the click took effect: the clicked element is gone, hidden or moved, or fewer controls of its kind are
     * left (a stacked banner on top took the click and closed). Read without a page script.
     */
    private boolean controlReacted(ConsentHit hit) {
        try {
            if (hit.matches.count() < hit.matchCount) {
                return true;
            }
        } catch (PlaywrightException unreadable) {
            // fall through to the element itself
        }
        BoundingBox before = hit.box;
        try {
            BoundingBox after = hit.control.boundingBox();
            return after == null || after.width <= 0 || after.height <= 0
                    || Math.abs(after.x - before.x) > 2 || Math.abs(after.y - before.y) > 2
                    || Math.abs(after.width - before.width) > 2 || Math.abs(after.height - before.height) > 2;
        } catch (PlaywrightException gone) {
            return true; // detached with its banner
        }
    }

    private static void disposeQuietly(ElementHandle handle) {
        try {
            handle.dispose();
        } catch (RuntimeException ignored) {
            // gone with its page
        }
    }

    // ------------------------------------------------------------------ search forms (issue #22, Argos' web_search)

    /**
     * The engines' tabs of the running search, at the index of their start page in {@link #openSearchTabs} (null where
     * no tab could be opened). Each engine is asked in a tab of its own (Argos' ParallelWebSearch): no tab is brought
     * to the front, real input goes to each tab's browsing context.
     */
    private final List<Page> searchTabs = new ArrayList<Page>();
    /** The page the driver worked on before the search tabs took over; restored by {@link #closeSearchTabs}. */
    private Page pageBeforeSearch;
    /**
     * Per page: how many top-level navigations were committed (protocol events only), to wait for the document that
     * Enter or a "next" click loads: until it is committed, the old page's load state says nothing.
     */
    private final Map<Page, int[]> navigationCounts = new java.util.IdentityHashMap<Page, int[]>();
    /** Per page: the HTTP method of its main frame's last navigation request ({@link #watchNavigations}). */
    private final Map<Page, String> lastNavigationMethods =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<Page, String>());
    /** Per tab: where its pointer rests (BiDi keeps one input state per browsing context). */
    private final Map<Page, int[]> tabPointers = new java.util.IdentityHashMap<Page, int[]>();
    /** A search field is only measured, never waited for. */
    private static final double SEARCH_BOX_MEASURE_TIMEOUT_MILLIS = 1_500d;
    /** How long the navigation that Enter (or a "next" click) starts may take to commit its new document. */
    private static final long SUBMIT_NAVIGATION_START_MILLIS = 15_000L;
    /** DOMContentLoaded is enough to read; late resources get a bounded chance (Argos' settleLoad). */
    private static final double SETTLE_LOAD_MILLIS = 5_000d;
    /** A start page's navigation is only started: this short wait for its commit holds back no other engine. */
    private static final double START_NAVIGATION_SLICE_MILLIS = 150d;
    /** How long one look at a start page's load state may wait. */
    private static final double LOAD_STATE_PROBE_MILLIS = 15d;
    /** The pause between rounds while no pending start page has committed yet. */
    private static final double START_PAGE_POLL_MILLIS = 20d;

    @Override
    public List<String> openSearchTabs(List<String> homeUrls) throws BrowserException {
        owner.check();
        requireOpen();
        closeSearchTabs();
        pageBeforeSearch = page;
        // ONE budget for all engines (Argos' loadTabs): the start pages load side by side against the same deadline.
        long deadline = System.currentTimeMillis() + navigationTimeoutMillis;
        List<PendingLoad> starts = new ArrayList<PendingLoad>();
        for (String url : homeUrls) {
            Page tab;
            try {
                tab = context.newPage(); // never brought to the front: which tab to watch stays the user's choice
            } catch (PlaywrightException failed) {
                PendingLoad none = new PendingLoad(null, 0);
                none.failure = "tab could not be opened: " + firstLine(failed);
                starts.add(none);
                searchTabs.add(null); // keeps every later engine at its own index
                continue;
            }
            searchTabs.add(tab);
            PendingLoad start = null;
            try {
                tab.onPopup(popupCloser());
                watchNavigations(tab);
                start = new PendingLoad(tab, navigationCounts.get(tab)[0]);
                starts.add(start);
                // Only STARTED here: at most a short wait for the commit, so an engine that stalls before answering
                // holds back no other start. On that timeout the navigation keeps running in the browser and is
                // looked at below with the others.
                tab.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.COMMIT)
                        .setTimeout(START_NAVIGATION_SLICE_MILLIS));
                start.committed = true;
            } catch (com.microsoft.playwright.TimeoutError stillLoading) {
                // committed later; polled below
            } catch (PlaywrightException failed) {
                if (start == null) {
                    start = new PendingLoad(null, 0);
                    starts.add(start);
                }
                start.failure = firstLine(failed); // this engine only; the others go on
            }
        }
        String overBudget = "the start page did not load within the " + navigationTimeoutMillis
                + " ms all engines share";
        awaitLoads(starts, deadline, deadline, navigationCounter(), overBudget, overBudget, pauser());
        List<String> outcomes = new ArrayList<String>();
        for (PendingLoad start : starts) {
            outcomes.add(start.failure);
        }
        return outcomes;
    }

    /**
     * One tab's page while it loads beside the others: an engine's start page, or the result page its Enter
     * started. Package-private for the stand-in page tests of {@link #awaitLoads}.
     */
    static final class PendingLoad {
        final Page tab;
        /** The tab's committed top-level navigations before this load; one more means the new document is there. */
        final int navigationsBefore;
        boolean committed;
        long domContentLoadedAt;
        boolean loaded;
        String failure;

        PendingLoad(Page tab, int navigationsBefore) {
            this.tab = tab;
            this.navigationsBefore = navigationsBefore;
        }

        boolean pending() {
            return failure == null && !loaded;
        }
    }

    /** How a tab's committed navigations are counted ({@link #watchNavigations}: protocol events only). */
    private java.util.function.ToIntFunction<Page> navigationCounter() {
        return new java.util.function.ToIntFunction<Page>() {
            public int applyAsInt(Page tab) {
                int[] count = navigationCounts.get(tab);
                return count == null ? 0 : count[0];
            }
        };
    }

    /** Pauses on a still open page: one of the pending tabs, else the page the search started from. */
    private java.util.function.BiConsumer<List<Page>, Double> pauser() {
        return new java.util.function.BiConsumer<List<Page>, Double>() {
            public void accept(List<Page> pending, Double millis) {
                List<Page> clocks = new ArrayList<Page>(pending);
                if (pageBeforeSearch != null) {
                    clocks.add(pageBeforeSearch);
                }
                pauseOn(clocks, millis.longValue());
            }
        };
    }

    /**
     * Waits {@code millis} on the first page of {@code clocks} that is still open, so the protocol events of every
     * tab keep arriving meanwhile. A clock page that closes during the wait only hands the rest of the wait to the
     * next open one; no single tab can end the wait for the others.
     */
    static void pauseOn(List<Page> clocks, long millis) {
        long until = System.currentTimeMillis() + millis;
        for (Page clock : clocks) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            if (clock == null || clock.isClosed()) {
                continue;
            }
            try {
                clock.waitForTimeout(left);
                return;
            } catch (PlaywrightException closedMeanwhile) {
                if (!clock.isClosed()) {
                    break; // not a closed tab: sit out the rest below instead of trying every page
                }
            }
        }
        sleepQuietly(until - System.currentTimeMillis());
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

    /**
     * Polls every pending load in turn until each has loaded or failed: commit (protocol event, until
     * {@code commitDeadline}), DOMContentLoaded, then load or {@link #SETTLE_LOAD_MILLIS} after DOMContentLoaded (until
     * {@code loadDeadline}; a page past DOMContentLoaded is read as it is then). Every check is a short wait, so no
     * tab waits for another tab's page (no head-of-line blocking), and a failure (closed tab, browser error page,
     * deadline over) ends only that tab's load. A closed tab is a failure at once, never polled on until a deadline.
     */
    static void awaitLoads(List<PendingLoad> loads, long commitDeadline, long loadDeadline,
                           java.util.function.ToIntFunction<Page> navigations, String noCommit, String notLoaded,
                           java.util.function.BiConsumer<List<Page>, Double> pause) {
        while (true) {
            boolean pending = false;
            boolean waited = false;
            List<Page> uncommitted = new ArrayList<Page>();
            for (PendingLoad load : loads) {
                if (!load.pending()) {
                    continue;
                }
                try {
                    if (load.tab.isClosed()) {
                        load.failure = "the tab was closed";
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    if (!load.committed) {
                        load.committed = navigations.applyAsInt(load.tab) > load.navigationsBefore;
                        if (!load.committed) {
                            if (now > commitDeadline) {
                                load.failure = noCommit;
                            } else {
                                pending = true;
                                uncommitted.add(load.tab);
                            }
                            continue;
                        }
                    }
                    if (now > loadDeadline) {
                        if (load.domContentLoadedAt > 0) {
                            load.loaded = true; // read with the DOM that is there
                        } else {
                            load.failure = notLoaded;
                        }
                        continue;
                    }
                    if (load.domContentLoadedAt == 0) {
                        waited = true;
                        if (!reached(load.tab, com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED)) {
                            pending = true;
                            continue;
                        }
                        load.domContentLoadedAt = System.currentTimeMillis();
                        String committedUrl = load.tab.url();
                        if (isBrowserErrorPage(committedUrl)) {
                            load.failure = "the page could not be loaded (" + committedUrl + ")";
                            continue;
                        }
                    }
                    waited = true;
                    if (reached(load.tab, com.microsoft.playwright.options.LoadState.LOAD)
                            || System.currentTimeMillis() - load.domContentLoadedAt > SETTLE_LOAD_MILLIS) {
                        load.loaded = true;
                    } else {
                        pending = true;
                    }
                } catch (PlaywrightException failed) {
                    load.failure = load.tab.isClosed() ? "the tab was closed" : firstLine(failed);
                }
            }
            if (!pending) {
                return;
            }
            if (!waited) {
                pause.accept(uncommitted, START_PAGE_POLL_MILLIS); // lets the protocol events of all tabs arrive
            }
        }
    }

    /** A load state check that waits only {@link #LOAD_STATE_PROBE_MILLIS}. */
    private static boolean reached(Page target, com.microsoft.playwright.options.LoadState state) {
        try {
            target.waitForLoadState(state, new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_PROBE_MILLIS));
            return true;
        } catch (com.microsoft.playwright.TimeoutError notYet) {
            return false;
        }
    }

    /** The browser's own page for a navigation that failed after its start (no server answer). */
    static boolean isBrowserErrorPage(String url) {
        return url != null && (url.startsWith("chrome-error:") || url.startsWith("about:neterror")
                || url.startsWith("about:certerror"));
    }

    @Override
    public PlaywrightPageState useSearchTab(int index) throws BrowserException {
        owner.check();
        requireOpen();
        if (index < 0 || index >= searchTabs.size() || searchTabs.get(index) == null
                || searchTabs.get(index).isClosed()) {
            throw new BrowserException("Search tab " + index + " is not open.");
        }
        page = searchTabs.get(index); // reads, guards and clicks now work on this tab; it is NOT brought to the front
        try {
            return state();
        } catch (PlaywrightException ex) {
            throw asBrowserException("Reading the search tab failed", ex);
        }
    }

    @Override
    public PlaywrightPageState openInSearchTab(int index, String url) throws BrowserException {
        owner.check();
        requireOpen();
        if (index < 0 || index >= searchTabs.size() || searchTabs.get(index) == null
                || searchTabs.get(index).isClosed()) {
            throw new BrowserException("Search tab " + index + " is not open.");
        }
        page = searchTabs.get(index);
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(navigationTimeoutMillis));
            settleLoad(page);
            return state();
        } catch (PlaywrightException ex) {
            throw asBrowserException("Navigation failed", ex);
        }
    }

    /**
     * Types the query into each listed tab's search field AT THE SAME TIME and presses Enter in each (Argos #79): the
     * steps of all tabs (pointer path into the field, click, select-all and Backspace, every key, Enter) are planned
     * with human timing and played as ONE timeline merged by time, so the keys of one tab fall into the pauses of the
     * others. Every step is real input over WebDriver BiDi to that tab's browsing context; no tab is switched to or
     * brought to the front, nothing is filled or submitted by script. Then waits for each tab's result page.
     *
     * @param searchBoxSelectors per tab (same index as the tabs): the field's selectors in order, or null to leave
     *                           that tab alone
     * @return per tab: {@code "submitted"}, {@code "no-search-box"}, {@code "refused: <why>"} or {@code "skipped"}
     */
    @Override
    public List<String> submitSearchInTabs(List<List<String>> searchBoxSelectors, String query) throws BrowserException {
        owner.check();
        requireOpen();
        String typed = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        List<String> outcomes = new ArrayList<String>();
        List<TabTyping> plans = new ArrayList<TabTyping>();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < searchTabs.size(); i++) {
            List<String> selectors = i < searchBoxSelectors.size() ? searchBoxSelectors.get(i) : null;
            Page tab = searchTabs.get(i);
            if (selectors == null || tab == null || tab.isClosed() || typed.isEmpty()) {
                outcomes.add("skipped");
                continue;
            }
            String refused = null;
            TabTyping plan = null;
            for (String selector : selectors) {
                try {
                    Locator first = tab.locator(selector).first();
                    if (first.count() == 0) {
                        continue;
                    }
                    ElementHandle field = first.elementHandle(
                            new Locator.ElementHandleOptions().setTimeout(SEARCH_BOX_MEASURE_TIMEOUT_MILLIS));
                    BoundingBox box = inView(tab, field, humanPolicy, pointerOf(tab));
                    if (box == null) {
                        disposeQuietly(field);
                        refused = "the search field " + selector + " is not visible in the window";
                        continue;
                    }
                    plan = new TabTyping(i, tab, selector, field);
                    planTyping(plan, box, typed, 40L + random.nextInt(360), random);
                    break;
                } catch (PlaywrightException unusable) {
                    refused = firstLine(unusable); // a hidden or covered match; an alternative may be the field
                }
            }
            if (plan != null) {
                plans.add(plan);
                outcomes.add("submitted");
            } else {
                outcomes.add(refused == null ? "no-search-box" : "refused: " + refused);
            }
        }
        if (plans.isEmpty()) {
            return outcomes;
        }
        playMerged(plans);
        for (TabTyping plan : plans) {
            disposeQuietly(plan.field);
        }
        awaitResultPages(plans);
        // After the wait: a tab whose Enter started no navigation, or whose result page never loaded, did not submit.
        for (TabTyping plan : plans) {
            if (plan.failure != null) {
                outcomes.set(plan.index, "refused: " + plan.failure);
            }
        }
        return outcomes;
    }

    /**
     * Clicks the pager and returns true only when ANOTHER result page is loaded: the click must start a navigation and
     * the page must then have a different address, or the navigation was a form POST (DuckDuckGo HTML/Lite page by
     * POST under one address; the session then compares the page's results). A click that lands beside the control (a stale box after a smooth
     * scroll, a covering element) or on the current page's own number loads nothing new; before, that was reported as
     * the next page and the same result page was read again as page 3 (Bing "first=11" twice).
     */
    @Override
    public boolean clickNextPage(List<String> selectors) throws BrowserException {
        owner.check();
        requireOpen();
        watchNavigations(page);
        String pageBefore = page.url();
        for (String selector : selectors) {
            ElementHandle control = null;
            try {
                Locator first = page.locator(selector).first();
                if (first.count() == 0) {
                    continue;
                }
                control = first.elementHandle(
                        new Locator.ElementHandleOptions().setTimeout(SEARCH_BOX_MEASURE_TIMEOUT_MILLIS));
                BoundingBox box = control.boundingBox();
                if (box == null || box.width <= 0 || box.height <= 0) {
                    continue;
                }
                int before = navigationCounts.get(page)[0];
                // The real, human-paced pointer (wheel scrolling to the pager first): never Locator.click.
                if (!pointerClickInView(control, box)) {
                    System.err.println("[browser-mcp] next-page control " + selector
                            + " could not be brought into the window and clicked; no script fallback");
                    continue;
                }
                boolean navigated = awaitNavigation(page, before);
                String pageAfter = page.url();
                if (navigated && (isAnotherPage(pageBefore, pageAfter)
                        || "POST".equalsIgnoreCase(lastNavigationMethods.get(page)))) {
                    return true; // a POST pager keeps its address; whether its page is new, the session judges
                }
                System.err.println("[browser-mcp] next-page click on " + selector + " loaded no other result page ("
                        + (navigated ? "same address " : "no navigation, still ") + pageAfter + ")");
                if (navigated) {
                    // The page was reloaded: the handles of the old document are gone, the next selector re-measures.
                    pageBefore = pageAfter;
                }
            } catch (PlaywrightException unusable) {
                // an alternative selector may be the engine's pager
            } finally {
                if (control != null) {
                    disposeQuietly(control);
                }
            }
        }
        return false;
    }

    /** Whether a pager click reached a different result page: the address differs, ignoring only a fragment. */
    static boolean isAnotherPage(String before, String after) {
        if (after == null || after.isEmpty() || "about:blank".equals(after)) {
            return false;
        }
        return before == null || !withoutFragment(before).equals(withoutFragment(after));
    }

    private static String withoutFragment(String url) {
        int hash = url.indexOf('#');
        return hash < 0 ? url : url.substring(0, hash);
    }

    @Override
    public void closeSearchTabs() {
        owner.check();
        for (Page tab : searchTabs) {
            if (tab == null || tab == challengePage) {
                continue; // a parked challenge stays open for the user, still bound to its add-on tab
            }
            pageKeys.remove(tab);
            navigationCounts.remove(tab);
            lastNavigationMethods.remove(tab);
            tabPointers.remove(tab);
            try {
                if (!tab.isClosed()) {
                    tab.close();
                }
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        searchTabs.clear();
        if (pageBeforeSearch != null) {
            page = pageBeforeSearch;
            pageBeforeSearch = null;
        }
    }

    /** One tab's planned input: the steps, each at its time after the timeline started. */
    private static final class TabTyping {
        final int index;
        final Page tab;
        final String selector;
        final ElementHandle field;
        final List<long[]> times = new ArrayList<long[]>();
        final List<Runnable> steps = new ArrayList<Runnable>();
        String failure;
        int navigationsBeforeEnter = -1;

        TabTyping(int index, Page tab, String selector, ElementHandle field) {
            this.index = index;
            this.tab = tab;
            this.selector = selector;
            this.field = field;
        }

        void at(long millis, Runnable step) {
            times.add(new long[]{millis, steps.size()});
            steps.add(step);
        }
    }

    /**
     * The steps of one tab, with the policy's human timing: a pointer path into the field (not its exact centre), a
     * click, a check that the click put the focus into the field (otherwise select-all would mark the page), select
     * all and Backspace, the query key by key (Shift held for capitals), a look at the input, Enter.
     */
    private void planTyping(final TabTyping plan, BoundingBox box, String typed, long start, java.util.Random random) {
        final Page tab = plan.tab;
        int[] from = pointerOf(tab);
        final int targetX = (int) Math.round(box.x + box.width * (0.25 + random.nextDouble() * 0.4));
        final int targetY = (int) Math.round(box.y + box.height * (0.35 + random.nextDouble() * 0.3));
        long t = start;
        for (final com.aresstack.askai.browser.input.HumanInteractionPolicy.TimedPoint point
                : humanPolicy.mousePath(from[0], from[1], targetX, targetY)) {
            plan.at(t, new Runnable() {
                public void run() {
                    tab.mouse().move(point.x, point.y);
                    pointerOf(tab)[0] = point.x;
                    pointerOf(tab)[1] = point.y;
                }
            });
            t += point.pauseMillis;
        }
        t += humanPolicy.preClickPauseMillis();
        plan.at(t, new Runnable() {
            public void run() {
                tab.mouse().down();
            }
        });
        t += 40 + random.nextInt(80);
        plan.at(t, new Runnable() {
            public void run() {
                tab.mouse().up();
            }
        });
        t += humanPolicy.postClickPauseMillis();
        plan.at(t, new Runnable() {
            public void run() {
                // Read through Playwright's selector engine (utility world), no page script.
                if (tab.locator(plan.selector + ":focus").count() == 0) {
                    throw new IllegalStateException("the click did not put the focus into the search field"
                            + " (an overlay or banner may cover it); nothing was typed");
                }
            }
        });
        t += 80 + random.nextInt(120);
        plan.at(t, key(tab, "ControlOrMeta+a"));
        t += 90 + random.nextInt(110);
        plan.at(t, key(tab, "Backspace"));
        List<Long> delays = humanPolicy.typingDelaysMillis(typed);
        for (int i = 0; i < typed.length(); ) {
            int codePoint = typed.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            t += delays.get(i);
            plan.at(t, typeCharacter(tab, character));
            i += Character.charCount(codePoint);
        }
        t += humanPolicy.preSubmitPauseMillis();
        plan.at(t, new Runnable() {
            public void run() {
                plan.navigationsBeforeEnter = navigationCounts.get(tab)[0];
                tab.keyboard().press("Enter");
            }
        });
    }

    /** Plays the steps of all tabs in time order; a tab whose step fails stops, the others go on. */
    private void playMerged(List<TabTyping> plans) {
        List<Object[]> timeline = new ArrayList<Object[]>();
        for (TabTyping plan : plans) {
            for (long[] time : plan.times) {
                timeline.add(new Object[]{time[0], plan, plan.steps.get((int) time[1])});
            }
        }
        java.util.Collections.sort(timeline, new java.util.Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) {
                return Long.compare((Long) a[0], (Long) b[0]);
            }
        });
        long started = System.currentTimeMillis();
        for (Object[] entry : timeline) {
            TabTyping plan = (TabTyping) entry[1];
            if (plan.failure != null) {
                continue;
            }
            long wait = (Long) entry[0] - (System.currentTimeMillis() - started);
            if (wait > 0) {
                // Any still open tab keeps the Playwright message loop running meanwhile: the timeline belongs to no
                // single tab, so one tab closing during the input ends only that tab's input.
                pauseOn(typingClocks(plans), wait);
            }
            if (plan.tab.isClosed()) {
                plan.failure = "the tab was closed during the input";
                continue;
            }
            try {
                ((Runnable) entry[2]).run();
            } catch (RuntimeException refused) {
                plan.failure = firstLine(refused);
                try {
                    plan.tab.keyboard().up("Shift"); // never leave a tab with Shift held
                } catch (RuntimeException ignored) {
                    // the tab is gone
                }
            }
        }
    }

    /** The tabs still taking input, then the page the search started from. */
    private List<Page> typingClocks(List<TabTyping> plans) {
        List<Page> clocks = new ArrayList<Page>();
        for (TabTyping plan : plans) {
            if (plan.failure == null && !plan.tab.isClosed()) {
                clocks.add(plan.tab);
            }
        }
        if (pageBeforeSearch != null) {
            clocks.add(pageBeforeSearch);
        }
        return clocks;
    }

    /** One named key or chord as real key input. */
    private static Runnable key(final Page tab, final String chord) {
        return new Runnable() {
            public void run() {
                tab.keyboard().press(chord);
            }
        };
    }

    /** Shifted symbols of the US layout: typed with Shift held, as on a real keyboard (Playwright's type omits it). */
    private static final String SHIFTED_SYMBOLS = "~!@#$%^&*()_+{}|:\"<>?";

    /**
     * One character as a real key. Over WebDriver BiDi playwright4j sends any single grapheme ("ä") as a real key
     * (playwright4j#59); a browser that refuses the key name (Chromium) gets it as inserted text, which CDP delivers
     * as trusted native input.
     */
    private static Runnable typeCharacter(final Page tab, final String character) {
        return new Runnable() {
            public void run() {
                if (" ".equals(character)) {
                    tab.keyboard().press("Space");
                    return;
                }
                int codePoint = character.codePointAt(0);
                boolean shift = Character.isUpperCase(codePoint) || SHIFTED_SYMBOLS.indexOf(codePoint) >= 0;
                if (shift) {
                    tab.keyboard().down("Shift");
                }
                try {
                    tab.keyboard().press(character);
                } catch (PlaywrightException refused) {
                    if (!String.valueOf(refused.getMessage()).contains("Unknown key")) {
                        throw refused;
                    }
                    tab.keyboard().insertText(character);
                } finally {
                    if (shift) {
                        tab.keyboard().up("Shift");
                    }
                }
            }
        };
    }

    /**
     * The field's box once it is inside the window: a field below the safe line is first wheeled up with real input
     * (the pointer resting inside the window). Null when it has no box or cannot be brought into view.
     *
     * <p>When the window height is unknown (Camoufox reports no viewport), the safe line {@link #UNKNOWN_WINDOW_SAFE_Y}
     * is only a guess: a field below it on a page that does not scroll is where the page shows it, because a page that
     * does not scroll fits into its window. It is taken as it is, like {@link #pointerClickInView} takes such a control
     * (DuckDuckGo's HTML start page: its field sits at y=372 and the page does not scroll). With a known window height
     * a field that does not move and stays outside the window is still refused.</p>
     */
    static BoundingBox inView(Page tab, ElementHandle field,
                              com.aresstack.askai.browser.input.HumanInteractionPolicy policy, int[] pointer) {
        BoundingBox box = field.boundingBox();
        com.microsoft.playwright.options.ViewportSize viewport = tab.viewportSize();
        int height = viewport != null && viewport.height > 0 ? viewport.height : 0;
        int safeBottom = height > 0 ? height : UNKNOWN_WINDOW_SAFE_Y;
        int anchor = height > 0 ? height / 3 : UNKNOWN_WINDOW_ANCHOR;
        for (int round = 0; round < MAX_SCROLL_ROUNDS && box != null; round++) {
            double centerY = box.y + box.height / 2;
            if (box.width <= 0 || box.height <= 0) {
                return null;
            }
            if (centerY >= 0 && centerY < safeBottom) {
                return box;
            }
            tab.mouse().move(pointer[0], pointer[1]);
            for (com.aresstack.askai.browser.input.HumanInteractionPolicy.ScrollStep step
                    : policy.scrollSteps((int) Math.round(centerY - anchor))) {
                tab.mouse().wheel(0, step.deltaY);
                tab.waitForTimeout(step.pauseMillis);
            }
            BoundingBox moved = field.boundingBox();
            if (moved == null || moved.width <= 0 || moved.height <= 0) {
                return null;
            }
            if (Math.abs(moved.y - box.y) < 1) {
                // Nothing scrolls: the page fits its window, so an unknown window shows the field where it is.
                return height == 0 && centerY >= 0 ? moved : null;
            }
            box = moved;
        }
        return box != null && box.y + box.height / 2 >= 0 && box.y + box.height / 2 < safeBottom ? box : null;
    }

    private int[] pointerOf(Page tab) {
        int[] pointer = tabPointers.get(tab);
        if (pointer == null) {
            pointer = new int[]{8 + new java.util.Random().nextInt(40), 8 + new java.util.Random().nextInt(40)};
            tabPointers.put(tab, pointer);
        }
        return pointer;
    }

    /** Counts a page's committed top-level navigations (protocol events only). Idempotent. */
    private void watchNavigations(final Page target) {
        if (navigationCounts.containsKey(target)) {
            return;
        }
        final int[] count = new int[1];
        navigationCounts.put(target, count);
        // The method of the main frame's last navigation request (protocol events only): a form that pages by POST
        // (DuckDuckGo's HTML and Lite endpoints) shows the next result page under the SAME address.
        target.onRequest(new Consumer<com.microsoft.playwright.Request>() {
            public void accept(com.microsoft.playwright.Request request) {
                try {
                    if (request.isNavigationRequest() && request.frame() == target.mainFrame()) {
                        lastNavigationMethods.put(target, request.method());
                    }
                } catch (RuntimeException ignored) {
                    // a request of a frame that is already gone
                }
            }
        });
        target.onFrameNavigated(new Consumer<com.microsoft.playwright.Frame>() {
            public void accept(com.microsoft.playwright.Frame frame) {
                if (frame == target.mainFrame()) {
                    count[0]++;
                }
            }
        });
    }

    /**
     * After Enter in every typed tab: each tab's result page, polled side by side against ONE deadline (the same
     * polling as the start pages): a tab whose page hangs holds back no other tab's page, and the tabs do not add
     * up their waits.
     */
    private void awaitResultPages(List<TabTyping> plans) {
        long started = System.currentTimeMillis();
        long loadDeadline = started + navigationTimeoutMillis;
        long commitDeadline = Math.min(started + SUBMIT_NAVIGATION_START_MILLIS, loadDeadline);
        List<PendingLoad> loads = new ArrayList<PendingLoad>();
        List<TabTyping> waitedFor = new ArrayList<TabTyping>();
        for (TabTyping plan : plans) {
            if (plan.failure != null || plan.navigationsBeforeEnter < 0) {
                continue;
            }
            loads.add(new PendingLoad(plan.tab, plan.navigationsBeforeEnter));
            waitedFor.add(plan);
        }
        awaitLoads(loads, commitDeadline, loadDeadline, navigationCounter(),
                "Enter started no navigation (the engine did not take the query)",
                "the result page did not load within the " + navigationTimeoutMillis + " ms all engines share",
                pauser());
        for (int i = 0; i < loads.size(); i++) {
            String failure = loads.get(i).failure;
            if (failure != null) {
                waitedFor.get(i).failure = failure.startsWith("Enter started") || failure.startsWith("the result page")
                        ? failure : "the result page did not load: " + failure;
            }
        }
    }

    /**
     * Waits for the navigation a click started, then for its document; protocol events only. False when the click
     * started no navigation at all: then the page shows the same document as before and must not be read as a new one.
     */
    private boolean awaitNavigation(Page target, int before) {
        final int[] count = navigationCounts.get(target);
        try {
            if (count[0] <= before) {
                target.waitForCondition(() -> count[0] > before,
                        new Page.WaitForConditionOptions().setTimeout(SUBMIT_NAVIGATION_START_MILLIS));
            }
        } catch (PlaywrightException noNavigation) {
            return count[0] > before;
        }
        try {
            target.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(navigationTimeoutMillis));
            settleLoad(target);
        } catch (PlaywrightException slow) {
            // reading proceeds with the document that is there
        }
        return true;
    }

    /** Gives late subresources a bounded chance, never a long sleep. */
    private static void settleLoad(Page target) {
        try {
            target.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout(SETTLE_LOAD_MILLIS));
        } catch (PlaywrightException slowSubresources) {
            // reading proceeds with the DOM that is there
        }
    }

    @Override
    public String renderHud(String stateLine) {
        owner.check();
        if (closed) {
            return "closed";
        }
        if (!ownsGeneration) {
            return "unsupported"; // the HUD belongs to the chat's own browser, never to a worker tab
        }
        com.aresstack.askai.browser.hud.ResearchHudState state =
                com.aresstack.askai.browser.hud.ResearchHudState.parse(stateLine);
        if (camoufox || addonBridge != null) {
            // Camoufox never gets the overlay or the binding (issue #22); Chrome/Edge with the research extension
            // (stage 4) neither: the HUD lives in the extension.
            return renderHudInAddon(state);
        }
        if (!inPageFallbackAllowed()) {
            return "unsupported";
        }
        // Chrome/Edge without the extension (switched off, or it could not be loaded): the in-page overlay.
        try {
            if (!hudBindingRegistered) {
                // Buffer overlay button commands; registered on the CONTEXT so it survives navigations.
                context.exposeBinding("__askaiHudCommand", (source, args) -> {
                    if (args != null && args.length > 0 && args[0] != null) {
                        hudCommands.add(String.valueOf(args[0]));
                    }
                    return null;
                });
                hudBindingRegistered = true;
            }
            pageScripts.evaluate(page, ResearchHudOverlay.installScript(), "hud-overlay"); // idempotent
            pageScripts.evaluate(page, ResearchHudOverlay.renderScript(state), "hud-overlay");
            return "rendered";
        } catch (RuntimeException ex) {
            return "error"; // a HUD failure must never take the visit down
        }
    }

    /**
     * The HUD through the research add-on, the only HUD Camoufox has: the state is always published (a late add-on
     * shows the current one at once). Without a connected add-on there is no HUD in the browser and only the log
     * says so; nothing is put into the page instead.
     */
    private String renderHudInAddon(com.aresstack.askai.browser.hud.ResearchHudState state) {
        if (addonBridge == null) {
            if (!addonMissingLogged) {
                addonMissingLogged = true;
                System.err.println("[browser-mcp] research add-on is off or not prepared: no HUD in the browser "
                        + "(nothing is put into the page)");
            }
            return "addon-missing";
        }
        addonBridge.publish(state, pageHost());
        if (addonBridge.addonConnected()) {
            if (!addonConnectedLogged) {
                addonConnectedLogged = true;
                System.err.println("[browser-mcp] research add-on connected (version "
                        + addonBridge.addonVersion() + "): HUD shown in the browser UI, not in the page");
            }
            return "rendered-addon";
        }
        if (System.currentTimeMillis() - launchedMillis < ADDON_GRACE_MILLIS) {
            return "pending-addon";
        }
        if (!addonMissingLogged) {
            addonMissingLogged = true;
            System.err.println("[browser-mcp] WARNING research add-on did not connect within "
                    + (ADDON_GRACE_MILLIS / 1000L) + " s: no HUD in the browser until it does (nothing is put into "
                    + "the page)");
        }
        return "addon-missing";
    }

    /** The host of the current page for the add-on panel; never the full URL (no paths or queries leave here). */
    private String pageHost() {
        try {
            String host = java.net.URI.create(page.url()).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean inPageFallbackAllowed() {
        return !"false".equalsIgnoreCase(System.getProperty(IN_PAGE_FALLBACK_PROPERTY));
    }

    @Override
    public String pollHudCommands() {
        // Deliberately NO owner check: this drains a thread-safe Java inbox and never touches Playwright.
        return hudCommands.drain();
    }

    @Override
    public HudCommandInbox hudInbox() {
        return hudCommands;
    }

    /**
     * Worker tabs only: the user closing the ACTIVE page ends the tab (its later calls fail with the typed
     * marker). A parked challenge page closing — resolved, or closed by {@link #closeParkedChallenge()} — is
     * not the tab going away.
     */
    private Consumer<Page> activeTabCloseWatcher() {
        return new Consumer<Page>() {
            public void accept(Page gone) {
                if (!closed && gone == page) {
                    browserDisconnected = true;
                }
            }
        };
    }

    /** The chat's tabs whose closing the driver watches ({@link #chatTabCloseWatcher}); owner thread only. */
    private final java.util.Set<Page> watchedChatTabs =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Page, Boolean>());

    /**
     * The research continues in the chat's tab {@code tab}: reads, links, guards and navigation of the session work on
     * the tab the chat used last, so the chat's {@code browser_*} tools and the research's {@code web_*} tools see one
     * current page. Owner thread, between calls; true when the current page became another tab.
     */
    boolean useChatTab(Page tab) {
        owner.check();
        if (tab == null) {
            return false;
        }
        pageGeneration++; // the chat may change the page, also when it stays on the same tab
        boolean changed = tab != page;
        page = tab;
        watchChatTab(tab);
        return changed;
    }

    /**
     * The chat's load of the current tab answered with a PDF the browser shows in its viewer: as after
     * {@link #open}, the tab's document is that PDF, which the session reads itself. Null when the response is no PDF.
     */
    PlaywrightPageState chatViewedDocument(com.microsoft.playwright.Response response) {
        owner.check();
        String contentType = response == null ? null : response.headers().get("content-type");
        if (!isPdf(contentType)) {
            viewedDocuments.remove(page);
            return null;
        }
        PlaywrightPageState viewedDocument = PlaywrightPageState.viewedDocument(response.url(), contentType);
        viewedDocuments.put(page, viewedDocument);
        return viewedDocument;
    }

    /** The PDF the current tab's viewer shows, or null when it shows a page. Owner thread. */
    PlaywrightPageState viewedDocumentOfCurrentTab() {
        owner.check();
        PlaywrightPageState viewed = viewedDocuments.get(page);
        return viewed != null && viewed.url.equals(page.url()) ? viewed : null;
    }

    /** The chat changed pages without naming one current page (parallel loading in several tabs). */
    void chatChangedPages() {
        owner.check();
        pageGeneration++;
    }

    @Override
    public long pageGeneration() {
        return pageGeneration;
    }

    private void watchChatTab(Page tab) {
        if (watchedChatTabs.add(tab)) {
            tab.onClose(chatTabCloseWatcher());
        }
    }

    /**
     * The chat's current tab closing: the driver goes on in another open tab of the context (its event pump needs a
     * page); only when none is left is the browser gone.
     */
    private Consumer<Page> chatTabCloseWatcher() {
        return new Consumer<Page>() {
            public void accept(Page gone) {
                viewedDocuments.remove(gone);
                watchedChatTabs.remove(gone); // a closed tab is no longer kept reachable
                if (closed || gone != page) {
                    return;
                }
                for (Page other : context.pages()) {
                    if (other != gone && !other.isClosed()) {
                        page = other;
                        pageGeneration++; // another tab took over: the session's links are the closed tab's
                        watchChatTab(other);
                        return;
                    }
                }
                browserDisconnected = true;
            }
        };
    }

    /** The one popup policy: close immediately, count it, and never let a dead popup break the session. */
    private Consumer<Page> popupCloser() {
        return new Consumer<Page>() {
            public void accept(Page popup) {
                popupsClosed.incrementAndGet();
                try {
                    popup.close();
                } catch (RuntimeException ignored) {
                    // A popup that is already gone must not take the session down.
                }
            }
        };
    }

    /**
     * A worker tab: read its pages through the worker browser's add-on bridge (owned and closed by the worker
     * browser), or null to keep {@code page.evaluate}.
     */
    void readThroughAddon(AddonBridge workerBridge) {
        this.readBridge = workerBridge;
    }

    /** The research add-on's bridge, or null (switched off, not prepared or not loaded; Chrome/Edge worker tabs). */
    AddonBridge addonBridge() {
        return addonBridge;
    }

    /** Tests only: the browser context, e.g. to reach the Chrome extension's service worker. */
    BrowserContext browserContext() {
        return context;
    }

    /** How many popups were closed so far. Thread-safe counter read — touches no Playwright state. */
    int closedPopupCount() {
        return popupsClosed.get();
    }

    @Override
    public boolean pumpEvents(java.util.function.BooleanSupplier wake, long timeoutMillis) {
        owner.check();
        if (closed) {
            return false; // torn down — the actor falls back to a plain queue wait
        }
        Page target = page;
        if (target == null) {
            return false;
        }
        try {
            // Runs the Playwright message loop until the wake condition holds or the slice ends: route
            // interception gets its resume/abort, exposeBinding and popup events are delivered — even
            // when no MCP command is in flight. This is what keeps the visible browser interactive.
            target.waitForCondition(wake::getAsBoolean,
                    new Page.WaitForConditionOptions().setTimeout(timeoutMillis));
        } catch (com.microsoft.playwright.TimeoutError normalIdle) {
            // The slice ended with nothing queued — the actor simply pumps again.
        } catch (RuntimeException broken) {
            // A page closing/navigating mid-pump must not kill the loop; yield briefly so a permanently
            // broken page cannot hot-spin the owner thread. But FIRST: when the pump broke because the
            // USER closed the window, record it — with the page gone no message loop runs anymore, so
            // Browser.onDisconnected would never dispatch and the close would stay invisible until some
            // API call happened to fail.
            if (!connected() || page.isClosed()) {
                browserDisconnected = true;
            }
            try {
                Thread.sleep(Math.min(50L, Math.max(1L, timeoutMillis)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return true;
    }

    @Override
    public boolean browserClosedByUser() {
        owner.check();
        return !closed && (browserDisconnected || !connected());
    }

    @Override
    public boolean challengePresent() {
        owner.check();
        if (closed || !captcha.enabled) {
            return false;
        }
        try {
            // Only a VISIBLE/blocking challenge counts here (this drives challenge parking) — a hidden
            // artifact ('hidden:…') must never park a readable page.
            return String.valueOf(readPage(page, SearchPageGuards.challengeDetectScript(captcha)))
                    .startsWith("visible");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public String challengeMarker() {
        owner.check();
        if (closed || !captcha.enabled) {
            return "none";
        }
        try {
            return String.valueOf(readPage(page, SearchPageGuards.challengeDetectScript(captcha)));
        } catch (RuntimeException ex) {
            return "none";
        }
    }

    @Override
    public String consentCandidate() {
        owner.check();
        if (closed || !consent.enabled) {
            return "none";
        }
        try {
            return String.valueOf(readPage(page, SearchPageGuards.consentReportScript(consent)));
        } catch (RuntimeException ex) {
            return "none"; // a broken CMP script must never take a probe down
        }
    }

    @Override
    public boolean parkChallenge() {
        owner.check();
        if (closed || challengePage != null) {
            return false;
        }
        if (searchTabs.contains(page) && pageBeforeSearch != null) {
            // An engine's tab shows the challenge: it simply stays open for the user (not closed with the other
            // search tabs); the search goes on in the other engines' tabs.
            challengePage = page;
            page = pageBeforeSearch;
            if (captcha.focusTabOnFirstDetection) {
                try {
                    challengePage.bringToFront();
                } catch (RuntimeException ignored) {
                }
            }
            return true;
        }
        try {
            challengePage = page;
            page = context.newPage();
            page.onPopup(popupCloser());
            if (!ownsGeneration) {
                page.onClose(activeTabCloseWatcher());
            }
            // Bring the challenge to the user's attention exactly ONCE — polls never steal focus again.
            if (captcha.focusTabOnFirstDetection) {
                try {
                    challengePage.bringToFront();
                } catch (RuntimeException ignored) {
                }
            }
            return true;
        } catch (RuntimeException ex) {
            page = challengePage; // fresh page failed: keep working on the original page
            challengePage = null;
            return false;
        }
    }

    @Override
    public boolean parkedChallengeStillPresent() {
        owner.check();
        if (closed || challengePage == null) {
            return false;
        }
        try {
            // Resolved once the challenge is no longer VISIBLY blocking (a lingering hidden artifact does not count).
            return String.valueOf(readPage(challengePage, SearchPageGuards.challengeDetectScript(captcha)))
                    .startsWith("visible");
        } catch (RuntimeException ex) {
            return false; // an unreadable/closed challenge tab counts as resolved, never blocks forever
        }
    }

    @Override
    public void closeParkedChallenge() {
        owner.check();
        if (challengePage != null) {
            pageKeys.remove(challengePage);
            navigationCounts.remove(challengePage);
            tabPointers.remove(challengePage);
            try {
                challengePage.close();
            } catch (RuntimeException ignored) {
            }
            challengePage = null;
        }
    }

    @Override
    public void close() {
        owner.check();
        if (closed) {
            return;
        }
        closed = true;
        closeParkedChallenge();
        try {
            page.close();
        } catch (RuntimeException ignored) {
        }
        if (!ownsGeneration) {
            return; // a worker tab: the user context and the browser stay with the worker browser
        }
        if (launched != null) {
            launched.close();
        }
        if (addonBridge != null) {
            addonBridge.close();
        }
    }

    /**
     * A request of the Chrome extension's service worker to its bridge: the exact bridge prefix with this start's
     * bearer token, which only the extension has (it reads it from its own {@code bridge.json}). A page knows neither.
     * Deliberately decided on the URL and headers only: asking Playwright for the request's frame from inside the
     * route handler blocked the interception setup in the live test.
     */
    static boolean fromExtensionWorker(com.microsoft.playwright.Request request, String bridgePrefix,
                                       String bearer) {
        if (!request.url().startsWith(bridgePrefix)) {
            return false;
        }
        String authorization = request.headers().get("authorization");
        return authorization != null && java.security.MessageDigest.isEqual(
                authorization.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                bearer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void terminate() {
        // Deliberately NO owner check: the owner thread is the stuck one. Cutting the Playwright connection
        // fails its pending call ("connection closed") and ends the driver child; `closed` stops the pump
        // and every later call. The cut runs on its own daemon thread because Playwright's close waits
        // (bounded) for the driver process to exit — the caller must never block here. Context and browser
        // are NOT closed over the stuck connection (that could block forever); the browser window of a cut
        // connection may therefore survive as an orphan. close() stays the orderly path.
        if (closed) {
            return;
        }
        closed = true;
        browserDisconnected = true;
        if (!ownsGeneration) {
            return; // the worker browser cuts its own connection; a tab has nothing of its own to cut
        }
        final LaunchedBrowser toCut = launched;
        final AddonBridge bridgeToClose = addonBridge;
        Thread cutter = new Thread(new Runnable() {
            public void run() {
                if (toCut != null) {
                    toCut.abandon();
                }
                if (bridgeToClose != null) {
                    bridgeToClose.close();
                }
            }
        }, "playwright-terminate");
        cutter.setDaemon(true);
        cutter.start();
    }

    /** Whether the browser is still there; a persistent context without a Browser object ends with its context. */
    private boolean connected() {
        return browser != null ? browser.isConnected() : !contextClosed;
    }

    /**
     * RENDERED state via the DOM: url after redirects, title, body innerText, anchors with absolute hrefs. The page
     * scripts and their parsing are the chat's ({@link PageOperations#readResult}, {@link PageOperations#linksResult}):
     * one reading and one link extraction for chat and research; here they run through {@link #readPage}.
     */
    private PlaywrightPageState state() {
        String readJson = String.valueOf(readPage(page, "() => " + ChatPageScripts.READ_SCRIPT));
        String linksJson = String.valueOf(readPage(page, "() => " + ChatPageScripts.LINKS_SCRIPT));
        com.aresstack.enterpriseai.browser.api.engine.RawPage read;
        List<com.aresstack.enterpriseai.browser.api.engine.RawLink> links;
        try {
            read = PageOperations.readResult(readJson);
            links = PageOperations.linksResult(linksJson);
        } catch (RuntimeException unreadable) {
            // Every caller handles a failed page read as a PlaywrightException.
            throw new PlaywrightException("The page could not be read: " + unreadable.getMessage());
        }
        List<PlaywrightPageState.Anchor> anchors = new ArrayList<PlaywrightPageState.Anchor>(links.size());
        for (com.aresstack.enterpriseai.browser.api.engine.RawLink link : links) {
            anchors.add(new PlaywrightPageState.Anchor(link.text(), link.href()));
        }
        return new PlaywrightPageState(read.url(), read.title(), read.text(), anchors);
    }

    /**
     * Every read-only page script goes through here. With the research add-on it runs in the tab's isolated
     * content-script world (docs/research-browser-addon.md): the page's own scripts never see it, and no script of
     * AskAI is evaluated in the page's world. Only a driver without it (switched off, not prepared or not loaded,
     * Chrome/Edge worker tabs) still uses {@code page.evaluate}, through {@link PageScriptGate}. Failures surface as {@link PlaywrightException}, so every caller
     * keeps its existing error handling. While waiting, the Playwright message loop keeps running (route
     * interception, popups), exactly as during the idle pump.
     */
    private Object readPage(Page target, String script) {
        AddonBridge reads = readBridge;
        if (reads == null) {
            // Without the add-on: the main-world fallback passes the script gate (refused in script-free mode).
            return pageScripts.evaluate(target, script, "page-read");
        }
        String key = pageKeys.get(target);
        if (key == null) {
            key = "page-" + PAGE_KEYS.incrementAndGet();
            pageKeys.put(target, key);
        }
        AddonBridge.PageRead read = reads.submitRead(key, target.url(), script);
        try {
            if (!read.isDone()) {
                target.waitForCondition(read::isDone,
                        new Page.WaitForConditionOptions().setTimeout(PAGE_READ_TIMEOUT_MILLIS));
            }
            return read.value();
        } catch (com.microsoft.playwright.TimeoutError timedOut) {
            throw new PlaywrightException("The research add-on did not answer the page read within "
                    + (PAGE_READ_TIMEOUT_MILLIS / 1000L) + " s");
        } catch (AddonBridge.PageReadFailed failed) {
            throw new PlaywrightException(failed.getMessage());
        } finally {
            if (!read.isDone()) {
                read.cancel();
            }
        }
    }

    private void requireOpen() throws BrowserException {
        if (closed) {
            throw new BrowserException("Browser session is closed.");
        }
        if (browserDisconnected) {
            throw new BrowserException(BROWSER_CLOSED_MESSAGE);
        }
    }

    /** Map a Playwright failure to a readable tool error; a dead/closed browser keeps its typed marker. */
    private BrowserException asBrowserException(String action, PlaywrightException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if (browserDisconnected || message.contains("has been closed") || message.contains("Target closed")) {
            return new BrowserException(BROWSER_CLOSED_MESSAGE);
        }
        return new BrowserException(action + ": " + firstLine(ex));
    }

    /** Tool errors carry the reason, never a multi-line driver stack trace. */
    private static String firstLine(Throwable ex) {
        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        // Playwright wraps driver errors as "Error {\n  message='...'": the message line is the useful one.
        int wrapped = message.startsWith("Error {") ? message.indexOf("message='") : -1;
        if (wrapped > 0) {
            message = message.substring(wrapped + "message='".length());
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline).trim() : message.trim();
    }
}
