package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The configured browser engines decide which searches run, in which order and how many. */
public class BrowserEnginesSearchStrategyTest {

    private static final InitialSearchRequest REQUEST = new InitialSearchRequest("q", 10, "", "");
    private static final SearchBudgetGate BUDGET = () -> true;
    private static final CancellationSignal NOT_CANCELLED = () -> false;

    @Test
    public void googleFirstStopsWhenItDeliversEnough() throws Exception {
        Fake google = new Fake("https://a.example/", "https://b.example/", "https://c.example/",
                "https://d.example/", "https://e.example/");
        Fake pages = new Fake("https://b.example/");
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:on", "duckduckgo:on"), google, pages);
        assertEquals(Arrays.asList("google", "page-engines"), strategy.stepNames());
        InitialSearchResult result = strategy.search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(1, google.calls);
        assertEquals("five usable results: DuckDuckGo (priority 1) is only the fallback", 0, pages.calls);
        assertEquals(InitialSearchStatus.RESULTS, result.status);
    }

    @Test
    public void aFailingGoogleFallsBackToThePageEngines() throws Exception {
        Fake google = new Fake() {
            @Override
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                calls++;
                throw new IllegalStateException("blocked");
            }
        };
        Fake pages = new Fake("https://b.example/");
        InitialSearchResult result = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:on", "duckduckgo:on"), google, pages)
                .search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(1, pages.calls);
        assertEquals(1, result.candidates.size());
    }

    @Test
    public void aClosedBrowserIsTheUsersStopAndNeverFallsBack() {
        Fake pages = new Fake() {
            @Override
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                calls++;
                throw new IllegalStateException("BROWSER_CLOSED — the browser window was closed by the user.");
            }
        };
        Fake google = new Fake();
        try {
            new BrowserEnginesSearchStrategy(
                    selection(EngineAcquisitionMode.ALL_ENABLED, "duckduckgo:on", "google:on"), google, pages)
                    .search(REQUEST, NOT_CANCELLED, BUDGET);
            org.junit.Assert.fail("the close must reach the caller");
        } catch (Exception closed) {
            assertTrue(closed.getMessage().contains("BROWSER_CLOSED"));
        }
        assertEquals(0, google.calls);
    }

    @Test
    public void cancelledBeforeTheFirstEngineIsANeutralEmptyResult() throws Exception {
        Fake google = new Fake("https://a.example/");
        InitialSearchResult result = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:on"), google, null)
                .search(REQUEST, () -> true, BUDGET);
        assertEquals(0, google.calls);
        assertTrue(result.candidates.isEmpty());
        assertFalse(result.status == InitialSearchStatus.TECHNICAL_PROBLEM);
    }

    @Test
    public void allEnabledMergesWithoutDuplicates() throws Exception {
        Fake google = new Fake("https://a.example/", "https://same.example/");
        Fake pages = new Fake("https://same.example/", "https://c.example/");
        InitialSearchResult result = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.ALL_ENABLED, "duckduckgo:on", "google:on"), google, pages)
                .search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(3, result.candidates.size());
        assertEquals("the user's order: page engines first", "https://same.example/",
                result.candidates.get(0).resolvedTargetUrl);
    }

    @Test
    public void disabledGoogleNeverRunsAndGoogleAloneIsGoogleOnly() throws Exception {
        Fake google = new Fake("https://a.example/");
        Fake pages = new Fake("https://b.example/");
        BrowserEnginesSearchStrategy noGoogle = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:off", "duckduckgo:on"), google, pages);
        noGoogle.search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(0, google.calls);
        assertFalse(noGoogle.isGoogleOnly());
        assertTrue(new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:on", "bing:off"), google, pages).isGoogleOnly());
    }

    @Test
    public void searchesFromManyThreadsNeverRunAtTheSameTime() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxInside = new java.util.concurrent.atomic.AtomicInteger();
        SearchStrategy slowGoogle = new SearchStrategy() {
            public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b) {
                int now = inside.incrementAndGet();
                maxInside.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inside.decrementAndGet();
                return new Fake("https://a.example/" + now).search(r, c, b);
            }
        };
        // two chats, each with its own strategy instance: still one search at a time
        final BrowserEnginesSearchStrategy chatA = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:on"), slowGoogle, null);
        final BrowserEnginesSearchStrategy chatB = new BrowserEnginesSearchStrategy(
                selection(EngineAcquisitionMode.FIRST_USABLE, "google:on"), slowGoogle, null);
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < 6; i++) {
            final BrowserEnginesSearchStrategy strategy = i % 2 == 0 ? chatA : chatB;
            Thread t = new Thread(() -> {
                try {
                    strategy.search(REQUEST, NOT_CANCELLED, BUDGET);
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join(10_000L);
        }
        assertTrue(errors.toString(), errors.isEmpty());
        assertEquals(1, maxInside.get());
        assertEquals(0, BrowserEnginesSearchStrategy.waitingSearches());
    }

    // ------------------------------------------------------------------ priority groups

    /** The research browser's engine search narrowed per group; records which engines each call asked. */
    private static final class GroupFake implements EngineGroupSearchStrategy {
        final List<List<String>> calls = Collections.synchronizedList(new ArrayList<List<String>>());
        final java.util.Map<String, String[]> urlsPerEngine = new java.util.HashMap<String, String[]>();
        volatile RuntimeException failure;
        volatile long sleepMillis;
        final java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger overlap = new java.util.concurrent.atomic.AtomicInteger();

        public InitialSearchResult search(InitialSearchRequest r, CancellationSignal c, SearchBudgetGate b)
                throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
            return forEngines(Collections.<String>emptyList()).search(r, c, b);
        }

        public SearchStrategy forEngines(final List<String> ids) {
            return (r, c, b) -> {
                calls.add(ids);
                if (failure != null) {
                    throw failure;
                }
                List<String> urls = new ArrayList<String>();
                for (String id : ids) {
                    String[] u = urlsPerEngine.get(id);
                    if (u != null) {
                        urls.addAll(Arrays.asList(u));
                    }
                }
                return new Fake(urls.toArray(new String[0])).search(r, c, b);
            };
        }
    }

    private static BrowserSearchEngineSelection table(String... rows) {
        List<BrowserSearchEngineSelection.Entry> list = new ArrayList<BrowserSearchEngineSelection.Entry>();
        for (String row : rows) {
            String[] parts = row.split("@");
            list.add(new BrowserSearchEngineSelection.Entry(parts[0], true, 1, 0, Integer.parseInt(parts[1]),
                    Collections.singletonList("de"), Collections.<String>emptyList()));
        }
        return new BrowserSearchEngineSelection(list, null);
    }

    /** Rows "engine@priority@lang+lang" (an empty language part: no languages). */
    private static BrowserSearchEngineSelection languageTable(String... rows) {
        List<BrowserSearchEngineSelection.Entry> list = new ArrayList<BrowserSearchEngineSelection.Entry>();
        for (String row : rows) {
            String[] parts = row.split("@", -1);
            List<String> languages = parts[2].isEmpty() ? Collections.<String>emptyList()
                    : Arrays.asList(parts[2].split("\\+"));
            list.add(new BrowserSearchEngineSelection.Entry(parts[0], true, 1, 0, Integer.parseInt(parts[1]),
                    languages, Collections.<String>emptyList()));
        }
        return new BrowserSearchEngineSelection(list, null);
    }

    /** Records the language of every search it is asked; finds one result per search. */
    private static final class LanguageRecorder implements SearchStrategy {
        final List<String> languages = new ArrayList<String>();

        @Override
        public synchronized InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                                       SearchBudgetGate budget) {
            languages.add(String.valueOf(request.getLanguage()));
            return new Fake("https://" + request.getLanguage() + languages.size() + ".example/")
                    .search(request, cancellation, budget);
        }
    }

    private static final int ASK_EVERY_GROUP = 100;

    @Test
    public void aGoogleRowWithOtherLanguagesSearchesAgainInItsLanguage() throws Exception {
        LanguageRecorder google = new LanguageRecorder();
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                languageTable("google@0@de", "google@1@en"), ASK_EVERY_GROUP, google, null);
        assertEquals(Arrays.asList("google", "google:en"), strategy.stepNames());
        strategy.search(new InitialSearchRequest("q", 10, "de", ""), NOT_CANCELLED, BUDGET);
        assertEquals(Arrays.asList("de", "en"), google.languages);
    }

    @Test
    public void aRowKeepsTheSearchLanguageWhenItListsItElseTakesItsFirst() throws Exception {
        LanguageRecorder google = new LanguageRecorder();
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                languageTable("google@0@en+de"), ASK_EVERY_GROUP, google, null);
        strategy.search(new InitialSearchRequest("q", 10, "de", ""), NOT_CANCELLED, BUDGET);
        strategy.search(new InitialSearchRequest("q", 10, "fr", ""), NOT_CANCELLED, BUDGET);
        strategy.search(new InitialSearchRequest("q", 10, null, ""), NOT_CANCELLED, BUDGET);
        assertEquals(Arrays.asList("de", "en", "en"), google.languages);

        LanguageRecorder noLanguages = new LanguageRecorder();
        new BrowserEnginesSearchStrategy(languageTable("google@0@"), ASK_EVERY_GROUP, noLanguages, null)
                .search(new InitialSearchRequest("q", 10, "fr", ""), NOT_CANCELLED, BUDGET);
        assertEquals("a row without languages keeps the search's language",
                Collections.singletonList("fr"), noLanguages.languages);
    }

    @Test
    public void twoRowsLeadingToTheSameSearchAskItOnce() throws Exception {
        LanguageRecorder google = new LanguageRecorder();
        InitialSearchResult result = new BrowserEnginesSearchStrategy(
                languageTable("google@0@de+en", "google@1@de+fr"), ASK_EVERY_GROUP, google, null)
                .search(new InitialSearchRequest("q", 10, "de", ""), NOT_CANCELLED, BUDGET);
        assertEquals(Collections.singletonList("de"), google.languages);
        assertTrue(result.diagnostics.toString().contains("skipped, already searched in de"));
    }

    @Test
    public void googleRowsOfOneGroupRunOneAfterAnotherBesideThePageEngines() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger inGoogle = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxInGoogle = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean pagesRanBesideGoogle =
                new java.util.concurrent.atomic.AtomicBoolean();
        SearchStrategy google = (r, c, b) -> {
            maxInGoogle.accumulateAndGet(inGoogle.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inGoogle.decrementAndGet();
            return new Fake("https://" + r.getLanguage() + ".example/").search(r, c, b);
        };
        SearchStrategy pages = (r, c, b) -> {
            long deadline = System.currentTimeMillis() + 1000;
            while (inGoogle.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.yield();
            }
            pagesRanBesideGoogle.set(inGoogle.get() > 0);
            return new Fake("https://page.example/").search(r, c, b);
        };
        InitialSearchResult result = new BrowserEnginesSearchStrategy(
                languageTable("google@0@de", "google@0@en", "duckduckgo@0@de"), ASK_EVERY_GROUP, google, pages)
                .search(new InitialSearchRequest("q", 10, "de", ""), NOT_CANCELLED, BUDGET);
        assertEquals("the search browser serves one Google search at a time", 1, maxInGoogle.get());
        assertTrue("the research browser works beside it", pagesRanBesideGoogle.get());
        assertEquals(3, result.candidates.size());
    }

    @Test
    public void aPageEngineIsAskedOnceWhateverItsRowsLanguages() {
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                languageTable("duckduckgo@0@de", "duckduckgo@1@en"), ASK_EVERY_GROUP, null, new Fake());
        assertEquals("a search form takes no language", Collections.singletonList("page-engines"),
                strategy.stepNames());
    }

    private static String[] urls(String prefix, int n) {
        String[] urls = new String[n];
        for (int i = 0; i < n; i++) {
            urls[i] = "https://" + prefix + i + ".example/";
        }
        return urls;
    }

    @Test
    public void groupsRunByPriorityAndStopOnceEnoughIsUsable() throws Exception {
        GroupFake pages = new GroupFake();
        pages.urlsPerEngine.put("duckduckgo", urls("d", 3));
        pages.urlsPerEngine.put("bing", urls("b", 3));
        Fake google = new Fake(urls("g", 5));
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                table("duckduckgo@0", "bing@1", "google@2"), 5, google, pages);
        assertEquals(Arrays.asList(Arrays.asList("page-engines:duckduckgo"), Arrays.asList("page-engines:bing"),
                Arrays.asList("google")), strategy.groupNames());
        InitialSearchResult result = strategy.search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals("3 from DuckDuckGo are not enough, Bing makes 6", 2, pages.calls.size());
        assertEquals(0, google.calls);
        assertEquals(6, result.candidates.size());
    }

    @Test
    public void enginesOfOnePriorityAreAskedTogether() throws Exception {
        GroupFake pages = new GroupFake();
        pages.urlsPerEngine.put("duckduckgo", urls("d", 2));
        pages.urlsPerEngine.put("bing", urls("b", 2));
        Fake google = new Fake(urls("g", 2));
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                table("google@0", "duckduckgo@0", "bing@0"), 5, google, pages);
        assertEquals(Collections.singletonList(Arrays.asList("google", "page-engines:duckduckgo+bing")),
                strategy.groupNames());
        InitialSearchResult result = strategy.search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(1, google.calls);
        assertEquals(Collections.singletonList(Arrays.asList("duckduckgo", "bing")), pages.calls);
        assertEquals(6, result.candidates.size());
    }

    @Test
    public void anInterruptedGroupEndsItsStepsBeforeTheSearchReturnsAndRunsNoFurtherGroup() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean googleEnded = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.CountDownLatch googleStarted = new java.util.concurrent.CountDownLatch(1);
        SearchStrategy slowGoogle = (r, c, b) -> {
            googleStarted.countDown();
            long until = System.currentTimeMillis() + 300;
            while (System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // a browser call that does not react to interrupts
                }
            }
            googleEnded.set(true);
            return InitialSearchResult.empty(new ArrayList<String>());
        };
        GroupFake pages = new GroupFake();
        pages.urlsPerEngine.put("duckduckgo", urls("d", 1));
        pages.urlsPerEngine.put("bing", urls("b", 5));
        final BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                table("google@0", "duckduckgo@0", "bing@1"), 5, slowGoogle, pages);
        final java.util.concurrent.atomic.AtomicBoolean endedWhenReturned =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                strategy.search(REQUEST, NOT_CANCELLED, BUDGET);
            } catch (Exception ignored) {
                // the outcome itself does not matter here
            }
            endedWhenReturned.set(googleEnded.get());
        });
        caller.start();
        googleStarted.await();
        caller.interrupt();
        caller.join(5_000);
        assertTrue("the abandoned step must have ended before the one-search lock is released",
                endedWhenReturned.get());
        assertFalse("no further group after an interrupt",
                pages.calls.contains(Collections.singletonList("bing")));
    }

    @Test
    public void aBlockedGroupCountsNothingAndTheNextGroupRuns() throws Exception {
        GroupFake pages = new GroupFake();
        pages.failure = new IllegalStateException("challenge: unusual traffic");
        Fake google = new Fake(urls("g", 5));
        InitialSearchResult result = new BrowserEnginesSearchStrategy(
                table("duckduckgo@0", "google@1"), 5, google, pages).search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(1, google.calls);
        assertEquals(5, result.candidates.size());
        assertTrue(result.diagnostics.toString(), result.diagnostics.toString().contains("challenge"));
    }

    @Test
    public void shopAndSearchPagesAreNotUsableButGoogleWrappersAre() throws Exception {
        Fake google = new Fake("https://www.google.com/goto?url=x1", "https://www.google.com/goto?url=x2",
                "https://www.amazon.de/dp/123", "https://shop.example.de/products/a", "https://a.example/");
        GroupFake pages = new GroupFake();
        pages.urlsPerEngine.put("duckduckgo", urls("d", 3));
        new BrowserEnginesSearchStrategy(table("google@0", "duckduckgo@1"), 5, google, pages)
                .search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals("2 wrappers + 1 page are usable; the shop page is not; 3 < 5", 1, pages.calls.size());
    }

    @Test
    public void aSecondRowOfTheSameEngineNeverRepeatsTheQuery() throws Exception {
        Fake google = new Fake("https://a.example/");
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                table("google@1", "google@3"), 5, google, null);
        assertEquals(Collections.singletonList(Collections.singletonList("google")), strategy.groupNames());
        strategy.search(REQUEST, NOT_CANCELLED, BUDGET);
        assertEquals(1, google.calls);
    }

    @Test
    public void rowsOfEnginesWithoutDriverAreSkipped() {
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                table("mojeek@0", "duckduckgo@1"), 5, null, new GroupFake());
        assertEquals(Collections.singletonList(Collections.singletonList("page-engines:duckduckgo")),
                strategy.groupNames());
    }

    @Test
    public void theDefaultTriesStartpageThenDuckDuckGoThenGoogle() {
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create().navigation.engineSelection,
                new Fake(urls("g", 5)), new GroupFake());
        assertEquals(Arrays.asList(Collections.singletonList("page-engines:startpage"),
                Collections.singletonList("page-engines:duckduckgo"), Collections.singletonList("google")),
                strategy.groupNames());
    }

    private static BrowserSearchEngineSelection selection(EngineAcquisitionMode mode, String... entries) {
        List<BrowserSearchEngineSelection.Entry> list = new ArrayList<BrowserSearchEngineSelection.Entry>();
        for (String entry : entries) {
            String[] parts = entry.split(":");
            list.add(new BrowserSearchEngineSelection.Entry(parts[0], "on".equals(parts[1])));
        }
        assertTrue(BrowserSearchEngineCatalog.byId("google") != null);
        return new BrowserSearchEngineSelection(list, mode);
    }

    private static class Fake implements SearchStrategy {
        private final List<String> urls;
        int calls;

        Fake(String... urls) {
            this.urls = Arrays.asList(urls);
        }

        @Override
        public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                          SearchBudgetGate budget) {
            calls++;
            List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
            int rank = 1;
            for (String url : urls) {
                candidates.add(new SearchResultCandidate("c" + rank, "s", url, url, "t", "", "", rank++, "", "",
                        1.0, 1.0, Collections.emptyList()));
            }
            return new InitialSearchResult(candidates, Collections.<String>emptyList(),
                    Collections.<SearchChallengeState>emptyList(), Collections.<String>emptyList());
        }
    }
}
