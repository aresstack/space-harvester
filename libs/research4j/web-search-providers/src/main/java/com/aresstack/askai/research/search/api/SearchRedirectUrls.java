package com.aresstack.askai.research.search.api;

import java.net.URI;
import java.util.Locale;

/**
 * Recognises search-engine redirect URLs whose target cannot be read from the URL itself (issue #41).
 *
 * <p>Google {@code /goto?url=CAES...} carries an opaque token; it is never decoded or guessed here. Resolution
 * (GET without redirect following, {@code Location} header) is done by Serp4J and reported via the
 * {@code serp4j:url_resolution} extension.</p>
 */
public final class SearchRedirectUrls {

    private SearchRedirectUrls() {
    }

    /** True for {@code http(s)://(www.)google.<tld>/goto?...}. */
    public static boolean isOpaqueRedirect(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return isGoogleHost(host.toLowerCase(Locale.ROOT)) && (path.equals("/goto") || path.equals("/goto/"));
        } catch (RuntimeException notAUri) {
            return false;
        }
    }

    private static boolean isGoogleHost(String host) {
        String h = host.startsWith("www.") ? host.substring(4) : host;
        // google.com, google.de, google.co.uk, ...
        return h.equals("google.com") || h.matches("google\\.(co\\.)?[a-z]{2,3}")
                || h.matches("google\\.com\\.[a-z]{2}");
    }
}
