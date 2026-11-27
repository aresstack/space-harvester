package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The core proof of the seam: with an injected {@link SearchStrategy} that yields three start URLs, the
 * research loop opens exactly those, explores, captures and accepts sources — WITHOUT ever calling
 * {@code web_search_prepare}. After initial URL discovery the loop cannot tell whether the URLs came from
 * Brave, Bright Data, DataForSEO or a browser SERP; the continuation is identical.
 */
public class ResearchLoopProviderIndependenceTest {

    /** A browser fake that serves navigation but FAILS if asked to run a SERP — proving independence. */
    private static final class NavOnlyBrowser implements ToolInvoker {
        final Map<String, String> pages = new LinkedHashMap<String, String>();
        final List<String> opened = new ArrayList<String>();
        boolean serpWasCalled;
        int captureSeq;

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if ("web_search_prepare".equals(tool) || "web_search".equals(tool)
                    || "web_search_apply_layout".equals(tool)) {
                serpWasCalled = true;
                throw new ToolFailure("the browser SERP path must not be used with an API strategy");
            }
            if ("web_open".equals(tool)) {
                String url = String.valueOf(args.get("url"));
                String text = pages.get(url);
                if (text == null) {
                    throw new ToolFailure("404 " + url);
                }
                opened.add(url);
                return "URL: " + url + " title=\"Page " + url + "\" capture_id=cap-" + (++captureSeq)
                        + "\n" + text;
            }
            if ("web_links".equals(tool)) {
                return ""; // no onward links — the three start URLs are the whole run
            }
            if ("web_challenge_status".equals(tool)) {
                return "NONE";
            }
            throw new ToolFailure("unknown tool " + tool);
        }
    }

    /** A research fake that accepts each capture as a fresh source and records findings. */
    private static final class AcceptingResearch implements ToolInvoker {
        int seq;
        final List<String> findings = new ArrayList<String>();

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if ("source_accept".equals(tool)) {
                return "status=ACCEPTED source_id=source-" + (++seq) + " title=\"t\" passage_count=1 "
                        + "duplicate=false";
            }
            if ("finding_add".equals(tool)) {
                findings.add(String.valueOf(args.get("text")));
                return "appended revision=1";
            }
            throw new ToolFailure("unknown tool " + tool);
        }
    }

    /** The provider-independent strategy under test: three direct target URLs, no transit, no challenges. */
    private static final class FakeSearchStrategy implements SearchStrategy {
        final List<String> urls;

        FakeSearchStrategy(List<String> urls) {
            this.urls = urls;
        }

        public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                          SearchBudgetGate budget) {
            budget.beforeToolCall(); // a strategy is expected to consult the budget once
            List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
            for (int i = 0; i < urls.size(); i++) {
                candidates.add(new SearchResultCandidate("api#" + i, "", urls.get(i), urls.get(i),
                        "title " + i, "wearables snippet " + i, "", i + 1, "", "", 1.0, 1.0,
                        Collections.<SearchResultSiteLink>emptyList()));
            }
            return new InitialSearchResult(candidates, Collections.<String>emptyList(),
                    Collections.<SearchChallengeState>emptyList(),
                    Collections.singletonList("fake provider returned " + candidates.size()));
        }
    }

    @Test
    public void injectedStrategyDrivesTheUnchangedContinuationWithoutAnySerp() {
        NavOnlyBrowser browser = new NavOnlyBrowser();
        browser.pages.put("https://a.example/x", "wearables evidence from site a");
        browser.pages.put("https://b.example/y", "wearables evidence from site b");
        browser.pages.put("https://c.example/z", "wearables evidence from site c");
        AcceptingResearch research = new AcceptingResearch();

        ResearchLoop loop = new ResearchLoop(browser, research, ResearchRunBudget.defaults(),
                new ResearchLoopClock() {
                    public long currentTimeMillis() {
                        return 1000L;
                    }

                    public void sleepMillis(long millis) {
                    }
                }, new SilentListener(), new AtomicBoolean(false));
        loop.setSearchStrategy(new FakeSearchStrategy(java.util.Arrays.asList(
                "https://a.example/x", "https://b.example/y", "https://c.example/z")));

        ResearchStopReason reason = loop.run("wearables fitness tracker");

        assertFalse("the browser SERP path was never used", browser.serpWasCalled);
        assertEquals("all three provider URLs were opened", 3, browser.opened.size());
        assertTrue(browser.opened.contains("https://a.example/x"));
        assertTrue(browser.opened.contains("https://b.example/y"));
        assertTrue(browser.opened.contains("https://c.example/z"));
        assertEquals(3, loop.getProgress().getPagesVisited());
        assertEquals(3, loop.getProgress().getDistinctHosts().size());
        assertTrue("issue #32: no findings are recorded anymore", research.findings.isEmpty());
        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
    }

    private static final class SilentListener implements ResearchLoopListener {
        public void status(String message) {
        }

        public void progress(ResearchRunProgress progress, ResearchRunActivity activity) {
        }

        public void phaseReady(ResearchStopReason reason) {
        }

        public void attention(String reason, String domainFamily, String url, boolean resolved) {
        }
    }
}
