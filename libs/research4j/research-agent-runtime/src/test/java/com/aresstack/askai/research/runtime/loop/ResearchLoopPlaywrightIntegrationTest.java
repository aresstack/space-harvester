package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordination;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordinator;
import com.aresstack.askai.browser.playwright.PlaywrightBrowserRuntime;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.ResearchSearchIndex;
import com.aresstack.askai.research.capture.SourceAcceptanceService;
import com.aresstack.askai.research.capture.VisitedCapture;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 36C: the UNCHANGED {@link ResearchLoop} with the UNCHANGED {@link ContributionToolInvoker} runs against the LIVE
 * in-process Playwright browser ({@code PlaywrightBrowserRuntime}: playwright4j → GraalJS driver JVM → a real
 * locally installed browser). Two local HTTP servers (two distinct hosts by port) serve pages whose text and
 * links exist ONLY after JavaScript execution, so a static fetch cannot produce this run. Between loop and
 * browser sits the browser BRIDGE endpoint that adds the {@code capture_id} convention by recording
 * every {@code web_open} into the PRODUCTIVE Commit-37 {@link CaptureStore}; the research endpoint applies
 * the PRODUCTIVE {@link SourceAcceptanceService} (repository + index, real dedup, real result contract).
 * This bridge is exactly the host-side glue Commit 38 wires into the plugin; the loop and the invoker
 * contain no Playwright special case whatsoever. No fallback: if the browser does not report READY, the
 * test SKIPS with the browser runtime's own readiness line — it never swaps in STATIC_HTTP.
 */
public class ResearchLoopPlaywrightIntegrationTest {

    private static final String PAGE_TEMPLATE = "<!doctype html><html><head><title>%TITLE%</title></head>"
            + "<body><div id='c'></div><script>"
            + "document.getElementById('c').textContent='%TEXT% Rendered by JavaScript.';"
            + "%LINKS%"
            + "</script></body></html>";

