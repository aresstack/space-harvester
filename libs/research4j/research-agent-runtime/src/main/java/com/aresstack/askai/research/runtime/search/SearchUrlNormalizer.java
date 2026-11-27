package com.aresstack.askai.research.runtime.search;

import java.util.Locale;

/**
 * Conservative normalization of an initial provider URL BEFORE it enters the frontier: drop the fragment,
 * lowercase the host, remove default ports, normalize a trailing slash and strip well-known tracking
 * parameters (utm_*, gclid, fbclid, srsltid, …). It deliberately does NOT merge functionally distinct URLs
 * (e.g. {@code ?page=1} vs {@code ?page=2}) and never removes meaningful query parameters — final duplicate
 * detection remains the capture/source-acceptance job (final redirect URL, canonical, content hash). If the
 * input cannot be parsed it is returned trimmed but otherwise unchanged.
 */
public final class SearchUrlNormalizer {

    private SearchUrlNormalizer() {
    }

    public static String normalize(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String url = rawUrl.trim();
        if (url.isEmpty()) {
            return "";
        }
        int hash = url.indexOf('#');
        if (hash >= 0) {
            url = url.substring(0, hash);
        }
        int schemeSep = url.indexOf("://");
        if (schemeSep < 0) {
            return url; // not an absolute http(s) URL — leave it to downstream handling
        }
        String scheme = url.substring(0, schemeSep).toLowerCase(Locale.ROOT);
        String rest = url.substring(schemeSep + 3);

        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        String authority;
        String path;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            authority = rest;
            path = "";
        } else {
            authority = rest.substring(0, slash);
            path = rest.substring(slash);
        }

        // userinfo@host:port — lowercase host, drop default ports.
        String userInfo = "";
        int at = authority.indexOf('@');
        if (at >= 0) {
            userInfo = authority.substring(0, at + 1);
            authority = authority.substring(at + 1);
        }
        String host = authority;
        String port = "";
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        boolean defaultPort = ("http".equals(scheme) && "80".equals(port))
                || ("https".equals(scheme) && "443".equals(port));
        String normalizedAuthority = userInfo + host + (port.isEmpty() || defaultPort ? "" : ":" + port);

        // Trailing slash: a bare "/" path is dropped; "/a/" becomes "/a".
        if (path.equals("/")) {
            path = "";
        } else if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String cleanedQuery = stripTracking(query);

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(normalizedAuthority).append(path);
        if (!cleanedQuery.isEmpty()) {
            sb.append('?').append(cleanedQuery);
        }
        return sb.toString();
    }

    private static String stripTracking(String query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (isTracking(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(pair);
        }
        return kept.toString();
    }

    private static boolean isTracking(String name) {
        if (name.startsWith("utm_")) {
            return true;
        }
        switch (name) {
            case "gclid":
            case "gclsrc":
            case "dclid":
            case "fbclid":
            case "msclkid":
            case "yclid":
            case "srsltid":
            case "mc_eid":
            case "mc_cid":
            case "igshid":
            case "_hsenc":
            case "_hsmi":
                return true;
            default:
                return false;
        }
    }
}
