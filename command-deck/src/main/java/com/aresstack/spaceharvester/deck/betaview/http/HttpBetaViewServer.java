package com.aresstack.spaceharvester.deck.betaview.http;

import com.aresstack.spaceharvester.deck.betaview.BetaViewConnection;
import com.aresstack.spaceharvester.deck.betaview.BetaViewProfile;
import com.aresstack.spaceharvester.deck.betaview.BetaViewServer;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/**
 * Adapter zum BetaView-Webserver über HTTP, übernommen aus MainframeMate ({@code betaview-integration}, dort gegen den
 * echten Server erprobt): Struts-Formulare mit Token, CSRF-Token aus {@code getAppContext.action}, Cookies der
 * Sitzung, Weiterleitungen von Hand. Die HTTP-Verbindungen baut {@link java.net.http.HttpClient} statt
 * {@code HttpURLConnection}, damit Proxy und TLS der App gelten.
 */
public final class HttpBetaViewServer implements BetaViewServer {

    /** Höchstens so lange auf eine Antwort warten. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClientFactory clients;
    private final Duration timeout;

    public HttpBetaViewServer(HttpClientFactory clients) {
        this(clients, TIMEOUT);
    }

    public HttpBetaViewServer(HttpClientFactory clients, Duration timeout) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public BetaViewConnection login(BetaViewProfile profile, char[] password) throws IOException {
        HttpClient client = clients.builder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        BetaViewHttp http = new BetaViewHttp(client, profile.address(), timeout);
        http.login(profile.user(), password);
        return new HttpBetaViewConnection(http);
    }
}
