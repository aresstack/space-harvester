package com.aresstack.askai.browser.search;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngine;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The engine list is a user decision that has to survive a restart — order included. An order that is
 * only honoured while the app runs is not an order, it is a suggestion.
 */
public class BrowserSearchEngineSelectionTest {

    private static BrowserSearchEngineSelection selection(EngineAcquisitionMode mode, String... ids) {
        List<BrowserSearchEngineSelection.Entry> entries =
                new java.util.ArrayList<BrowserSearchEngineSelection.Entry>();
        for (String id : ids) {
            boolean off = id.endsWith("!");
            entries.add(new BrowserSearchEngineSelection.Entry(off ? id.substring(0, id.length() - 1) : id,
                    !off));
        }
        return new BrowserSearchEngineSelection(entries, mode);
    }

    @Test
    public void theArrangedOrderIsWhatIsStoredAndReadBack() {
        BrowserSearchEngineSelection moved = selection(EngineAcquisitionMode.FIRST_USABLE,
                BrowserSearchEngineCatalog.BING, BrowserSearchEngineCatalog.DUCKDUCKGO);

        List<BrowserSearchEngineSelection.Entry> restored =
                BrowserSearchEngineSelection.parseEntries(moved.encodeEntries());

        assertEquals(2, restored.size());
        assertEquals(BrowserSearchEngineCatalog.BING, restored.get(0).getEngineId());
        assertEquals(BrowserSearchEngineCatalog.DUCKDUCKGO, restored.get(1).getEngineId());
        assertTrue(restored.get(0).isEnabled());
    }

    @Test
    public void aDisabledEngineIsNotVisitedAndSaysSoAcrossARestart() {
        BrowserSearchEngineSelection stored = selection(EngineAcquisitionMode.FIRST_USABLE,
                BrowserSearchEngineCatalog.DUCKDUCKGO, BrowserSearchEngineCatalog.BING + "!");

        BrowserSearchEngineSelection restored = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries(stored.encodeEntries()),
                EngineAcquisitionMode.FIRST_USABLE);

