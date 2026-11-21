package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.astu.security.SecretRef;

import java.net.URI;
import java.util.Locale;

/**
 * Fachliche Konfiguration einer Jira-Data-Center-Instanz (unveränderlich).
 *
 * <p>Enthält bewusst keine Zugangsdaten, nur den {@link SecretRef}, über den der Adapter die Anmeldedaten pro
 * Vorgang beim {@code SecretProvider} anfordert. Proxy, Timeouts und Client-Zertifikat gehören zum
 * {@link JiraHttpTransport}.
 */
public final class JiraConfig {

    private final URI baseUrl;
    private final SecretRef credentialRef;
    private final int pageSize;
    private final int maxResponseBytes;
    private final int maxComments;
    private final String jqlFilter;
    private final boolean allowInsecureHttp;
    private final int maxRetries;
    private final int retryBackoffMillis;
    private final int maxRetryBackoffMillis;

    private JiraConfig(Builder builder) {
        if (builder.credentialRef != null && !builder.allowInsecureHttp
                && !"https".equals(builder.baseUrl.getScheme())) {
            throw new IllegalArgumentException(
                    "Zugangsdaten werden nur über https gesendet; für Entwicklung allowInsecureHttp(true) setzen");
        }
        this.allowInsecureHttp = builder.allowInsecureHttp;
        this.baseUrl = builder.baseUrl;
        this.credentialRef = builder.credentialRef;
        this.pageSize = builder.pageSize;
        this.maxResponseBytes = builder.maxResponseBytes;
        this.maxComments = builder.maxComments;
        this.jqlFilter = builder.jqlFilter;
        this.maxRetries = builder.maxRetries;
        this.retryBackoffMillis = builder.retryBackoffMillis;
        this.maxRetryBackoffMillis = Math.max(builder.maxRetryBackoffMillis, builder.retryBackoffMillis);
    }

    /**
     * @param baseUrl Basis-URL inklusive Kontextpfad, z. B. {@code https://jira.example.org/jira};
     *                nur http/https, ohne Zugangsdaten, Query oder Fragment
     */
    public static Builder builder(URI baseUrl) {
        return new Builder(baseUrl);
    }

    /** Basis-URL ohne abschließenden Schrägstrich. */
    public URI baseUrl() {
        return baseUrl;
    }

    /** Verweis auf die Anmeldedaten oder {@code null}: dann ohne {@code Authorization}-Header (z. B. nur mTLS). */
    public SecretRef credentialRef() {
        return credentialRef;
    }

    /** Vorgänge je Suchaufruf ({@code maxResults}); Jira kappt serverseitig, meist bei 1000. */
    public int pageSize() {
        return pageSize;
    }

    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Höchstzahl Kommentare, die ein Vorgang in Text und Lese-Ansicht mitbringt. */
    public int maxComments() {
        return maxComments;
    }

    /** Zusätzliche JQL-Bedingung, die jede Suche und Auflistung einschränkt (z. B. {@code resolution = Unresolved}); leer = keine. */
    public String jqlFilter() {
        return jqlFilter;
    }

    /** {@code true}, wenn Zugangsdaten ausdrücklich auch über unverschlüsseltes http gesendet werden dürfen. */
    public boolean allowInsecureHttp() {
        return allowInsecureHttp;
    }

    /** Wiederholungen eines REST-Aufrufs nach Netzfehler, HTTP 429 oder 5xx; {@code 0} = keine. */
    public int maxRetries() {
        return maxRetries;
    }

    public int retryBackoffMillis() {
        return retryBackoffMillis;
    }

    public int maxRetryBackoffMillis() {
        return maxRetryBackoffMillis;
    }

    @Override
    public String toString() {
        return "JiraConfig[" + baseUrl + ", credentials=" + (credentialRef == null ? "keine" : credentialRef)
                + (jqlFilter.isEmpty() ? "" : ", jql=" + jqlFilter) + "]";
    }

    /** Builder; Defaults: 50 Vorgänge je Seite, Antworten bis 20 MB, 200 Kommentare, keine Wiederholungen. */
    public static final class Builder {

        private final URI baseUrl;
        private SecretRef credentialRef;
        private int pageSize = 50;
        private int maxResponseBytes = 20 * 1024 * 1024;
        private int maxComments = 200;
        private String jqlFilter = "";
        private boolean allowInsecureHttp;
        private int maxRetries;
        private int retryBackoffMillis = 1000;
        private int maxRetryBackoffMillis = 30000;

        private Builder(URI baseUrl) {
            this.baseUrl = normalize(baseUrl);
        }

        public Builder credentialRef(SecretRef value) {
            this.credentialRef = value;
            return this;
        }

        /** Nur für Entwicklung: erlaubt {@link #credentialRef(SecretRef)} zusammen mit einer http-Basis-URL. */
        public Builder allowInsecureHttp(boolean value) {
            this.allowInsecureHttp = value;
            return this;
        }

        /** Vorgänge je REST-Aufruf ({@code maxResults}); 1–1000. */
        public Builder pageSize(int value) {
            if (value < 1 || value > 1000) {
                throw new IllegalArgumentException("pageSize muss zwischen 1 und 1000 liegen");
            }
            this.pageSize = value;
            return this;
        }

        public Builder maxResponseBytes(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("maxResponseBytes muss positiv sein");
            }
            this.maxResponseBytes = value;
            return this;
        }

        /** Siehe {@link JiraConfig#maxComments()}; 0–10000. */
        public Builder maxComments(int value) {
            if (value < 0 || value > 10000) {
                throw new IllegalArgumentException("maxComments muss zwischen 0 und 10000 liegen");
            }
            this.maxComments = value;
            return this;
        }

        /** Siehe {@link JiraConfig#jqlFilter()}; {@code null} oder leer = keine Einschränkung. */
        public Builder jqlFilter(String value) {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.toLowerCase(Locale.ROOT).contains("order by")) {
                throw new IllegalArgumentException("jqlFilter darf keine Sortierung (ORDER BY) enthalten");
            }
            this.jqlFilter = trimmed;
            return this;
        }

        /** Siehe {@link JiraConfig#maxRetries()}; 0–10. */
        public Builder maxRetries(int value) {
            if (value < 0 || value > 10) {
                throw new IllegalArgumentException("maxRetries muss zwischen 0 und 10 liegen");
            }
            this.maxRetries = value;
            return this;
        }

        public Builder retryBackoffMillis(int value) {
            if (value < 0 || value > 60000) {
                throw new IllegalArgumentException("retryBackoffMillis muss zwischen 0 und 60000 liegen");
            }
            this.retryBackoffMillis = value;
            return this;
        }

        public Builder maxRetryBackoffMillis(int value) {
            if (value < 0 || value > 300000) {
                throw new IllegalArgumentException("maxRetryBackoffMillis muss zwischen 0 und 300000 liegen");
            }
            this.maxRetryBackoffMillis = value;
            return this;
        }

        public JiraConfig build() {
            return new JiraConfig(this);
        }

        private static URI normalize(URI uri) {
            if (uri == null) {
                throw new IllegalArgumentException("baseUrl fehlt");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new IllegalArgumentException("baseUrl muss http oder https sein");
            }
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("baseUrl ohne Host");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("baseUrl darf keine Zugangsdaten enthalten");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("baseUrl darf weder Query noch Fragment enthalten");
            }
            String text = uri.toString();
            while (text.endsWith("/")) {
                text = text.substring(0, text.length() - 1);
            }
            return URI.create(text);
        }
    }
}
