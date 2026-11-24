package com.aresstack.spaceharvester.deck.eakte.http;

import com.aresstack.spaceharvester.deck.eakte.LoginRefusedException;
import com.fasterxml.jackson.jr.ob.JSON;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jsoup.nodes.Document;

/**
 * Anmeldung an OpenAM über dessen REST-Schnittstelle ({@code /json/authenticate}), so wie das Testprogramm von Angelo
 * sie auf dem echten System erfolgreich durchlaufen hat (23.11.2026):
 * <ol>
 *   <li>{@code POST …/json/authenticate?realm=…} mit {@code {}}.</li>
 *   <li>OpenAM antwortet mit einem {@code RedirectCallback} zum Identitätsdienst (OAuth). Dessen Weiterleitungen führen
 *       zum Anmeldeformular; Benutzer und Kennwort gehen genau einmal dorthin.</li>
 *   <li>Der Identitätsdienst leitet mit {@code code}/{@code state} zu OpenAM zurück. Dann wird der ursprüngliche
 *       REST-Schritt ({@code authId} und Callbacks) mit diesen Parametern fortgesetzt; die Antwort trägt
 *       {@code tokenId}, das als Sitzungscookie {@value #SESSION_COOKIE} hinterlegt wird.</li>
 * </ol>
 * Fragt OpenAM stattdessen direkt nach Benutzer und Kennwort ({@code NameCallback}/{@code PasswordCallback}), werden
 * diese beantwortet, ebenfalls höchstens einmal. Zugangsdaten gehen nur an Hosts aus
 * {@link com.aresstack.spaceharvester.deck.eakte.EakteProfile#loginHosts()}.
 *
 * <p>Nach dem Identitätsdienst geht es wie in v2.0 weiter: {@code /openam/?code…} liefert die feste Startseite
 * „Please Wait While Redirecting to Login page“, deren Skript {@code UI/Login} mit derselben Abfrage lädt
 * ({@link #landingRedirect}); das leitet zu {@code XUI/}, und dort wird der REST-Schritt fortgesetzt.
 */
final class OpenAmLogin {

    /** Name des OpenAM-Sitzungscookies (Standard). */
    static final String SESSION_COOKIE = "iPlanetDirectoryPro";

    private static final int MAX_STEPS = 25;

    private final SsoHttp http;
    private final String user;
    private final char[] password;
    private boolean credentialsSent;
    private boolean done;
    /** Endeten die Weiterleitungen schon bei der E-Akte, deren Antwort (v2.0: {@code returnedToFabasoft}). */
    private SsoHttp.Response reachedEakte;
    private int samlForms;

    OpenAmLogin(SsoHttp http, String user, char[] password) {
        this.http = http;
        this.user = user;
        this.password = password.clone();
    }

    /** Die Antwort der E-Akte, wenn die Anmeldung schon dort ankam; einmalig. */
    SsoHttp.Response takeReachedEakte() {
        SsoHttp.Response reached = reachedEakte;
        reachedEakte = null;
        return reached;
    }

    boolean done() {
        return done;
    }

    /** Überschreibt die Kopie des Kennworts. */
    void forget() {
        Arrays.fill(password, '\0');
    }

