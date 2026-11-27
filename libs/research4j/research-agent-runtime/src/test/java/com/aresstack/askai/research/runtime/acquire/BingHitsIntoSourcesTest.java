package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.analysis.RenderedPageSource;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools;
import com.aresstack.askai.research.runtime.loop.McpLayoutRepairClient;
import com.aresstack.askai.research.runtime.loop.ResearchLoopClock;
import com.aresstack.askai.research.runtime.loop.ResearchLoopListener;
import com.aresstack.askai.research.runtime.loop.ResearchRunActivity;
import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.rerank.Bm25CandidateReranker;
import com.aresstack.askai.research.runtime.search.LegacyBrowserSearchStrategy;
import com.google.gson.Gson;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #22, followed past the extraction: the two real Bing pages of "Java 21 LTS release" (ten
 * organic results behind AI answer cards, recorded by {@code CamoufoxBingSearchLiveTest}) go through
 * the PRODUCTIVE chain of a user search — {@code web_search_prepare} ({@link SearchLayoutRepairTools}
 * with the default settings), the runtime's {@link McpLayoutRepairClient} and
 * {@link LegacyBrowserSearchStrategy}, the default BM25 reranker, the frontier, the page visits and
 * the source acceptance with the user search's fixed target of eight sources. Only the browser is
 * scripted: the SERP is the captured page, every result page answers with readable text.
 * <p>
 * {@code BingAnswerCardRegressionTest} pins the extraction (stage 2). This test pins what happens to
 * those ten hits afterwards: every one of them reaches the reranker and is parked, nothing else is
 * opened (no answer card, no Bing redirect, no duplicate), and the sources the run accepts are Bing's
 * organic results, each once.
 */
public class BingHitsIntoSourcesTest {

    private static final Path CAPTURES = Paths.get("..", "browser-playwright", "src", "test",
            "resources", "serp", "bing-java21-2026-10-31");
    private static final String QUERY = "Java 21 LTS release";

    /** Bing's organic results of both captures, in Bing's order. */
    private static final List<String> ORGANIC = Arrays.asList(
            "https://www.oracle.com/java/technologies/javase/21all-relnotes.html",
            "https://versionlog.com/java/21/",
            "https://www.oracle.com/java/technologies/java-se-support-roadmap.html",
            "https://adoptium.net/temurin/releases/?version=21",
            "https://ops.java/releases/",
            "https://en.wikipedia.org/wiki/Java_version_history",
            "https://openjdk.org/projects/jdk/21/",
            "https://bell-sw.com/blog/liberica-jdk-21-lts-release-a-lasting-foundation-for-your-java-application/",
            "https://jdk.java.net/21/",
            "https://www.innoq.com/de/podcast/135-java-21/");

    @Test
    public void runOneFollowsEveryBingHitIntoTheSources() throws Exception {
        assertBingHitsBecomeSources("answer-card-run-1.capture.json");
    }

    @Test
    public void runTwoFollowsEveryBingHitIntoTheSources() throws Exception {
        assertBingHitsBecomeSources("answer-card-run-2.capture.json");
    }

    private void assertBingHitsBecomeSources(String capture) throws Exception {
        RenderedPageDocument serp = new Gson().fromJson(
                new String(Files.readAllBytes(CAPTURES.resolve(capture)), StandardCharsets.UTF_8),
                RenderedPageDocument.class);
        ScriptedBrowser browser = new ScriptedBrowser(new SearchLayoutRepairTools(
                LegacyBrowserSearchDefaults.create(), new CapturedBing(serp), () -> 0L));
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        ResearchRunBudget budget = ResearchRunBudget.defaults();
        WebSearchApplicationService service = new WebSearchApplicationService(browser, budget,
                new ResearchRunProgress(), new SteppingClock(), new RecordingListener(status),
                new AtomicBoolean(false),
                new LegacyBrowserSearchStrategy(new McpLayoutRepairClient(browser, null), () -> 0L),
                null, new Bm25CandidateReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(
                            WebSearchApplicationService.AcceptedSource source,
                            WebSearchApplicationService.ToolBudget toolBudget) {
                        acceptance.acceptedUrls.add(source.getPageUrl());
                        return null;
                    }
                },
                false, 2, 200);
        // As the user search is wired: done exactly at the configured number of accepted sources.
        service.setCompletionPolicy(new FixedAcceptedSourceCountPolicy(budget.getMaxAcceptedSources()));

