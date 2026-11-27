package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.core.error.SearchErrorCategory;
import com.aresstack.serp4j.core.error.SearchProviderException;
import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.model.data.SearchResultItem;
import com.aresstack.serp4j.core.model.ext.ExtensionKey;
import com.aresstack.serp4j.playwright.PlaywrightSearchFailures;
import com.aresstack.serp4j.playwright.PlaywrightSearchProvider;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of the synchronous serp4j-playwright provider against a scripted browser (no real browser, no network):
 * the host's browser is used, never closed; only the provider's own page is closed.
 */
class GooglePlaywrightSearchProviderTest {

    /** Serp4J's {@code serp4j:url_resolution} extension: absent, because nothing was resolved. */
    private static final ExtensionKey<String> URL_RESOLUTION =
            ExtensionKey.of("serp4j", "url_resolution", String.class);

    private final PlaywrightSearchProvider provider = GooglePlaywrightSearchProvider.builder()
            .defaultLanguage("de").defaultCountry("DE").build();

    @Test
    void onlyGotoHitsAreAllKeptUnresolvedWithTheirMetadataAndWithoutAnyResolutionRequest() {
        // Recorded (anonymised) Camoufox result page: all nine organic links are google.com/goto redirects.
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j&hl=de",
                GoogleSerpHtmlParserTest.html("browser-web-de.html"), 200);

        SearchResponse response = provider.search(SearchRequest.builder("serp4j").build(), fake.browser());

