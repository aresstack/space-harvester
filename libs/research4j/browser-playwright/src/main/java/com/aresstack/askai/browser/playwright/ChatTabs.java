package com.aresstack.askai.browser.playwright;

import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.engine.RawTab;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tabs of the chat in one browser context, carried over from the Enterprise AI Client: every page of the context
 * is a tab with an id local to this browser ({@code tab-1}, {@code tab-2}, …), and new tabs open in the same context,
 * so a sign-in holds in all of them. Each tab keeps its last pointer position for real input. Not thread-safe: the
 * caller owns the browser's thread (lock or owner thread). Playwright failures pass through for the caller to classify.
 */
final class ChatTabs {

    private final BrowserContext context;
    private final Map<Page, String> ids = new IdentityHashMap<>();
    private final Map<String, Page> pages = new HashMap<>();
    private final Map<String, PageOperations.Pointer> pointers = new HashMap<>();
    private int nextId;

    ChatTabs(BrowserContext context) {
        this.context = context;
    }

    /** All open tabs of the context with address and title. */
    List<RawTab> list() {
        List<RawTab> tabs = new ArrayList<>();
        for (Page page : context.pages()) {
            tabs.add(new RawTab(idOf(page), page.url(), title(page)));
        }
        return tabs;
    }

    /** Opens an empty tab in the chat's context and answers its id. */
    String create() {
        return idOf(context.newPage());
    }

    /** Brings the tab to the front. */
    void activate(String contextId) throws BrowserException {
        page(contextId).bringToFront();
    }

    /** Closes the tab and forgets it. */
    void close(String contextId) throws BrowserException {
        Page page = page(contextId);
        page.close();
        pages.remove(contextId);
        ids.remove(page);
        pointers.remove(contextId);
    }

    /** The page of an open tab; an unknown or closed tab is an invalid request. */
    Page page(String contextId) throws BrowserException {
        Page page = pages.get(contextId);
        if (page == null || page.isClosed()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Unbekannter Tab: " + contextId);
        }
        return page;
    }

    /** The pointer of the tab, start of its next mouse path. */
    PageOperations.Pointer pointer(String contextId) {
        return pointers.computeIfAbsent(contextId, id -> new PageOperations.Pointer());
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

    private static String title(Page page) {
        try {
            return page.title();
        } catch (RuntimeException e) {
            // A tab that is still loading or shows an error page has no readable title; the list stays usable.
            return "";
        }
    }
}
