package com.aresstack.askai.browser.domain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The public-suffix aware domain foundation: registrable domains respect public suffixes (bbc.co.uk),
 * IP literals / localhost / single-label hosts keep their own identity, and the classification
 * distinguishes SAME_HOST / SUBDOMAIN / SAME_REGISTRABLE_DOMAIN / EXTERNAL_DOMAIN.
 */
public class DomainKeyResolverTest {

    private final DomainKeyResolver resolver = new PublicSuffixDomainKeyResolver();

    @Test
    public void providerSubdomainsShareTheRegistrableDomain() {
        assertEquals("bing.com", resolver.resolve("https://www.bing.com/search?q=x").getRegistrableDomain());
        assertEquals("bing.com", resolver.resolve("cn.bing.com").getRegistrableDomain());
        assertTrue(resolver.resolve("www.bing.com").sameFamily(resolver.resolve("cn.bing.com")));
    }

    @Test
    public void publicSuffixesAreRespected() {
        assertEquals("the naive last-two-labels rule would yield co.uk",
                "bbc.co.uk", resolver.resolve("https://news.bbc.co.uk/article").getRegistrableDomain());
        assertEquals("bbc.co.uk", resolver.resolve("sport.bbc.co.uk").getRegistrableDomain());
        assertTrue(resolver.resolve("news.bbc.co.uk").sameFamily(resolver.resolve("sport.bbc.co.uk")));
        assertEquals("example.com.au", resolver.resolve("shop.example.com.au").getRegistrableDomain());
    }

    @Test
    public void privateSectionSeparatesHostedTenants() {
        assertEquals("a challenge on one tenant must never block unrelated tenants of the platform",
                "foo.github.io", resolver.resolve("https://foo.github.io/repo").getRegistrableDomain());
        assertEquals("bar.github.io", resolver.resolve("bar.github.io").getRegistrableDomain());
        assertEquals(DomainClassification.EXTERNAL_DOMAIN, DomainClassification.classify(
                resolver.resolve("foo.github.io"), resolver.resolve("bar.github.io")));
        assertEquals("foo.blogspot.com", resolver.resolve("foo.blogspot.com").getRegistrableDomain());
        assertEquals("bar.blogspot.com", resolver.resolve("bar.blogspot.com").getRegistrableDomain());
    }

    @Test
    public void wildcardAndExceptionRulesAreHonoured() {
        // *.kawasaki.jp: every direct label is itself a public suffix …
        assertEquals("web.city2.kawasaki.jp",
                resolver.resolve("https://web.city2.kawasaki.jp/").getRegistrableDomain());
        // … EXCEPT the !city.kawasaki.jp exception, which is registrable itself.
        assertEquals("city.kawasaki.jp", resolver.resolve("city.kawasaki.jp").getRegistrableDomain());
        assertEquals("city.kawasaki.jp", resolver.resolve("sub.city.kawasaki.jp").getRegistrableDomain());
        // *.ck with !www.ck
        assertEquals("www.ck", resolver.resolve("foo.www.ck").getRegistrableDomain());
        assertEquals("shop.other.ck", resolver.resolve("shop.other.ck").getRegistrableDomain());
    }

    @Test
    public void snapshotIsTheFullVersionedPublicSuffixList() {
        PublicSuffixDomainKeyResolver psl = new PublicSuffixDomainKeyResolver();
        assertTrue("bundled snapshot must carry its upstream VERSION stamp",
                !psl.getSnapshotVersion().isEmpty());
    }

    @Test
    public void yahooSubdomainsAreSameFamilyButDifferentHosts() {
        DomainIdentity search = resolver.resolve("https://search.yahoo.com/search?p=x");
        DomainIdentity finance = resolver.resolve("https://finance.yahoo.com/quote/x");
        assertEquals("yahoo.com", search.getRegistrableDomain());
        assertEquals("yahoo.com", finance.getRegistrableDomain());
        assertEquals(DomainClassification.SAME_REGISTRABLE_DOMAIN,
                DomainClassification.classify(search, finance));
    }

    @Test
    public void specialHostKindsKeepTheirOwnIdentity() {
        DomainIdentity ip = resolver.resolve("http://127.0.0.1:8080/find?q=x");
        assertEquals(HostKind.IP_LITERAL, ip.getHostKind());
        assertEquals("127.0.0.1", ip.getRegistrableDomain());

        DomainIdentity local = resolver.resolve("http://localhost:3000/");
        assertEquals(HostKind.LOCAL_HOST, local.getHostKind());
        assertEquals("localhost", local.getRegistrableDomain());

        DomainIdentity intranet = resolver.resolve("http://intranet/wiki");
        assertEquals(HostKind.SINGLE_LABEL_HOST, intranet.getHostKind());
        assertEquals("intranet", intranet.getRegistrableDomain());

        assertEquals(HostKind.REGISTERED_NAME, resolver.resolve("www.bing.com").getHostKind());
    }

    @Test
    public void classificationCoversAllRelations() {
        DomainIdentity a = resolver.resolve("https://www.example.org/a");
        assertEquals(DomainClassification.SAME_HOST,
                DomainClassification.classify(a, resolver.resolve("https://www.example.org/b")));
        assertEquals(DomainClassification.SUBDOMAIN,
                DomainClassification.classify(resolver.resolve("docs.example.org"),
                        resolver.resolve("example.org")));
        assertEquals(DomainClassification.SAME_REGISTRABLE_DOMAIN,
                DomainClassification.classify(resolver.resolve("docs.example.org"),
                        resolver.resolve("blog.example.org")));
        assertEquals(DomainClassification.EXTERNAL_DOMAIN,
                DomainClassification.classify(a, resolver.resolve("https://other.net/")));
    }

    @Test
    public void hostExtractionHandlesPortsCredentialsAndIpv6() {
        assertEquals("example.org", PublicSuffixDomainKeyResolver.hostOf("https://user@example.org:8443/x"));
        assertEquals("[::1]", PublicSuffixDomainKeyResolver.hostOf("http://[::1]:8080/x"));
        assertEquals("", resolver.resolve("").getHost());
    }
}
