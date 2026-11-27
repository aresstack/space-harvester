package com.aresstack.askai.research.runtime;

import com.aresstack.askai.browser.playwright.PlaywrightBrowserRuntime;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.UrlResolutionStatus;
import com.aresstack.askai.research.runtime.acquire.WebAcquisitionText;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.search.GoogleSearchBrowserStrategy;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * askai #48 live check: the research runtime's shared search browser runs in THIS JVM, a real Google search
 * goes through Serp4J ({@code serp4j-playwright-google}) on it, and a second search reuses the same browser.
 * Opt-in only ({@code -Daskai.live.google=camoufox}); it never runs in CI.
 *
 * <p>askai #41 (lazy resolution): Google's {@code /goto} hits come back unchanged and UNRESOLVED, and only opening one
 * in the research browser resolves it, through the browser's own navigation.</p>
 */
public class InProcessGoogleSearchLiveTest {

    private static final SearchBudgetGate ALLOW = new SearchBudgetGate() {
        public boolean beforeToolCall() {
            return true;
        }
    };

    @Test
    public void realGoogleSearchRunsOnTheSharedSearchBrowserInThisJvm() throws Exception {
        String channel = System.getProperty("askai.live.google", "");
        assumeTrue("SKIPPED (opt-in live test): -Daskai.live.google=<channel>", !channel.isEmpty());
        ResearchRuntimeShared shared = new ResearchRuntimeShared(channel);
        List<SearchResultCandidate> unresolved = new ArrayList<SearchResultCandidate>();
        try {
            SearchStrategy strategy = GoogleSearchBrowserStrategy.create(shared.searchBrowser(), Locale.GERMANY);
            for (String query : new String[]{"pf4j plugin framework", "playwright java"}) {
                InitialSearchResult result = strategy.search(new InitialSearchRequest(query, 10, "de", "de"),
                        CancellationSignal.NONE, ALLOW);
                System.out.println("[live] query=" + query + " status=" + result.status
                        + " candidates=" + result.candidates.size() + " diagnostics=" + result.diagnostics);
                for (SearchResultCandidate c : result.candidates) {
                    System.out.println("[live]   #" + c.originalRank + " " + c.urlResolution + " "
                            + c.navigationUrl() + " | " + c.title);
                    if (c.urlResolution == UrlResolutionStatus.UNRESOLVED) {
                        assertTrue("an unresolved hit keeps its provider URL", c.rawSearchHref.contains("/goto"));
                        assertFalse("never a verified target", c.hasVerifiedTarget());
                        unresolved.add(c);
                    }
                }
                assertFalse("real Google hits for " + query, result.candidates.isEmpty());
            }
            assertEquals("one search browser for both searches", 1, shared.searchBrowser().getLaunchCount());
        } finally {
            shared.close();
        }
        System.out.println("[live] unresolved /goto hits kept as candidates: " + unresolved.size());
        if (unresolved.isEmpty()) {
            return; // Google listed direct links this time: nothing to navigate
        }
        // Only now, when a hit is opened, is its /goto link resolved: by the research browser's normal navigation.
        PlaywrightBrowserRuntime browser = PlaywrightBrowserRuntime.start(
                new PlaywrightBrowserRuntime.Options(channel, true, false, null, null, false));
        try {
            String gotoUrl = unresolved.get(0).navigationUrl();
            String page = browser.call("web_open", Collections.<String, Object>singletonMap("url", gotoUrl));
            String landed = WebAcquisitionText.finalUrlOf(page);
            System.out.println("[live] navigated " + gotoUrl + " -> " + landed);
            assertTrue("the browser followed the redirect to the target", landed != null
                    && !WebAcquisitionText.hostOf(landed).contains("google."));
        } finally {
            browser.close();
        }
    }
}
