package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.UrlResolutionStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * askai #41, lazy resolution (Angelo 02.11.): an unresolved Google {@code /goto} hit stays in the research pipeline
 * and is resolved only when it is actually opened — but never by the research browser. The research browser does
 * not open a search engine address (2026-11-09: following the {@code /goto} hops from a second browser identity
 * ended in Google's "one last step" challenge). The hop runs in the search browser through the
 * {@link OpaqueRedirectResolver}; the final URL becomes the source identity and the {@code /goto} link stays as
 * provenance. Without a resolver, or when it fails, the hit is dropped; when the engine blocks the hop, the remaining
 * engine hits of the search are dropped and nobody is asked to solve anything.
 */
public class UnresolvedRedirectCandidateTest {

    private static final String GOTO = "https://www.google.com/goto?url=CAESAAAA";
    private static final String TARGET = "https://target.example/freertos-scheduling";
    private static final String DIRECT = "https://direct.example/freertos-tasks";

    private static final class RecordingBrowser implements ToolInvoker {
        final List<String> openedUrls = new ArrayList<String>();
        final Map<String, String> finalUrls = new HashMap<String, String>();
        final Map<String, String> failures = new HashMap<String, String>();

        public String call(String tool, Map<String, Object> args) throws ToolFailure {
            if ("web_open".equals(tool)) {
                String url = String.valueOf(args.get("url"));
                openedUrls.add(url);
                if (failures.containsKey(url)) {
                    throw new ToolFailure(failures.get(url));
                }
                String landed = finalUrls.containsKey(url) ? finalUrls.get(url) : url;
                return "URL: " + landed + " capture_id=c" + openedUrls.size()
                        + " title=\"FreeRTOS scheduling\"\nfreertos scheduling tasks priorities preemption";
            }
            return "";
        }
    }

    private static final class RecordingAcceptance implements SourceAcceptancePort {
        final List<String> parked = new ArrayList<String>();
        final List<String> accepted = new ArrayList<String>();

        public String accept(String captureId) {
            accepted.add(captureId);
            return "accepted source_id=s-" + captureId;
        }

        public void park(String url, String title, String excerpt, double score) {
            parked.add(url);
        }
    }

    private static SearchResultCandidate gotoHit(String gotoUrl, int rank) {
        return new SearchResultCandidate("google#" + rank, "", "", gotoUrl,
                "FreeRTOS Scheduling " + rank, "freertos scheduling", "", rank, "", "", 1.0, 1.0,
                Collections.<SearchResultSiteLink>emptyList(), "", UrlResolutionStatus.UNRESOLVED, gotoUrl);
    }

    private static SearchStrategy strategy() {
        return strategy(Arrays.asList(gotoHit(GOTO, 1), new SearchResultCandidate("google#2", "", DIRECT, DIRECT,
                "FreeRTOS Tasks", "freertos tasks", "direct.example", 2, "", "", 1.0, 1.0,
                Collections.<SearchResultSiteLink>emptyList())));
    }

    private static SearchStrategy strategy(final List<SearchResultCandidate> hits) {
        return new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                              SearchBudgetGate budget) {
                return new InitialSearchResult(hits, new ArrayList<String>(),
                        Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                        new ArrayList<String>());
            }
        };
    }

    private static WebSearchApplicationService service(RecordingBrowser browser, RecordingAcceptance acceptance,
                                                       final List<String> status) {
        return service(browser, acceptance, status, strategy(),
                new ArrayList<WebSearchApplicationService.AcceptedSource>());
    }

    private static WebSearchApplicationService service(RecordingBrowser browser, RecordingAcceptance acceptance,
                                                       final List<String> status, SearchStrategy strategy,
                                                       final List<WebSearchApplicationService.AcceptedSource> sources) {
        return service(browser, acceptance, new ResearchLoopListener() {
            public void status(String message) {
                status.add(message);
            }

            public void progress(ResearchRunProgress p, ResearchRunActivity activity) {
            }

            public void phaseReady(ResearchStopReason reason) {
            }

            public void attention(String reason, String family, String url, boolean resolved) {
            }
        }, strategy, sources);
    }

    private static WebSearchApplicationService service(RecordingBrowser browser, RecordingAcceptance acceptance,
                                                       ResearchLoopListener listener, SearchStrategy strategy,
                                                       final List<WebSearchApplicationService.AcceptedSource> sources) {
        return new WebSearchApplicationService(browser, ResearchRunBudget.defaults(), new ResearchRunProgress(),
                new ResearchLoopClock() {
                    long now;

                    public long currentTimeMillis() {
                        return now;
                    }

                    public void sleepMillis(long millis) {
                        now += Math.max(1L, millis);
                    }
                },
                listener,
                new AtomicBoolean(false), strategy, null, new EngineOrderReranker(),
                new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                acceptance, 0L, 1L,
                new WebSearchApplicationService.AcceptedSourceListener() {
                    public ResearchStopReason onAccepted(WebSearchApplicationService.AcceptedSource source,
                                                         WebSearchApplicationService.ToolBudget budget) {
                        sources.add(source);
                        return null;
                    }
                },
                false, 0 /* readiness loop off: web_open verbatim */, 48);
    }

    /** A resolver scripted per wrapper URL; records what it was asked. */
    private static final class ScriptedResolver implements OpaqueRedirectResolver {
        final Map<String, Resolution> answers = new HashMap<String, Resolution>();
        final List<String> asked = new ArrayList<String>();

        public Resolution resolve(String wrapperUrl) {
            asked.add(wrapperUrl);
            Resolution answer = answers.get(wrapperUrl);
            return answer == null ? Resolution.unresolved("not scripted") : answer;
        }
    }

    private static final class RecordingListener implements ResearchLoopListener {
        final List<String> status = new ArrayList<String>();
        final List<String> attention = new ArrayList<String>();

        public void status(String message) {
            status.add(message);
        }

        public void progress(ResearchRunProgress p, ResearchRunActivity activity) {
        }

        public void phaseReady(ResearchStopReason reason) {
        }

        public void attention(String reason, String family, String url, boolean resolved) {
            attention.add(reason + " " + family);
        }
    }

    @Test
    public void aSelectedGotoHitIsResolvedByTheSearchBrowserAndTheResearchBrowserOpensOnlyTheTarget() {
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        List<WebSearchApplicationService.AcceptedSource> sources =
                new ArrayList<WebSearchApplicationService.AcceptedSource>();
        ScriptedResolver resolver = new ScriptedResolver();
        resolver.answers.put(GOTO, OpaqueRedirectResolver.Resolution.resolved(TARGET));

        WebSearchApplicationService service = service(browser, acceptance, status, strategy(), sources);
        service.setRedirectResolver(resolver);
        service.execute("freertos scheduling");

        assertEquals("the search browser resolved the hop, lazily, once", Collections.singletonList(GOTO),
                resolver.asked);
        assertFalse("the research browser never opened the engine address", browser.openedUrls.contains(GOTO));
        assertTrue("the research browser opened the resolved target", browser.openedUrls.contains(TARGET));
        assertFalse("never parked under the redirect (no verified target at seed time)",
                acceptance.parked.contains(GOTO));
        assertTrue("a verified hit is still parked", acceptance.parked.contains(DIRECT));
        assertEquals(2, acceptance.accepted.size());
        WebSearchApplicationService.AcceptedSource viaGoto = sourceAt(sources, TARGET);
        assertEquals("the final URL is the source identity", TARGET, viaGoto.getPageUrl());
        assertEquals("the /goto link stays as provenance", GOTO, viaGoto.getOriginalUrl());
        assertEquals(UrlResolutionStatus.RESOLVED, viaGoto.getUrlResolution());
        WebSearchApplicationService.AcceptedSource direct = sourceAt(sources, DIRECT);
        assertEquals(UrlResolutionStatus.DIRECT, direct.getUrlResolution());
        assertEquals("", direct.getOriginalUrl());
        assertTrue(status.toString(), status.toString().contains("redirect resolved by search browser: " + GOTO
                + " -> " + TARGET));
    }

    @Test
    public void withoutAResolverTheGotoHitIsDroppedAndTheEngineAddressIsNeverOpened() {
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();

        service(browser, acceptance, status).execute("freertos scheduling");

        assertFalse(browser.openedUrls.contains(GOTO));
        assertTrue("the run went on with the other hit", browser.openedUrls.contains(DIRECT));
        assertEquals(1, acceptance.accepted.size());
        assertTrue(status.toString(), status.toString().contains(
                "skipped unresolved engine redirect (no search browser to resolve it): " + GOTO));
    }

    @Test
    public void anUnresolvedHopDropsOnlyThatHit() {
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        ScriptedResolver resolver = new ScriptedResolver();
        resolver.answers.put(GOTO, OpaqueRedirectResolver.Resolution.unresolved("timeout"));

        WebSearchApplicationService service = service(browser, acceptance, status, strategy(),
                new ArrayList<WebSearchApplicationService.AcceptedSource>());
        service.setRedirectResolver(resolver);
        service.execute("freertos scheduling");

        assertFalse(browser.openedUrls.contains(GOTO));
        assertTrue(browser.openedUrls.contains(DIRECT));
        assertEquals(1, acceptance.accepted.size());
        assertTrue(status.toString(), status.toString().contains("skipped unresolved engine redirect: " + GOTO
                + " [timeout]"));
    }

    @Test
    public void aBlockedHopDropsTheRemainingEngineHitsWithoutAskingTheUser() {
        String otherGoto = "https://www.google.com/goto?url=CAESBBBB";
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        RecordingListener listener = new RecordingListener();
        ScriptedResolver resolver = new ScriptedResolver();
        resolver.answers.put(GOTO, OpaqueRedirectResolver.Resolution.blocked("https://www.google.com/sorry/index"));
        resolver.answers.put(otherGoto, OpaqueRedirectResolver.Resolution.resolved("https://other.example/x"));

        WebSearchApplicationService service = service(browser, acceptance, listener, strategy(Arrays.asList(
                gotoHit(GOTO, 1), gotoHit(otherGoto, 2),
                new SearchResultCandidate("google#3", "", DIRECT, DIRECT, "FreeRTOS Tasks", "freertos tasks",
                        "direct.example", 3, "", "", 1.0, 1.0, Collections.<SearchResultSiteLink>emptyList()))),
                new ArrayList<WebSearchApplicationService.AcceptedSource>());
        service.setRedirectResolver(resolver);
        service.execute("freertos scheduling");

        assertEquals("after the block the second hop is not even attempted", Collections.singletonList(GOTO),
                resolver.asked);
        assertFalse(browser.openedUrls.contains(GOTO));
        assertFalse(browser.openedUrls.contains(otherGoto));
        assertFalse(browser.openedUrls.contains("https://other.example/x"));
        assertTrue("the direct hit is still read", browser.openedUrls.contains(DIRECT));
        assertTrue("a challenge in the headless search browser is nothing the user could solve: no notice",
                listener.attention.isEmpty());
        assertTrue(listener.status.toString(), listener.status.toString().contains(
                "search engine blocked the redirect hop"));
        assertTrue(listener.status.toString(), listener.status.toString().contains(
                "skipped engine redirect (engine blocked earlier in this search): " + otherGoto));
    }

    @Test
    public void twoGotoHitsResolvingToTheSameTargetAreDedupedOnTheTarget() {
        String otherGoto = "https://www.google.com/goto?url=CAESBBBB";
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        ScriptedResolver resolver = new ScriptedResolver();
        resolver.answers.put(GOTO, OpaqueRedirectResolver.Resolution.resolved(TARGET));
        resolver.answers.put(otherGoto, OpaqueRedirectResolver.Resolution.resolved(TARGET));

        WebSearchApplicationService service = service(browser, acceptance, status,
                strategy(Arrays.asList(gotoHit(GOTO, 1), gotoHit(otherGoto, 2))),
                new ArrayList<WebSearchApplicationService.AcceptedSource>());
        service.setRedirectResolver(resolver);
        service.execute("freertos scheduling");

        assertEquals("one source for one target", 1, acceptance.accepted.size());
        assertEquals(Collections.singletonList(TARGET), browser.openedUrls);
        assertTrue(status.toString(), status.toString().contains("skipped duplicate target: " + TARGET
                + " (via " + otherGoto + ")"));
    }

    @Test
    public void anEngineVerticalInTheFrontierIsNeverOpened() {
        // an engine page without a query parameter: the hit-list rule does not catch it, the engine guard must
        String vertical = "https://www.google.com/maps/place/FreeRTOS";
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();

        service(browser, acceptance, status, strategy(Arrays.asList(
                new SearchResultCandidate("google#1", "", vertical, vertical, "Videos", "freertos videos",
                        "google.com", 1, "", "", 1.0, 1.0, Collections.<SearchResultSiteLink>emptyList()),
                new SearchResultCandidate("google#2", "", DIRECT, DIRECT, "FreeRTOS Tasks", "freertos tasks",
                        "direct.example", 2, "", "", 1.0, 1.0, Collections.<SearchResultSiteLink>emptyList()))),
                new ArrayList<WebSearchApplicationService.AcceptedSource>()).execute("freertos scheduling");

        assertEquals(Collections.singletonList(DIRECT), browser.openedUrls);
        assertTrue(status.toString(), status.toString().contains(
                "skipped search-engine address (never opened in the research browser): " + vertical));
    }

    @Test
    public void aHopOntoAShopCatalogueIsSkippedBeforeTheResearchBrowserOpensIt() {
        // live run 2026-11-09: the SERP lane cannot judge an opaque /goto wrapper, so shop.bueroheuser.de was
        // navigated and only rejected after the fetch — the resolved target is judged before the visit
        String shop = "https://shop.example.com/collections/wearables";
        RecordingBrowser browser = new RecordingBrowser();
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        ScriptedResolver resolver = new ScriptedResolver();
        resolver.answers.put(GOTO, OpaqueRedirectResolver.Resolution.resolved(shop));

        WebSearchApplicationService service = service(browser, acceptance, status, strategy(),
                new ArrayList<WebSearchApplicationService.AcceptedSource>());
        service.setRedirectResolver(resolver);
        service.execute("freertos scheduling");

        assertEquals("the hop was resolved once, in the search browser", Collections.singletonList(GOTO),
                resolver.asked);
        assertEquals("only the direct hit was opened", Collections.singletonList(DIRECT), browser.openedUrls);
        assertEquals(1, acceptance.accepted.size());
        assertTrue(status.toString(), status.toString().contains("search-blacklist target skip url=" + shop
                + " (via " + GOTO + ") entry=\"shop catalogue page\" -> not opened"));
    }

    @Test
    public void aFailedNavigationOfTheResolvedTargetEndsOnlyThatVisit() {
        RecordingBrowser browser = new RecordingBrowser();
        browser.failures.put(TARGET, "navigation timeout");
        RecordingAcceptance acceptance = new RecordingAcceptance();
        List<String> status = new ArrayList<String>();
        ScriptedResolver resolver = new ScriptedResolver();
        resolver.answers.put(GOTO, OpaqueRedirectResolver.Resolution.resolved(TARGET));

        WebSearchApplicationService service = service(browser, acceptance, status, strategy(),
                new ArrayList<WebSearchApplicationService.AcceptedSource>());
        service.setRedirectResolver(resolver);
        service.execute("freertos scheduling");

        assertTrue(browser.openedUrls.contains(TARGET));
        assertTrue("the run went on with the other hit", browser.openedUrls.contains(DIRECT));
        assertEquals(1, acceptance.accepted.size());
    }

    private static WebSearchApplicationService.AcceptedSource sourceAt(
            List<WebSearchApplicationService.AcceptedSource> sources, String url) {
        for (WebSearchApplicationService.AcceptedSource source : sources) {
            if (url.equals(source.getPageUrl())) {
                return source;
            }
        }
        throw new AssertionError("no source " + url + " in " + sources.size());
    }
}
