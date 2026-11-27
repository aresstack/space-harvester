package com.aresstack.askai.browser.domain;

/**
 * Resolves a URL or bare host to its {@link DomainIdentity}. The productive implementation is
 * public-suffix aware ({@link PublicSuffixDomainKeyResolver}); tests may inject a fake (e.g. one that
 * keys local test servers by host:port) instead of bending the production semantics to localhost worlds.
 */
public interface DomainKeyResolver {

    /** @param urlOrHost an absolute URL or a bare host; never throws — empty input yields an empty identity. */
    DomainIdentity resolve(String urlOrHost);
}
