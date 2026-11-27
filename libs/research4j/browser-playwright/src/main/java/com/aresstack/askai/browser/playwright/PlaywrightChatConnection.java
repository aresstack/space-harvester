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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The engine connection of the chat browser on Playwright: the Firefox adapter of the Enterprise AI Client
 * ({@code BidiBrowserConnection}) carried over to Playwright. The page operations (page scripts, waiting rules,
 * real input) are in {@link PageOperations} and the tabs in {@link ChatTabs}; this connection adds the owner thread and
 * the failure contract. Playwright is not thread-safe: every call runs on the owner thread of the browser
 * ({@link BrowserSessionActor}), the same thread model as the research browser.
 */
final class PlaywrightChatConnection implements BrowserConnection {

    private final PlaywrightChatEngine.Instance instance;
    private final BrowserContext context;
    private final long navigationTimeoutMillis;
    /** The chat's tabs in the context, with their ids and pointer positions. */
    private final ChatTabs tabs;
    private volatile boolean disconnected;
    /** Human variation of input, scrolling and reading; off unless the chat switched it on. */
    private volatile HumanMotion human = HumanMotion.off();
    /** The page operations, shared with the research driver; this connection adds tab ids and the lock. */
    private final PageOperations operations;

    PlaywrightChatConnection(PlaywrightChatEngine.Instance instance, BrowserContext context,
                             long navigationTimeoutMillis) {
        this.instance = instance;
        this.context = context;
        this.navigationTimeoutMillis = navigationTimeoutMillis;
        this.tabs = new ChatTabs(context);
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
        return onOwner(session -> {
            check();
            try {
                return tabs.list();
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        });
    }

    @Override
    public String createTab() throws BrowserException {
        return onOwner(session -> {
            check();
            try {
                return tabs.create();
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        });
    }

    @Override
    public void activate(String contextId) throws BrowserException {
        onOwner(session -> {
            check();
            try {
                tabs.activate(contextId);
                session.useChatTab(tabs.page(contextId));
            } catch (PlaywrightException e) {
                throw classify(e);
            }
            return null;
        });
    }

    @Override
    public void close(String contextId) throws BrowserException {
        onOwner(session -> {
            check();
            try {
                tabs.close(contextId);
            } catch (PlaywrightException e) {
                throw classify(e);
            }
            return null;
        });
    }

    @Override
    public void navigate(String contextId, String url) throws BrowserException {
        onOwner(session -> {
            check();
            operations.navigate(tab(session, contextId), url, human, tabs.pointer(contextId), documents(session));
            return null;
        });
    }

    @Override
    public void setHumanEmulation(boolean keys, boolean mouse) {
        HumanMotion now = human;
        if (now.keys() != keys || now.mouse() != mouse) {
            human = new HumanMotion(new Random(), keys, mouse);
        }
    }

    @Override
    public Map<String, String> navigateAll(Map<String, String> urlByContext) throws BrowserException {
        return onOwner(session -> {
            check();
            session.chatChangedPages();
            return operations.navigateAll(tabs::page, urlByContext, human, tabs::pointer);
        });
    }

    @Override
    public RawPage read(String contextId) throws BrowserException {
        return onOwner(session -> {
            check();
            Page page = tab(session, contextId);
            try {
                // A PDF the tab reached is read as the research reads it (web_open), not as the viewer's page.
                com.aresstack.askai.browser.BrowserPageSnapshot document = session.chatDocument();
                if (document != null) {
                    return new RawPage(document.getUrl(), document.getTitle(), document.getText());
                }
            } catch (com.aresstack.askai.browser.BrowserException e) {
                throw unreadableDocument(e);
            }
            return operations.read(page);
        });
    }

    @Override
    public List<RawLink> links(String contextId) throws BrowserException {
        return onOwner(session -> {
            check();
            return operations.links(tab(session, contextId));
        });
    }

    @Override
    public List<RawControl> controls(String contextId) throws BrowserException {
        return onOwner(session -> {
            check();
            return operations.controls(tab(session, contextId));
        });
    }

    @Override
    public RawActOutcome act(String contextId, String ref, BrowserAction action, String value)
            throws BrowserException {
        return onOwner(session -> {
            check();
            return operations.act(tab(session, contextId), ref, action, value, human, tabs.pointer(contextId));
        });
    }

    @Override
    public String search(String contextId, String query, List<String> engines) throws BrowserException {
        return onOwner(session -> {
            check();
            // The search of the research browser (engine catalog, SERP extraction, consent, challenge) on this tab,
            // in the research session itself: the tab is its current page. Which addresses the chat may open stays
            // with the chat tools' access guard; the search engines are the host's configured ones.
            tab(session, contextId);
            try {
                // The session lives on between searches: a challenge the user solved or closed since releases its
                // engine before this search selects engines.
                session.challengeStatus();
                return BrowserToolSet.renderSearch(session.search(query, engines), session.challengeStatus());
            } catch (com.aresstack.askai.browser.BrowserException e) {
                throw new BrowserException(BrowserException.Kind.FAILED, "Websuche fehlgeschlagen: " + e.getMessage(),
                        e);
            } catch (PlaywrightException e) {
                throw classify(e);
            }
        });
    }

    @Override
    public RawScreenshot screenshot(String contextId, Map<String, String> marks, String clipRef, int maxBytes)
            throws BrowserException {
        return onOwner(session -> {
            check();
            return operations.screenshot(tab(session, contextId), marks, clipRef, maxBytes);
        });
    }

    @Override
    public RawDownload fetch(String contextId, String url, int maxBytes) throws BrowserException {
        return onOwner(session -> {
            check();
            return operations.fetch(tab(session, contextId), url, maxBytes);
        });
    }

    @Override
    public BrowserProbe probe(String contextId) throws BrowserException {
        return onOwner(session -> {
            check();
            return operations.probe(tab(session, contextId));
        });
    }

    @Override
    public String documentToken(String contextId) throws BrowserException {
        return onOwner(session -> {
            check();
            return operations.documentToken(tab(session, contextId));
        });
    }

    @Override
    public void disconnect() {
        // The browser keeps running; only this connection ends.
        disconnected = true;
    }

    /** A call of the chat that runs on the browser's owner thread. */
    private interface OwnerCall<T> {
        T run(PlaywrightBrowserSession session) throws BrowserException;
    }

    /** Carries the chat contract's failure through the owner thread's actor. */
    private static final class Failure extends RuntimeException {
        final BrowserException failure;

        Failure(BrowserException failure) {
            super(failure.getMessage(), failure, false, false);
            this.failure = failure;
        }
    }

    /**
     * Runs {@code call} on the owner thread of the browser and waits for it; the event pump of the browser goes on
     * between calls. Interrupting the waiting thread (the chat's Stop) interrupts the running call, so its waiting
     * ends at once.
     */
    private <T> T onOwner(OwnerCall<T> call) throws BrowserException {
        try {
            return instance.actor.onPlaywrightSession(session -> {
                try {
                    return call.run(session);
                } catch (BrowserException e) {
                    throw new Failure(e);
                }
            });
        } catch (Failure e) {
            throw e.failure;
        } catch (com.aresstack.askai.browser.BrowserException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new BrowserException(BrowserException.Kind.FAILED, "Warten unterbrochen", e);
            }
            check();
            throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Der Browser wurde geschlossen", e);
        }
    }

    /**
     * The page of the chat's tab {@code contextId}, which also becomes the research session's current page: chat
     * and research share one current page.
     */
    private Page tab(PlaywrightBrowserSession session, String contextId) throws BrowserException {
        Page page = tabs.page(contextId);
        session.useChatTab(page);
        return page;
    }

    /** PDFs and downloads the chat's loads reach, handed to the research session that reads them. */
    private PageOperations.Documents documents(PlaywrightBrowserSession session) {
        return new PageOperations.Documents() {
            @Override
            public boolean downloadStarted(Page page, String url, PlaywrightException e) throws BrowserException {
                if (!Playwright4jDriver.startedDownload(e)) {
                    return false;
                }
                try {
                    return session.chatReachedDocument(url, null);
                } catch (com.aresstack.askai.browser.BrowserException failed) {
                    throw unreadableDocument(failed);
                }
            }

            @Override
            public boolean shown(Page page, com.microsoft.playwright.Response response) throws BrowserException {
                if (response == null) {
                    return false;
                }
                try {
                    return session.chatReachedDocument(response.url(), response);
                } catch (com.aresstack.askai.browser.BrowserException failed) {
                    throw unreadableDocument(failed);
                }
            }
        };
    }

    private static BrowserException unreadableDocument(com.aresstack.askai.browser.BrowserException e) {
        return new BrowserException(BrowserException.Kind.FAILED,
                "Dokument konnte nicht gelesen werden: " + e.getMessage(), e);
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
