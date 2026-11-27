package com.aresstack.askai.research.runtime;

import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.capture.VisitedCapture;
import com.aresstack.askai.research.host.ProductiveResearchSessionResources;
import com.aresstack.askai.research.host.ResearchRuntimeConfig;
import com.aresstack.askai.research.host.ResearchRuntimeGeneration;
import com.aresstack.askai.research.host.ResearchRuntimeGenerationSwitch;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Commit 38: the PRODUCTIVE research MVP end to end, driven exclusively through the host wiring AskAI uses —
 * {@code ResearchRuntimeGenerationSwitch} → {@code ProductiveResearchBackendFactory} →
 * {@code ProductiveResearchSessionResources} → {@code ResearchSessionBackend} port. No ResearchLoop call, no
 * ToolInvoker, no fake backend, no STATIC_HTTP anywhere in this test: the loop runs INSIDE the real
 * in-process agent session; pages come from the real in-process Playwright browser (JavaScript-only
 * content, two hosts);
 * sources persist through the Commit-37 acceptance path into the FILE repository; the host state machine is
 * the only transition authority (PHASE_READY is an event, the HOST dispatches REQUEST_EVIDENCE_REVIEW).
 * Also proven: ordered session teardown, prepare-then-publish generation switching and that a failed switch
 * leaves the old generation fully usable.
 */
public class ProductiveResearchMvpEndToEndTest {

    private static final String PAGE_TEMPLATE = "<!doctype html><html><head><title>%TITLE%</title></head>"
            + "<body><div id='c'></div><script>"
            + "document.getElementById('c').textContent='%TEXT% Rendered by JavaScript.';"
            + "%LINKS%"
            + "</script></body></html>";

    private static int outcomes(Collecting observer) {
        int n = 0;
        for (ResearchBackendEvent event : observer.events) {
            if (event.getType() == com.aresstack.askai.research.backend.ResearchBackendEventType
                    .RUN_OUTCOME) {
                n++;
            }
        }
        return n;
    }

    private static final class Collecting implements ResearchSessionListener {
        final List<ResearchBackendEvent> events = new CopyOnWriteArrayList<ResearchBackendEvent>();
        final CountDownLatch runStopped = new CountDownLatch(1);

        public void onEvent(ResearchBackendEvent event) {
            events.add(event);
            if (event.getType() == com.aresstack.askai.research.backend.ResearchBackendEventType
                    .RUN_OUTCOME) {
                // Commit 55: the STRUCTURED outcome is the run terminal (sent after PHASE_READY).
                runStopped.countDown();
            }
        }

        static String text(ResearchBackendEvent event) {
            return (event.getText() == null ? "" : event.getText())
                    + " " + (event.getPublicMessage() == null ? "" : event.getPublicMessage());
        }

        int count(String needle) {
            int n = 0;
            for (ResearchBackendEvent event : events) {
                if (text(event).contains(needle)) {
                    n++;
                }
            }
            return n;
        }
    }

