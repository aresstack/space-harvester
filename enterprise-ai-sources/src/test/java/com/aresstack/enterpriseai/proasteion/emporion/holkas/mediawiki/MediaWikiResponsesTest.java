package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MediaWikiResponsesTest {

    @Test
    public void parsesParseResponseInBothFormatVersions() throws Exception {
        WikiParsedPage v2 = MediaWikiResponses.parsedPage(
                "{\"parse\":{\"title\":\"Haupt Seite\",\"pageid\":7,\"revid\":42,\"text\":\"<p>Hallo</p>\"}}");
        assertEquals("Haupt Seite", v2.title());
        assertEquals(7L, v2.pageId());
        assertEquals(42L, v2.revisionId());
        assertEquals("<p>Hallo</p>", v2.html());

        WikiParsedPage v1 = MediaWikiResponses.parsedPage(
                "{\"parse\":{\"title\":\"X\",\"pageid\":1,\"revid\":2,\"text\":{\"*\":\"<p>alt</p>\"}}}");
        assertEquals("<p>alt</p>", v1.html());
    }

    @Test
    public void parsesPageInfoWithNormalizationRedirectsAndMissingPages() throws Exception {
        List<WikiPageInfo> infos = MediaWikiResponses.pageInfos("{\"query\":{"
                + "\"normalized\":[{\"from\":\"foo\",\"to\":\"Foo\"}],"
                + "\"redirects\":[{\"from\":\"Foo\",\"to\":\"Bar\"}],"
                + "\"pages\":[{\"pageid\":3,\"ns\":0,\"title\":\"Bar\",\"touched\":\"2026-01-01T00:00:00Z\","
                + "\"lastrevid\":9,\"fullurl\":\"https://w/wiki/Bar\",\"contentmodel\":\"wikitext\"},"
                + "{\"ns\":0,\"title\":\"Nope\",\"missing\":true}]}}");
        assertEquals(2, infos.size());
        WikiPageInfo bar = infos.get(0);
        assertEquals("Bar", bar.title());
        assertEquals("foo", bar.requestedTitle());
        assertEquals(9L, bar.revisionId());
        assertEquals("2026-01-01T00:00:00Z", bar.touched());
        assertEquals("https://w/wiki/Bar", bar.fullUrl());
        assertFalse(bar.missing());
        assertTrue(infos.get(1).missing());
        assertEquals("Nope", infos.get(1).requestedTitle());
    }

    @Test
    public void parsesLinksWithContinuation() throws Exception {
        MediaWikiResponses.LinksPage page = MediaWikiResponses.links("{\"continue\":{\"plcontinue\":\"5|0|X\"},"
                + "\"query\":{\"pages\":[{\"title\":\"A\",\"links\":[{\"ns\":0,\"title\":\"B\"},{\"ns\":0,\"title\":\"C\"}]}]}}");
        assertEquals(2, page.titles.size());
        assertEquals("5|0|X", page.continuation);

        MediaWikiResponses.LinksPage last = MediaWikiResponses.links("{\"batchcomplete\":true,"
                + "\"query\":{\"pages\":[{\"title\":\"A\"}]}}");
        assertTrue(last.titles.isEmpty());
        assertNull(last.continuation);
    }

    @Test
    public void parsesSearchHitsAndStripsHighlightMarkup() throws Exception {
        List<WikiSearchHit> hits = MediaWikiResponses.searchHits("{\"query\":{\"search\":[{\"ns\":0,\"title\":\"A\","
                + "\"pageid\":1,\"snippet\":\"ein <span class=\\\"searchmatch\\\">Treffer</span> &amp; mehr\","
                + "\"timestamp\":\"2026-01-01T00:00:00Z\"}]}}");
        assertEquals(1, hits.size());
        assertEquals("ein Treffer & mehr", hits.get(0).snippet());
    }

    @Test
    public void mapsApiErrorsToNeutralKinds() {
        assertKind("{\"error\":{\"code\":\"missingtitle\",\"info\":\"x\"}}", Kind.NOT_FOUND);
        assertKind("{\"error\":{\"code\":\"readapidenied\",\"info\":\"x\"}}", Kind.ACCESS_DENIED);
        assertKind("{\"error\":{\"code\":\"maxlag\",\"info\":\"x\"}}", Kind.UNAVAILABLE);
        assertKind("{\"error\":{\"code\":\"internal_api_error_DBQueryError\"}}", Kind.UNAVAILABLE);
        assertKind("{\"error\":{\"code\":\"somethingelse\"}}", Kind.INVALID_RESPONSE);
        assertKind("<html>Login</html>", Kind.INVALID_RESPONSE);
        assertKind("[1,2]", Kind.INVALID_RESPONSE);
    }

    @Test
    public void errorMessageIsAbbreviated() {
        StringBuilder longInfo = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longInfo.append('x');
        }
        try {
            MediaWikiResponses.parseObject("{\"error\":{\"code\":\"c\",\"info\":\"" + longInfo + "\"}}");
            fail();
        } catch (KnowledgeSourceException e) {
            assertTrue(e.getMessage().length() < 300);
        }
    }

    private static void assertKind(String body, Kind kind) {
        try {
            MediaWikiResponses.parseObject(body);
            fail("expected " + kind);
        } catch (KnowledgeSourceException e) {
            assertEquals(kind, e.kind());
        }
    }
}
