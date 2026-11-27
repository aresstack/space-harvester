package com.aresstack.askai.browser.playwright;

import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.BrowserProbe;
import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.RawActOutcome;
import com.aresstack.enterpriseai.browser.api.engine.RawControl;
import com.aresstack.enterpriseai.browser.api.engine.RawDownload;
import com.aresstack.enterpriseai.browser.api.engine.RawLink;
import com.aresstack.enterpriseai.browser.api.engine.RawPage;
import com.aresstack.enterpriseai.browser.api.engine.RawScreenshot;
import com.aresstack.enterpriseai.browser.api.engine.RawTab;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine connection of the chat browser on Playwright: the Firefox adapter of the Enterprise AI Client
 * ({@code BidiBrowserConnection}) carried over to Playwright. The page operations (page scripts, waiting rules,
 * real input) are in {@link PageOperations}; this connection keeps the tabs and their ids. Tabs are the pages of the
 * persistent context; their ids are local to this browser. Playwright is not thread-safe, so every call holds the
 * lock of its browser instance.
 */
final class PlaywrightChatConnection implements BrowserConnection {

    private final PlaywrightChatEngine.Instance instance;
    private final BrowserContext context;
    private final long navigationTimeoutMillis;
    private final Map<Page, String> ids = new IdentityHashMap<>();
    private final Map<String, Page> pages = new HashMap<>();
    private int nextId;
    private volatile boolean disconnected;
    /** Human variation of input, scrolling and reading; off unless the chat switched it on. */
    private volatile HumanMotion human = HumanMotion.off();
    /** Last pointer position per tab, start of the next mouse path. */
    private final Map<String, PageOperations.Pointer> pointers = new ConcurrentHashMap<>();
    /** The page operations, shared with the research driver; this connection adds tab ids and the lock. */
    private final PageOperations operations;

    PlaywrightChatConnection(PlaywrightChatEngine.Instance instance, BrowserContext context,
                             long navigationTimeoutMillis) {
        this.instance = instance;
        this.context = context;
        this.navigationTimeoutMillis = navigationTimeoutMillis;
        this.operations = new PageOperations(new PageOperations.Guard() {
            @Override
            public void check() throws BrowserException {
                PlaywrightChatConnection.this.check();
            }

            @Override
            public BrowserException classify(PlaywrightException e) {
                return PlaywrightChatConnection.this.classify(e);
            }
        }, navigationTimeoutMillis);
    }

    @Override
    public boolean isOpen() {
        return !disconnected && instance.isAlive();
    }

