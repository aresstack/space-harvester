package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.deck.http.HttpClientFactory;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Der Netzweg der HTTP-Bereiche des Command Deck (BetaView, E-Akte): TLS-Vertrauen aus den Einstellungen der App (unter
 * Windows samt Windows-ROOT), dazu der Proxy der App, solange der Bereich ihn verwenden soll. Gelesen wird bei jeder
 * Verbindung, also gelten geänderte Einstellungen ab der nächsten.
 */
public final class DeckNetwork {

    private DeckNetwork() {
    }

    /** @param useProxy ob der Bereich den Proxy der App nimmt (sonst direkt) */
    public static HttpClientFactory http(AppContext context, BooleanSupplier useProxy) {
        Objects.requireNonNull(context, "context");
        return () -> {
            var settings = context.settings();
            if (settings == null) {
                return java.net.http.HttpClient.newBuilder();
            }
            if (!useProxy.getAsBoolean()) {
                return com.aresstack.spaceharvester.app.nlp.HttpModelDownload.routed(null, settings.tls());
            }
            return com.aresstack.spaceharvester.app.nlp.HttpModelDownload.routed(
                    com.aresstack.spaceharvester.app.runtime.AppRuntime.proxy(settings.proxy(), context.secrets()),
                    settings.tls());
        };
    }
}
