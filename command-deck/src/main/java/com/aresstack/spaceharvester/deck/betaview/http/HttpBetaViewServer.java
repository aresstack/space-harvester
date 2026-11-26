package com.aresstack.spaceharvester.deck.betaview.http;

import com.aresstack.spaceharvester.deck.betaview.BetaViewConnection;
import com.aresstack.spaceharvester.deck.betaview.BetaViewProfile;
import com.aresstack.spaceharvester.deck.betaview.BetaViewServer;
import com.aresstack.spaceharvester.deck.http.HttpClientFactory;
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

    /** Vorgabe: höchstens so lange auf eine Antwort warten; die App stellt die Frist ein ({@code DeckTimeouts}). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClientFactory clients;
    private final java.util.function.Supplier<Duration> timeout;

    public HttpBetaViewServer(HttpClientFactory clients) {
        this(clients, DEFAULT_TIMEOUT);
    }

    public HttpBetaViewServer(HttpClientFactory clients, Duration timeout) {
        this(clients, constant(timeout));
    }

    /** @param timeout die Frist, bei jeder Anmeldung neu gelesen */
    public HttpBetaViewServer(HttpClientFactory clients, java.util.function.Supplier<Duration> timeout) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    private static java.util.function.Supplier<Duration> constant(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return () -> timeout;
    }

    @Override
    public BetaViewConnection login(BetaViewProfile profile, char[] password) throws IOException {
        Duration timeout = this.timeout.get();
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
