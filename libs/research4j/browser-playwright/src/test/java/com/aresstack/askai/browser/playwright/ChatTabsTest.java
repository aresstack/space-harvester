package com.aresstack.askai.browser.playwright;

import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.engine.RawTab;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/** The tab bookkeeping of the chat browser without a browser. */
public class ChatTabsTest {

    private final List<Page> open = new ArrayList<>();

    @Test
    public void tabsKeepTheirIdsAndNewTabsOpenInTheSameContext() throws BrowserException {
        open.add(page("https://example.org/start", "Start"));
        ChatTabs tabs = new ChatTabs(context());
        List<RawTab> first = tabs.list();
        assertEquals(1, first.size());
        assertEquals("tab-1", first.get(0).contextId());
        assertEquals("Start", first.get(0).title());

        String created = tabs.create();
        assertEquals("tab-2", created);
        assertEquals(2, tabs.list().size());
        assertEquals("tab-1", tabs.list().get(0).contextId());
        assertSame(open.get(1), tabs.page(created));
    }

    @Test
    public void aClosedTabIsForgottenWithItsPointer() throws BrowserException {
        open.add(page("about:blank", ""));
        ChatTabs tabs = new ChatTabs(context());
        String id = tabs.list().get(0).contextId();
        PageOperations.Pointer pointer = tabs.pointer(id);
        assertSame(pointer, tabs.pointer(id));
        tabs.close(id);
        try {
            tabs.page(id);
            fail("closed tab still known");
        } catch (BrowserException e) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, e.kind());
        }
        assertNotSame(pointer, tabs.pointer(id));
    }

    @Test
    public void anUnknownTabIsAnInvalidRequest() {
        try {
            new ChatTabs(context()).page("tab-9");
            fail("unknown tab accepted");
        } catch (BrowserException e) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, e.kind());
        }
    }

    private BrowserContext context() {
        return (BrowserContext) Proxy.newProxyInstance(BrowserContext.class.getClassLoader(),
                new Class<?>[]{BrowserContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "pages":
                            return new ArrayList<>(open);
                        case "newPage":
                            Page page = page("about:blank", "");
                            open.add(page);
                            return page;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private Page page(String url, String title) {
        AtomicBoolean closed = new AtomicBoolean();
        return (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[]{Page.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "url":
                            return url;
                        case "title":
                            return title;
                        case "isClosed":
                            return closed.get();
                        case "close":
                            closed.set(true);
                            open.remove(proxy);
                            return null;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
