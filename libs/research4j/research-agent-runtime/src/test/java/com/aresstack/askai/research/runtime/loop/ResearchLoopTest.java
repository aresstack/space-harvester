package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The deterministic loop against content fixtures (A relevant primary / B irrelevant / C relevant on a second
 * host / D content-duplicate of A / E complementary): decisions derive from page/link CONTENT, not order.
 * Budgets, cancel, error-reset and late-write rejection are all driven without any real time or sleeps.
 */
public class ResearchLoopTest {

    /** One fixture page. */
    private static final class Page {
        final String title;
        final String text;
        final List<String> links;

        Page(String title, String text, List<String> links) {
            this.title = title;
            this.text = text;
            this.links = links;
        }
    }

    /** Deterministic browser tool backend (fixture world) with a capture-id convention like the real one. */
    private static final class FakeBrowser implements ToolInvoker {
        final Map<String, Page> pages = new LinkedHashMap<String, Page>();
        final Map<String, String> captureByUrl = new LinkedHashMap<String, String>();
        final Map<String, String> redirects = new LinkedHashMap<String, String>();
        // Typed web_search_prepare fixture: candidate urls (A3-resolved), transit provider hosts and
        // typed challenges. Null candidate urls → the default two-result SERP.
        List<String> searchUrls;
        List<String> searchProviders = new ArrayList<String>();
        List<SearchChallengeState> searchChallenges = new ArrayList<SearchChallengeState>();
        String current;
        int captureSeq;
        boolean failEverything;
        /** Characterization: the exact web_open sequence (frontier processing order), requested urls. */
        final List<String> opened = new ArrayList<String>();
        /** Scripted web_challenge_status responses (consumed in order; last one repeats). */
        final List<String> challengeStatusScript = new ArrayList<String>();
        int challengeStatusCalls;

