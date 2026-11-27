package com.aresstack.askai.browser.playwright;

import com.microsoft.playwright.PlaywrightException;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The decisions of the page operations that need no browser. */
public class PageOperationsTest {

    @Test
    public void aLoadIsSettledOnlyWithANewCompleteAndQuietDocument() {
        assertTrue(PageOperations.isLoadSettled(true, true, ChatPageScripts.QUIET_MILLIS));
        assertFalse(PageOperations.isLoadSettled(false, true, ChatPageScripts.QUIET_MILLIS));
        assertFalse(PageOperations.isLoadSettled(true, false, ChatPageScripts.QUIET_MILLIS));
        assertFalse(PageOperations.isLoadSettled(true, true, ChatPageScripts.QUIET_MILLIS - 1));
    }

    @Test
    public void aRedirectIsSettledAfterAReplacementBackOnTheOrigin() {
        long quiet = ChatPageScripts.QUIET_MILLIS;
        assertTrue(PageOperations.isRedirectSettled(true, 1, true, quiet, true));
        assertTrue(PageOperations.isRedirectSettled(false, 2, true, quiet, true));
        assertFalse(PageOperations.isRedirectSettled(false, 1, true, quiet, true));
        assertFalse(PageOperations.isRedirectSettled(true, 1, true, quiet, false));
        assertFalse(PageOperations.isRedirectSettled(true, 1, false, quiet, true));
    }

    @Test
    public void theOriginIsReportedLikeTheBrowserDoes() {
        assertEquals("https://example.org", PageOperations.originOf("https://Example.org:443/a?b"));
        assertEquals("http://example.org:8080", PageOperations.originOf("http://example.org:8080/"));
        assertNull(PageOperations.originOf("about:blank"));
        assertNull(PageOperations.originOf("not a url"));
    }

    @Test
    public void onlyReplacedLoadsCountAsRedirects() {
        assertTrue(PageOperations.isAbortedByRedirect(new PlaywrightException("NS_BINDING_ABORTED")));
        assertTrue(PageOperations.isAbortedByRedirect(new PlaywrightException("net::ERR_ABORTED at x")));
        assertTrue(PageOperations.isAbortedByRedirect(
                new PlaywrightException("Navigation interrupted by another navigation")));
        assertFalse(PageOperations.isAbortedByRedirect(new PlaywrightException("net::ERR_NAME_NOT_RESOLVED")));
    }

    @Test
    public void thePointerStartsOnceAndFollowsTheClicks() {
        HumanMotion human = new HumanMotion(new Random(1), false, true);
        PageOperations.Pointer pointer = new PageOperations.Pointer();
        double[] start = pointer.at(human);
        assertSame(start, pointer.at(human));
        pointer.moveTo(10, 20);
        assertArrayEquals(new double[]{10, 20}, pointer.at(human), 0.0);
    }
}
