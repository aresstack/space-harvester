package com.aresstack.enterpriseai.model.kipitz;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.util.function.Supplier;

/**
 * Konfiguration des KIPITZ-Katalogs. Basis-URL, Token und Netz kommen aus der Composition Root; das Token wird je
 * Abfrage geholt und nirgends gespeichert, {@link #toString()} enthält es nie.
 */
public final class KipitzModelCatalogConfig {

    private final URI baseUrl;
    private final Supplier<String> bearerToken;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private KipitzModelCatalogConfig(Builder b) {
        this.baseUrl = b.baseUrl;
        this.bearerToken = b.bearerToken;
        this.routes = b.routes;
        this.sslSocketFactory = b.sslSocketFactory;
        this.userAgent = b.userAgent;
        this.connectTimeoutMillis = b.connectTimeoutMillis;
        this.readTimeoutMillis = b.readTimeoutMillis;
    }

    /** @param baseUrl Basis der API ohne Endpunkt (meist bis {@code /v1}); der Adapter hängt {@code models} an */
    public static Builder builder(URI baseUrl) {
        return new Builder(baseUrl);
    }

    public URI baseUrl() {
        return baseUrl;
    }

    /** {@code <baseUrl>/models}. */
    public URI modelsEndpoint() {
        return endpoint("models");
    }

    /** {@code <baseUrl>/<path>}, etwa {@code audio/speech}. */
    public URI endpoint(String path) {
        String text = baseUrl.toString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text + "/" + path);
    }

    Supplier<String> bearerToken() {
        return bearerToken;
    }

    HttpRoutePort routes() {
        return routes;
    }

    SSLSocketFactory sslSocketFactory() {
        return sslSocketFactory;
    }

    String userAgent() {
        return userAgent;
    }

    int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    int readTimeoutMillis() {
        return readTimeoutMillis;
    }

    @Override
    public String toString() {
        return "KipitzModelCatalogConfig[" + modelsEndpoint() + "]";
    }

    public static final class Builder {

        private final URI baseUrl;
        private Supplier<String> bearerToken;
        private HttpRoutePort routes;
        private SSLSocketFactory sslSocketFactory;
        private String userAgent;
        private int connectTimeoutMillis = 10000;
        private int readTimeoutMillis = 30000;

        private Builder(URI baseUrl) {
            if (baseUrl == null || baseUrl.getScheme() == null) {
                throw new IllegalArgumentException("baseUrl must be an absolute URI");
            }
            this.baseUrl = baseUrl;
        }

        /** Liefert das Token je Abfrage; {@code null} oder leer = ohne Authorization-Header. */
        public Builder bearerToken(Supplier<String> value) {
            this.bearerToken = value;
            return this;
        }

        public Builder routes(HttpRoutePort value) {
            this.routes = value;
            return this;
        }

        public Builder sslSocketFactory(SSLSocketFactory value) {
            this.sslSocketFactory = value;
            return this;
        }

        public Builder userAgent(String value) {
            this.userAgent = value;
            return this;
        }

        public Builder connectTimeoutMillis(int value) {
            this.connectTimeoutMillis = Math.max(0, value);
            return this;
        }

        public Builder readTimeoutMillis(int value) {
            this.readTimeoutMillis = Math.max(0, value);
            return this;
        }

        public KipitzModelCatalogConfig build() {
            return new KipitzModelCatalogConfig(this);
        }
    }
}