    @Override
    public List<RawTab> tabs() throws BrowserException {
        synchronized (instance) {
            check();
            try {
                List<RawTab> tabs = new ArrayList<>();
                for (Page page : context.pages()) {
                    String id = idOf(page);
                    tabs.add(new RawTab(id, page.url(), title(id)));
                }
                return tabs;
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public String createTab() throws BrowserException {
        synchronized (instance) {
            check();
            try {
                return idOf(context.newPage());
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public void activate(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            try {
                page(contextId).bringToFront();
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public void close(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            try {
                Page page = page(contextId);
                page.close();
                pages.remove(contextId);
                ids.remove(page);
                pointers.remove(contextId);
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public void navigate(String contextId, String url) throws BrowserException {
        synchronized (instance) {
            check();
            operations.navigate(page(contextId), url, human, pointerOf(contextId));
        }
    }

    @Override
    public void setHumanEmulation(boolean keys, boolean mouse) {
        HumanMotion now = human;
        if (now.keys() != keys || now.mouse() != mouse) {
            human = new HumanMotion(new Random(), keys, mouse);
        }
    }

    private PageOperations.Pointer pointerOf(String contextId) {
        return pointers.computeIfAbsent(contextId, id -> new PageOperations.Pointer());
    }

    @Override
    public Map<String, String> navigateAll(Map<String, String> urlByContext) throws BrowserException {
        synchronized (instance) {
            check();
            return operations.navigateAll(this::page, urlByContext, human, this::pointerOf);
        }
    }

    @Override
    public RawPage read(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            return operations.read(page(contextId));
        }
    }

    @Override
    public List<RawLink> links(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            return operations.links(page(contextId));
        }
    }

    @Override
    public List<RawControl> controls(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            return operations.controls(page(contextId));
        }
    }

    @Override
    public RawActOutcome act(String contextId, String ref, BrowserAction action, String value)
            throws BrowserException {
        synchronized (instance) {
            check();
            return operations.act(page(contextId), ref, action, value, human, pointerOf(contextId));
        }
    }

    @Override
    public String search(String contextId, String query, List<String> engines) throws BrowserException {
        synchronized (instance) {
            check();
            Page page = page(contextId);
            // The search of the research browser (engine catalog, SERP extraction, consent, challenge) on this tab.
            // Which addresses the chat may open stays with the chat tools' access guard; the search engines are
            // the host's configured ones.
            Playwright4jDriver driver = Playwright4jDriver.onChatTab(context, page,
                    (int) Math.min(Integer.MAX_VALUE, navigationTimeoutMillis), instance.searchSettings.consent,
                    instance.searchSettings.captcha, instance.camoufox);
            driver.setRenderedCapture(new RenderedPageDocumentCapture(instance.searchSettings.analysis));
            PlaywrightBrowserSession session = new PlaywrightBrowserSession(driver,
                    com.aresstack.askai.browser.UrlSafetyPolicy.allowingPrivateNetworks(),
                    com.aresstack.askai.browser.BrowserLimits.defaults(), instance.testSearchUrlTemplate, null,
                    instance.searchSettings);
            if (instance.testSearchUrlTemplate != null) {
                session.setDomainKeyResolver(new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver());
            }
            try {
                return BrowserToolSet.renderSearch(session.search(query, engines), session.challengeStatus());
            } catch (com.aresstack.askai.browser.BrowserException e) {
                throw new BrowserException(BrowserException.Kind.FAILED, "Websuche fehlgeschlagen: " + e.getMessage(),
                        e);
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        }
    }

    @Override
    public RawScreenshot screenshot(String contextId, Map<String, String> marks, String clipRef, int maxBytes)
            throws BrowserException {
        synchronized (instance) {
            check();
            return operations.screenshot(page(contextId), marks, clipRef, maxBytes);
        }
    }

    @Override
    public RawDownload fetch(String contextId, String url, int maxBytes) throws BrowserException {
        synchronized (instance) {
            check();
            return operations.fetch(page(contextId), url, maxBytes);
        }
    }

    @Override
    public BrowserProbe probe(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            return operations.probe(page(contextId));
        }
    }

    @Override
    public String documentToken(String contextId) throws BrowserException {
        synchronized (instance) {
            check();
            return operations.documentToken(page(contextId));
        }
    }

    @Override
    public void disconnect() {
        // The browser keeps running; only this connection ends.
        disconnected = true;
    }

    private String title(String contextId) {
        try {
            return page(contextId).title();
        } catch (BrowserException | RuntimeException e) {
            // A tab that is still loading or shows an error page has no readable title; the list stays usable.
            return "";
        }
    }

    private String idOf(Page page) {
        String id = ids.get(page);
        if (id == null) {
            id = "tab-" + (++nextId);
            ids.put(page, id);
            pages.put(id, page);
        }
        return id;
    }

    private Page page(String contextId) throws BrowserException {
        Page page = pages.get(contextId);
        if (page == null || page.isClosed()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Unbekannter Tab: " + contextId);
        }
        return page;
    }

    private void check() throws BrowserException {
        if (!instance.isAlive()) {
            throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Der Browser wurde geschlossen");
        }
        if (disconnected) {
            throw new BrowserException(BrowserException.Kind.CONNECTION_LOST, "Verbindung zum Browser beendet");
        }
    }

    /** Process gone beats protocol errors. */
    private BrowserException classify(PlaywrightException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\n")[0];
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("target page, context or browser has been closed") || lower.contains("browser has been closed")
                || lower.contains("target closed") && context.pages().isEmpty()) {
            instance.markClosed();
        }
        if (!instance.isAlive()) {
            return new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Der Browser wurde geschlossen", e);
        }
        if (lower.contains("timeout")) {
            return new BrowserException(BrowserException.Kind.TIMEOUT, "Der Browser hat nicht rechtzeitig geantwortet",
                    e);
        }
        if (lower.contains("invalid url") || lower.contains("cannot navigate")) {
            return new BrowserException(BrowserException.Kind.INVALID_REQUEST, message, e);
        }
        return new BrowserException(BrowserException.Kind.FAILED, message, e);
    }
}
