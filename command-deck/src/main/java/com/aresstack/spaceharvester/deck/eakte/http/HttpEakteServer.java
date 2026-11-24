package com.aresstack.spaceharvester.deck.eakte.http;

import com.aresstack.spaceharvester.deck.eakte.EakteConnection;
import com.aresstack.spaceharvester.deck.eakte.EakteProfile;
import com.aresstack.spaceharvester.deck.eakte.EakteServer;
import com.aresstack.spaceharvester.deck.http.HttpClientFactory;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapter zur E-Akte (Fabasoft) über HTTP, nach dem Ablauf, der im Testprogramm von Angelo (v2.0, 23.11.2026) auf dem
 * echten System durchlief: Startseite laden, SAML-Einmalanmeldung über OpenAM (REST) und den Identitätsdienst, dann
 * WebDAV mit allen passenden Cookies in EINER {@code Cookie}-Zeile (RFC 6265 5.4; mehrere Zeilen, wie in v1, lehnte die
 * E-Akte mit 302 zur Anmeldung ab). Proxy und TLS-Vertrauen kommen aus {@link HttpClientFactory}, also aus der App.
 */
public final class HttpEakteServer implements EakteServer {

    private final HttpClientFactory clients;
    private final Duration timeout;
    private final Consumer<String> trace;

    /**
     * @param trace Protokoll ohne Werte (Methode, Host, Pfad, Namen von Parametern und Cookies), für die Diagnose
     */
    public HttpEakteServer(HttpClientFactory clients, Duration timeout, Consumer<String> trace) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.trace = trace == null ? line -> { } : trace;
    }

    @Override
    public EakteConnection connect(EakteProfile profile, char[] password) throws IOException {
        Objects.requireNonNull(profile, "profile");
        HttpClient client = clients.builder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        SsoHttp http = new SsoHttp(client, profile, timeout, trace);
        OpenAmLogin login = new OpenAmLogin(http, profile.user(), password);
        try {
            SamlNavigator withPassword = new SamlNavigator(http, login);
            if (profile.start() != null) {
                http.trace("Anmeldung über die Startseite: " + SsoHttp.safe(profile.start()));
                SsoHttp.Response start = withPassword.settle(http.get(profile.start()));
                if (start.status() >= 400) {
                    throw new IOException("Die E-Akte antwortet nach der Anmeldung HTTP " + start.status());
                }
            }
            SamlNavigator sessionOnly = new SamlNavigator(http, null);
            HttpEakteConnection connection = new HttpEakteConnection(http, sessionOnly);
            // v2.0: nach der Anmeldung über die Startseite kein zweiter Passwort- oder REST-Versuch für WebDAV.
            connection.check(profile.start() != null ? sessionOnly : withPassword);
            return connection;
        } finally {
            login.forget();
        }
    }
}
