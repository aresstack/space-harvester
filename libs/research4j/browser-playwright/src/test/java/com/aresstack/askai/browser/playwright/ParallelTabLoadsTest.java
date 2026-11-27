package com.aresstack.askai.browser.playwright;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Review of #28 (second round): the polling that loads the engines' start pages and their result pages side by side
 * ({@link Playwright4jDriver#awaitLoads}) and the pause that keeps Playwright's message loop running
 * ({@link Playwright4jDriver#pauseOn}), with stand-in pages instead of a browser, so the timing is exact.
 */
public class ParallelTabLoadsTest {

    /** A stand-in tab: commits after {@code commitAfterMillis}, reaches DOMContentLoaded and load at once then. */
    private static final class Tab {
        final String name;
        final long commitAfterMillis;
        boolean closed;
        boolean closeOnWait;
        long started = System.currentTimeMillis();
        int navigations;
        final List<String> calls = new ArrayList<String>();

        Tab(String name, long commitAfterMillis) {
            this.name = name;
            this.commitAfterMillis = commitAfterMillis;
        }

        boolean committed() {
            return System.currentTimeMillis() - started >= commitAfterMillis;
        }

        Page page() {
            return (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[]{Page.class},
                    (proxy, method, args) -> {
                        calls.add(method.getName());
                        switch (method.getName()) {
                            case "isClosed":
                                return closed;
                            case "url":
                                return "http://" + name + ".test/";
                            case "waitForLoadState":
                                if (closed) {
                                    throw new PlaywrightException("Target page, context or browser has been closed");
                                }
                                if (!committed()) {
                                    Thread.sleep(15);
                                    throw new TimeoutError("Timeout 15ms exceeded.");
                                }
                                return null;
                            case "waitForTimeout":
                                if (closed || closeOnWait) {
                                    closed = true;
                                    throw new PlaywrightException("Target page, context or browser has been closed");
                                }
                                Thread.sleep(((Number) args[0]).longValue());
                                return null;
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            case "toString":
                                return name;
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    private final Map<Page, Tab> tabs = new IdentityHashMap<Page, Tab>();

    private Page page(Tab tab) {
        Page page = tab.page();
        tabs.put(page, tab);
        return page;
    }

    /** Committed navigations as the protocol events would count them: one once the tab has committed. */
    private int navigations(Page page) {
        Tab tab = tabs.get(page);
        return tab.committed() ? tab.navigations + 1 : tab.navigations;
    }

    @Test
    public void aHangingTabHoldsBackNoOtherTab() {
        Page hanging = page(new Tab("hanging", Long.MAX_VALUE));
        Page fast = page(new Tab("fast", 0));
        Page late = page(new Tab("late", 300));
        Playwright4jDriver.PendingLoad first = new Playwright4jDriver.PendingLoad(hanging, 0);
        Playwright4jDriver.PendingLoad second = new Playwright4jDriver.PendingLoad(fast, 0);
        Playwright4jDriver.PendingLoad third = new Playwright4jDriver.PendingLoad(late, 0);
        final Map<String, Long> loadedAt = new java.util.HashMap<String, Long>();
        long started = System.currentTimeMillis();
        long deadline = started + 1_500;

        Playwright4jDriver.awaitLoads(Arrays.asList(first, second, third), deadline, deadline,
                page -> {
                    int n = navigations(page);
                    if (n > 0 && !loadedAt.containsKey(page.toString())) {
                        loadedAt.put(page.toString(), System.currentTimeMillis() - started);
                    }
                    return n;
                }, "no commit", "not loaded", (pending, millis) -> Playwright4jDriver.pauseOn(pending,
                        millis.longValue()));
        long took = System.currentTimeMillis() - started;

        assertEquals("no commit", first.failure);
        assertTrue(second.loaded);
        assertTrue(third.loaded);
        assertTrue("the fast tab committed at once, not after the hanging one: " + loadedAt,
                loadedAt.get("fast") < 200);
        assertTrue("the late tab was seen as soon as it committed: " + loadedAt, loadedAt.get("late") < 600);
        assertTrue("one shared deadline, " + took + " ms", took < 1_500 + 300);
    }

    @Test
    public void aClosedTabFailsAtOnceInsteadOfBeingPolledUntilTheDeadline() {
        Tab gone = new Tab("gone", Long.MAX_VALUE);
        gone.closed = true;
        Playwright4jDriver.PendingLoad closed = new Playwright4jDriver.PendingLoad(page(gone), 0);
        Playwright4jDriver.PendingLoad fast = new Playwright4jDriver.PendingLoad(page(new Tab("fast", 0)), 0);
        long started = System.currentTimeMillis();
        long deadline = started + 10_000;

        Playwright4jDriver.awaitLoads(Arrays.asList(closed, fast), deadline, deadline, this::navigations,
                "no commit", "not loaded", (pending, millis) -> Playwright4jDriver.pauseOn(pending,
                        millis.longValue()));
        long took = System.currentTimeMillis() - started;

        assertEquals("the tab was closed", closed.failure);
        assertTrue(fast.loaded);
        assertTrue("ends right away, not at the deadline: " + took + " ms", took < 1_000);
    }

    @Test
    public void aTabThatClosesWhileUncommittedEndsOnlyItsOwnLoad() {
        Tab closing = new Tab("closing", Long.MAX_VALUE);
        closing.closeOnWait = true; // closes during the first pause the loop spends on it
        Playwright4jDriver.PendingLoad doomed = new Playwright4jDriver.PendingLoad(page(closing), 0);
        Playwright4jDriver.PendingLoad late = new Playwright4jDriver.PendingLoad(page(new Tab("late", 200)), 0);
        long started = System.currentTimeMillis();
        long deadline = started + 10_000;

        Playwright4jDriver.awaitLoads(Arrays.asList(doomed, late), deadline, deadline, this::navigations,
                "no commit", "not loaded", (pending, millis) -> Playwright4jDriver.pauseOn(pending,
                        millis.longValue()));
        long took = System.currentTimeMillis() - started;

        assertEquals("the tab was closed", doomed.failure);
        assertTrue(late.loaded);
        assertTrue("no polling until the deadline: " + took + " ms", took < 1_500);
    }

    @Test
    public void aClockTabThatClosesDuringThePauseHandsTheRestToTheNextOpenPage() {
        Tab first = new Tab("first", 0);
        first.closeOnWait = true;
        Tab second = new Tab("second", 0);
        long started = System.currentTimeMillis();

        Playwright4jDriver.pauseOn(Arrays.asList(page(first), page(second)), 200);
        long took = System.currentTimeMillis() - started;

        assertTrue("the first clock closed", first.closed);
        assertTrue("the second page took over the wait", second.calls.contains("waitForTimeout"));
        assertTrue("the whole pause was kept: " + took + " ms", took >= 180 && took < 1_000);
    }

    @Test
    public void withoutAnyOpenPageThePauseStillPasses() {
        Tab gone = new Tab("gone", 0);
        gone.closed = true;
        long started = System.currentTimeMillis();

        Playwright4jDriver.pauseOn(Arrays.asList(page(gone), null), 100);

        long took = System.currentTimeMillis() - started;
        assertFalse(gone.calls.contains("waitForTimeout"));
        assertTrue(took + " ms", took >= 90);
        assertNull(null);
    }
}
