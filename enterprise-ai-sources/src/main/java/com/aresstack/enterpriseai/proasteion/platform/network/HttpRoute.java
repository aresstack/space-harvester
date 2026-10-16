package com.aresstack.enterpriseai.proasteion.platform.network;

/**
 * Ergebnis einer Routenentscheidung für eine Ziel-URL: direkt, über einen HTTP-Proxy oder nicht bestimmbar.
 *
 * <p>Wert ohne Transporttypen: der Adapter macht daraus {@code java.net.Proxy}. {@link Kind#UNAVAILABLE} trägt
 * einen technischen Grund ({@link #reason()}, z. B. {@code pac-download-failed} oder
 * {@code windows-native-proxy-settings-not-implemented}) und eine Beschreibung für Protokoll und Oberfläche.
 */
public final class HttpRoute {

    /** Art der Route. */
    public enum Kind {
        /** Direktverbindung ohne Proxy. */
        DIRECT,
        /** HTTP-Proxy (auch für HTTPS per CONNECT) mit {@link #proxyHost()} und {@link #proxyPort()}. */
        PROXY,
        /** Keine Entscheidung möglich (Fehler oder nicht implementierter Modus); nicht verbinden. */
        UNAVAILABLE
    }

    private final Kind kind;
    private final String proxyHost;
    private final int proxyPort;
    private final String reason;
    private final String detail;

    private HttpRoute(Kind kind, String proxyHost, int proxyPort, String reason, String detail) {
        this.kind = kind;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.reason = reason == null ? "" : reason;
        this.detail = detail == null ? "" : detail;
    }

    /** @param reason kurzer technischer Grund, z. B. {@code disabled}, {@code loopback}, {@code pac:DIRECT} */
    public static HttpRoute direct(String reason) {
        return new HttpRoute(Kind.DIRECT, null, -1, reason, null);
    }

    /**
     * @param host   Proxy-Host, nicht leer
     * @param port   Proxy-Port 1..65535
     * @param reason kurzer technischer Grund, z. B. {@code manual}, {@code pac:resolved}
     */
    public static HttpRoute proxy(String host, int port, String reason) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("proxy host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("proxy port must be in 1..65535: " + port);
        }
        return new HttpRoute(Kind.PROXY, host.trim(), port, reason, null);
    }

    /**
     * @param reason technischer Grund, nie leer (z. B. {@code pac-url-not-found})
     * @param detail Beschreibung für Protokoll und Oberfläche; darf leer sein
     */
    public static HttpRoute unavailable(String reason, String detail) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return new HttpRoute(Kind.UNAVAILABLE, null, -1, reason.trim(), detail);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isDirect() {
        return kind == Kind.DIRECT;
    }

    public boolean isProxy() {
        return kind == Kind.PROXY;
    }

    public boolean isUnavailable() {
        return kind == Kind.UNAVAILABLE;
    }

    /** Proxy-Host; nur bei {@link Kind#PROXY} gesetzt, sonst {@code null}. */
    public String proxyHost() {
        return proxyHost;
    }

    /** Proxy-Port; nur bei {@link Kind#PROXY} gesetzt, sonst {@code -1}. */
    public int proxyPort() {
        return proxyPort;
    }

    /** Kurzer technischer Grund, nie {@code null}. */
    public String reason() {
        return reason;
    }

    /** Beschreibung für Menschen, nie {@code null}, evtl. leer. */
    public String detail() {
        return detail;
    }

    /** Eine Zeile für Protokoll und Oberfläche, z. B. {@code PROXY proxy.intern.example:8080 (pac:resolved)}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        switch (kind) {
            case PROXY:
                sb.append("PROXY ").append(proxyHost).append(':').append(proxyPort);
                break;
            case DIRECT:
                sb.append("DIRECT");
                break;
            default:
                sb.append("UNAVAILABLE");
        }
        if (!reason.isEmpty()) {
            sb.append(" (").append(reason).append(')');
        }
        if (!detail.isEmpty()) {
            sb.append(": ").append(detail);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HttpRoute)) {
            return false;
        }
        HttpRoute that = (HttpRoute) other;
        return kind == that.kind && proxyPort == that.proxyPort
                && (proxyHost == null ? that.proxyHost == null : proxyHost.equals(that.proxyHost))
                && reason.equals(that.reason) && detail.equals(that.detail);
    }

    @Override
    public int hashCode() {
        int result = kind.hashCode();
        result = 31 * result + (proxyHost == null ? 0 : proxyHost.hashCode());
        result = 31 * result + proxyPort;
        result = 31 * result + reason.hashCode();
        result = 31 * result + detail.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "HttpRoute[" + describe() + "]";
    }
}
