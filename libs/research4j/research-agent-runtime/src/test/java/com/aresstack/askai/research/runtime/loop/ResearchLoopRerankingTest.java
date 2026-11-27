package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptorCodec;
import com.aresstack.askai.research.runtime.rerank.HttpRerankerClient;
import com.aresstack.askai.research.runtime.rerank.RerankerConfigurationLoader;
import com.aresstack.askai.research.runtime.rerank.SearchResultReranker;
import com.aresstack.askai.research.runtime.rerank.SearchResultSelectionPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A5d proof: the mandatory reranker runs EXACTLY ONCE before any browser navigation, and the loop
 * opens only the selected candidates, in reranked (RAW_LOGIT) order — never in raw engine order.
 */
public class ResearchLoopRerankingTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String MODEL = "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest";

    private HttpServer server;
    /** documentIndex -> raw logit; the embedded reranker echoes these. */
    private final AtomicInteger rerankCalls = new AtomicInteger(0);

    @Before
    public void startReranker() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/rerank", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                rerankCalls.incrementAndGet();
                // The endpoint is asked twice for different things now: once to rank the SERP hits, and
                // once per opened page to say how relevant that page is. It must answer whatever it was
                // asked, so it counts the documents it was given (each begins with "Title: ").
                String request = readAll(exchange.getRequestBody());
                int documents = countOccurrences(request, "Title:");
                String body;
                if (documents == 3) {
                    // Score index1 highest, then index2, then index0 — a deliberate reordering of the
                    // engine's original A,B,C order into B,C,A. Best hit is a small logit (no 0.5 gate).
                    body = "{\"model\":\"" + MODEL + "\",\"results\":["
                            + "{\"index\":0,\"score\":-2.0},"
                            + "{\"index\":1,\"score\":0.4},"
                            + "{\"index\":2,\"score\":-0.1}]}";
                } else {
                    StringBuilder results = new StringBuilder();
                    for (int i = 0; i < Math.max(1, documents); i++) {
                        if (i > 0) {
                            results.append(',');
                        }
                        results.append("{\"index\":").append(i).append(",\"score\":0.0}");
                    }
                    body = "{\"model\":\"" + MODEL + "\",\"results\":[" + results + "]}";
                }
                byte[] out = body.getBytes(UTF_8);
                exchange.sendResponseHeaders(200, out.length);
                OutputStream os = exchange.getResponseBody();
                os.write(out);
                os.close();
            }
        });
        server.start();
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        return new String(buffer.toByteArray(), UTF_8);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @After
    public void stopReranker() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SearchResultReranker reranker(int topN) {
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest",
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 5_000L,
                RerankerSelectionConfiguration.topN(topN));
        return new SearchResultReranker(new HttpRerankerClient(descriptor),
                new SearchResultSelectionPolicy(descriptor.selectionConfiguration));
    }

    /** A browser fake that records the ORDER of web_open calls; pages carry no links (seed only). */
    private static final class RecordingBrowser implements ToolInvoker {
        final List<String> opened = new ArrayList<String>();
        final List<String> urls = Arrays.asList(
                "https://a.example/x", "https://b.example/y", "https://c.example/z");
        int cap;

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if ("web_search_prepare".equals(tool)) {
                return ResearchLoopTest.preparedJson(urls, new ArrayList<String>(),
                        new ArrayList<com.aresstack.askai.browser.search.repair.SearchChallengeState>());
            }
            if ("web_open".equals(tool)) {
                String url = String.valueOf(args.get("url"));
                opened.add(url);
                return "URL: " + url + " title=\"pf4j page\" capture_id=cap-" + (++cap)
                        + "\npf4j content here";
            }
            if ("web_links".equals(tool)) {
                return ""; // no onward links: the frontier is exactly the reranked seed
            }
            if ("web_challenge_status".equals(tool)) {
                return "NONE";
            }
            throw new ToolFailure("unknown tool " + tool);
        }
    }

    /** A research fake that accepts nothing (keeps the test focused on navigation order). */
    private static final class NoopResearch implements ToolInvoker {
        public String call(String tool, Map<String, Object> args) {
            if ("source_accept".equals(tool)) {
                return "source_id=-";
            }
            return "ok";
        }
    }

    private ResearchLoop loop(ToolInvoker browser, ToolInvoker research) {
        return new ResearchLoop(browser, research, ResearchRunBudget.defaults(),
                new ResearchLoopClock() {
                    public long currentTimeMillis() {
                        return 1000L;
                    }

                    public void sleepMillis(long millis) {
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
    }

    @Test
    public void opensOnlySelectedCandidatesInRerankedOrder() {
        RecordingBrowser browser = new RecordingBrowser();
        ResearchLoop loop = loop(browser, new NoopResearch());
        loop.setReranker(reranker(2)); // Top-2 of the three prepared candidates

        loop.run("investigate pf4j plugin framework");

        assertEquals("once to rank the SERP, then once per opened page — relevance does not stop "
                + "being a question after the result page", 1 + browser.opened.size(),
                rerankCalls.get());
        // Engine order was A,B,C; reranked B,C,A; Top-2 -> only B then C are opened, A never.
        assertEquals(Arrays.asList("https://b.example/y", "https://c.example/z"), browser.opened);
        assertFalse("the lowest-scored candidate is never opened",
                browser.opened.contains("https://a.example/x"));
    }

    @Test
    public void fullChainFromPublishedSnapshotFileDrivesRerankedNavigation() throws Exception {
        // Publish a start snapshot exactly as the host writer emits it (shared strict codec bytes) …
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest",
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 5_000L,
                RerankerSelectionConfiguration.topN(2));
        File snapshot = File.createTempFile("reranker-config", ".json");
        snapshot.deleteOnExit();
        Files.write(snapshot.toPath(), RerankerEndpointDescriptorCodec.toJson(
                RerankerConfigurationDocument.current(1L, descriptor)).getBytes(UTF_8));

        // … load it through the strict loader and build the productive client + policy from it.
        RerankerConfigurationDocument loaded = RerankerConfigurationLoader.load(snapshot.getPath());
        SearchResultReranker fromSnapshot = new SearchResultReranker(
                new HttpRerankerClient(loaded.descriptor),
                new SearchResultSelectionPolicy(loaded.descriptor.selectionConfiguration));

        RecordingBrowser browser = new RecordingBrowser();
        ResearchLoop loop = loop(browser, new NoopResearch());
        loop.setReranker(fromSnapshot);
        loop.run("investigate pf4j plugin framework");

        assertEquals(1 + browser.opened.size(), rerankCalls.get());
        // Same reranking outcome, now sourced entirely from the on-disk snapshot contract.
        assertEquals(Arrays.asList("https://b.example/y", "https://c.example/z"), browser.opened);
    }

    /** A stub reranker that always reports one fixed outcome and opens nothing. */
    private static com.aresstack.askai.research.runtime.rerank.CandidateReranker stub(
            final com.aresstack.askai.research.runtime.rerank.SearchResultRerankingOutcome outcome) {
        return new com.aresstack.askai.research.runtime.rerank.CandidateReranker() {
            public com.aresstack.askai.research.runtime.rerank.SearchResultRerankingResult rerank(
                    String query,
                    java.util.List<com.aresstack.askai.browser.search.SearchResultCandidate> candidates,
                    com.aresstack.askai.browser.search.inference.CancellationSignal cancellation) {
                return com.aresstack.askai.research.runtime.rerank.SearchResultRerankingResult.failure(
                        outcome, "stub", RerankerScoreSemantics.RAW_LOGIT, "stubbed " + outcome);
            }

            public com.aresstack.askai.research.domain.search.RelevanceAssessment assess(
                    String query, java.util.LinkedHashMap<String, String> documentsById,
                    com.aresstack.askai.browser.search.inference.CancellationSignal cancellation) {
                return com.aresstack.askai.research.domain.search.RelevanceAssessment
                        .unavailable("stub reranker has no relevance model");
            }
        };
    }

    @Test
    public void rerankerUnavailableEndsTheRunWithATypedReasonAndNoPageOpens() {
        RecordingBrowser browser = new RecordingBrowser();
        ResearchLoop loop = loop(browser, new NoopResearch());
        loop.setReranker(stub(com.aresstack.askai.research.runtime.rerank
                .SearchResultRerankingOutcome.RERANKER_UNAVAILABLE));

        ResearchStopReason reason = loop.run("investigate pf4j plugin framework");

        assertEquals(ResearchStopReason.RERANKER_UNAVAILABLE, reason);
        assertTrue("no page is opened when the mandatory reranker fails", browser.opened.isEmpty());
    }

    @Test
    public void noSemanticMatchesIsTypedNotNoRelevantPaths() {
        RecordingBrowser browser = new RecordingBrowser();
        ResearchLoop loop = loop(browser, new NoopResearch());
        loop.setReranker(stub(com.aresstack.askai.research.runtime.rerank
                .SearchResultRerankingOutcome.NO_SEMANTIC_MATCHES));

        ResearchStopReason reason = loop.run("investigate pf4j plugin framework");

        assertEquals(ResearchStopReason.NO_SEMANTIC_MATCHES, reason);
        assertTrue(browser.opened.isEmpty());
    }

    @Test
    public void topOneSelectionOpensOnlyTheSingleBestCandidate() {
        RecordingBrowser browser = new RecordingBrowser();
        ResearchLoop loop = loop(browser, new NoopResearch());
        loop.setReranker(reranker(1));

        loop.run("investigate pf4j plugin framework");

        assertEquals(1 + browser.opened.size(), rerankCalls.get());
        assertEquals(Collections.singletonList("https://b.example/y"), browser.opened);
    }
}
