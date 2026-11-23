package com.aresstack.spaceharvester.deck.betaview.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Die HTTP-Ebene einer BetaView-Sitzung, Schritt für Schritt wie {@code BetaViewHttpClient} in MainframeMate:
 * Cookies der Sitzung nach den Regeln von {@code Set-Cookie} (Domain, Path, Secure, Ablauf – nie an einen fremden Host,
 * auch nicht bei einer Weiterleitung), Weiterleitungen von Hand (höchstens zehn, immer als GET),
 * CSRF-Token an jeder GET-{@code .action}-Adresse und als Formularfeld bei POST, sofern der Aufrufer kein
 * Struts-Token schickt. Nur Status 2xx gilt als Erfolg.
 */
final class BetaViewHttp {

    static final int MAX_REDIRECTS = 10;

    private final HttpClient client;
    private final URI base;
    private final Duration timeout;
    /** Cookies nach RFC 6265: nie für fremde Domains angenommen, nie an fremde Hosts gesendet. */
    private final CookieJar cookies = new CookieJar();
    private String csrfToken;

    BetaViewHttp(HttpClient client, URI base, Duration timeout) {
        this.client = client;
        this.base = base;
        this.timeout = timeout;
    }

    /** Antwort nach allen Weiterleitungen. */
    record Response(int status, byte[] body, String location, String contentType) {

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        boolean success() {
            return status / 100 == 2;
        }
    }

    /**
     * Meldet an: Anmeldeseite laden (Struts-Token und versteckte Felder übernehmen), Benutzer und Kennwort senden,
     * Weiterleitungen folgen, dann das CSRF-Token aus {@code getAppContext.action} lesen.
     */
    void login(String user, char[] password) throws IOException {
        String loginPage = getText("login.action");
        Map<String, String> hidden = BetaViewHtml.hiddenInputs(loginPage);
        Map<String, String> form = new LinkedHashMap<>();
        copy(hidden, form, "struts.token.name");
        String tokenName = hidden.get("struts.token.name");
        if (tokenName != null) {
            copy(hidden, form, tokenName);
        }
        for (String key : new String[] {"newpasswordmode", "ssID", "favoriteID", "systemID"}) {
            copy(hidden, form, key);
        }
        form.put("userName", user);
        form.put("password", new String(password));
        Response response = follow(post("login.action", form));
        form.put("password", "");
        if (!response.success()) {
            throw new IOException("Anmeldung abgelehnt: HTTP " + response.status() + where(response));
        }
        boolean backAtLogin = BetaViewHtml.looksLikeLoginForm(response.text());
        String appContext = getText("getAppContext.action");
        csrfToken = BetaViewHtml.csrfToken(appContext).orElse(null);
        // MainframeMate prüft nur den Status. Steht danach wieder die Anmeldemaske da und gibt es kein CSRF-Token,
        // hat der Server die Anmeldung abgelehnt (falsches Kennwort antwortet mit 200 und der Maske).
        if (backAtLogin && csrfToken == null) {
            throw new IOException("Anmeldung abgelehnt: Benutzer oder Kennwort falsch");
        }
    }

    Optional<String> csrfToken() {
        return Optional.ofNullable(csrfToken);
    }

    String getText(String relative) throws IOException {
        return new String(get(relative).body(), StandardCharsets.UTF_8);
    }

    Response get(String relative) throws IOException {
        Response response = follow(send(request(withCsrf(relative)).GET()));
        if (!response.success()) {
            throw new IOException("Server antwortet HTTP " + response.status() + " auf " + action(relative)
                    + where(response));
        }
        return response;
    }

    String postText(String relative, Map<String, String> fields) throws IOException {
        return postResponse(relative, fields).text();
    }

    Response postResponse(String relative, Map<String, String> fields) throws IOException {
        Response response = follow(post(relative, fields));
        if (!response.success()) {
            throw new IOException("Server antwortet HTTP " + response.status() + " auf " + action(relative)
                    + where(response));
        }
        return response;
    }

    private Response post(String relative, Map<String, String> fields) throws IOException {
        Map<String, String> effective = new LinkedHashMap<>(fields);
        if (csrfToken != null && !effective.containsKey("csrfToken")
                && !effective.containsKey("struts.token.name")) {
            effective.put("csrfToken", csrfToken);
        }
        String body = encode(effective);
        return send(request(relative)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private Response follow(Response first) throws IOException {
        Response current = first;
        int hops = 0;
        while (redirect(current.status()) && current.location() != null && hops < MAX_REDIRECTS) {
            hops++;
            current = send(request(current.location()).GET());
        }
        return current;
    }

    private HttpRequest.Builder request(String relativeOrAbsolute) {
        URI target = base.resolve(relativeOrAbsolute);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(timeout);
        String cookieLine = cookieLine(target);
        if (!cookieLine.isEmpty()) {
            builder.header("Cookie", cookieLine);
        }
        return builder;
    }

    private Response send(HttpRequest.Builder request) throws IOException {
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
        storeCookies(response);
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (response.headers().firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT)
                .contains("gzip")) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = in.readAllBytes();
            }
        }
        return new Response(response.statusCode(), body, response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("Content-Type").orElse(""));
    }

    private void storeCookies(HttpResponse<?> response) {
        cookies.store(response.uri(), response.headers().allValues("Set-Cookie"));
    }

    /** Die Cookie-Zeile für {@code target} (nur passende Domain, Pfad, Secure, nicht abgelaufen). */
    String cookieLine(URI target) {
        return cookies.header(target);
    }

    /** Hängt das CSRF-Token an GET-Adressen von {@code .action}, wie das JavaScript von BetaView es tut. */
    private String withCsrf(String relative) {
        if (csrfToken == null || !relative.contains(".action") || relative.contains("csrfToken=")) {
            return relative;
        }
        return relative + (relative.contains("?") ? '&' : '?') + "csrfToken="
                + URLEncoder.encode(csrfToken, StandardCharsets.UTF_8);
    }

    static String encode(Map<String, String> fields) {
        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        });
        return body.toString();
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void copy(Map<String, String> source, Map<String, String> target, String key) {
        String value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    /** Nur der Name der Aktion, ohne Query (keine Tokens ins Protokoll). */
    private static String action(String relative) {
        int query = relative.indexOf('?');
        return query < 0 ? relative : relative.substring(0, query);
    }

    private static String where(Response response) {
        String where = response.location() == null ? "" : " (weitergeleitet nach " + action(response.location()) + ")";
        return serverMessage(response.text()).map(message -> where + ": " + message).orElse(where);
    }

    private static final java.util.regex.Pattern ERRORS =
            java.util.regex.Pattern.compile("\"errors\"\\s*:\\s*\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final java.util.regex.Pattern MESSAGE_TEXT =
            java.util.regex.Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Die Fehlermeldung des Servers aus einer abgelehnten Antwort, etwa {@code {"errors":["Das Layout existiert
     * nicht …"]}} (Struts-JSON-Prüfung) oder {@code {"messages":[{"text":"…"}]}}; HTML-Entities aufgelöst.
     */
    static Optional<String> serverMessage(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        java.util.regex.Matcher matcher = ERRORS.matcher(body);
        if (!matcher.find()) {
            matcher = MESSAGE_TEXT.matcher(body);
            if (!matcher.find()) {
                return Optional.empty();
            }
        }
        String text = matcher.group(1).replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ");
        text = org.jsoup.parser.Parser.unescapeEntities(text, false).strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