        List<BrowserSearchEngine> visited = restored.resolvedEnabledEngines();
        assertEquals(1, visited.size());
        assertEquals(BrowserSearchEngineCatalog.DUCKDUCKGO, visited.get(0).getId());
    }

    /** An id from a newer build is skipped, not fatal — but it must not empty the whole list either. */
    @Test
    public void anUnknownEngineIdIsSkippedRatherThanBreakingTheSearch() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries("mystery:on,duckduckgo:on"),
                EngineAcquisitionMode.FIRST_USABLE);

        List<BrowserSearchEngine> visited = selection.resolvedEnabledEngines();
        assertEquals(1, visited.size());
        assertEquals(BrowserSearchEngineCatalog.DUCKDUCKGO, visited.get(0).getId());
    }

    /** The order also survives the settings codec — the layer that actually writes it to disk. */
    @Test
    public void theOrderSurvivesTheSettingsCodec() {
        LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();
        LegacySearchNavigationSettings navigation = new LegacySearchNavigationSettings(
                selection(EngineAcquisitionMode.ALL_ENABLED, BrowserSearchEngineCatalog.BING,
                        BrowserSearchEngineCatalog.DUCKDUCKGO),
                defaults.navigation.maximumEngineAttempts,
                defaults.navigation.navigationCommitTimeoutMillis,
                defaults.navigation.redirectResolutionEnabled,
                defaults.navigation.maximumRedirectUrlLength,
                defaults.navigation.searchResultLimit,
                defaults.navigation.language, defaults.navigation.country);
        LegacyBrowserSearchSettings reordered = new LegacyBrowserSearchSettings(navigation,
                defaults.consent, defaults.captcha, defaults.readiness, defaults.analysis,
                defaults.visualAnalysis, defaults.extraction, defaults.aiLayoutResolver,
                defaults.reranker, defaults.diagnostics, defaults.layoutRepair);

        Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(reordered);
        LegacyBrowserSearchSettingsCodec.Decoded decoded =
                LegacyBrowserSearchSettingsCodec.fromValues(values);

        assertTrue(decoded.violations.toString(), decoded.violations.isEmpty());
        assertEquals(EngineAcquisitionMode.ALL_ENABLED,
                decoded.settings.navigation.engineSelection.getMode());
        assertEquals(Arrays.asList(BrowserSearchEngineCatalog.BING,
                        BrowserSearchEngineCatalog.DUCKDUCKGO),
                idsOf(decoded.settings.navigation.engineSelection));
    }

    /**
     * The shipped default is the product decision: Startpage first, DuckDuckGo behind it, Google last (search
     * browser), one at a time; Bing is listed but off (DuckDuckGo's results come from Bing).
     */
    @Test
    public void theDefaultAsksStartpageFirstThenDuckDuckGoThenGoogleAndLeavesBingOff() {
        BrowserSearchEngineSelection shipped =
                LegacyBrowserSearchDefaults.create().navigation.engineSelection;

        assertEquals(EngineAcquisitionMode.FIRST_USABLE, shipped.getMode());
        assertEquals(Arrays.asList(BrowserSearchEngineCatalog.STARTPAGE, BrowserSearchEngineCatalog.DUCKDUCKGO,
                BrowserSearchEngineCatalog.GOOGLE), idsOf(shipped));
        assertEquals(4, shipped.getEntries().size());
        assertEquals(BrowserSearchEngineCatalog.BING, shipped.getEntries().get(3).getEngineId());
        org.junit.Assert.assertFalse(shipped.getEntries().get(3).isEnabled());
        org.junit.Assert.assertTrue(BrowserSearchEngineCatalog.runsInSearchBrowser(BrowserSearchEngineCatalog.GOOGLE));
        org.junit.Assert.assertFalse(
                BrowserSearchEngineCatalog.runsInSearchBrowser(BrowserSearchEngineCatalog.DUCKDUCKGO));
    }

    private static List<String> idsOf(BrowserSearchEngineSelection selection) {
        List<String> ids = new java.util.ArrayList<String>();
        for (BrowserSearchEngine engine : selection.resolvedEnabledEngines()) {
            ids.add(engine.getId());
        }
        return ids;
    }

    @Test
    public void aSearchPageNamedOnlyByItsAddressIsAskedThroughItsOwnSearchField() {
        String portal = "https://portal.example/";
        assertTrue(BrowserSearchEngineCatalog.isSearchPage(portal));

        BrowserSearchEngine engine = BrowserSearchEngineCatalog.byId(portal);
        assertEquals(portal, engine.getId());
        assertEquals("portal.example", engine.getDisplayName());
        assertTrue(engine.usesSearchForms());
        assertEquals(portal, engine.formOf(0).getHomeUrl());
        assertTrue(engine.formOf(0).getSearchBoxSelectors().contains("input[type=search]"));
        org.junit.Assert.assertFalse(BrowserSearchEngineCatalog.runsInSearchBrowser(portal));
    }

    @Test
    public void aSearchPageOfTheUsersOwnIsAnEngineWithItsUrlAsId() {
        String portal = "https://portal.example:8443/suche?q={query}&bereich=a,b";
        assertTrue(BrowserSearchEngineCatalog.isSearchPage(portal));
        org.junit.Assert.assertFalse(BrowserSearchEngineCatalog.isSearchPage("ftp://portal.example/{query}"));
        org.junit.Assert.assertFalse(BrowserSearchEngineCatalog.isSearchPage("Reisekosten"));

        BrowserSearchEngine engine = BrowserSearchEngineCatalog.byId(portal);
        assertEquals(portal, engine.getId());
        assertEquals("portal.example:8443", engine.getDisplayName());
        assertEquals(java.util.Collections.singletonList(portal), engine.getEndpointTemplates());
        org.junit.Assert.assertFalse(BrowserSearchEngineCatalog.runsInSearchBrowser(portal));
    }

    @Test
    public void searchPagesOfEqualPriorityFormOneGroupAndSurviveTheStoredForm() {
        String first = "https://portal.example/suche?q={query}";
        String second = "https://wiki.example/find?text={query}&x=50%25";
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                new BrowserSearchEngineSelection.Entry(first, true, 1, 0, 0, null, null),
                new BrowserSearchEngineSelection.Entry(second, true, 1, 0, 0, null, null)),
                EngineAcquisitionMode.ALL_ENABLED);

        List<BrowserSearchEngineSelection.Entry> back =
                BrowserSearchEngineSelection.parseEntries(selection.encodeEntries());
        assertEquals(2, back.size());
        assertEquals(java.util.Arrays.asList(first, second),
                Arrays.asList(back.get(0).getEngineId(), back.get(1).getEngineId()));
        assertEquals(0, back.get(0).getPriority());
        assertEquals(0, back.get(1).getPriority());
        assertEquals(2, new BrowserSearchEngineSelection(back, EngineAcquisitionMode.ALL_ENABLED)
                .resolvedEnabledEngines().size());
    }

    @Test
    public void aSearchPageNamedTwiceIsVisitedOnce() {
        String portal = "https://portal.example/suche?q={query}";
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                new BrowserSearchEngineSelection.Entry(portal, true, 1, 0, 0, null, null),
                new BrowserSearchEngineSelection.Entry(portal, true, 1, 0, 1, null, null)),
                EngineAcquisitionMode.ALL_ENABLED);
        assertEquals(1, selection.resolvedEnabledEngines().size());
    }
}
