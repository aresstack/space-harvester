package com.aresstack.enterpriseai.embedding.openai;

import com.aresstack.enterpriseai.astu.embedding.EmbeddingModelIdentity;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

import javax.net.ssl.SSLSocketFactory;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Unveränderliche Konfiguration des {@link OpenAiCompatibleEmbeddingAdapter}. Base-URL, Modell und Dimension sind
 * reine Konfiguration (nichts davon ist hart codiert); die Composition Root liest sie und baut den Adapter.
 *
 * <p>Das Bearer-Token ist nicht Teil dieser Klasse, sondern wird dem Adapter als {@link BearerTokenSource}
 * übergeben und je Request frisch gelesen. {@link #toString()} enthält deshalb nie ein Secret.
 */
public final class OpenAiCompatibleEmbeddingConfiguration {

    /** Pfad relativ zur Base-URL, wie in der Enterprise-OpenAPI dokumentiert. */
    public static final String DEFAULT_EMBEDDINGS_PATH = "/embeddings";
    /** Pfad der Modellliste unter derselben Basis-URL (Verbindungstest). */
    public static final String MODELS_PATH = "/models";
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 60000;
    public static final int DEFAULT_MAX_BATCH_SIZE = 16;

    private final URI endpoint;
    private final String modelId;
    private final int dimension;
    private final SortedMap<String, String> identityAttributes;
    private final EmbeddingInputMode inputMode;
    private final int maxBatchSize;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final Proxy proxy;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;
    private final URI modelsEndpoint;

    private OpenAiCompatibleEmbeddingConfiguration(Builder builder) {
        this.endpoint = resolveEndpoint(builder.baseUrl, builder.embeddingsPath);
        this.modelsEndpoint = resolveEndpoint(builder.baseUrl, MODELS_PATH);
        this.modelId = requireText(builder.modelId, "modelId");
        if (builder.dimension <= 0) {
            throw new IllegalArgumentException("dimension must be configured and positive: " + builder.dimension);
        }
        this.dimension = builder.dimension;
        this.identityAttributes = Collections.unmodifiableSortedMap(new TreeMap<String, String>(builder.identityAttributes));
        this.inputMode = builder.inputMode;
        if (builder.maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive: " + builder.maxBatchSize);
        }
        this.maxBatchSize = builder.maxBatchSize;
        this.connectTimeoutMillis = requireTimeout(builder.connectTimeoutMillis, "connectTimeoutMillis");
        this.readTimeoutMillis = requireTimeout(builder.readTimeoutMillis, "readTimeoutMillis");
        this.proxy = builder.proxy;
        this.routes = builder.routes;
        this.sslSocketFactory = builder.sslSocketFactory;
        this.userAgent = builder.userAgent;
    }

    /**
     * @param baseUrl   z. B. {@code https://ai.intern.example/v1}; {@code /embeddings} wird angehängt
     * @param modelId   Embedding-Modell, wie es der Server erwartet
     * @param dimension erwartete Vektordimension; jede Antwort wird dagegen geprüft
     */
    public static Builder builder(String baseUrl, String modelId, int dimension) {
        return new Builder(baseUrl, modelId, dimension);
    }

    /**
     * Die Embedding-Welt dieses Adapters: Modell-ID, Dimension, {@code encoding=float} und alle per
     * {@link Builder#identityAttribute} gesetzten Merkmale. Bewusst ohne URL, Timeouts oder Zugangsdaten.
     */
    public EmbeddingModelIdentity modelIdentity() {
        return modelIdentity(dimension);
    }

    /**
     * Wie {@link #modelIdentity()}, aber mit der Dimension, die der Dienst tatsächlich liefert (Modellerkennung des
     * Adapters, wenn die konfigurierte Dimension nicht stimmt).
     */
    public EmbeddingModelIdentity modelIdentity(int actualDimension) {
        EmbeddingModelIdentity identity = EmbeddingModelIdentity.of(modelId, actualDimension)
                .withAttribute("encoding", "float");
        for (Map.Entry<String, String> entry : identityAttributes.entrySet()) {
            identity = identity.withAttribute(entry.getKey(), entry.getValue());
        }
        return identity;
    }

    public URI endpoint() {
        return endpoint;
    }

    public String modelId() {
        return modelId;
    }

    public int dimension() {
        return dimension;
    }

    public EmbeddingInputMode inputMode() {
        return inputMode;
    }

    public int maxBatchSize() {
        return maxBatchSize;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int readTimeoutMillis() {
        return readTimeoutMillis;
    }

    /** {@code null}: JVM-Standard (System-Properties / ProxySelector). */
    /**
     * Fester Proxy für alle Anfragen; {@code null}: JVM-Standard. Ist ein {@link #routes() Routen-Port} gesetzt,
     * entscheidet dieser je Anfrage und dieser Wert bleibt unbenutzt.
     */
    public Proxy proxy() {
        return proxy;
    }

    /** Route je Anfrage (Proxy-Entscheidung der Composition Root); {@code null}: {@link #proxy()} bzw. JVM. */
    public HttpRoutePort routes() {
        return routes;
    }

    /** Socket-Factory für HTTPS (Vertrauensregel); {@code null}: JVM-Standard. */
    public SSLSocketFactory sslSocketFactory() {
        return sslSocketFactory;
    }

    /** {@code User-Agent} jeder Anfrage; {@code null}: JVM-Standard. */
    public String userAgent() {
        return userAgent;
    }

    /** {@code <baseUrl>/models}. */
    public URI modelsEndpoint() {
        return modelsEndpoint;
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleEmbeddingConfiguration{endpoint=" + endpoint + ", model=" + modelId
                + ", dimension=" + dimension + ", inputMode=" + inputMode + ", maxBatchSize=" + maxBatchSize + "}";
    }

    private static URI resolveEndpoint(String baseUrl, String path) {
        String base = requireText(baseUrl, "baseUrl");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String p = requireText(path, "embeddingsPath");
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        URI uri;
        try {
            uri = new URI(base + p);
        } catch (URISyntaxException ex) {
            // Eingabe nicht wiederholen: sie könnte Zugangsdaten enthalten.
            throw new IllegalArgumentException("baseUrl is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain a query or fragment");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must not contain credentials");
        }
        String suffix = endpointSuffix(base);
        if (suffix != null) {
            throw new IllegalArgumentException("baseUrl must not end with the endpoint path " + suffix
                    + "; the adapter appends " + p + " itself");
        }
        return uri;
    }

    /**
     * Der Endpunkt-Pfad, auf den eine Basis-URL fälschlich endet ({@code /chat/completions}, {@code /embeddings},
     * {@code /models}), sonst {@code null}.
     */
    public static String endpointSuffix(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String path;
        try {
            URI uri = new URI(baseUrl.trim());
            path = uri.getPath() == null ? "" : uri.getPath();
        } catch (URISyntaxException ex) {
            return null;
        }
        path = path.toLowerCase(java.util.Locale.ROOT);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        for (String suffix : new String[] {"/chat/completions", "/embeddings", "/models"}) {
            if (path.endsWith(suffix)) {
                return suffix;
            }
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static int requireTimeout(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
        return value;
    }

    /** Builder; alle Setter liefern {@code this}. */
    public static final class Builder {

        private final String baseUrl;
        private final String modelId;
        private final int dimension;
        private String embeddingsPath = DEFAULT_EMBEDDINGS_PATH;
        private final SortedMap<String, String> identityAttributes = new TreeMap<String, String>();
        private EmbeddingInputMode inputMode = EmbeddingInputMode.SINGLE_STRING;
        private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
        private int connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
        private int readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;
        private Proxy proxy;
        private HttpRoutePort routes;
        private SSLSocketFactory sslSocketFactory;
        private String userAgent;

        private Builder(String baseUrl, String modelId, int dimension) {
            this.baseUrl = baseUrl;
            this.modelId = modelId;
            this.dimension = dimension;
        }

        public Builder embeddingsPath(String path) {
            this.embeddingsPath = path;
            return this;
        }

        /**
         * Zusätzliches Merkmal der Embedding-Welt, z. B. {@code modelVersion}. Ändert den Fingerprint und trennt
         * damit alte von neuen Vektoren. Keine Secrets.
         */
        public Builder identityAttribute(String key, String value) {
            identityAttributes.put(key, value);
            return this;
        }

        public Builder inputMode(EmbeddingInputMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("inputMode must not be null");
            }
            this.inputMode = mode;
            return this;
        }

        /** Nur für {@link EmbeddingInputMode#ARRAY_UNVERIFIED}: maximale Texte je Request. */
        public Builder maxBatchSize(int size) {
            this.maxBatchSize = size;
            return this;
        }

        public Builder connectTimeoutMillis(int millis) {
            this.connectTimeoutMillis = millis;
            return this;
        }

        public Builder readTimeoutMillis(int millis) {
            this.readTimeoutMillis = millis;
            return this;
        }

        public Builder proxy(Proxy proxy) {
            this.proxy = proxy;
            return this;
        }

        /**
         * Routenentscheidung je Anfrage; hat Vorrang vor {@link #proxy(Proxy)}. DIRECT wird ausdrücklich
         * {@code Proxy.NO_PROXY}, UNAVAILABLE ein Transportfehler mit Grund.
         */
        public Builder routes(HttpRoutePort port) {
            this.routes = port;
            return this;
        }

        /** Socket-Factory für HTTPS (Vertrauensregel); {@code null}: JVM-Standard. */
        public Builder sslSocketFactory(SSLSocketFactory factory) {
            this.sslSocketFactory = factory;
            return this;
        }

        /** {@code User-Agent} jeder Anfrage; leer oder {@code null}: JVM-Standard. */
        public Builder userAgent(String value) {
            this.userAgent = value == null || value.trim().isEmpty() ? null : value.trim();
            return this;
        }

        public OpenAiCompatibleEmbeddingConfiguration build() {
            return new OpenAiCompatibleEmbeddingConfiguration(this);
        }
    }
}
