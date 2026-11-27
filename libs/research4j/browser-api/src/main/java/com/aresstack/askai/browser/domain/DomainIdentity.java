package com.aresstack.askai.browser.domain;

import java.util.Locale;

/**
 * The resolved identity of one host: the normalized host itself, its registrable domain (public-suffix
 * aware — {@code news.bbc.co.uk} → {@code bbc.co.uk}) and the structural {@link HostKind}. For IP
 * literals, localhost and single-label hosts the registrable domain is the host itself. The registrable
 * domain is THE domain-family key used for challenge locks, transit rules and link classification.
 */
public final class DomainIdentity {

    private final String host;
    private final String registrableDomain;
    private final HostKind hostKind;

    public DomainIdentity(String host, String registrableDomain, HostKind hostKind) {
        this.host = host == null ? "" : host.toLowerCase(Locale.ROOT);
        this.registrableDomain = registrableDomain == null ? "" : registrableDomain.toLowerCase(Locale.ROOT);
        this.hostKind = hostKind == null ? HostKind.SINGLE_LABEL_HOST : hostKind;
    }

    public String getHost() {
        return host;
    }

    /** The domain-family key ("" only for empty hosts). */
    public String getRegistrableDomain() {
        return registrableDomain;
    }

    public HostKind getHostKind() {
        return hostKind;
    }

    public boolean isEmpty() {
        return host.isEmpty();
    }

    /** True when both hosts belong to the same domain family (same registrable domain). */
    public boolean sameFamily(DomainIdentity other) {
        return other != null && !registrableDomain.isEmpty()
                && registrableDomain.equals(other.registrableDomain);
    }

    @Override
    public String toString() {
        return host + " (" + registrableDomain + ", " + hostKind + ")";
    }
}
