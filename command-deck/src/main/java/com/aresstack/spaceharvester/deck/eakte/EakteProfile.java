package com.aresstack.spaceharvester.deck.eakte;

import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wohin eine E-Akte-Anmeldung geht. Das Kennwort gehört nicht hierher; es kommt beim Anmelden einmal herein
 * ({@link EakteServer#connect}) und wird danach überschrieben.
 *
 * @param webdav     WebDAV-Wurzel der E-Akte (Fabasoft), etwa {@code https://host/fsc/webdav}
 * @param start      optionale Startseite, die vor dem ersten WebDAV-Aufruf geladen wird (so meldete sich das
 *                   Testprogramm an); {@code null} = die Anmeldung beginnt mit dem WebDAV-Aufruf selbst
 * @param user       Benutzer der Anmeldung (ZBV), aus dem eigenen KeePass-Eintrag der E-Akte
 * @param loginHosts Anmeldedienste (Host oder Host:Port), die das Kennwort bekommen dürfen; jeder andere Host bekommt
 *                   es nie, auch nicht nach einer Weiterleitung
 */
public record EakteProfile(URI webdav, URI start, String user, Set<String> loginHosts) {

    /** WebDAV der E-Akte, wie im Testprogramm von Angelo (23.11.2026). */
    public static final String DEFAULT_WEBDAV = "https://eakte.zoll.in.bund.de/fsc/webdav";

    /**
     * Startseite aus dem Testprogramm v2.0 (Angelo, 23.11.2026): Über sie lief die Anmeldung auf dem echten System
     * vollständig durch, danach lieferte PROPFIND sofort 207.
     */
    public static final String DEFAULT_START = "https://eakte.zoll.in.bund.de/fsc/fscasp/content/bin/fscvext.dll"
            + "?bx=COO.7169.100.8.2186717&venv_view=COO.1.1001.1.439101";

    /**
     * Die Anmeldedienste aus dem Testprogramm: OpenAM (ZBV) und der Identitätsdienst mit dem Anmeldeformular. Nur sie
     * bekommen Benutzer und Kennwort.
     */
    public static final List<String> DEFAULT_LOGIN_HOSTS =
            List.of("amp3b.cloud.zoll.in.bund.de", "anwendungen.zoll.itzbund.net");

    public EakteProfile {
        webdav = checked(webdav, "WebDAV-Adresse");
        if (start != null) {
            start = checked(start, "Startseite");
        }
        user = user == null ? "" : user.trim();
        if (user.isEmpty()) {
            throw new IllegalArgumentException("Benutzer fehlt");
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String host : loginHosts == null ? Set.<String>of() : loginHosts) {
            if (host != null && !host.isBlank()) {
                hosts.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("Kein Anmeldedienst angegeben");
        }
        loginHosts = Set.copyOf(hosts);
    }

    /** Mit den Vorgaben für WebDAV, Startseite und Anmeldedienste. */
    public static EakteProfile withDefaults(String user) {
        return new EakteProfile(URI.create(DEFAULT_WEBDAV), URI.create(DEFAULT_START), user,
                Set.copyOf(DEFAULT_LOGIN_HOSTS));
    }

    /** Ob {@code target} ein Anmeldedienst ist, der Zugangsdaten bekommen darf (nur über HTTPS, lokal auch HTTP). */
    public boolean mayReceiveCredentials(URI target) {
        if (target == null || target.getHost() == null || !secure(target)) {
            return false;
        }
        String host = target.getHost().toLowerCase(Locale.ROOT);
        return loginHosts.contains(host) || loginHosts.contains(host + ":" + port(target));
    }

    /** Ob {@code target} zur E-Akte selbst gehört (Host der WebDAV-Wurzel oder der Startseite). */
    public boolean isEakte(URI target) {
        return sameAuthority(target, webdav) || (start != null && sameAuthority(target, start));
    }

    /** Ob eine Weiterleitung nach {@code target} gefolgt werden darf: E-Akte oder ein Anmeldedienst. */
    public boolean allows(URI target) {
        return target != null && target.getHost() != null && (isEakte(target) || mayReceiveCredentials(target));
    }

    /** Kurzname für Reiter und Statuszeile: {@code benutzer@host}. */
    public String label() {
        return user + "@" + webdav.getHost();
    }

    /**
     * Baut aus einer eingetippten oder aus KeePass übernommenen Adresse die WebDAV-Wurzel: ohne Schema gilt https,
     * ohne Pfad {@code /fsc/webdav}, ein abschließender Schrägstrich entfällt.
     */
    public static URI normaliseWebdav(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Adresse fehlt");
        }
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        URI parsed;
        try {
            parsed = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Keine gültige Adresse: " + raw.trim());
        }
        if (parsed.getHost() == null) {
            throw new IllegalArgumentException("Keine gültige Adresse: " + raw.trim());
        }
        String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
        if (path.isEmpty() || path.equals("/")) {
            path = "/fsc/webdav";
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String authority = parsed.getRawAuthority();
        return URI.create(parsed.getScheme().toLowerCase(Locale.ROOT) + "://" + authority + path);
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

    /** HTTPS, oder HTTP nur auf dem eigenen Rechner (Schein-Server der Tests). */
    private static boolean secure(URI uri) {
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
}
