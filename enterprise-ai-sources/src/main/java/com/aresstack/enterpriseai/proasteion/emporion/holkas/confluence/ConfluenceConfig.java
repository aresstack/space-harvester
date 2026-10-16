package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.security.SecretRef;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Fachliche Konfiguration einer Confluence-Data-Center-Instanz (unveränderlich).
 *
 * <p>Enthält bewusst keine Zugangsdaten, nur den {@link SecretRef}, über den der Adapter die Anmeldedaten pro
 * Vorgang beim {@code SecretProvider} anfordert. Proxy, Timeouts und Client-Zertifikat gehören zum
 * {@link ConfluenceHttpTransport}. Felder wie in MainframeMate {@code ConfluenceConnectionConfig}, ohne
 * Passwort- und Proxy-Felder.
 */
public final class ConfluenceConfig {

    private final URI baseUrl;
    private final SecretRef credentialRef;
    private final int pageSize;
    private final boolean includeAttachments;
    private final int maxAttachmentBytes;
    private final int maxResponseBytes;
    private final List<String> searchSpaceKeys;
    private final boolean allowInsecureHttp;
    private final java.nio.file.Path diagnosticsDirectory;

    private ConfluenceConfig(Builder builder) {
        if (builder.credentialRef != null && !builder.allowInsecureHttp
                && !"https".equals(builder.baseUrl.getScheme())) {
            throw new IllegalArgumentException(
                    "Zugangsdaten werden nur über https gesendet; für Entwicklung allowInsecureHttp(true) setzen");
        }
        this.allowInsecureHttp = builder.allowInsecureHttp;
        this.baseUrl = builder.baseUrl;
        this.credentialRef = builder.credentialRef;
        this.pageSize = builder.pageSize;
        this.includeAttachments = builder.includeAttachments;
        this.maxAttachmentBytes = builder.maxAttachmentBytes;
        this.maxResponseBytes = builder.maxResponseBytes;
        this.searchSpaceKeys = Collections.unmodifiableList(new ArrayList<String>(builder.searchSpaceKeys));
        this.diagnosticsDirectory = builder.diagnosticsDirectory;
    }

    /**
     * @param baseUrl Basis-URL inklusive Kontextpfad, z. B. {@code https://confluence.example.org/confluence};
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

    public int pageSize() {
        return pageSize;
    }

    public boolean includeAttachments() {
        return includeAttachments;
    }

    public int maxAttachmentBytes() {
        return maxAttachmentBytes;
    }

    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Spaces, auf die die Suche eingeschränkt wird; leer = alle für den Benutzer sichtbaren. */
    public List<String> searchSpaceKeys() {
        return searchSpaceKeys;
    }

    /** {@code true}, wenn Zugangsdaten ausdrücklich auch über unverschlüsseltes http gesendet werden dürfen. */
    public boolean allowInsecureHttp() {
        return allowInsecureHttp;
    }

    /**
     * Verzeichnis für Diagnosedateien (HTML-Antworten statt Dateien) oder {@code null}: dann nur Logzeilen. Die
     * Anwendung setzt ihr Protokollverzeichnis.
     */
    public java.nio.file.Path diagnosticsDirectory() {
        return diagnosticsDirectory;
    }

    @Override
    public String toString() {
        return "ConfluenceConfig[" + baseUrl + ", credentials=" + (credentialRef == null ? "keine" : credentialRef)
                + ", attachments=" + includeAttachments + "]";
    }

    /** Builder; Defaults: 50 Einträge je Seite, ohne Anhänge, Anhänge bis 5 MB, Antworten bis 20 MB. */
    public static final class Builder {

        private final URI baseUrl;
        private SecretRef credentialRef;
        private int pageSize = 50;
        private boolean includeAttachments;
        private int maxAttachmentBytes = 5 * 1024 * 1024;
        private int maxResponseBytes = 20 * 1024 * 1024;
        private final List<String> searchSpaceKeys = new ArrayList<String>();
        private boolean allowInsecureHttp;
        private java.nio.file.Path diagnosticsDirectory;

        private Builder(URI baseUrl) {
            this.baseUrl = normalize(baseUrl);
        }

        public Builder credentialRef(SecretRef value) {
            this.credentialRef = value;
            return this;
        }

        /**
         * Nur für Entwicklung: erlaubt {@link #credentialRef(SecretRef)} zusammen mit einer http-Basis-URL.
         * Ohne diesen Schalter lehnt {@link #build()} die Kombination ab, weil Basic-Passwort oder Token sonst im
         * Klartext über die Leitung gingen.
         */
        public Builder allowInsecureHttp(boolean value) {
            this.allowInsecureHttp = value;
            return this;
        }

        /** Einträge je REST-Aufruf ({@code limit}); 1–500. */
        public Builder pageSize(int value) {
            if (value < 1 || value > 500) {
                throw new IllegalArgumentException("pageSize muss zwischen 1 und 500 liegen");
            }
            this.pageSize = value;
            return this;
        }

        /** Textartige Anhänge (text/*, JSON, XML) als eigene Ressourcen unter ihrer Seite melden. */
        public Builder includeAttachments(boolean value) {
            this.includeAttachments = value;
            return this;
        }

        public Builder maxAttachmentBytes(int value) {
            this.maxAttachmentBytes = positive(value, "maxAttachmentBytes");
            return this;
        }

        public Builder maxResponseBytes(int value) {
            this.maxResponseBytes = positive(value, "maxResponseBytes");
            return this;
        }

        public Builder searchSpaceKey(String spaceKey) {
            if (spaceKey == null || !ConfluenceIds.isSpaceKey(spaceKey.trim())) {
                throw new IllegalArgumentException("ungültiger Space-Key");
            }
            if (!searchSpaceKeys.contains(spaceKey.trim())) {
                searchSpaceKeys.add(spaceKey.trim());
            }
            return this;
        }

        /** Siehe {@link ConfluenceConfig#diagnosticsDirectory()}; {@code null} = keine Diagnosedateien. */
        public Builder diagnosticsDirectory(java.nio.file.Path value) {
            this.diagnosticsDirectory = value;
            return this;
        }

        public ConfluenceConfig build() {
            return new ConfluenceConfig(this);
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " muss positiv sein");
            }
            return value;
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
