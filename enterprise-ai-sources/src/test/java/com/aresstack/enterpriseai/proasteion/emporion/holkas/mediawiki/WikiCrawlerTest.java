package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class WikiCrawlerTest {

    private final FakeMediaWikiTransport wiki = new FakeMediaWikiTransport();
    private WikiCrawler crawler;

    @Before
    public void setUp() {
        crawler = new WikiCrawler(new MediaWikiClient(MediaWikiSiteConfig.builder("w", "https://wiki.example").build(),
                wiki, MediaWikiCredentialsProvider.anonymous()));
        // Start -> A, B, Alt(->C), Fehlt;  A -> Start, D;  B -> D;  D -> E
        wiki.page("Start", "", 1, 1, "A", "B", "Alt", "Fehlt");
        wiki.page("A", "", 2, 1, "Start", "D");
        wiki.page("B", "", 3, 1, "D");
        wiki.page("C", "", 4, 1);
        wiki.page("D", "", 5, 1, "E");
        wiki.page("E", "", 6, 1);
        wiki.redirects.put("Alt", "C");
    }

    @Test
    public void depthZeroReturnsOnlyStartPages() throws Exception {
        assertEquals(Collections.singletonList("Start"), titles(crawler.crawl(list("Start"), 0, 100)));
    }

    @Test
    public void breadthFirstWithRedirectsDeduplicationAndMissingPagesSkipped() throws Exception {
        List<WikiCrawler.CrawledPage> pages = crawler.crawl(list("start"), 2, 100);

        assertEquals(Arrays.asList("Start", "A", "B", "C", "D"), titles(pages));
        assertNull(pages.get(0).parentTitle());
        assertEquals("Start", pages.get(3).parentTitle());
        assertEquals(1, pages.get(3).depth());
        assertEquals("A", pages.get(4).parentTitle());
        assertEquals(2, pages.get(4).depth());
    }

    @Test
    public void maxPagesStopsTheCrawl() throws Exception {
        assertEquals(Arrays.asList("Start", "A", "B"), titles(crawler.crawl(list("Start"), 5, 3)));
    }

    @Test
    public void linkFailureOfOnePageDoesNotStopTheCrawl() throws Exception {
        wiki.failLinksFor.add("A");
        assertEquals(Arrays.asList("Start", "A", "B", "C", "D", "E"), titles(crawler.crawl(list("Start"), 3, 100)));
    }

    @Test
    public void unavailableWikiStopsTheCrawl() {
        wiki.unavailableLinksFor.add("A");
        try {
            crawler.crawl(list("Start"), 3, 100);
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNAVAILABLE, e.kind());
        }
    }

    @Test
    public void accessDeniedStopsTheCrawl() {
        wiki.requiredUser = "u";
        try {
            crawler.crawl(list("Start"), 1, 10);
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
        }
    }

    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }

    private static List<String> titles(List<WikiCrawler.CrawledPage> pages) {
        List<String> titles = new ArrayList<String>();
        for (WikiCrawler.CrawledPage page : pages) {
            titles.add(page.info().title());
        }
        return titles;
    }
}