    /**
     * Meldet an OpenAM an. {@code idpPage} ist eine OpenAM-Adresse mit dem Realm (etwa {@code loginUrl} der
     * Zwischenseite); daraus ergeben sich Kontextpfad und Realm.
     */
    void authenticate(URI idpPage) throws IOException {
        if (done) {
            return;
        }
        if (credentialsSent) {
            throw new LoginRefusedException("OpenAM verlangt erneut eine Anmeldung; Kennwort wird nicht noch einmal "
                    + "gesendet");
        }
        String realm = query(idpPage, "realm");
        URI api = URI.create(origin(idpPage) + context(idpPage) + "/json/authenticate?realm="
                + URLEncoder.encode(realm == null || realm.isBlank() ? "/" : realm, StandardCharsets.UTF_8));
        http.trace("OpenAM-Anmeldung über REST: " + SsoHttp.safe(api));
        String request = "{}";
        for (int step = 1; step <= 4; step++) {
            SsoHttp.Response response = http.postJson(api, request);
            Map<String, Object> answer = json(response, credentialsSent);
            if (finish(api, answer)) {
                return;
            }
            List<?> callbacks = answer.get("callbacks") instanceof List<?> list ? list : List.of();
            if (callbacks.isEmpty()) {
                throw new IOException("OpenAM liefert weder Sitzung noch Anmeldeschritt");
            }
            boolean filled = false;
            for (Object raw : callbacks) {
                Map<String, Object> callback = map(raw);
                String type = String.valueOf(callback.get("type"));
                switch (type) {
                    case "RedirectCallback" -> {
                        followRedirect(api, answer, callback);
                        return;
                    }
                    case "NameCallback" -> filled |= input(callback, user);
                    case "PasswordCallback" -> filled |= input(callback, new String(password));
                    case "TextOutputCallback", "ConfirmationCallback" -> {
                        // nur Hinweise des Servers; nichts zu beantworten
                    }
                    default -> throw new IOException("OpenAM verlangt einen nicht unterstützten Anmeldeschritt: "
                            + type.replaceAll("[^A-Za-z0-9_]", "_"));
                }
            }
            if (!filled) {
                throw new IOException("OpenAM fragt nichts, was sich beantworten ließe");
            }
            if (credentialsSent) {
                throw new LoginRefusedException("OpenAM fragt erneut nach Benutzer und Kennwort: Anmeldung abgelehnt");
            }
            if (!http.profile().mayReceiveCredentials(api)) {
                throw new IOException("Zugangsdaten nicht gesendet: " + SsoHttp.host(api)
                        + " ist kein zugelassener Anmeldedienst");
            }
            credentialsSent = true;
            http.trace("Beantworte Benutzer und Kennwort einmalig (Werte verborgen)");
            request = JSON.std.asString(answer);
        }
        throw new IOException("OpenAM: zu viele Anmeldeschritte");
    }