        ResearchStopReason reason = service.execute(QUERY);

        assertEquals(capture + ": all ten organic hits reach the reranker and are parked "
                        + status, new HashSet<String>(ORGANIC),
                new HashSet<String>(acceptance.parked));
        assertEquals(capture + ": each hit is parked once", ORGANIC.size(), acceptance.parked.size());
        assertEquals(capture + ": pages open in the reranker's order, nothing else is opened",
                acceptance.parked.subList(0, browser.opened.size()), browser.opened);
        assertEquals(capture + ": the run ends at the target, not on a budget " + status,
                ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
        assertEquals(capture + ": eight sources, the user search's target",
                budget.getMaxAcceptedSources(), acceptance.acceptedUrls.size());
        assertEquals(capture + ": every source is a different organic Bing result",
                acceptance.acceptedUrls.size(), new HashSet<String>(acceptance.acceptedUrls).size());
        assertTrue(capture + ": only organic Bing results become sources " + acceptance.acceptedUrls,
                ORGANIC.containsAll(acceptance.acceptedUrls));
        assertEquals(capture + ": the sources are the first pages opened",
                browser.opened.subList(0, acceptance.acceptedUrls.size()), acceptance.acceptedUrls);
    }

    /**
     * Plan T4: with a page worker as judge, every relevant page goes to it with its text; a page it calls not useful
     * never becomes a source, the run takes the next hit instead.
     */
    @Test
    public void aPageWorkerDecidesWhichOpenedPagesBecomeSources() throws Exception {
        RenderedPageDocument serp = new Gson().fromJson(
                new String(Files.readAllBytes(CAPTURES.resolve("answer-card-run-1.capture.json")),
                        StandardCharsets.UTF_8), RenderedPageDocument.class);
        ScriptedBrowser browser = new ScriptedBrowser(new SearchLayoutRepairTools(
                LegacyBrowserSearchDefaults.create(), new CapturedBing(serp), () -> 0L));
        RecordingAcceptance acceptance = new RecordingAcceptance();
        ResearchRunBudget budget = ResearchRunBudget.defaults();
        WebSearchApplicationService service = new WebSearchApplicationService(browser, budget,
                new ResearchRunProgress(), new SteppingClock(), new RecordingListener(new ArrayList<String>()),
                new AtomicBoolean(false),
                new LegacyBrowserSearchStrategy(new McpLayoutRepairClient(browser, null), () -> 0L),
                null, new Bm25CandidateReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(
                            WebSearchApplicationService.AcceptedSource source,
                            WebSearchApplicationService.ToolBudget toolBudget) {
                        acceptance.acceptedUrls.add(source.getPageUrl());
                        return null;
                    }
                },
                false, 2, 200);
        service.setCompletionPolicy(new FixedAcceptedSourceCountPolicy(3));
        final List<String> judged = new ArrayList<String>();
        service.setPageJudge(new PageJudge() {
            public Verdict judge(Page page) {
                judged.add(page.getUrl());
                assertEquals(QUERY, page.getQuery());
                assertTrue(page.getText(), page.getText().startsWith("Java 21 is a long-term support"));
                return new Verdict(judged.size() % 2 == 0, "every second page", Collections.<Integer>emptyList());
            }
        });

