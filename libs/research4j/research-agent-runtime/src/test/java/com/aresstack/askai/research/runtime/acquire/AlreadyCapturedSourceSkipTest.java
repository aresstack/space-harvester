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
 * Live run 4: after a restart the search read a page again that was already a captured source (the visited-pages
 * memory of the agent process is gone after a restart, and every search starts with an empty one). The second read
 * produced a second capture of the same source, whose passages then counted twice. The host's park answer now says
 * {@code ALREADY_CAPTURED} for such an address, and the search skips the hit instead of reading it again.
 */
public class AlreadyCapturedSourceSkipTest {

    private static final String READ_BEFORE = "https://bauleiter.example/digitales-baustellenmanagement";
    private static final String NEW_PAGE = "https://handwerk.example/wearables";

    private static final class RecordingBrowser implements ToolInvoker {
        final List<String> openedUrls = new ArrayList<String>();

        public String call(String tool, Map<String, Object> args) {
            if ("web_open".equals(tool)) {
                String url = String.valueOf(args.get("url"));
                openedUrls.add(url);
                return "URL: " + url + " capture_id=c" + openedUrls.size()
                        + " title=\"Wearables on construction sites\"\nwearables construction sites safety";
            }
            return "";
        }
    }

    /** The host's park answers: the page read before is a captured source, the new one is parked. */
    private static final class HostAcceptance implements SourceAcceptancePort {
        final List<String> accepted = new ArrayList<String>();

        public String accept(String captureId) {
            accepted.add(captureId);
            return "status=ACCEPTED source_id=s-" + captureId;
        }

        public void park(String url, String title, String excerpt, double score) {
            throw new AssertionError("the search asks for the park result");
        }

        @Override
        public String parkReporting(String url, String title, String excerpt, double score) {
            return READ_BEFORE.equals(url) ? "status=ALREADY_CAPTURED source_id=source-61"
                    : "status=PARKED source_id=source-70";
        }
    }

    private static SearchResultCandidate hit(String url, int rank) {
        return new SearchResultCandidate("engine#" + rank, "", url, url, "Wearables " + rank,
                "wearables construction sites", "", rank, "", "", 1.0, 1.0,
                Collections.<SearchResultSiteLink>emptyList());
    }

    @Test
    public void aHitWhosePageIsAlreadyACapturedSourceIsNotReadAgain() {
        RecordingBrowser browser = new RecordingBrowser();
        HostAcceptance acceptance = new HostAcceptance();
        final List<String> status = new ArrayList<String>();
        final List<SearchResultCandidate> hits = Arrays.asList(hit(READ_BEFORE, 1), hit(NEW_PAGE, 2));
        WebSearchApplicationService service = new WebSearchApplicationService(browser,
                ResearchRunBudget.defaults(), new ResearchRunProgress(),
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

                    public void attention(String reason, String family, String url, boolean resolved) {
                    }
                },
                new AtomicBoolean(false),
                new SearchStrategy() {
                    public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                                      SearchBudgetGate budget) {
                        return new InitialSearchResult(hits, new ArrayList<String>(), Collections.<com.aresstack
                                .askai.browser.search.repair.SearchChallengeState>emptyList(),
                                new ArrayList<String>());
                    }
                },
                null, new EngineOrderReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(WebSearchApplicationService.AcceptedSource source,
                                                         WebSearchApplicationService.ToolBudget budget) {
                        return null;
                    }
                },
                false, 0 /* readiness loop off: web_open verbatim */, 48);

        service.execute("wearables construction sites");

        assertFalse("the captured source's page is not read again", browser.openedUrls.contains(READ_BEFORE));
        assertEquals(Collections.singletonList(NEW_PAGE), browser.openedUrls);
        assertEquals(1, acceptance.accepted.size());
        assertTrue(status.toString(), status.contains("skipped already captured source: " + READ_BEFORE));
    }
}