    @Test
    public void unchangedLoopReachesSufficientEvidenceThroughTheLiveBrowser() throws Exception {

        // ---- an ENGINE server + two content servers (host:port families via --domain-key-mode) ----
        HttpServer engineServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer serverOne = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer serverTwo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseEngine = "http://127.0.0.1:" + engineServer.getAddress().getPort();
        String baseOne = "http://127.0.0.1:" + serverOne.getAddress().getPort();
        String baseTwo = "http://127.0.0.1:" + serverTwo.getAddress().getPort();

        // The SERP has engine-internal navigation (never a candidate) plus a REPEATED result list
        // of three similar blocks (title link + explanatory snippet) — the A3 mechanical analysis
        // only accepts structurally valid SERPs, never single naked anchors.
        engineServer.createContext("/find", serpPage(baseEngine + "/videos?q=pf4j",
                new String[][]{
                        {"PF4J primer", baseOne + "/a"},
                        {"Independent pf4j review", baseTwo + "/c"},
                        {"pf4j in production", baseTwo + "/e"}}));
        serverOne.createContext("/a", page("PF4J primer",
                "pf4j is a plugin framework. Primary source.",
                jsLink("pf4j details", baseTwo + "/c")));
        serverTwo.createContext("/c", page("Independent pf4j review",
                "pf4j works well with java 8.",
                jsLink("pf4j extra evidence", baseTwo + "/e")));
        serverTwo.createContext("/e", page("pf4j in production",
                "More pf4j evidence from the field.", ""));
        engineServer.start();
        serverOne.start();
        serverTwo.start();

        // ---- the LIVE Playwright browser, in-process (exactly as the plugin runs it) ----
        PlaywrightBrowserRuntime browserRuntime = null;
        ContributionToolInvoker browser = null;
        ContributionToolInvoker research = null;
        try {
            browserRuntime = startBrowserOrSkip(null, baseEngine + "/find?q={query}");

            // ---- PRODUCTIVE Commit-37 lifecycle behind the research endpoint ----
            final CaptureStore captures = new CaptureStore(100);
            final InMemoryResearchSourceRepository repository = InMemoryResearchSourceRepository.empty();
            final ResearchSearchIndex.InMemory index = new ResearchSearchIndex.InMemory();
            final SourceAcceptanceService acceptance = new SourceAcceptanceService(captures, repository,
                    new SourceAcceptanceService.SourceCreator() {
                        public void create(ResearchSourceRecord record) {
                            repository.put(record);
                        }
                    }, index);
            final List<String> findings = new ArrayList<String>();

            // ---- browser BRIDGE endpoint: browser delegation + capture_id convention (host-side glue) ----
            final PlaywrightBrowserRuntime toBrowser = browserRuntime;
            browser = new ContributionToolInvoker(Arrays.asList(
                    // A4: the loop drives the typed web_search_prepare; the bridge delegates it to the
                    // REAL browser's web_search_prepare (JSON serialized through the tool text contract).
                    McpToolContribution.of("web_search_prepare", "prepare", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return delegate(toBrowser, "web_search_prepare", call.getArguments());
                        }
                    }, McpToolParameter.string("query", true, "q")),
                    McpToolContribution.of("web_open", "open", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            McpToolResult raw = delegate(toBrowser, "web_open", call.getArguments());
                            if (raw.isError()) {
                                return raw;
                            }
                            String[] parsed = parseBrowserPage(raw.getText());
                            VisitedCapture capture = captures.record(parsed[0], parsed[1], parsed[2]);
                            return McpToolResult.ok("URL: " + parsed[0] + " title=\"" + parsed[1]
                                    + "\" capture_id=" + capture.getCaptureId() + "\n" + parsed[2]);
                        }
                    }, McpToolParameter.string("url", true, "u")),
                    McpToolContribution.of("web_links", "links", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return delegate(toBrowser, "web_links", call.getArguments());
                        }
                    })));

            // ---- research endpoint on the productive acceptance service ----
            research = new ContributionToolInvoker(Arrays.asList(
                    McpToolContribution.of("source_accept", "accept", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return McpToolResult.ok(
                                    acceptance.accept(call.getString("capture_id")).render());
                        }
                    }, McpToolParameter.string("capture_id", true, "c")),
                    McpToolContribution.of("finding_add", "finding", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            String sourceId = call.getString("source_id");
                            if (repository.get(sourceId) == null) {
                                return McpToolResult.error("Unknown source: " + sourceId);
                            }
                            findings.add(sourceId + ": " + call.getString("text"));
                            return McpToolResult.ok("appended revision=1");
                        }
                    }, McpToolParameter.string("source_id", true, "s"),
                       McpToolParameter.string("text", true, "t"))));

            // ---- the IDENTICAL loop + IDENTICAL invoker as 36A — no Playwright special case ----
            final AtomicLong now = new AtomicLong(0);
            final List<ResearchStopReason> phaseReady = new ArrayList<ResearchStopReason>();
            ResearchLoop loop = new ResearchLoop(browser, research,
                    new ResearchRunBudget(30, 20, 8, 3, 600_000, 2, 2),
                    new ResearchLoopClock() {
                        public long currentTimeMillis() {
                            return now.get();
                        }

                        public void sleepMillis(long millis) {
                            now.addAndGet(millis);
                        }
                    },
                    new ResearchLoopListener() {
                        public void status(String message) {
                        }

                        public void progress(ResearchRunProgress progress, ResearchRunActivity activity) {
                        }

                        public void phaseReady(ResearchStopReason reason) {
                            phaseReady.add(reason);
                        }

                        public void attention(String reason, String domainFamily, String url,
                                              boolean resolved) {
                        }
                    }, new AtomicBoolean(false));
            ResearchStopReason reason = loop.run("investigate pf4j plugin framework");

            assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
            assertTrue("three JS pages visited", loop.getProgress().getPagesVisited() >= 3);
            assertTrue("two sources accepted via the productive service",
                    loop.getProgress().getAcceptedSources() >= 2);
            assertEquals("two distinct hosts (two local servers)",
                    2, loop.getProgress().getDistinctHosts().size());
            assertTrue("issue #32: no findings are recorded anymore", findings.isEmpty());
            assertEquals(1, phaseReady.size());
            // The decisive Playwright proof: every capture text exists ONLY after JS execution.
            assertTrue(captures.size() >= 3);
            for (VisitedCapture capture : captures.list()) {
                assertTrue("capture text must be JS-rendered: " + capture.getCanonicalUrl(),
                        capture.getText().contains("Rendered by JavaScript"));
            }

            // A4 tool surface on the REAL browser runtime: the loop already drove web_search_prepare above;
            // web_search stays a working compatibility surface and the repair tools are discoverable.
            assertNotNull("web_search remains a working compatibility surface",
                    browserRuntime.call("web_search",
                            java.util.Collections.<String, Object>singletonMap("query", "pf4j")));
            assertTrue("web_search_discard_repair is registered on the browser runtime",
                    browserRuntime.call("web_search_discard_repair",
                            java.util.Collections.<String, Object>singletonMap("repairTicketId", "x"))
                            .startsWith("DISCARDED"));
            // A HIGH-confidence real SERP prepares organic candidates directly — zero inference.
            com.aresstack.askai.browser.search.repair.PreparedWebSearchResult prepared =
                    com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson.decodePrepared(
                            browserRuntime.call("web_search_prepare",
                                    java.util.Collections.<String, Object>singletonMap("query",
                                            "pf4j")));
            assertEquals("real high-confidence SERP → organic, no repair ticket",
                    com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus
                            .ORGANIC_RESULTS, prepared.status);
            assertTrue("real A3 candidates carry resolved target urls",
                    !prepared.candidates.isEmpty()
                            && prepared.candidates.get(0).resolvedTargetUrl.startsWith("http"));
        } finally {
            closeBrowser(browserRuntime);
            engineServer.stop(0);
            serverOne.stop(0);
            serverTwo.stop(0);
        }
    }

    /**
     * The A4 CORE proof, LIVE: a real browser renders a mechanically UNSURE SERP (forced LOW_CONFIDENCE
     * via a schema-v3 browser profile, without touching production defaults); the real in-process browser
     * returns a typed REPAIR_REQUIRED ticket; a scripted inference resolves the organic region;
     * the browser re-checks the full binding and applies the EXISTING A3 extraction to the SAME cached
     * live snapshot, returning real titles, snippets and DIRECT target urls. The target servers are
     * never opened by the repair itself.
     */
    @Test
    public void lowConfidenceRepairOnLiveSnapshotYieldsRealCandidates() throws Exception {

        HttpServer engineServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer serverOne = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer serverTwo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseEngine = "http://127.0.0.1:" + engineServer.getAddress().getPort();
        String baseOne = "http://127.0.0.1:" + serverOne.getAddress().getPort();
        String baseTwo = "http://127.0.0.1:" + serverTwo.getAddress().getPort();
        // Three real result blocks (title link + snippet) inside a valid result region; the same DOM
        // A3 accepts at default confidence, but the forced-high threshold makes the MECHANICS unsure.
        engineServer.createContext("/find", serpPage(baseEngine + "/videos?q=pf4j",
                new String[][]{
                        {"PF4J primer", baseOne + "/a"},
                        {"Independent pf4j review", baseTwo + "/c"},
                        {"pf4j in production", baseTwo + "/e"}}));
        java.util.concurrent.atomic.AtomicInteger hitA = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger hitC = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger hitE = new java.util.concurrent.atomic.AtomicInteger();
        serverOne.createContext("/a", counting(hitA, page("PF4J primer",
                "pf4j is a plugin framework.", "")));
        serverTwo.createContext("/c", counting(hitC, page("Independent pf4j review",
                "pf4j works well with java 8.", "")));
        serverTwo.createContext("/e", counting(hitE, page("pf4j in production",
                "More pf4j evidence.", "")));
        engineServer.start();
        serverOne.start();
        serverTwo.start();

        String configPath = writeLowConfidenceConfig();
        PlaywrightBrowserRuntime browserRuntime = null;
        try {
            browserRuntime = startBrowserOrSkip(configPath, baseEngine + "/find?q={query}");

            // ---- prepare on the REAL browser render → typed REPAIR_REQUIRED ----
            com.aresstack.askai.browser.search.repair.PreparedWebSearchResult prepared =
                    com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson.decodePrepared(
                            browserRuntime.call("web_search_prepare",
                                    java.util.Collections.<String, Object>singletonMap("query", "pf4j")));
            assertEquals("the mechanics are unsure on this snapshot",
                    com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus.REPAIR_REQUIRED,
                    prepared.status);
            assertEquals(1, prepared.repairRequests.size());
            com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest request =
                    prepared.repairRequests.get(0);
            assertEquals("no target page is opened before applying", 0, hitA.get() + hitC.get()
                    + hitE.get());

            // ---- Java-8 runtime: scripted inference built from the ACTUAL request (no fragile ids) ----
            final String organicId =
                    request.artifact.mechanicallyPreferredContainerIds.get(0);
            final java.util.concurrent.atomic.AtomicInteger inferenceCalls =
                    new java.util.concurrent.atomic.AtomicInteger();
            com.aresstack.askai.browser.search.inference.StructuredInferencePort port =
                    new com.aresstack.askai.browser.search.inference.StructuredInferencePort() {
                        public com.aresstack.askai.browser.search.inference.StructuredInferenceResult
                                execute(com.aresstack.askai.browser.search.inference
                                        .StructuredInferenceRequest req) {
                            inferenceCalls.incrementAndGet();
                            return com.aresstack.askai.browser.search.inference.StructuredInferenceResult
                                    .success("{\"analysisId\":\"" + request.artifact.analysisId
                                            + "\",\"snapshotId\":\"" + request.snapshotId + "\","
                                            + "\"organicResultContainerIds\":[\"" + organicId + "\"],"
                                            + "\"resultBlockContainerIds\":[],\"excludedContainerIds\":[],"
                                            + "\"confidence\":0.9,\"explanation\":\"live\"}");
                        }
                    };
            SearchLayoutRepairCoordinator coordinator = new SearchLayoutRepairCoordinator(
                    RepairBridgeFixtures.highConfidenceAiEnabled(), port,
                    com.aresstack.askai.browser.search.inference.InferenceBudgetGate.ALLOW_ALL,
                    com.aresstack.askai.browser.search.inference.RetryDelay.IMMEDIATE, null);
            SearchLayoutRepairCoordination coordination =
                    coordinator.coordinate(request, com.aresstack.askai.browser.search.inference
                            .CancellationSignal.NONE, 1000L);
            assertEquals(SearchLayoutRepairCoordination.Outcome.SUBMIT, coordination.outcome);
            assertEquals("exactly one inference call", 1, inferenceCalls.get());

            // ---- apply on the SAME cached live snapshot → real A3 candidates ----
            String submissionJson = com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson
                    .encodeSubmission(coordination.submission);
            com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult applied =
                    com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson.decodeRepairResult(
                            browserRuntime.call("web_search_apply_layout",
                                    java.util.Collections.<String, Object>singletonMap("submission",
                                            submissionJson)));
            assertEquals(com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus
                    .ORGANIC_RESULTS, applied.status);
            assertEquals("three live result blocks extracted", 3, applied.candidates.size());
            java.util.List<String> titles = new ArrayList<String>();
            java.util.List<String> urls = new ArrayList<String>();
            for (com.aresstack.askai.browser.search.SearchResultCandidate candidate
                    : applied.candidates) {
                titles.add(candidate.title);
                urls.add(candidate.resolvedTargetUrl);
                assertTrue("snippet is taken from the block: " + candidate.snippet,
                        candidate.snippet.contains("Explanatory snippet describing"));
                assertTrue("resolved url is a DIRECT target, never the engine wrapper",
                        candidate.resolvedTargetUrl.startsWith(baseOne)
                                || candidate.resolvedTargetUrl.startsWith(baseTwo));
            }
            assertTrue("titles come from the live-rendered DOM",
                    titles.contains("PF4J primer") && titles.contains("Independent pf4j review")
                            && titles.contains("pf4j in production"));
            assertTrue(urls.contains(baseOne + "/a") && urls.contains(baseTwo + "/c")
                    && urls.contains(baseTwo + "/e"));
            assertEquals("apply returns candidates only — it never opens target pages", 0,
                    hitA.get() + hitC.get() + hitE.get());

            // ---- a second apply of the SAME ticket is hard-rejected (one-shot) ----
            com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult again =
                    com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson.decodeRepairResult(
                            browserRuntime.call("web_search_apply_layout",
                                    java.util.Collections.<String, Object>singletonMap("submission",
                                            submissionJson)));
            assertEquals(com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus
                    .UNKNOWN_ATTEMPT, again.status);
        } finally {
            closeBrowser(browserRuntime);
            engineServer.stop(0);
            serverOne.stop(0);
            serverTwo.stop(0);
        }
    }

    /**
     * A5 productive proof, LIVE end to end: a real in-process Playwright browser renders a JS SERP with
     * three organic candidates (A, B, C); A3/A4 turns them into typed SearchResultCandidates; the REAL
     * local MiniLM cross-encoder reranks them and the Top-2 selection policy admits only two — so ONLY
     * those two target pages are ever opened (the rejected candidate stays at 0 hits), which raw engine
     * order could never achieve. A second run against an UNREACHABLE reranker opens nothing and ends
     * with the typed RERANKER_UNAVAILABLE stop reason — never NO_RELEVANT_PATHS.
     */
    @Test
    public void livePlaywrightCandidatesFlowThroughTheRealLocalReranker() throws Exception {
        com.aresstack.askai.research.runtime.rerank.LiveLocalRerankerRuntime reranker =
                com.aresstack.askai.research.runtime.rerank.LiveLocalRerankerRuntime.startOrNull();
        assumeTrue("SKIPPED: no live local reranker (Java-21 / staged jar / installed model)",
                reranker != null);

        HttpServer engineServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer serverOne = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer serverTwo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseEngine = "http://127.0.0.1:" + engineServer.getAddress().getPort();
        String baseOne = "http://127.0.0.1:" + serverOne.getAddress().getPort();
        String baseTwo = "http://127.0.0.1:" + serverTwo.getAddress().getPort();
        engineServer.createContext("/find", serpPage(baseEngine + "/videos?q=pf4j",
                new String[][]{
                        {"PF4J plugin framework primer", baseOne + "/a"},
                        {"Independent pf4j review for java", baseTwo + "/c"},
                        {"Tomato soup recipe with basil", baseTwo + "/e"}}));
        // No onward links: the frontier is exactly the reranked seed, so open-counts equal selections.
        java.util.concurrent.atomic.AtomicInteger hitA = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger hitC = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger hitE = new java.util.concurrent.atomic.AtomicInteger();
        serverOne.createContext("/a", counting(hitA, page("PF4J plugin framework primer",
                "pf4j is a plugin framework for java.", "")));
        serverTwo.createContext("/c", counting(hitC, page("Independent pf4j review for java",
                "pf4j works well with java 8 plugins.", "")));
        serverTwo.createContext("/e", counting(hitE, page("Tomato soup recipe with basil",
                "simmer tomatoes with basil and cream.", "")));
        engineServer.start();
        serverOne.start();
        serverTwo.start();

        PlaywrightBrowserRuntime browserRuntime = null;
        ContributionToolInvoker browser = null;
        ContributionToolInvoker research = null;
        try {
            browserRuntime = startBrowserOrSkip(null, baseEngine + "/find?q={query}");

            final CaptureStore captures = new CaptureStore(100);
            final InMemoryResearchSourceRepository repository = InMemoryResearchSourceRepository.empty();
            final ResearchSearchIndex.InMemory index = new ResearchSearchIndex.InMemory();
            final SourceAcceptanceService acceptance = new SourceAcceptanceService(captures, repository,
                    new SourceAcceptanceService.SourceCreator() {
                        public void create(ResearchSourceRecord record) {
                            repository.put(record);
                        }
                    }, index);
            final PlaywrightBrowserRuntime toBrowser = browserRuntime;
            browser = new ContributionToolInvoker(Arrays.asList(
                    McpToolContribution.of("web_search_prepare", "prepare", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return delegate(toBrowser, "web_search_prepare", call.getArguments());
                        }
                    }, McpToolParameter.string("query", true, "q")),
                    McpToolContribution.of("web_open", "open", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            McpToolResult raw = delegate(toBrowser, "web_open", call.getArguments());
                            if (raw.isError()) {
                                return raw;
                            }
                            String[] parsed = parseBrowserPage(raw.getText());
                            VisitedCapture capture = captures.record(parsed[0], parsed[1], parsed[2]);
                            return McpToolResult.ok("URL: " + parsed[0] + " title=\"" + parsed[1]
                                    + "\" capture_id=" + capture.getCaptureId() + "\n" + parsed[2]);
                        }
                    }, McpToolParameter.string("url", true, "u")),
                    McpToolContribution.of("web_links", "links", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return delegate(toBrowser, "web_links", call.getArguments());
                        }
                    })));
            research = new ContributionToolInvoker(Arrays.asList(
                    McpToolContribution.of("source_accept", "accept", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return McpToolResult.ok(
                                    acceptance.accept(call.getString("capture_id")).render());
                        }
                    }, McpToolParameter.string("capture_id", true, "c")),
                    McpToolContribution.of("finding_add", "finding", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return McpToolResult.ok("appended revision=1");
                        }
                    }, McpToolParameter.string("source_id", true, "s"),
                       McpToolParameter.string("text", true, "t"))));


            // ---- RUN 1: the REAL local reranker with a Top-2 policy ----
            com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor topTwo =
                    reranker.descriptor(2);
            ResearchLoop loop1 = rerankLoop(browser, research,
                    new com.aresstack.askai.research.runtime.rerank.SearchResultReranker(
                            new com.aresstack.askai.research.runtime.rerank.HttpRerankerClient(topTwo),
                            new com.aresstack.askai.research.runtime.rerank.SearchResultSelectionPolicy(
                                    topTwo.selectionConfiguration),
                            topTwo.modelName,
                            com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics.RAW_LOGIT));
            loop1.run("investigate pf4j plugin framework for java");

            int openedTargets = (hitA.get() > 0 ? 1 : 0) + (hitC.get() > 0 ? 1 : 0)
                    + (hitE.get() > 0 ? 1 : 0);
            assertEquals("the real reranker Top-2 opens exactly two of the three candidates "
                    + "(a=" + hitA.get() + " c=" + hitC.get() + " e=" + hitE.get() + ")",
                    2, openedTargets);
            int rejected = (hitA.get() == 0 ? 1 : 0) + (hitC.get() == 0 ? 1 : 0)
                    + (hitE.get() == 0 ? 1 : 0);
            assertEquals("exactly one candidate is rejected by reranking and never opened (0 hits)",
                    1, rejected);
            // The SEMANTIC identity of the survivors — not just "two of three": the real MiniLM
            // cross-encoder must keep both pf4j candidates and must reject the off-topic recipe.
            assertTrue("the PF4J primer is a semantic survivor", hitA.get() > 0);
            assertTrue("the independent PF4J review is a semantic survivor", hitC.get() > 0);
            assertEquals("the tomato soup recipe must never be opened", 0, hitE.get());

            // ---- RUN 2: an UNREACHABLE reranker → typed stop reason, opens nothing ----
            int deadPort = freePort();
            com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor dead =
                    new com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor(
                            com.aresstack.askai.agent.model.reranker.RerankerProvider.ASKAI_LOCAL,
                            "http://127.0.0.1:" + deadPort, reranker.modelName,
                            Arrays.asList(com.aresstack.askai.agent.model.reranker
                                    .RerankerCapability.RERANK),
                            com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics.RAW_LOGIT,
                            1_500L, com.aresstack.askai.agent.model.reranker
                            .RerankerSelectionConfiguration.topN(2));
            int beforeA = hitA.get();
            int beforeC = hitC.get();
            int beforeE = hitE.get();
            ResearchLoop loop2 = rerankLoop(browser, research,
                    new com.aresstack.askai.research.runtime.rerank.SearchResultReranker(
                            new com.aresstack.askai.research.runtime.rerank.HttpRerankerClient(dead),
                            new com.aresstack.askai.research.runtime.rerank.SearchResultSelectionPolicy(
                                    dead.selectionConfiguration)));
            ResearchStopReason reason = loop2.run("investigate pf4j plugin framework for java");

            assertTrue("an unreachable mandatory reranker is a typed RERANKER_* reason (was " + reason
                    + ")", reason == ResearchStopReason.RERANKER_UNAVAILABLE
                    || reason == ResearchStopReason.RERANKER_TIMEOUT);
            assertTrue("a reranker failure is never NO_RELEVANT_PATHS",
                    reason != ResearchStopReason.NO_RELEVANT_PATHS);
            // EVERY target page individually: zero additional hits during the failed run.
            assertEquals("page A gets no additional hit without a reranker", beforeA, hitA.get());
            assertEquals("page C gets no additional hit without a reranker", beforeC, hitC.get());
            assertEquals("page E gets no additional hit without a reranker", beforeE, hitE.get());
        } finally {
            closeBrowser(browserRuntime);
            reranker.close();
            engineServer.stop(0);
            serverOne.stop(0);
            serverTwo.stop(0);
        }
    }

    /** A standard live-harness loop with a generous budget and a no-op listener, for the given reranker. */
    private static ResearchLoop rerankLoop(ContributionToolInvoker browser, ContributionToolInvoker research,
            com.aresstack.askai.research.runtime.rerank.CandidateReranker reranker) {
        final AtomicLong now = new AtomicLong(0);
        ResearchLoop loop = new ResearchLoop(browser, research,
                new ResearchRunBudget(30, 20, 8, 3, 600_000, 2, 2),
                new ResearchLoopClock() {
                    public long currentTimeMillis() {
                        return now.get();
                    }

                    public void sleepMillis(long millis) {
                        now.addAndGet(millis);
                    }
                },
                new ResearchLoopListener() {
                    public void status(String message) {
                    }

                    public void progress(ResearchRunProgress progress, ResearchRunActivity activity) {
                    }

                    public void phaseReady(ResearchStopReason reason) {
                    }

                    public void attention(String reason, String domainFamily, String url,
                                          boolean resolved) {
                    }
                }, new AtomicBoolean(false));
        loop.setReranker(reranker);
        return loop;
    }

    private static HttpHandler counting(final java.util.concurrent.atomic.AtomicInteger hits,
                                        final HttpHandler delegate) {
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                hits.incrementAndGet();
                delegate.handle(exchange);
            }
        };
    }

    /** A schema-v3 browser profile that forces LOW_CONFIDENCE (raised structural threshold) — the
     *  production defaults are never touched. */
    private static String writeLowConfidenceConfig() throws IOException {
        com.aresstack.askai.browser.search.LegacyBrowserSearchSettings d =
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create();
        com.aresstack.askai.browser.search.SearchPageAnalysisSettings a = d.analysis;
        com.aresstack.askai.browser.search.SearchPageAnalysisSettings forced =
                new com.aresstack.askai.browser.search.SearchPageAnalysisSettings(a.noResultsTexts,
                        a.maximumCandidateContainers, a.minimumContainerTextCharacters,
                        a.minimumNonLinkTextCharacters, a.minimumRepeatedSiblingCount, 0.999,
                        a.maximumNavigationLinkDensity, a.internalLinkWeight, a.externalLinkWeight,
                        a.sameHostPenalty, a.sameRegistrableDomainPenalty, a.subdomainPenalty,
                        a.unknownDomainPenalty, a.repeatedBlockWeight, a.nonLinkTextWeight,
                        a.titleLinkWeight, a.snippetPresenceWeight, a.headingLinkWeight,
                        a.semanticMainWeight, a.navigationRolePenalty, a.resultBlockSimilarityThreshold,
                        a.minimumDiscriminatingSignalFamilies, a.fullPageAreaRatio,
                        a.textLengthSaturationCharacters, a.maximumContainerDomDepth,
                        a.maximumCapturedContainers, a.maximumLinksPerContainer,
                        a.maximumStructureSignatureDepth,
                        // This fixture exercises the AI REPAIR path: the link harvest (which would
                        // rescue the forced-low-confidence page first) is off here.
                        0, a.linkHarvestMaximumCandidates, a.linkHarvestExcludedDomains);
        com.aresstack.askai.browser.search.LegacyBrowserSearchSettings low =
                new com.aresstack.askai.browser.search.LegacyBrowserSearchSettings(d.navigation,
                        d.consent, d.captcha, d.readiness, forced, d.visualAnalysis, d.extraction,
                        d.aiLayoutResolver, d.reranker, d.diagnostics, d.layoutRepair);
        java.util.Map<String, String> values =
                com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec.toValues(low);
        String json = new com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument(
                com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument
                        .CURRENT_SCHEMA_VERSION, 1L,
                com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec.digest(low), values)
                .toJson();
        File file = File.createTempFile("askai-lowconf", ".json");
        java.nio.file.Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return file.getAbsolutePath();
    }

    // ------------------------------------------------------------------ helpers

    /** Delegate a tool call to the live in-process browser; its failures surface as readable tool errors. */
    private static McpToolResult delegate(PlaywrightBrowserRuntime browser, String tool,
                                          Map<String, Object> args) {
        try {
            return McpToolResult.ok(browser.call(tool, args));
        } catch (com.aresstack.askai.browser.BrowserException ex) {
            return McpToolResult.error(ex.getMessage());
        }
    }

    /**
     * Start the in-process browser for a local test world (loopback targets allowed, host:port domain
     * families, ONE explicit search engine), or SKIP readably with the browser's own readiness status.
     */
    private static PlaywrightBrowserRuntime startBrowserOrSkip(String browserConfigPath, String searchUrl)
            throws Exception {
        try {
            return PlaywrightBrowserRuntime.start(new PlaywrightBrowserRuntime.Options(
                    System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "camoufox"), true, true,
                    searchUrl, PlaywrightBrowserRuntime.loadBrowserConfig(browserConfigPath), true));
        } catch (PlaywrightBrowserRuntime.StartFailure notReady) {
            assumeTrue("SKIPPED (environment-gated): " + notReady.readiness(), false);
            throw notReady;
        }
    }

    private static void closeBrowser(PlaywrightBrowserRuntime browser) {
        if (browser != null) {
            browser.close();
        }
    }

    /** Parse the browser's render format ("URL: u\nTITLE: t\n\ntext") into {url, title, text}. */
    static String[] parseBrowserPage(String rendered) {
        String url = "";
        String title = "";
        StringBuilder text = new StringBuilder();
        boolean inText = false;
        for (String line : rendered.split("\n", -1)) {
            if (!inText && line.startsWith("URL: ")) {
                url = line.substring(5).trim();
            } else if (!inText && line.startsWith("TITLE: ")) {
                title = line.substring(7).trim();
            } else if (!inText && line.trim().isEmpty()) {
                inText = true;
            } else if (inText) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(line);
            }
        }
        return new String[]{url, title, text.toString()};
    }

    /** A structurally valid artificial SERP: nav bar + repeated li(h2(a),p) result blocks. */
    private static HttpHandler serpPage(String navHref, String[][] results) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><title>Find</title>"
                + "</head><body><nav><a href='" + navHref + "'>Videos</a></nav><main><ul>");
        for (String[] result : results) {
            html.append("<li><h2><a href='").append(result[1]).append("'>").append(result[0])
                .append("</a></h2><p>Explanatory snippet describing ").append(result[0])
                .append(" in detail.</p></li>");
        }
        html.append("</ul></main></body></html>");
        final String body = html.toString();
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes);
                exchange.close();
            }
        };
    }

    private static HttpHandler page(String title, String text, String linkScript) {
        final String html = PAGE_TEMPLATE.replace("%TITLE%", title)
                .replace("%TEXT%", text).replace("%LINKS%", linkScript);
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body);
                exchange.close();
            }
        };
    }

    private static String jsLink(String text, String href) {
        return "var a=document.createElement('a');a.href='" + href + "';a.textContent='" + text
                + "';document.body.appendChild(a);";
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

}
