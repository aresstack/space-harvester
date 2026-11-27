package com.aresstack.askai.research.domain.scope;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NonSourceHostsTest {

    @Test
    public void theDefaultHostsAreHostEntriesOfTheTypedBlacklist() {
        TypedBlacklist list = TypedBlacklist.of(NonSourceHosts.DEFAULT);
        for (String host : NonSourceHosts.DEFAULT) {
            assertTrue(host, TypedBlacklist.isHostEntry(host) || TypedBlacklist.isExemptEntry(host));
        }
        // the three sellers of one handbook that covered the root node on 2026-11-09
        assertTrue(list.matches("", "", "https://www.amazon.de/Bautagebuch-Dokumentation/dp/B07QHHSSHZ"));
        assertTrue(list.matches("", "", "https://www.thalia.de/shop/home/artikeldetails/A1070494655"));
        assertTrue(list.matches("", "", "https://www.beck-shop.de/seibel-zoeller-handbuch/product/36958153"));
        assertTrue(list.matches("", "", "https://de.scribd.com/document/681792181/201-060-BG-BAU-web"));
        assertFalse(list.matches("", "", "https://www.baua.de/DE/Themen/Arbeitsgestaltung/x"));
        assertFalse("a publisher's own site is a source", list.matches("", "", "https://www.beck.de/x"));
        assertFalse("a mention is not a host", list.matches("Buy it on amazon.de", "", "https://blog.example/x"));
        // the technical sites of the same companies stay sources (Codex, PR #153)
        assertFalse(list.matches("", "", "https://docs.aws.amazon.com/iot/latest/developerguide/what-is-aws-iot.html"));
        assertFalse(list.matches("", "", "https://aws.amazon.com/blogs/iot/wearables/"));
        assertFalse(list.matches("", "", "https://developer.amazon.com/alexa"));
        assertFalse(list.matches("", "", "https://developer.ebay.com/api-docs"));
        assertFalse(list.matches("", "", "https://engineering.zalando.com/posts/2023/x.html"));
        assertTrue(list.matches("", "", "https://www.amazon.com/dp/B0ABC"));
        assertTrue(list.matches("", "", "https://smile.amazon.com/dp/B0ABC"));
        assertTrue("a term entry still applies on an exempt host",
                TypedBlacklist.of(java.util.Arrays.asList("amazon.com", "!aws.amazon.com", "Alexa"))
                        .matches("Alexa skills", "", "https://aws.amazon.com/x"));
    }

    @Test
    public void aShopCataloguePageIsRecognisedByItsUrlShape() {
        // live run 2026-11-09: shop.bueroheuser.de and a Shopify shop counted as sources of a concept node
        assertTrue(NonSourceHosts.isShopCataloguePage("https://shop.bueroheuser.de/page/arbeitsschutz-bereiche"));
        assertTrue(NonSourceHosts.isShopCataloguePage("https://store.example.com/x"));
        assertTrue(NonSourceHosts.isShopCataloguePage("https://www.stahlgear.de/products/sicherheitsschuh"));
        assertTrue(NonSourceHosts.isShopCataloguePage("https://www.stahlgear.de/collections/arbeitsschutz?page=2"));
        assertTrue(NonSourceHosts.isShopCataloguePage("https://example.de/produkte/helm-123"));
        assertFalse("a shop's editorial page can be a source",
                NonSourceHosts.isShopCataloguePage("https://www.stahlgear.de/pages/handwerk-aktuell-v1"));
        assertFalse(NonSourceHosts.isShopCataloguePage("https://www.stahlgear.de/blogs/news/wearables"));
        assertFalse("an editorial page on a shop subdomain is a source",
                NonSourceHosts.isShopCataloguePage("https://shop.example.com/blogs/news/wearables"));
        assertFalse(NonSourceHosts.isShopCataloguePage("https://store.example.com/pages/guide?ref=x"));
        assertTrue("…but its catalogue is not", NonSourceHosts.isShopCataloguePage("https://shop.example.com/products/x"));
        assertFalse("a bare two-label host has no shop label", NonSourceHosts.isShopCataloguePage("https://shop.de/x"));
        assertFalse(NonSourceHosts.isShopCataloguePage("https://www.haufe.de/id/beitrag/x.html"));
        assertFalse(NonSourceHosts.isShopCataloguePage("https://example.org/research/productsafety"));
        assertFalse(NonSourceHosts.isShopCataloguePage(""));
        assertFalse(NonSourceHosts.isShopCataloguePage(null));
        assertTrue(NonSourceHosts.isNonSourcePage("https://example.org/search?q=x"));
        assertTrue(NonSourceHosts.isNonSourcePage("https://shop.example.de/x"));
    }

    @Test
    public void aSearchResultPageOfAWebsiteIsRecognisedByItsUrlShape() {
        assertTrue(NonSourceHosts.isSearchResultPage(
                "https://www.dguv.de/de/suche/index.jsp?query=p306-001&filter=language%3Ade&page=118"));
        assertTrue(NonSourceHosts.isSearchResultPage("https://www.baua.de/SiteGlobals/Forms/Suche/x?q=wearables"));
        assertTrue(NonSourceHosts.isSearchResultPage("https://example.org/search?term=x&q=sensor"));
        assertTrue(NonSourceHosts.isSearchResultPage("https://example.org/en/search/results?page=2"));
        assertTrue(NonSourceHosts.isSearchResultPage("https://shop.example/?s=wearable"));
        assertFalse(NonSourceHosts.isSearchResultPage("https://www.baua.de/DE/Themen/Gefaehrdungsbeurteilung?pos=4"));
        assertFalse("a word inside a step is not a step", NonSourceHosts.isSearchResultPage(
                "https://example.org/research/searchlight-sensors"));
        // documentation about an API named find or search is not a hit list (Codex, PR #153)
        assertFalse(NonSourceHosts.isSearchResultPage(
                "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Array/find"));
        assertFalse(NonSourceHosts.isSearchResultPage("https://developer.mozilla.org/en-US/docs/Web/API/URL/search"));
        assertFalse("a search form without a query lists nothing", NonSourceHosts.isSearchResultPage(
                "https://example.org/en/search/results"));
        assertFalse("a search step deep in the path is a section name", NonSourceHosts.isSearchResultPage(
                "https://example.org/search/guides/2023/wearables?lang=de"));
        assertFalse("an empty parameter searches nothing", NonSourceHosts.isSearchResultPage(
                "https://example.org/page?q="));
        assertFalse(NonSourceHosts.isSearchResultPage(""));
        assertFalse(NonSourceHosts.isSearchResultPage(null));
    }
}