    /** Folgt dem OAuth-Aufruf zum Identitätsdienst bis zur Rückkehr mit {@code code}/{@code state}. */
    private void followRedirect(URI api, Map<String, Object> step, Map<String, Object> callback) throws IOException {
        String target = "";
        String method = "GET";
        if (callback.get("output") instanceof List<?> outputs) {
            for (Object raw : outputs) {
                Map<String, Object> output = map(raw);
                if ("redirectUrl".equals(output.get("name"))) {
                    target = String.valueOf(output.get("value"));
                } else if ("redirectMethod".equals(output.get("name")) && output.get("value") != null) {
                    method = String.valueOf(output.get("value"));
                }
            }
        }
        if (target.isBlank() || !"GET".equalsIgnoreCase(method)) {
            throw new IOException("OpenAM: Weiterleitung zum Identitätsdienst ohne Ziel oder nicht per GET");
        }
        URI current = api.resolve(target);
        http.trace("OpenAM leitet zum Identitätsdienst: " + SsoHttp.safe(current));
        SsoHttp.Response response = http.get(current);
        for (int step2 = 1; step2 <= MAX_STEPS; step2++) {
            if (response.redirect()) {
                response = http.get(response.next());
                continue;
            }
            // Wie das Testprogramm v2.0: erst auf /openam/XUI/ mit code/state den REST-Schritt fortsetzen.
            if (returnsToOpenAm(api, response.uri())) {
                resume(api, step, response.uri());
                return;
            }
            if (response.status() >= 400) {
                throw credentialsSent
                        ? new LoginRefusedException("Identitätsdienst lehnt die Anmeldung ab: HTTP " + response.status())
                        : new IOException("Identitätsdienst antwortet HTTP " + response.status());
            }
            // v2.0: Die Weiterleitungen enden schon bei der E-Akte; dann gleich weiter mit WebDAV.
            if (http.profile().isEakte(response.uri()) && response.status() == 200) {
                http.trace("Rückkehr zur E-Akte schon während der Anmeldung");
                reachedEakte = response;
                done = true;
                return;
            }
            if (!response.html()) {
                throw new IOException("Identitätsdienst liefert keine Seite (" + response.contentType() + ")");
            }
            Document page = SsoPages.parse(response);
            URI scripted = landingRedirect(api, response.uri(), page);
            if (scripted != null) {
                http.trace("OpenAM-Startseite: dokumentierte JavaScript-Weiterleitung erkannt; übernehme die "
                        + "Rückgabeparameter unverändert an " + SsoHttp.safe(scripted) + " (Werte verborgen)");
                response = http.get(scripted);
                continue;
            }
            // v2.0: ein vom Server geliefertes SAML-Formular unterwegs (höchstens drei) einfach übermitteln.
            var saml = SsoPages.samlResponse(page);
            if (saml.isPresent() && samlForms++ < 3) {
                URI acs = saml.get().action();
                if (!http.profile().allows(acs)) {
                    throw new IOException("SAML-Formular verweist auf einen fremden Server: " + SsoHttp.host(acs));
                }
                http.trace("Übermittle vom Server geliefertes SAML-Formular");
                response = http.postForm(acs, saml.get().encode(null, null));
                continue;
            }
            var form = SsoPages.login(page);
            if (form.isEmpty() && http.cookies().has(api, SESSION_COOKIE)
                    && origin(response.uri()).equalsIgnoreCase(origin(api))) {
                // v2.0: zurück bei OpenAM und dessen Sitzungscookie ist da.
                http.trace("OpenAM-Sitzungscookie nach der Rückkehr vom Identitätsdienst vorhanden");
                done = true;
                return;
            }
            if (form.isEmpty()) {
                throw new IOException("Unerwartete Seite des Identitätsdienstes: „" + SsoPages.title(page) + "“ auf "
                        + SsoHttp.host(response.uri()));
            }
            if (credentialsSent) {
                throw new LoginRefusedException("Anmeldung abgelehnt: Der Identitätsdienst zeigt das Anmeldeformular "
                        + "erneut (Benutzer oder Kennwort falsch, abgelaufen oder gesperrt)");
            }
            URI action = form.get().action();
            if (!"POST".equals(form.get().method()) || !http.profile().mayReceiveCredentials(action)) {
                throw new IOException("Zugangsdaten nicht gesendet: Das Anmeldeformular zielt auf "
                        + SsoHttp.host(action) + " oder nicht per POST");
            }
            http.trace("Anmeldeformular erkannt (Felder " + String.join(", ", form.get().fields().keySet())
                    + "); sende Zugangsdaten einmalig (Werte verborgen)");
            credentialsSent = true;
            response = http.postForm(action, form.get().encode(user, password));
        }
        throw new IOException("Identitätsdienst: zu viele Weiterleitungen");
    }

