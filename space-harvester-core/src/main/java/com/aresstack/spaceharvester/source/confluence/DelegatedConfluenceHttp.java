package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * GET an die Confluence einer Quelle mit einem je Anfrage mitgegebenen Nutzertoken statt der Zugangsdaten der
 * Quelle (delegierte Anmeldung, #24), über dieselbe Proxy-Route und dasselbe Client-Zertifikat wie das Ernten.
 *
 * <ul>
 *     <li>Nur Pfade unter der Basis-URL der Quelle; alles andere wird abgelehnt, damit das Token nie an einen
 *     anderen Host geht.</li>
 *     <li>Gesendet werden genau {@code Authorization: Bearer …} und {@code Accept: application/json}: kein Körper,
 *     keine Cookies, keine Weiterleitungen (3xx kommt als Antwort mit {@code location()}).</li>
 *     <li>Feste Zeitgrenze und Körpergrenze; 4xx/5xx sind normale Antworten, die Zuordnung macht der Aufrufer.</li>
 *     <li>Das Token steht nie in Logs, {@link #toString()}, Fehlermeldungen oder Pool-Schlüsseln.</li>
 * </ul>
 */
public final class DelegatedConfluenceHttp implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BYTES = 1024 * 1024;

    private final URI base;
    private final WebClientConfluenceTransport transport;
    private final Duration timeout;

    DelegatedConfluenceHttp(URI baseUrl, WebClientConfluenceTransport transport, Duration timeout) {
        String text = baseUrl.toString();
        this.base = URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
        this.transport = transport;
        this.timeout = timeout;
    }

    /**
     * @param pathAndQuery Pfad relativ zur Basis-URL der Quelle, z. B. {@code /rest/api/user/current}
     * @param bearerToken  Confluence-Token des Nutzers (ohne {@code Bearer})
     * @return die Antwort, oder ausnahmsweise beendet mit {@link java.io.IOException} (Verbindung, Route, Zeit)
     * @throws IllegalArgumentException bei einem Pfad außerhalb der Quelle oder einem unbrauchbaren Token
     */
    public CompletionStage<ConfluenceHttpResponse> get(String pathAndQuery, String bearerToken) {
        URI target = target(pathAndQuery);
        if (bearerToken == null || bearerToken.isBlank() || bearerToken.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("Nutzertoken fehlt oder enthält unzulässige Zeichen");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + bearerToken);
        headers.put("Accept", "application/json");
        return transport.getOnceAsync(target, Map.copyOf(headers), MAX_BYTES, timeout);
    }

    private URI target(String pathAndQuery) {
        String path = pathAndQuery == null ? null : pathAndQuery.split("\\?", 2)[0];
        if (path == null || !path.startsWith("/") || path.contains("//") || path.contains("\\")
                || pathAndQuery.contains("#") || (path + "/").contains("/./") || (path + "/").contains("/../")
                || path.toLowerCase(java.util.Locale.ROOT).matches(".*%(2e|2f|5c).*")) {
            // auch kodierte Punkte und Schrägstriche: Confluence dekodiert sie und käme sonst aus dem Pfad heraus
            throw new IllegalArgumentException("nur Pfade relativ zur Confluence der Quelle erlaubt");
        }
        URI target;
        try {
            target = URI.create(base + pathAndQuery);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ungültiger Pfad");
        }
        URI normalized = target.normalize();
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        if (!normalized.equals(target) || !base.getScheme().equalsIgnoreCase(target.getScheme())
                || !base.getHost().equalsIgnoreCase(target.getHost()) || base.getPort() != target.getPort()
                || target.getRawUserInfo() != null || !target.getRawPath().startsWith(basePath + "/")) {
            throw new IllegalArgumentException("nur Pfade relativ zur Confluence der Quelle erlaubt");
        }
        return target;
    }

    @Override
    public void close() {
        transport.close();
    }

    /** Wie {@link #close()}, aber laufende Aufrufe mit dem Token des Nutzers enden noch normal. */
    public void closeGracefully() {
        transport.closeGracefully();
    }

    @Override
    public String toString() {
        return "DelegatedConfluenceHttp[" + base + "]";
    }
}
