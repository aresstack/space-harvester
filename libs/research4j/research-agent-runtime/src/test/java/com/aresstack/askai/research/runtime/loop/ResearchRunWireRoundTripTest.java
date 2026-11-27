package com.aresstack.askai.research.runtime.loop;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the research ACP extension format across the process boundary: the runtime ENCODER and the UI-plugin
 * PARSER are deliberately small duplicates — this round-trip is the contract test that keeps them identical.
 */
public class ResearchRunWireRoundTripTest {

    @Test
    public void markersAreIdenticalOnBothSides() {
        assertEquals(com.aresstack.askai.research.agentwire.ResearchRunWire.MARKER, ResearchRunWire.MARKER);
    }

    @Test
    public void progressLineRoundTrips() {
        ResearchRunProgress progress = new ResearchRunProgress();
        progress.pageVisited("https://example-a.org/article", "example-a.org");
        progress.sourceAccepted();
        progress.toolCall();
        progress.toolCall();
        String line = ResearchRunWire.progress(progress, new ResearchRunBudget(30, 20, 8, 3, 600_000, 3, 2),
                ResearchRunActivity.readingPage("https://example-a.org/article", "example-a.org",
                        "Ein Artikel über PF4J & Isolation"));

        assertTrue(com.aresstack.askai.research.agentwire.ResearchRunWire.isWireLine(line));
        assertEquals("progress", com.aresstack.askai.research.agentwire.ResearchRunWire.typeOf(line));
        Map<String, String> f = com.aresstack.askai.research.agentwire.ResearchRunWire.fields(line);
        assertEquals(1, com.aresstack.askai.research.agentwire.ResearchRunWire.intField(f, "pages"));
        assertEquals(1, com.aresstack.askai.research.agentwire.ResearchRunWire.intField(f, "sources"));
        assertEquals(1, com.aresstack.askai.research.agentwire.ResearchRunWire.intField(f, "hosts"));
        assertEquals(2, com.aresstack.askai.research.agentwire.ResearchRunWire.intField(f, "tools"));
        assertEquals(3, com.aresstack.askai.research.agentwire.ResearchRunWire.intField(f, "min_sources"));
        assertEquals(2, com.aresstack.askai.research.agentwire.ResearchRunWire.intField(f, "min_hosts"));
        assertEquals("READING_PAGE", f.get("activity"));
        assertEquals("https://example-a.org/article", f.get("url"));
        // Free-text fields travel URL-encoded (never a space on the wire) and decode losslessly.
        assertEquals("example-a.org",
                com.aresstack.askai.research.agentwire.ResearchRunWire.decodedField(f, "host"));
        assertEquals("Ein Artikel über PF4J & Isolation",
                com.aresstack.askai.research.agentwire.ResearchRunWire.decodedField(f, "title"));
    }

    @Test
    public void searchingProgressCarriesTheDecodableQuery() {
        ResearchRunProgress progress = new ResearchRunProgress();
        String line = ResearchRunWire.progress(progress, ResearchRunBudget.defaults(),
                ResearchRunActivity.searching("pf4j plugin isolation updates"));
        Map<String, String> f = com.aresstack.askai.research.agentwire.ResearchRunWire.fields(line);
        assertEquals("SEARCHING", f.get("activity"));
        assertEquals("pf4j plugin isolation updates",
                com.aresstack.askai.research.agentwire.ResearchRunWire.decodedField(f, "query"));
        assertEquals("a missing field decodes to the empty string", "",
                com.aresstack.askai.research.agentwire.ResearchRunWire.decodedField(f, "title"));
    }

    @Test
    public void outcomeLineRoundTrips() {
        ResearchRunProgress progress = new ResearchRunProgress();
        for (int i = 0; i < 7; i++) {
            progress.sourceAccepted();
        }
        progress.pageVisited("https://one.example/a", "one.example");
        ResearchRunBudget budget = new ResearchRunBudget(30, 20, 8, 3, 600_000, 3, 2);
        ResearchRunOutcome outcome =
                ResearchRunOutcome.from(ResearchStopReason.TOOL_BUDGET_EXHAUSTED, progress, budget);
        String line = ResearchRunWire.outcome(outcome);

        assertEquals("outcome", com.aresstack.askai.research.agentwire.ResearchRunWire.typeOf(line));
        Map<String, String> f = com.aresstack.askai.research.agentwire.ResearchRunWire.fields(line);
        assertEquals("TOOL_BUDGET_EXHAUSTED", f.get("stop"));
        assertEquals(7, com.aresstack.askai.research.agentwire.ResearchRunWire.intField(f, "sources"));
        assertEquals(1, com.aresstack.askai.research.agentwire.ResearchRunWire.intField(f, "hosts"));
        assertEquals("INSUFFICIENT_HOST_DIVERSITY", f.get("limitation"));
        assertEquals("CONTINUE_RESEARCH", f.get("action"));
        assertEquals("true", f.get("recoverable"));
    }

    @Test
    public void typedRerankerOutcomeRoundTripsWithItsOwnActionAndRecoverability() {
        String line = ResearchRunWire.outcome(ResearchRunOutcome.from(
                ResearchStopReason.RERANKER_CONFIGURATION_ERROR, new ResearchRunProgress(),
                ResearchRunBudget.defaults()));
        Map<String, String> f = com.aresstack.askai.research.agentwire.ResearchRunWire.fields(line);
        assertEquals("RERANKER_CONFIGURATION_ERROR", f.get("stop"));
        assertEquals("OPEN_CONFIGURATION", f.get("action"));
        assertEquals("false", f.get("recoverable"));
    }

    @Test
    public void greetedSignalRoundTrips() {
        String line = ResearchRunWire.greeted();
        assertTrue(com.aresstack.askai.research.agentwire.ResearchRunWire.isWireLine(line));
        assertEquals(com.aresstack.askai.research.agentwire.ResearchRunWire.TYPE_GREETED,
                com.aresstack.askai.research.agentwire.ResearchRunWire.typeOf(line));
    }

    @Test
    public void attentionLinesRoundTripBothStates() {
        String required = ResearchRunWire.attention("CAPTCHA", "bing.com",
                "https://www.bing.com/search?q=pf4j", false);
        assertEquals("attention", com.aresstack.askai.research.agentwire.ResearchRunWire.typeOf(required));
        Map<String, String> f = com.aresstack.askai.research.agentwire.ResearchRunWire.fields(required);
        assertEquals("CAPTCHA", f.get("reason"));
        assertEquals("bing.com", f.get("domain"));
        assertEquals("REQUIRED", f.get("state"));
        assertEquals("https://www.bing.com/search?q=pf4j", f.get("url"));

        Map<String, String> resolved = com.aresstack.askai.research.agentwire.ResearchRunWire.fields(
                ResearchRunWire.attention("CAPTCHA", "bing.com", "", true));
        assertEquals("RESOLVED", resolved.get("state"));
    }

    @Test
    public void logLinesCarryFreeTextAndNewlinesAreFlattened() {
        String line = ResearchRunWire.log("accepted source-7 (duplicate content)\nsecond");
        assertEquals("log", com.aresstack.askai.research.agentwire.ResearchRunWire.typeOf(line));
        assertEquals("accepted source-7 (duplicate content) second",
                com.aresstack.askai.research.agentwire.ResearchRunWire.logText(line));
    }
}
