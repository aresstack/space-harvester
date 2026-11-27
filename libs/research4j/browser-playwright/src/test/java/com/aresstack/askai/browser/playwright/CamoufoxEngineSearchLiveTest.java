package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.LegacySearchEngineAttemptResult;
import com.aresstack.askai.browser.WebSearchItem;
import com.aresstack.askai.browser.WebSearchResult;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import org.junit.After;
import org.junit.Test;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.SearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.SearchEngineCatalogEntry;
import com.aresstack.askai.browser.search.engine.SearchEngineImplementationStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assume.assumeTrue;

/**
 * Issue #22 live acceptance per engine, through the product path ({@link PlaywrightSessionFactory}, real Camoufox,
 * the shipped settings, real typing into the engine's start page): runs {@code search(query, engines)} and prints
 * every item and every engine attempt. With {@code ASKAI_SERP_SNAPSHOT_DIR} set, the driver writes each result page
 * as {@code <snapshot>.html} (scripts removed) and {@code <snapshot>.capture.json} (the rendered capture the
 * extraction works on), so a failing engine can be replayed offline.
 *
 * <p>Opt-in: {@code ASKAI_LIVE_ENGINES=google} (comma-separated catalog ids) or {@code ASKAI_LIVE_ENGINES=experimental}
 * (every {@code EXPERIMENTAL} engine of the catalog that has a driver, each searched on its own, one after another,
 * so each gets its own attempt line and item count: the check before an engine becomes {@code SUPPORTED}). Never
 * part of the default CI run: it sends real requests from the machine's IP. Optional {@code ASKAI_LIVE_QUERY}
 * (default "Java 21 LTS release") and {@code ASKAI_LIVE_CAMOUFOX_HEADED=1}. Diagnostic output only, no assertion
 * on the engine's answer: what an engine serves changes from day to day.</p>
 */
public class CamoufoxEngineSearchLiveTest {

    private BrowserSession session;

    @After
    public void stop() {
        if (session != null) {
            long closing = System.currentTimeMillis();
            session.close();
            System.err.println("[engine-live] session closed in " + (System.currentTimeMillis() - closing) + " ms");
        }
    }

    @Test
    public void searchesWithTheNamedEnginesAndReportsEveryAttempt() throws Exception {
        String engines = System.getenv("ASKAI_LIVE_ENGINES");
        assumeTrue("set ASKAI_LIVE_ENGINES=<ids> to search real engines", engines != null && !engines.isEmpty());
        String query = System.getenv().getOrDefault("ASKAI_LIVE_QUERY", "Java 21 LTS release");
        LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        session = PlaywrightSessionFactory.create(CamoufoxProvisioning.CHANNEL, headless, false, null,
                BrowserLimits.defaults(), settings);
        if (EXPERIMENTAL.equalsIgnoreCase(engines.trim())) {
            List<String> failed = new ArrayList<String>();
            for (String id : experimentalEnginesWithDriver()) {
                try {
                    report(query, Collections.singletonList(id)); // one engine per search: one verdict per engine
                } catch (Exception | AssertionError engineFailure) {
                    // an unreachable engine is that engine's verdict; the sweep goes on with the next one
                    failed.add(id);
                    System.err.println("[engine-live] engines=[" + id + "] query=" + query + " FAILED: "
                            + engineFailure);
                }
            }
            System.err.println("[engine-live] experimental sweep done; failed=" + failed);
            return;
        }
        report(query, Arrays.asList(engines.split("\\s*,\\s*")));
    }

    private static final String EXPERIMENTAL = "experimental";

    /** Offline: which engines the "experimental" run covers (no browser, no request). */
    @Test
    public void theExperimentalRunCoversEveryUnvalidatedDriverButNotGoogle() {
        List<String> ids = experimentalEnginesWithDriver();
        org.junit.Assert.assertTrue(ids.toString(), ids.contains(BrowserSearchEngineCatalog.SEZNAM));
        org.junit.Assert.assertTrue(ids.toString(), ids.contains(BrowserSearchEngineCatalog.YAHOO_JAPAN));
        org.junit.Assert.assertFalse("validated live", ids.contains(BrowserSearchEngineCatalog.STARTPAGE));
        org.junit.Assert.assertFalse("Google is SUPPORTED and runs in the search browser",
                ids.contains(BrowserSearchEngineCatalog.GOOGLE));
        org.junit.Assert.assertFalse(ids.contains(BrowserSearchEngineCatalog.DUCKDUCKGO));
    }

    /** The catalog's EXPERIMENTAL engines that the research browser can search with. */
    static List<String> experimentalEnginesWithDriver() {
        List<String> ids = new ArrayList<String>();
        for (SearchEngineCatalogEntry entry : SearchEngineCatalog.entries()) {
            if (entry.getStatus() == SearchEngineImplementationStatus.EXPERIMENTAL
                    && BrowserSearchEngineCatalog.byId(entry.getId()) != null
                    && !BrowserSearchEngineCatalog.runsInSearchBrowser(entry.getId())) {
                ids.add(entry.getId());
            }
        }
        return ids;
    }

    private void report(String query, List<String> ids) throws Exception {
        long started = System.currentTimeMillis();
        WebSearchResult result = session.search(query, ids);
        System.err.println("[engine-live] engines=" + ids + " query=" + query + " items="
                + result.getItems().size() + " in " + (System.currentTimeMillis() - started) + " ms hosts="
                + result.getProviderHosts());
        for (LegacySearchEngineAttemptResult attempt : result.getAttempts()) {
            System.err.println("[engine-live] attempt " + attempt);
        }
        for (WebSearchItem item : result.getItems()) {
            System.err.println("[engine-live] item " + item.getUrl() + " | " + item.getTitle());
        }
    }
}
