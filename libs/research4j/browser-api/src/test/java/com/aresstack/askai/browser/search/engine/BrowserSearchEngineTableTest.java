package com.aresstack.askai.browser.search.engine;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The engine table of Search → Engines as stored: priorities, languages, duplicates and the migration. */
public class BrowserSearchEngineTableTest {

    private static BrowserSearchEngineSelection.Entry row(String id, int priority, String... languages) {
        return new BrowserSearchEngineSelection.Entry(id, true, 3, 0, priority, Arrays.asList(languages),
                Collections.<String>emptyList());
    }

    private static List<String> ids(BrowserSearchEngineSelection selection) {
        List<String> ids = new java.util.ArrayList<String>();
        for (BrowserSearchEngineSelection.Entry entry : selection.getEntries()) {
            ids.add(entry.getEngineId() + "@" + entry.getPriority());
        }
        return ids;
    }

    @Test
    public void rowsAreSortedByPriorityAndKeepTheirStoredOrderWithinAPriority() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                row("google", 1, "de"), row("startpage", 0, "de"), row("duckduckgo", 0, "en"),
                row("google", 3, "fr", "es"), row("bing", 1, "de")), null);
        assertEquals(Arrays.asList("startpage@0", "duckduckgo@0", "google@1", "bing@1", "google@3"),
                ids(selection));
    }

    @Test
    public void theSameEngineMayHaveSeveralRowsAndIsVisitedOnce() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                row("google", 1, "de"), row("google", 3, "fr", "es")), null);
        assertEquals(2, selection.getEntries().size());
        assertEquals(1, selection.resolvedEnabledEngines().size());
        BrowserSearchEngineSelection restored = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries(selection.encodeEntries()), null);
        assertEquals(Arrays.asList("de"), restored.getEntries().get(0).getLanguages());
        assertEquals(Arrays.asList("fr", "es"), restored.getEntries().get(1).getLanguages());
    }

    @Test
    public void everyColumnSurvivesTheFlatForm() {
        BrowserSearchEngineSelection.Entry google = new BrowserSearchEngineSelection.Entry("google", true, 1, 15_000,
                2, Arrays.asList("de", "zh_cn"), Arrays.asList("pl", "UK", "pl", "polish"));
        String encoded = new BrowserSearchEngineSelection(Collections.singletonList(google), null).encodeEntries();
        assertEquals("google:on:1:15:2:de+zh-CN:pl+uk", encoded);
        BrowserSearchEngineSelection.Entry back = BrowserSearchEngineSelection.parseEntries(encoded).get(0);
        assertEquals(1, back.getResultPages());
        assertEquals(15_000, back.getDelayMillis());
        assertEquals(2, back.getPriority());
        assertEquals(Arrays.asList("de", "zh-CN"), back.getLanguages());
        assertEquals(Arrays.asList("pl", "uk"), back.getFurtherLanguages());
        assertEquals(Arrays.asList("de", "zh-CN", "pl", "uk"), back.effectiveLanguages());
    }

    @Test
    public void aRowFromTheSwitchedFormKeepsOnlySwitchedOnFurtherLanguages() {
        List<BrowserSearchEngineSelection.Entry> rows = BrowserSearchEngineSelection.parseEntries(
                "yandex:on:3:0:4:ru:off:uk+be,google:on:1:15:0:de:on:pl");
        assertEquals(Collections.<String>emptyList(), rows.get(0).getFurtherLanguages());
        assertEquals(Arrays.asList("pl"), rows.get(1).getFurtherLanguages());
        assertEquals("google:on:1:15:0:de:pl,yandex:on:3:0:4:ru:",
                new BrowserSearchEngineSelection(rows, null).encodeEntries());
    }

    @Test
    public void anOldListKeepsItsOrderAsPrioritiesAndGetsTheCatalogLanguages() {
        String migrated = BrowserSearchEngineSelection.migrate("google:on:1:15,duckduckgo:on:3,bing:off:3",
                EngineAcquisitionMode.FIRST_USABLE);
        assertEquals("google:on:1:15:0:de+en+fr+es+it:,duckduckgo:on:3:0:1:de+en:,"
                + "bing:off:3:0:2:de+en+fr+es:", migrated);
        assertEquals("migrating twice changes nothing", migrated,
                BrowserSearchEngineSelection.migrate(migrated, EngineAcquisitionMode.FIRST_USABLE));
    }

    @Test
    public void aGoogleRowWithoutADelayFieldGetsTheCatalogDelayAndAStoredDelayStays() {
        BrowserSearchEngineSelection fromBefore = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries("google:on:3,duckduckgo:on:3"),
                EngineAcquisitionMode.FIRST_USABLE);
        assertEquals("the old form could not express a pause, so Google starts with the catalog's",
                SearchEngineCatalog.GOOGLE_REQUEST_DELAY_MILLIS, fromBefore.delayMillisFor("google"));
        assertEquals("DuckDuckGo takes requests as they come", 0, fromBefore.delayMillisFor("duckduckgo"));
        BrowserSearchEngineSelection stored = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries("google:on:3:0:0:de+en:"),
                EngineAcquisitionMode.FIRST_USABLE);
        assertEquals("a delay the table wrote, 0 included, is the user's choice", 0, stored.delayMillisFor("google"));
        assertEquals("a row made without a delay starts with the catalog's too",
                SearchEngineCatalog.GOOGLE_REQUEST_DELAY_MILLIS,
                new BrowserSearchEngineSelection.Entry("google", true).getDelayMillis());
    }

    @Test
    public void anOldAllEnabledListBecomesOneGroup() {
        String migrated = BrowserSearchEngineSelection.migrate("duckduckgo:on:3,bing:on:3:1.5",
                EngineAcquisitionMode.ALL_ENABLED);
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries(migrated), EngineAcquisitionMode.ALL_ENABLED);
        assertEquals("the old list order stays the order the attempt budget is spent in",
                Arrays.asList("duckduckgo@0", "bing@0"), ids(selection));
        assertEquals(1500, selection.delayMillisFor("bing"));
    }

    @Test
    public void theCodecMigratesAStoredOldListWithTheStoredStrategy() {
        Map<String, String> stored = new HashMap<String, String>();
        stored.put("navigation.engines", "duckduckgo:off:3,bing:off:3,google:on:3");
        stored.put("navigation.engineAcquisitionMode", "FIRST_USABLE");
        Map<String, String> migrated = LegacyBrowserSearchSettingsCodec.migrateEngineList(stored);
        assertEquals("a Google row from before delays existed starts with the catalog's Google delay",
                "duckduckgo:off:3:0:0:de+en:,bing:off:3:0:1:de+en+fr+es:,"
                + "google:on:3:15:2:de+en+fr+es+it:", migrated.get("navigation.engines"));
        BrowserSearchEngineSelection decoded = LegacyBrowserSearchSettingsCodec.fromValues(stored)
                .settings.navigation.engineSelection;
        assertEquals(Arrays.asList("duckduckgo@0", "bing@1", "google@2"), ids(decoded));
    }

    @Test
    public void aRowOfAnEngineWithoutDriverIsKeptButNeverVisited() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                row("mojeek", 0, "de"), row("duckduckgo", 1, "de")), null);
        assertEquals(2, selection.getEntries().size());
        assertEquals(1, selection.resolvedEnabledEngines().size());
        assertEquals("duckduckgo", selection.resolvedEnabledEngines().get(0).getId());
        assertFalse(SearchEngineCatalog.byId("mojeek").isSearchable());
    }

    @Test
    public void theFirstRowOfAnEngineSuppliesItsPagesAndDelay() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                new BrowserSearchEngineSelection.Entry("google", true, 2, 4000, 3, Arrays.asList("fr"),
                        Collections.<String>emptyList()),
                new BrowserSearchEngineSelection.Entry("google", true, 1, 15_000, 0, Arrays.asList("de"),
                        Collections.<String>emptyList())), null);
        assertEquals(15_000, selection.delayMillisFor("google"));
        assertEquals(1, selection.resultPagesFor("google"));
    }
}
