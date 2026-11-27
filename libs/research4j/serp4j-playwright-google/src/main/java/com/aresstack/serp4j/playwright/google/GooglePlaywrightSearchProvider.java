package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.core.error.SearchErrorCategory;
import com.aresstack.serp4j.core.error.SearchProviderException;
import com.aresstack.serp4j.core.model.SearchFeature;
import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.model.id.ProviderId;
import com.aresstack.serp4j.core.model.id.SearchEngineId;
import com.aresstack.serp4j.core.model.id.SearchVertical;
import com.aresstack.serp4j.core.model.metadata.SearchWarning;
import com.aresstack.serp4j.core.port.ProviderDescriptor;
import com.aresstack.serp4j.core.port.ProviderKind;
import com.aresstack.serp4j.core.port.SearchContext;
import com.aresstack.serp4j.core.port.capability.CapabilityCheck;
import com.aresstack.serp4j.core.port.capability.CapabilityViolation;
import com.aresstack.serp4j.core.port.capability.ResponseFeature;
import com.aresstack.serp4j.core.port.capability.SearchCapabilities;
import com.aresstack.serp4j.playwright.PlaywrightSearchFailures;
import com.aresstack.serp4j.playwright.PlaywrightSearchProvider;
import com.aresstack.serp4j.playwright.PlaywrightSearchSupport;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Google web search (Quick Web Search) on a Playwright {@link Browser} that the host hands over with every request
 * (askai #31: module serp4j-playwright-google, built on serp4j-playwright; lives in the AskAI repository and is
 * never published).
 *
 * <ul>
 *   <li>The browser belongs to the host: this provider never launches, closes or replaces it and creates no browser
 *       context of its own. It opens one page in the browser's existing context (a persistent search profile keeps
 *       its consent state), loads {@code https://www.google.com/search?q=…} (never {@code udm=50}) and closes only
 *       that page.</li>
 *   <li>Nothing is clicked: consent, challenge ({@code /sorry/}) and sign-in pages without results fail with
 *       {@code BLOCKED} and the detail {@code google_obstacle}.</li>
 *   <li>Google {@code /goto?url=…} result links are returned exactly as Google lists them (askai #41, Angelo
 *       02.11.): no request is sent to resolve them while the result page is fetched, so the number of requests
 *       per search does not grow with the number of links. The host follows such a link later, when it actually
 *       opens the hit in its browser; the redirect is then part of that normal navigation.</li>
 *   <li>Synchronous (serp4j-playwright contract): {@link #search} runs on the calling thread, which must be the thread
 *       that owns the browser, because Playwright objects are not thread-safe.</li>
 * </ul>
 */
public final class GooglePlaywrightSearchProvider implements PlaywrightSearchProvider {

    public static final String PROVIDER_TYPE = "serp4j-playwright-google";
    public static final ProviderId DEFAULT_ID = ProviderId.of(PROVIDER_TYPE);
    public static final int RESULTS_PER_PAGE = 10;
    public static final Duration DEFAULT_NAVIGATION_TIMEOUT = Duration.ofSeconds(30);
    private final ProviderDescriptor descriptor;
    private final GoogleUrlBuilder urlBuilder;
    private final GoogleSerpHtmlParser parser = new GoogleSerpHtmlParser();
    private final GoogleSerpMapper mapper;
    /** Longest wait for Google's result page after the JavaScript start page. */
    static final Duration RESULT_WAIT = Duration.ofSeconds(15);
    /** Result container, challenge form or consent form: whichever Google ends up showing. */
    static final String RESULT_PAGE_SELECTOR =
            "#search, #rso, #captcha-form, form[action*=\"consent.google.\"]";

    private final Duration navigationTimeout;
    private final Clock clock;

    private GooglePlaywrightSearchProvider(Builder builder) {
        this.descriptor = ProviderDescriptor.builder(builder.id, PROVIDER_TYPE, ProviderKind.BROWSER, capabilities())
                .displayName("Google (AskAI search browser)")
                .build();
        this.urlBuilder = new GoogleUrlBuilder(builder.baseUrl, builder.defaultLanguage,
                builder.defaultCountry);
        this.mapper = new GoogleSerpMapper(builder.id);
        this.navigationTimeout = builder.navigationTimeout;
        this.clock = builder.clock;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SearchCapabilities capabilities() {
        return SearchCapabilities.builder()
                .target(SearchEngineId.GOOGLE, SearchVertical.WEB)
                .features(SearchFeature.QUERY_TEXT, SearchFeature.PAGINATION_OFFSET, SearchFeature.MAX_RESULTS,
                        SearchFeature.COUNTRY, SearchFeature.LANGUAGE, SearchFeature.RAW_RESPONSE)
                .responseFeatures(ResponseFeature.ORGANIC_RESULTS, ResponseFeature.RELATED_QUERIES,
                        ResponseFeature.RAW_PAYLOAD)
                .maxResultsPerRequest(RESULTS_PER_PAGE)
                .build();
    }

    @Override
    public ProviderDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Google first answers {@code /search} with a JavaScript start page (its {@code noscript} block points to
     * {@code /httpservice/retry/enablejs}); the results appear only after that script has run. Waits until the
     * result container, a challenge form or a consent form is in the page, at most {@link #RESULT_WAIT} and never
     * beyond the context deadline. When none appears, the page is classified as it is.
     */
    private void awaitResultPage(Page page, SearchContext context) {
        double wait = Math.min(RESULT_WAIT.toMillis(),
                PlaywrightSearchSupport.timeoutMillis(context, navigationTimeout, clock.instant()));
        try {
            page.waitForSelector(RESULT_PAGE_SELECTOR, new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.ATTACHED).setTimeout(wait));
        } catch (TimeoutError e) {
            // classify what is there (JavaScript start page, unknown layout)
        }
    }

    @Override
    public SearchResponse search(SearchRequest request, SearchContext context, Browser browser) {
        ProviderId id = descriptor.getId();
        Instant startedAt = clock.instant();
        CapabilityCheck check = PlaywrightSearchSupport.precheck(id, descriptor.getCapabilities(), request, context,
                browser, startedAt);
        String url;
        try {
            url = urlBuilder.build(request);
        } catch (IllegalArgumentException e) {
            throw new SearchProviderException(id, SearchErrorCategory.INVALID_REQUEST, e.getMessage());
        }
        if (GoogleUrlBuilder.isAiModeUrl(url)) {
            throw new SearchProviderException(id, SearchErrorCategory.INTERNAL, "AI Mode URL must never be requested");
        }
        String finalUrl;
        String html;
        Response response;
        Page page = null;
        boolean opened = false;
        try {
            page = existingPage(browser);
            if (page == null) {
                page = openPage(browser);
                opened = true;
            }
            page.setDefaultTimeout(PlaywrightSearchSupport.timeoutMillis(context, navigationTimeout, clock.instant()));
            response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            awaitResultPage(page, context);
            finalUrl = page.url();
            html = page.content();
        } catch (PlaywrightException e) {
            throw PlaywrightSearchSupport.translate(id, e, context, clock.instant());
        } finally {
            if (opened) {
                closeQuietly(page);
            }
        }
        if (context.getCancellation().isCancelled()) {
            throw new SearchProviderException(id, SearchErrorCategory.CANCELLED, "search was cancelled");
        }
        boolean truncated = false;
        if (html.length() > PlaywrightSearchSupport.MAX_RAW_HTML_CHARS) {
            html = html.substring(0, PlaywrightSearchSupport.MAX_RAW_HTML_CHARS);
            truncated = true;
        }
        GoogleSerpPage parsed = parser.parse(html);
        GoogleObstacle obstacle = GoogleObstacleDetector.fromPage(finalUrl, parsed);
        if (obstacle != GoogleObstacle.NONE) {
            // ADR-016: consent, challenge and sign-in pages are reported, never clicked away.
            throw PlaywrightSearchFailures.obstacle(id, obstacle.name().toLowerCase(Locale.ROOT), finalUrl);
        }
        if (response != null && parsed.getOrganicResults().isEmpty()) {
            SearchProviderException byStatus = GoogleSearchFailures.forStatus(id, response.status());
            if (byStatus != null) {
                throw byStatus;
            }
        }
        if (parsed.getOrganicResults().isEmpty() && !parsed.isStructureRecognized()) {
            throw PlaywrightSearchFailures.unrecognized(id, "no Google result structure", finalUrl);
        }
        List<SearchWarning> warnings = new ArrayList<SearchWarning>();
        for (CapabilityViolation violation : check.getOptionalViolations()) {
            warnings.add(SearchWarning.of(SearchWarning.FEATURE_IGNORED, violation.getMessage(), id));
        }
        if (truncated) {
            warnings.add(SearchWarning.of(SearchWarning.PARTIAL_RESULTS,
                    "HTML snapshot truncated at " + PlaywrightSearchSupport.MAX_RAW_HTML_CHARS + " characters", id));
        }
        return mapper.map(parsed, request, url, html, startedAt, clock.instant(), warnings);
    }

    /**
     * The host's search tab: the first open page of the browser's first context. The host keeps that tab for
     * all searches (one visible window instead of a new window per search); it is reused and never closed here.
     */
    private static Page existingPage(Browser browser) {
        List<BrowserContext> contexts = browser.contexts();
        if (contexts.isEmpty()) {
            return null;
        }
        List<Page> pages = contexts.get(0).pages();
        return pages == null || pages.isEmpty() ? null : pages.get(0);
    }

    /** A page in the browser's existing context; only a context-less browser gets Playwright's default page. */
    private static Page openPage(Browser browser) {
        List<BrowserContext> contexts = browser.contexts();
        if (!contexts.isEmpty()) {
            return contexts.get(0).newPage();
        }
        return browser.newPage();
    }

    private static void closeQuietly(Page page) {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (RuntimeException ignored) {
            // best effort; the host owns the browser and its contexts
        }
    }

    @Override
    public String toString() {
        return "GooglePlaywrightSearchProvider{id=" + descriptor.getId() + '}';
    }

    public static final class Builder {
        private ProviderId id = DEFAULT_ID;
        private String baseUrl = GoogleUrlBuilder.DEFAULT_BASE_URL;
        private String defaultLanguage;
        private String defaultCountry;
        private Duration navigationTimeout = DEFAULT_NAVIGATION_TIMEOUT;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        public Builder id(ProviderId id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        /**
         * The search endpoint, normally {@link GoogleUrlBuilder#DEFAULT_BASE_URL}. Another value is only meant for
         * local test worlds that serve a Google-shaped result page (askai #47 in-process end-to-end test).
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        public Builder defaultLanguage(String language) {
            this.defaultLanguage = language;
            return this;
        }

        public Builder defaultCountry(String country) {
            this.defaultCountry = country;
            return this;
        }

        public Builder navigationTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.navigationTimeout = timeout;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public GooglePlaywrightSearchProvider build() {
            return new GooglePlaywrightSearchProvider(this);
        }
    }
}
