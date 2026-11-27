package com.aresstack.askai.browser.playwright;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Static resolution of known search-engine redirect wrappers — applied BEFORE any domain judgement.
 * An unresolvable wrapper is UNRESOLVED (controlled discard), never misclassified by the wrapper host.
 */
public class SearchRedirectResolverTest {

    private static String bingWrapped(String target) {
        return "https://www.bing.com/ck/a?!&&p=abc&u=a1"
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(target.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void bingCkWrappersDecodeTheirBase64Target() {
        SearchRedirectResolver.Resolution resolution =
                SearchRedirectResolver.resolve(bingWrapped("https://pf4j.org/doc/getting-started.html"));
        assertEquals(SearchRedirectResolver.Status.RESOLVED, resolution.getStatus());
        assertEquals("https://pf4j.org/doc/getting-started.html", resolution.getTargetUrl());
    }

    @Test
    public void bingTabWrappersWithARelativeTargetResolveToBingItself() {
        SearchRedirectResolver.Resolution images =
                SearchRedirectResolver.resolve(bingWrapped("/images/search?q=kaninchen&FORM=HDRSC2"));
        assertEquals(SearchRedirectResolver.Status.RESOLVED, images.getStatus());
        assertEquals("https://www.bing.com/images/search?q=kaninchen&FORM=HDRSC2", images.getTargetUrl());
        assertEquals("a protocol-relative target is never guessed", SearchRedirectResolver.Status.UNRESOLVED,
                SearchRedirectResolver.resolve(bingWrapped("//evil.example/x")).getStatus());
    }

    @Test
    public void googleUrlAndDuckDuckGoWrappersDecodeTheirParameter() {
        SearchRedirectResolver.Resolution google = SearchRedirectResolver.resolve(
                "https://www.google.com/url?q=https%3A%2F%2Fexample.org%2Fa&sa=U");
        assertEquals(SearchRedirectResolver.Status.RESOLVED, google.getStatus());
        assertEquals("https://example.org/a", google.getTargetUrl());

        SearchRedirectResolver.Resolution ddg = SearchRedirectResolver.resolve(
                "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.net%2Fb&rut=x");
        assertEquals(SearchRedirectResolver.Status.RESOLVED, ddg.getStatus());
        assertEquals("https://example.net/b", ddg.getTargetUrl());
    }

    @Test
    public void unresolvableWrappersAreDiscardedNotMisclassified() {
        assertEquals("bing wrapper without a decodable target",
                SearchRedirectResolver.Status.UNRESOLVED,
                SearchRedirectResolver.resolve("https://www.bing.com/ck/a?!&&p=abc").getStatus());
        assertEquals("google /url without target",
                SearchRedirectResolver.Status.UNRESOLVED,
                SearchRedirectResolver.resolve("https://www.google.com/url?sa=U").getStatus());
    }

    @Test
    public void ordinaryUrlsPassThroughUntouched() {
        SearchRedirectResolver.Resolution plain =
                SearchRedirectResolver.resolve("https://github.com/pf4j/pf4j");
        assertEquals(SearchRedirectResolver.Status.NOT_A_REDIRECT, plain.getStatus());
        assertEquals("https://github.com/pf4j/pf4j", plain.getTargetUrl());
        assertEquals("a bing page OUTSIDE /ck/ is no wrapper",
                SearchRedirectResolver.Status.NOT_A_REDIRECT,
                SearchRedirectResolver.resolve("https://www.bing.com/videos/search?q=x").getStatus());
    }

    @Test
    public void googlesOpaqueGotoWrapperIsUnresolvedAndNeverJudgedByGooglesHost() {
        String wrapper = "https://www.google.com/goto?url=CAESABC123&ved=x";
        assertEquals(SearchRedirectResolver.Status.UNRESOLVED, SearchRedirectResolver.resolve(wrapper).getStatus());
        assertEquals("", SearchRedirectResolver.resolve(wrapper).getTargetUrl());
        org.junit.Assert.assertTrue(SearchRedirectResolver.isOpaqueWrapper(wrapper));
        org.junit.Assert.assertFalse(SearchRedirectResolver.isOpaqueWrapper("https://www.google.com/goto"));
        org.junit.Assert.assertFalse(SearchRedirectResolver.isOpaqueWrapper("https://example.com/goto?url=x"));
        org.junit.Assert.assertFalse(SearchRedirectResolver.isOpaqueWrapper("https://www.google.com/url?q=https://a.b/"));
    }

    @Test
    public void aLaterStrategyMayResolveAWrapperButNeverIntoAnotherWrapperOrANonHttpTarget() {
        String wrapper = "https://www.google.com/goto?url=CAESABC123";
        SearchRedirectResolver.Resolution known = SearchRedirectResolver.resolve(wrapper,
                url -> "https://openjdk.org/projects/jdk/21/");
        assertEquals(SearchRedirectResolver.Status.RESOLVED, known.getStatus());
        assertEquals("https://openjdk.org/projects/jdk/21/", known.getTargetUrl());
        assertEquals(SearchRedirectResolver.Status.UNRESOLVED, SearchRedirectResolver.resolve(wrapper,
                url -> "https://www.google.com/goto?url=again").getStatus());
        assertEquals(SearchRedirectResolver.Status.UNRESOLVED, SearchRedirectResolver.resolve(wrapper,
                url -> "javascript:alert(1)").getStatus());
        assertEquals(SearchRedirectResolver.Status.UNRESOLVED,
                SearchRedirectResolver.resolve(wrapper, OpaqueRedirectStrategy.DISCARD).getStatus());
    }

    @Test
    public void yahooWrappersDecodeTheirRuSegmentWithoutARequest() {
        SearchRedirectResolver.Resolution resolution = SearchRedirectResolver.resolve(
                "https://r.search.yahoo.com/_ylt=AwrE.abc;_ylu=Y29sbwNiZjEEcG9z/RV=2/RE=1762732800/RO=10/"
                        + "RU=https%3a%2f%2fwww.baua.de%2fDE%2fThemen%2fArbeitsgestaltung%3fx%3d1/RK=2/RS=xyz-");
        assertEquals(SearchRedirectResolver.Status.RESOLVED, resolution.getStatus());
        assertEquals("https://www.baua.de/DE/Themen/Arbeitsgestaltung?x=1", resolution.getTargetUrl());
        assertEquals("a wrapper without a target is discarded, never classified by Yahoo's host",
                SearchRedirectResolver.Status.UNRESOLVED,
                SearchRedirectResolver.resolve("https://r.search.yahoo.com/_ylt=abc/RV=2/RK=2/RS=x").getStatus());
        assertEquals(SearchRedirectResolver.Status.UNRESOLVED,
                SearchRedirectResolver.resolve("https://r.search.yahoo.com/RU=javascript%3aalert(1)/RK=2")
                        .getStatus());
        assertEquals("ordinary Yahoo pages are no wrappers", SearchRedirectResolver.Status.NOT_A_REDIRECT,
                SearchRedirectResolver.resolve("https://finance.yahoo.com/quote/X").getStatus());
    }

    @Test
    public void baiduWrappersResolveOnlyFromTheTargetThePagePrints() {
        String wrapper = "https://www.baidu.com/link?url=Xq7aB3opaqueToken&wd=&eqid=9f1";
        assertTrue("Baidu's wrapper is opaque", SearchRedirectResolver.isOpaqueWrapper(wrapper));
        SearchRedirectResolver.Resolution resolved =
                SearchRedirectResolver.resolve(wrapper, "https://www.baua.de/DE/Themen/Arbeitsgestaltung");
        assertEquals(SearchRedirectResolver.Status.RESOLVED, resolved.getStatus());
        assertEquals("https://www.baua.de/DE/Themen/Arbeitsgestaltung", resolved.getTargetUrl());
        assertEquals("no hint: never decoded, never fetched, never a result", SearchRedirectResolver.Status.DISCARDED,
                SearchRedirectResolver.resolve(wrapper).getStatus());
        assertEquals(SearchRedirectResolver.Status.DISCARDED,
                SearchRedirectResolver.resolve(wrapper, "javascript:alert(1)").getStatus());
        assertEquals("a hint pointing back at Baidu is no target", SearchRedirectResolver.Status.DISCARDED,
                SearchRedirectResolver.resolve(wrapper, "https://www.baidu.com/s?wd=x").getStatus());
        assertEquals("a hint on an ordinary link changes nothing", SearchRedirectResolver.Status.NOT_A_REDIRECT,
                SearchRedirectResolver.resolve("https://baike.baidu.com/item/x", "https://other.example/")
                        .getStatus());
        assertEquals("https://baike.baidu.com/item/x",
                SearchRedirectResolver.resolve("https://baike.baidu.com/item/x", "https://other.example/")
                        .getTargetUrl());
    }
}
