package com.aresstack.askai.browser.playwright;

import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.model.data.ResultType;
import com.aresstack.serp4j.core.model.data.SearchData;
import com.aresstack.serp4j.core.model.data.SearchResultItem;
import com.aresstack.serp4j.core.model.data.SerpPosition;
import com.aresstack.serp4j.core.model.id.ElementId;
import com.aresstack.serp4j.core.model.id.ProviderId;
import com.aresstack.serp4j.core.model.id.SearchEngineId;
import com.aresstack.serp4j.core.model.id.SearchVertical;
import com.aresstack.serp4j.core.port.ProviderDescriptor;
import com.aresstack.serp4j.core.port.ProviderKind;
import com.aresstack.serp4j.core.port.SearchContext;
import com.aresstack.serp4j.core.port.capability.SearchCapabilities;
import com.aresstack.serp4j.playwright.PlaywrightSearchProvider;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * askai #31 / ADR-020: AskAI's search browser starts once, hands the same {@link Browser} to the provider on
 * every search, starts again after the browser died, and is never closed by the provider. Environment-gated
 * on a local Chromium channel (Camoufox in production).
 */
public class SearchBrowserLiveTest {

    @Test
    public void reusesOneBrowserAndStartsItAgainAfterItDied() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<!doctype html><title>Result page</title><p>ok</p>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
        TitleProvider provider = new TitleProvider(url);
        SearchBrowser searchBrowser = new SearchBrowser(channel);
        try {
            SearchResponse first = searchBrowser.search(provider, SearchRequest.builder("a").build(),
                    SearchContext.defaults());
            SearchResponse second = searchBrowser.search(provider, SearchRequest.builder("b").build(),
                    SearchContext.defaults());
            assertEquals("Result page", first.getData().getResults().get(0).getTitle().orElse(null));
            assertEquals("Result page", second.getData().getResults().get(0).getTitle().orElse(null));
            assertEquals(1, searchBrowser.getLaunchCount());
            assertSame("the same browser for every search", provider.browsers.get(0), provider.browsers.get(1));
            assertTrue("the provider never closes the browser", provider.browsers.get(1).isConnected());

            provider.browsers.get(1).close(); // the browser dies between two searches
            searchBrowser.search(provider, SearchRequest.builder("c").build(), SearchContext.defaults());
            assertEquals(2, searchBrowser.getLaunchCount());
            assertTrue(provider.browsers.get(2).isConnected());
        } finally {
            searchBrowser.close();
            server.stop(0);
        }
    }

    /** A minimal provider in the serp4j-playwright pattern: own page in the existing context, closed after use. */
    private static final class TitleProvider implements PlaywrightSearchProvider {
        private final String url;
        final List<Browser> browsers = new ArrayList<Browser>();
        private final ProviderDescriptor descriptor = ProviderDescriptor.builder(ProviderId.of("test-title"),
                "test-title", ProviderKind.BROWSER,
                SearchCapabilities.builder().target(SearchEngineId.of("local"), SearchVertical.WEB).build()).build();

        TitleProvider(String url) {
            this.url = url;
        }

        @Override
        public ProviderDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public SearchResponse search(SearchRequest request, SearchContext context, Browser browser) {
            browsers.add(browser);
            Page page = browser.contexts().get(0).newPage();
            try {
                page.navigate(url + "?q=" + request.getQuery());
                return SearchResponse.builder(SearchData.builder()
                        .result(SearchResultItem.builder(ElementId.of("r1"), ResultType.ORGANIC, page.url())
                                .title(page.title()).position(SerpPosition.ofRankInGroup(1)).build())
                        .build()).build();
            } finally {
                page.close();
            }
        }
    }
}
