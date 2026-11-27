package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.domain.search.RelevanceAssessment;
import com.aresstack.askai.research.runtime.rerank.CandidateReranker;
import com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate;
import com.aresstack.askai.research.runtime.rerank.SearchResultRerankingOutcome;
import com.aresstack.askai.research.runtime.rerank.SearchResultRerankingResult;

import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;

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
 * The live drift this closes. For the query "Hasensteaks kulinarische Bedeutung" the acquisition
 * rejected the Wikipedia article on rabbit meat and accepted a chilled noodle salad, a mushroom recipe
 * and a Facebook share stub — because after the semantic reranking the only remaining test was
 * <pre>hasensteaks OR kulinarische OR bedeutung, anywhere in the text</pre>
 * and those recipes live under a path spelled {@code /kulinarische-spiele/}. A relevance decision must
 * not degrade into a substring test on the way from the result page to the source.
 */
public class SemanticRelevanceThroughAcquisitionTest {

    private static final String QUERY = "Hasensteaks kulinarische Bedeutung";

    private static final String RABBIT_STEAK = "https://rezepte.example/hasensteak-mit-gemuese";
    private static final String RABBIT_MEAT = "https://de.wikipedia.example/wiki/Hasenfleisch";
    private static final String FARM = "https://bauernladen.example/der-hase-am-teller";
    private static final String NOODLES =
            "https://rezepte.example/kulinarische-spiele/sommernudelsalat";
    private static final String MUSHROOMS =
            "https://rezepte.example/kulinarische-spiele/champignons";

    /**
     * A relevance model that answers about MEANING: anything about hare/rabbit meat scores well for this
     * query, anything else does not. It never sees a URL — exactly like the productive one.
     */
    private static final class TopicReranker implements CandidateReranker {
        final List<String> assessed = new ArrayList<String>();

        private static double score(String document) {
            String text = document.toLowerCase(java.util.Locale.ROOT);
            return text.contains("hase") || text.contains("rabbit") || text.contains("kaninchen")
                    ? 1.0 : -3.0;
        }

        public SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates,
                                                  CancellationSignal cancellation) {
            List<RerankedSearchResultCandidate> ranked =
                    new ArrayList<RerankedSearchResultCandidate>();
            for (int i = 0; i < candidates.size(); i++) {
                ranked.add(new RerankedSearchResultCandidate(candidates.get(i),
                        score(candidates.get(i).title + " " + candidates.get(i).snippet), i + 1));
            }
            return new SearchResultRerankingResult(SearchResultRerankingOutcome.SUCCESS, ranked,
                    ranked, "topic", RerankerScoreSemantics.RAW_LOGIT, "all selected", 0L, 0L);
        }

