package com.aresstack.askai.research.runtime.search;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Conservative URL normalization: tracking params (incl. DataForSEO's {@code srsltid}) and fragments go,
 * host case/default ports/trailing slash are canonicalized, but functionally distinct URLs and meaningful
 * query parameters survive — final dedup stays a capture/source-acceptance job.
 */
public class SearchUrlNormalizerTest {

    @Test
    public void stripsSrsltidAndTrackingButKeepsMeaningfulParams() {
        assertEquals("https://www.mediamarkt.de/de/category/wearables.html?page=1",
                SearchUrlNormalizer.normalize(
                        "https://www.mediamarkt.de/de/category/wearables.html?srsltid=Afm&page=1"));
        assertEquals("https://example.org/a?q=shoes",
                SearchUrlNormalizer.normalize(
                        "https://example.org/a?utm_source=x&q=shoes&gclid=1&fbclid=2"));
    }

    @Test
    public void dropsFragmentLowercasesHostRemovesDefaultPortAndTrailingSlash() {
        assertEquals("https://www.example.com/Path",
                SearchUrlNormalizer.normalize("HTTPS://WWW.Example.COM/Path#section"));
        assertEquals("https://example.com/a",
                SearchUrlNormalizer.normalize("https://example.com:443/a/"));
        assertEquals("https://example.com",
                SearchUrlNormalizer.normalize("https://example.com/"));
    }

    @Test
    public void keepsNonDefaultPort() {
        assertEquals("http://example.com:8080/a",
                SearchUrlNormalizer.normalize("http://example.com:8080/a"));
    }

    @Test
    public void doesNotMergeFunctionallyDistinctPages() {
        String p1 = SearchUrlNormalizer.normalize("https://example.com/list?page=1");
        String p2 = SearchUrlNormalizer.normalize("https://example.com/list?page=2");
        assertTrue(!p1.equals(p2));
        assertTrue(p1.endsWith("page=1"));
        assertTrue(p2.endsWith("page=2"));
    }

    @Test
    public void leavesNonAbsoluteInputUntouchedButTrimmed() {
        assertEquals("mailto:a@b.com", SearchUrlNormalizer.normalize("  mailto:a@b.com  "));
    }
}