    /** Setzt den ursprünglichen REST-Schritt mit {@code code}, {@code state} und {@code iss} fort. */
    private void resume(URI api, Map<String, Object> step, URI callback) throws IOException {
        StringBuilder address = new StringBuilder(api.toString());
        for (String name : new String[] {"code", "state", "iss"}) {
            String value = query(callback, name);
            if (value != null && !value.isEmpty()) {
                address.append('&').append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        URI resume = URI.create(address.toString());
        http.trace("Rückkehr vom Identitätsdienst; setze die OpenAM-Anmeldung fort: " + SsoHttp.safe(resume));
        Map<String, Object> answer = json(http.postJson(resume, JSON.std.asString(step)), credentialsSent);
        if (!finish(resume, answer)) {
            throw new IOException("OpenAM hat nach der Rückkehr vom Identitätsdienst keine Sitzung ausgestellt");
        }
    }

    private boolean finish(URI api, Map<String, Object> answer) throws IOException {
        Object token = answer.get("tokenId");
        if (token instanceof String text && !text.isEmpty()) {
            http.cookies().store(api, List.of(SESSION_COOKIE + "=" + text + "; Path=/"
                    + ("https".equalsIgnoreCase(api.getScheme()) ? "; Secure" : "")));
            http.trace("OpenAM-Sitzung erstellt (Wert verborgen)");
            done = true;
            return true;
        }
        if (!answer.containsKey("callbacks") && answer.containsKey("successUrl")
                && http.cookies().has(api, SESSION_COOKIE)) {
            http.trace("OpenAM-Sitzung erstellt (Cookie vom Server)");
            done = true;
            return true;
        }
        return false;
    }

    private static Map<String, Object> json(SsoHttp.Response response, boolean credentialsSent) throws IOException {
        if ((response.status() == 401 || response.status() == 403) && credentialsSent) {
            throw new LoginRefusedException("OpenAM lehnt die Anmeldung ab: HTTP " + response.status());
        }
        if (response.status() != 200 || !response.json()) {
            throw new IOException("OpenAM antwortet HTTP " + response.status() + " (" + response.contentType() + ")");
        }
        try {
            return JSON.std.mapFrom(response.text());
        } catch (IOException e) {
            throw new IOException("OpenAM liefert unlesbares JSON", e);
        }
    }

    /** Die Rückkehr vom Identitätsdienst, wie v2.0 sie erkennt: {@code <Kontext>/XUI/} bei OpenAM mit code/state. */
    private boolean returnsToOpenAm(URI api, URI target) {
        return origin(target).equalsIgnoreCase(origin(api))
                && (context(api) + "/XUI/").equals(target.getRawPath())
                && query(target, "code") != null && query(target, "state") != null;
    }

    /**
     * Die feste Startseite von OpenAM nach der Rückkehr vom Identitätsdienst („Please Wait While Redirecting to Login
     * page“), wie in v2.0 ({@code OpenAmJavascriptRedirect}): nur genau diese Vorlage wird gedeutet, kein JavaScript
     * ausgeführt. Ihr Skript lädt {@code UI/Login} mit derselben Abfrage; das Ergebnis ist diese Adresse oder
     * {@code null}.
     */
    static URI landingRedirect(URI api, URI current, Document page) {
        if (!origin(current).equalsIgnoreCase(origin(api)) || !(context(api) + "/").equals(current.getRawPath())
                || !"Please Wait While Redirecting to Login page".equalsIgnoreCase(SsoPages.title(page).strip())) {
            return null;
        }
        String html = page.html();
        if (!html.contains("redirectToAuth") || !html.contains("getQueryParameters")
                || !html.contains("top.location.replace(url)")
                || !html.matches("(?s).*\\bvar\\s+url\\s*=\\s*['\"]UI/Login['\"].*")) {
            return null;
        }
        String rawQuery = current.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty() || isBlank(query(current, "code"))
                || isBlank(query(current, "state"))) {
            return null;
        }
        return URI.create(origin(current) + context(api) + "/UI/Login?" + rawQuery);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) throws IOException {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IOException("OpenAM liefert ein unbekanntes Format");
    }

    private static boolean input(Map<String, Object> callback, String value) throws IOException {
        if (callback.get("input") instanceof List<?> inputs && !inputs.isEmpty()) {
            map(inputs.get(0)).put("value", value);
            return true;
        }
        return false;
    }

    /**
     * Schema, Host und Port ohne Standardport: {@code https://host:443} und {@code https://host} sind derselbe Server.
     * (Die Zwischenseite nennt {@code loginUrl} mit {@code :443}, der Identitätsdienst leitet ohne Port zurück; v2.0
     * baut die REST-Adresse deshalb ohne Port.)
     */
    static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        int port = uri.getPort();
        boolean standard = port < 0 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return scheme + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + (standard ? "" : ":" + port);
    }

    /** Der Kontextpfad von OpenAM: der erste Abschnitt des Pfads, etwa {@code /openam}. */
    static String context(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        int next = path.indexOf('/', 1);
        return path.length() <= 1 ? "" : next < 0 ? path : path.substring(0, next);
    }

    static String query(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
