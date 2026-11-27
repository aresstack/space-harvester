package com.aresstack.askai.browser.domain;

import java.util.Locale;

/**
 * DEV/TEST {@link DomainKeyResolver}: the domain family is the authority INCLUDING the port, so several
 * local test servers on 127.0.0.1 act as distinct "domains". This keeps production semantics (public
 * suffixes) untouched instead of bending them to localhost worlds. Selected via the sidecar's
 * {@code --domain-key-mode=host-port} argument or injected directly in tests — never the default.
 */
public final class HostPortDomainKeyResolver implements DomainKeyResolver {

    private final PublicSuffixDomainKeyResolver kinds = new PublicSuffixDomainKeyResolver();

    @Override
    public DomainIdentity resolve(String urlOrHost) {
        String authority = authorityOf(urlOrHost);
        if (authority.isEmpty()) {
            return new DomainIdentity("", "", HostKind.SINGLE_LABEL_HOST);
        }
        HostKind kind = kinds.resolve(authority).getHostKind();
        return new DomainIdentity(authority, authority, kind);
    }

    /** host[:port] of a URL or bare input (lower-cased, credentials stripped). */
    static String authorityOf(String urlOrHost) {
        String value = urlOrHost == null ? "" : urlOrHost.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
        }
        int at = value.indexOf('@');
        return at >= 0 ? value.substring(at + 1) : value;
    }
}
