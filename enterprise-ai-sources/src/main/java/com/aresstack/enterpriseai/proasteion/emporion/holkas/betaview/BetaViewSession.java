package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eine angemeldete BetaView-Sitzung (aus MainframeMate {@code BetaViewHttpClient}/{@code BetaViewSession}): eigener
 * Cookie-Speicher je Sitzung (nie Browser-Cookies), Weiterleitungen von Hand mit Cookies, CSRF-Token an jeder
 * {@code .action}-Anfrage. Nicht thread-sicher; die Quelle serialisiert die Zugriffe.
 */
final class BetaViewSession {

    private static final int MAX_REDIRECT_HOPS = 10;
    private static final long MAX_HTML_BYTES = 32L * 1024L * 1024L;

    private final URI base;
    private final BetaViewTransport transport;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private String csrfToken;

    private BetaViewSession(URI base, BetaViewTransport transport) {
        this.base = base;
        this.transport = transport;
    }

    /**
     * Anmeldung wie im Browser: Login-Seite (Struts-Token und versteckte Felder), POST {@code login.action},
     * danach das CSRF-Token aus {@code getAppContext.action}.
     *
     * @throws BetaViewLoginException wenn der Server die Anmeldung ablehnt
     */
    static BetaViewSession login(URI base, BetaViewTransport transport, BetaViewCredentials credentials) throws IOException {
        BetaViewSession session = new BetaViewSession(base, transport);
        String loginPage = session.getText("login.action");
        Map<String, String> hidden = BetaViewHtml.hiddenInputs(loginPage);
        Map<String, String> form = new LinkedHashMap<String, String>();
        copy(hidden, form, "struts.token.name");
        String tokenName = hidden.get("struts.token.name");
        if (tokenName != null) {
            copy(hidden, form, tokenName);
        }
        copy(hidden, form, "newpasswordmode");
        copy(hidden, form, "ssID");
        copy(hidden, form, "favoriteID");
        copy(hidden, form, "systemID");
        form.put("userName", credentials.username());
        form.put("password", new String(credentials.password()));
        String afterLogin;
        try {
            afterLogin = session.postText("login.action", form);
        } finally {
            form.remove("password");
        }
        if (BetaViewHtml.isLoginPage(afterLogin)) {
            throw new BetaViewLoginException();
        }
        session.csrfToken = BetaViewHtml.csrfToken(session.getText("getAppContext.action"));
        return session;
    }

    String getText(String relative) throws IOException {
        return new String(exchange(relative, null).body(), StandardCharsets.UTF_8);
    }

    String postText(String relative, Map<String, String> fields) throws IOException {
        return new String(post(relative, fields, MAX_HTML_BYTES).body(), StandardCharsets.UTF_8);
    }

    /** POST, dessen Antwort ein Dokument ist; Rumpf und Inhaltstyp. */
    BetaViewTransport.Response postDownload(String relative, Map<String, String> fields, long maxBytes) throws IOException {
        return post(relative, fields, maxBytes);
    }

    /** Die absolute Adresse einer Aktion (für Quellenangaben). */
    URI resolve(String relative) {
        return base.resolve(relative);
    }

    private BetaViewTransport.Response post(String relative, Map<String, String> fields, long maxBytes) throws IOException {
        Map<String, String> effective = new LinkedHashMap<String, String>(fields);
        if (csrfToken != null && !effective.containsKey("csrfToken") && !effective.containsKey("struts.token.name")) {
            effective.put("csrfToken", csrfToken);
        }
        BetaViewTransport.Response response = transport.exchange(base.resolve(relative), cookieHeader(),
                encode(effective), maxBytes);
        store(base.resolve(relative), response);
        return follow(response, maxBytes, relative);
    }

    private BetaViewTransport.Response exchange(String relative, String formBody) throws IOException {
        String path = withCsrf(relative);
        URI uri = base.resolve(path);
        BetaViewTransport.Response response = transport.exchange(uri, cookieHeader(), formBody, MAX_HTML_BYTES);
        store(uri, response);
        return follow(response, MAX_HTML_BYTES, relative);
    }

    private BetaViewTransport.Response follow(BetaViewTransport.Response first, long maxBytes, String relative)
            throws IOException {
        BetaViewTransport.Response current = first;
        int hops = 0;
        while (isRedirect(current.status()) && current.header("Location") != null && hops++ < MAX_REDIRECT_HOPS) {
            URI next = base.resolve(current.header("Location"));
            current = transport.exchange(next, cookieHeader(), null, maxBytes);
            store(next, current);
        }
        if (current.status() / 100 != 2) {
            throw new BetaViewHttpException(current.status(), relative);
        }
        return current;
    }

    private String withCsrf(String relative) {
        if (csrfToken == null || !relative.contains(".action") || relative.contains("csrfToken=")) {
            return relative;
        }
        return relative + (relative.contains("?") ? '&' : '?') + "csrfToken=" + urlEncode(csrfToken);
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(cookie.getName()).append('=').append(cookie.getValue());
        }
        return sb.toString();
    }

    private void store(URI uri, BetaViewTransport.Response response) throws IOException {
        cookies.put(uri, response.headers());
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String encode(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copy(Map<String, String> source, Map<String, String> target, String key) {
        String value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    /** Der Server hat die Anmeldung abgelehnt (wieder die Login-Seite). */
    static final class BetaViewLoginException extends IOException {
        private static final long serialVersionUID = 1L;

        BetaViewLoginException() {
            super("BetaView login rejected");
        }
    }

    /** Antwort mit einem Status außerhalb von 2xx nach allen Weiterleitungen. */
    static final class BetaViewHttpException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        BetaViewHttpException(int status, String action) {
            super("HTTP " + status + " for " + action);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
