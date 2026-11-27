package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.analysis.LegacySearchResultExtractor;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Issue #22 against the REAL Bing in a real Camoufox: the capture must see the organic result blocks as visible
 * and the mechanical extraction must take them over as structured candidates with external targets — without any
 * AI repair. Bing's own script hides {@code #b_content}; only its bot-detection module reveals it again, which it
 * did for Argos (no page-world scripts). Since #26/#27 AskAI runs no script in the page's world either (reads in the
 * add-on, real input), so this is the acceptance run of issue #22. The test itself stays clean too: its diagnostic
 * probes are main-world scripts, so they only run with {@code ASKAI_LIVE_BING_DIAG=1} and only AFTER the capture.
 *
 * <p>Opt-in, because it needs the network and the Camoufox download: {@code ASKAI_LIVE_BING=1 gradlew
 * :browser-playwright:test --tests '*CamoufoxBingSearchLiveTest'}; {@code ASKAI_LIVE_BING_QUERY} changes the query,
 * {@code ASKAI_LIVE_CAMOUFOX_HEADED=1} shows the browser, {@code ASKAI_LIVE_BING_ADDON=0} starts without the research
 * add-on, {@code ASKAI_LIVE_BING_QUIET_MS=n} reads nothing from the page for n ms after the navigation,
 * {@code ASKAI_LIVE_BING_DIAG=1} prints the page-world probes after the capture, {@code ASKAI_LIVE_BING_SCROLL=n}
 * turns the real mouse wheel n times (300 px each, human pauses) before the capture, to see whether Bing loads more
 * result blocks while scrolling, {@code ASKAI_LIVE_BING_PROFILE=key} uses a persistent chat profile.</p>
 */
public class CamoufoxBingSearchLiveTest {

    private Playwright4jDriver driver;

    @After
    public void stop() {
        System.clearProperty(Playwright4jDriver.ADDON_PROPERTY);
        if (driver != null) {
            driver.close();
        }
    }

    /** Why the first result block is hidden: every ancestor that is not plainly visible, with its cause. */
    private String probe() throws Exception {
        return String.valueOf(page().evaluate(PROBE));
    }

    private com.microsoft.playwright.Page page() throws Exception {
        java.lang.reflect.Field field = Playwright4jDriver.class.getDeclaredField("page");
        field.setAccessible(true);
        return (com.microsoft.playwright.Page) field.get(driver);
    }

    /** What Bing's bot detection (its BD module) decides on: reveal flags, cookies, activation, our traces. */
    private static final String BOT_PROBE = "() => {\n"
            + "  const c = document.getElementById('b_content');\n"
            + "  const cookie = (name) => (document.cookie.split('; ').find((x) => x.startsWith(name + '=')) || '')\n"
            + "    .substring(0, 120);\n"
            + "  const rp = performance.getEntriesByType('resource').filter((e) => /\\/rp\\/.*\\.js/.test(e.name));\n"
            + "  const ua = navigator.userActivation;\n"
            + "  return 'b_content=' + (c ? c.getAttribute('style') : 'none')\n"
            + "    + ' visibleBlocks=' + [...document.querySelectorAll('li.b_algo')]\n"
            + "        .filter((e) => e.checkVisibility({visibilityProperty: true})).length\n"
            + "    + ' webdriver=' + navigator.webdriver + ' BN=' + (typeof _G !== 'undefined' ? _G.BN : 'no _G')\n"
            + "    + ' state=' + document.visibilityState + ' focus=' + document.hasFocus()\n"
            + "    + ' activation=' + (ua ? ua.hasBeenActive + '/' + ua.isActive : 'n/a')\n"
            + "    + ' rpScripts=' + rp.length + ' url=' + location.href\n"
            + "    + ' SS=' + cookie('_SS') + ' SRCHHPGUSR=' + cookie('SRCHHPGUSR')\n"
            + "    + ' askaiGlobals=' + Object.keys(window).filter((k) => /askai|playwright|__pw/i.test(k)).join(',');\n"
            + "}";

