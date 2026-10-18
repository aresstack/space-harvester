package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

/**
 * Unveränderliche Verbindungskonfiguration für KeePassRPC. Wird in der Composition Root aus der
 * Anwendungskonfiguration gebaut; der Adapter lädt keine Settings selbst.
 *
 * <p>Defaults nach MainframeMate ({@code Settings.keepassRpc*}): {@code 127.0.0.1:12546}, Origin mit
 * {@code chrome-extension://}-Präfix (KeePassRPC lehnt Verbindungen ohne erlaubten Origin stillschweigend ab),
 * 15 s Timeout.
 */
public final class KeePassRpcConfig {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 12546;
    public static final String DEFAULT_ORIGIN = "chrome-extension://enterpriseaiclient";
    public static final String DEFAULT_CLIENT_ID = "EnterpriseAiClient";
    public static final int DEFAULT_TIMEOUT_MILLIS = 15000;

    private final String host;
    private final int port;
    private final String origin;
    private final String clientId;
    private final String clientDisplayName;
    private final int timeoutMillis;

    private KeePassRpcConfig(Builder builder) {
        this.host = requireText(builder.host, "host");
        if (builder.port < 1 || builder.port > 65535) {
            throw new IllegalArgumentException("port außerhalb 1..65535: " + builder.port);
        }
        this.port = builder.port;
        this.origin = requireText(builder.origin, "origin");
        this.clientId = requireText(builder.clientId, "clientId");
        this.clientDisplayName = requireText(builder.clientDisplayName, "clientDisplayName");
        if (builder.timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis muss positiv sein");
        }
        this.timeoutMillis = builder.timeoutMillis;
    }

    public static KeePassRpcConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String origin() {
        return origin;
    }

    /** Client-ID, unter der KeePassRPC das Pairing speichert (SRP {@code I}, KCR {@code username}). */
    public String clientId() {
        return clientId;
    }

    /** Name, den KeePass im Pairing-Dialog anzeigt. */
    public String clientDisplayName() {
        return clientDisplayName;
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    @Override
    public String toString() {
        return "KeePassRpcConfig[" + host + ":" + port + ", origin=" + origin + ", clientId=" + clientId + "]";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " darf nicht leer sein");
        }
        return value.trim();
    }

    /** Builder mit den MainframeMate-Defaults. */
    public static final class Builder {

        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private String origin = DEFAULT_ORIGIN;
        private String clientId = DEFAULT_CLIENT_ID;
        private String clientDisplayName = "Enterprise AI Client";
        private int timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

        private Builder() {
        }

        public Builder host(String value) {
            this.host = value;
            return this;
        }

        public Builder port(int value) {
            this.port = value;
            return this;
        }

        public Builder origin(String value) {
            this.origin = value;
            return this;
        }

        public Builder clientId(String value) {
            this.clientId = value;
            return this;
        }

        public Builder clientDisplayName(String value) {
            this.clientDisplayName = value;
            return this;
        }

        public Builder timeoutMillis(int value) {
            this.timeoutMillis = value;
            return this;
        }

        public KeePassRpcConfig build() {
            return new KeePassRpcConfig(this);
        }
    }
}
