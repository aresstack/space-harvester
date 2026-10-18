package com.aresstack.enterpriseai.chat.openai;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.util.Locale;

/**
 * Unveränderliche Konfiguration des Adapters für die interne GPT-kompatible Enterprise-API. Base-URL, Modell
 * und Token sind reine Konfiguration (Composition Root); nichts davon ist im Code fest verdrahtet.
 *
 * <p>Der Adapter sendet an {@code <baseUrl>/chat/completions}. Das Bearer-Token wird über {@link TokenSource} erst beim
 * Senden geholt und nirgends gespeichert oder ausgegeben; {@link #toString()} enthält es nie.
 */
public final class OpenAiCompatibleChatConfig {

    /**
     * Liefert das Bearer-Token je Anfrage; {@code null} oder leer bedeutet "ohne Authorization-Header".
     * Die Composition Root implementiert sie über den Security-Port (z. B. {@code SecretProvider}), damit dieser
     * Adapter adyton nicht kennen muss und der Klartext nur für die Dauer einer Anfrage existiert.
     */
    public interface TokenSource {
        String token();

        /**
         * {@code true}, wenn {@code failure} aus {@link #token()} nur ein vorübergehendes Problem der Ablage war (z. B.
         * Passwortmanager kurz nicht erreichbar) und nichts über die Gültigkeit der Zugangsdaten sagt. Der Adapter
         * meldet das als {@link com.aresstack.enterpriseai.chat.api.ChatErrorKind#CREDENTIALS_UNAVAILABLE} statt als
         * Anmeldefehler. Standard: nein.
         */
        default boolean isTransient(RuntimeException failure) {
            return false;
        }

        /** Fester Token, z. B. für Tests oder lokale Konfiguration. */
        static TokenSource fixed(final String token) {
            return new TokenSource() {
                @Override
                public String token() {
                    return token;
                }

                @Override
                public String toString() {
                    return "TokenSource[fixed, ***]";
                }
            };
        }
    }

    /** Pfad, den der Adapter für Chat-Anfragen an die Basis-URL hängt. */
    public static final String CHAT_COMPLETIONS_PATH = "chat/completions";
    /** Pfad der Modellliste; die Basis-URL endet selbst nie auf einen dieser Endpunkte. */
    public static final String MODELS_PATH = "models";

    private static final String[] ENDPOINT_SUFFIXES = {"/chat/completions", "/embeddings", "/models", "/responses"};

    private final URI baseUrl;
    private final URI endpoint;
    private final URI modelsEndpoint;
    private final String defaultModel;
    private final TokenSource tokenSource;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final DeveloperRolePolicy developerRolePolicy;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;

    private OpenAiCompatibleChatConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.endpoint = resolve(builder.baseUrl, CHAT_COMPLETIONS_PATH);
        this.modelsEndpoint = resolve(builder.baseUrl, MODELS_PATH);
        this.defaultModel = builder.defaultModel;
        this.tokenSource = builder.tokenSource;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
        this.developerRolePolicy = builder.developerRolePolicy;
        this.routes = builder.routes;
        this.sslSocketFactory = builder.sslSocketFactory;
        this.userAgent = builder.userAgent;
    }

    /**
     * @param baseUrl Basis der API, z. B. {@code https://ki.intern/v1}; der Adapter hängt {@code chat/completions} an
     * @param defaultModel Modell, falls die Anfrage keines nennt (z. B. aus der Konfiguration)
     */
    public static Builder builder(URI baseUrl, String defaultModel) {
        return new Builder(baseUrl, defaultModel);
    }

    public URI baseUrl() {
        return baseUrl;
    }

    /** @return {@code <baseUrl>/chat/completions} */
    /** {@code <baseUrl>/chat/completions}. */
    public URI endpoint() {
        return endpoint;
    }

    /** {@code <baseUrl>/models}, der Endpunkt des Verbindungstests. */
    public URI modelsEndpoint() {
        return modelsEndpoint;
    }

    public String defaultModel() {
        return defaultModel;
    }

    TokenSource tokenSource() {
        return tokenSource;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /** @return maximale Wartezeit auf Daten (beim Streaming: zwischen zwei Chunks), 0 = unbegrenzt */
    public int readTimeoutMillis() {
        return readTimeoutMillis;
    }

    public DeveloperRolePolicy developerRolePolicy() {
        return developerRolePolicy;
    }

    /** Route je Anfrage (Proxy-Entscheidung); {@code null}: JVM-Standard ohne eigene Entscheidung. */
    public HttpRoutePort routes() {
        return routes;
    }

    /** Socket-Factory für HTTPS (Vertrauensregel); {@code null}: JVM-Standard. */
    public SSLSocketFactory sslSocketFactory() {
        return sslSocketFactory;
    }

    /** {@code User-Agent} jeder Anfrage; {@code null}: der Standard der JVM. */
    public String userAgent() {
        return userAgent;
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleChatConfig[baseUrl=" + baseUrl + ", defaultModel=" + defaultModel
                + ", token=" + (tokenSource == null ? "none" : "***") + ", connectTimeoutMillis="
                + connectTimeoutMillis + ", readTimeoutMillis=" + readTimeoutMillis
                + ", developerRolePolicy=" + developerRolePolicy
                + ", routes=" + (routes == null ? "jvm" : routes) + ", tls=" + (sslSocketFactory == null ? "jvm" : "eigen")
                + ", userAgent=" + (userAgent == null ? "jvm" : userAgent) + "]";
    }

    public static final class Builder {

        private final URI baseUrl;
        private final String defaultModel;
        private TokenSource tokenSource;
        private int connectTimeoutMillis = 10000;
        private int readTimeoutMillis = 120000;
        private DeveloperRolePolicy developerRolePolicy = DeveloperRolePolicy.REJECT;
        private HttpRoutePort routes;
        private SSLSocketFactory sslSocketFactory;
        private String userAgent;

        private Builder(URI baseUrl, String defaultModel) {
            if (baseUrl == null || baseUrl.getScheme() == null
                    || !("http".equalsIgnoreCase(baseUrl.getScheme()) || "https".equalsIgnoreCase(baseUrl.getScheme()))) {
                throw new IllegalArgumentException("baseUrl must be an absolute http(s) URI");
            }
            if (baseUrl.getHost() == null || baseUrl.getHost().isEmpty()) {
                throw new IllegalArgumentException("baseUrl must name a host");
            }
            if (baseUrl.getUserInfo() != null) {
                throw new IllegalArgumentException("baseUrl must not contain user info; use the token source");
            }
            if (baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
                throw new IllegalArgumentException("baseUrl must not contain a query or fragment");
            }
            String endpointSuffix = endpointSuffix(baseUrl);
            if (endpointSuffix != null) {
                throw new IllegalArgumentException("baseUrl must not end with the endpoint path " + endpointSuffix
                        + "; the adapter appends /chat/completions itself");
            }
            if (defaultModel == null || defaultModel.trim().isEmpty()) {
                throw new IllegalArgumentException("defaultModel must not be blank");
            }
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel.trim();
        }

        public Builder bearerToken(TokenSource source) {
            this.tokenSource = source;
            return this;
        }

        public Builder connectTimeoutMillis(int millis) {
            if (millis < 0) {
                throw new IllegalArgumentException("connect timeout must not be negative");
            }
            this.connectTimeoutMillis = millis;
            return this;
        }

        public Builder readTimeoutMillis(int millis) {
            if (millis < 0) {
                throw new IllegalArgumentException("read timeout must not be negative");
            }
            this.readTimeoutMillis = millis;
            return this;
        }

        public Builder developerRolePolicy(DeveloperRolePolicy policy) {
            if (policy == null) {
                throw new IllegalArgumentException("policy must not be null");
            }
            this.developerRolePolicy = policy;
            return this;
        }

        /**
         * Routenentscheidung je Anfrage. Der Adapter fragt den Port vor jeder Verbindung und übergibt das Ergebnis
         * explizit an {@code URL.openConnection(Proxy)}: DIRECT heißt {@code Proxy.NO_PROXY} (kein JVM-Selector),
         * UNAVAILABLE scheitert als Transportfehler mit dem Grund. {@code null}: JVM-Standard.
         */
        public Builder routes(HttpRoutePort port) {
            this.routes = port;
            return this;
        }

        /** Socket-Factory für HTTPS-Verbindungen (Vertrauensregel der Anwendung); {@code null}: JVM-Standard. */
        public Builder sslSocketFactory(SSLSocketFactory factory) {
            this.sslSocketFactory = factory;
            return this;
        }

        /** {@code User-Agent} jeder Anfrage; leer oder {@code null}: JVM-Standard. */
        public Builder userAgent(String value) {
            this.userAgent = value == null || value.trim().isEmpty() ? null : value.trim();
            return this;
        }

        public OpenAiCompatibleChatConfig build() {
            return new OpenAiCompatibleChatConfig(this);
        }
    }

    private static URI resolve(URI base, String path) {
        String text = base.toString();
        return URI.create(text.endsWith("/") ? text + path : text + "/" + path);
    }

    /**
     * Der Endpunkt-Pfad, auf den die Basis-URL fälschlich endet ({@code /chat/completions}, {@code /embeddings},
     * {@code /models}), sonst {@code null}. Schließende Schrägstriche zählen nicht.
     */
    public static String endpointSuffix(URI baseUrl) {
        String path = baseUrl.getPath() == null ? "" : baseUrl.getPath().toLowerCase(Locale.ROOT);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        for (String suffix : ENDPOINT_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return suffix;
            }
        }
        return null;
    }
}
