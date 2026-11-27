package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Issue #22, Argos' web_search in AskAI, against three LOCAL "engines" in a real browser: every start page opens in a
 * background tab of its own, the query is typed into all three search fields at the same time and Enter pressed in
 * each, with real input only. Each start page records what its field receives (key events with their isTrusted flag,
 * blur and visibility changes) into a hidden form field that Enter submits with the query, so the result page shows
 * the query exactly as the engine got it and the page's own view of the input.
 *
 * <p>Environment-gated like the other live tests: {@code ASKAI_TEST_BROWSER_CHANNEL} (default chrome) names the
 * browser; for Camoufox set {@code ASKAI_TEST_BROWSER_CHANNEL=camoufox ASKAI_LIVE_CAMOUFOX=1}.</p>
 */
public class SearchTabsFixtureLiveTest {

    private static final String QUERY = "Kaninchen Ernährung Futter";

    private HttpServer server;
    private Playwright4jDriver driver;
    /** Per engine: when its result request arrived (ms since the epoch). */
    private final Map<String, Long> arrivals = new ConcurrentHashMap<String, Long>();

    @After
    public void stop() {
        if (driver != null) {
            driver.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    /** A start page whose own script logs the input its field gets; nothing of AskAI runs in it. */
    private static String startPage(String engine) {
        return "<!doctype html><html><head><title>" + engine + " start</title></head><body>"
                + "<form action='/" + engine + "/results' method='get'>"
                + "<input name='q' id='q' style='width:400px;font-size:18px' autocomplete='off'>"
                + "<input type='hidden' name='log' id='log'></form>"
                + "<script>const log=[];const f=document.getElementById('q');"
                + "const put=(s)=>{log.push(s);document.getElementById('log').value=log.join(' ');};"
                + "for(const t of ['keydown','keyup']){f.addEventListener(t,(e)=>put(t+':'+e.key+':'+e.isTrusted));}"
                + "f.addEventListener('input',(e)=>put('input:'+e.isTrusted));"
                + "f.addEventListener('blur',()=>put('blur'));"
                + "document.addEventListener('visibilitychange',()=>put('visibility:'+document.visibilityState));"
                + "</script></body></html>";
    }

    /** The browser to test with, or the test is skipped where that browser is missing. */
    private static String gatedChannel() {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        if (CamoufoxProvisioning.CHANNEL.equals(channel)) {
            assumeTrue("set ASKAI_LIVE_CAMOUFOX=1 for the real Camoufox", "1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX")));
        } else {
            PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
            assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());
        }
        return channel;
    }

    /**
     * Review of #28: the start pages load independently. One engine answers late, one not within the budget, one
     * refuses the connection; the fast engine behind them is asked at once, every engine keeps its own tab index, and
     * the whole start takes one shared budget, not one per engine.
     */
    @Test
    public void aDelayedEngineHoldsBackNoOtherStartPage() throws Exception {
        String channel = gatedChannel();
        final long slowMillis = 2_500L;
        final int budgetMillis = 5_000;
        final Map<String, Long> asked = new ConcurrentHashMap<String, Long>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One thread per request: a stalling engine must not stall the test server's other engines.
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "delayed-engine");
            thread.setDaemon(true);
            return thread;
        }));
        for (final String engine : Arrays.asList("slow", "stuck", "fast")) {
            server.createContext("/" + engine + "/", exchange -> {
                asked.putIfAbsent(engine, System.currentTimeMillis());
                try {
                    if ("slow".equals(engine)) {
                        Thread.sleep(slowMillis);
                    } else if ("stuck".equals(engine)) {
                        Thread.sleep(budgetMillis * 2L);
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, "<!doctype html><html><head><title>" + engine + " start</title></head>"
                        + "<body><form><input name='q'></form></body></html>");
            });
        }
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        int refusingPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            refusingPort = probe.getLocalPort(); // closed again: connecting to it is refused
        }

        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        driver = Playwright4jDriver.launch(channel, !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED")),
                budgetMillis, null, null, settings.consent, settings.captcha);
        driver.open(base + "/fast/front");
        asked.clear();

        long started = System.currentTimeMillis();
        List<String> loaded = driver.openSearchTabs(Arrays.asList(
                base + "/slow/", base + "/stuck/", "http://127.0.0.1:" + refusingPort + "/", base + "/fast/"));
        long took = System.currentTimeMillis() - started;
        System.err.println("[search-tabs] start pages " + loaded + " in " + took + " ms; fast engine asked after "
                + (asked.get("fast") - started) + " ms");

        assertNull("the late engine still loads within the budget: " + loaded, loaded.get(0));
        assertNotNull("the engine that never answers in time fails on its own: " + loaded, loaded.get(1));
        assertNotNull("the refused connection fails on its own: " + loaded, loaded.get(2));
        assertNull("the fast engine loads although engines before it are slow or broken: " + loaded, loaded.get(3));
        assertTrue("the fast engine was asked without waiting for the slow ones (" + (asked.get("fast") - started)
                + " ms)", asked.get("fast") - started < slowMillis);
        assertTrue("one budget for all engines, not one per engine: " + took + " ms", took < budgetMillis + 2_000L);
        assertTrue("each engine keeps its own tab index", driver.useSearchTab(3).url.endsWith("/fast/"));
        assertTrue("each engine keeps its own tab index", driver.useSearchTab(0).url.endsWith("/slow/"));
        driver.closeSearchTabs();
    }

    /** A plain start page: a form whose Enter asks {@code /<engine>/results}; {@code script} runs in it. */
    private static String plainStartPage(String engine, String script) {
        return "<!doctype html><html><head><title>" + engine + " start</title></head><body>"
                + "<form action='/" + engine + "/results' method='get'>"
                + "<input name='q' id='q' style='width:400px;font-size:18px' autocomplete='off'></form>"
                + "<script>" + script + "</script></body></html>";
    }

    /** A test server whose engines each answer on a thread of their own, so a stalling engine stalls no other. */
    private HttpServer threadedServer() throws IOException {
        HttpServer threaded = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threaded.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "fixture-engine");
            thread.setDaemon(true);
            return thread;
        }));
        return threaded;
    }

    /**
     * Review of #28, second round (A): the result pages are waited for side by side against one deadline. Two
     * engines answer Enter with a page that starts (commits) but never finishes; before, each one got the whole
     * timeout in turn, so they added up. Now the search returns one budget after Enter, the fast engine's result is
     * there, and only the two hanging engines report their result page as not loaded.
     */
    @Test
    public void hangingResultPagesDoNotAddUpTheirWaits() throws Exception {
        String channel = gatedChannel();
        final int budgetMillis = 5_000;
        final Map<String, Long> asked = new ConcurrentHashMap<String, Long>();
        server = threadedServer();
        final List<String> engines = Arrays.asList("hangs", "hangs-too", "fast");
        for (final String engine : engines) {
            server.createContext("/" + engine + "/", exchange -> {
                if (!exchange.getRequestURI().getPath().endsWith("/results")) {
                    respond(exchange, plainStartPage(engine, ""));
                    return;
                }
                asked.put(engine, System.currentTimeMillis());
                if ("fast".equals(engine)) {
                    respond(exchange, "<!doctype html><html><body><p>RESULTS[fast]</p></body></html>");
                    return;
                }
                // Commits (headers and the first bytes arrive), but the document never ends within the budget.
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write("<!doctype html><html><body><p>RESULTS[".getBytes(StandardCharsets.UTF_8));
                out.write(new byte[2048]);
                out.flush();
                try {
                    Thread.sleep(budgetMillis * 3L);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                out.close();
            });
        }
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        driver = Playwright4jDriver.launch(channel, !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED")),
                budgetMillis, null, null, settings.consent, settings.captcha);
        driver.open(base + "/fast/front");
        List<String> homes = new ArrayList<String>();
        List<List<String>> fields = new ArrayList<List<String>>();
        for (String engine : engines) {
            homes.add(base + "/" + engine + "/");
            fields.add(Arrays.asList("input[name=q]"));
        }
        assertEquals(Arrays.asList(null, null, null), driver.openSearchTabs(homes));

        List<String> typed = driver.submitSearchInTabs(fields, "hase");
        long returned = System.currentTimeMillis();
        long lastEnter = Collections.max(asked.values());
        System.err.println("[search-tabs] hanging result pages: " + typed + ", returned " + (returned - lastEnter)
                + " ms after the last Enter");

        assertTrue("the first hanging engine reports its result page: " + typed, typed.get(0).startsWith("refused:"));
        assertTrue("the second hanging engine reports its result page: " + typed, typed.get(1).startsWith("refused:"));
        assertEquals("the fast engine is not held back: " + typed, "submitted", typed.get(2));
        assertTrue("the fast engine's result page is read", driver.useSearchTab(2).text.contains("RESULTS[fast]"));
        assertTrue("one budget for all result pages, not one per hanging engine: " + (returned - lastEnter) + " ms",
                returned - lastEnter < budgetMillis + 2_500L);
        driver.closeSearchTabs();
    }

    /**
     * Review of #28, second round (B): the merged input timeline belongs to no single tab. The FIRST engine's tab
     * closes itself on its first key; the other engines still get their whole query and Enter.
     */
    @Test
    public void theFirstTabClosingDuringTheInputEndsOnlyItsOwnInput() throws Exception {
        String channel = gatedChannel();
        server = threadedServer();
        final List<String> engines = Arrays.asList("closes", "two", "three");
        for (final String engine : engines) {
            server.createContext("/" + engine + "/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/results")) {
                    String raw = exchange.getRequestURI().getRawQuery();
                    String q = raw == null ? "" : URLDecoder.decode(raw.substring(raw.indexOf('=') + 1), "UTF-8");
                    respond(exchange, "<!doctype html><html><body><p>QUERY[" + q + "]</p></body></html>");
                } else {
                    respond(exchange, plainStartPage(engine, "closes".equals(engine)
                            ? "document.getElementById('q').addEventListener('keydown',"
                            + "()=>console.log('close-me'),{once:true});" : ""));
                }
            });
        }
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        driver = Playwright4jDriver.launch(channel, !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED")),
                30_000, null, null, settings.consent, settings.captcha);
        driver.open(base + "/two/front");
        List<String> homes = new ArrayList<String>();
        List<List<String>> fields = new ArrayList<List<String>>();
        for (String engine : engines) {
            homes.add(base + "/" + engine + "/");
            fields.add(Arrays.asList("input[name=q]"));
        }
        assertEquals(Arrays.asList(null, null, null), driver.openSearchTabs(homes));
        // A script may not close a tab it did not open, so the test closes it: on the tab's first key, from inside
        // the Playwright event dispatch that runs while the input timeline waits (the moment the user would).
        java.lang.reflect.Field contextField = Playwright4jDriver.class.getDeclaredField("context");
        contextField.setAccessible(true);
        com.microsoft.playwright.BrowserContext context =
                (com.microsoft.playwright.BrowserContext) contextField.get(driver);
        for (final com.microsoft.playwright.Page tab : context.pages()) {
            if (tab.url().contains("/closes/")) {
                tab.onConsoleMessage(message -> {
                    if ("close-me".equals(message.text())) {
                        tab.close();
                    }
                });
            }
        }

        List<String> typed = driver.submitSearchInTabs(fields, QUERY);
        System.err.println("[search-tabs] first tab closed during the input: " + typed);

        assertTrue("the closed tab reports its input as refused: " + typed, typed.get(0).startsWith("refused:"));
        assertEquals("the other engines still got their input: " + typed, Arrays.asList("submitted", "submitted"),
                typed.subList(1, 3));
        for (int i = 1; i < 3; i++) {
            String text = driver.useSearchTab(i).text;
            assertTrue(engines.get(i) + " got the whole query: " + text, text.contains("QUERY[" + QUERY + "]"));
        }
        driver.closeSearchTabs();
    }

    @Test
    public void allEngineTabsAreTypedIntoAtTheSameTimeWithRealInput() throws Exception {
        String channel = gatedChannel();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final List<String> engines = Arrays.asList("one", "two", "three");
        for (final String engine : engines) {
            server.createContext("/" + engine + "/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/results")) {
                    arrivals.put(engine, System.currentTimeMillis());
                    String raw = exchange.getRequestURI().getRawQuery();
                    String q = "";
                    String log = "";
                    for (String pair : raw == null ? new String[0] : raw.split("&")) {
                        int eq = pair.indexOf('=');
                        String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                        if (pair.startsWith("q=")) {
                            q = value;
                        } else if (pair.startsWith("log=")) {
                            log = value;
                        }
                    }
                    respond(exchange, "<!doctype html><html><head><title>" + engine + " results</title></head>"
                            + "<body><p id='q'>QUERY[" + q + "]</p><p id='log'>LOG[" + log + "]</p></body></html>");
                } else {
                    respond(exchange, startPage(engine));
                }
            });
        }
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        driver = Playwright4jDriver.launch(channel, headless, 30_000, null, null, settings.consent, settings.captcha);
        PlaywrightPageState front = driver.open(base + "/one/front");

        List<String> homes = new ArrayList<String>();
        List<List<String>> fields = new ArrayList<List<String>>();
        for (String engine : engines) {
            homes.add(base + "/" + engine + "/");
            fields.add(Arrays.asList("textarea[name=q]", "input[name=q]"));
        }
        long started = System.currentTimeMillis();
        List<String> loaded = driver.openSearchTabs(homes);
        assertEquals(Arrays.asList(null, null, null), loaded);
        List<String> typed = driver.submitSearchInTabs(fields, QUERY);
        long took = System.currentTimeMillis() - started;
        assertEquals(Arrays.asList("submitted", "submitted", "submitted"), typed);

        for (int i = 0; i < engines.size(); i++) {
            PlaywrightPageState results = driver.useSearchTab(i);
            System.err.println("[search-tabs] " + engines.get(i) + ": " + results.text);
            assertTrue("each engine got exactly its query: " + results.text,
                    results.text.contains("QUERY[" + QUERY + "]"));
            String log = results.text.substring(results.text.indexOf("LOG["));
            assertTrue("keys arrived as key events: " + log, log.contains("keydown:K:true"));
            assertTrue("Enter was a real key: " + log, log.contains("keydown:Enter:true") || log.contains("keyup:"));
            assertFalse("no untrusted (script-made) input: " + log, log.contains(":false"));
            assertFalse("the tab never lost focus or visibility: " + log,
                    log.contains("blur") || log.contains("visibility:hidden"));
        }
        long first = Collections.min(arrivals.values());
        long last = Collections.max(arrivals.values());
        System.err.println("[search-tabs] typing and submit took " + took + " ms, results arrived within "
                + (last - first) + " ms");
        // Typed one after another, the result requests would be a whole typing duration apart.
        assertTrue("the three tabs were typed into side by side, results within " + (last - first) + " ms of "
                + took + " ms", last - first < took / 3);

        driver.closeSearchTabs();
        assertEquals("the page before the search is current again", front.url, driver.current().url);
    }

    /**
     * A Bing-like result page: ten tall result blocks, the pager below them at the bottom of a page far taller than the
     * window ("2", "3", "Weiter" like Bing's ol of li), smooth scrolling on. {@code deadNext} makes "Weiter" a link to
     * the page itself, so the click loads nothing new.
     */
    private static String pagedResults(int first, boolean deadNext) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><title>results first=" + first
                + "</title><style>html{scroll-behavior:smooth}li.b_algo{height:280px}"
                + "#pager li{display:inline-block;margin:0 8px}</style></head><body><ol id='b_results'>");
        for (int i = 0; i < 10; i++) {
            html.append("<li class='b_algo'><h2><a href='https://example.org/").append(first + i).append("'>Result ")
                    .append(first + i).append("</a></h2></li>");
        }
        html.append("</ol><ul id='pager'>");
        for (int page = 1; page <= 3; page++) {
            int offset = (page - 1) * 10 + 1;
            html.append(offset == first ? "<li><span>" + page + "</span></li>"
                    : "<li><a href='/serp?first=" + offset + "'>" + page + "</a></li>");
        }
        html.append("<li><a class='sb_pagN' title='Nächste Seite' href='")
                .append(deadNext ? "/serp?first=" + first + "&dead=1" : "/serp?first=" + (first + 10))
                .append("'>Weiter</a></li></ul><div style='height:120px'></div></body></html>");
        return html.toString();
    }

    /**
     * Live acceptance of #28: Bing's pages 2 and 3 had the same address. With real wheel scrolling to the pager at the
     * bottom of a long page, every "Weiter" click must load the NEXT result page: first=1, 11, 21, each asked once.
     */
    @Test
    public void everyPagerClickLoadsTheNextResultPage() throws Exception {
        String channel = gatedChannel();
        final List<String> asked = java.util.Collections.synchronizedList(new ArrayList<String>());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/serp", exchange -> {
            String raw = exchange.getRequestURI().getRawQuery();
            asked.add(raw);
            int first = Integer.parseInt(raw.replaceAll(".*first=(\\d+).*", "$1"));
            respond(exchange, pagedResults(first, false));
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        driver = Playwright4jDriver.launch(channel, !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED")),
                30_000, null, null, settings.consent, settings.captcha);
        driver.open(base + "/serp?first=1");

        List<String> pager = Arrays.asList("a.sb_pagN", "a[aria-label='Next page']");
        assertTrue("page 2 reached", driver.clickNextPage(pager));
        assertTrue(driver.current().url, driver.current().url.endsWith("first=11"));
        assertTrue("page 3 reached", driver.clickNextPage(pager));
        assertTrue("page 3 is not page 2 again: " + driver.current().url, driver.current().url.endsWith("first=21"));
        assertEquals("each result page asked exactly once", Arrays.asList("first=1", "first=11", "first=21"), asked);
    }

    /** A "Weiter" that loads the same result page again is no next page: the click reports false, nothing is re-read. */
    @Test
    public void aPagerClickThatLoadsNoOtherPageIsNotTheNextPage() throws Exception {
        String channel = gatedChannel();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/serp", exchange -> respond(exchange, pagedResults(11, true)));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        driver = Playwright4jDriver.launch(channel, !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED")),
                30_000, null, null, settings.consent, settings.captcha);
        driver.open(base + "/serp?first=11&dead=1");

        assertFalse("the same page again is no next page", driver.clickNextPage(Arrays.asList("a.sb_pagN")));
    }

    /**
     * Codex review of #34: DuckDuckGo's HTML endpoint pages with a form POST under the same address. Such a click is a
     * next page although the address stays the same.
     */
    @Test
    public void aPostPagerKeepsItsAddressAndIsStillTheNextPage() throws Exception {
        String channel = gatedChannel();
        final List<String> posted = java.util.Collections.synchronizedList(new ArrayList<String>());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/html/", exchange -> {
            int offset = 0;
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                posted.add(body);
                offset = Integer.parseInt(body.replaceAll(".*s=(\\d+).*", "$1"));
            }
            StringBuilder html = new StringBuilder("<!doctype html><html><head><title>ddg s=" + offset
                    + "</title></head><body>");
            for (int i = 0; i < 12; i++) {
                html.append("<div style='height:200px'><a href='https://example.org/").append(offset + i)
                        .append("'>Result ").append(offset + i).append("</a></div>");
            }
            html.append("<form action='/html/' method='post'><input type='hidden' name='q' value='hase'>")
                    .append("<input type='hidden' name='s' value='").append(offset + 10).append("'>")
                    .append("<input type='submit' class='navbutton' value='Next'></form></body></html>");
            respond(exchange, html.toString());
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        driver = Playwright4jDriver.launch(channel, !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED")),
                30_000, null, null, settings.consent, settings.captcha);
        driver.open(base + "/html/");

        assertTrue("the POST page is the next page", driver.clickNextPage(Arrays.asList("input.navbutton[value*='Next' i]")));
        assertEquals("same address", base + "/html/", driver.current().url);
        assertEquals(1, posted.size());
        assertTrue(posted.get(0), posted.get(0).contains("s=10"));
    }

    private static void respond(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }
}
