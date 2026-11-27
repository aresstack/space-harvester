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
 * A result page that shows a visible security check (CAPTCHA) and is solved by the user is READ afterwards:
 * the run must report the check as RESOLVED for that website (not leave it BLOCKED), so the host's Sources list
 * marks the website yellow instead of red and the "manual input required" notice gets its all-clear.
 * Reuses the recorded Bing SERP of {@link BingHitsIntoSourcesTest}; only the first organic page is challenged.
 */
public class PageChallengeResolvedAttentionTest {

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
    public void aSolvedPageChallengeIsReportedResolvedForItsWebsite() throws Exception {
        String capture = "answer-card-run-1.capture.json";
        RenderedPageDocument serp = new Gson().fromJson(
                new String(Files.readAllBytes(CAPTURES.resolve(capture)), StandardCharsets.UTF_8),
                RenderedPageDocument.class);
        ScriptedBrowser browser = new ScriptedBrowser(new SearchLayoutRepairTools(
                LegacyBrowserSearchDefaults.create(), new CapturedBing(serp), () -> 0L));
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        RecordingListener listener = new RecordingListener(status);
        ResearchRunBudget budget = ResearchRunBudget.defaults();
        WebSearchApplicationService service = new WebSearchApplicationService(browser, budget,
                new ResearchRunProgress(), new SteppingClock(), listener,
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
                true, 2, 200);
        // As the user search is wired: done exactly at the configured number of accepted sources.
        service.setCompletionPolicy(new FixedAcceptedSourceCountPolicy(budget.getMaxAcceptedSources()));

        service.execute(QUERY);

        String challenged = browser.challengedUrl;
        assertTrue("a page was challenged " + status, challenged != null);
        assertTrue("REQUIRED is reported " + listener.attentions,
                listener.attentions.contains("CAPTCHA REQUIRED " + challenged));
        assertTrue("the solved and read page is reported RESOLVED " + listener.attentions,
                listener.attentions.contains("CAPTCHA RESOLVED " + challenged));
        assertTrue("RESOLVED follows REQUIRED",
                listener.attentions.indexOf("CAPTCHA REQUIRED " + challenged)
                        < listener.attentions.indexOf("CAPTCHA RESOLVED " + challenged));
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
        /** The first opened result page shows a visible challenge on its first probe; solved on reprobe. */
        String challengedUrl;

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
            if ("web_probe".equals(tool) && challengedUrl == null) {
                challengedUrl = current;
                return "url: " + current + "\ntitle: One last step\ntext_length: 40"
                        + "\nchallenge: true\nchallenge_marker: visible:captcha\nconsent: false\nexcerpt: "
                        + "verify you are human";
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

    private static final class RecordingAcceptance implements SourceAcceptancePort {
        final List<String> parked = new ArrayList<String>();
        final List<String> acceptedUrls = new ArrayList<String>();
        private final Map<String, String> sourceByCapture = new LinkedHashMap<String, String>();

        public String accept(String captureId) {
            String sourceId = sourceByCapture.get(captureId);
            if (sourceId == null) {
                sourceId = "source-" + (sourceByCapture.size() + 1);
                sourceByCapture.put(captureId, sourceId);
            }
            return "accepted source_id=" + sourceId;
        }

        public void park(String url, String title, String excerpt, double score) {
            parked.add(url);
        }
    }

    private static final class SteppingClock implements ResearchLoopClock {
        private long now;

        public long currentTimeMillis() {
            return now;
        }

        public void sleepMillis(long millis) {
            now += Math.max(1L, millis);
        }
    }

    private static final class RecordingListener implements ResearchLoopListener {
        private final List<String> status;
        final List<String> attentions = new ArrayList<String>();

        RecordingListener(List<String> status) {
            this.status = status;
        }

        public void status(String message) {
            status.add(message);
        }

        public void progress(ResearchRunProgress p, ResearchRunActivity activity) {
        }

        public void phaseReady(ResearchStopReason reason) {
        }

        public void attention(String reason, String family, String url, boolean resolved) {
            attentions.add(reason + (resolved ? " RESOLVED " : " REQUIRED ") + url);
        }
    }
}
