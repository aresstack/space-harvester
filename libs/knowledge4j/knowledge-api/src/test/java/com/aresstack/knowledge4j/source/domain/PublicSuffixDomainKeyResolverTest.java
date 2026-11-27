package com.aresstack.knowledge4j.source.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registrable domains by the bundled public suffix list; addresses and internal names keep their own identity. */
class PublicSuffixDomainKeyResolverTest {

    private final DomainKeyResolver resolver = new PublicSuffixDomainKeyResolver();

    @Test
    void publicSuffixesAreRespected() {
        assertEquals("bbc.co.uk", resolver.resolve("https://news.bbc.co.uk/article").getRegistrableDomain());
        assertEquals("example.com.au", resolver.resolve("shop.example.com.au").getRegistrableDomain());
        assertTrue(resolver.resolve("www.example.org").sameFamily(resolver.resolve("docs.example.org")));
        assertEquals(HostKind.REGISTERED_NAME, resolver.resolve("example.org").getHostKind());
    }

    @Test
    void addressesAndInternalNamesAreTheirOwnFamily() {
        assertEquals(HostKind.IP_LITERAL, resolver.resolve("http://127.0.0.1:8080/x").getHostKind());
        assertEquals(HostKind.LOCAL_HOST, resolver.resolve("localhost").getHostKind());
        assertEquals(HostKind.SINGLE_LABEL_HOST, resolver.resolve("intranet").getHostKind());
        assertFalse(resolver.resolve("").sameFamily(resolver.resolve("")));
    }
}
