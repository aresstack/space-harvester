package com.aresstack.spaceharvester.deck.betaview.http;

import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cookies einer BetaView-Sitzung nach RFC 6265, wie ein Browser sie hält: {@code Domain} (mit oder ohne führenden
 * Punkt, nur der eigene Host oder eine übergeordnete Domain), sonst nur der setzende Host; {@code Path} (Vorgabe: der
 * Ordner der Anfrage); {@code Secure} nur über HTTPS; Ablauf über {@code Max-Age}/{@code Expires}. Gesendet werden
 * passende Cookies, die genaueren Pfade zuerst. So erreicht keine Sitzungs-ID einen fremden Host.
 */
final class CookieJar {

    private record Entry(String name, String value, String domain, boolean hostOnly, String path, boolean secure,
                         long expiresAt) {

        boolean expired(long now) {
            return expiresAt >= 0 && now >= expiresAt;
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final List<Entry> entries = new ArrayList<>();

    /** Übernimmt die {@code Set-Cookie}-Zeilen einer Antwort von {@code origin}; ungültige werden verworfen. */
    void store(URI origin, List<String> setCookieHeaders) {
        String host = host(origin);
        if (host.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String header : setCookieHeaders) {
            List<HttpCookie> parsed;
            try {
                parsed = HttpCookie.parse("Set-Cookie: " + header);
            } catch (IllegalArgumentException e) {
                continue;
            }
            for (HttpCookie cookie : parsed) {
                store(origin, host, cookie, now);
            }
        }
    }

    private void store(URI origin, String host, HttpCookie cookie, long now) {
        String domain = cookie.getDomain() == null ? "" : cookie.getDomain().trim().toLowerCase(Locale.ROOT);
        if (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        boolean hostOnly = domain.isEmpty();
        if (hostOnly) {
            domain = host;
        } else if (!domainMatches(host, domain) || (!domain.equals(host) && !domain.contains("."))) {
            return; // fremde Domain oder nur eine Endung („com“): nie annehmen
        }
        String path = cookie.getPath();
        if (path == null || !path.startsWith("/")) {
            path = defaultPath(origin);
        }
        long maxAge = cookie.getMaxAge();
        long expiresAt = maxAge < 0 ? -1 : now + maxAge * 1000L;
        Entry entry = new Entry(cookie.getName(), cookie.getValue() == null ? "" : cookie.getValue(), domain,
                hostOnly, path, cookie.getSecure(), expiresAt);
        lock.lock();
        try {
            String finalDomain = domain;
            String finalPath = path;
            entries.removeIf(old -> old.name.equals(entry.name) && old.domain.equals(finalDomain)
                    && old.path.equals(finalPath));
            if (!entry.expired(now)) {
                entries.add(entry);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Die {@code Cookie}-Zeile für {@code target}, leer wenn nichts passt. */
    String header(URI target) {
        String host = host(target);
        String path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();
        boolean https = "https".equalsIgnoreCase(target.getScheme());
        long now = System.currentTimeMillis();
        List<Entry> matching;
        lock.lock();
        try {
            entries.removeIf(entry -> entry.expired(now));
            matching = entries.stream()
                    .filter(entry -> entry.hostOnly ? host.equals(entry.domain) : domainMatches(host, entry.domain))
                    .filter(entry -> pathMatches(path, entry.path))
                    .filter(entry -> !entry.secure || https)
                    .sorted(Comparator.comparingInt((Entry entry) -> entry.path.length()).reversed())
                    .toList();
        } finally {
            lock.unlock();
        }
        StringBuilder line = new StringBuilder();
        for (Entry entry : matching) {
            if (line.length() > 0) {
                line.append("; ");
            }
            line.append(entry.name).append('=').append(entry.value);
        }
        return line.toString();
    }

    private static String host(URI uri) {
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    /** RFC 6265 5.1.3; IP-Adressen nur exakt. */
    static boolean domainMatches(String host, String domain) {
        if (host.equals(domain)) {
            return true;
        }
        boolean ip = host.matches("[0-9.]+") || host.contains(":");
        return !ip && host.endsWith("." + domain);
    }

    /** RFC 6265 5.1.4. */
    static boolean pathMatches(String requestPath, String cookiePath) {
        if (requestPath.equals(cookiePath)) {
            return true;
        }
        if (!requestPath.startsWith(cookiePath)) {
            return false;
        }
        return cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/';
    }

    /** RFC 6265 5.1.4: Ordner der Anfrage, ohne abschließenden Schrägstrich. */
    private static String defaultPath(URI origin) {
        String path = origin.getRawPath();
        if (path == null || !path.startsWith("/")) {
            return "/";
        }
        int last = path.lastIndexOf('/');
        return last <= 0 ? "/" : path.substring(0, last);
    }
}
