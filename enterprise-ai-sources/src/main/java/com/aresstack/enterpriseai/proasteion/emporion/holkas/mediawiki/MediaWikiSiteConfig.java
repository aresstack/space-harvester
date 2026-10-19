package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Unveränderliche Konfiguration einer MediaWiki-Site. Wird in der Composition Root aus den Einstellungen
 * gebaut und per Konstruktor an den Adapter gegeben; der Adapter liest keine globalen Settings.
 *
 * <p>Enthält bewusst keine Zugangsdaten: Diese liefert ein {@link MediaWikiCredentialsProvider} erst beim
 * Login.
 */
public final class MediaWikiSiteConfig {

    private static final Pattern SITE_KEY = Pattern.compile("[a-z0-9][A-Za-z0-9._:/~%-]*");

    private final String siteKey;
    private final String displayName;
    private final URI apiUrl;
    private final boolean requiresLogin;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final String userAgent;
    private final String linkNamespaces;

    private MediaWikiSiteConfig(Builder b) {
        this.siteKey = b.siteKey;
        this.displayName = b.displayName != null ? b.displayName : b.siteKey;
        this.apiUrl = b.apiUrl;
        this.requiresLogin = b.requiresLogin;
        this.connectTimeoutMillis = b.connectTimeoutMillis;
        this.readTimeoutMillis = b.readTimeoutMillis;
        this.userAgent = b.userAgent;
        this.linkNamespaces = b.linkNamespaces;
    }

    /**
     * @param siteKey kanonischer Endpoint der Site ohne Schema (Provider: {@code wiki.intern/w}); Teil jeder
     *                {@code wiki:}-Resource-ID
     *                und darf sich nach dem ersten Indexieren nicht mehr ändern
     * @param apiUrl  Basis-URL des Wikis oder direkt die {@code api.php}
     */
    public static Builder builder(String siteKey, String apiUrl) {
        return new Builder(siteKey, apiUrl);
    }

    public String siteKey() {
        return siteKey;
    }

    public String displayName() {
        return displayName;
    }

    /** Basis- oder {@code api.php}-URL wie konfiguriert. */
    public URI apiUrl() {
        return apiUrl;
    }

    /** Die URL von {@code api.php}, abgeleitet wie in MainframeMate {@code JwbfWikiContentService.buildApiUrl}. */
    public String apiEndpoint() {
        String base = apiUrl.toString();
        if (base.endsWith("api.php")) {
            return base;
        }
        return base.endsWith("/") ? base + "api.php" : base + "/api.php";
    }

    public boolean requiresLogin() {
        return requiresLogin;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int readTimeoutMillis() {
        return readTimeoutMillis;
    }

    public String userAgent() {
        return userAgent;
    }

    /** Namensräume, deren Links beim Crawl verfolgt werden, im API-Format ({@code "0"} oder {@code "0|4"}). */
    public String linkNamespaces() {
        return linkNamespaces;
    }

    @Override
    public String toString() {
        return "MediaWikiSiteConfig{siteKey=" + siteKey + ", apiUrl=" + apiUrl + ", requiresLogin="
                + requiresLogin + "}";
    }

    public static final class Builder {

        private final String siteKey;
        private final URI apiUrl;
        private String displayName;
        private boolean requiresLogin;
        private int connectTimeoutMillis = 15000;
        private int readTimeoutMillis = 30000;
        private String userAgent = "EnterpriseAiClient/0.1 (MediaWiki knowledge source)";
        private String linkNamespaces = "0";

        private Builder(String siteKey, String apiUrl) {
            if (siteKey == null || !SITE_KEY.matcher(siteKey).matches()) {
                throw new IllegalArgumentException(
                        "siteKey must match [a-z0-9][A-Za-z0-9._:/~%-]*: " + siteKey);
            }
            if (apiUrl == null || apiUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("apiUrl must not be blank");
            }
            URI uri = URI.create(apiUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("apiUrl must be an http(s) URL: " + apiUrl);
            }
            if (uri.isOpaque() || uri.getHost() == null) {
                throw new IllegalArgumentException("apiUrl must be a hierarchical URL with host: " + apiUrl);
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("apiUrl must not contain query or fragment");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("apiUrl must not contain user info");
            }
            this.siteKey = siteKey;
            this.apiUrl = uri;
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder requiresLogin(boolean value) {
            this.requiresLogin = value;
            return this;
        }

        public Builder connectTimeoutMillis(int value) {
            this.connectTimeoutMillis = positive(value, "connectTimeoutMillis");
            return this;
        }

        public Builder readTimeoutMillis(int value) {
            this.readTimeoutMillis = positive(value, "readTimeoutMillis");
            return this;
        }

        public Builder userAgent(String value) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("userAgent must not be blank");
            }
            this.userAgent = value;
            return this;
        }

        public Builder linkNamespaces(int... namespaces) {
            if (namespaces.length == 0) {
                throw new IllegalArgumentException("at least one namespace is required");
            }
            StringBuilder sb = new StringBuilder();
            for (int ns : namespaces) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(ns);
            }
            this.linkNamespaces = sb.toString();
            return this;
        }

        public MediaWikiSiteConfig build() {
            return new MediaWikiSiteConfig(this);
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be > 0");
            }
            return value;
        }
    }
}
