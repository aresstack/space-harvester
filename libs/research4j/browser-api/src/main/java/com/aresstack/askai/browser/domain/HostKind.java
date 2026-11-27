package com.aresstack.askai.browser.domain;

/** The structural kind of a host — drives how its domain family (registrable domain) is formed. */
public enum HostKind {
    /** A registered DNS name ("www.bing.com", "news.bbc.co.uk"). */
    REGISTERED_NAME,
    /** A literal IPv4/IPv6 address ("127.0.0.1", "[::1]"). */
    IP_LITERAL,
    /** The local loopback name ("localhost"). */
    LOCAL_HOST,
    /** A dot-less single-label host ("intranet") — typically an internal network name. */
    SINGLE_LABEL_HOST
}
