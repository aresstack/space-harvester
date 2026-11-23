package com.aresstack.spaceharvester.deck.http;

import java.net.http.HttpClient;

/**
 * Liefert einen vorbereiteten {@link HttpClient.Builder}: Die App setzt darin Proxy-Regel (auch PAC), Anmeldung am
 * Proxy und TLS-Vertrauen, damit BetaView und die E-Akte denselben Netzweg nehmen wie die Quellen. Weiterleitungen,
 * Zeitlimit und HTTP-Version legt der Adapter selbst fest.
 */
@FunctionalInterface
public interface HttpClientFactory {

    HttpClient.Builder builder();

    /** Ohne Proxy und mit dem Standardvertrauen der JVM (Tests, Schein-Server). */
    static HttpClientFactory direct() {
        return HttpClient::newBuilder;
    }
}
