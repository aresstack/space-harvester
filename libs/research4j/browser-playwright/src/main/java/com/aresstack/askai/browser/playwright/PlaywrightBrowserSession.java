package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageReadiness;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.aresstack.askai.browser.WebSearchResult;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The productive Playwright-backed {@link BrowserSession}. The URL policy is applied TWICE per navigation:
 * before navigating and again on the FINAL URL after redirects — a public URL that redirects to a private or
 * loopback target fails the call and the page is abandoned. Snapshots stay small and deterministic (text
 * capped at {@link BrowserLimits#getMaxTextChars()}, links at {@link BrowserLimits#getMaxLinks()} with
 * per-snapshot stable ids {@code link-1..n}); old link ids are invalidated by the next navigation.
 * {@code web_search} navigates to the configured provider URL — without one it fails honestly.
 */
final class PlaywrightBrowserSession implements BrowserSession, EngineGroupSession {

    private final PlaywrightDriver driver;
    private final UrlSafetyPolicy policy;
    private final BrowserLimits limits;
    private final String searchUrlTemplate;
    private WebSearchProvider searchProvider;
    private List<BrowserLink> currentLinks = Collections.emptyList();
    private boolean hasPage;
    /** Reads the PDF documents the browser reaches but cannot read as pages. */
    private final PdfPageReader documents;
    /**
     * The current page when it is a read PDF document (the tab shows its viewer, or kept its page for a download),
     * else null. Every navigation drops it, and so does a tab that shows something else since ({@link #shownDocument}).
     */
    private PlaywrightPageState currentDocument;
    /** MANUAL_CHALLENGE_PENDING: the domain family whose parked challenge waits for the user, or null. */
    private String challengeFamily;
    private String challengeUrl;
    /** Monotonic per navigation — the stale-reference guard of every rendered-page snapshot. */
    private long snapshotGeneration;

    /** The typed configuration contract; defaults come ONLY from LegacyBrowserSearchDefaults. */
    private final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings;

    /** Public-suffix aware domain families; tests may inject a fake (e.g. host:port for local worlds). */
    private com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys =
            new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver();

    PlaywrightBrowserSession(PlaywrightDriver driver, UrlSafetyPolicy policy, BrowserLimits limits,
                             String searchUrlTemplate, WebSearchProvider searchProvider) {
        this(driver, policy, limits, searchUrlTemplate, searchProvider,
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create());
    }

    PlaywrightBrowserSession(PlaywrightDriver driver, UrlSafetyPolicy policy, BrowserLimits limits,
                             String searchUrlTemplate, WebSearchProvider searchProvider,
                             com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings) {
        this.driver = driver;
        this.policy = policy;
        this.limits = limits;
        this.documents = new PdfPageReader(limits, policy);
        this.settings = settings;
        this.searchUrlTemplate = searchUrlTemplate == null || searchUrlTemplate.trim().isEmpty()
                ? null : searchUrlTemplate.trim();
        this.searchProvider = searchProvider == null
                ? new WebSearchProvider.OrganicResultSearchProvider(settings) : searchProvider;
    }

    /**
     * Inject a different {@link com.aresstack.askai.browser.domain.DomainKeyResolver} (tests/dev modes,
     * e.g. host:port keys for local multi-server worlds). The default organic extraction is rebuilt on
     * the same domain semantics; a custom search provider stays untouched.
     */
    void setDomainKeyResolver(com.aresstack.askai.browser.domain.DomainKeyResolver resolver) {
        if (resolver != null) {
            this.domainKeys = resolver; // capture + engine policy pick it up on the next search
        }
    }

    public BrowserBackendKind getBackendKind() {
        return BrowserBackendKind.PLAYWRIGHT_SIDECAR;
    }

    /** Test seam: search with exactly these engines (tests use literal-IP URLs, no DNS). */
    private java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> engineOverride;

    void setSearchEngines(java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> engines) {
        this.engineOverride = engines;
    }

    /**
     * The engines this search visits, in order. Precedence is the documented one: an explicit
     * {@code --search-url} is a dev/test escape hatch and stands ALONE — it never falls through to
     * public engines, because a local world must stay local. Otherwise the user's enabled engines
     * decide, and nothing here privileges one of them.
     */
    private java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> enginesToVisit() {
        if (engineOverride != null) {
            return engineOverride;
        }
        if (callEngines != null) {
            return callEngines;
        }
        if (searchUrlTemplate != null) {
            return java.util.Collections.singletonList(
                    com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog.custom(searchUrlTemplate));
        }
        // Google is searched by AskAI's separate search browser, never by this (research) browser.
        java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> pageEngines =
                new java.util.ArrayList<com.aresstack.askai.browser.search.engine.BrowserSearchEngine>();
        for (com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine
                : settings.navigation.engineSelection.resolvedEnabledEngines()) {
            if (!com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog.runsInSearchBrowser(
                    engine.getId())) {
                pageEngines.add(engine);
            }
        }
        return pageEngines;
    }

    /** The engines named by the running web_search call, or null (then the settings' enabled engines). */
    private java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> callEngines;

    /** The acquisition mode of the running call when it searches one priority group, else null (the settings'). */
    private com.aresstack.askai.browser.search.engine.EngineAcquisitionMode callMode;

    /** Work that may fail like a browser call. */
    public interface BrowserCall<T> {
        T run() throws BrowserException;
    }

    /**
     * Runs {@code call} (a web_search_prepare) for ONE priority group of Search → Engines: exactly the named engines,
     * in the order given, and every one of them ({@code ALL_ENABLED} within the group), whatever the settings' order
     * and mode. The group's fallback to the next priority is the web search tool's business, not this browser's.
     */
    @Override
    public <T> T withEngineGroup(List<String> engineIds, BrowserCall<T> call) throws BrowserException {
        if (searchUrlTemplate != null) {
            throw new BrowserException("This browser runtime searches only its --search-url override; "
                    + "it does not take the engines " + engineIds + ".");
        }
        callEngines = selectEngines(engineIds);
        callMode = com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.ALL_ENABLED;
        try {
            return call.run();
        } finally {
            callEngines = null;
            callMode = null;
        }
    }

    /**
     * web_search with its optional {@code engines} parameter (as Argos' web_search): exactly the named catalog
     * engines, in the order given, each once, for this call only; without it (null) the engines enabled in the
     * settings. A named engine need not be enabled in the settings, but it must exist: a wrong id is refused, never
     * corrected or replaced. A {@code --search-url} dev override stands alone and refuses a selection.
     */
    @Override
    public WebSearchResult search(String query, List<String> engineIds) throws BrowserException {
        if (engineIds == null) {
            return search(query);
        }
        if (searchUrlTemplate != null) {
            throw new BrowserException("This browser runtime searches only its --search-url override; "
                    + "it does not take the engines " + engineIds + ".");
        }
        callEngines = selectEngines(engineIds);
        try {
            return search(query);
        } finally {
            callEngines = null;
        }
    }

    static java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> selectEngines(
            List<String> engineIds) throws BrowserException {
        StringBuilder known = new StringBuilder();
        for (com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine
                : com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog.engines()) {
            known.append(known.length() == 0 ? "" : ", ").append(engine.getId());
        }
        if (engineIds.isEmpty()) {
            throw new BrowserException("engines is empty. Name at least one engine (" + known
                    + ") or leave engines out to search with the engines enabled in the settings.");
        }
        java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> selected =
                new ArrayList<com.aresstack.askai.browser.search.engine.BrowserSearchEngine>();
        for (String raw : engineIds) {
            String id = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
            com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine =
                    com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog.byId(id);
            if (engine == null) {
                throw new BrowserException("Unknown search engine: \"" + (raw == null ? "null" : raw.trim())
                        + "\" (available: " + known + ")");
            }
            if (!selected.contains(engine)) {
                selected.add(engine);
            }
        }
        return selected;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public WebSearchResult search(String query) throws BrowserException {
        if (policy.isRefusingSearchEngines()) {
            com.aresstack.askai.browser.EngineHostRefusedException refused =
                    new com.aresstack.askai.browser.EngineHostRefusedException("search engines", "web_search");
            logEngineRefusal(refused);
            throw refused;
        }
        // web_search: run the SHARED engine navigation; the consumer extracts each captured page. With
        // per-engine result-page counts, one engine may deliver SEVERAL pages — the items accumulate
        // (deduplicated by target URL) up to the configured result limit.
        final List<com.aresstack.askai.browser.WebSearchItem>[] organic = new List[]{null};
        final java.util.Set<String> seenTargets = new java.util.HashSet<String>();
        EngineNavigation nav = navigateAndCaptureSearchEngines(query, new CapturedPageConsumer() {
            public CapturedPageVerdict accept(
                    com.aresstack.askai.browser.render.RenderedPageDocument document,
                    String host,
                    List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts) {
                com.aresstack.askai.browser.search.SearchResultExtractionResult extraction =
                        searchProvider.extract(document);
                switch (extraction.outcome) {
                    case ORGANIC_RESULTS:
                        List<com.aresstack.askai.browser.WebSearchItem> items =
                                organic[0] != null ? organic[0]
                                        : new ArrayList<com.aresstack.askai.browser.WebSearchItem>();
                        int before = items.size();
                        for (com.aresstack.askai.browser.search.SearchResultCandidate candidate
                                : extraction.candidates) {
                            if (items.size() >= settings.navigation.searchResultLimit) {
                                break;
                            }
                            // askai #41: an unresolved redirect is opened through its raw href.
                            if (!seenTargets.add(candidate.navigationUrl())) {
                                continue; // engines repeat hits across result pages — carried once
                            }
                            items.add(new com.aresstack.askai.browser.WebSearchItem(
                                    String.valueOf(items.size() + 1), candidate.title,
                                    candidate.navigationUrl(), candidate.snippet));
                        }
                        attempts.add(attempt(host, com.aresstack.askai.browser
                                .LegacySearchAttemptOutcome.ORGANIC_RESULTS,
                                (items.size() - before) + " candidates"));
                        organic[0] = items;
                        return CapturedPageVerdict.DELIVERED;
                    case NO_ORGANIC_RESULTS:
                        attempts.add(attempt(host, com.aresstack.askai.browser
                                .LegacySearchAttemptOutcome.NO_ORGANIC_RESULTS,
                                bounded(failureDiagnostic(document, extraction))));
                        return CapturedPageVerdict.EMPTY;
                    default:
                        // An ununderstood layout is an extraction FAILURE, never an empty engine.
                        String why = failureDiagnostic(document, extraction);
                        attempts.add(attempt(host, com.aresstack.askai.browser
                                .LegacySearchAttemptOutcome.EXTRACTION_FAILED, bounded(why)));
                        // Results wrapped in opaque redirects (Google /goto) stay so on every next page: asking
                        // for more pages of them only costs time.
                        return why.startsWith(OPAQUE_REDIRECTS) ? CapturedPageVerdict.EMPTY
                                : CapturedPageVerdict.UNUSABLE;
                }
            }
        });
        if (organic[0] != null) {
            return new WebSearchResult(organic[0], nav.providerHosts, nav.attempts);
        }
        if (!nav.anyEngineReached && challengeFamily == null && nav.lastFailure != null) {
            throw nav.lastFailure; // nothing was reachable at all — a plain technical failure
        }
        // HARD INVARIANT: no path ever returns the SERP's raw anchors as results.
        return new WebSearchResult(
                Collections.<com.aresstack.askai.browser.WebSearchItem>emptyList(),
                nav.providerHosts, nav.attempts);
    }

    /**
     * What one captured result page turned out to be. DELIVERED and EMPTY are answers; UNUSABLE is the
     * page's problem, not the engine's — a layout the mechanical analysis did not understand may still
     * be rescued downstream (AI layout repair, link harvest), so it must not end the pagination the
     * user configured.
     */
    enum CapturedPageVerdict {
        /** Usable organic results — the engine delivered; deeper pages widen the same answer. */
        DELIVERED,
        /** EXPLICITLY no results ("no results found" markers) — deeper pages cannot have more. */
        EMPTY,
        /** Not understood/not extracted here — keep fetching the configured pages regardless. */
        UNUSABLE
    }

    /**
     * The captured-page hook for the shared engine navigation. It answers ONE question: what did this
     * page deliver? Whether that ends the search is the acquisition mode's decision, not the consumer's.
     */
    interface CapturedPageConsumer {
        CapturedPageVerdict accept(com.aresstack.askai.browser.render.RenderedPageDocument document,
                       String host,
                       List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts);
    }

    /** The shared outcome of one engine-navigation pass. */
    static final class EngineNavigation {
        final List<String> providerHosts;
        final List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts;
        final List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges;
        final boolean anyEngineReached;
        final BrowserException lastFailure;

        EngineNavigation(List<String> providerHosts,
                List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts,
                List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges,
                boolean anyEngineReached, BrowserException lastFailure) {
            this.providerHosts = providerHosts;
            this.attempts = attempts;
            this.challenges = challenges;
            this.anyEngineReached = anyEngineReached;
            this.lastFailure = lastFailure;
        }
    }

    /**
     * The SINGLE engine-navigation loop (engine order, endpoint alternatives, domain-family locks,
     * consent, challenge detection, provider hosts, capture, per-engine attempts). web_search and
     * web_search_prepare both drive it. The consumer says whether a captured page DELIVERED; the
     * acquisition mode says whether that ends the run.
     */
    EngineNavigation navigateAndCaptureSearchEngines(String query, CapturedPageConsumer consumer)
            throws BrowserException {
        if (policy.isRefusingSearchEngines()) { // web_search and web_search_prepare both start here
            com.aresstack.askai.browser.EngineHostRefusedException refused =
                    new com.aresstack.askai.browser.EngineHostRefusedException("search engines", "engine search");
            logEngineRefusal(refused);
            throw refused;
        }
        List<BrowserLink> linksBefore = currentLinks;
        boolean hadPage = hasPage;
        searchTabsUsed = false;
        try {
            return navigateAndCapture(query, consumer);
        } finally {
            searchTabs = null;
            driver.closeSearchTabs(); // a parked challenge tab stays open; the session returns to its own page
            if (searchTabsUsed) {
                // The result pages were read in the tabs that are gone now: links and "has a page" are again those
                // of the page the session is back on, so a follow_link never resolves an id of a closed tab.
                currentLinks = linksBefore;
                hasPage = hadPage;
            }
        }
    }

    /** Whether the running search opened engine tabs (then the session's own page is current again afterwards). */
    private boolean searchTabsUsed;

    /**
     * The engines' tabs of the running search (issue #22, Argos' web_search): every engine that is asked through its
     * search form got a tab of its own, and all of them were typed into at the same time before the first result page
     * is evaluated. Null outside a search, or when the driver has no tabs.
     */
    private SearchTabs searchTabs;

    /** Per engine id: its tab, the start page's load problem (null = loaded) and what typing the query did. */
    private static final class SearchTabs {
        final java.util.Map<String, Integer> tabOf = new java.util.HashMap<String, Integer>();
        final java.util.List<String> loadProblems;
        final java.util.List<String> submitted;

        SearchTabs(java.util.List<String> loadProblems, java.util.List<String> submitted) {
            this.loadProblems = loadProblems;
            this.submitted = submitted;
        }
    }

    /**
     * Opens the start page of every form engine in a tab of its own (one parallel fan-out) and types the query into
     * all of them at the same time, Enter in each. Engines asked by URL template (the dev/test override) are not part
     * of it. Null when there is no form engine or the driver has no tabs.
     */
    private SearchTabs openAndTypeSearchTabs(
            java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> formEngines, String query)
            throws BrowserException {
        if (formEngines.isEmpty()) {
            return null;
        }
        java.util.List<String> homes = new ArrayList<String>();
        for (com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine : formEngines) {
            homes.add(engine.formOf(0).getHomeUrl());
        }
        searchTabsUsed = true;
        try {
            java.util.List<String> loaded = driver.openSearchTabs(homes);
            java.util.List<java.util.List<String>> fields = new ArrayList<java.util.List<String>>();
            for (int i = 0; i < formEngines.size(); i++) {
                fields.add(loaded.get(i) == null ? formEngines.get(i).formOf(0).getSearchBoxSelectors() : null);
            }
            SearchTabs tabs = new SearchTabs(loaded, new ArrayList<String>(driver.submitSearchInTabs(fields, query)));
            for (int i = 0; i < formEngines.size(); i++) {
                tabs.tabOf.put(formEngines.get(i).getId(), i);
            }
            System.err.println("[engines] search tabs " + describe(formEngines) + " loaded=" + loaded
                    + " typed=" + tabs.submitted);
            return tabs;
        } catch (BrowserException noTabs) {
            if (noTabs.getMessage() != null && noTabs.getMessage().contains("BROWSER_CLOSED")) {
                throw noTabs; // the user closed the window: that ends the search, it is no missing tab
            }
            System.err.println("[engines] search tabs unavailable: " + noTabs.getMessage());
            return null;
        }
    }

    /**
     * The form engines whose start pages are opened and typed into together before the first result page is read:
     * with ALL_ENABLED every engine the endpoint budget reaches (each first endpoint counts as one opened endpoint, as
     * it would in the loop), with FIRST_USABLE only the first one, because the engines behind it are asked only when
     * it does not deliver. An engine of a challenged family is never asked.
     */
    private java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> enginesTypedTogether(
            java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> engines,
            com.aresstack.askai.browser.search.engine.EngineAcquisitionMode mode) {
        java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> together =
                new ArrayList<com.aresstack.askai.browser.search.engine.BrowserSearchEngine>();
        int budget = settings.navigation.maximumEngineAttempts;
        for (com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine : engines) {
            if (budget <= 0) {
                break;
            }
            if (domainKeys.resolve(engine.getEndpointTemplates().get(0)).getRegistrableDomain()
                    .equals(challengeFamily)) {
                continue; // the loop records the lock and opens nothing for it
            }
            budget--;
            if (engine.usesSearchForms()) {
                together.add(engine);
            }
            if (mode == com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.FIRST_USABLE) {
                break;
            }
        }
        return together;
    }

    /**
     * Result page 1 of a form endpoint: the engine's tab after its query was typed (endpoint 0, typed together with
     * the other engines), or its fallback start page loaded in the same tab and typed into alone. A start page whose
     * field is covered gets one consent dismissal and one more try. A start page that shows a challenge is returned
     * as it is, so the challenge handling below sees it. Null (with the attempt recorded) when the field could not be
     * used: never a result URL instead.
     */
    private BrowserPageSnapshot firstResultPageThroughForm(
            com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine, int endpointIndex,
            com.aresstack.askai.browser.search.engine.BrowserSearchEngine.SearchForm form, String query,
            String engineHost, List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts)
            throws BrowserException {
        Integer tab = searchTabs == null ? null : searchTabs.tabOf.get(engine.getId());
        if (tab == null && endpointIndex == 0) {
            // Not typed together with the others (FIRST_USABLE asks the next engine only now): its own tab, typed alone.
            SearchTabs own = openAndTypeSearchTabs(
                    java.util.Collections.singletonList(engine), query);
            if (own != null) {
                searchTabs = own;
                tab = own.tabOf.get(engine.getId());
            }
        }
        if (tab == null) {
            attempts.add(attempt(engineHost,
                    com.aresstack.askai.browser.LegacySearchAttemptOutcome.NAVIGATION_FAILED,
                    "no tab for the engine's start page"));
            return null;
        }
        String typed;
        if (endpointIndex == 0) {
            String loadProblem = searchTabs.loadProblems.get(tab);
            if (loadProblem != null) {
                attempts.add(attempt(engineHost,
                        com.aresstack.askai.browser.LegacySearchAttemptOutcome.NAVIGATION_FAILED, loadProblem));
                return null;
            }
            typed = searchTabs.submitted.get(tab);
        } else {
            checkedSnapshot(driver.openInSearchTab(tab, form.getHomeUrl()));
            typed = typeInto(tab, form, query);
        }
        if (!"submitted".equals(typed)) {
            driver.useSearchTab(tab);
            if (driver.tryDismissConsent().startsWith("clicked")) {
                typed = typeInto(tab, form, query);
            }
        }
        if ("submitted".equals(typed)) {
            return checkedSnapshot(driver.useSearchTab(tab));
        }
        BrowserPageSnapshot start = checkedSnapshot(driver.useSearchTab(tab));
        if (driver.challengePresent()) {
            return start; // reported as the challenge it is
        }
        attempts.add(attempt(engineHost,
                com.aresstack.askai.browser.LegacySearchAttemptOutcome.SEARCH_BOX_UNAVAILABLE, typed));
        return null;
    }

    /** Types the query into ONE search tab (a fallback endpoint or a retry after a consent banner). */
    private String typeInto(int tab, com.aresstack.askai.browser.search.engine.BrowserSearchEngine.SearchForm form,
                            String query) throws BrowserException {
        java.util.List<java.util.List<String>> fields = new ArrayList<java.util.List<String>>();
        for (int i = 0; i <= tab; i++) {
            fields.add(i == tab ? form.getSearchBoxSelectors() : null);
        }
        return driver.submitSearchInTabs(fields, query).get(tab);
    }

    private EngineNavigation navigateAndCapture(String query, CapturedPageConsumer consumer)
            throws BrowserException {
        java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> engines = enginesToVisit();
        if (engines.isEmpty()) {
            throw new BrowserException("No search engine is enabled for the Playwright backend "
                    + "(enable one in the search settings, or start the browser runtime with "
                    + "--search-url=<template containing {query}>).");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new BrowserException("Empty search query.");
        }
        String encoded = encode(query.trim());
        com.aresstack.askai.browser.search.engine.EngineAcquisitionMode mode = callMode != null ? callMode
                : settings.navigation.engineSelection.getMode();
        // WHICH engines this search is really using, and why. A configured order that silently loses to
        // a leftover override is invisible in the result — the provider hosts only ever show what was
        // actually opened, never what was supposed to be.
        System.err.println("[engines] searchUrlOverride=" + (searchUrlTemplate == null ? "empty" : "set")
                + " engineOverride=" + (engineOverride == null ? "empty" : "set")
                + " acquisitionMode=" + mode + " resolvedEngines=" + describe(engines));
        List<String> providerHosts = new ArrayList<String>();
        List<com.aresstack.askai.browser.LegacySearchEngineAttemptResult> attempts =
                new ArrayList<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>();
        List<com.aresstack.askai.browser.search.repair.SearchChallengeState> challenges =
                new ArrayList<com.aresstack.askai.browser.search.repair.SearchChallengeState>();
        boolean anyEngineReached = false;
        BrowserException lastFailure = null;
        int endpointsOpened = 0;
        // Issue #22: all form engines at once, each in its own tab, typed into side by side (Argos' web_search). Their
        // first endpoints are opened now, so they are counted against the budget now and not again in the loop.
        searchTabs = openAndTypeSearchTabs(enginesTypedTogether(engines, mode), query.trim());
        java.util.Set<String> prepaid = new java.util.HashSet<String>(
                searchTabs == null ? java.util.Collections.<String>emptySet() : searchTabs.tabOf.keySet());
        endpointsOpened += prepaid.size();
        for (com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine : engines) {
            // An engine's endpoints are alternative ways to ask the SAME provider: the first one that
            // answers ends this engine's turn, whatever the acquisition mode is. Only whether the NEXT
            // engine is visited at all is a policy question.
            boolean engineDelivered = false;
            java.util.List<String> endpointTemplates = engine.getEndpointTemplates();
            // The per-engine RESULT-PAGE count is the user's setting (default 3); pages do NOT burn the
            // endpoint budget — maximumEngineAttempts keeps counting transports, not depth.
            int resultPages = settings.navigation.engineSelection == null
                    ? com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection
                            .Entry.DEFAULT_RESULT_PAGES
                    : settings.navigation.engineSelection.resultPagesFor(engine.getId());
            // The per-engine request delay is the user's setting (seconds, default 0 = off): an extra
            // pause before every request to this engine AFTER the first — on top of the natural
            // evaluation time — so a touchy provider never sees rapid-fire clicks.
            int engineDelayMillis = settings.navigation.engineSelection == null
                    ? com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection
                            .Entry.DEFAULT_DELAY_MILLIS
                    : settings.navigation.engineSelection.delayMillisFor(engine.getId());
            boolean engineRequestedBefore = false;
            for (int endpointIndex = 0; endpointIndex < endpointTemplates.size(); endpointIndex++) {
                String template = endpointTemplates.get(endpointIndex);
                boolean alreadyOpened = endpointIndex == 0 && prepaid.remove(engine.getId());
                if (!alreadyOpened && endpointsOpened >= settings.navigation.maximumEngineAttempts) {
                    break;
                }
                String engineHost = com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver
                        .hostOf(template);
                String engineFamily = domainKeys.resolve(template).getRegistrableDomain();
                if (engineFamily.equals(challengeFamily)) {
                    // MANUAL_CHALLENGE_PENDING: no new search, no reload, no retry on this family.
                    attempts.add(attempt(engineHost,
                            com.aresstack.askai.browser.LegacySearchAttemptOutcome.CHALLENGE_PENDING,
                            "family locked"));
                    continue;
                }
                if (!alreadyOpened) {
                    endpointsOpened++;
                }
                boolean endpointReached = false;
                // DELIBERATELY SEQUENTIAL: each result page is fetched, captured and fully EVALUATED
                // (extraction — and behind prepare, the AI judgement) before the NEXT page is requested.
                // That evaluation time is the natural pacing between clicks, so the engine never sees
                // rapid-fire page requests and answers with a CAPTCHA. Never prefetch result pages.
                com.aresstack.askai.browser.search.engine.BrowserSearchEngine.SearchForm form =
                        engine.formOf(endpointIndex);
                // The results of every page read of this endpoint: a page that comes back (a pager click that loaded
                // nothing new) is never read and counted again as a further page. Judged by its results, not by its
                // address: DuckDuckGo's HTML and Lite endpoints page by POST under one address.
                java.util.Set<java.util.Set<String>> resultPagesRead = new java.util.HashSet<java.util.Set<String>>();
                for (int resultPage = 1; resultPage <= resultPages; resultPage++) {
                    String pageUrl = form != null ? null : engine.pageUrl(endpointIndex, encoded, resultPage);
                    if (form == null && pageUrl == null) {
                        break; // this endpoint cannot address deeper result pages
                    }
                    if (form != null && resultPage > 1 && form.getNextPageSelectors().isEmpty()) {
                        break; // this engine has no pager we know
                    }
                    if (engineRequestedBefore && engineDelayMillis > 0) {
                        paceBeforeRepeatRequest(engineDelayMillis);
                    }
                    engineRequestedBefore = true;
                    BrowserPageSnapshot page;
                    try {
                        if (form == null) {
                            page = open(pageUrl);
                        } else if (resultPage == 1) {
                            // Asked like a person: the query typed into the start page's field, never a result URL.
                            page = firstResultPageThroughForm(engine, endpointIndex, form, query.trim(), engineHost,
                                    attempts);
                            if (page == null) {
                                anyEngineReached = true;
                                break;
                            }
                        } else if (driver.clickNextPage(form.getNextPageSelectors())) {
                            page = checkedSnapshot(driver.current()); // the engine's own pager, clicked for real
                        } else {
                            break; // no further result page offered
                        }
                    } catch (BrowserException engineUnreachable) {
                        if (engineUnreachable.getMessage() != null
                                && engineUnreachable.getMessage().contains("BROWSER_CLOSED")) {
                            // The USER closed the window: no other engine can help, and degrading
                            // their stop into an ATTEMPT line would launder it into a technical end.
                            throw engineUnreachable;
                        }
                        lastFailure = engineUnreachable;
                        attempts.add(attempt(engineHost,
                                com.aresstack.askai.browser.LegacySearchAttemptOutcome.NAVIGATION_FAILED,
                                engineUnreachable.getMessage()));
                        break; // page 1: transport down (try the next); page n: out of pages
                    }
                    anyEngineReached = true;
                    endpointReached = true;
                    // SERP guards, encapsulated here (never in the research loop): consent first, then
                    // the challenge check — a challenge page is never read as a result page.
                    if (driver.tryDismissConsent().startsWith("clicked")) {
                        page = checkedSnapshot(driver.current()); // re-read without the consent overlay
                    }
                    com.aresstack.askai.browser.domain.DomainIdentity pageIdentity =
                            domainKeys.resolve(page.getUrl());
                    String host = pageIdentity.getHost();
                    if (driver.challengePresent()) {
                        if (challengeFamily == null && driver.parkChallenge()) {
                            challengeFamily = pageIdentity.getRegistrableDomain();
                            challengeUrl = page.getUrl();
                        }
                        challenges.add(new com.aresstack.askai.browser.search.repair.SearchChallengeState(
                                pageIdentity.getRegistrableDomain(), page.getUrl()));
                        attempts.add(attempt(host,
                                com.aresstack.askai.browser.LegacySearchAttemptOutcome.CHALLENGE_PENDING,
                                "manual challenge"));
                        break; // the user solves it manually; no deeper pages on a challenged family
                    }
                    // Transit semantics only exist for PUBLIC engines; an IP/localhost dev world has no
                    // engine navigation to hide, so it never marks itself as transit.
                    if (!host.isEmpty() && !providerHosts.contains(host)
                            && pageIdentity.getHostKind()
                                    == com.aresstack.askai.browser.domain.HostKind.REGISTERED_NAME) {
                        providerHosts.add(host);
                    }
                    // A3: judge the STRUCTURED rendered page — container hierarchy, repeated result
                    // blocks, primary links, snippets. Navigation targets are the RESOLVED direct URLs.
                    com.aresstack.askai.browser.render.RenderedPageDocument document;
                    try {
                        document = driver.captureRenderedPage(domainKeys, ++snapshotGeneration);
                    } catch (BrowserException captureFailed) {
                        attempts.add(attempt(host,
                                com.aresstack.askai.browser.LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                                bounded(captureFailed.getMessage())));
                        break;
                    }
                    if (document == null) {
                        attempts.add(attempt(host,
                                com.aresstack.askai.browser.LegacySearchAttemptOutcome.EXTRACTION_FAILED,
                                "structured page capture unavailable"));
                        break;
                    }
                    java.util.Set<String> results = externalTargets(document);
                    if (!results.isEmpty() && !resultPagesRead.add(results)) {
                        attempts.add(attempt(host,
                                com.aresstack.askai.browser.LegacySearchAttemptOutcome.NAVIGATION_FAILED,
                                "result page " + resultPage + " not reached: the pager showed an earlier page again ("
                                        + bounded(page.getUrl()) + ")"));
                        break; // the same page again is no further page; reading it twice only duplicates hits
                    }
                    // The consumer states a FACT — what did this page deliver — and the policy below
                    // decides what that means for the run. A delivering page widens the same answer
                    // with the NEXT page; only an EXPLICITLY empty page ends this engine's pagination.
                    // An ununderstood layout does NOT: the user configured N pages, and downstream
                    // rescue (AI layout repair, link harvest) works per captured page.
                    CapturedPageVerdict verdict = consumer.accept(document, host, attempts);
                    if (verdict == CapturedPageVerdict.DELIVERED) {
                        engineDelivered = true;
                    } else if (verdict == CapturedPageVerdict.EMPTY) {
                        break;
                    }
                }
                if (endpointReached && engineDelivered) {
                    break; // this transport answered — the remaining endpoints are its fallbacks
                }
            }
            if (engineDelivered && mode == com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.FIRST_USABLE) {
                break; // an engine answered; the ones behind it are the safety net, not extra work
            }
            if (endpointsOpened >= settings.navigation.maximumEngineAttempts && prepaid.isEmpty()) {
                break;
            }
        }
        return new EngineNavigation(providerHosts, attempts, challenges, anyEngineReached,
                lastFailure);
    }

    /**
     * What a result page links to outside the engine: the resolved targets of its external links. Two reads of the same
     * page give the same set even where the engine's own wrapper or tracking links change per request.
     */
    static java.util.Set<String> externalTargets(com.aresstack.askai.browser.render.RenderedPageDocument document) {
        java.util.Set<String> targets = new java.util.TreeSet<String>();
        for (com.aresstack.askai.browser.render.RenderedLinkDescriptor link : document.links) {
            if (link.domainClassification == com.aresstack.askai.browser.domain.DomainClassification.EXTERNAL_DOMAIN
                    && link.resolvedTargetUrl != null && !link.resolvedTargetUrl.isEmpty()) {
                targets.add(link.resolvedTargetUrl);
            }
        }
        return targets;
    }

    /** The capture warning of a page whose result links are opaque engine wrappers ({@link OpaqueRedirectStrategy}). */
    static final String OPAQUE_REDIRECTS = "OPAQUE_REDIRECTS:";

    /**
     * Why a captured result page delivered nothing. When the engine wrapped its results in opaque redirects (Google's
     * {@code /goto}), that is named first: those links are left unresolved on purpose ({@link OpaqueRedirectStrategy}),
     * so the empty page is explained instead of looking like an unknown layout.
     */
    static String failureDiagnostic(com.aresstack.askai.browser.render.RenderedPageDocument document,
                                    com.aresstack.askai.browser.search.SearchResultExtractionResult extraction) {
        String reason = firstDiagnostic(extraction);
        for (String warning : document.captureWarnings) {
            if (warning.startsWith(OPAQUE_REDIRECTS)) {
                return warning + (reason.isEmpty() ? "" : "; " + reason);
            }
        }
        return reason;
    }

    private static String firstDiagnostic(
            com.aresstack.askai.browser.search.SearchResultExtractionResult extraction) {
        return extraction.diagnostics.isEmpty() ? "" : extraction.diagnostics.get(
                extraction.diagnostics.size() - 1);
    }

    /** {@code [duckduckgo: html.duckduckgo.com, lite.duckduckgo.com][bing: www.bing.com]} */
    private static String describe(java.util.List<com.aresstack.askai.browser.search.engine.BrowserSearchEngine> engines) {
        StringBuilder sb = new StringBuilder();
        for (com.aresstack.askai.browser.search.engine.BrowserSearchEngine engine : engines) {
            sb.append('[').append(engine.getId()).append(": ");
            for (int i = 0; i < engine.getEndpointTemplates().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver
                        .hostOf(engine.getEndpointTemplates().get(i)));
            }
            sb.append(']');
        }
        return sb.length() == 0 ? "[]" : sb.toString();
    }

    /** Attempt diagnostics are bounded by the diagnostics settings — never unbounded dumps. */
    private String bounded(String text) {
        String value = text == null ? "" : text;
        int limit = settings.diagnostics.maximumTextExcerptCharacters;
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    private static com.aresstack.askai.browser.LegacySearchEngineAttemptResult attempt(
            String engineHost, com.aresstack.askai.browser.LegacySearchAttemptOutcome outcome,
            String diagnostic) {
        return new com.aresstack.askai.browser.LegacySearchEngineAttemptResult(engineHost, outcome,
                diagnostic == null ? "" : diagnostic);
    }

    public BrowserPageSnapshot open(String url) throws BrowserException {
        checkPolicy(url);
        currentDocument = null; // superseded by this navigation, whatever it brings
        return checkedSnapshot(readDocument(driver.open(url)));
    }

    public BrowserPageSnapshot currentPage() throws BrowserException {
        requirePage();
        return checkedSnapshot(current());
    }

    @Override
    public BrowserPageReadiness probe(String url) throws BrowserException {
        checkPolicy(url);
        currentDocument = null; // superseded by this navigation, whatever it brings
        return readinessFrom(checkedSnapshot(readDocument(driver.open(url))));
    }

    @Override
    public BrowserPageReadiness probeCurrent() throws BrowserException {
        requirePage();
        return readinessFrom(checkedSnapshot(current()));
    }

    @Override
    public String dismissConsent() throws BrowserException {
        requirePage();
        if (shownDocument() != null) {
            return "none"; // a PDF document has no consent banner to click
        }
        return driver.tryDismissConsent();
    }

    @Override
    public String renderHud(String stateLine) throws BrowserException {
        requirePage();
        return driver.renderHud(stateLine);
    }

    @Override
    public String pollHudCommands() throws BrowserException {
        requirePage();
        return driver.pollHudCommands();
    }

    /**
     * Compose a readability probe from a snapshot plus the driver's consent/challenge guards. A read PDF document
     * has neither, and asking the driver would only wait for page scripts that never run in its viewer.
     */
    private BrowserPageReadiness readinessFrom(BrowserPageSnapshot s) {
        if (currentDocument != null) {
            int len = s.getText() == null ? 0 : s.getText().length();
            return new BrowserPageReadiness(s.getUrl(), s.getTitle(), len,
                    BrowserPageReadiness.excerptOf(s.getText()), false, false, "", false, "");
        }
        String marker = driver.challengeMarker(); // 'visible:…' | 'hidden:…' | 'none'
        boolean present = marker != null && !marker.isEmpty() && !"none".equals(marker);
        boolean visible = marker != null && marker.startsWith("visible");
        String consent = driver.consentCandidate();
        boolean consentPresent = consent != null && consent.startsWith("candidate");
        int len = s.getText() == null ? 0 : s.getText().length();
        return new BrowserPageReadiness(s.getUrl(), s.getTitle(), len,
                BrowserPageReadiness.excerptOf(s.getText()),
                present, visible, present ? marker : "",
                consentPresent, consentPresent ? consent : "");
    }

    public List<BrowserLink> links() throws BrowserException {
        requirePage();
        return currentLinks;
    }

    public BrowserPageSnapshot follow(String linkId) throws BrowserException {
        requirePage();
        for (BrowserLink link : currentLinks) {
            if (link.getId().equals(linkId)) {
                return open(link.getUrl());
            }
        }
        throw new BrowserException("Unknown link id: " + linkId + " (link ids are only valid for the "
                + "current page; call web_links first).");
    }

    public BrowserPageSnapshot back() throws BrowserException {
        requirePage();
        PlaywrightPageState document = shownDocument();
        currentDocument = null; // superseded by this navigation, whatever it brings
        if (document != null && !document.documentInTab) {
            // The download never replaced the tab's page: going back means the page the tab still shows.
            return checkedSnapshot(readDocument(driver.current()));
        }
        return checkedSnapshot(readDocument(driver.back()));
    }

    /**
     * Poll the parked manual challenge WITHOUT reloading or focusing it: while present → CHALLENGE line;
     * on first disappearance → RESOLVED line (challenge tab closed, family unlocked); otherwise NONE.
     */
    public List<String> challengeStatus() {
        // The user closing the window must NEVER read as "challenge resolved" — a dead challenge tab
        // and a dead browser look identical to the poll below, so the browser is checked first.
        if (driver.browserClosedByUser()) {
            return Collections.singletonList("BROWSER_CLOSED");
        }
        if (challengeFamily == null) {
            return Collections.singletonList("NONE");
        }
        if (driver.parkedChallengeStillPresent()) {
            return Collections.singletonList("CHALLENGE: " + challengeFamily + " " + challengeUrl);
        }
        String resolved = challengeFamily;
        driver.closeParkedChallenge();
        challengeFamily = null;
        challengeUrl = null;
        return Collections.singletonList("RESOLVED: " + resolved);
    }

    public void close() {
        driver.close();
    }

    /**
     * EMERGENCY teardown from a foreign thread (stuck owner): see {@link PlaywrightDriver#terminate()}. A PDF
     * download in progress runs outside the driver, so it is cut first ({@link PdfPageReader#terminate()}).
     */
    void terminate() {
        documents.terminate();
        driver.terminate();
    }

    /**
     * Keep the driver's event loop alive while the owner thread has no command to run (route interception,
     * HUD binding, popup close). @return false when the driver has no event loop to pump.
     */
    boolean pumpEvents(java.util.function.BooleanSupplier wake, long timeoutMillis) {
        return driver.pumpEvents(wake, timeoutMillis);
    }

    /** Slice length of one pacing pump round — granularity only; the DURATION is the user's setting. */
    private static final long PACING_PUMP_SLICE_MILLIS = 250L;

    /**
     * The user's per-engine request delay: wait, but keep PUMPING — a plain sleep on the owner thread
     * would freeze route interception, the HUD and close detection for the whole pause. A driver
     * without an event loop (tests, teardown) falls back to a plain sleep slice.
     */
    private void paceBeforeRepeatRequest(long delayMillis) {
        long remaining = delayMillis;
        while (remaining > 0) {
            long slice = Math.min(remaining, PACING_PUMP_SLICE_MILLIS);
            if (!driver.pumpEvents(() -> false, slice)) {
                try {
                    Thread.sleep(slice);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            remaining -= slice;
        }
    }

    /** The driver's control-plane HUD inbox (or null) — drained OUTSIDE the actor's command queue. */
    HudCommandInbox hudInbox() {
        return driver.hudInbox();
    }

    /** The pre-navigation gate; an engine refusal in a worker context is logged before it propagates. */
    private void checkPolicy(String url) throws BrowserException {
        try {
            policy.check(url);
        } catch (com.aresstack.askai.browser.EngineHostRefusedException engine) {
            logEngineRefusal(engine);
            throw engine;
        }
    }

    private static void logEngineRefusal(com.aresstack.askai.browser.EngineHostRefusedException refused) {
        System.err.println("[browser-worker] " + refused.getMessage());
    }

    /** The current page: the read PDF document, or what the driver's tab shows (a document there is read). */
    private PlaywrightPageState current() throws BrowserException {
        PlaywrightPageState document = shownDocument();
        return document != null ? document : readDocument(driver.current());
    }

    /**
     * The read PDF document while the tab still shows it (its viewer, or the page the tab kept for a download),
     * else null: a tab that shows something else since (the user navigated the visible window) has left it.
     */
    private PlaywrightPageState shownDocument() throws BrowserException {
        if (currentDocument != null && !currentDocument.tabUrl.equals(driver.currentUrl())) {
            currentDocument = null;
        }
        return currentDocument;
    }

    /**
     * A PDF the browser reached but cannot read as a page (its viewer runs no page script, an attachment only
     * starts a download) is read by the {@link PdfPageReader}: the browser's final URL passes the same gate as a
     * page's, every redirect of the reader's own fetch is checked again. Every other state passes unchanged.
     */
    private PlaywrightPageState readDocument(PlaywrightPageState state) throws BrowserException {
        if (!state.isDocument()) {
            return state;
        }
        requireAllowedFinalUrl(state.url);
        try {
            return documents.read(state);
        } catch (com.aresstack.askai.browser.EngineHostRefusedException engine) {
            logEngineRefusal(engine);
            throw engine;
        }
    }

    /** The post-redirect gate: the FINAL url must pass the policy or the page is abandoned. */
    private BrowserPageSnapshot checkedSnapshot(PlaywrightPageState state) throws BrowserException {
        requireAllowedFinalUrl(state.url);
        String text = state.text;
        boolean truncated = false;
        if (text.length() > limits.getMaxTextChars()) {
            text = text.substring(0, limits.getMaxTextChars());
            truncated = true;
        }
        List<BrowserLink> links = new ArrayList<BrowserLink>();
        int id = 0;
        for (PlaywrightPageState.Anchor anchor : state.anchors) {
            if (anchor.href.isEmpty()) {
                continue;
            }
            if (links.size() >= limits.getMaxLinks()) {
                truncated = true;
                break;
            }
            id++;
            links.add(new BrowserLink("link-" + id, anchor.text, anchor.href));
        }
        currentLinks = Collections.unmodifiableList(links);
        hasPage = true;
        currentDocument = state.isDocument() ? state : null;
        return new BrowserPageSnapshot(state.url, state.title, text, truncated);
    }

    private void requireAllowedFinalUrl(String url) throws BrowserException {
        try {
            policy.check(url);
        } catch (com.aresstack.askai.browser.EngineHostRefusedException engine) {
            abandonPage();
            logEngineRefusal(engine);
            throw engine;
        } catch (BrowserException blocked) {
            abandonPage();
            throw new BrowserException("Blocked after redirect — final URL is not allowed: "
                    + blocked.getMessage());
        }
    }

    /** After a blocked redirect the offending page must not stay current. */
    private void abandonPage() {
        currentLinks = Collections.emptyList();
        hasPage = false;
        currentDocument = null;
        try {
            driver.back();
        } catch (BrowserException ignored) {
            // No history to fall back to — the session simply has no current page anymore.
        }
    }

    private void requirePage() throws BrowserException {
        if (!hasPage) {
            throw new BrowserException("No page is open yet — call web_open or web_search first.");
        }
    }

    private static String encode(String value) throws BrowserException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new BrowserException("Cannot encode query.");
        }
    }
}