        ResearchStopReason reason = service.execute(QUERY);

        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
        assertEquals(Arrays.asList(judged.get(1), judged.get(3), judged.get(5)), acceptance.acceptedUrls);
        assertEquals(6, judged.size()); // the run stops at its target: no page beyond is judged
    }

    /**
     * Live run 4: a drinking-water PDF and vendor pages, all judged not useful, still fed their own terms into the
     * plan. Only a page the worker calls useful may suggest a search; the others' suggestions are reported dropped.
     */
    @Test
    public void onlyAUsefulPageSuggestsASearch() throws Exception {
        RenderedPageDocument serp = new Gson().fromJson(
                new String(Files.readAllBytes(CAPTURES.resolve("answer-card-run-1.capture.json")),
                        StandardCharsets.UTF_8), RenderedPageDocument.class);
        ScriptedBrowser browser = new ScriptedBrowser(new SearchLayoutRepairTools(
                LegacyBrowserSearchDefaults.create(), new CapturedBing(serp), () -> 0L));
        final RecordingAcceptance acceptance = new RecordingAcceptance();
        WebSearchApplicationService service = new WebSearchApplicationService(browser, ResearchRunBudget.defaults(),
                new ResearchRunProgress(), new SteppingClock(), new RecordingListener(new ArrayList<String>()),
                new AtomicBoolean(false),
                new LegacyBrowserSearchStrategy(new McpLayoutRepairClient(browser, null), () -> 0L),
                null, new Bm25CandidateReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(
                            WebSearchApplicationService.AcceptedSource source,
                            WebSearchApplicationService.ToolBudget toolBudget) {
                        acceptance.acceptedUrls.add(source.getPageUrl());
                        return null;
                    }
                },
                false, 2, 200);
        service.setCompletionPolicy(new FixedAcceptedSourceCountPolicy(2));
        final List<String> judged = new ArrayList<String>();
        service.setPageJudge(new PageJudge() {
            public Verdict judge(Page page) {
                judged.add(page.getUrl());
                boolean useful = judged.size() % 2 == 0;
                return new Verdict(useful, "every second page", Collections.<Integer>emptyList(),
                        Collections.singletonList((useful ? "useful " : "useless ") + judged.size()));
            }
        });
        final List<String> passed = new ArrayList<String>();
        final List<String> dropped = new ArrayList<String>();
        service.setSuggestionSink(new WebSearchApplicationService.SuggestionSink() {
            public void suggested(String query, String pageUrl, String rejectedReason) {
                if (rejectedReason == null) {
                    passed.add(query);
                } else {
                    dropped.add(query + " (" + rejectedReason + ")");
                }
            }
        });

        service.execute(QUERY);

        assertEquals(4, judged.size());
        assertEquals(Arrays.asList("useful 2", "useful 4"), passed);
        assertEquals(Arrays.asList("useless 1 (the page was not useful)", "useless 3 (the page was not useful)"),
                dropped);
    }

    /**
     * Plan T4, the research team: ONE search, its hits read side by side in three tabs. Every hit is opened once,
     * the tabs really overlap, every lane's worker judges the pages of its own tab, and the workers' new queries
     * reach the plan.
     */
    @Test
    public void theHitsOfOneSearchAreReadSideBySideInSeveralTabs() throws Exception {
        RenderedPageDocument serp = new Gson().fromJson(
                new String(Files.readAllBytes(CAPTURES.resolve("answer-card-run-1.capture.json")),
                        StandardCharsets.UTF_8), RenderedPageDocument.class);
        SearchLayoutRepairTools tools = new SearchLayoutRepairTools(
                LegacyBrowserSearchDefaults.create(), new CapturedBing(serp), () -> 0L);
        ScriptedBrowser searchTab = new ScriptedBrowser(tools);
        final java.util.concurrent.atomic.AtomicInteger reading = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger mostAtOnce = new java.util.concurrent.atomic.AtomicInteger();
        final List<ScriptedBrowser> tabs = new ArrayList<ScriptedBrowser>();
        final List<WebSearchApplicationService.Lane> lanes = new ArrayList<WebSearchApplicationService.Lane>();
        final List<String> judgedByLane = java.util.Collections.synchronizedList(new ArrayList<String>());
        for (int i = 0; i < 3; i++) {
            final ScriptedBrowser tab = new ScriptedBrowser(tools);
            final int laneNumber = i;
            tabs.add(tab);
            lanes.add(new WebSearchApplicationService.Lane(new ToolInvoker() {
                public String call(String tool, Map<String, Object> args) {
                    if ("web_read".equals(tool)) {
                        int now = reading.incrementAndGet();
                        mostAtOnce.accumulateAndGet(now, Math::max);
                        try {
                            Thread.sleep(30); // a page takes a while to read: the other tabs go on meanwhile
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        reading.decrementAndGet();
                    }
                    return tab.call(tool, args);
                }
            }, new PageJudge() {
                public Verdict judge(Page page) {
                    judgedByLane.add(laneNumber + " " + page.getUrl());
                    return new Verdict(true, "fits", Collections.<Integer>emptyList(),
                            Collections.singletonList("Java 21 virtual threads " + page.getTitle()));
                }
            }));
        }
        RecordingAcceptance acceptance = new RecordingAcceptance();
        ResearchRunBudget budget = ResearchRunBudget.defaults();
        WebSearchApplicationService service = new WebSearchApplicationService(searchTab, budget,
                new ResearchRunProgress(), new SteppingClock(), new RecordingListener(new ArrayList<String>()),
                new AtomicBoolean(false),
                new LegacyBrowserSearchStrategy(new McpLayoutRepairClient(searchTab, null), () -> 0L),
                null, new Bm25CandidateReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(
                            WebSearchApplicationService.AcceptedSource source,
                            WebSearchApplicationService.ToolBudget toolBudget) {
                        synchronized (acceptance) {
                            acceptance.acceptedUrls.add(source.getPageUrl());
                        }
                        return null;
                    }
                },
                false, 2, 200);
        service.setCompletionPolicy(new FixedAcceptedSourceCountPolicy(ORGANIC.size()));
        service.setLanes(lanes);
        final List<Integer> open = java.util.Collections.synchronizedList(new ArrayList<Integer>());
        service.setOpenPagesListener(new WebSearchApplicationService.OpenPagesListener() {
            public void openPages(int count) {
                open.add(count);
            }
        });
        final List<String> suggested = java.util.Collections.synchronizedList(new ArrayList<String>());
        service.setSuggestionSink(new WebSearchApplicationService.SuggestionSink() {
            public void suggested(String query, String pageUrl, String rejectedReason) {
                if (rejectedReason == null) {
                    suggested.add(query);
                }
            }
        });

        service.execute(QUERY);

        List<String> opened = new ArrayList<String>();
        int lanesUsed = 0;
        for (ScriptedBrowser tab : tabs) {
            opened.addAll(tab.opened);
            lanesUsed += tab.opened.isEmpty() ? 0 : 1;
        }
        assertTrue("the search itself ran in the search tab only", searchTab.opened.isEmpty());
        assertEquals("every hit opened exactly once over all tabs", new HashSet<String>(opened).size(), opened.size());
        assertTrue(ORGANIC.containsAll(opened));
        assertEquals(3, lanesUsed);
        assertTrue("the tabs read at the same time: " + mostAtOnce.get(), mostAtOnce.get() >= 2);
        assertTrue("every page a tab finished reading was judged: " + judgedByLane,
                judgedByLane.size() >= budget.getMaxAcceptedSources());
        assertEquals("each judged page's suggestion reached the plan", judgedByLane.size(), suggested.size());
        assertEquals("the run ends at the user search's target", budget.getMaxAcceptedSources(),
                acceptance.acceptedUrls.size());
        assertEquals("the last report: nothing open", Integer.valueOf(0), open.get(open.size() - 1));
    }

    /** The engine navigation of {@code web_search_prepare}, replaced by the captured Bing page. */
    private static final class CapturedBing implements RenderedPageSource {
        private final RenderedPageDocument serp;

        CapturedBing(RenderedPageDocument serp) {
            this.serp = serp;
        }

        public EngineCapture capture(String query, PageEvaluator evaluator) {
            evaluator.judge(serp, "www.bing.com");
            return new EngineCapture(
                    Collections.singletonList(new Captured(serp, "www.bing.com")),
                    Collections.singletonList("www.bing.com"),
                    Collections.<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>emptyList(),
                    Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList());
        }
    }

    /**
     * The browser lane: {@code web_search_prepare} is the productive tool on the captured page; every
     * result page is readable and answers in the bridge's {@code capture_id} format.
     */
    private static final class ScriptedBrowser implements ToolInvoker {
        private final SearchLayoutRepairTools tools;
        final List<String> opened = new ArrayList<String>();
        private String current = "";

        ScriptedBrowser(SearchLayoutRepairTools tools) {
            this.tools = tools;
        }

        public String call(String tool, Map<String, Object> args) {
            if ("web_search_prepare".equals(tool)) {
                return tools.prepare(String.valueOf(args.get("query")));
            }
            if ("web_probe".equals(tool) || "web_open".equals(tool)) {
                current = String.valueOf(args.get("url"));
                opened.add(current);
            }
            if ("web_probe".equals(tool) || "web_reprobe".equals(tool)) {
                return "url: " + current + "\ntitle: " + titleOf(current) + "\ntext_length: 2400"
                        + "\nchallenge: false\nconsent: false\nexcerpt: " + textOf(current);
            }
            if ("web_read".equals(tool) || "web_open".equals(tool)) {
                return "URL: " + current + " title=\"" + titleOf(current) + "\" capture_id=cap-"
                        + opened.size() + "\n" + textOf(current);
            }
            return ""; // no consent banner, no links, no challenge
        }

        private static String titleOf(String url) {
            return "Java 21 page " + (ORGANIC.indexOf(url) + 1);
        }

        private static String textOf(String url) {
            return "Java 21 is a long-term support (LTS) release. " + url;
        }
    }

    /** The research team's lanes accept side by side: every call is synchronized, like the productive store. */
    private static final class RecordingAcceptance implements SourceAcceptancePort {
        final List<String> parked = new ArrayList<String>();
        final List<String> acceptedUrls = new ArrayList<String>();
        private final Map<String, String> sourceByCapture = new LinkedHashMap<String, String>();

        public synchronized String accept(String captureId) {
            String sourceId = sourceByCapture.get(captureId);
            if (sourceId == null) {
                sourceId = "source-" + (sourceByCapture.size() + 1);
                sourceByCapture.put(captureId, sourceId);
            }
            return "accepted source_id=" + sourceId;
        }

        public synchronized void park(String url, String title, String excerpt, double score) {
            parked.add(url);
        }
    }

    /** Read and advanced by every lane of the research team. */
    private static final class SteppingClock implements ResearchLoopClock {
        private long now;

        public synchronized long currentTimeMillis() {
            return now;
        }

        public synchronized void sleepMillis(long millis) {
            now += Math.max(1L, millis);
        }
    }

    /**
     * The research team's lanes report side by side, like the productive listeners (stderr, the session's wire). A
     * plain list lost entries or threw inside a lane, and the lane then dropped that page's suggestion or source.
     */
    private static final class RecordingListener implements ResearchLoopListener {
        private final List<String> status;

        RecordingListener(List<String> status) {
            this.status = status;
        }

        public void status(String message) {
            synchronized (status) {
                status.add(message);
            }
        }

        public void progress(ResearchRunProgress p, ResearchRunActivity activity) {
        }

        public void phaseReady(ResearchStopReason reason) {
        }

        public void attention(String reason, String family, String url, boolean resolved) {
        }
    }
}