        List<SearchResultItem> results = response.getData().getResults();
        assertThat(results).hasSize(9).allSatisfy(item -> {
            assertThat(item.getUrl()).startsWith("https://www.google.com/goto?url=ANONYMIZED");
            assertThat(item.getTitle()).isPresent();
            assertThat(item.getExtensions().get(URL_RESOLUTION)).isEmpty();
            assertThat(item.getDomain()).isEmpty(); // never guessed for a redirect
        });
        assertThat(results.get(0).getDisplayUrl()).contains("https://www.elastic.co › clients");
        assertThat(results.get(0).getSnippet().get()).startsWith("The Elasticsearch Java client");
        assertThat(results.get(0).getSiteName()).contains("Elastic.co");
        assertThat(results.get(0).getSiteLinks()).hasSize(2);
        // Exactly one Google request (the result page), however many /goto links it lists.
        assertThat(fake.navigated).hasSize(1);
        assertThat(fake.pagesOpened).isEqualTo(1);
        assertThat(fake.apiRequests).isZero();
    }

    @Test
    void mixedDirectAndGotoHitsAreAllKeptInTheirOrder() {
        String html = GoogleSerpHtmlParserTest.html("browser-web-de.html");
        String withDirect = html.replace("href=\"/goto?url=ANONYMIZED1&amp;gsessionid=ANONYMIZED\"",
                "href=\"https://www.elastic.co/docs/reference/elasticsearch/clients/java\"");
        assertThat(withDirect).isNotEqualTo(html); // test variation of the recorded page, not a recording
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j&hl=de", withDirect, 200);

        SearchResponse response = provider.search(SearchRequest.builder("serp4j").build(), fake.browser());

        List<SearchResultItem> results = response.getData().getResults();
        assertThat(results).hasSize(9);
        long direct = results.stream().filter(r -> r.getUrl().startsWith("https://www.elastic.co/")).count();
        long gotos = results.stream().filter(r -> r.getUrl().startsWith("https://www.google.com/goto?")).count();
        assertThat(direct + gotos).isEqualTo(9);
        assertThat(direct).isLessThanOrEqualTo(1);
        assertThat(results).extracting(r -> r.getPosition().get().getRankAbsolute().getAsInt())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(fake.navigated).hasSize(1);
        assertThat(fake.apiRequests).isZero();
    }

    @Test
    void mapsRecordedResultPageAndClosesOnlyItsPage() {
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j&hl=de",
                GoogleSerpHtmlParserTest.html("browser-web-de.html"), 200);

        SearchResponse response = provider.search(SearchRequest.builder("serp4j").build(), fake.browser());

        assertThat(response.getData().getResults()).isNotEmpty();
        assertThat(fake.navigated).hasSize(1);
        assertThat(fake.navigated.get(0)).startsWith("https://www.google.com/search?").doesNotContain("udm=50");
        assertThat(fake.pageClosed).isTrue();
        assertThat(fake.browserClosed).isFalse();
    }

    @Test
    void waitsForTheResultPageAfterGooglesJavaScriptStartPage() {
        // Live with Camoufox (02.11.): /search first answers with a JavaScript start page whose noscript block
        // points to /httpservice/retry/enablejs; #search appears only after that script ran.
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j&hl=de",
                GoogleSerpHtmlParserTest.html("browser-web-de.html"), 200);

        provider.search(SearchRequest.builder("serp4j").build(), fake.browser());

        assertThat(fake.awaited).containsExactly(GooglePlaywrightSearchProvider.RESULT_PAGE_SELECTOR);
        assertThat(GooglePlaywrightSearchProvider.RESULT_PAGE_SELECTOR)
                .contains("#search", "#rso", "#captcha-form", "consent.google.");
    }

    @Test
    void startPageThatNeverRendersIsReportedAsJavaScriptRequired() {
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j&hl=de",
                "<html><head><noscript><meta content=\"0;url=/httpservice/retry/enablejs?sei=x\" "
                        + "http-equiv=\"refresh\"></noscript></head><body></body></html>", 200);
        fake.waitTimesOut = true;

        assertThatThrownBy(() -> provider.search(SearchRequest.builder("serp4j").build(), fake.browser()))
                .isInstanceOfSatisfying(SearchProviderException.class, e -> {
                    assertThat(e.getCategory()).isEqualTo(SearchErrorCategory.BLOCKED);
                    assertThat(e.getDetails())
                            .containsEntry(PlaywrightSearchFailures.DETAIL_OBSTACLE, "javascript_required");
                });
        assertThat(fake.pageClosed).isTrue();
    }

    @Test
    void challengePageIsBlockedWithoutClicking() {
        FakeBrowser fake = new FakeBrowser("https://www.google.com/sorry/index?continue=x",
                "<html><body><form id=\"captcha-form\"></form></body></html>", 429);

        assertThatThrownBy(() -> provider.search(SearchRequest.builder("serp4j").build(), fake.browser()))
                .isInstanceOfSatisfying(SearchProviderException.class, e -> {
                    assertThat(e.getCategory()).isEqualTo(SearchErrorCategory.BLOCKED);
                    assertThat(e.getDetails()).containsEntry(PlaywrightSearchFailures.DETAIL_OBSTACLE, "challenge");
                });
        assertThat(fake.pageClosed).isTrue();
    }

    @Test
    void disconnectedBrowserIsUnavailableBeforeAnyPageIsOpened() {
        FakeBrowser fake = new FakeBrowser("about:blank", "", 200);
        fake.connected = false;

        assertThatThrownBy(() -> provider.search(SearchRequest.builder("serp4j").build(), fake.browser()))
                .isInstanceOfSatisfying(SearchProviderException.class,
                        e -> assertThat(e.getCategory()).isEqualTo(SearchErrorCategory.UNAVAILABLE));
        assertThat(fake.navigated).isEmpty();
    }

    @Test
    void closedTargetDuringNavigationIsUnavailable() {
        FakeBrowser fake = new FakeBrowser("about:blank", "", 200);
        fake.navigationFailure = new PlaywrightException("Target page, context or browser has been closed");

        assertThatThrownBy(() -> provider.search(SearchRequest.builder("serp4j").build(), fake.browser()))
                .isInstanceOfSatisfying(SearchProviderException.class,
                        e -> assertThat(e.getCategory()).isEqualTo(SearchErrorCategory.UNAVAILABLE));
        assertThat(fake.pageClosed).isTrue();
        assertThat(fake.browserClosed).isFalse();
    }

    @Test
    void reusesTheHostSearchTabAndLeavesItOpen() {
        FakeBrowser fake = new FakeBrowser("https://www.google.com/search?q=serp4j", "", 200);
        fake.hostTab = fake.page();

        try {
            provider.search(SearchRequest.builder("serp4j").build(), fake.browser());
        } catch (SearchProviderException expected) {
            // empty fixture: the outcome is irrelevant, only which page was used
        }

        assertThat(fake.navigated).hasSize(1);
        assertThat(fake.pagesOpened).isZero();
        assertThat(fake.pageClosed).isFalse();
    }

    /** Scripted Playwright objects via dynamic proxies; only the methods the provider uses are answered. */
    private static final class FakeBrowser {
        final String finalUrl;
        final String html;
        final int status;
        final List<String> navigated = new ArrayList<String>();
        final List<String> awaited = new ArrayList<String>();
        boolean waitTimesOut;
        boolean connected = true;
        boolean pageClosed;
        boolean browserClosed;
        int pagesOpened;
        /** Calls of the request API ({@code context.request()}, {@code page.request()}): resolution requests. */
        int apiRequests;
        RuntimeException navigationFailure;
        /** The host's search tab, when the context already holds one. */
        Page hostTab;

        FakeBrowser(String finalUrl, String html, int status) {
            this.finalUrl = finalUrl;
            this.html = html;
            this.status = status;
        }

        Browser browser() {
            BrowserContext context = proxy(BrowserContext.class, (name, args) -> {
                if (name.equals("newPage")) {
                    pagesOpened++;
                    return page();
                }
                if (name.equals("pages")) {
                    return hostTab == null ? Collections.<Page>emptyList() : Collections.singletonList(hostTab);
                }
                if (name.equals("request")) {
                    apiRequests++;
                }
                throw new UnsupportedOperationException(name);
            });
            return proxy(Browser.class, (name, args) -> {
                switch (name) {
                    case "isConnected":
                        return connected;
                    case "contexts":
                        return Collections.singletonList(context);
                    case "close":
                        browserClosed = true;
                        return null;
                    default:
                        throw new UnsupportedOperationException(name);
                }
            });
        }

        Page page() {
            Response response = proxy(Response.class, (name, args) -> {
                if (name.equals("status")) {
                    return status;
                }
                throw new UnsupportedOperationException(name);
            });
            return proxy(Page.class, (name, args) -> {
                switch (name) {
                    case "setDefaultTimeout":
                        return null;
                    case "navigate":
                        navigated.add((String) args[0]);
                        if (navigationFailure != null) {
                            throw navigationFailure;
                        }
                        return response;
                    case "waitForSelector":
                        awaited.add((String) args[0]);
                        if (waitTimesOut) {
                            throw new com.microsoft.playwright.TimeoutError("Timeout 15000ms exceeded.");
                        }
                        return null;
                    case "url":
                        return finalUrl;
                    case "content":
                        return html;
                    case "close":
                        pageClosed = true;
                        return null;
                    case "request":
                        apiRequests++;
                        throw new UnsupportedOperationException(name);
                    default:
                        throw new UnsupportedOperationException(name);
                }
            });
        }

        private interface Handler {
            Object handle(String name, Object[] args);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, Handler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
                if (m.getDeclaringClass() == Object.class) {
                    return m.getName().equals("toString") ? type.getSimpleName() : m.invoke(handler, a);
                }
                return handler.handle(m.getName(), a);
            });
        }
    }
}
