package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.domain.search.RelevanceAssessment;
import com.aresstack.askai.research.runtime.rerank.CandidateReranker;
import com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate;
import com.aresstack.askai.research.runtime.rerank.SearchResultRerankingOutcome;
import com.aresstack.askai.research.runtime.rerank.SearchResultRerankingResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The HUD's Skip button used to buffer a command in the browser that nobody read while a page was being
 * worked on. By the time it was picked up — at the top of the next round — all it still meant was
 * {@code skipNextInterPageDelay = true}: the page had already been read, assessed, accepted and
 * harvested for links. A control pressed on one page that takes effect on the next is not a control.
 */
public class HudSkipEndsTheCurrentPageTest {

    private static final String FIRST = "https://a.example/first";
    private static final String SECOND = "https://b.example/second";
    private static final String LINK = "https://a.example/onward";

    /** Answers every relevance question with "yes", so only the skip can keep a page out. */
    private static final class AlwaysRelevant implements CandidateReranker {
        public SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates,
                                                  CancellationSignal cancellation) {
            List<RerankedSearchResultCandidate> ranked = new ArrayList<RerankedSearchResultCandidate>();
            for (int i = 0; i < candidates.size(); i++) {
                ranked.add(new RerankedSearchResultCandidate(candidates.get(i), 1.0, i + 1));
            }
            return new SearchResultRerankingResult(SearchResultRerankingOutcome.SUCCESS, ranked, ranked,
                    "always", RerankerScoreSemantics.RAW_LOGIT, "all selected", 0L, 0L);
        }

        public RelevanceAssessment assess(String query, LinkedHashMap<String, String> documentsById,
                                          CancellationSignal cancellation) {
            List<RelevanceAssessment.Score> scores = new ArrayList<RelevanceAssessment.Score>();
            for (Map.Entry<String, String> entry : documentsById.entrySet()) {
                scores.add(new RelevanceAssessment.Score(entry.getKey(), 1.0));
            }
            return RelevanceAssessment.of("always", scores);
        }
    }

    /**
     * A browser whose overlay reports a Skip exactly once, at the moment the FIRST page has been read.
     * That is the situation from the live report: the button was pressed while the page was being
     * worked on, not while the loop was between pages.
     */
    private static final class SkippingBrowser implements ToolInvoker {
        final List<String> opened = new ArrayList<String>();
        boolean skipDelivered;
        int cap;

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if ("web_search_prepare".equals(tool)) {
                return ResearchLoopTest.preparedJson(Arrays.asList(FIRST, SECOND),
                        new ArrayList<String>(),
                        new ArrayList<com.aresstack.askai.browser.search.repair.SearchChallengeState>());
            }
            if ("web_open".equals(tool)) {
                String url = String.valueOf(args.get("url"));
                opened.add(url);
                return "URL: " + url + " title=\"page\" capture_id=cap-" + (++cap) + "\ncontent";
            }
            if ("web_hud_poll".equals(tool)) {
                // The user pressed Skip while the first page was being worked on.
                if (!skipDelivered && opened.size() == 1) {
                    skipDelivered = true;
                    return "SKIP";
                }
                return "";
            }
            if ("web_links".equals(tool)) {
                // ONLY the first page offers this link, so opening it could only ever come from the
                // page the user abandoned.
                return opened.size() == 1 ? "l1: onward — " + LINK + "\n" : "";
            }
            if ("web_hud_render".equals(tool)) {
                return "ok";
            }
            if ("web_challenge_status".equals(tool)) {
                return "NONE";
            }
            throw new ToolFailure("unknown tool " + tool);
        }
    }

    private static final class RecordingResearch implements ToolInvoker {
        final List<String> accepted = new ArrayList<String>();
        int ids;

        public String call(String tool, Map<String, Object> args) {
            if ("source_accept".equals(tool)) {
                accepted.add(String.valueOf(args.get("capture_id")));
                return "source_id=src-" + (++ids);
            }
            return "ok";
        }
    }

    private static ResearchLoop loop(ToolInvoker browser, ToolInvoker research) {
        ResearchLoop loop = new ResearchLoop(browser, research, ResearchRunBudget.defaults(),
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
        loop.setReranker(new AlwaysRelevant());
        return loop;
    }

    @Test
    public void aSkippedPageBecomesNoSource() {
        SkippingBrowser browser = new SkippingBrowser();
        RecordingResearch research = new RecordingResearch();

        loop(browser, research).run("anything at all");

        assertFalse("the page the user left may not turn into a source",
                research.accepted.contains("cap-1"));
    }

    @Test
    public void aSkippedPageHandsNoLinksOn() {
        SkippingBrowser browser = new SkippingBrowser();

        loop(browser, new RecordingResearch()).run("anything at all");

        assertFalse("an abandoned page must not carry the run onwards",
                browser.opened.contains(LINK));
    }

    @Test
    public void theRunContinuesWithTheNextPage() {
        SkippingBrowser browser = new SkippingBrowser();
        RecordingResearch research = new RecordingResearch();

        loop(browser, research).run("anything at all");

        assertTrue("skipping one page is not stopping the search", browser.opened.contains(SECOND));
        assertFalse("and the next page is not skipped along with it", research.accepted.isEmpty());
    }
}
