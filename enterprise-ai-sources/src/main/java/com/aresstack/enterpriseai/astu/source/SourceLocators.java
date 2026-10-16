package com.aresstack.enterpriseai.astu.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Kanonische Locator einer Quelle: der technische Ursprung ohne Scheme, aus dem die Ressourcen-IDs
 * ({@code <scheme>:<locator>/<ziel>}, als corenth-Bookmark {@code <scheme>://<locator>/<ziel>}) und der interne
 * die Quellenadresse ({@link KnowledgeSourceType#addressFor}) entstehen. Gleiche Endpunkte ergeben denselben Locator,
 * unabhängig von Groß-/Kleinschreibung des Hosts, Standardport oder Schrägstrich am Ende.
 *
 * <ul>
 *   <li>Web-Endpunkt: {@code https://CF.intern:443/confluence/} → {@code cf.intern/confluence}</li>
 *   <li>Host und Port (FTP, NDV): {@code Host.Example}, 21 → {@code host.example:21}</li>
 *   <li>Verzeichnis: {@code C:\Daten\Doku\} → {@code /C:/Daten/Doku}</li>
 * </ul>
 * Benutzerangaben in der URL, Query und Fragment gehören nie zum Locator.
 */
public final class SourceLocators {

    private SourceLocators() {
    }

    /**
     * Basis-URL eines Web-Endpunkts: Host klein, Port nur, wenn er vom Standard des Protokolls abweicht, Pfad
     * ohne Schrägstrich am Ende; leer, wenn {@code url} keine http(s)-URL mit Host ist.
     */
    public static String url(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return "";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.isOpaque() || uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
            return "";
        }
        StringBuilder locator = new StringBuilder(uri.getHost().toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port > 0 && !("http".equals(scheme) && port == 80) && !("https".equals(scheme) && port == 443)) {
            locator.append(':').append(port);
        }
        locator.append(trimSlashes(uri.getRawPath()));
        return locator.toString();
    }

    /** Host und Port: {@code host.example:21}; leer ohne Host. */
    public static String hostPort(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            return "";
        }
        return host.trim().toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** Verzeichnis als Pfad mit {@code /}, beginnend mit {@code /}, ohne {@code /} am Ende; leer ohne Pfad. */
    public static String path(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            return "";
        }
        String path = directory.trim().replace('\\', '/');
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Allgemeiner Locator aus dem Adressfeld eines Quelltyps (für Adapter ohne eigene Regel): http(s)-URL wie
     * {@link #url}, sonst der Wert ohne Leerraum am Rand, klein geschrieben und ohne Schrägstrich am Ende.
     */
    public static String of(String address) {
        if (address == null || address.trim().isEmpty()) {
            return "";
        }
        String url = url(address);
        if (!url.isEmpty()) {
            return url;
        }
        String value = address.trim();
        int scheme = value.indexOf("://");
        if (scheme > 0) {
            value = value.substring(scheme + 3);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String trimSlashes(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
