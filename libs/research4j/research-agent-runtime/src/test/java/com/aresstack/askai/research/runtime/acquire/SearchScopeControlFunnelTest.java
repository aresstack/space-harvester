package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.ResearchLoopClock;
import com.aresstack.askai.research.runtime.loop.ResearchLoopListener;
import com.aresstack.askai.research.runtime.loop.ResearchRunActivity;
import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.rerank.EngineOrderReranker;
import com.aresstack.askai.research.runtime.search.InitialSearchRequest;
import com.aresstack.askai.research.runtime.search.InitialSearchResult;
import com.aresstack.askai.research.runtime.search.SearchBudgetGate;
import com.aresstack.askai.research.runtime.search.SearchStrategy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SC1 funnel pins against the REAL WebSearchApplicationService: a CLEAR canonical OUT SERP hit
 * is removed BEFORE web_open and BEFORE source_park; a loaded OUT page is neither accepted nor
 * link-expanded; begin() failure is fail-closed BEFORE any engine call; INACTIVE is a true
 * no-op fast path (zero evaluate calls).
 */
public class SearchScopeControlFunnelTest {

    private static final class ScriptedScope implements SearchScopeControlPort {
        final List<String> lanes = new ArrayList<String>();
        final java.util.Map<String, java.util.Set<String>> outIdsByLane =
                new java.util.HashMap<String, java.util.Set<String>>();
        boolean inactive;
        boolean failBegin;
        boolean ended;
        boolean inAffinityOnly; // SC2a: outFilter=false, every KEEP reports nearIn
        boolean failEvaluate;
        com.aresstack.askai.research.domain.scope.TypedBlacklist typed =
                com.aresstack.askai.research.domain.scope.TypedBlacklist.empty();

        void out(String lane, String id) {
            java.util.Set<String> ids = outIdsByLane.get(lane);
            if (ids == null) {
                ids = new java.util.HashSet<String>();
                outIdsByLane.put(lane, ids);
            }
            ids.add(id);
        }

        public Session begin() throws ToolInvoker.ToolFailure {
            return beginUntyped().withBlacklist(typed);
        }

        private Session beginUntyped() throws ToolInvoker.ToolFailure {
            if (failBegin) {
                throw new ToolInvoker.ToolFailure("UNAVAILABLE no embedding model");
            }
            if (inactive) {
                return new Session(false, null, "INACTIVE outAnchors=0");
            }
            return inAffinityOnly
                    ? new Session(true, "ss-test", "handle=ss-test outAnchors=0 "
                            + "outFilter=false inAffinity=true", false, true)
                    : new Session(true, "ss-test", "handle=ss-test outAnchors=1");
        }

        public List<Decision> evaluate(String handle, String lane, List<Item> items)
                throws ToolInvoker.ToolFailure {
            if (failEvaluate) {
                throw new ToolInvoker.ToolFailure("UNAVAILABLE embedding broke");
            }
            lanes.add(lane);
            java.util.Set<String> outIds = outIdsByLane.get(lane);
            List<Decision> decisions = new ArrayList<Decision>();
            for (Item item : items) {
                boolean out = outIds != null && outIds.contains(item.id);
                decisions.add(new Decision(item.id, out, false, "GUI",
                        inAffinityOnly && !out, "Scheduling"));
            }
            return decisions;
        }

        public void end(String handle) {
            ended = true;
        }
    }

    private static final class RecordingBrowser implements ToolInvoker {
        final List<String> tools = new ArrayList<String>();
        final List<String> openedUrls = new ArrayList<String>();
        String pageReply = "";
        String linksReply = "";

        public String call(String tool, Map<String, Object> args) {
            tools.add(tool);
            if ("web_open".equals(tool)) {
                openedUrls.add(String.valueOf(args.get("url")));
                return pageReply;
            }
            if ("web_links".equals(tool)) {
                String reply = linksReply;
                linksReply = ""; // only the first page offers links
                return reply;
            }
            return "";
        }
    }

    private static final class RecordingAcceptance implements SourceAcceptancePort {
        final List<String> parked = new ArrayList<String>();
        final List<String> accepted = new ArrayList<String>();