    private static final String PROBE = "() => {\n"
            + "  const first = document.querySelector('li.b_algo');\n"
            + "  if (!first) return 'no li.b_algo';\n"
            + "  const out = [];\n"
            + "  for (let el = first; el && el !== document.documentElement; el = el.parentElement) {\n"
            + "    const st = getComputedStyle(el);\n"
            + "    out.push(el.tagName + (el.id ? '#' + el.id : '') + '[vis=' + st.visibility + ' op=' + st.opacity\n"
            + "      + ' disp=' + st.display + ' style=' + (el.getAttribute('style') || '') + ']');\n"
            + "  }\n"
            + "  const r = first.getBoundingClientRect();\n"
            + "  return 'box=' + Math.round(r.top) + '/' + Math.round(r.height) + ' checkVisibility='\n"
            + "    + (first.checkVisibility ? first.checkVisibility({visibilityProperty: true}) : 'n/a')\n"
            + "    + ' readyState=' + document.readyState + ' sheets=' + document.styleSheets.length\n"
            + "    + ' chain=' + out.join(' < ');\n"
            + "}";

    @Test
    public void bingResultsAreCapturedVisibleAndExtracted() throws Exception {
        assumeTrue("set ASKAI_LIVE_BING=1 to search the real Bing", "1".equals(System.getenv("ASKAI_LIVE_BING")));
        String query = System.getenv().getOrDefault("ASKAI_LIVE_BING_QUERY", "Kaninchen Ernährung Futter");
        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        if ("0".equals(System.getenv("ASKAI_LIVE_BING_ADDON"))) {
            System.setProperty(Playwright4jDriver.ADDON_PROPERTY, "false");
        }
        long quietMillis = Long.parseLong(System.getenv().getOrDefault("ASKAI_LIVE_BING_QUIET_MS", "0"));
        // ASKAI_LIVE_BING_PROFILE=<key>: a persistent chat profile (cookies survive between runs), as in the app.
        String profileKey = System.getenv("ASKAI_LIVE_BING_PROFILE");
        driver = Playwright4jDriver.launch(CamoufoxProvisioning.CHANNEL, headless, 60_000, profileKey, null,
                settings.consent, settings.captcha);
        driver.setRenderedCapture(new RenderedPageDocumentCapture(settings.analysis));

        // What Bing's SERVER sent (protocol level, no page script): result blocks in the raw document and the request
        // headers, to tell "Bing sends fewer results" from "the page removes them".
        final StringBuilder served = new StringBuilder();
        java.lang.reflect.Field contextField = Playwright4jDriver.class.getDeclaredField("context");
        contextField.setAccessible(true);
        ((com.microsoft.playwright.BrowserContext) contextField.get(driver)).onResponse(response -> {
            try {
                if (response.request().isNavigationRequest() && response.url().contains("/search?")) {
                    String body = response.text();
                    java.util.regex.Matcher blocks = java.util.regex.Pattern
                            .compile("class=\"[^\"]*\\bb_algo\\b").matcher(body);
                    int count = 0;
                    while (blocks.find()) {
                        count++;
                    }
                    String dumpDir = System.getenv("ASKAI_SERP_SNAPSHOT_DIR");
                    if (dumpDir != null && !dumpDir.isEmpty()) {
                        java.nio.file.Path raw = java.nio.file.Paths.get(dumpDir)
                                .resolve("served-" + System.currentTimeMillis() + ".html");
                        java.nio.file.Files.createDirectories(raw.getParent());
                        java.nio.file.Files.write(raw, body.getBytes(StandardCharsets.UTF_8));
                        served.append("raw=").append(raw).append(' ');
                    }
                    served.append("status=").append(response.status()).append(" bytes=").append(body.length())
                            .append(" b_algo=").append(count).append(" request=")
                            .append(response.request().allHeaders());
                }
            } catch (RuntimeException | java.io.IOException unreadable) {
                served.append("unreadable: ").append(unreadable.getMessage());
            }
        });
        // Issue #22, as Argos' web_search: Bing's start page in a tab, the query typed into its field, Enter. Never a
        // result URL (opened directly, Bing answered with random result sets).
        com.aresstack.askai.browser.search.engine.BrowserSearchEngine.SearchForm bing =
                com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog
                        .byId(com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog.BING).formOf(0);
        assertEquals("start page", java.util.Collections.singletonList((String) null),
                driver.openSearchTabs(java.util.Collections.singletonList(bing.getHomeUrl())));
        java.util.List<String> typed = driver.submitSearchInTabs(
                java.util.Collections.singletonList(bing.getSearchBoxSelectors()), query);
        System.err.println("[bing-live] typed: " + typed);
        assertEquals("the query was typed into Bing's field: " + typed, "submitted", typed.get(0));
        String resultUrl = driver.useSearchTab(0).url;
        System.err.println("[bing-live] result page: " + resultUrl);
        assertTrue("the tab shows Bing's result page, not its start page: " + resultUrl,
                resultUrl.contains("/search?"));
        if (quietMillis > 0) {
            // No page read at all until then (Argos' rule): only the Playwright message loop runs.
            driver.pumpEvents(() -> false, quietMillis);
        }
        int wheelTurns = Integer.parseInt(System.getenv().getOrDefault("ASKAI_LIVE_BING_SCROLL", "0"));
        if (wheelTurns > 0) {
            // Real wheel input (WebDriver BiDi input actions), no page script: the pointer rests inside the window.
            com.microsoft.playwright.Page target = page();
            target.mouse().move(300, 300);
            for (int turn = 0; turn < wheelTurns; turn++) {
                target.mouse().wheel(0, 300);
                driver.pumpEvents(() -> false, 350L + (turn % 3) * 120L);
            }
            driver.pumpEvents(() -> false, 1500L);
        }
        boolean diagnostics = "1".equals(System.getenv("ASKAI_LIVE_BING_DIAG"));
        System.err.println("[bing-live] addon=" + (driver.addonBridge() != null) + " quietMs=" + quietMillis
                + " wheelTurns=" + wheelTurns + " diag=" + diagnostics);
        RenderedPageDocument document = driver.captureRenderedPage(new PublicSuffixDomainKeyResolver(), 1L);

        // Bing's organic results are the li.b_algo items; AI answer cards may carry a div.b_algo of
        // their own, which is no result.
        int blocks = 0;
        int visibleBlocks = 0;
        java.util.Set<String> resultBlockIds = new java.util.LinkedHashSet<String>();
        for (RenderedContainerDescriptor container : document.containers) {
            if (container.classTokens.contains("b_algo") && "li".equalsIgnoreCase(container.tagName)) {
                blocks++;
                resultBlockIds.add(container.containerId);
                if (container.visible) {
                    visibleBlocks++;
                }
            }
        }
        SearchResultExtractionResult extraction =
                new LegacySearchResultExtractor(settings).extract(document, query);
        int structured = 0;
        java.util.Set<String> takenOver = new java.util.HashSet<String>();
        int relevant = 0;
        String[] terms = query.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        for (SearchResultCandidate candidate : extraction.candidates) {
            String said = (candidate.title + " " + candidate.resolvedTargetUrl).toLowerCase(java.util.Locale.ROOT);
            for (String term : terms) {
                if (term.length() > 3 && said.contains(term.substring(0, Math.min(term.length(), 6)))) {
                    relevant++;
                    break;
                }
            }
            System.err.println("[bing-live] " + candidate.originalRank + " " + candidate.resolvedTargetUrl
                    + (candidate.resultBlockContainerId.isEmpty() ? " (harvested)" : " " + candidate.title));
            if (!candidate.resultBlockContainerId.isEmpty()) {
                structured++;
                takenOver.add(candidate.resultBlockContainerId);
            }
        }
        System.err.println("[bing-live] page=" + document.pageUrl + " warnings=" + document.captureWarnings);
        System.err.println("[bing-live] served document: " + served);
        System.err.println("[bing-live] relevant=" + relevant + " of " + extraction.candidates.size()
                + " candidates name a query term in title or URL");
        System.err.println("[bing-live] b_algo=" + blocks + " visible=" + visibleBlocks + " outcome="
                + extraction.outcome + " candidates=" + extraction.candidates.size() + " structured=" + structured);

        if (diagnostics) {
            // Main-world scripts: only after everything above was measured, so they cannot influence it.
            System.err.println("[bing-live] after capture: " + probe());
            System.err.println("[bing-live] after capture: " + page().evaluate(BOT_PROBE));
        }

        assertTrue("Bing delivered result blocks (consent or challenge page?): " + blocks, blocks >= 5);
        assertEquals("every Bing result block is captured visible", blocks, visibleBlocks);
        assertEquals(extraction.diagnostics.toString(), SearchPageAnalysisOutcome.ORGANIC_RESULTS,
                extraction.outcome);
        java.util.Set<String> missing = new java.util.LinkedHashSet<String>(resultBlockIds);
        missing.removeAll(takenOver);
        assertTrue("every Bing result block is taken over as a structured candidate, missing "
                + missing + " " + extraction.diagnostics, missing.isEmpty());
        assertTrue("the results answer the query (" + relevant + " of " + extraction.candidates.size() + ")",
                relevant * 2 >= extraction.candidates.size());
    }
}
