package com.aresstack.spaceharvester.deck.eakte;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Wohin eine E-Akte-Anmeldung geht. Das Kennwort gehört nicht hierher; es kommt beim Anmelden einmal herein
 * ({@link EakteServer#connect}) und wird danach überschrieben.
 *
 * @param webdav     WebDAV-Wurzel der E-Akte (Fabasoft), etwa {@code https://host/fsc/webdav}
 * @param start      optionale Startseite, die vor dem ersten WebDAV-Aufruf geladen wird (so meldete sich das
 *                   Testprogramm an); {@code null} = die Anmeldung beginnt mit dem WebDAV-Aufruf selbst
 * @param user       Benutzer der Anmeldung (ZBV), aus dem eigenen KeePass-Eintrag der E-Akte
 *
 * <p>Anmeldedienste stehen nirgends fest: Welche Server angesprochen werden dürfen, ergibt sich wie im Browser aus den
 * tatsächlichen Weiterleitungen ab der E-Akte-Adresse (siehe {@code SsoHttp}).
 */
public record EakteProfile(URI webdav, URI start, String user) {

    public EakteProfile {
        webdav = checked(webdav, "WebDAV-Adresse");
        if (start != null) {
            start = checked(start, "Startseite");
        }
        user = user == null ? "" : user.trim();
        if (user.isEmpty()) {
            throw new IllegalArgumentException("Benutzer fehlt");
        }
    }

    /** HTTPS, oder HTTP nur auf dem eigenen Rechner (Nachbau der Tests): nur dorthin gehen Zugangsdaten. */
    public static boolean secure(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return host.matches("[0-9.]+|\\[?[0-9a-fA-F:]+]?") && InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /** Ob {@code target} zur E-Akte selbst gehört (Host der WebDAV-Wurzel oder der Startseite). */
    public boolean isEakte(URI target) {
        return sameAuthority(target, webdav) || (start != null && sameAuthority(target, start));
    }

    /** Kurzname für Reiter und Statuszeile: {@code benutzer@host}. */
    public String label() {
        return user + "@" + webdav.getHost();
    }

    /** Pfad der WebDAV-Schnittstelle von Fabasoft, wenn nichts anderes eingestellt ist. */
    public static final String DEFAULT_WEBDAV_PATH = "/fsc/webdav";

    /**
     * Das Profil aus der Basisadresse der E-Akte (URL-Feld des KeePass-Eintrags) und dem WebDAV-Pfad: Endpunkt ist
     * {@code <Schema>://<Server><Pfad>}, zugleich Einstieg der Anmeldung und WebDAV-Wurzel (live belegt mit v2.0:
     * Start = WebDAV-Adresse ergibt 207). Ein Pfad in der Adresse zählt nicht; so wird {@code /fsc/webdav} auch bei
     * einer schon vollständigen Adresse nie doppelt angehängt.
     *
     * @param path WebDAV-Pfad, leer = {@link #DEFAULT_WEBDAV_PATH}
     */
    public static EakteProfile fromAddress(String address, String path, String user) {
        String text = address == null ? "" : address.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("E-Akte-Adresse fehlt");
        }
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        URI base;
        try {
            base = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("E-Akte-Adresse: keine gültige Adresse");
        }
        if (base.getScheme() == null || base.getHost() == null) {
            throw new IllegalArgumentException("E-Akte-Adresse: keine gültige Adresse");
        }
        URI endpoint = URI.create(base.getScheme().toLowerCase(Locale.ROOT) + "://" + base.getRawAuthority()
                + webdavPath(path));
        return new EakteProfile(endpoint, endpoint, user);
    }

    /** Der WebDAV-Pfad: mit führendem, ohne abschließenden Schrägstrich; leer = {@link #DEFAULT_WEBDAV_PATH}. */
    public static String webdavPath(String path) {
        String text = path == null ? "" : path.strip();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            return DEFAULT_WEBDAV_PATH;
        }
        if (text.contains("://") || text.contains("?") || text.contains("#") || text.contains(" ")) {
            throw new IllegalArgumentException("WebDAV-Pfad: nur ein Pfad wie " + DEFAULT_WEBDAV_PATH);
        }
        return text.startsWith("/") ? text : "/" + text;
    }

    private static URI checked(URI address, String what) {
        if (address == null) {
            throw new IllegalArgumentException(what + " fehlt");
        }
        String scheme = address.getScheme() == null ? "" : address.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(what + ": die Adresse muss mit https:// beginnen");
        }
        if (address.getHost() == null || address.getHost().isBlank()) {
            throw new IllegalArgumentException(what + ": in der Adresse fehlt der Rechnername");
        }
        return address;
    }

    private static boolean sameAuthority(URI a, URI b) {
        return a != null && b != null && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b)
                && String.valueOf(a.getScheme()).equalsIgnoreCase(String.valueOf(b.getScheme()));
    }

    private static int port(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }
}