        public String accept(String captureId) {
            accepted.add(captureId);
            return "accepted " + captureId;
        }

        public void park(String url, String title, String excerpt, double score) {
            parked.add(url);
        }
    }

    private static SearchResultCandidate candidate(String url, String title, String snippet) {
        return new SearchResultCandidate("c-" + url, "s1", url, url, title, snippet,
                "example.com", 1, "r1", "b1", 1.0, 1.0,
                Collections.<SearchResultSiteLink>emptyList());
    }

    private static SearchStrategy twoHitStrategy(final int[] calls) {
        return new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest request,
                                              CancellationSignal cancellation,
                                              SearchBudgetGate budget) {
                calls[0]++;
                return new InitialSearchResult(Arrays.asList(
                        candidate("https://kept.example/scheduling",
                                "FreeRTOS Scheduling Guide", "freertos scheduling"),
                        candidate("https://out.example/gui",
                                "Java Swing GUI Tutorial", "desktop windows toolkit"),
                        candidate("https://second.example/tasks",
                                "FreeRTOS Task Basics", "freertos tasks")),
                        new ArrayList<String>(),
                        Collections.<com.aresstack.askai.browser.search.repair
                                .SearchChallengeState>emptyList(),
                        new ArrayList<String>());
            }
        };
    }

    private static WebSearchApplicationService service(RecordingBrowser browser,
            SearchStrategy strategy, RecordingAcceptance acceptance, final List<String> status) {
        return new WebSearchApplicationService(browser, ResearchRunBudget.defaults(),
                new ResearchRunProgress(),
                new ResearchLoopClock() {
                    long now;

                    public long currentTimeMillis() {
                        return now;
                    }

                    public void sleepMillis(long millis) {
                        now += Math.max(1L, millis);
                    }
                },
                new ResearchLoopListener() {
                    public void status(String message) {
                        status.add(message);
                    }

                    public void progress(ResearchRunProgress p, ResearchRunActivity activity) {
                    }

                    public void phaseReady(ResearchStopReason reason) {
                    }

                    public void attention(String reason, String family, String url,
                                          boolean resolved) {
                    }
                },
                new AtomicBoolean(false), strategy, null, new EngineOrderReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(
                            WebSearchApplicationService.AcceptedSource source,
                            WebSearchApplicationService.ToolBudget budget) {
                        return null;
                    }
                },
                false, 0 /* readiness loop off: web_open verbatim */, 48);
    }

    @Test
    public void aClearOutSerpHitIsRemovedBeforeWebOpenAndBeforePark() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.out("serp", "https://out.example/gui");
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertTrue("the SERP lane was judged", scope.lanes.contains("serp"));
        assertEquals("only the kept hits are parked, in the RERANKER'S order",
                Arrays.asList("https://kept.example/scheduling",
                        "https://second.example/tasks"),
                acceptance.parked);
        assertEquals("the OUT hit is NEVER opened; survivor order stays the reranker's",
                Arrays.asList("https://kept.example/scheduling",
                        "https://second.example/tasks"),
                browser.openedUrls);
        assertTrue("the skip is observable", status.toString().contains(
                "search-scope skip candidate=\"Java Swing GUI Tutorial\" "
                        + "relation=LIKELY_OUT authority=CANONICAL_OUT nearest=\"GUI\""));
        assertTrue("the snapshot is released", scope.ended);
    }

    @Test
    public void aLoadedOutPageIsNeitherAcceptedNorLinkExpanded() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        // The page IS query-relevant (lexical fallback matches the terms) — only the SCOPE
        // rejects it: the last guard against wrong SERP snippets.
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"FreeRTOS Scheduling\"\nfreertos scheduling deep dive";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.out("serp", "https://out.example/gui");
        // BOTH surviving hits measure OUT once their content is loaded — the fixture's page
        // reply is shared, so both visits must be page-lane OUT for the pin to be exact.
        scope.out("page", "https://kept.example/scheduling");
        scope.out("page", "https://second.example/tasks");
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertTrue(scope.lanes.contains("page"));
        assertTrue("never accepted", acceptance.accepted.isEmpty());
        assertFalse("links never harvested", browser.tools.contains("web_links"));
        assertTrue(status.toString().contains(
                "search-scope page OUT url=https://kept.example/scheduling nearest=\"GUI\" "
                        + "-> not accepted, not expanded"));
    }

    @Test
    public void aFailedBeginEndsFailClosedBeforeAnyEngineCall() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.failBegin = true;
        service.setScopeControl(scope);

        ResearchStopReason reason = service.execute("freertos scheduling");

        assertEquals(ResearchStopReason.SCOPE_CONTROL_UNAVAILABLE, reason);
        assertEquals("no engine call after a fail-closed begin", 0, calls[0]);
        assertTrue(browser.openedUrls.isEmpty());
    }

    /**
     * SC2a: with an EMPTY blacklist the IN affinity still measures (evaluate calls happen,
     * nearIn appears in the diagnosis) but NOTHING is filtered — park/open/accept identical
     * to the baseline. Positive concept steering is not a by-product of a blacklist.
     */
    @Test
    public void inAffinityOnlyMeasuresWithoutFilteringAnything() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.inAffinityOnly = true;
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertTrue("the shadow DID measure", scope.lanes.contains("serp"));
        assertEquals("nothing filtered: ALL hits parked", 3, acceptance.parked.size());
        assertEquals("nothing filtered: ALL hits opened", 3, browser.openedUrls.size());
        assertTrue("the shadow count is observable",
                status.toString().contains("nearIn=3"));
    }

    /** SC2a failure split: a PURE IN-affinity failure degrades to baseline — never a stop. */
    @Test
    public void aPureInAffinityFailureDegradesToBaseline() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.inAffinityOnly = true;
        scope.failEvaluate = true;
        service.setScopeControl(scope);

        ResearchStopReason reason = service.execute("freertos scheduling");

        assertFalse("never a fail-closed stop without an OUT boundary",
                ResearchStopReason.SCOPE_CONTROL_UNAVAILABLE == reason);
        assertEquals("baseline behaviour after the degrade", 3, browser.openedUrls.size());
        assertTrue(status.toString().contains(
                "search-scope inAffinity degraded to baseline at serp"));
        assertTrue("the degrade releases the host snapshot — no handle lingers until "
                + "session teardown", scope.ended);
    }

    /** SC1 regression: with the OUT filter active an evaluate failure stays FAIL-CLOSED. */
    @Test
    public void anOutFilterEvaluateFailureStaysFailClosed() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.failEvaluate = true; // outFilter=true (default ACTIVE session)
        service.setScopeControl(scope);

        ResearchStopReason reason = service.execute("freertos scheduling");

        assertEquals(ResearchStopReason.SCOPE_CONTROL_UNAVAILABLE, reason);
        assertTrue("no page ever opened after the fail-closed stop",
                browser.openedUrls.isEmpty());
    }

    @Test
    public void inactiveIsATrueNoOpFastPath() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.inactive = true;
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertTrue("zero evaluate calls on the fast path", scope.lanes.isEmpty());
        assertEquals("baseline behaviour: ALL hits parked", 3, acceptance.parked.size());
        assertEquals("baseline behaviour: ALL hits opened", 3, browser.openedUrls.size());
    }

    // ---- PR 12: the typed blacklist, a deterministic filter beside the semantic gate ----

    private static com.aresstack.askai.research.domain.scope.TypedBlacklist typed(String... entries) {
        return com.aresstack.askai.research.domain.scope.TypedBlacklist.of(Arrays.asList(entries));
    }

    @Test
    public void aTypedTermRejectsTheSerpHitBeforeRerankWithoutTheSemanticGate() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.inactive = true; // no semantic gate at all
        scope.typed = typed("swing");
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertTrue("the semantic gate was never asked", scope.lanes.isEmpty());
        assertEquals(Arrays.asList("https://kept.example/scheduling", "https://second.example/tasks"),
                acceptance.parked);
        assertFalse("the blocked hit is never opened",
                browser.openedUrls.contains("https://out.example/gui"));
        assertTrue(status.toString().contains("search-blacklist serp skip candidate=\"Java Swing GUI Tutorial\""));
    }

    @Test
    public void aSearchResultPageOfAnotherSiteIsNeverVisitedNorParked() {
        // the live run of 2026-11-09 stored two dguv.de hit lists as sources
        final int[] calls = {0};
        SearchStrategy strategy = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                              SearchBudgetGate budget) {
                calls[0]++;
                return new InitialSearchResult(Arrays.asList(
                        candidate("https://kept.example/scheduling", "FreeRTOS Scheduling Guide",
                                "freertos scheduling"),
                        candidate("https://www.dguv.de/de/suche/index.jsp?query=freertos&page=3", "DGUV - Suche",
                                "freertos scheduling treffer"),
                        candidate("https://second.example/tasks", "FreeRTOS Task Basics", "freertos tasks")),
                        new ArrayList<String>(),
                        Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                        new ArrayList<String>());
            }
        };
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service = service(browser, strategy, acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.inactive = true; // no blacklist, no semantic gate: the URL shape alone decides
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertEquals(Arrays.asList("https://kept.example/scheduling", "https://second.example/tasks"),
                acceptance.parked);
        assertFalse(browser.openedUrls.toString(),
                browser.openedUrls.contains("https://www.dguv.de/de/suche/index.jsp?query=freertos&page=3"));
        assertTrue(status.toString(), status.toString().contains("entry=\""
                + WebSearchApplicationService.SEARCH_RESULT_PAGE + "\""));
    }

    @Test
    public void aTypedHostNameNeverReachesTheVisitStage() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, new ArrayList<String>());
        ScriptedScope scope = new ScriptedScope();
        scope.inactive = true;
        scope.typed = typed("www.Second.example");
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertFalse(browser.openedUrls.contains("https://second.example/tasks"));
        assertFalse(acceptance.parked.contains("https://second.example/tasks"));
        assertEquals(2, browser.openedUrls.size());
    }

    @Test
    public void aTypedTermRejectsTheLoadedPageAfterFetch() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        // the SERP said nothing about "lab"; only the loaded page title does
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"FreeRTOS Scheduling Lab\"\nfreertos scheduling deep dive";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.inactive = true;
        scope.typed = typed("Lab");
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertEquals("all three SERP hits pass the SERP lane", 3, acceptance.parked.size());
        assertTrue("never accepted", acceptance.accepted.isEmpty());
        assertFalse("links never harvested", browser.tools.contains("web_links"));
        assertTrue(status.toString().contains("search-blacklist page skip url=https://kept.example/scheduling"));
    }

    @Test
    public void aTypedTermRejectsALinkBeforeTheFrontier() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"FreeRTOS Scheduling\"\nfreertos scheduling deep dive";
        browser.linksReply = "l1: FreeRTOS scheduling with Swing — https://links.example/swing-scheduling\n"
                + "l2: FreeRTOS scheduling internals — https://links.example/internals\n";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.inactive = true;
        scope.typed = typed("swing");
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertTrue("links were read", browser.tools.contains("web_links"));
        assertFalse("the blocked link is never opened",
                browser.openedUrls.contains("https://links.example/swing-scheduling"));
        assertTrue(status.toString().contains("search-blacklist links items="));
    }

    @Test
    public void withoutScopeControlTheRunSaysItIsUnfiltered() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), new RecordingAcceptance(), status);

        service.execute("freertos scheduling");

        assertTrue(status.toString().contains("search-blacklist NONE (no scope control) — this search runs unfiltered"));
    }

    @Test
    public void anExcludedFacetWithoutATypedTermIsLeftToTheSemanticGate() {
        int[] calls = {0};
        RecordingBrowser browser = new RecordingBrowser();
        browser.pageReply = "capture_id=c1 final_url=https://kept.example/scheduling "
                + "title=\"irrelevant page\"\nnothing about the query here";
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        WebSearchApplicationService service =
                service(browser, twoHitStrategy(calls), acceptance, status);
        ScriptedScope scope = new ScriptedScope();
        scope.out("serp", "https://out.example/gui"); // the facet "GUI" lives only in the gate
        service.setScopeControl(scope);

        service.execute("freertos scheduling");

        assertFalse(status.toString().contains("search-blacklist serp skip"));
        assertTrue(status.toString().contains("search-scope skip candidate=\"Java Swing GUI Tutorial\""));
    }
}