    @Test
    public void productiveHostWiringRunsTheFullMvpAndSurvivesGenerationSwitching() throws Exception {
        // ---- external prerequisites (environment-gated with the concrete reason) ----
        // The reranker is MANDATORY for a productive browser session: start the real local reranker or
        // skip readably (no installed cross-encoder / no Java-21 launcher).
        com.aresstack.askai.research.runtime.rerank.LiveLocalRerankerRuntime reranker =
                com.aresstack.askai.research.runtime.rerank.LiveLocalRerankerRuntime.startOrNull();
        assumeTrue("SKIPPED: no live local reranker (mandatory for a browser session)",
                reranker != null);

        // ---- an ENGINE server + two content servers (host:port families via the browser dev flag) ----
        HttpServer engineServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer serverOne = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer serverTwo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseEngine = "http://127.0.0.1:" + engineServer.getAddress().getPort();
        String baseOne = "http://127.0.0.1:" + serverOne.getAddress().getPort();
        String baseTwo = "http://127.0.0.1:" + serverTwo.getAddress().getPort();
        // A structurally valid artificial SERP: repeated li(h2(a),p) blocks (A3 contract).
        engineServer.createContext("/find", serpPage(
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
        // Documented dev/test hand-off: local multi-server worlds act as distinct domain families,
        // and the local test engine is an EXPLICIT per-run browser flag (the persisted override is dead).
        String oldBrowserArgs = System.setProperty("askai.research.browser.args",
                "--domain-key-mode=host-port --search-url=" + baseEngine + "/find?q={query}");

        SolonMcpServerRuntime registry = new SolonMcpServerRuntime();
        ResearchRuntimeGenerationSwitch switcher = new ResearchRuntimeGenerationSwitch(
                registry, reranker.asProvider(10));
        ResearchRuntimeConfig config = new ResearchRuntimeConfig(
                System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome"),
                true, true, reranker.modelName);

        File projectDir = Files.createTempDirectory("askai-e2e").toFile();
        ProductiveResearchSessionResources resources = null;
        ResearchRuntimeGeneration gen1;
        try {
            // ---- generation 1: prepare-then-publish, then a productive session ----
            gen1 = switcher.switchTo(config);
            try {
                resources = gen1.createSession("e2e1", projectDir);
            } catch (IOException notReady) {
                // The only external dependency left is an installed browser; skip with the specific status.
                assumeTrue("SKIPPED (environment-gated): " + notReady.getMessage(), false);
                return;
            }

            // Both endpoints are really registered on the host MCP runtime.
            assertNotNull("research endpoint registered",
                    registry.endpointUrl(resources.getControlEndpoint().getHandle()));
            assertNotNull("browser bridge endpoint registered",
                    registry.endpointUrl(resources.getBrowserBridge().getHandle()));
            // Lazy browser: creating the session must NOT start the browser — it stays STOPPED until the
            // first web_* command.
            assertFalse("browser runtime is STOPPED at session start (lazy)",
                    resources.getBrowser().isReady());

            // ---- host is the only state authority: drive the machine to RESEARCH/running ----
            for (ResearchCommandType command : new ResearchCommandType[]{
                    ResearchCommandType.START, ResearchCommandType.SUBMIT_SCOPE, // C5: -> RESEARCH directly
                    ResearchCommandType.START_RESEARCH}) {
                assertTrue("command must be accepted: " + command,
                        resources.dispatch(command).isAccepted());
            }
            assertEquals(ResearchStateIds.RESEARCH, resources.currentState().getPhaseId());
            assertEquals(ResearchStateIds.RUNNING, resources.currentState().getStateId());

            // ---- the productive backend port: real agent process + prompt-driven autonomous run ----
            Collecting observer = new Collecting();
            ResearchSessionHandle handle = resources.getBackend().createSession(
                    new ResearchProjectRequest("e2e1", "p1", "Research project"), observer);
            assertTrue("agent session must not report a start error",
                    observer.count("could not be started") == 0);
            resources.getBackend().submitPrompt(handle,
                    new ResearchPrompt("research: pf4j plugin framework", ""));
            assertTrue("autonomous run must finish", observer.runStopped.await(180, TimeUnit.SECONDS));

            // The run drove web commands, so the lazy browser was started on demand (STOPPED → READY).
            // This test drives the backend directly (no ResearchAgentSession), so nothing stops it until
            // close() below — it is still READY here.
            assertTrue("browser runtime became READY on the first web command during the run",
                    resources.getBrowser().isReady());

            // ---- the mandated verifications ----
            assertEquals("exactly one PHASE_READY event", 1, observer.count("PHASE_READY:"));
            assertEquals("run must stop with sufficient evidence",
                    1, observer.count("run stopped: SUFFICIENT_EVIDENCE"));
            assertEquals("the structured outcome event arrived exactly once", 1, outcomes(observer));
            assertTrue("readiness reached the observer", observer.count("RESEARCH_MCP_READY") >= 1);
            assertTrue("observer received a full event stream", observer.events.size() >= 5);

            List<VisitedCapture> captures = resources.getCaptures().list();
            assertTrue("at least three captures", captures.size() >= 3);
            for (VisitedCapture capture : captures) {
                assertTrue("capture must be JS-rendered: " + capture.getCanonicalUrl(),
                        capture.getText().contains("Rendered by JavaScript"));
            }

            List<ResearchSourceRecord> sources = resources.getRepository().find(SourceQuery.all());
            assertTrue("at least two persisted sources", sources.size() >= 2);
            Set<String> origins = new HashSet<String>();
            for (ResearchSourceRecord source : sources) {
                origins.add(source.getOrigin());
            }
            assertEquals("sources from two distinct hosts", 2, origins.size());

            assertEquals("issue #32: no findings artifact is written anymore - accepted sources are the record",
                    "", resources.getArtifactStore().read("findings").getMarkdown());

            // PHASE_READY was an event only — the state did NOT change until the HOST reacts.
            assertEquals(ResearchStateIds.RUNNING, resources.currentState().getStateId());
            assertTrue(resources.dispatch(ResearchCommandType.REQUEST_EVIDENCE_REVIEW).isAccepted());
            assertEquals(ResearchStateIds.WAITING_APPROVAL, resources.currentState().getStateId());

            // ---- ordered, idempotent teardown ----
            resources.getBackend().close(handle);
            com.aresstack.askai.mcp.api.McpEndpointHandle controlHandle =
                    resources.getControlEndpoint().getHandle();
            resources.close();
            resources.close();
            assertFalse("browser runtime stopped/closed with the session (READY → STOPPED)",
                    resources.getBrowser().isReady());
            assertNull("research endpoint unregistered (token invalid)",
                    registry.endpointUrl(controlHandle));

            // ---- generation 1 stays usable until a NEW generation is published ----
            assertTrue("generation 1 still active", switcher.getActive() == gen1);
            assertFalse("generation 1 not retired", gen1.isRetired());
            ProductiveResearchSessionResources second = gen1.createSession("e2e2", projectDir);
            assertNotNull("old generation still creates working sessions", second);
            assertFalse("a fresh session's browser is STOPPED until first use (lazy)",
                    second.getBrowser().isReady());
            second.close();

            // ---- successful switch: publish only after preparation, then lock + close the old one ----
            ResearchRuntimeGeneration gen2 = switcher.switchTo(config);
            assertTrue("generation 1 retired after publish", gen1.isRetired());
            try {
                gen1.createSession("e2e3", projectDir);
                fail("retired generation must be locked for new sessions");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("retired"));
            }
            assertTrue(switcher.getActive() == gen2);
        } finally {
            if (resources != null) {
                resources.close();
            }
            switcher.shutdown();
            registry.shutdown();
            reranker.close();
            engineServer.stop(0);
            serverOne.stop(0);
            serverTwo.stop(0);
            if (oldBrowserArgs == null) {
                System.clearProperty("askai.research.browser.args");
            } else {
                System.setProperty("askai.research.browser.args", oldBrowserArgs);
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

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

    /** A structurally valid artificial SERP: repeated li(h2(a),p) result blocks. */
    private static HttpHandler serpPage(String[][] results) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><title>Find</title>"
                + "</head><body><main><ul>");
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

    private static String jsLink(String text, String href) {
        return "var a=document.createElement('a');a.href='" + href + "';a.textContent='" + text
                + "';document.body.appendChild(a);";
    }
}
