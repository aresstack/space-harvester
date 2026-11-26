package com.aresstack.spaceharvester.deck.eakte.http;

import com.aresstack.spaceharvester.deck.eakte.EakteProfile;
import com.aresstack.spaceharvester.deck.http.CookieJar;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Die HTTP-Ebene einer E-Akte-Sitzung: Weiterleitungen nie automatisch (jede wird geprüft, ob sie zur E-Akte oder zu
 * einem Anmeldedienst geht), Cookies nach RFC 6265 ({@link CookieJar}, auch aus WebDAV-Antworten), und ein
 * Protokoll ohne Werte: nur Methode, Host, Pfad, Namen der Parameter und Namen der Cookies.
 */
final class SsoHttp {

    static final String USER_AGENT = "Mozilla/5.0 (compatible; SpaceHarvester-CommandDeck)";

    /** Eine Antwort; der Text wird nur bei Bedarf gelesen. */
    record Response(URI uri, int status, String location, String contentType, byte[] body) {

        boolean redirect() {
            return (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
                    && location != null;
        }

        URI next() {
            return uri.resolve(location.trim().replace(" ", "%20"));
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        boolean html() {
            return contentType.toLowerCase(Locale.ROOT).contains("html");
        }

        boolean json() {
            return contentType.toLowerCase(Locale.ROOT).contains("json");
        }
    }

    private final HttpClient client;
    private final EakteProfile profile;
    private final Duration timeout;
    private final Consumer<String> trace;
    private final CookieJar cookies = new CookieJar();

    SsoHttp(HttpClient client, EakteProfile profile, Duration timeout, Consumer<String> trace) {
        this.client = client;
        this.profile = profile;
        this.timeout = timeout;
        this.trace = trace;
    }

    /**
     * Server, die angesprochen werden dürfen ({@code schema://host:port}): anfangs nur die E-Akte; dazu kommt, wohin
     * ein schon erlaubter Server ausdrücklich weiterleitet (HTTP-Weiterleitung, Fortsetzungsadresse, Formular oder
     * Weiterleitung von OpenAM). Fest eingetragene Anmeldedienste gibt es nicht.
     */
    private final java.util.Set<String> trusted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Die zuletzt aufgerufene Seite der Anmeldung (für {@code Referer}). */
    private volatile URI previous;

    /** Ob {@code target} angesprochen werden darf: die E-Akte oder ein Server, zu dem die Kette geführt hat. */
    boolean allows(URI target) {
        return target != null && target.getHost() != null
                && (profile.isEakte(target) || trusted.contains(key(target)));
    }

    /** Ein erlaubter Server ({@code from}) verweist auf {@code to}: von nun an darf auch {@code to} angesprochen werden. */
    void trust(URI from, URI to) {
        if (allows(from) && to != null && to.getHost() != null
                && ("https".equalsIgnoreCase(to.getScheme()) || "http".equalsIgnoreCase(to.getScheme()))
                && trusted.add(key(to))) {
            trace.accept("Weiterleitung zu " + host(to) + " (von " + host(from) + ")");
        }
    }

    /**
     * Ob Zugangsdaten an {@code target} gehen dürfen: ein Server der Kette, nur über HTTPS (lokal im Test auch HTTP),
     * und nie die E-Akte selbst.
     */
    boolean mayReceiveCredentials(URI target) {
        return allows(target) && !profile.isEakte(target) && EakteProfile.secure(target);
    }

    private static String key(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        int port = uri.getPort() > 0 ? uri.getPort() : "http".equals(scheme) ? 80 : 443;
        return scheme + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    EakteProfile profile() {
        return profile;
    }

    CookieJar cookies() {
        return cookies;
    }

    void trace(String line) {
        trace.accept(line);
    }

    Response get(URI target) throws IOException {
        return navigate("GET", target, Map.of(), null);
    }

    Response postForm(URI target, String body) throws IOException {
        return navigate("POST", target, Map.of("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    /** REST von OpenAM; wie v2.0 ohne Referer, und die nächste Seite danach beginnt ohne. */
    Response postJson(URI target, String json) throws IOException {
        previous = null;
        return send("POST", target, Map.of("Content-Type", "application/json; charset=UTF-8",
                "Accept", "application/json", "Accept-API-Version", "resource=2.0, protocol=1.0"),
                json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Seitenaufrufe der Anmeldung wie in v2.0 ({@code SessionTest.execute}): mit {@code Referer} der vorigen Seite,
     * wenn sie auf demselben Host lag.
     */
    private Response navigate(String method, URI target, Map<String, String> headers, byte[] body)
            throws IOException {
        URI from = previous;
        Map<String, String> all = headers;
        if (from != null && from.getHost() != null && from.getHost().equalsIgnoreCase(target.getHost())) {
            all = new java.util.LinkedHashMap<>(headers);
            all.put("Referer", from.toString());
        }
        Response response = send(method, target, all, body);
        previous = target;
        return response;
    }

    /**
     * Sendet genau eine Anfrage, ohne Weiterleitungen zu folgen. Ziele außerhalb der E-Akte und der Anmeldedienste
     * werden nie angefragt.
     */
    Response send(String method, URI target, Map<String, String> headers, byte[] body) throws IOException {
        if (!allows(target)) {
            throw new IOException("Weiterleitung zu einem fremden Server abgelehnt: " + host(target));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(timeout)
                .header("User-Agent", USER_AGENT);
        if (!headers.containsKey("Accept")) {
            request.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.7");
        }
        headers.forEach(request::header);
        String cookieLine = cookies.header(target);
        if (!cookieLine.isEmpty()) {
            request.header("Cookie", cookieLine);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unterbrochen", e);
        } catch (java.io.UncheckedIOException e) {
            // Die Proxy-Regel der App meldet „kein Netzweg“ so (nie still direkt verbinden).
            throw e.getCause();
        }
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        cookies.store(target, setCookies);
        Response result = new Response(target, response.statusCode(),
                response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("Content-Type").orElse(""),
                response.body() == null ? new byte[0] : response.body());
        if (result.redirect()) {
            trust(target, result.next());
        }
        trace.accept(method + " " + safe(target) + " → HTTP " + result.status()
                + (setCookies.isEmpty() ? "" : " | setzt " + cookieNames(setCookies))
                + (result.redirect() ? " | weiter zu " + safe(result.next()) : ""));
        return result;
    }

    /** Eine gestreamte Antwort ({@code GET} eines Dokuments); der Aufrufer schließt {@code body}. */
    record Stream(URI uri, int status, String location, String contentType, long length, java.io.InputStream body,
                  java.net.http.HttpHeaders headers) {

        boolean redirect() {
            return (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
                    && location != null;
        }

        URI next() {
            return uri.resolve(location.trim().replace(" ", "%20"));
        }

        /** Als gewöhnliche Antwort ohne Inhalt (für Weiterleitungen zur Anmeldung). */
        Response head() {
            return new Response(uri, status, location, contentType, new byte[0]);
        }
    }

    /** {@code GET} mit gestreamtem Inhalt; sonst wie {@link #send}. */
    Stream stream(URI target) throws IOException {
        return stream(target, Map.of());
    }

    /** Wie {@link #stream(URI)}, mit weiteren Kopfzeilen (etwa {@code Range} für das Fortsetzen eines Downloads). */
    Stream stream(URI target, Map<String, String> extra) throws IOException {
        if (!allows(target)) {
            throw new IOException("Weiterleitung zu einem fremden Server abgelehnt: " + host(target));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(timeout)
                .header("User-Agent", USER_AGENT).header("Accept", "*/*").GET();
        String cookieLine = cookies.header(target);
        if (!cookieLine.isEmpty()) {
            request.header("Cookie", cookieLine);
        }
        extra.forEach(request::header);
        HttpResponse<java.io.InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unterbrochen", e);
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        cookies.store(target, setCookies);
        Stream result = new Stream(target, response.statusCode(),
                response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValueAsLong("Content-Length").orElse(-1L), response.body(),
                response.headers());
        if (result.redirect()) {
            trust(target, result.next());
        }
        trace.accept("GET " + safe(target) + " → HTTP " + result.status() + " | " + result.contentType()
                + (result.length() >= 0 ? " | " + result.length() + " Byte" : "")
                + (setCookies.isEmpty() ? "" : " | setzt " + cookieNames(setCookies))
                + (result.redirect() ? " | weiter zu " + safe(result.next()) : ""));
        return result;
    }

    /** Host, Pfad und nur die Namen der Parameter, nie ihre Werte (dort stehen SAML-Daten und Codes). */
    static String safe(URI uri) {
        if (uri == null) {
            return "?";
        }
        StringBuilder text = new StringBuilder();
        text.append(uri.getScheme()).append("://").append(uri.getRawAuthority())
                .append(uri.getRawPath() == null ? "" : uri.getRawPath());
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                names.add(eq < 0 ? part : part.substring(0, eq));
            }
            text.append(" [Parameter: ").append(String.join(", ", names)).append(']');
        }
        return text.toString();
    }

    static String host(URI uri) {
        return uri == null || uri.getHost() == null ? "?" : uri.getHost();
    }

    private static String cookieNames(List<String> setCookies) {
        List<String> names = new ArrayList<>();
        for (String header : setCookies) {
            int eq = header.indexOf('=');
            String name = (eq < 0 ? header : header.substring(0, eq)).trim();
            String lower = header.toLowerCase(Locale.ROOT);
            int path = lower.indexOf("path=");
            String scope = "";
            if (path >= 0) {
                int end = header.indexOf(';', path);
                scope = " (Pfad " + header.substring(path + 5, end < 0 ? header.length() : end).trim() + ")";
            }
            names.add(name + scope);
        }
        return String.join(", ", names);
    }
}