        public RelevanceAssessment assess(String query, LinkedHashMap<String, String> documentsById,
                                          CancellationSignal cancellation) {
            List<RelevanceAssessment.Score> scores = new ArrayList<RelevanceAssessment.Score>();
            for (Map.Entry<String, String> entry : documentsById.entrySet()) {
                assessed.add(entry.getValue());
                scores.add(new RelevanceAssessment.Score(entry.getKey(), score(entry.getValue())));
            }
            java.util.Collections.sort(scores, new java.util.Comparator<RelevanceAssessment.Score>() {
                public int compare(RelevanceAssessment.Score a, RelevanceAssessment.Score b) {
                    return Double.compare(b.getRelevance(), a.getRelevance());
                }
            });
            return RelevanceAssessment.of("topic", scores);
        }
    }

    /** The SERP plus four pages, two of which are the off-topic recipes the live run swallowed. */
    private static final class SerpAndPages implements ToolInvoker {
        final List<String> opened = new ArrayList<String>();
        int cap;

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if ("web_search_prepare".equals(tool)) {
                // Real titles and snippets: the reranker judges the SERP by what the hits SAY, and the
                // weakest selected hit is the bar every discovered link later has to clear.
                return preparedJson(
                        candidate(RABBIT_STEAK, "Hasensteak mit Röstgemüse",
                                "Hasensteaks aus der Keule, kurz gebraten"),
                        candidate(RABBIT_MEAT, "Hasenfleisch",
                                "Fleisch des Feldhasen, dunkles Wildbret"),
                        candidate(FARM, "Der Hase am Teller",
                                "Wildbret vom Hasen in der regionalen Küche"));
            }
            if ("web_open".equals(tool)) {
                String url = String.valueOf(args.get("url"));
                opened.add(url);
                return "URL: " + url + " title=\"" + titleOf(url) + "\" capture_id=cap-" + (++cap)
                        + "\n" + bodyOf(url);
            }
            if ("web_links".equals(tool)) {
                // The recipe site cross-links its other recipes; their ADDRESSES spell "kulinarische".
                return "l1: Gekühlter Sommernudelsalat mit Sesam-Ingwer-Dressing — " + NOODLES + "\n"
                        + "l2: Sautierte Champignon-Pilze mit Telemea-Käse — " + MUSHROOMS + "\n";
            }
            if ("web_challenge_status".equals(tool)) {
                return "NONE";
            }
            throw new ToolFailure("unknown tool " + tool);
        }

        private static String titleOf(String url) {
            if (url.equals(RABBIT_STEAK)) {
                return "Hasensteak mit Röstgemüse";
            }
            if (url.equals(RABBIT_MEAT)) {
                return "Hasenfleisch";
            }
            if (url.equals(FARM)) {
                return "Der Hase am Teller";
            }
            return "Rezept";
        }

        /**
         * The Wikipedia body deliberately contains NONE of the query's words as substrings — no
         * "hasensteaks", no "kulinarische", no "bedeutung". Lexically it is a miss; semantically it is
         * the best answer on the page.
         */
        private static String bodyOf(String url) {
            if (url.equals(RABBIT_MEAT)) {
                return "Hasenfleisch ist das Fleisch des Feldhasen und gilt als dunkles Wildbret.";
            }
            if (url.equals(FARM)) {
                return "Der Hase am Teller: Wildbret vom Hasen in der regionalen Küche.";
            }
            if (url.equals(RABBIT_STEAK)) {
                return "Hasensteaks aus der Keule, kurz gebraten — kulinarische Einordnung.";
            }
            return "Nudelsalat mit Sesam und Ingwer, ein Sommerrezept ohne Fleisch.";
        }
    }

    private static com.aresstack.askai.browser.search.SearchResultCandidate candidate(
            String url, String title, String snippet) {
        return new com.aresstack.askai.browser.search.SearchResultCandidate("c-" + url, "snap", url,
                url, title, snippet, "", 1, "rc", "rb", 0.9, 0.9,
                java.util.Collections
                        .<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList());
    }

    private static String preparedJson(
            com.aresstack.askai.browser.search.SearchResultCandidate... candidates) {
        return com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson.encodePrepared(
                new com.aresstack.askai.browser.search.repair.PreparedWebSearchResult(
                        com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus
                                .ORGANIC_RESULTS,
                        Arrays.asList(candidates),
                        java.util.Collections.<com.aresstack.askai.browser.search.repair
                                .SearchLayoutRepairRequest>emptyList(),
                        new ArrayList<String>(),
                        java.util.Collections.<com.aresstack.askai.browser
                                .LegacySearchEngineAttemptResult>emptyList(),
                        java.util.Collections.<com.aresstack.askai.browser.search.repair
                                .SearchChallengeState>emptyList(),
                        new ArrayList<String>()));
    }

    /** Records which pages actually became sources. */
    private static final class RecordingResearch implements ToolInvoker {
        final List<String> acceptedCaptures = new ArrayList<String>();
        int ids;

        public String call(String tool, Map<String, Object> args) {
            if ("source_accept".equals(tool)) {
                acceptedCaptures.add(String.valueOf(args.get("capture_id")));
                return "source_id=src-" + (++ids);
            }
            return "ok";
        }
    }

    private static ResearchLoop loop(ToolInvoker browser, ToolInvoker research,
                                     CandidateReranker reranker) {
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
        loop.setReranker(reranker);
        return loop;
    }

    @Test
    public void aSemanticallyRelevantPageIsKeptEvenWithoutASingleQueryWord() {
        SerpAndPages browser = new SerpAndPages();
        RecordingResearch research = new RecordingResearch();
        loop(browser, research, new TopicReranker()).run(QUERY);

        assertTrue("the article on rabbit meat is what this query is about",
                browser.opened.contains(RABBIT_MEAT));
        assertTrue("and it must become a source although it spells none of the query's words",
                research.acceptedCaptures.size() >= 2);
    }

    @Test
    public void anOffTopicPageIsNeitherFollowedNorAccepted() {
        SerpAndPages browser = new SerpAndPages();
        RecordingResearch research = new RecordingResearch();
        loop(browser, research, new TopicReranker()).run(QUERY);

        assertFalse("a noodle salad is not a rabbit steak, whatever its address spells",
                browser.opened.contains(NOODLES));
        assertFalse(browser.opened.contains(MUSHROOMS));
    }

    /** Without a relevance model nothing may pretend to have judged: the old lexical test is named. */
    @Test
    public void withoutARelevanceModelTheLexicalFallbackIsUsedAndSaysSo() {
        SerpAndPages browser = new SerpAndPages();
        RecordingResearch research = new RecordingResearch();
        final List<String> status = new ArrayList<String>();
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
                        status.add(message);
                    }

                    public void progress(ResearchRunProgress progress, ResearchRunActivity activity) {
                    }

                    public void phaseReady(ResearchStopReason reason) {
                    }

                    public void attention(String reason, String domainFamily, String url,
                                          boolean resolved) {
                    }
                }, new AtomicBoolean(false));
        loop.setReranker(new com.aresstack.askai.research.runtime.rerank.EngineOrderReranker());

        loop.run(QUERY);

        boolean saidSo = false;
        for (String line : status) {
            saidSo |= line.contains("relevance unavailable");
        }
        assertTrue("a degraded decision has to be visible as one", saidSo);
    }
}