        FakeBrowser() {
            List<String> aLinks = new ArrayList<String>();
            aLinks.add("pf4j details — https://host2.net/c");
            aLinks.add("boring news — https://host1.com/b");
            pages.put("https://host1.com/a", new Page("PF4J primer",
                    "pf4j is a plugin framework. Primary source.", aLinks));
            pages.put("https://host1.com/b", new Page("Cooking tips",
                    "Recipes for soup and bread. Purely culinary content.", new ArrayList<String>()));
            List<String> cLinks = new ArrayList<String>();
            cLinks.add("pf4j mirror copy — https://host3.org/d");
            cLinks.add("pf4j extra evidence — https://host2.net/e");
            pages.put("https://host2.net/c", new Page("Independent pf4j review",
                    "pf4j works well with java 8.", cLinks));
            pages.put("https://host3.org/d", new Page("PF4J primer (mirror)",
                    "pf4j is a plugin framework. Primary source.", new ArrayList<String>()));
            pages.put("https://host2.net/e", new Page("pf4j in production",
                    "More pf4j evidence from the field.", new ArrayList<String>()));
        }

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if (failEverything) {
                throw new ToolFailure("browser down");
            }
            if ("web_search_prepare".equals(tool)) {
                List<String> urls = searchUrls != null ? searchUrls
                        : Arrays.asList("https://host1.com/a", "https://host1.com/b");
                return preparedJson(urls, searchProviders, searchChallenges);
            }
            if ("web_open".equals(tool)) {
                String requested = String.valueOf(args.get("url"));
                opened.add(requested);
                // Like the real browser: navigation FOLLOWS redirects; the snapshot reports the FINAL URL.
                String url = redirects.containsKey(requested) ? redirects.get(requested) : requested;
                Page page = pages.get(url);
                if (page == null) {
                    throw new ToolFailure("404 " + url);
                }
                current = url;
                String cap = captureByUrl.get(url);
                if (cap == null) {
                    cap = "cap-" + (++captureSeq);
                    captureByUrl.put(url, cap);
                }
                return "URL: " + url + " title=\"" + page.title + "\" capture_id=" + cap
                        + "\n" + page.text;
            }
            if ("web_challenge_status".equals(tool)) {
                challengeStatusCalls++;
                if (challengeStatusScript.isEmpty()) {
                    return "NONE";
                }
                return challengeStatusScript.size() > 1
                        ? challengeStatusScript.remove(0) : challengeStatusScript.get(0);
            }
            if ("web_links".equals(tool)) {
                Page page = pages.get(current);
                StringBuilder sb = new StringBuilder();
                if (page != null) {
                    for (String link : page.links) {
                        sb.append(link).append('\n');
                    }
                }
                return sb.toString();
            }
            throw new ToolFailure("unknown tool " + tool);
        }
    }

    /** Encode a web_search_prepare result: A3-resolved organic candidates + transit hosts + challenges. */
    static String preparedJson(List<String> urls, List<String> providerHosts,
                               List<SearchChallengeState> challenges) {
        List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            candidates.add(new SearchResultCandidate("c" + i, "snap", url, url, "result " + i,
                    "snippet " + i, "", i + 1, "rc", "rb", 0.9, 0.9,
                    Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList()));
        }
        WebSearchPreparationStatus status = !candidates.isEmpty()
                ? WebSearchPreparationStatus.ORGANIC_RESULTS
                : (!challenges.isEmpty() ? WebSearchPreparationStatus.CHALLENGE_PENDING
                        : WebSearchPreparationStatus.NO_ORGANIC_RESULTS);
        return SearchLayoutRepairJson.encodePrepared(new PreparedWebSearchResult(status, candidates,
                Collections.<com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest>
                        emptyList(),
                providerHosts,
                Collections.<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>emptyList(),
                challenges, Collections.<String>emptyList()));
    }

    /** Deterministic research backend honoring the Commit-37 result contract incl. dedup by content. */
    private static final class FakeResearch implements ToolInvoker {
        final FakeBrowser browser;
        final Map<String, String> sourceByHash = new LinkedHashMap<String, String>();
        final Map<String, String> sourceByCapture = new LinkedHashMap<String, String>();
        final List<String> findings = new ArrayList<String>();
        boolean waitingApproval;
        int seq;

        FakeResearch(FakeBrowser browser) {
            this.browser = browser;
        }

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if (waitingApproval && ("source_accept".equals(tool) || "finding_add".equals(tool))) {
                throw new ToolFailure("Not allowed in the current state (research/waiting_approval).");
            }
            if ("source_accept".equals(tool)) {
                String cap = String.valueOf(args.get("capture_id"));
                String already = sourceByCapture.get(cap);
                if (already != null) {
                    return "status=ALREADY_ACCEPTED source_id=" + already + " duplicate=false";
                }
                String url = null;
                for (Map.Entry<String, String> e : browser.captureByUrl.entrySet()) {
                    if (e.getValue().equals(cap)) {
                        url = e.getKey();
                    }
                }
                String hash = browser.pages.get(url).text; // content hash stand-in
                boolean duplicate = sourceByHash.containsKey(hash);
                String id = "source-" + (++seq);
                sourceByCapture.put(cap, id);
                sourceByHash.put(hash + (duplicate ? "#" + id : ""), id);
                if (!duplicate) {
                    sourceByHash.put(hash, id);
                }
                return "status=ACCEPTED source_id=" + id + " title=\"t\" passage_count=1 duplicate="
                        + duplicate;
            }
            if ("finding_add".equals(tool)) {
                String sourceId = String.valueOf(args.get("source_id"));
                if (!sourceByCapture.containsValue(sourceId)) {
                    throw new ToolFailure("Unknown source: " + sourceId);
                }
                findings.add(sourceId + ": " + args.get("text"));
                return "appended revision=1";
            }
            throw new ToolFailure("unknown tool " + tool);
        }
    }

    private static final class Fx {
        final FakeBrowser browser = new FakeBrowser();
        final FakeResearch research = new FakeResearch(browser);
        final AtomicLong now = new AtomicLong(1000L);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final List<String> statuses = new ArrayList<String>();
        final List<String> progressTokens = new ArrayList<String>();
        final List<ResearchRunActivity> activities = new ArrayList<ResearchRunActivity>();
        final List<ResearchStopReason> ready = new ArrayList<ResearchStopReason>();
        final List<String> attentions = new ArrayList<String>();
        /** Runs once per simulated wait tick (lets tests resolve the challenge or cancel mid-wait). */
        Runnable onSleepTick;

        ResearchLoop loop(ResearchRunBudget budget) {
            return new ResearchLoop(browser, research, budget,
                    new ResearchLoopClock() {
                        public long currentTimeMillis() {
                            return now.get();
                        }

                        public void sleepMillis(long millis) {
                            now.addAndGet(millis); // simulated time — tests never sleep for real
                            if (onSleepTick != null) {
                                onSleepTick.run();
                            }
                        }
                    },
                    new ResearchLoopListener() {
                        public void status(String message) {
                            statuses.add(message);
                        }

                        public void progress(ResearchRunProgress progress, ResearchRunActivity activity) {
                            progressTokens.add(activity.getToken());
                            activities.add(activity);
                        }

                        public void phaseReady(ResearchStopReason reason) {
                            ready.add(reason);
                        }

                        public void attention(String reason, String domainFamily, String url,
                                              boolean resolved) {
                            attentions.add((resolved ? "RESOLVED " : "REQUIRED ") + domainFamily);
                        }
                    }, cancelled);
        }
    }

    @Test
    public void contentDrivenRunVisitsFollowsAcceptsAndReportsPhaseReady() {
        Fx fx = new Fx();
        ResearchLoop loop = fx.loop(ResearchRunBudget.defaults());
        ResearchStopReason reason = loop.run("investigate pf4j plugin framework");

        assertTrue("at least three distinct pages", loop.getProgress().getPagesVisited() >= 3);
        assertTrue("at least two accepted sources", loop.getProgress().getAcceptedSources() >= 2);
        assertTrue("two distinct hosts", loop.getProgress().getDistinctHosts().size() >= 2);
        // The irrelevant page B was visited via search but never accepted.
        boolean bAccepted = fx.research.sourceByCapture.containsKey(
                fx.browser.captureByUrl.get("https://host1.com/b"));
        assertFalse("irrelevant page must not be accepted", bAccepted);
        // Issue #32: the loop records NO findings artifact anymore — acceptance is the evidence record.
        assertTrue("no finding_add calls anymore (issue #32)", fx.research.findings.isEmpty());
        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
        assertEquals(1, fx.ready.size()); // PHASE_READY event sent — but only as an event
    }

    @Test
    public void characterizationAcquisitionProcessingOrderIsFrozen() {
        // Freeze TODAY's deterministic acquisition order before the extraction: SERP candidates [a, b] in
        // engine order, then relevance-filtered link discovery appends c (from a), then d, e (from c). The
        // irrelevant page b is visited (it was a SERP candidate) but never accepted; d is a content-duplicate
        // of a yet still an accepted source. This pins frontier FIFO + link-filter order + acceptance.
        Fx fx = new Fx();
        ResearchLoop loop = fx.loop(ResearchRunBudget.defaults());
        ResearchStopReason reason = loop.run("investigate pf4j plugin framework");

        assertEquals(Arrays.asList(
                "https://host1.com/a",
                "https://host1.com/b",
                "https://host2.net/c",
                "https://host3.org/d",
                "https://host2.net/e"), fx.browser.opened);
        assertEquals("a, c, d, e accepted (b irrelevant)", 4, loop.getProgress().getAcceptedSources());
        assertFalse("the irrelevant SERP page is never accepted",
                fx.research.sourceByCapture.containsKey(fx.browser.captureByUrl.get("https://host1.com/b")));
        assertTrue("issue #32: acceptance no longer records findings", fx.research.findings.isEmpty());
        assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
    }

    @Test
    public void budgetsStopTheRunExplicitly() {
        // Tool budget.
        Fx fx = new Fx();
        assertEquals(ResearchStopReason.TOOL_BUDGET_EXHAUSTED,
                fx.loop(new ResearchRunBudget(2, 20, 8, 3, 600_000, 3, 2)).run("pf4j"));
        // Page budget.
        Fx fx2 = new Fx();
        assertEquals(ResearchStopReason.PAGE_BUDGET_EXHAUSTED,
                fx2.loop(new ResearchRunBudget(30, 1, 8, 3, 600_000, 3, 2)).run("pf4j"));
        // Source budget.
        Fx fx3 = new Fx();
        assertEquals(ResearchStopReason.SOURCE_BUDGET_EXHAUSTED,
                fx3.loop(new ResearchRunBudget(30, 20, 1, 3, 600_000, 3, 2)).run("pf4j"));
    }

    @Test
    public void timeBudgetStopsViaClockPortWithoutSleep() {
        Fx fx = new Fx();
        ResearchLoop loop = fx.loop(new ResearchRunBudget(30, 20, 8, 3, 5_000, 3, 2));
        fx.now.addAndGet(10_000); // advance the injected clock past the budget — no real waiting
        assertEquals(ResearchStopReason.TIME_BUDGET_EXHAUSTED, loop.run("pf4j"));
    }

    @Test
    public void errorBudgetStopsAndSuccessResetsConsecutiveErrors() {
        Fx fx = new Fx();
        fx.browser.failEverything = true;
        ResearchLoop failing = fx.loop(new ResearchRunBudget(30, 20, 8, 2, 600_000, 3, 2));
        ResearchStopReason reason = failing.run("pf4j");
        // A browser that fails EVERYTHING is a TECHNICAL outcome: either the error budget ends the
        // run, or the thrown seed search reports SEARCH_TECHNICAL_PROBLEM. It must never end as
        // the honest-empty NO_RELEVANT_PATHS — that read as a green success without a searched page.
        assertTrue("unexpected stop reason: " + reason,
                reason == ResearchStopReason.ERROR_BUDGET_EXHAUSTED
                || reason == ResearchStopReason.SEARCH_TECHNICAL_PROBLEM);

        // Reset semantics on the progress itself: error, error, success → 0, error → 1.
        ResearchRunProgress p = new ResearchRunProgress();
        p.error();
        p.error();
        assertEquals(2, p.getConsecutiveErrors());
        p.success();
        assertEquals(0, p.getConsecutiveErrors());
        p.error();
        assertEquals(1, p.getConsecutiveErrors());
        assertEquals(3, p.getTotalErrors());
    }

    @Test
    public void userCancelStopsTheRun() {
        Fx fx = new Fx();
        fx.cancelled.set(true);
        assertEquals(ResearchStopReason.USER_CANCELLED, fx.loop(ResearchRunBudget.defaults()).run("pf4j"));
    }

    @Test
    public void lateWriteAfterStateChangeIsRejectedAndStopsWithApprovalRequired() {
        Fx fx = new Fx();
        fx.research.waitingApproval = true; // the host flipped to WAITING_APPROVAL mid-run
        ResearchStopReason reason = fx.loop(ResearchRunBudget.defaults()).run("pf4j");
        assertEquals(ResearchStopReason.APPROVAL_REQUIRED, reason);
        assertTrue("no finding may have been stored", fx.research.findings.isEmpty());
        assertEquals("PHASE_READY must not fire on an interrupted run", 0, fx.ready.size());
    }

    @Test
    public void hostDiversityCountsTheFinalPostRedirectUrlNotTheRequestedSearchLink() {
        // The user-reported counting bug: search engines link through redirect URLs (bing.com/ck/a?...), so
        // counting hostOf(requested) yields hosts=1 ("bing.com") even when the pages come from different
        // websites. The FINAL URL from web_open must drive pages/hosts/visited bookkeeping.
        Fx fx = new Fx();
        // A4: A3 resolves engine redirects during extraction, so the loop receives the DIRECT targets.
        fx.browser.searchUrls = java.util.Arrays.asList(
                "https://example-a.org/article", "https://example-b.net/report");
        fx.browser.pages.put("https://example-a.org/article",
                new Page("pf4j article", "pf4j evidence from site a", new ArrayList<String>()));
        fx.browser.pages.put("https://example-b.net/report",
                new Page("pf4j report", "pf4j evidence from site b", new ArrayList<String>()));

        ResearchLoop loop = fx.loop(new ResearchRunBudget(30, 20, 8, 3, 600_000, 2, 2));
        ResearchStopReason reason = loop.run("pf4j");

        assertEquals("both FINAL hosts count", 2, loop.getProgress().getDistinctHosts().size());
        assertTrue(loop.getProgress().getDistinctHosts().contains("example-a.org"));
        assertTrue(loop.getProgress().getDistinctHosts().contains("example-b.net"));
        assertFalse("the redirector must not be counted as an evidence host",
                loop.getProgress().getDistinctHosts().contains("www.bing.com"));
        assertTrue(loop.getProgress().alreadyVisited("https://example-a.org/article"));
        assertEquals("2 sources from 2 hosts meet the 2/2 minimums",
                ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
        assertFalse("in-place progress updates were emitted", fx.progressTokens.isEmpty());
    }

    @Test
    public void progressActivitiesCarryQueryFinalHostTitleAndDispositions() {
        // Commit 56: the loop reports WHAT it searches and WHICH final page it reads — through redirects.
        Fx fx = new Fx();
        fx.browser.searchUrls = java.util.Arrays.asList("https://example-a.org/article");
        List<String> links = new ArrayList<String>();
        links.add("pf4j cooking blog — https://host1.com/b");
        fx.browser.pages.put("https://example-a.org/article",
                new Page("PF4J article on site A", "pf4j evidence from site a", links));

        fx.loop(ResearchRunBudget.defaults()).run("pf4j");

        ResearchRunActivity searching = null;
        ResearchRunActivity reading = null;
        ResearchRunActivity accepted = null;
        ResearchRunActivity skipped = null;
        for (ResearchRunActivity activity : fx.activities) {
            if (ResearchRunActivity.SEARCHING.equals(activity.getToken()) && searching == null) {
                searching = activity;
            }
            if (ResearchRunActivity.READING_PAGE.equals(activity.getToken()) && reading == null) {
                reading = activity;
            }
            if (ResearchRunActivity.SOURCE_ACCEPTED.equals(activity.getToken()) && accepted == null) {
                accepted = activity;
            }
            if (ResearchRunActivity.PAGE_SKIPPED.equals(activity.getToken()) && skipped == null) {
                skipped = activity;
            }
        }
        assertTrue("SEARCHING carries the actually used query",
                searching != null && searching.getSearchQuery().contains("pf4j"));
        assertTrue("READING_PAGE reports the FINAL post-redirect url",
                reading != null && reading.getUrl().equals("https://example-a.org/article"));
        assertEquals("example-a.org", reading.getHost());
        assertEquals("the full quoted page title, not just its first word",
                "PF4J article on site A", reading.getPageTitle());
        assertTrue("an accepted page reports host + title",
                accepted != null && accepted.getHost().equals("example-a.org")
                        && accepted.getPageTitle().equals("PF4J article on site A"));
        assertTrue("the irrelevant page B is visibly skipped with its final host",
                skipped != null && skipped.getHost().equals("host1.com")
                        && skipped.getPageTitle().equals("Cooking tips"));
    }

    @Test
    public void searchProviderIsTransitNeverASourceHostOrLinkFarm() {
        // The user-reported bug: on Bing the agent walked the engine's own tabs (Videos, Shopping, …),
        // counted bing.com as THE website and never rated real target pages. The provider host from the
        // PROVIDER line is transit: visited at most once, never counted, never accepted, links ignored.
        Fx fx = new Fx();
        // A4: A3 returns ORGANIC results only (verticals like /videos never become candidates) and
        // resolves redirects, so the loop only ever sees the real target; www.bing.com stays transit.
        fx.browser.searchProviders = java.util.Arrays.asList("www.bing.com");
        fx.browser.searchUrls = java.util.Arrays.asList("https://example-a.org/article");
        fx.browser.pages.put("https://example-a.org/article",
                new Page("pf4j article", "pf4j evidence from site a", new ArrayList<String>()));

        ResearchLoop loop = fx.loop(ResearchRunBudget.defaults());
        loop.run("pf4j");

        assertEquals("only the real target host counts", 1, loop.getProgress().getDistinctHosts().size());
        assertTrue(loop.getProgress().getDistinctHosts().contains("example-a.org"));
        assertFalse("the provider host is transit, never counted as an evidence host",
                loop.getProgress().getDistinctHosts().contains("www.bing.com"));
        assertFalse("the provider vertical never reaches the loop (A3 filtered it upstream)",
                fx.browser.captureByUrl.containsKey("https://www.bing.com/videos/search?q=pf4j"));
    }

    @Test
    public void manualChallengeLocksTheFamilyContinuesElsewhereAndWaitsForTheUser() {
        // Bing demands a CAPTCHA: its family is locked (queued, no retries), other domains continue,
        // and once only challenge-bound work remains the loop WAITS for the user instead of failing.
        Fx fx = new Fx();
        fx.browser.searchProviders = java.util.Arrays.asList("html.duckduckgo.com");
        fx.browser.searchChallenges = java.util.Arrays.asList(new SearchChallengeState(
                "bing.com", "https://www.bing.com/search?q=pf4j"));
        fx.browser.searchUrls = java.util.Arrays.asList(
                "https://host1.com/a", "https://www.bing.com/deep/result");
        fx.browser.challengeStatusScript.add("CHALLENGE: bing.com https://www.bing.com/search?q=pf4j");
        fx.browser.challengeStatusScript.add("CHALLENGE: bing.com https://www.bing.com/search?q=pf4j");
        fx.browser.challengeStatusScript.add("RESOLVED: bing.com");
        fx.browser.pages.put("https://www.bing.com/deep/result",
                new Page("pf4j on bing", "pf4j evidence behind the challenge", new ArrayList<String>()));

        ResearchLoop loop = fx.loop(new ResearchRunBudget(60, 40, 8, 3, 5_000, 2, 2));
        ResearchStopReason reason = loop.run("pf4j");

        assertEquals("REQUIRED attention exactly once", 1,
                java.util.Collections.frequency(fx.attentions, "REQUIRED bing.com"));
        assertEquals("RESOLVED attention exactly once", 1,
                java.util.Collections.frequency(fx.attentions, "RESOLVED bing.com"));
        assertTrue("other domains were processed during the challenge",
                loop.getProgress().alreadyVisited("https://host1.com/a"));
        assertTrue("the queued bing URL was visited only AFTER resolution",
                loop.getProgress().alreadyVisited("https://www.bing.com/deep/result"));
        assertTrue("the wait phase was visible", fx.progressTokens.contains("WAITING_FOR_USER"));
        assertFalse("waiting for the user must never exhaust the time budget",
                reason == ResearchStopReason.TIME_BUDGET_EXHAUSTED);
    }

    @Test
    public void cancelDuringTheManualChallengeWaitStopsImmediately() {
        final Fx fx = new Fx();
        fx.browser.searchChallenges = java.util.Arrays.asList(new SearchChallengeState(
                "bing.com", "https://www.bing.com/search?q=pf4j"));
        fx.browser.searchUrls = java.util.Arrays.asList("https://www.bing.com/deep/only");
        fx.browser.challengeStatusScript.add("CHALLENGE: bing.com https://www.bing.com/search?q=pf4j");
        fx.onSleepTick = new Runnable() {
            public void run() {
                fx.cancelled.set(true); // the user cancels while the loop waits
            }
        };
        assertEquals(ResearchStopReason.USER_CANCELLED,
                fx.loop(ResearchRunBudget.defaults()).run("pf4j"));
    }

    @Test
    public void allProviderLinesAreParsedAsTransitSites() {
        assertEquals(java.util.Arrays.asList("www.bing.com", "html.duckduckgo.com"),
                ResearchLoop.providerHostsOf("PROVIDER: www.bing.com\nPROVIDER: html.duckduckgo.com\n"
                        + "1: x — https://a.example/"));
        assertTrue(ResearchLoop.providerHostsOf("1: x — https://a.example/").isEmpty());
    }

    @Test
    public void revisitedUrlsAreNeverNavigatedAgainAndPagesCountOnlyNewCanonicalUrls() {
        ResearchRunProgress p = new ResearchRunProgress();
        assertTrue(p.pageVisited("https://a/x", "a"));
        assertFalse("same canonical url counts once", p.pageVisited("https://a/x", "a"));
        assertTrue(p.alreadyVisited("https://a/x"));
        assertEquals(1, p.getPagesVisited());
    }
}
