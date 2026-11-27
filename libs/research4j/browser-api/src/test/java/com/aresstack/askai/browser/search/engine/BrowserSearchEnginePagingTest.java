package com.aresstack.askai.browser.search.engine;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Per-engine result paging. The catalog engines are asked through their start pages' search fields and page by a
 * real click on their own "next" control, so they have no result URL at all. A URL-template engine (the dev/test
 * override): page 1 is the plain endpoint template, deeper pages come from its OWN pagination template, and one
 * without a pagination template simply cannot page. The per-engine page count is the user's
 * setting, carried in the selection entries with a backward-compatible flat form.
 */
public class BrowserSearchEnginePagingTest {

    @Test
    public void theCatalogEnginesAreAskedThroughTheirStartPagesNeverByAResultUrl() {
        BrowserSearchEngine bing = BrowserSearchEngineCatalog.byId(BrowserSearchEngineCatalog.BING);
        assertTrue(bing.usesSearchForms());
        assertEquals("https://www.bing.com/", bing.formOf(0).getHomeUrl());
        assertEquals("#sb_form_q", bing.formOf(0).getSearchBoxSelectors().get(0));
        assertEquals("a.sb_pagN", bing.formOf(0).getNextPageSelectors().get(0));
        assertNull("no result URL for page 1", bing.pageUrl(0, "hase", 1));
        assertNull("no result URL for page 2", bing.pageUrl(0, "hase", 2));

        BrowserSearchEngine ddg = BrowserSearchEngineCatalog.byId(BrowserSearchEngineCatalog.DUCKDUCKGO);
        assertEquals(Arrays.asList("https://html.duckduckgo.com/html/", "https://lite.duckduckgo.com/lite/"),
                ddg.getEndpointTemplates());
        assertEquals("input[name=q]", ddg.formOf(1).getSearchBoxSelectors().get(0));
        assertNull(ddg.pageUrl(1, "hase", 1));

        BrowserSearchEngine google = BrowserSearchEngineCatalog.byId(BrowserSearchEngineCatalog.GOOGLE);
        assertEquals("https://www.google.com/", google.formOf(0).getHomeUrl());
        assertEquals("#pnnext", google.formOf(0).getNextPageSelectors().get(0));
        assertNull("an endpoint index without a form", google.formOf(1));
    }

    @Test
    public void anEngineWithoutAPaginationTemplateCannotPage() {
        BrowserSearchEngine custom = BrowserSearchEngineCatalog.custom("http://x.test/s?q={query}");
        assertEquals("http://x.test/s?q=hase", custom.pageUrl(0, "hase", 1));
        assertNull("no template, no deeper page", custom.pageUrl(0, "hase", 2));
        assertNull(custom.pageUrl(7, "hase", 1)); // unknown endpoint index
        assertFalse("a URL template is no search form", custom.usesSearchForms());
        assertNull(custom.formOf(0));
    }

    @Test
    public void selectionEntriesCarryThePageCountBackwardCompatibly() {
        List<BrowserSearchEngineSelection.Entry> entries = Arrays.asList(
                new BrowserSearchEngineSelection.Entry("duckduckgo", true, 5),
                new BrowserSearchEngineSelection.Entry("bing", false));
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(entries, null);
        assertEquals("duckduckgo:on:5:0:0:de+en:,bing:off:3:0:1:de+en+fr+es:",
                selection.encodeEntries());

        List<BrowserSearchEngineSelection.Entry> parsed =
                BrowserSearchEngineSelection.parseEntries("duckduckgo:on:5,bing:off:3");
        assertEquals(5, parsed.get(0).getResultPages());
        assertEquals(3, parsed.get(1).getResultPages());

        // Older configurations without the third part keep the documented default.
        List<BrowserSearchEngineSelection.Entry> legacy =
                BrowserSearchEngineSelection.parseEntries("duckduckgo:on,bing:off");
        assertEquals(BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                legacy.get(0).getResultPages());
        assertTrue(legacy.get(0).isEnabled());
        assertEquals("nonsense falls back", BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                BrowserSearchEngineSelection.parseEntries("bing:on:banane").get(0).getResultPages());
    }

    @Test
    public void theDelayTravelsAsSecondsWithOnlyRelevantDecimals() {
        assertEquals("1.5", BrowserSearchEngineSelection.formatDelaySeconds(1500));
        assertEquals("0.25", BrowserSearchEngineSelection.formatDelaySeconds(250));
        assertEquals("trailing zeros and a bare point are removed",
                "2", BrowserSearchEngineSelection.formatDelaySeconds(2000));
        assertEquals("1.005", BrowserSearchEngineSelection.formatDelaySeconds(1005));
        assertEquals("0", BrowserSearchEngineSelection.formatDelaySeconds(0));

        assertEquals(1500, BrowserSearchEngineSelection.parseDelayMillis("1.5"));
        assertEquals("a decimal comma is read like a point",
                250, BrowserSearchEngineSelection.parseDelayMillis("0,25"));
        assertEquals("nonsense means OFF", 0, BrowserSearchEngineSelection.parseDelayMillis("schnell"));
        assertEquals("negative means OFF", 0, BrowserSearchEngineSelection.parseDelayMillis("-3"));
    }

    @Test
    public void selectionEntriesCarryTheDelayAndOmitAnInactiveOne() {
        List<BrowserSearchEngineSelection.Entry> entries = Arrays.asList(
                new BrowserSearchEngineSelection.Entry("duckduckgo", true, 3, 1500),
                new BrowserSearchEngineSelection.Entry("bing", true, 3, 0));
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(entries, null);
        assertEquals("the delay is a column of every row, 0 included",
                "duckduckgo:on:3:1.5:0:de+en:,bing:on:3:0:1:de+en+fr+es:", selection.encodeEntries());
        assertEquals(1500, selection.delayMillisFor("duckduckgo"));
        assertEquals(0, selection.delayMillisFor("bing"));
        assertEquals("unknown engines run without an extra pause",
                0, selection.delayMillisFor("unknown-engine"));

        List<BrowserSearchEngineSelection.Entry> parsed =
                BrowserSearchEngineSelection.parseEntries("duckduckgo:on:3:1.5,bing:on:3");
        assertEquals(1500, parsed.get(0).getDelayMillis());
        assertEquals(0, parsed.get(1).getDelayMillis());
        assertEquals("an unreadable delay part falls back to OFF", 0,
                BrowserSearchEngineSelection.parseEntries("bing:on:3:zackig").get(0).getDelayMillis());
    }

    @Test
    public void resultPagesForAnswersFromTheEntriesElseTheDefault() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(
                Collections.singletonList(new BrowserSearchEngineSelection.Entry("bing", true, 7)),
                null);
        assertEquals(7, selection.resultPagesFor("bing"));
        assertEquals(BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                selection.resultPagesFor("unknown-engine"));
    }
}
