package com.aresstack.spaceharvester.deck.eakte.http;

import com.aresstack.spaceharvester.deck.eakte.LoginRefusedException;
import com.aresstack.spaceharvester.deck.eakte.SessionExpiredException;
import java.io.IOException;
import java.net.URI;
import org.jsoup.nodes.Document;

/**
 * Führt eine Antwort der E-Akte, die zur Anmeldung umleitet, durch die SAML-Einmalanmeldung, bis die E-Akte wieder
 * selbst antwortet. Ablauf wie im Testprogramm von Angelo (Schritte [1] bis [7], 23.11.2026):
 * <ol>
 *   <li>Die E-Akte leitet mit {@code SAMLRequest} zu OpenAM ({@code SSORedirect}).</li>
 *   <li>Ohne OpenAM-Sitzung zeigt OpenAM die Zwischenseite „Access rights validated“; dann meldet {@link OpenAmLogin}
 *       an (Kennwort höchstens einmal) und es geht mit {@code secondVisitUrl} weiter.</li>
 *   <li>Mit Sitzung liefert OpenAM das selbstabschickende Formular mit {@code SAMLResponse}; es geht per POST an die
 *       E-Akte, die Cookies setzt und zur ursprünglichen Seite zurückleitet.</li>
 * </ol>
 * Ohne {@link OpenAmLogin} (nach dem Anmelden) wird nie ein Kennwort gesendet: Verlangt OpenAM eine neue Anmeldung,
 * meldet der Navigator {@link SessionExpiredException}.
 */
final class SamlNavigator {

    private static final int MAX_STEPS = 30;
    private static final int MAX_SAML_POSTS = 3;

    private final SsoHttp http;
    private final OpenAmLogin login;

    /** @param login Anmeldung mit Kennwort, oder {@code null}: nur mit bestehender OpenAM-Sitzung weiter */
    SamlNavigator(SsoHttp http, OpenAmLogin login) {
        this.http = http;
        this.login = login;
    }

    /** Ob {@code response} von der E-Akte zur Anmeldung umleitet. */
    boolean leadsToLogin(SsoHttp.Response response) {
        return response.redirect() && !http.profile().isEakte(response.next());
    }

    /** Folgt {@code response}, bis die E-Akte selbst ohne Weiterleitung antwortet, und liefert diese Antwort. */
    SsoHttp.Response settle(SsoHttp.Response response) throws IOException {
        SsoHttp.Response current = response;
        URI continuation = null;
        int samlPosts = 0;
        int transitions = 0;
        boolean triedSession = false;
        for (int step = 1; step <= MAX_STEPS; step++) {
            if (current.redirect()) {
                current = http.get(current.next());
                continue;
            }
            if (http.profile().isEakte(current.uri())) {
                return current;
            }
            if (current.status() >= 400 || !current.html()) {
                throw new IOException("Anmeldedienst " + SsoHttp.host(current.uri()) + " antwortet HTTP "
                        + current.status() + " (" + current.contentType() + ")");
            }
            Document page = SsoPages.parse(current);
            var saml = SsoPages.samlResponse(page);
            if (saml.isPresent()) {
                if (++samlPosts > MAX_SAML_POSTS) {
                    throw new IOException("Mehr als drei SAML-Antworten hintereinander (Schleifenschutz)");
                }
                URI acs = saml.get().action();
                if (!http.profile().isEakte(acs)) {
                    throw new IOException("SAML-Antwort zielt nicht auf die E-Akte, sondern auf " + SsoHttp.host(acs));
                }
                http.trace("SAML-Antwort gefunden; übermittle SAMLResponse und RelayState an die E-Akte");
                current = http.postForm(acs, saml.get().encode(null, null));
                continue;
            }
            var transition = SsoPages.transition(page);
            if (transition.isPresent()) {
                if (++transitions > 3) {
                    throw new IOException("OpenAM zeigt die Zwischenseite immer wieder (Schleifenschutz)");
                }
                if (transition.get().secondVisit() != null) {
                    continuation = transition.get().secondVisit();
                }
                if (!hasSession(current.uri()) || triedSession) {
                    // Ohne Sitzungscookie, oder OpenAM kennt es nicht mehr (Zwischenseite trotz Cookie).
                    authenticate(transition.get().login() != null ? transition.get().login() : current.uri());
                }
                triedSession = true;
                if (continuation == null) {
                    throw new IOException("OpenAM-Zwischenseite ohne secondVisitUrl");
                }
                http.trace("OpenAM-Sitzung vorhanden; hole die SAML-Antwort: " + SsoHttp.safe(continuation));
                current = http.get(continuation);
                continue;
            }
            if (!hasSession(current.uri()) && continuation != null) {
                // JavaScript-Oberfläche von OpenAM (XUI) ohne Formular: über REST anmelden, dann weiter.
                authenticate(current.uri());
                current = http.get(continuation);
                continue;
            }
            throw new IOException("Unerwartete Seite „" + SsoPages.title(page) + "“ auf "
                    + SsoHttp.host(current.uri()));
        }
        throw new IOException("Anmeldung: zu viele Weiterleitungen");
    }

    private boolean hasSession(URI idp) {
        return http.cookies().has(idp, OpenAmLogin.SESSION_COOKIE);
    }

    private void authenticate(URI idpPage) throws IOException {
        if (login == null) {
            throw new SessionExpiredException("Die Anmeldung an der E-Akte ist abgelaufen; bitte neu verbinden");
        }
        if (login.done()) {
            throw new LoginRefusedException("OpenAM verlangt nach erfolgreicher Anmeldung erneut eine Anmeldung");
        }
        login.authenticate(idpPage);
    }
}
