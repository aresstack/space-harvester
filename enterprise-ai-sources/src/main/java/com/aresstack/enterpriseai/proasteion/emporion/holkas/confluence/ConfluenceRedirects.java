package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import java.net.URI;
import java.util.Map;

/**
 * Weiterleitungs- und Cookie-Regeln der Confluence-Transporte, gemeinsam für {@link UrlConnectionConfluenceTransport}
 * und asynchrone Umsetzungen von {@link ConfluenceAsyncHttpTransport}, damit beide gleich entscheiden: welcher
 * Weiterleitung gefolgt wird (nur derselbe Host und Port, ohne Userinfo, nie von https auf http) und welche Cookies
 * beim nächsten Schritt mitgehen. Cookie-Werte werden nie geloggt.
 */
public final class ConfluenceRedirects {

    /** Höchstzahl verfolgter Weiterleitungen je Anfrage. */
    public static final int MAX_REDIRECTS = 5;

    private ConfluenceRedirects() {
    }

    /** Die Header mit den unterwegs gesetzten Cookies im {@code Cookie}-Header (gleiche Namen ersetzt). */
    public static Map<String, String> withCookies(Map<String, String> headers, Map<String, String> collected) {
        if (collected.isEmpty()) {
            return headers;
        }
        Map<String, String> jar = new java.util.LinkedHashMap<String, String>();
        String given = null;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if ("Cookie".equalsIgnoreCase(header.getKey())) {
                given = header.getValue();
            }
        }
        if (given != null) {
            for (String pair : given.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    jar.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        jar.putAll(collected);
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!"Cookie".equalsIgnoreCase(header.getKey())) {
                out.put(header.getKey(), header.getValue());
            }
        }
        StringBuilder cookie = new StringBuilder();
        for (Map.Entry<String, String> entry : jar.entrySet()) {
            cookie.append(cookie.length() == 0 ? "" : "; ").append(entry.getKey()).append('=').append(entry.getValue());
        }
        out.put("Cookie", cookie.toString());
        return out;
    }

    /** Spätere Cookies gleichen Namens ersetzen frühere; ein Löschcookie (leerer Wert) entfernt den Namen. */
    public static void merge(Map<String, String> cookies, java.util.List<String> pairs) {
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            cookies.remove(name);
            if (!value.isEmpty()) {
                cookies.put(name, value);
            }
        }
    }

    /** Die Cookies als {@code name=wert}, wie {@link ConfluenceHttpResponse#cookies()} sie trägt. */
    public static java.util.List<String> pairs(Map<String, String> cookies) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            out.add(cookie.getKey() + "=" + cookie.getValue());
        }
        return out;
    }

    /**
     * Das Ziel einer Weiterleitung (301/302/303/307/308), der gefolgt werden darf, sonst {@code null}: nur derselbe
     * Host und Port, ohne Userinfo, nie von https auf http.
     */
    public static URI sameHostRedirect(URI from, int status, String location) {
        if ((status != 301 && status != 302 && status != 303 && status != 307 && status != 308)
                || location == null || location.trim().isEmpty()) {
            return null;
        }
        URI target;
        try {
            target = from.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (target.getHost() == null || !target.getHost().equalsIgnoreCase(from.getHost())
                || effectivePort(target) != effectivePort(from) || target.getRawUserInfo() != null) {
            return null;
        }
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(target.getScheme())) {
            return null;
        }
        return target;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * {@code name=wert} jedes {@code Set-Cookie}-Headers, ohne Attribute wie Path oder Expires; je Name gilt das
     * letzte. Ein Löschcookie (leerer Wert oder {@code Max-Age=0}) kommt als {@code name=} und entfernt den Namen
     * beim Zusammenführen über Weiterleitungen.
     */
    public static java.util.List<String> setCookies(Map<String, java.util.List<String>> headerFields) {
        Map<String, String> cookies = new java.util.LinkedHashMap<String, String>();
        for (Map.Entry<String, java.util.List<String>> header : headerFields.entrySet()) {
            if (header.getKey() == null || !"Set-Cookie".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            for (String value : header.getValue()) {
                String[] parts = value == null ? new String[] {""} : value.split(";");
                String pair = parts[0].trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                boolean expired = false;
                for (int i = 1; i < parts.length; i++) {
                    expired |= parts[i].trim().replace(" ", "").equalsIgnoreCase("Max-Age=0");
                }
                String name = pair.substring(0, eq).trim();
                cookies.remove(name);
                cookies.put(name, expired ? "" : pair.substring(eq + 1).trim());
            }
        }
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            out.add(cookie.getKey() + "=" + cookie.getValue());
        }
        return out;
    }
}
